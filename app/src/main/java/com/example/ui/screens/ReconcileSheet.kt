package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.Account
import com.example.domain.LedgerRules
import com.example.domain.ReconcileRules
import com.example.ui.viewmodel.ExpenseViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

private fun dayText(time: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(time))
private fun dayTimeText(time: Long): String = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(time))

/** What the real-world figure is called for each kind of account. */
private fun realFigureLabel(kind: AccountKind): String = when (kind) {
    AccountKind.BANK -> "Balance as per bank"
    AccountKind.CASH -> "Cash you actually have"
    AccountKind.CARD -> "Amount owed as per the card"
    AccountKind.LOAN -> "Loan outstanding as per the lender"
    AccountKind.FIXED_DEPOSIT, AccountKind.MUTUAL_FUND -> "Current value"
}

/**
 * Check an account against the real world: pick the date, enter what the bank (or card, lender,
 * wallet) says, and see the difference before anything is saved. Saving adds one adjustment for
 * the difference, if any, and locks the entries before that date. For a bank account, the
 * balance from its latest SMS can be used with one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReconcileSheet(
    account: Account,
    onDismiss: () -> Unit,
    viewModel: ExpenseViewModel
) {
    val transactions by viewModel.transactions.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    // The latest copy, so a bank SMS that arrives while this is open shows up.
    val live = accounts.find { it.accountId == account.accountId } ?: account
    val kind = accountKindOf(live)

    var closing by remember { mutableStateOf(System.currentTimeMillis()) }
    // True when [closing] is the moment of the bank SMS, so the comparison is made at that moment.
    var atSmsTime by remember { mutableStateOf(false) }
    var amountStr by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmUndo by remember { mutableStateOf(false) }

    val bank = remember(live, transactions) { ReconcileRules.bankCheck(live, transactions) }
    // In everyday terms: money in the account, amount owed on a card, amount left on a loan.
    val appBalance = remember(live, closing, atSmsTime, transactions) {
        val raw = LedgerRules.balance(live, transactions, if (atSmsTime) closing else LedgerRules.endOfDay(closing))
        if (kind == AccountKind.LOAN) abs(raw) else raw
    }
    val entered = amountStr.replace(",", "").trim().toDoubleOrNull()
    val difference = entered?.let { (if (kind == AccountKind.LOAN) abs(it) else it) - appBalance }
    val waiting = remember(live, closing, atSmsTime, transactions) { viewModel.pendingBefore(live.id, closing, atSmsTime) }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = closing)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { picked ->
                        // The picker gives midnight UTC of the chosen day; read the day itself from it.
                        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = picked }
                        closing = ReconcileRules.closingTime(
                            utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), System.currentTimeMillis()
                        )
                        atSmsTime = false
                    }
                    showDatePicker = false
                }) { Text("Select") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = dateState)
        }
    }

    val lastReconciled = live.lastReconciledDate
    if (confirmUndo && lastReconciled != null) {
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text("Undo the last reconcile?") },
            text = {
                Text(
                    "Entries before ${dayText(lastReconciled)} are unlocked again" +
                        (if (live.reconcileAdjustmentId.isNotBlank()) ", and the adjustment it added is removed" else "") +
                        ". Items it confirmed from review stay confirmed."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmUndo = false; viewModel.undoReconcile(live.id); onDismiss() }) { Text("Undo") }
            },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("Cancel") } }
        )
    }

    FormSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("reconcile_sheet"),
        title = {
            Column {
                Text("Reconcile ${live.name}", modifier = Modifier.testTag("reconcile_dialog_title"))
                Text(
                    "Check the app against the real figure and fix any difference.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (bank != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = (if (bank.matches) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer).copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth().testTag("reconcile_bank_sms_card")
                    ) {
                        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Bank SMS, ${dayTimeText(bank.at)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(formatAmount(bank.bankBalance), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    if (bank.matches) "Matches the app at that time."
                                    else "The app had ${formatAmount(bank.appBalance)} then: ${formatAmount(abs(bank.difference))} ${if (bank.difference > 0) "less" else "more"} than the bank.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (bank.matches) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                            TextButton(
                                onClick = {
                                    closing = bank.at
                                    atSmsTime = true
                                    amountStr = String.format(Locale.US, "%.2f", bank.bankBalance)
                                },
                                modifier = Modifier.testTag("use_bank_sms_balance_button")
                            ) { Text("Use this") }
                        }
                    }
                }

                Column {
                    Text("As on", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(6.dp))
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("select_closing_date_button")
                    ) {
                        Text(
                            if (atSmsTime) dayTimeText(closing) + " (bank SMS)" else dayText(closing),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Default.DateRange, contentDescription = "Choose date", modifier = Modifier.size(20.dp))
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("The app says", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(formatAmount(appBalance), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }

                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            realFigureLabel(kind),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { amountStr = String.format(Locale.US, "%.2f", appBalance) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.testTag("reset_to_calculated_balance_button")
                        ) { Text("Same as the app") }
                    }
                    OutlinedTextField(
                        value = amountStr,
                        onValueChange = { v -> amountStr = v.filter { it.isDigit() || it == '.' || it == ',' || it == '-' } },
                        placeholder = { Text("Type the real figure") },
                        prefix = { Text("₹ ") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("closing_balance_input")
                    )
                }

                if (difference != null) {
                    val same = abs(difference) < 0.005
                    Text(
                        if (same) "Matches the app. Nothing to adjust."
                        else "That is ${formatAmount(abs(difference))} ${if (difference > 0) "more" else "less"} than the app. " +
                            "An adjustment of ${formatAmount(abs(difference))} will be added so the app agrees. " +
                            "A difference usually means a missed or doubled entry, so have a look first if it is large.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (same) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("reconcile_difference_text")
                    )
                }

                if (waiting.isNotEmpty()) {
                    val one = waiting.size == 1
                    val duplicates = waiting.count { it.isProbablyDuplicate }
                    Text(
                        "${waiting.size} SMS ${if (one) "item" else "items"} up to then ${if (one) "is" else "are"} still waiting for review. " +
                            "Reconciling confirms ${if (one) "it" else "them"} with the suggested category" +
                            (if (duplicates > 0) ", and sets aside $duplicates suspected duplicate${if (duplicates == 1) "" else "s"}" else "") +
                            ". Review first if you want to change anything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                if (lastReconciled != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Last reconciled as on ${dayText(lastReconciled)}" + (live.reconciledBalance?.let { " at ${formatAmount(it)}" } ?: "") + ".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { confirmUndo = true }, modifier = Modifier.testTag("undo_reconcile_button")) { Text("Undo") }
                    }
                }

                Text(
                    "After this, entries on this account dated before ${dayText(closing)} keep their amount, date and account. " +
                        "Their category, note and tag can still be changed, and so can anything dated on that day or later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                enabled = entered != null,
                onClick = {
                    entered?.let { viewModel.reconcileAccount(live.id, closing, it, atSmsTime) }
                    onDismiss()
                },
                modifier = Modifier.testTag("confirm_lock_button")
            ) { Text("Reconcile") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("cancel_reconcile_button")) { Text("Cancel") }
        }
    )
}
