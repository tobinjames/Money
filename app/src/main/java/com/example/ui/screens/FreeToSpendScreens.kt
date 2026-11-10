package com.example.ui.screens

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.Account
import com.example.data.Reserve
import com.example.domain.FreeToSpendRules
import com.example.ui.viewmodel.ExpenseViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun shortDate(time: Long): String = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(time))

/**
 * The dashboard's headline number: what this person can really spend until their next salary,
 * with the sums behind it one tap away.
 */
@Composable
fun FreeToSpendCard(viewModel: ExpenseViewModel, onOpenReserves: () -> Unit) {
    val b by viewModel.freeToSpend.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val salaryDay by viewModel.salaryDay.collectAsState()
    val breakdown = b ?: return
    val negative = breakdown.freeToSpend < 0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clickable { expanded = !expanded }
            .testTag("free_to_spend_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (negative) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "FREE TO SPEND UNTIL ${shortDate(breakdown.horizon).uppercase()}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                formatAmount(breakdown.freeToSpend),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Text(
                if (negative) "Your card dues, bills and reserves are more than your bank and cash."
                else "Bank and cash after card dues, bills before ${if (salaryDay in 1..31) "salary" else "the next 30 days"} and reserves.",
                style = MaterialTheme.typography.bodySmall
            )
            if (breakdown.unreviewedCount > 0) {
                Text(
                    "Includes ${formatAmount(breakdown.unreviewed)} from ${breakdown.unreviewedCount} SMS ${if (breakdown.unreviewedCount == 1) "item" else "items"} waiting for your review.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            if (!expanded) {
                Text(
                    "Tap to see how it adds up",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                BreakdownRow("Bank and cash", breakdown.bankAndCash, bold = true)
                breakdown.accounts.forEach { (acc, bal) -> BreakdownRow(acc.accountName, bal, indent = true) }

                Spacer(Modifier.height(8.dp))
                BreakdownRow("Credit card dues", -breakdown.cardDues, bold = true)
                breakdown.cards.forEach { c ->
                    BreakdownRow(c.account.accountName, -c.owed, indent = true)
                    if (c.account.billingCycleDay in 1..31) {
                        Text(
                            "${formatAmount(c.billed)} on the last statement · ${formatAmount(c.spentSinceStatement)} spent since",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, bottom = 2.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                BreakdownRow("Bills before ${shortDate(breakdown.horizon)}", -breakdown.upcomingBills, bold = true)
                if (breakdown.bills.isEmpty()) {
                    Text("None in Reminders", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
                }
                breakdown.bills.forEach { line ->
                    BreakdownRow("${line.reminder.title} (${shortDate(line.dueDate)})", -line.reminder.amount, indent = true)
                }

                Spacer(Modifier.height(8.dp))
                BreakdownRow("Reserved", -breakdown.reserved, bold = true)
                breakdown.reserves.forEach { r -> BreakdownRow(r.name, -r.amount, indent = true) }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onOpenReserves) {
                        Icon(Icons.Default.Savings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reserves")
                    }
                    if (salaryDay !in 1..31) {
                        Text(
                            "Set your salary day in Settings > Money setup for a sharper figure.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakdownRow(label: String, amount: Double, bold: Boolean = false, indent: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 16.dp else 0.dp, top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = if (bold) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        Text(
            (if (amount < 0) "− " else "") + formatAmount(kotlin.math.abs(amount)),
            style = if (bold) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * Shared reserve pots. Each is kept in one account and lowers Free to spend for whoever owns
 * that account (both of you for a joint account).
 */
@Composable
fun ReservesScreen(viewModel: ExpenseViewModel, onBackClick: () -> Unit) {
    BackHandler { onBackClick() }
    val reserves by viewModel.reserves.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val myUid by viewModel.currentUserUid.collectAsState()
    val partnerName = viewModel.spouseUserFirstName.collectAsState().value ?: "Partner"
    val liquid = accounts.filter { FreeToSpendRules.isLiquid(it) }

    var editing by remember { mutableStateOf<Reserve?>(null) }
    var using by remember { mutableStateOf<Reserve?>(null) }

    fun ownerLabel(account: Account?): String = when {
        account == null -> "No account"
        account.ownerUserId.equals(FreeToSpendRules.SHARED, true) -> "joint"
        account.ownerUserId == myUid -> "yours"
        else -> "$partnerName's"
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Reserve(keptInAccountId = defaultAccount(liquid, myUid)?.accountId ?: "") },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New reserve") }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    IconButton(onClick = onBackClick) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                    Text("Reserves", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Set money aside for what's coming: insurance, school fees, a trip. The money stays in the " +
                        "account; it just stops counting as free to spend. Reserves are shared with your partner. " +
                        "Use them for things that aren't already in Reminders, so nothing is counted twice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                if (reserves.isEmpty()) {
                    Text("No reserves yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 24.dp))
                }
            }
            items(reserves.sortedBy { it.name.lowercase() }, key = { it.id }) { r ->
                val account = accounts.find { it.accountId == r.keptInAccountId }
                ReserveCard(
                    reserve = r,
                    where = "${account?.accountName ?: "No account"} (${ownerLabel(account)})",
                    onEdit = { editing = r },
                    onUse = { using = r }
                )
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }

    editing?.let { r ->
        ReserveDialog(
            initial = r,
            isNew = reserves.none { it.id == r.id },
            accounts = liquid,
            accountLabel = { "${it.accountName} (${ownerLabel(it)})" },
            onDismiss = { editing = null },
            onSave = { viewModel.saveReserve(it); editing = null },
            onDelete = { viewModel.deleteReserve(r.id); editing = null }
        )
    }
    using?.let { r ->
        UseReserveDialog(r, onDismiss = { using = null }, onUse = { viewModel.useReserve(r, it); using = null })
    }
}

private fun defaultAccount(liquid: List<Account>, myUid: String?): Account? =
    liquid.firstOrNull { it.ownerUserId == myUid } ?: liquid.firstOrNull { it.ownerUserId.equals(FreeToSpendRules.SHARED, true) }
        ?: liquid.firstOrNull()

@Composable
private fun ReserveCard(reserve: Reserve, where: String, onEdit: () -> Unit, onUse: () -> Unit) {
    val now = System.currentTimeMillis()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clickable(onClick = onEdit),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(reserve.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(formatAmount(reserve.amount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            if (reserve.target > 0.0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (reserve.amount / reserve.target).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                val goal = buildString {
                    append("Goal ${formatAmount(reserve.target)}")
                    if (reserve.targetDate > 0L) append(" by ${SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(reserve.targetDate))}")
                    FreeToSpendRules.neededPerMonth(reserve, now)?.takeIf { it > 0.0 }?.let { append(" · ${formatAmount(it)}/month to get there") }
                }
                Text(goal, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            if (reserve.monthlyContribution > 0.0) {
                Text(
                    "Adds ${formatAmount(reserve.monthlyContribution)} on the 1st of each month",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (reserve.amount > 0.0) {
                TextButton(onClick = onUse, contentPadding = PaddingValues(0.dp)) { Text("Spent from this") }
            }
        }
    }
}

@Composable
private fun ReserveDialog(
    initial: Reserve,
    isNew: Boolean,
    accounts: List<Account>,
    accountLabel: (Account) -> String,
    onDismiss: () -> Unit,
    onSave: (Reserve) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    fun num(d: Double) = if (d > 0.0) "%.0f".format(d) else ""
    var name by remember { mutableStateOf(initial.name) }
    var amount by remember { mutableStateOf(num(initial.amount)) }
    var target by remember { mutableStateOf(num(initial.target)) }
    var monthly by remember { mutableStateOf(num(initial.monthlyContribution)) }
    var targetDate by remember { mutableStateOf(initial.targetDate) }
    var accountId by remember { mutableStateOf(initial.keptInAccountId) }
    var accountMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val valid = name.isNotBlank() && accountId.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New reserve" else "Edit reserve") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("For what") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    amount, { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Set aside now (₹)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
                Box {
                    OutlinedButton(onClick = { accountMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(accounts.find { it.accountId == accountId }?.let { "Kept in ${accountLabel(it)}" } ?: "Choose the account it's kept in")
                    }
                    DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                        accounts.forEach { acc ->
                            DropdownMenuItem(text = { Text(accountLabel(acc)) }, onClick = { accountId = acc.accountId; accountMenu = false })
                        }
                    }
                }
                OutlinedTextField(
                    monthly, { monthly = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Add every month (₹, optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    target, { target = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Goal (₹, optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val cal = Calendar.getInstance().apply { if (targetDate > 0L) timeInMillis = targetDate }
                        DatePickerDialog(context, { _, y, m, d ->
                            targetDate = Calendar.getInstance().apply { clear(); set(y, m, d, 12, 0) }.timeInMillis
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    }) {
                        Text(if (targetDate > 0L) "Needed by ${SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(targetDate))}" else "Needed by (optional)")
                    }
                    if (targetDate > 0L) TextButton(onClick = { targetDate = 0L }) { Text("Clear") }
                }
                if (!isNew) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete reserve", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(
                    initial.copy(
                        name = name.trim(),
                        amount = amount.toDoubleOrNull() ?: 0.0,
                        target = target.toDoubleOrNull() ?: 0.0,
                        monthlyContribution = monthly.toDoubleOrNull() ?: 0.0,
                        targetDate = targetDate,
                        keptInAccountId = accountId
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${initial.name}?") },
            text = { Text("The ${formatAmount(initial.amount)} set aside goes back to free to spend, for both of you.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun UseReserveDialog(reserve: Reserve, onDismiss: () -> Unit, onUse: (Double) -> Unit) {
    var amount by remember { mutableStateOf("%.0f".format(reserve.amount)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Spent from ${reserve.name}") },
        text = {
            Column {
                Text(
                    "Once the payment is made, take it out of the reserve so it isn't counted twice.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    amount, { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount (₹)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = (amount.toDoubleOrNull() ?: 0.0) > 0.0, onClick = { onUse(amount.toDouble()) }) { Text("Take out") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
