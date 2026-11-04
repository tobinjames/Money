package com.example.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Receives incoming SMS and hands each message to [SmsProcessWorker].
 *
 * The receiver itself does no network or database work: Android may kill the process as soon as
 * onReceive returns, so a coroutine launched here could be lost. WorkManager keeps the job until
 * it has run.
 */
class SmsReceiver : BroadcastReceiver() {
    private val TAG = "SmsReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Check if automatic SMS Sync is enabled by the user
        val prefs = context.getSharedPreferences("sms_auto_sync_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_sync_enabled", true)) {
            Log.d(TAG, "Automatic SMS sync is disabled by the user. Ignoring incoming SMS.")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "Empty SMS intent content.")
            return
        }

        // Group multi-part SMS messages by address
        val smsGroups = messages.groupBy { it.displayOriginatingAddress ?: "Unknown" }
        for ((sender, multipartMsgs) in smsGroups) {
            val fullBody = multipartMsgs.joinToString("") { it.displayMessageBody ?: "" }
            val timestamp = multipartMsgs.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
            SmsProcessWorker.enqueue(context.applicationContext, sender, fullBody, timestamp)
        }
    }
}
