package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.Account
import com.example.data.AccountType
import com.example.data.Transaction
import com.example.data.TransactionType
import com.example.data.TransactionStatus
import com.example.ui.viewmodel.ExpenseViewModel
import java.text.SimpleDateFormat
import java.util.*

private fun calculateBalanceAsOf(
    account: Account,
    targetDate: Long,
    allTransactions: List<Transaction>
): Double {
    var balance = account.balance
    
    // Reverse any transactions that occurred after targetDate
    val txsAfterTarget = allTransactions.filter { 
        it.status == TransactionStatus.CONFIRMED && 
        !it.isIgnored && 
        it.timestamp > targetDate 
    }
    
    for (tx in txsAfterTarget) {
        when (tx.type) {
            TransactionType.INCOME -> {
                if (tx.accountId == account.id) {
                    balance -= tx.amount
                }
            }
            TransactionType.EXPENSE -> {
                if (tx.accountId == account.id) {
                    if (account.type == AccountType.CREDIT_CARD || account.type == AccountType.LOAN) {
                        balance -= tx.amount
                    } else {
                        balance += tx.amount
                    }
                }
            }
            TransactionType.TRANSFER -> {
                if (tx.accountId == account.id) {
                    if (account.type == AccountType.CREDIT_CARD) {
                        balance -= tx.amount
                    } else {
                        balance += tx.amount
                    }
                }
                if (tx.targetAccountId == account.id) {
                    if (account.type == AccountType.CREDIT_CARD) {
                        balance += tx.amount
                    } else if (account.type == AccountType.LOAN) {
                        balance -= tx.amount
                    } else {
                        balance -= tx.amount
                    }
                }
            }
        }
    }
    return balance
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReconcileAccountDialog(
    account: Account,
    onDismiss: () -> Unit,
    viewModel: ExpenseViewModel
) {
    val transactions by viewModel.transactions.collectAsState()
    var statementBalanceStr by remember { mutableStateOf("") }
    var selectedDateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var lastFourDigitsStr by remember { mutableStateOf(account.lastFourDigits) }

    val calculatedBalance = remember(account, selectedDateMillis, transactions) {
        calculateBalanceAsOf(account, selectedDateMillis, transactions)
    }

    // Auto-fill statement balance input field when calculated balance changes
    LaunchedEffect(calculatedBalance) {
        statementBalanceStr = String.format(Locale.US, "%.2f", calculatedBalance)
    }

    val dateState = rememberDatePickerState(initialSelectedDateMillis = selectedDateMillis)

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let {
                            selectedDateMillis = it
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("Select")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Reconcile & Lock",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("reconcile_dialog_title")
                    )
                    Text(
                        text = "Balance Integrity Checkpoint",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Account name banner
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.extraSmall)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = account.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Balance Comparison Flow
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Current Ledger Balance Card
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Current Ledger",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = formatAmount(account.balance),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Selected Date Calculated Balance Card
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1.1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Balance As Of Date",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = formatAmount(calculatedBalance),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Date Picker trigger button
                Column {
                    Text(
                        text = "Statement Closing Date",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("select_closing_date_button"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(selectedDateMillis)),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = "Choose Date",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Guardrail/Lock Warning Information Banner
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Safe Guardrail Active: Transactions strictly prior to the selected date will be locked and cannot be added or modified.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            lineHeight = 16.sp
                        )
                    }
                }

                // Actual bank statement Closing Balance text field
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Actual Bank Statement Closing Balance",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                statementBalanceStr = String.format(Locale.US, "%.2f", calculatedBalance)
                            },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(24.dp).testTag("reset_to_calculated_balance_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Auto-Fill", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = statementBalanceStr,
                        onValueChange = { statementBalanceStr = it },
                        placeholder = { Text("e.g. 15000.00") },
                        prefix = { Text("₹ ", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("closing_balance_input"),
                        shape = MaterialTheme.shapes.medium
                    )
                }

                // Last 4 Digits of Account Number
                Column {
                    Text(
                        text = "Last 4 Digits of Account Number",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = lastFourDigitsStr,
                        onValueChange = { input ->
                            if (input.all { char -> char.isDigit() || char == ',' || char == ' ' || char == '/' }) {
                                lastFourDigitsStr = input
                            }
                        },
                        placeholder = { Text("e.g. 5678, 1234 (Account #, Debit Card)") },
                        supportingText = { Text("Enter Account # and Debit Card last digits separated by comma", fontSize = 10.sp) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reconcile_last_four_digits_input"),
                        shape = MaterialTheme.shapes.medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val actualBalance = statementBalanceStr.toDoubleOrNull() ?: account.balance
                    viewModel.updateAccountLastFourDigits(account.id, lastFourDigitsStr.trim())
                    viewModel.reconcileAccount(account.id, selectedDateMillis, actualBalance)
                    onDismiss()
                },
                modifier = Modifier.testTag("confirm_lock_button")
            ) {
                Text("Confirm & Lock")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_reconcile_button")
            ) {
                Text("Cancel")
            }
        }
    )
}
