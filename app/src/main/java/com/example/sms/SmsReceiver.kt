package com.example.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.example.data.FirebaseService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    private val TAG = "SmsReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            Log.d(TAG, "SMS Broadcast received, extracting messages...")
            
            // Ensure Firebase is initialized for background DB modifications
            FirebaseService.initialize(context.applicationContext)
            
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
                
                // Check if automatic SMS Sync is enabled by the user
                val prefs = context.getSharedPreferences("sms_auto_sync_prefs", Context.MODE_PRIVATE)
                val isAutoSyncEnabled = prefs.getBoolean("auto_sync_enabled", true)
                if (!isAutoSyncEnabled) {
                    Log.d(TAG, "SmsReceiver: Automatic SMS Syncing is disabled by user. Ignoring incoming SMS from $sender.")
                    continue
                }

                // Launch asynchronous transaction pipeline
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        SmsProcessor.processSms(context.applicationContext, sender, fullBody, timestamp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception running background processor loop: ${e.message}", e)
                    }
                }
            }
        }
    }
}
