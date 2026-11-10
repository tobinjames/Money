package com.example.sms

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Whether Gemini is reading SMS at the moment, and the messages it missed.
 *
 * When Gemini can't be reached, an SMS is read by the simple on-phone reader (and its transaction
 * held for review), or not read at all when that reader can't tell. Either way the message is
 * kept here, on this phone, to be sent to Gemini again once it is back (see
 * [SmsProcessor.retryWithGemini]).
 */
object GeminiStatus {

    data class State(
        val lastOk: Long = 0L,
        val lastFailure: Long = 0L,
        val lastError: String = "",
        /** Messages waiting to be read by Gemini. */
        val waiting: Int = 0,
        val retrying: Boolean = false
    ) {
        /** The most recent attempt failed. */
        val unavailable: Boolean get() = lastFailure > lastOk
    }

    /** One SMS Gemini has not read, with the transactions the simple reader made from it. */
    data class Missed(val sender: String, val body: String, val timestamp: Long, val transactionIds: List<String>)

    val state = MutableStateFlow(State())

    private const val PREFS = "gemini_status"
    private const val FILE = "gemini_retry.json"
    private var loaded = false
    private val queue = ArrayList<Missed>()

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val file = File(context.filesDir, FILE)
            if (file.exists()) {
                val array = JSONArray(file.readText())
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val ids = o.optJSONArray("ids") ?: JSONArray()
                    queue.add(Missed(o.getString("s"), o.getString("b"), o.getLong("t"), (0 until ids.length()).map { ids.getString(it) }))
                }
            }
        } catch (e: Exception) {
            Log.w("GeminiStatus", "Could not read the list of missed SMS: ${e.message}")
        }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        state.value = State(p.getLong("ok", 0L), p.getLong("fail", 0L), p.getString("error", "") ?: "", queue.size)
    }

    @Synchronized
    fun recordOk(context: Context) {
        load(context)
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("ok", now).apply()
        state.value = state.value.copy(lastOk = now)
    }

    @Synchronized
    fun recordFailure(context: Context, error: String?) {
        load(context)
        val now = System.currentTimeMillis()
        val why = (error ?: "").take(200)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("fail", now).putString("error", why).apply()
        state.value = state.value.copy(lastFailure = now, lastError = why)
    }

    @Synchronized
    fun missed(context: Context): List<Missed> {
        load(context)
        return queue.toList()
    }

    @Synchronized
    fun addMissed(context: Context, item: Missed) {
        load(context)
        queue.removeAll { it.sender == item.sender && it.body == item.body }
        queue.add(item)
        save(context)
    }

    @Synchronized
    fun removeMissed(context: Context, item: Missed) {
        load(context)
        if (queue.removeAll { it.sender == item.sender && it.body == item.body }) save(context)
    }

    fun setRetrying(on: Boolean) {
        state.value = state.value.copy(retrying = on)
    }

    private fun save(context: Context) {
        try {
            val array = JSONArray()
            queue.forEach { m ->
                array.put(JSONObject().put("s", m.sender).put("b", m.body).put("t", m.timestamp).put("ids", JSONArray(m.transactionIds)))
            }
            File(context.filesDir, FILE).writeText(array.toString())
        } catch (e: Exception) {
            Log.w("GeminiStatus", "Could not save the list of missed SMS: ${e.message}")
        }
        state.value = state.value.copy(waiting = queue.size)
    }
}
