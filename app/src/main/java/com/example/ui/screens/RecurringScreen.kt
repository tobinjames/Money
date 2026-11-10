package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.InsightRules
import com.example.domain.ReminderRules
import com.example.ui.viewmodel.ExpenseViewModel
import java.util.Calendar

/**
 * Payments that come round every month (subscriptions, rent, SIPs), found from the history,
 * with what each costs in a year. One tap turns one into a monthly reminder, which also makes
 * Free to spend set money aside for it.
 */
@Composable
fun RecurringScreen(viewModel: ExpenseViewModel, onBackClick: () -> Unit) {
    BackHandler { onBackClick() }
    val txs by viewModel.transactions.collectAsState()
    val reminders by viewModel.reminders.collectAsState()
    val now = remember { System.currentTimeMillis() }
    val found = rememberInBackground<List<InsightRules.Recurring>?>(null, txs) { InsightRules.recurring(txs, now) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                IconButton(onClick = onBackClick) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                Text("Monthly payments", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        val list = found
        when {
            list == null -> item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            list.isEmpty() -> item {
                Text(
                    "Nothing found yet. A payment shows up here once the same payee has been paid about the same amount in 3 of the last 4 months.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            else -> {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("recurring_total"),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("${list.size} PAYMENTS EVERY MONTH", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text("${formatAmount(list.sumOf { it.amount })} a month", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                            Text("${formatAmount(list.sumOf { it.yearly })} a year", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(list, key = { it.name }) { r ->
                    val hasReminder = reminders.any { it.title.equals(r.name, ignoreCase = true) }
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(formatAmount(r.amount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                "${r.category} · around the ${ordinal(r.dayOfMonth)} · ${formatAmount(r.yearly)} a year",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (hasReminder) {
                                Text("Reminder set", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                            } else {
                                TextButton(
                                    onClick = {
                                        viewModel.addNewReminder(
                                            title = r.name,
                                            amount = r.amount,
                                            type = "Subscription",
                                            dueDate = nextOn(r.dayOfMonth, System.currentTimeMillis()),
                                            isRecurring = true
                                        )
                                        viewModel.triggerToast("Monthly reminder added for ${r.name}")
                                    },
                                    contentPadding = PaddingValues(0.dp)
                                ) { Text("Remind me and set money aside") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ordinal(day: Int): String = "$day" + when {
    day in 11..13 -> "th"
    day % 10 == 1 -> "st"
    day % 10 == 2 -> "nd"
    day % 10 == 3 -> "rd"
    else -> "th"
}

/** The next time [day] of the month comes round after [now], at 9 in the morning. */
private fun nextOn(day: Int, now: Long): Long {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val thisMonth = ReminderRules.dateOnDay(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), day, 9)
    if (thisMonth > now) return thisMonth
    cal.set(Calendar.DAY_OF_MONTH, 1)
    cal.add(Calendar.MONTH, 1)
    return ReminderRules.dateOnDay(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), day, 9)
}
