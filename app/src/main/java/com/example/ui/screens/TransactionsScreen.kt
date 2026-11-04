package com.example.ui.screens

import android.app.DatePickerDialog
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.*
import com.example.ui.viewmodel.ExpenseViewModel
import com.example.ui.viewmodel.ViewFilter

enum class DateRangePreset { CURRENT_MONTH, PREVIOUS_MONTH, CUSTOM }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    viewModel: ExpenseViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val accounts by viewModel.accounts.collectAsState()
    val filteredAccounts by viewModel.filteredAccounts.collectAsState()
    val transactions by viewModel.filteredTransactions.collectAsState()
    val allTransactions by viewModel.transactions.collectAsState()
    val categories by viewModel.categories.collectAsState(initial = emptyList())
    val activeFilter by viewModel.currentFilter.collectAsState()
    val merchantRules by viewModel.merchantRules.collectAsState(initial = emptyList())
    
    // Dynamic extraction profiles for tab labeling
    val myFirstName by viewModel.currentUserFirstName.collectAsState()
    val spouseFirstName by viewModel.spouseUserFirstName.collectAsState()
    val myNameStr = myFirstName ?: "You"
    val spouseNameStr = spouseFirstName ?: "Partner"
    val myUid by viewModel.currentUserUid.collectAsState()
    val spouseUid by viewModel.spouseUserUid.collectAsState()
    
    // Filter Header States
    var selectedAccountFilterId by remember { mutableStateOf("ALL") }
    var datePresetFilter by remember { mutableStateOf(DateRangePreset.CURRENT_MONTH) }
    var customStartMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var customEndMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var isStatementViewEnabled by remember { mutableStateOf(false) }
    
    LaunchedEffect(selectedAccountFilterId) {
        if (selectedAccountFilterId == "ALL") {
            isStatementViewEnabled = false
        }
    }
    
    LaunchedEffect(activeFilter) {
        selectedAccountFilterId = "ALL"
    }
    
    var showAddDialog by remember { mutableStateOf(false) }
    var editingTransaction by remember { mutableStateOf<Transaction?>(null) }

    // Form inputs state (Manual Entry Add form)
    var amountStr by remember { mutableStateOf("") }
    var txType by remember { mutableStateOf(TransactionType.EXPENSE) }
    var scope by remember { mutableStateOf(TransactionScope.FAMILY) }
    var paidBy by remember { mutableStateOf("BOTH") }
    var narration by remember { mutableStateOf("") }
    var tagStr by remember { mutableStateOf("") }
    var selectedTagFilter by remember { mutableStateOf<String?>(null) }
    var selectedCategoryId by remember { mutableStateOf("Others") }
    var selectedAccountId by remember { mutableStateOf("") }
    var selectedTargetAccountId by remember { mutableStateOf<String?>(null) } // Target mapping for bank transfers
    
    LaunchedEffect(showAddDialog, accounts) {
        if (showAddDialog) {
            if (selectedAccountId.isEmpty() && accounts.isNotEmpty()) {
                selectedAccountId = accounts.firstOrNull()?.id ?: ""
            }
        }
    }
    
    var dateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val calendar = remember { Calendar.getInstance() }
    
    // Auto-category suggestion based on narration input in Add form
    val suggestedCategory = remember(narration) {
        viewModel.getSuggestedCategoryForVendor(narration)
    }

    // Set initial default account when add manual dialog opens
    LaunchedEffect(showAddDialog) {
        if (showAddDialog && selectedAccountId.isEmpty() && accounts.isNotEmpty()) {
            selectedAccountId = accounts.firstOrNull { it.type == AccountType.SAVINGS_ACCOUNT }?.id ?: accounts.first().id
        }
    }

    val availableTags = remember(allTransactions) {
        allTransactions.mapNotNull { it.tag.trim().takeIf { t -> t.isNotBlank() } }.distinct().sorted()
    }

    // --- INSTANT MULTI-WAY FILTER ENGINE ---
    val filteredLedgerTransactions = remember(
        transactions,
        allTransactions,
        selectedAccountFilterId,
        datePresetFilter,
        customStartMillis,
        customEndMillis,
        selectedTagFilter
    ) {
        val cal = Calendar.getInstance()
        val (startTime, endTime) = when (datePresetFilter) {
            DateRangePreset.CURRENT_MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                
                cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis
                Pair(start, end)
            }
            DateRangePreset.PREVIOUS_MONTH -> {
                cal.add(Calendar.MONTH, -1)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                
                cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis
                Pair(start, end)
            }
            DateRangePreset.CUSTOM -> {
                Pair(customStartMillis, customEndMillis)
            }
        }
        
        val sourceList = transactions
        sourceList.filter { tx ->
            val matchesAcc = if (selectedAccountFilterId == "ALL") true 
                             else (tx.accountId == selectedAccountFilterId || tx.targetAccountId == selectedAccountFilterId)
            val matchesDate = tx.timestamp in startTime..endTime
            val matchesTag = selectedTagFilter == null || tx.tag.trim().equals(selectedTagFilter, ignoreCase = true)
            matchesAcc && matchesDate && matchesTag
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_transaction_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add manual transaction")
            }
        },
        modifier = modifier
            .fillMaxSize()
            .testTag("transactions_screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Financial Ledger",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            // --- STICKY FILTER HEADER BLOCK ---
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. User Filter Selection
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            Triple(ViewFilter.User1, myNameStr, Icons.Default.Person),
                            Triple(ViewFilter.User2, spouseNameStr, Icons.Default.Favorite),
                            Triple(ViewFilter.Combined, "Shared", Icons.Default.Group)
                        ).forEach { (f, label, icon) ->
                            val isSel = activeFilter == f
                            val chipBg = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            val chipTc = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            val chipBorder = if (isSel) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(chipBg)
                                    .border(chipBorder, RoundedCornerShape(12.dp))
                                    .clickable { viewModel.setFilter(f) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(icon, contentDescription = null, tint = chipTc, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(label, color = chipTc, fontSize = 11.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium)
                                }
                            }
                        }
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 2. Account Selector Dropdown Box
                        var accountExpanded by remember { mutableStateOf(false) }
                        val activeAccountName = accounts.find { it.id == selectedAccountFilterId }?.name ?: "All Accounts"
                        Box(
                            modifier = Modifier
                                .weight(1.2f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .border(androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)), RoundedCornerShape(10.dp))
                                .clickable { accountExpanded = true }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = Icons.Default.AccountBalance,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(activeAccountName, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                }
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = accountExpanded, onDismissRequest = { accountExpanded = false }) {
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    text = { Text("All Accounts", fontSize = 12.sp) },
                                    onClick = {
                                        selectedAccountFilterId = "ALL"
                                        accountExpanded = false
                                    }
                                )
                                filteredAccounts.forEach { acc ->
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Default.AccountBalance, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                        text = { Text(acc.name, fontSize = 12.sp) },
                                        onClick = {
                                            selectedAccountFilterId = acc.id
                                            accountExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        
                        // 3. Date Range Preset Selector Dropdown
                        var datePresetExpanded by remember { mutableStateOf(false) }
                        val datePresetLabel = when (datePresetFilter) {
                            DateRangePreset.CURRENT_MONTH -> "Current Month"
                            DateRangePreset.PREVIOUS_MONTH -> "Previous Month"
                            DateRangePreset.CUSTOM -> "Custom Range"
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .border(androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)), RoundedCornerShape(10.dp))
                                .clickable { datePresetExpanded = true }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.DateRange,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(datePresetLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                }
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(expanded = datePresetExpanded, onDismissRequest = { datePresetExpanded = false }) {
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    text = { Text("Current Month", fontSize = 12.sp) },
                                    onClick = {
                                        datePresetFilter = DateRangePreset.CURRENT_MONTH
                                        datePresetExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    text = { Text("Previous Month", fontSize = 12.sp) },
                                    onClick = {
                                        datePresetFilter = DateRangePreset.PREVIOUS_MONTH
                                        datePresetExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    text = { Text("Custom Range", fontSize = 12.sp) },
                                    onClick = {
                                        datePresetFilter = DateRangePreset.CUSTOM
                                        datePresetExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    
                    // Custom date range selectors displayed if CUSTOM is chosen in dropdown
                    if (datePresetFilter == DateRangePreset.CUSTOM) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val pickStartText = remember(customStartMillis) {
                                SimpleDateFormat("dd MMM yy", Locale.getDefault()).format(Date(customStartMillis))
                            }
                            val pickEndText = remember(customEndMillis) {
                                SimpleDateFormat("dd MMM yy", Locale.getDefault()).format(Date(customEndMillis))
                            }
                            
                            OutlinedButton(
                                onClick = {
                                    DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            val c = Calendar.getInstance()
                                            c.set(y, m, d, 0, 0, 0)
                                            customStartMillis = c.timeInMillis
                                        },
                                        calendar.get(Calendar.YEAR),
                                        calendar.get(Calendar.MONTH),
                                        calendar.get(Calendar.DAY_OF_MONTH)
                                    ).show()
                                },
                                modifier = Modifier.weight(1f).height(38.dp),
                                contentPadding = PaddingValues(2.dp)
                            ) {
                                Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("From: $pickStartText", fontSize = 10.sp)
                            }
                            
                            OutlinedButton(
                                onClick = {
                                    DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            val c = Calendar.getInstance()
                                            c.set(y, m, d, 23, 59, 59)
                                            customEndMillis = c.timeInMillis
                                        },
                                        calendar.get(Calendar.YEAR),
                                        calendar.get(Calendar.MONTH),
                                        calendar.get(Calendar.DAY_OF_MONTH)
                                    ).show()
                                },
                                modifier = Modifier.weight(1f).height(38.dp),
                                contentPadding = PaddingValues(2.dp)
                            ) {
                                Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("To: $pickEndText", fontSize = 10.sp)
                            }
                        }
                    }
                    // 4. Sub-Ledger / Trip / Event Tag Filter Chips
                    if (availableTags.isNotEmpty()) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Tag:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            androidx.compose.foundation.lazy.LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                item {
                                    FilterChip(
                                        selected = selectedTagFilter == null,
                                        onClick = { selectedTagFilter = null },
                                        label = { Text("All", fontSize = 10.sp) },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                                items(availableTags) { tagItem ->
                                    val isSelected = selectedTagFilter.equals(tagItem, ignoreCase = true)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            selectedTagFilter = if (isSelected) null else tagItem
                                        },
                                        label = { Text(if (tagItem.startsWith("#")) tagItem else "#$tagItem", fontSize = 10.sp) },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (selectedAccountFilterId != "ALL") {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Bank Statement View",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Show chronological running balance sheet",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = isStatementViewEnabled,
                                onCheckedChange = { isStatementViewEnabled = it },
                                modifier = Modifier.testTag("statement_view_switch")
                            )
                        }
                    }
                }
            }

            // Ledger dynamic list render
            if (selectedAccountFilterId != "ALL" && isStatementViewEnabled) {
                val selectedAccountObj = accounts.find { it.id == selectedAccountFilterId }
                if (selectedAccountObj != null) {
                    val cal = Calendar.getInstance()
                    val (startTime, endTime) = when (datePresetFilter) {
                        DateRangePreset.CURRENT_MONTH -> {
                            cal.set(Calendar.DAY_OF_MONTH, 1)
                            cal.set(Calendar.HOUR_OF_DAY, 0)
                            cal.set(Calendar.MINUTE, 0)
                            cal.set(Calendar.SECOND, 0)
                            cal.set(Calendar.MILLISECOND, 0)
                            val start = cal.timeInMillis
                            
                            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                            cal.set(Calendar.HOUR_OF_DAY, 23)
                            cal.set(Calendar.MINUTE, 59)
                            cal.set(Calendar.SECOND, 59)
                            cal.set(Calendar.MILLISECOND, 999)
                            val end = cal.timeInMillis
                            Pair(start, end)
                        }
                        DateRangePreset.PREVIOUS_MONTH -> {
                            cal.add(Calendar.MONTH, -1)
                            cal.set(Calendar.DAY_OF_MONTH, 1)
                            cal.set(Calendar.HOUR_OF_DAY, 0)
                            cal.set(Calendar.MINUTE, 0)
                            cal.set(Calendar.SECOND, 0)
                            cal.set(Calendar.MILLISECOND, 0)
                            val start = cal.timeInMillis
                            
                            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                            cal.set(Calendar.HOUR_OF_DAY, 23)
                            cal.set(Calendar.MINUTE, 59)
                            cal.set(Calendar.SECOND, 59)
                            cal.set(Calendar.MILLISECOND, 999)
                            val end = cal.timeInMillis
                            Pair(start, end)
                        }
                        DateRangePreset.CUSTOM -> {
                            Pair(customStartMillis, customEndMillis)
                        }
                    }

                    val isAssetOrLiability = selectedAccountObj.accountType == "ASSET" || selectedAccountObj.accountType == "LIABILITY"

                    val statementTxWithBalances = remember(allTransactions, selectedAccountFilterId, selectedAccountObj) {
                        val allAccTx = allTransactions.filter {
                            !it.isIgnored &&
                            it.status == TransactionStatus.CONFIRMED &&
                            it.timestamp >= selectedAccountObj.asOfDate &&
                            (it.accountId == selectedAccountObj.id || it.targetAccountId == selectedAccountObj.id)
                        }.sortedBy { it.timestamp }
                        
                        var currentBal = if (isAssetOrLiability) selectedAccountObj.openingBalance else 0.0
                        allAccTx.map { tx ->
                            val isCredit = if (tx.accountId == selectedAccountObj.id) {
                                tx.type == TransactionType.INCOME
                            } else if (tx.targetAccountId == selectedAccountObj.id) {
                                tx.type == TransactionType.TRANSFER
                            } else {
                                false
                            }
                            
                            val isDebit = if (tx.accountId == selectedAccountObj.id) {
                                tx.type == TransactionType.EXPENSE || tx.type == TransactionType.TRANSFER
                            } else {
                                false
                            }
                            
                            val amount = tx.amount
                            if (selectedAccountObj.accountClass == "LIABILITY") {
                                if (selectedAccountObj.type == AccountType.LOAN) {
                                    if (isCredit) currentBal += amount
                                    if (isDebit) currentBal -= amount
                                } else {
                                    if (isDebit) currentBal += amount
                                    if (isCredit) currentBal -= amount
                                }
                            } else {
                                if (isCredit) currentBal += amount
                                if (isDebit) currentBal -= amount
                            }
                            tx to currentBal
                        }
                    }

                    val statementTxFiltered = remember(statementTxWithBalances, startTime, endTime) {
                        statementTxWithBalances.filter { (tx, _) ->
                            tx.timestamp in startTime..endTime
                        }
                    }

                    val firstDisplayedIndex = statementTxWithBalances.indexOfFirst { (tx, _) ->
                        tx.timestamp >= startTime
                    }
                    val periodOpeningBalance = if (firstDisplayedIndex > 0) {
                        statementTxWithBalances[firstDisplayedIndex - 1].second
                    } else {
                        if (isAssetOrLiability) selectedAccountObj.openingBalance else 0.0
                    }

                    val periodClosingBalance = if (statementTxFiltered.isNotEmpty()) {
                        statementTxFiltered.last().second
                    } else {
                        periodOpeningBalance
                    }

                    var totalPeriodDebits = 0.0
                    var totalPeriodCredits = 0.0
                    statementTxFiltered.forEach { (tx, _) ->
                        val isCredit = if (tx.accountId == selectedAccountFilterId) {
                            tx.type == TransactionType.INCOME
                        } else if (tx.targetAccountId == selectedAccountFilterId) {
                            tx.type == TransactionType.TRANSFER
                        } else {
                            false
                        }
                        
                        val isDebit = if (tx.accountId == selectedAccountFilterId) {
                            tx.type == TransactionType.EXPENSE || tx.type == TransactionType.TRANSFER
                        } else {
                            false
                        }
                        
                        if (isCredit) totalPeriodCredits += tx.amount
                        if (isDebit) totalPeriodDebits += tx.amount
                    }

                    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "RECONCILIATION STATEMENT",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Opening Bal (Period)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(formatAmount(periodOpeningBalance), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Closing Bal (Period)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(formatAmount(periodClosingBalance), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Total Out (Debits)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("- " + formatAmount(totalPeriodDebits), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color(0xFFD93025))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Total In (Credits)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("+ " + formatAmount(totalPeriodCredits), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                                    }
                                }
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Date", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Particulars", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.3f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Amount", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Balance", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        if (statementTxFiltered.isEmpty()) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("No transactions in this period", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            ) {
                                items(statementTxFiltered, key = { it.first.id }) { (tx, bal) ->
                                    val isCredit = if (tx.accountId == selectedAccountFilterId) {
                                        tx.type == TransactionType.INCOME
                                    } else if (tx.targetAccountId == selectedAccountFilterId) {
                                        tx.type == TransactionType.TRANSFER
                                    } else {
                                        false
                                    }
                                    
                                    val isDebit = if (tx.accountId == selectedAccountFilterId) {
                                        tx.type == TransactionType.EXPENSE || tx.type == TransactionType.TRANSFER
                                    } else {
                                        false
                                    }
                                    
                                    val displayAmountStr = when {
                                        isCredit -> "+ ${formatAmount(tx.amount)}"
                                        isDebit -> "- ${formatAmount(tx.amount)}"
                                        else -> formatAmount(tx.amount)
                                    }
                                    val amtColor = when {
                                        isCredit -> Color(0xFF10B981)
                                        isDebit -> Color(0xFFD93025)
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                    
                                    val displayDate = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(tx.timestamp))
                                    
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { editingTransaction = tx }
                                            .testTag("statement_row_${tx.id}"),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.05f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp).fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = displayDate,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.width(60.dp)
                                            )
                                            
                                            Column(modifier = Modifier.weight(1.3f)) {
                                                Text(
                                                    text = tx.narration,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (tx.isProbablyDuplicate) {
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            imageVector = Icons.Default.Warning,
                                                            contentDescription = null,
                                                            tint = Color(0xFFD93025),
                                                            modifier = Modifier.size(10.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(3.dp))
                                                        Text(
                                                            text = "Suspected Duplicate",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = Color(0xFFD93025),
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 9.sp
                                                        )
                                                    }
                                                }
                                            }
                                            
                                            Text(
                                                text = displayAmountStr,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = amtColor,
                                                modifier = Modifier.weight(1f),
                                                textAlign = TextAlign.End
                                            )
                                            
                                            Text(
                                                text = formatAmount(bal),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f),
                                                textAlign = TextAlign.End
                                            )
                                        }
                                    }
                                }
                                item {
                                    Spacer(modifier = Modifier.height(84.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                if (filteredLedgerTransactions.filter { it.status == TransactionStatus.CONFIRMED }.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.List,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Ledger matches nothing",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Adjust filters or create a transaction.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        val finalConfirmed = filteredLedgerTransactions.filter { it.status == TransactionStatus.CONFIRMED }
                        items(finalConfirmed, key = { it.id }) { tx ->
                            val matchingAccount = accounts.find { it.id == tx.accountId }
                            val accountLabel = matchingAccount?.name ?: "Family Ledger"
                            
                            val isLocked = matchingAccount?.takeIf { it.accountType == "ASSET" || it.accountType == "LIABILITY" }?.lastReconciledDate?.let { tx.timestamp < it && !isSameDay(tx.timestamp, it) } ?: false

                            if (tx.type == TransactionType.TRANSFER) {
                                val targetAcc = accounts.find { it.id == tx.targetAccountId }
                                val targetLabel = targetAcc?.name ?: "External Account"
                                val isTargetLocked = targetAcc?.takeIf { it.accountType == "ASSET" || it.accountType == "LIABILITY" }?.lastReconciledDate?.let { tx.timestamp < it && !isSameDay(tx.timestamp, it) } ?: false
                                val isAnyLocked = isLocked || isTargetLocked
                                TransferRowItem(
                                    tx = tx,
                                    sourceLabel = accountLabel,
                                    targetLabel = targetLabel,
                                    isLocked = isAnyLocked,
                                    merchantRules = merchantRules,
                                    user1Name = myNameStr,
                                    user2Name = spouseNameStr,
                                    onClick = { editingTransaction = tx }
                                )
                            } else {
                                TransactionRowItem(
                                    tx = tx,
                                    accountName = accountLabel,
                                    isLocked = isLocked,
                                    merchantRules = merchantRules,
                                    user1Name = myNameStr,
                                    user2Name = spouseNameStr,
                                    categories = categories,
                                    onNavigateDetail = { editingTransaction = tx }
                                )
                            }
                        }
                        
                        item {
                            Spacer(modifier = Modifier.height(84.dp))
                        }
                    }
                }
            }
        }
        
        // --- MANUAL ENTRY ADD TRANSACTION FORM DIALOG ---
        if (showAddDialog) {
            var showAddAccountPicker by remember { mutableStateOf(false) }
            var showAddTargetAccountPicker by remember { mutableStateOf(false) }
            var showAddCategoryPicker by remember { mutableStateOf(false) }
            var showAddTagField by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { 
                    showAddTagField = false
                    showAddDialog = false 
                },
                title = { Text("Log Transaction", fontWeight = FontWeight.Bold) },
                confirmButton = {
                    Button(
                        onClick = {
                            val amt = amountStr.toDoubleOrNull() ?: 0.0
                            if (amt > 0 && selectedAccountId.isNotEmpty() && narration.isNotEmpty()) {
                                
                                // RECONCILIATION SAFETY CHECK (CRITICAL FORCE) ON ADD
                                val targetAccObj = accounts.find { it.id == selectedAccountId }
                                val lastRecDate = targetAccObj?.takeIf { it.accountType == "ASSET" || it.accountType == "LIABILITY" }?.lastReconciledDate
                                val isLocked = lastRecDate?.let { dateMillis < it && !isSameDay(dateMillis, it) } ?: false
                                
                                var isTargetLocked = false
                                if (txType == TransactionType.TRANSFER && selectedTargetAccountId != null) {
                                    val targetAccObj2 = accounts.find { it.id == selectedTargetAccountId }
                                    val targetLastRec = targetAccObj2?.takeIf { it.accountType == "ASSET" || it.accountType == "LIABILITY" }?.lastReconciledDate
                                    isTargetLocked = targetLastRec?.let { dateMillis < it && !isSameDay(dateMillis, it) } ?: false
                                }
                                
                                if (isLocked || isTargetLocked) {
                                    Toast.makeText(context, "Locked Period: Transaction belongs to a closed/reconciled accounting session.", Toast.LENGTH_LONG).show()
                                } else {
                                    viewModel.addManualTransaction(
                                        amount = amt,
                                        type = txType,
                                        category = selectedCategoryId,
                                        narration = narration.trim(),
                                        accountId = selectedAccountId,
                                        targetAccountId = if (txType == TransactionType.TRANSFER) selectedTargetAccountId else null,
                                        paidBy = paidBy,
                                        scope = scope,
                                        dateMillis = dateMillis,
                                        tag = tagStr.trim()
                                    )
                                    // Reset Form
                                    amountStr = ""
                                    narration = ""
                                    tagStr = ""
                                    showAddTagField = false
                                    selectedTargetAccountId = null
                                    showAddDialog = false
                                }
                            }
                        },
                        enabled = amountStr.toDoubleOrNull() != null && narration.isNotEmpty()
                    ) {
                        Text("Add to Ledger")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        showAddTagField = false
                        showAddDialog = false 
                    }) {
                        Text("Cancel")
                    }
                },
                text = {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 450.dp)
                    ) {
                        item {
                            TabRow(
                                selectedTabIndex = when (txType) {
                                    TransactionType.EXPENSE -> 0
                                    TransactionType.INCOME -> 1
                                    TransactionType.TRANSFER -> 2
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Tab(
                                    selected = txType == TransactionType.EXPENSE,
                                    onClick = { 
                                        txType = TransactionType.EXPENSE
                                        selectedCategoryId = "Others"
                                    }
                                ) {
                                    Text("Expense", modifier = Modifier.padding(10.dp), fontWeight = FontWeight.Bold)
                                }
                                Tab(
                                    selected = txType == TransactionType.INCOME,
                                    onClick = { 
                                        txType = TransactionType.INCOME
                                        selectedCategoryId = "Salary"
                                    }
                                ) {
                                    Text("Income", modifier = Modifier.padding(10.dp), fontWeight = FontWeight.Bold)
                                }
                                Tab(
                                    selected = txType == TransactionType.TRANSFER,
                                    onClick = { 
                                        txType = TransactionType.TRANSFER
                                        selectedCategoryId = "Transfer"
                                        if (selectedTargetAccountId == null && accounts.size > 1) {
                                            selectedTargetAccountId = accounts.firstOrNull { it.id != selectedAccountId }?.id
                                        }
                                    }
                                ) {
                                    Text("Transfer", modifier = Modifier.padding(10.dp), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        item {
                            OutlinedTextField(
                                value = amountStr,
                                onValueChange = { amountStr = it },
                                label = { Text("Amount") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .testTag("amount_field"),
                                singleLine = true
                            )
                        }

                        item {
                            OutlinedTextField(
                                value = narration,
                                onValueChange = { narration = it },
                                label = { Text("Vendor / Narration") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                placeholder = { Text("Starbucks, rent etc") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .testTag("narration_field"),
                                singleLine = true
                            )
                        }

                        item {
                            if (!showAddTagField && tagStr.isBlank()) {
                                Surface(
                                    onClick = { showAddTagField = true },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .testTag("add_tag_button")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Add,
                                                    contentDescription = "Add tag",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Add Event / Trip Tag",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = "Optional • Tag for trips, renovations, etc.",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                        .padding(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Bookmark,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Event / Trip Tag (Optional)",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                tagStr = ""
                                                showAddTagField = false
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Remove tag",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    OutlinedTextField(
                                        value = tagStr,
                                        onValueChange = { tagStr = it },
                                        placeholder = { Text("#Trip, #Renovation, #Event", fontSize = 11.sp) },
                                        leadingIcon = { Text("#", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    if (availableTags.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        androidx.compose.foundation.lazy.LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            items(availableTags) { exTag ->
                                                val isSelected = tagStr.equals(exTag, ignoreCase = true)
                                                Surface(
                                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.clickable { tagStr = exTag }
                                                ) {
                                                    Text(
                                                        text = if (exTag.startsWith("#")) exTag else "#$exTag",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (suggestedCategory != null) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                        .clickable { selectedCategoryId = suggestedCategory }
                                        .padding(8.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Auto-Suggest category: '$suggestedCategory'. Tap to apply.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            val pickDateText = remember(dateMillis) {
                                SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date(dateMillis))
                            }
                            OutlinedTextField(
                                value = pickDateText,
                                onValueChange = {},
                                label = { Text("Payment Date") },
                                readOnly = true,
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            DatePickerDialog(
                                                context,
                                                { _, year, month, day ->
                                                    calendar.set(year, month, day)
                                                    dateMillis = calendar.timeInMillis
                                                },
                                                calendar.get(Calendar.YEAR),
                                                calendar.get(Calendar.MONTH),
                                                calendar.get(Calendar.DAY_OF_MONTH)
                                            ).show()
                                        }
                                    ) {
                                        Icon(Icons.Default.DateRange, contentDescription = "Pick date")
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            )
                        }

                        val currentSelectedAcc = accounts.find { it.id == selectedAccountId }
                        val isSharedAccount = currentSelectedAcc?.ownerUserId == "SHARED" || currentSelectedAcc?.ownerUserId == "Shared"

                        item {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text("Paid For (Responsibility)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val options = listOf(
                                        Triple("BOTH", "Family", Icons.Default.Group),
                                        Triple("User 1", myNameStr, Icons.Default.Person),
                                        Triple("User 2", spouseNameStr, Icons.Default.Person)
                                    )
                                    options.forEach { (v, lbl, icon) ->
                                        val isSel = when (v) {
                                            "BOTH" -> paidBy == "BOTH" || paidBy.isBlank()
                                            "User 1" -> paidBy == "User 1" || (myUid != null && paidBy == myUid)
                                            "User 2" -> paidBy == "User 2" || (spouseUid != null && paidBy == spouseUid)
                                            else -> false
                                        }
                                        Surface(
                                            onClick = { paidBy = v },
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(
                                                1.dp,
                                                if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                            ),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center,
                                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp)
                                            ) {
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = null,
                                                    tint = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = lbl, 
                                                    fontSize = 11.sp, 
                                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Searchable Source / Payment Account Picker
                        item {
                            val currentAcc = accounts.find { it.id == selectedAccountId } ?: accounts.firstOrNull()
                            AccountPickerField(
                                label = if (txType == TransactionType.TRANSFER) "Source Account" else "Payment Account",
                                selectedAccount = currentAcc,
                                onClick = { showAddAccountPicker = true },
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        // Searchable Destination Account Picker (when Transfer)
                        if (txType == TransactionType.TRANSFER) {
                            item {
                                val currentTarget = accounts.find { it.id == selectedTargetAccountId }
                                AccountPickerField(
                                    label = "Destination Account",
                                    selectedAccount = currentTarget,
                                    onClick = { showAddTargetAccountPicker = true },
                                    placeholder = "Select destination...",
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }

                        // Searchable Category Picker (when not Transfer)
                        if (txType != TransactionType.TRANSFER) {
                            item {
                                val targetClass = if (txType == TransactionType.INCOME) "INCOME" else "EXPENSE"
                                val quickCats = remember(categories, txType) {
                                    val filtered = categories.filter {
                                        it.accountClass.equals(targetClass, ignoreCase = true) ||
                                        it.accountType.equals(targetClass, ignoreCase = true) ||
                                        (it.accountClass.isBlank() && it.accountType.isBlank())
                                    }.map { it.name.trim() }.filter { it.isNotBlank() && !it.equals("Transfer", ignoreCase = true) }.distinct()

                                    if (filtered.isNotEmpty()) filtered.take(6)
                                    else if (txType == TransactionType.INCOME) listOf("Salary", "Investment Return", "Refund", "Gifts")
                                    else listOf("Food & Dining", "Groceries", "Utilities", "Housing", "Shopping", "Travel")
                                }

                                CategoryPickerField(
                                    label = "Transaction Category",
                                    selectedCategory = selectedCategoryId,
                                    onClick = { showAddCategoryPicker = true },
                                    onQuickSelect = { selectedCategoryId = it },
                                    quickSuggestions = quickCats,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            )

            // Searchable Pickers Bottom Sheets
            SearchableAccountPickerBottomSheet(
                visible = showAddAccountPicker,
                onDismiss = { showAddAccountPicker = false },
                accounts = accounts,
                selectedAccountId = selectedAccountId,
                onAccountSelected = { selectedAccountId = it.id },
                title = if (txType == TransactionType.TRANSFER) "Select Source Account" else "Select Payment Account"
            )

            SearchableAccountPickerBottomSheet(
                visible = showAddTargetAccountPicker,
                onDismiss = { showAddTargetAccountPicker = false },
                accounts = accounts,
                selectedAccountId = selectedTargetAccountId ?: "",
                excludedAccountId = selectedAccountId,
                onAccountSelected = { selectedTargetAccountId = it.id },
                title = "Select Destination Account"
            )

            SearchableCategoryPickerBottomSheet(
                visible = showAddCategoryPicker,
                onDismiss = { showAddCategoryPicker = false },
                categories = categories,
                selectedCategory = selectedCategoryId,
                onCategorySelected = { selectedCategoryId = it },
                transactionType = txType
            )
        }

        // --- REAL TRANSACTION EDITING DIALOG (WITH SAFETY LOCK TESTS & 1-TAP SMART RULES) ---
        editingTransaction?.let { editingTx ->
            var showEditAccountPicker by remember { mutableStateOf(false) }
            var showEditTargetAccountPicker by remember { mutableStateOf(false) }
            var showEditCategoryPicker by remember { mutableStateOf(false) }

            var editAmountStr by remember { mutableStateOf(editingTx.amount.toString()) }
            var editNarration by remember { mutableStateOf(editingTx.narration) }
            var editTag by remember { mutableStateOf(editingTx.tag) }
            var showEditTagField by remember(editingTx.id) { mutableStateOf(editingTx.tag.isNotBlank()) }
            var editCategoryId by remember { mutableStateOf(editingTx.category) }
            var editAccountId by remember { mutableStateOf(editingTx.accountId) }
            var editTargetAccountId by remember { mutableStateOf(editingTx.targetAccountId) }
            var editDateMillis by remember { mutableStateOf(editingTx.timestamp) }
            var editPaidBy by remember { mutableStateOf(editingTx.paidByUserId) }
            var editScope by remember { mutableStateOf(editingTx.scope) }
            
            var editTxType by remember { mutableStateOf(editingTx.type) }
            var editErrorText by remember { mutableStateOf<String?>(null) }
            
            // Smart Override Rule State
            val defaultKeyword = remember(editingTx) {
                val clean = (editingTx.payeeName.ifBlank { editingTx.narration })
                    .replace(Regex("^(Spent at|Paid to|Transferred to)\\s*", RegexOption.IGNORE_CASE), "")
                    .substringAfter(" - ")
                    .trim()
                    .uppercase()
                clean.ifBlank { "MERCHANT" }
            }
            var createSmartRule by remember(editingTx.id) { mutableStateOf(false) }
            var ruleKeyword by remember(editingTx.id) { mutableStateOf(defaultKeyword) }
            var applyToHistory by remember(editingTx.id) { mutableStateOf(true) }

            val editCalendar = remember { Calendar.getInstance().apply { timeInMillis = editDateMillis } }

            val isTxLocked = remember(editAccountId, editDateMillis, editTargetAccountId, editTxType) {
                val targetAccObj = accounts.find { it.id == editAccountId }
                val lastRecDate = targetAccObj?.takeIf { it.accountType == "ASSET" || it.accountType == "LIABILITY" }?.lastReconciledDate
                val isLocked = lastRecDate?.let { editDateMillis < it && !isSameDay(editDateMillis, it) } ?: false
                val isOriginalLocked = lastRecDate?.let { editingTx.timestamp < it && !isSameDay(editingTx.timestamp, it) } ?: false
                
                var isTargetLocked = false
                var isOriginalTargetLocked = false
                if (editTxType == TransactionType.TRANSFER && editTargetAccountId != null) {
                    val targetAccObj2 = accounts.find { it.id == editTargetAccountId }
                    val targetLastRec = targetAccObj2?.takeIf { it.accountType == "ASSET" || it.accountType == "LIABILITY" }?.lastReconciledDate
                    isTargetLocked = targetLastRec?.let { editDateMillis < it && !isSameDay(editDateMillis, it) } ?: false
                    isOriginalTargetLocked = targetLastRec?.let { editingTx.timestamp < it && !isSameDay(editingTx.timestamp, it) } ?: false
                }
                isLocked || isOriginalLocked || isTargetLocked || isOriginalTargetLocked
            }

            LaunchedEffect(isTxLocked) {
                if (isTxLocked) {
                    editErrorText = "Locked Period: Transaction belongs to a closed/reconciled accounting session."
                } else {
                    editErrorText = null
                }
            }

            AlertDialog(
                onDismissRequest = { editingTransaction = null },
                title = { Text("Edit/Delete Transaction Ledger Entry", fontWeight = FontWeight.Bold) },
                confirmButton = {
                    Button(
                        onClick = {
                            if (!isTxLocked) {
                                val finalCat = if (editTxType == TransactionType.TRANSFER) "Transfer" else editCategoryId
                                val updated = editingTx.copy(
                                    amount = editAmountStr.toDoubleOrNull() ?: editingTx.amount,
                                    narration = editNarration.trim(),
                                    payeeName = editNarration.trim(),
                                    category = finalCat,
                                    accountId = editAccountId,
                                    targetAccountId = if (editTxType == TransactionType.TRANSFER) editTargetAccountId else null,
                                    timestamp = editDateMillis,
                                    paidByUserId = editPaidBy,
                                    scope = editScope,
                                    type = editTxType,
                                    tag = editTag.trim()
                                )
                                viewModel.updateTransaction(updated)

                                if (createSmartRule && editTxType != TransactionType.TRANSFER && ruleKeyword.trim().isNotEmpty()) {
                                    viewModel.addMerchantRule(
                                        keyword = ruleKeyword.trim().uppercase(),
                                        category = finalCat,
                                        updateExistingTransactions = applyToHistory
                                    )
                                }

                                editingTransaction = null
                            }
                        },
                        enabled = !isTxLocked && editAmountStr.toDoubleOrNull() != null && editNarration.trim().isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!isTxLocked) {
                                    viewModel.deleteTransaction(editingTx.id)
                                    editingTransaction = null
                                }
                            },
                            enabled = !isTxLocked,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete")
                        }
                        
                        TextButton(onClick = { editingTransaction = null }) {
                            Text("Cancel")
                        }
                    }
                },
                text = {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                    ) {
                        // Display safety block alerts
                        if (editErrorText != null) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                                ) {
                                    Text(
                                        text = editErrorText!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(10.dp),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(
                                    TransactionType.EXPENSE to "Expense",
                                    TransactionType.INCOME to "Income",
                                    TransactionType.TRANSFER to "Transfer"
                                ).forEach { (type, label) ->
                                    val isSelected = editTxType == type
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                                            .clickable(enabled = !isTxLocked) {
                                                editTxType = type
                                                if (type == TransactionType.EXPENSE) editCategoryId = "Others"
                                                if (type == TransactionType.INCOME) editCategoryId = "Salary"
                                                if (type == TransactionType.TRANSFER) {
                                                    editCategoryId = "Transfer"
                                                    if (editTargetAccountId == null && accounts.size > 1) {
                                                        editTargetAccountId = accounts.firstOrNull { it.id != editAccountId }?.id
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = editAmountStr,
                                    onValueChange = { editAmountStr = it },
                                    label = { Text("Amount (₹)", fontSize = 11.sp) },
                                    enabled = !isTxLocked,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )

                                val pickDateText = remember(editDateMillis) {
                                    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(editDateMillis))
                                }
                                OutlinedTextField(
                                    value = pickDateText,
                                    onValueChange = {},
                                    label = { Text("Date", fontSize = 11.sp) },
                                    readOnly = true,
                                    enabled = !isTxLocked,
                                    shape = RoundedCornerShape(10.dp),
                                    trailingIcon = {
                                        IconButton(
                                            enabled = !isTxLocked,
                                            onClick = {
                                                DatePickerDialog(
                                                    context,
                                                    { _, year, month, day ->
                                                        editCalendar.set(year, month, day)
                                                        editDateMillis = editCalendar.timeInMillis
                                                    },
                                                    editCalendar.get(Calendar.YEAR),
                                                    editCalendar.get(Calendar.MONTH),
                                                    editCalendar.get(Calendar.DAY_OF_MONTH)
                                                ).show()
                                            }
                                        ) {
                                            Icon(Icons.Default.DateRange, contentDescription = "Pick date", modifier = Modifier.size(18.dp))
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        item {
                            OutlinedTextField(
                                value = editNarration,
                                onValueChange = { editNarration = it },
                                label = { Text("Vendor / Narration", fontSize = 11.sp) },
                                enabled = !isTxLocked,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        item {
                            if (!showEditTagField && editTag.isBlank()) {
                                Surface(
                                    onClick = { if (!isTxLocked) showEditTagField = true },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .testTag("edit_add_tag_button")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.Add,
                                                    contentDescription = "Add tag",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Add Event / Trip Tag",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = "Optional • Tag for trips, renovations, etc.",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                        .padding(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Bookmark,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Event / Trip Tag (Optional)",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        if (!isTxLocked) {
                                            IconButton(
                                                onClick = {
                                                    editTag = ""
                                                    showEditTagField = false
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Remove tag",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    OutlinedTextField(
                                        value = editTag,
                                        onValueChange = { editTag = it },
                                        placeholder = { Text("#Trip, #Renovation, #Event", fontSize = 11.sp) },
                                        leadingIcon = { Text("#", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                                        enabled = !isTxLocked,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                    if (availableTags.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        androidx.compose.foundation.lazy.LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            items(availableTags) { exTag ->
                                                val isSelected = editTag.equals(exTag, ignoreCase = true)
                                                Surface(
                                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                                    border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.clickable(enabled = !isTxLocked) { editTag = exTag }
                                                ) {
                                                    Text(
                                                        text = if (exTag.startsWith("#")) exTag else "#$exTag",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text("Paid For (Responsibility)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val options = listOf(
                                        Triple("BOTH", "Family", Icons.Default.Group),
                                        Triple("User 1", myNameStr, Icons.Default.Person),
                                        Triple("User 2", spouseNameStr, Icons.Default.Person)
                                    )
                                    options.forEach { (v, lbl, icon) ->
                                        val isSel = when (v) {
                                            "BOTH" -> editPaidBy == "BOTH" || editPaidBy.isBlank()
                                            "User 1" -> editPaidBy == "User 1" || (myUid != null && editPaidBy == myUid)
                                            "User 2" -> editPaidBy == "User 2" || (spouseUid != null && editPaidBy == spouseUid)
                                            else -> false
                                        }
                                        Surface(
                                            onClick = { if (!isTxLocked) editPaidBy = v },
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(
                                                1.dp,
                                                if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                            ),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center,
                                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp)
                                            ) {
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = null,
                                                    tint = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = lbl, 
                                                    fontSize = 11.sp, 
                                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Searchable Source / Payment Account Picker
                        item {
                            val currentAcc = accounts.find { it.id == editAccountId } ?: accounts.firstOrNull()
                            AccountPickerField(
                                label = if (editTxType == TransactionType.TRANSFER) "Source Account" else "Payment Account",
                                selectedAccount = currentAcc,
                                onClick = { showEditAccountPicker = true },
                                enabled = !isTxLocked,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        // Searchable Destination Account Picker (when Transfer)
                        if (editTxType == TransactionType.TRANSFER) {
                            item {
                                val currentTarget = accounts.find { it.id == editTargetAccountId }
                                AccountPickerField(
                                    label = "Destination Account",
                                    selectedAccount = currentTarget,
                                    onClick = { showEditTargetAccountPicker = true },
                                    enabled = !isTxLocked,
                                    placeholder = "Select destination...",
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }

                        // Searchable Category Picker (when not Transfer)
                        if (editTxType != TransactionType.TRANSFER) {
                            item {
                                val targetClass = if (editTxType == TransactionType.INCOME) "INCOME" else "EXPENSE"
                                val quickCats = remember(categories, editTxType) {
                                    val filtered = categories.filter {
                                        it.accountClass.equals(targetClass, ignoreCase = true) ||
                                        it.accountType.equals(targetClass, ignoreCase = true) ||
                                        (it.accountClass.isBlank() && it.accountType.isBlank())
                                    }.map { it.name.trim() }.filter { it.isNotBlank() && !it.equals("Transfer", ignoreCase = true) }.distinct()

                                    if (filtered.isNotEmpty()) filtered.take(6)
                                    else if (editTxType == TransactionType.INCOME) listOf("Salary", "Investment Return", "Refund", "Gifts")
                                    else listOf("Food & Dining", "Groceries", "Utilities", "Housing", "Shopping", "Travel")
                                }

                                CategoryPickerField(
                                    label = "Transaction Category",
                                    selectedCategory = editCategoryId,
                                    onClick = { showEditCategoryPicker = true },
                                    onQuickSelect = { editCategoryId = it },
                                    quickSuggestions = quickCats,
                                    enabled = !isTxLocked,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }

                        if (editTxType != TransactionType.TRANSFER) {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (createSmartRule) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                                         else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (createSmartRule) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.AutoAwesome,
                                                    contentDescription = null,
                                                    tint = if (createSmartRule) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(
                                                        text = "Smart Override Rule",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (createSmartRule) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = "Always categorize as '$editCategoryId'",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                            Switch(
                                                checked = createSmartRule,
                                                onCheckedChange = { createSmartRule = it },
                                                enabled = !isTxLocked,
                                                modifier = Modifier.testTag("toggle_smart_rule_in_edit")
                                            )
                                        }

                                        if (createSmartRule) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            OutlinedTextField(
                                                value = ruleKeyword,
                                                onValueChange = { ruleKeyword = it.uppercase() },
                                                label = { Text("SMS Merchant Keyword", fontSize = 11.sp) },
                                                placeholder = { Text("e.g. SWIGGY, UBER, NETFLIX", fontSize = 11.sp) },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true,
                                                enabled = !isTxLocked,
                                                shape = RoundedCornerShape(8.dp),
                                                leadingIcon = {
                                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                                }
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable(enabled = !isTxLocked) { applyToHistory = !applyToHistory }
                                            ) {
                                                Checkbox(
                                                    checked = applyToHistory,
                                                    onCheckedChange = { applyToHistory = it },
                                                    enabled = !isTxLocked,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Also update matching past transactions",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            )

            // Searchable Pickers Bottom Sheets for Edit
            SearchableAccountPickerBottomSheet(
                visible = showEditAccountPicker,
                onDismiss = { showEditAccountPicker = false },
                accounts = accounts,
                selectedAccountId = editAccountId,
                onAccountSelected = { editAccountId = it.id },
                title = if (editTxType == TransactionType.TRANSFER) "Select Source Account" else "Select Payment Account"
            )

            SearchableAccountPickerBottomSheet(
                visible = showEditTargetAccountPicker,
                onDismiss = { showEditTargetAccountPicker = false },
                accounts = accounts,
                selectedAccountId = editTargetAccountId ?: "",
                excludedAccountId = editAccountId,
                onAccountSelected = { editTargetAccountId = it.id },
                title = "Select Destination Account"
            )

            SearchableCategoryPickerBottomSheet(
                visible = showEditCategoryPicker,
                onDismiss = { showEditCategoryPicker = false },
                categories = categories,
                selectedCategory = editCategoryId,
                onCategorySelected = { editCategoryId = it },
                transactionType = editTxType
            )
        }
    }
}

@Composable
fun TransferRowItem(
    tx: Transaction,
    sourceLabel: String,
    targetLabel: String,
    isLocked: Boolean = false,
    merchantRules: List<MerchantRule> = emptyList(),
    user1Name: String = "Tobin",
    user2Name: String = "Soumya",
    onClick: () -> Unit = {}
) {
    val myUid by FirebaseService.currentUserUid.collectAsState()
    val spouseUid by FirebaseService.spouseUserUid.collectAsState()

    val paidByLabel = remember(tx.paidByUserId, user1Name, user2Name, myUid, spouseUid) {
        when {
            tx.paidByUserId.equals("BOTH", ignoreCase = true) || tx.paidByUserId.isBlank() -> "Shared Split"
            tx.paidByUserId == "User 1" || (myUid != null && tx.paidByUserId == myUid) -> "For $user1Name"
            tx.paidByUserId == "User 2" || (spouseUid != null && tx.paidByUserId == spouseUid) -> "For $user2Name"
            else -> "For ${tx.paidByUserId}"
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable { onClick() }
            .testTag("transfer_row_${tx.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isLocked) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                             else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon Container
                val iconColor = if (isLocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(iconColor.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))

                // Content
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tx.narration,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isLocked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Elegant Transfer Category Badge
                        val catColor = if (isLocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(catColor.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Transfer",
                                style = MaterialTheme.typography.labelSmall,
                                color = catColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            text = formatDate(tx.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    TransactionTagsRow(tx = tx, merchantRules = merchantRules)
                    if (!tx.originalSmsText.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = tx.originalSmsText,
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // Amount & Paid By Badge
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = formatAmount(tx.amount),
                        fontWeight = FontWeight.Bold,
                        color = if (isLocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (isLocked) {
                            Icon(
                                 imageVector = Icons.Default.Lock,
                                 contentDescription = "Locked",
                                 tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                 modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Locked",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        
                        // Elegant "Paid By" pill indicator
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = paidByLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(10.dp))
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.05f))
                ) {
                    Text(
                        text = sourceLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(14.dp)
                )

                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                ) {
                    Text(
                        text = targetLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun isSameDay(millis1: Long, millis2: Long): Boolean {
    val cal1 = Calendar.getInstance().apply { timeInMillis = millis1 }
    val cal2 = Calendar.getInstance().apply { timeInMillis = millis2 }
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
           cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}
