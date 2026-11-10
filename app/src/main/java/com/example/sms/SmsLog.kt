package com.example.sms

import android.content.Context
import android.util.Log
import com.example.domain.SmsRules
import java.io.File

/**
 * A log, kept on this phone, of every bank SMS already handled (saved, merged or ignored). Every
 * path that reads SMS checks it first, so a re-scan of the inbox never creates duplicates,
 * never brings back a transaction that was deleted, and never sends the same message to Gemini twice.
 */
object SmsLog {
    private const val FILE = "sms_handled.log"
    private var seen: HashSet<String>? = null

    @Synchronized
    private fun load(context: Context): HashSet<String> {
        seen?.let { return it }
        val set = HashSet<String>()
        try {
            val file = File(context.filesDir, FILE)
            if (file.exists()) file.forEachLine { if (it.isNotBlank()) set.add(it) }
        } catch (e: Exception) {
            Log.w("SmsLog", "Could not read the SMS log: ${e.message}")
        }
        seen = set
        return set
    }

    @Synchronized
    fun isHandled(context: Context, sender: String, body: String): Boolean =
        SmsRules.fingerprint(sender, body) in load(context)

    @Synchronized
    fun markHandled(context: Context, sender: String, body: String) {
        val key = SmsRules.fingerprint(sender, body)
        if (!load(context).add(key)) return
        try {
            File(context.filesDir, FILE).appendText(key + "\n")
        } catch (e: Exception) {
            Log.w("SmsLog", "Could not write the SMS log: ${e.message}")
        }
    }

    /** Lets a message be read again (when it is re-sent to Gemini). */
    @Synchronized
    fun forget(context: Context, sender: String, body: String) {
        val set = load(context)
        if (!set.remove(SmsRules.fingerprint(sender, body))) return
        try {
            File(context.filesDir, FILE).writeText(set.joinToString("") { it + "\n" })
        } catch (e: Exception) {
            Log.w("SmsLog", "Could not rewrite the SMS log: ${e.message}")
        }
    }
}
