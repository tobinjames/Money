package com.example.ai

import com.google.firebase.firestore.FirebaseFirestore
import android.util.Log
import java.util.UUID

data class GeminiInteractionLog(
    val logId: String = "",
    val rawSmsContent: String = "",
    val geminiJsonOutput: String = "",
    val timestamp: Long = 0L
)

object GeminiTelemetryLogger {
    private const val TAG = "GeminiTelemetryLogger"

    fun logInteraction(rawSms: String, jsonOutput: String) {
        val db = try {
            FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            Log.e(TAG, "FirebaseFirestore instance error: ${e.message}")
            null
        }

        val logEntry = GeminiInteractionLog(
            logId = UUID.randomUUID().toString(),
            rawSmsContent = rawSms,
            geminiJsonOutput = jsonOutput,
            timestamp = System.currentTimeMillis()
        )

        if (db != null) {
            db.collection("GeminiInteractionLogs")
                .document(logEntry.logId)
                .set(logEntry)
                .addOnSuccessListener {
                    Log.d(TAG, "Telemetry log recorded successfully: ${logEntry.logId}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to write telemetry log: ${e.message}")
                }
        } else {
            Log.w(TAG, "Firestore not available, skipped telemetry log.")
        }
    }
}
