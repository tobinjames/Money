package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.Account
import com.example.data.TransactionType
import com.example.domain.FreeToSpendRules
import com.example.domain.LedgerRules
import com.example.domain.ReconcileRules
import com.example.ui.viewmodel.ExpenseViewModel
import kotlin.math.abs

/**
 * Accounts & categories: the real-money accounts (with balance, owner and when each was last
 * checked against the bank), and the spending and income categories (with this month's total).
 * Tapping anything opens its edit form; the button at the bottom adds a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagementScreen(
    viewModel: ExpenseViewModel,
    onBackClick: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBackClick() }
    val categories by viewModel.categories.collectAsState()
    // Same list with ledger balances worked out.
    val accountsWithBalances by viewModel.accounts.collectAsState()
    val transactions by viewModel.transactions.collectAsState()
    val myUid by viewModel.currentUserUid.collectAsState()
    val partnerName by viewModel.spouseUserFirstName.collectAsState()

    // 0 = Accounts, 1 = Spending categories, 2 = Income categories
    var tab by rememberSaveable { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    var addingAccount by remember { mutableStateOf(false) }
    var addingCategory by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf<Account?>(null) }
    var editingCategory by remember { mutableStateOf<Account?>(null) }
    var reconcilingAccount by remember { mutableStateOf<Account?>(null) }

    val now = remember { System.currentTimeMillis() }

    val accountGroups = remember(accountsWithBalances) { groupAccounts(accountsWithBalances) }
    // Anything that is neither a real account nor a category (old "equity" entries).
    val otherEntries = remember(categories) {
        categories.filter { it.accountClass.uppercase() !in setOf("ASSET", "LIABILITY", "INCOME", "EXPENSE") }
            .sortedBy { it.name.lowercase() }
    }

    val categoryClass = if (tab == 2) "INCOME" else "EXPENSE"
    val categoryGroups = remember(categories, categoryClass, searchQuery) {
        val q = searchQuery.trim().lowercase()
        categories
            .filter { it.accountClass.uppercase() == categoryClass }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.mainCategory.lowercase().contains(q) }
            .groupBy { displayCategoryGroup(it.mainCategory) }
            .toSortedMap(compareBy<String> { it != "General" }.thenBy { it.lowercase() })
            .mapValues { (_, list) -> list.sortedBy { it.name.lowercase() } }
    }
    // This month's total per category (lowercase name), so it is clear which ones are in use.
    val monthTotals = remember(transactions, categoryClass) {
        val (start, end) = LedgerRules.monthBounds(System.currentTimeMillis())
        val type = if (categoryClass == "INCOME") TransactionType.INCOME else TransactionType.EXPENSE
        transactions
            .filter { LedgerRules.isCounted(it) && it.type == type && it.timestamp >= start && it.timestamp < end }
            .groupBy { it.category.trim().lowercase() }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text("Accounts & categories", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (tab == 0) addingAccount = true else addingCategory = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(if (tab == 0) "Add account" else "Add category") },
                modifier = Modifier.testTag("add_category_fab")
            )
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            TabRow(selectedTabIndex = tab) {
                listOf("Accounts", "Spending", "Income").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i; searchQuery = "" }, text = { Text(label) })
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)
            ) {
                if (tab == 0) {
                    if (accountGroups.isEmpty()) {
                        item { EmptyHint("No accounts yet. Add your bank accounts and cards so balances and Free to spend work.") }
                    }
                    accountGroups.forEach { (title, list) ->
                        item(key = "h_$title") {
                            val total = list.sumOf { it.balance }
                            val owed = list.all { it.accountType == "LIABILITY" }
                            GroupHeader(title, formatAmount(if (owed) abs(total) else total) + if (owed && abs(total) > 0.5) " owed" else "")
                        }
                        items(list, key = { it.accountId }) { acc ->
                            AccountRow(
                                account = acc,
                                subtitle = accountSubtitle(acc, myUid, partnerName),
                                now = now,
                                // Edit the stored entry, not the copy carrying a worked-out balance.
                                onClick = { editingAccount = categories.find { it.accountId == acc.accountId } ?: acc },
                                onReconcile = { reconcilingAccount = acc }
                            )
                        }
                    }
                    if (otherEntries.isNotEmpty()) {
                        item(key = "h_other") { GroupHeader("Other", "") }
                        items(otherEntries, key = { "o_" + it.accountId }) { entry ->
                            CategoryListRow(entry, trailing = "", onClick = { editingCategory = entry })
                        }
                    }
                } else {
                    item(key = "search") {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search categories") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear") }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    }
                    if (categoryGroups.isEmpty()) {
                        item { EmptyHint(if (searchQuery.isBlank()) "No categories yet." else "Nothing matches \"${searchQuery.trim()}\".") }
                    }
                    categoryGroups.forEach { (group, list) ->
                        item(key = "g_$group") {
                            val total = list.sumOf { monthTotals[it.name.trim().lowercase()] ?: 0.0 }
                            GroupHeader(group, if (total > 0.5) "${formatAmount(total)} this month" else "")
                        }
                        item(key = "card_$group") {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column {
                                    list.forEachIndexed { i, cat ->
                                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(start = 60.dp))
                                        val total = monthTotals[cat.name.trim().lowercase()] ?: 0.0
                                        CategoryListRow(cat, trailing = if (total > 0.5) formatAmount(total) else "", onClick = { editingCategory = cat })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (addingAccount) {
        AccountEditorSheet(viewModel = viewModel, existing = null, onDismiss = { addingAccount = false })
    }
    editingAccount?.let { acc ->
        AccountEditorSheet(viewModel = viewModel, existing = acc, onDismiss = { editingAccount = null })
    }
    if (addingCategory) {
        CategoryEditorSheet(viewModel = viewModel, existing = null, initialClass = categoryClass, onDismiss = { addingCategory = false })
    }
    editingCategory?.let { cat ->
        CategoryEditorSheet(viewModel = viewModel, existing = cat, onDismiss = { editingCategory = null })
    }
    reconcilingAccount?.let { acc ->
        ReconcileAccountDialog(account = acc, onDismiss = { reconcilingAccount = null }, viewModel = viewModel)
    }
}

@Composable
private fun GroupHeader(title: String, trailing: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp)
    )
}

@Composable
private fun RoundIcon(icon: ImageVector, tint: Color) {
    Surface(shape = CircleShape, color = tint.copy(alpha = 0.13f), modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CategoryListRow(cat: Account, trailing: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth().testTag("hierarchical_item_${cat.id}")) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(getCategoryIcon(cat.name, cat.iconName), getCategoryColor(cat.name))
            Spacer(Modifier.width(12.dp))
            Text(
                cat.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (trailing.isNotEmpty()) {
                Text(trailing, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 4.dp).size(18.dp))
        }
    }
}

/**
 * One account: name, owner and last digits, balance, and when it was last checked against the
 * bank. Tapping the card edits it; the button reconciles. Overdue checks are flagged (30 days
 * for banks and cards, 60 for the rest), as is a card with no statement day.
 */
