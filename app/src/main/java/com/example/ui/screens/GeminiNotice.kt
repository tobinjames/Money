package com.example.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sms.GeminiStatus
import com.example.sms.SmsProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun timeText(at: Long): String = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(at))

private fun waitingText(count: Int): String = if (count == 1) "1 message is waiting" else "$count messages are waiting"

/** One line on how Gemini is doing, for Settings. */
fun geminiStatusText(state: GeminiStatus.State): String = when {
    state.unavailable -> buildString {
        append("Not available since ${timeText(state.lastFailure)}.")
        if (state.waiting > 0) append(" ${waitingText(state.waiting)} to be read again.")
        if (state.lastError.isNotBlank()) append(" (${state.lastError.take(80)})")
    }
    state.waiting > 0 -> "Working again. ${waitingText(state.waiting)} to be read again."
    state.lastOk > 0L -> "Working. Last read an SMS on ${timeText(state.lastOk)}"
    else -> "Not used yet"
}

/** Sends the missed SMS to Gemini again and says how it went. */
fun retryGemini(context: Context, scope: CoroutineScope) {
    val app = context.applicationContext
    scope.launch {
        val before = GeminiStatus.missed(app).size
        val read = try {
            withContext(Dispatchers.IO) { SmsProcessor.retryWithGemini(app) }
        } catch (e: Exception) {
            0
        }
        val message = when {
            before == 0 -> "Nothing is waiting"
            read == 0 -> "Gemini is still not available. I'll keep trying."
            read < before -> "Read $read of $before. The rest will be tried again later."
            read == 1 -> "Gemini read the message"
            else -> "Gemini read all $read messages"
        }
        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }
}

/** A notice on the home screen while Gemini is not reading SMS, or some are waiting for it. */
@Composable
fun GeminiBanner() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by GeminiStatus.state.collectAsState()
    LaunchedEffect(Unit) { GeminiStatus.load(context) }
    if (!state.unavailable && state.waiting == 0) return

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Warning, contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.unavailable) "Gemini is not available" else "Gemini is back",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    when {
                        state.waiting == 0 -> "The next bank SMS will show if it is working again."
                        state.unavailable -> "${waitingText(state.waiting)}. Until it is read properly, anything added from it is in your review list."
                        else -> "${waitingText(state.waiting)} to be read again."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            if (state.waiting > 0) {
                TextButton(onClick = { retryGemini(context, scope) }, enabled = !state.retrying) {
                    Text(if (state.retrying) "Trying…" else "Try again")
                }
            }
        }
    }
}
