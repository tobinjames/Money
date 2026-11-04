package com.example.sms

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.FirebaseService

/**
 * Processes one incoming SMS in the background.
 *
 * Jobs are chained under one unique name, so messages are handled one at a time and in order.
 * That keeps the duplicate and transfer checks in [SmsProcessor] from racing each other.
 */
class SmsProcessWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sender = inputData.getString(KEY_SENDER) ?: return Result.success()
        val body = inputData.getString(KEY_BODY) ?: return Result.success()
        val timestamp = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())

        FirebaseService.initialize(applicationContext)
        if (!FirebaseService.hasSignedInUser()) {
            // Firestore rules need a signed-in member. The message stays in the inbox and is picked
            // up by the inbox reconciliation the next time the app is opened.
            Log.w(TAG, "No signed-in user; skipping SMS until the app is opened.")
            return Result.success()
        }

        return try {
            SmsProcessor.processSms(applicationContext, sender, body, timestamp)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "SMS processing failed: ${e.message}", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "SmsProcessWorker"
        private const val UNIQUE_CHAIN = "sms_process_chain"
        private const val KEY_SENDER = "sender"
        private const val KEY_BODY = "body"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val MAX_ATTEMPTS = 3

        fun enqueue(context: Context, sender: String, body: String, timestamp: Long) {
            val request = OneTimeWorkRequestBuilder<SmsProcessWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SENDER to sender,
                        KEY_BODY to body,
                        KEY_TIMESTAMP to timestamp
                    )
                )
                // Gemini parsing needs the network; the local regex parser is only a fallback.
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_CHAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