@Composable
private fun AccountRow(
    account: Account,
    subtitle: String,
    now: Long,
    onClick: () -> Unit,
    onReconcile: () -> Unit
) {
    val isLiability = account.accountType == "LIABILITY"
    val owes = isLiability && abs(account.balance) > 0.5
    val days = ReconcileRules.daysSince(account, now)
    val overdue = days >= ReconcileRules.staleAfterDays(account)
    val reconciledText = when {
        account.lastReconciledDate == null -> "Never checked against the bank"
        days <= 0 -> "Checked today"
        else -> "Checked $days day${if (days == 1) "" else "s"} ago"
    }
    val needsStatementDay = FreeToSpendRules.isCard(account) && account.billingCycleDay !in 1..31

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("hierarchical_item_${account.id}")
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(accountIcon(account), MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(account.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatAmount(if (isLiability) abs(account.balance) else account.balance),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (owes || (!isLiability && account.balance < -0.5)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    if (owes) Text("owed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (needsStatementDay) {
                Text(
                    "Add the statement day so the bill can be worked out",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 52.dp, top = 6.dp)
                )
            }
            Row(Modifier.fillMaxWidth().padding(start = 52.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    reconciledText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onReconcile,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    modifier = Modifier.testTag("reconcile_${account.id}")
                ) {
                    Text(if (overdue) "Reconcile now" else "Reconcile", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
