package com.example.sms

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.FirebaseService
import kotlinx.coroutines.flow.first

class SmsBackfillWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("SmsBackfillWorker", "SMS historical backfill background job started.")
        val context = applicationContext
        
        if (context.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w("SmsBackfillWorker", "READ_SMS permission is not granted. Skipping SMS backfill.")
            return Result.success()
        }

        FirebaseService.initialize(context)
        if (!FirebaseService.hasSignedInUser()) {
            Log.w("SmsBackfillWorker", "No signed-in user; skipping SMS backfill.")
            return Result.success()
        }

        try {
            if (!FirebaseService.ensureCoreDataLoaded()) {
                Log.w("SmsBackfillWorker", "Household data not loaded; will retry.")
                return if (runAttemptCount < 3) Result.retry() else Result.success()
            }
            val accountId = inputData.getString("accountId")
            val senderId = inputData.getString("senderId")
            
            val accounts = FirebaseService.getAccounts().first()
            val whitelist = FirebaseService.getWhitelistedSenders().first()
            val privacyKeywords = FirebaseService.getPrivacyKeywords().first()
            
            var startDate: Long = 0L
            var targetSenders = whitelist.map { it.senderId }

            if (accountId != null) {
                val targetAcc = accounts.find { it.accountId == accountId }
                if (targetAcc != null) {
                    startDate = targetAcc.asOfDate
                    Log.d("SmsBackfillWorker", "Running backfill for Account: ${targetAcc.accountName}, starting from ${targetAcc.asOfDate}")
                } else {
                    Log.w("SmsBackfillWorker", "Account with ID $accountId not found. Skipping.")
                    return Result.success()
                }
            } else if (senderId != null) {
                targetSenders = listOf(senderId)
                // Search the database for the absolute oldest asOfDate among all active accounts (Asset/Liability)
                val activeAccounts = accounts.filter { it.accountType == "ASSET" || it.accountType == "LIABILITY" }
                if (activeAccounts.isNotEmpty()) {
                    startDate = activeAccounts.minOf { it.asOfDate }
                } else {
                    startDate = 0L
                }
                Log.d("SmsBackfillWorker", "Running backfill for Whitelisted Sender: $senderId, oldest asOfDate: $startDate")
            } else {
                // Default fallback: oldest asOfDate of all active accounts
                val activeAccounts = accounts.filter { it.accountType == "ASSET" || it.accountType == "LIABILITY" }
                if (activeAccounts.isNotEmpty()) {
                    startDate = activeAccounts.minOf { it.asOfDate }
                }
                Log.d("SmsBackfillWorker", "Running default backfill, starting from oldest asOfDate: $startDate")
            }

            if (targetSenders.isEmpty()) {
                Log.d("SmsBackfillWorker", "No whitelisted senders found. Backfill completed.")
                return Result.success()
            }

            // Query user Android SMS Inbox
            val uri = Uri.parse("content://sms/inbox")
            val cursor = context.contentResolver.query(
                uri,
                arrayOf("address", "body", "date"),
                "date >= ?",
                arrayOf(startDate.toString()),
                "date DESC"
            )

            if (cursor != null) {
                var count = 0
                val existingTxs = FirebaseService.getTransactions().first()

                while (cursor.moveToNext()) {
                    val sender = cursor.getString(0) ?: ""
                    val body = cursor.getString(1) ?: ""
                    val dateMillis = cursor.getLong(2)

                    // Sender match: check if matches targetSenders
                    val isMatchedSender = targetSenders.any { s ->
                        sender.lowercase().contains(s.lowercase().trim())
                    }
                    if (!isMatchedSender) continue

                    // Content privacy check
                    val hasPrivacyKeyword = privacyKeywords.any { 
                        body.lowercase().contains(it.keyword.lowercase().trim()) 
                    }
                    if (hasPrivacyKeyword) continue

                    // Duplicate check
                    val isDuplicate = existingTxs.any { tx ->
                        tx.timestamp == dateMillis && (tx.originalSmsText == body || tx.originalSmsText?.contains(body) == true || body.contains(tx.originalSmsText ?: "_"))
                    }
                    
                    if (!isDuplicate) {
                        Log.d("SmsBackfillWorker", "Discovered valid historical SMS from $sender received at $dateMillis. Backfilling transaction...")
                        SmsProcessor.processSms(context, sender, body, dateMillis)
                        count++
                    }
                }
                cursor.close()
                Log.d("SmsBackfillWorker", "SMS Backfill complete. Enqueued/Backfilled $count historical messages.")
            }
        } catch (e: Exception) {
            Log.e("SmsBackfillWorker", "Error during backfill operation: ${e.message}", e)
            return Result.failure()
        }

        return Result.success()
    }
}
