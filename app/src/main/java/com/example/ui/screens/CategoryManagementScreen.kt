package com.example.ui.screens

import android.app.DatePickerDialog
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.Account
import com.example.data.AccountType
import com.example.domain.FreeToSpendRules
import com.example.domain.ReconcileRules
import com.example.ui.viewmodel.ExpenseViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CategoryManagementScreen(
    viewModel: ExpenseViewModel,
    onBackClick: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBackClick() }
    val categories by viewModel.categories.collectAsState()
    // Same list with ledger balances worked out.
    val accountsWithBalances by viewModel.accounts.collectAsState()
    val myUid by viewModel.currentUserUid.collectAsState()
    val partnerName = viewModel.spouseUserFirstName.collectAsState().value ?: "Partner"

    // 0 = Accounts, 1 = Spending categories, 2 = Income categories
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<Account?>(null) }
    var reconcilingAccount by remember { mutableStateOf<Account?>(null) }
    var confirmDelete by remember { mutableStateOf<Account?>(null) }

    val balances = remember(accountsWithBalances) { accountsWithBalances.associate { it.accountId to it.balance } }
    val now = remember { System.currentTimeMillis() }

    val accountGroups = remember(categories) {
        val real = categories.filter { it.accountClass.uppercase() == "ASSET" || it.accountClass.uppercase() == "LIABILITY" || it.accountClass.uppercase() == "EQUITY" }
        listOf(
            "Bank & cash" to real.filter { FreeToSpendRules.isLiquid(it) },
            "Credit cards" to real.filter { FreeToSpendRules.isCard(it) },
            "Loans" to real.filter { it.accountType == "LIABILITY" && it.type == AccountType.LOAN },
            "Investments" to real.filter { it.accountType == "ASSET" && (it.type == AccountType.FIXED_DEPOSIT || it.type == AccountType.MUTUAL_FUND) },
            "Other" to real.filter { it.accountClass.uppercase() == "EQUITY" }
        ).map { (title, list) -> title to list.sortedBy { it.name.lowercase() } }.filter { it.second.isNotEmpty() }
    }

    val categoryClass = if (tab == 2) "INCOME" else "EXPENSE"
    val categoryGroups = remember(categories, tab, searchQuery) {
        val q = searchQuery.trim().lowercase()
        categories
            .filter { it.accountClass.uppercase() == categoryClass }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.mainCategory.lowercase().contains(q) }
            .groupBy { it.mainCategory.ifBlank { "General" } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }

    fun ownerLabel(a: Account): String = when {
        a.ownerUserId.equals("SHARED", true) || a.ownerUserId.isBlank() -> "Joint"
        a.ownerUserId == myUid -> "Yours"
        else -> partnerName
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
                onClick = { showAddDialog = true },
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

            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)
            ) {
                if (tab == 0) {
                    if (accountGroups.isEmpty()) {
                        item { EmptyHint("No accounts yet. Add your bank accounts and cards so balances and Free to spend work.") }
                    }
                    accountGroups.forEach { (title, list) ->
                        item(key = "h_$title") {
                            val total = list.sumOf { balances[it.accountId] ?: 0.0 }
                            GroupHeader(title, formatAmount(kotlin.math.abs(total)))
                        }
                        items(list.size, key = { list[it].accountId }) { i ->
                            val acc = list[i]
                            AccountRow(
                                account = acc,
                                balance = balances[acc.accountId] ?: 0.0,
                                owner = ownerLabel(acc),
                                now = now,
                                onClick = { editingCategory = acc },
                                onReconcile = { reconcilingAccount = acc }
                            )
                        }
                    }
                } else {
                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search categories") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear") }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                        )
                    }
                    if (categoryGroups.isEmpty()) {
                        item { EmptyHint(if (searchQuery.isBlank()) "No categories yet." else "Nothing matches \"$searchQuery\".") }
                    }
                    categoryGroups.forEach { (group, list) ->
                        item(key = "g_$group") { GroupHeader(group, "${list.size}") }
                        item(key = "c_$group") {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                list.sortedBy { it.name.lowercase() }.forEach { cat ->
                                    AssistChip(
                                        onClick = { editingCategory = cat },
                                        label = { Text(cat.name) },
                                        leadingIcon = { Icon(getCategoryIcon(cat.name, cat.iconName), contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- ADD DIALOG ---
    if (showAddDialog) {
        AddAccountOrNodeDialog(
            viewModel = viewModel,
            onDismissRequest = { showAddDialog = false },
            initialSelectedClass = when (tab) { 1 -> "EXPENSE"; 2 -> "INCOME"; else -> "ASSET" }
        )
    }

    // --- DELETE CONFIRMATION ---
    confirmDelete?.let { acc ->
        val isRealAccount = acc.accountType == "ASSET" || acc.accountType == "LIABILITY"
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${acc.name}?") },
            text = {
                Text(
                    if (isRealAccount) "Its transactions stay, but they will no longer count towards any balance. This can't be undone."
                    else "Transactions filed under it keep the name but won't belong to a category. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteCategory(acc.id); confirmDelete = null; editingCategory = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }

    // --- RECONCILE POPUP ---
    reconcilingAccount?.let { acc ->
        ReconcileAccountDialog(
            account = acc,
            onDismiss = { reconcilingAccount = null },
            viewModel = viewModel
        )
    }
    
    // --- ACTIONS / RENAME / DELETE / EXTENDED CONFIGURATION DIALOG ---
    editingCategory?.let { category ->
        val context = LocalContext.current
        val isAssetOrLiability = category.accountClass.uppercase() == "ASSET" || category.accountClass.uppercase() == "LIABILITY"
        val partnerAName = viewModel.currentUserFirstName.collectAsState().value?.ifBlank { "User 1" } ?: "User 1"
        val partnerBName = viewModel.spouseUserFirstName.collectAsState().value?.ifBlank { "User 2" } ?: "User 2"

        var renameName by remember { mutableStateOf(category.name) }
        var mainCategoryInput by remember { mutableStateOf(category.mainCategory) }
        var iconSelected by remember { mutableStateOf(category.iconName) }

        // Extended configuration fields for active tracking accounts
        var enableRealTimeTracking by remember { mutableStateOf(category.lastFourDigits.isNotBlank() || category.openingBalance != 0.0) }
        var initialBalanceStr by remember { mutableStateOf(category.openingBalance.toString()) }
        var lastFourDigits by remember { mutableStateOf(category.lastFourDigits) }
        var owner by remember { mutableStateOf(if (category.ownerUserId.isBlank()) "Combined" else category.ownerUserId) }
        var asOfDate by remember { mutableStateOf(category.asOfDate) }
        
        var annualRateStr by remember { mutableStateOf(category.annualInterestRate.toString()) }
        var emiStr by remember { mutableStateOf(category.monthlyEmi.toString()) }
        var remainingMonthsStr by remember { mutableStateOf(category.remainingMonths.toString()) }
        var billingCycleDayStr by remember { mutableStateOf(if (category.billingCycleDay > 0) category.billingCycleDay.toString() else "") }
        var paymentDueDayStr by remember { mutableStateOf(if (category.paymentDueDay > 0) category.paymentDueDay.toString() else "") }
        
        val calendar = remember { Calendar.getInstance() }

        AlertDialog(
            onDismissRequest = { editingCategory = null },
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = if (category.isSystemDefault) "System Default Ledger" else "Configure ${category.accountClass}",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Box(modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            OutlinedTextField(
                                value = renameName,
                                onValueChange = { if (!category.isSystemDefault) renameName = it },
                                label = { Text("Account/Category Name") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                readOnly = category.isSystemDefault,
                                modifier = Modifier.fillMaxWidth().testTag("category_rename_input")
                            )
                        }
                        
                        item {
                            OutlinedTextField(
                                value = mainCategoryInput,
                                onValueChange = { if (!category.isSystemDefault) mainCategoryInput = it },
                                label = { Text("Main Category / Group") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                readOnly = category.isSystemDefault,
                                modifier = Modifier.fillMaxWidth().testTag("category_main_modify")
                            )
                        }
                        
                        item {
                            Column {
                                Text(
                                    text = "Assign Icon",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                
                                androidx.compose.foundation.lazy.LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(horizontal = 2.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    categoryIconPack.forEach { (name, iconVector) ->
                                        item {
                                            val isSelected = iconSelected == name
                                            Surface(
                                                onClick = { iconSelected = name },
                                                shape = CircleShape,
                                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                                modifier = Modifier.size(44.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = iconVector,
                                                        contentDescription = name,
                                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ADVANCED LEDGER & REAL-TIME RECONCILIATION CONFIGURATION (Assets/Liabilities)
                        if (isAssetOrLiability) {
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                                    ),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    "Real-Time SMS Sync",
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                                Text(
                                                    "Link live bank ledger balances and track automated banking SMS.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                                )
                                            }
                                            Switch(
                                                checked = enableRealTimeTracking,
                                                onCheckedChange = { enableRealTimeTracking = it },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = MaterialTheme.colorScheme.secondary,
                                                    checkedTrackColor = MaterialTheme.colorScheme.secondaryContainer
                                                )
                                            )
                                        }

                                        AnimatedVisibility(visible = enableRealTimeTracking) {
                                            Column(
                                                modifier = Modifier.padding(top = 12.dp),
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Divider(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f))
                                                Spacer(modifier = Modifier.height(2.dp))

                                                val activeBalanceLabel = if (mainCategoryInput == "Loans") "Outstanding Principal" else "Starting Balance"
                                                val prefixSymbol = if (mainCategoryInput == "Loans") "-" else "₹ "
                                                
                                                OutlinedTextField(
                                                    value = initialBalanceStr,
                                                    onValueChange = { initialBalanceStr = it },
                                                    label = { Text(activeBalanceLabel) },
                                                    leadingIcon = { 
                                                        Text(
                                                            prefixSymbol, 
                                                            fontWeight = FontWeight.Bold, 
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.padding(start = 12.dp)
                                                        ) 
                                                    },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    shape = RoundedCornerShape(12.dp),
                                                    modifier = Modifier.fillMaxWidth(),
                                                    singleLine = true
                                                )

                                                OutlinedTextField(
                                                    value = lastFourDigits,
                                                    onValueChange = { input ->
                                                        if (input.all { char -> char.isDigit() || char == ',' || char == ' ' || char == '/' }) {
                                                            lastFourDigits = input
                                                        }
                                                    },
                                                    label = { Text("Account & Debit Card Last Digits") },
                                                    placeholder = { Text("e.g. 5678, 1234 (Account #, Debit Card)") },
                                                    supportingText = { Text("Add both Account and Debit Card last 4 digits separated by comma for automatic SMS matching", fontSize = 10.sp) },
                                                    leadingIcon = { Icon(Icons.Default.Pin, contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
                                                    shape = RoundedCornerShape(12.dp),
                                                    modifier = Modifier.fillMaxWidth(),
                                                    singleLine = true
                                                )

                                                Column {
                                                    Text(
                                                        "Account Ownership", 
                                                        style = MaterialTheme.typography.labelSmall, 
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.secondary,
                                                        modifier = Modifier.padding(bottom = 6.dp)
                                                    )
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        val owners = listOf(
                                                            Triple("User 1", partnerAName, Icons.Default.Person),
                                                            Triple("User 2", partnerBName, Icons.Default.Person),
                                                            Triple("Combined", "Family", Icons.Default.Group)
                                                        )
                                                        owners.forEach { (v, lbl, icon) ->
                                                            val isSel = owner == v
                                                            Surface(
                                                                onClick = { owner = v },
                                                                shape = RoundedCornerShape(12.dp),
                                                                color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                                                border = BorderStroke(
                                                                    1.5.dp,
                                                                    if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                                                ),
                                                                modifier = Modifier.weight(1f)
                                                            ) {
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.Center,
                                                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
                                                                ) {
                                                                    Icon(
                                                                        imageVector = icon,
                                                                        contentDescription = null,
                                                                        tint = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                                        modifier = Modifier.size(16.dp)
                                                                    )
                                                                    Spacer(modifier = Modifier.width(4.dp))
                                                                    Text(
                                                                        text = lbl, 
                                                                        fontSize = 11.sp, 
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }

                                                val dateText = remember(asOfDate) {
                                                    val d = Date(asOfDate)
                                                    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(d)
                                                }
                                                OutlinedTextField(
                                                    value = dateText,
                                                    onValueChange = {},
                                                    label = { Text("Balance Effective Date") },
                                                    readOnly = true,
                                                    shape = RoundedCornerShape(12.dp),
                                                    leadingIcon = { Icon(Icons.Default.Event, contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
                                                    trailingIcon = {
                                                        IconButton(
                                                            onClick = {
                                                                DatePickerDialog(
                                                                    context,
                                                                    { _, year, month, day ->
                                                                        calendar.set(year, month, day)
                                                                        asOfDate = calendar.timeInMillis
                                                                    },
                                                                    calendar.get(Calendar.YEAR),
                                                                    calendar.get(Calendar.MONTH),
                                                                    calendar.get(Calendar.DAY_OF_MONTH)
                                                                ).show()
                                                            }
                                                        ) {
                                                            Icon(Icons.Default.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                        }
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                )

                                                AnimatedVisibility(visible = mainCategoryInput == "Loans") {
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                                            .padding(12.dp),
                                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Text(
                                                            "Loan Configuration (Auto-Interest Splits)", 
                                                            fontSize = 11.sp, 
                                                            fontWeight = FontWeight.Bold, 
                                                            color = MaterialTheme.colorScheme.error
                                                        )
                                                        
                                                        OutlinedTextField(
                                                            value = annualRateStr,
                                                            onValueChange = { annualRateStr = it },
                                                            label = { Text("Annual Interest Rate (%)") },
                                                            placeholder = { Text("e.g. 11.5") },
                                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                            shape = RoundedCornerShape(10.dp),
                                                            modifier = Modifier.fillMaxWidth(),
                                                            singleLine = true
                                                        )
                                                        OutlinedTextField(
                                                            value = emiStr,
                                                            onValueChange = { emiStr = it },
                                                            label = { Text("Monthly EMI Amount") },
                                                            placeholder = { Text("e.g. 5000") },
                                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                            shape = RoundedCornerShape(10.dp),
                                                            modifier = Modifier.fillMaxWidth(),
                                                            singleLine = true
                                                        )
                                                        OutlinedTextField(
                                                            value = remainingMonthsStr,
                                                            onValueChange = { remainingMonthsStr = it },
                                                            label = { Text("Remaining Tenure (Months)") },
                                                            placeholder = { Text("e.g. 24") },
                                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                            shape = RoundedCornerShape(10.dp),
                                                            modifier = Modifier.fillMaxWidth(),
                                                            singleLine = true
                                                        )
                                                    }
                                                }

                                                AnimatedVisibility(visible = mainCategoryInput == "Credit Cards") {
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                                            .padding(12.dp),
                                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Text(
                                                            "Credit Card Bill Configuration", 
                                                            fontSize = 11.sp, 
                                                            fontWeight = FontWeight.Bold, 
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        
                                                        OutlinedTextField(
                                                             value = billingCycleDayStr,
                                                             onValueChange = { input ->
                                                                 if (input.isEmpty() || (input.all { it.isDigit() } && (input.toIntOrNull() ?: 0) <= 31)) {
                                                                     billingCycleDayStr = input
                                                                 }
                                                             },
                                                             label = { Text("Billing Cycle Date (Day of Month)") },
                                                             placeholder = { Text("e.g. 15") },
                                                             keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                             shape = RoundedCornerShape(10.dp),
                                                             modifier = Modifier.fillMaxWidth().testTag("billing_cycle_day_input"),
                                                             singleLine = true
                                                        )

                                                        OutlinedTextField(
                                                             value = paymentDueDayStr,
                                                             onValueChange = { input ->
                                                                 if (input.isEmpty() || (input.all { it.isDigit() } && (input.toIntOrNull() ?: 0) <= 31)) {
                                                                     paymentDueDayStr = input
                                                                 }
                                                             },
                                                             label = { Text("Payment Due Date (Day of Month)") },
                                                             placeholder = { Text("e.g. 5") },
                                                             keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                             shape = RoundedCornerShape(10.dp),
                                                             modifier = Modifier.fillMaxWidth().testTag("payment_due_day_input"),
                                                             singleLine = true
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (category.isSystemDefault) {
                            item {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "This is a system default item. You can customize its icon but cannot rename or delete it.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val isIconChanged = iconSelected != category.iconName
                val isNameChanged = renameName.trim() != category.name && !category.isSystemDefault
                val isGroupChanged = mainCategoryInput.trim() != category.mainCategory && !category.isSystemDefault
                
                // Advanced values comparison
                val finalOpenBalance = if (enableRealTimeTracking) (initialBalanceStr.toDoubleOrNull() ?: 0.0) else 0.0
                val finalLast4 = if (enableRealTimeTracking) lastFourDigits.trim() else ""
                val finalOwner = if (enableRealTimeTracking) owner else ""
                val finalAsOfDate = if (enableRealTimeTracking) asOfDate else category.asOfDate
                val finalRate = if (enableRealTimeTracking && mainCategoryInput == "Loans") (annualRateStr.toDoubleOrNull() ?: 0.0) else 0.0
                val finalEmi = if (enableRealTimeTracking && mainCategoryInput == "Loans") (emiStr.toDoubleOrNull() ?: 0.0) else 0.0
                val finalTenure = if (enableRealTimeTracking && mainCategoryInput == "Loans") (remainingMonthsStr.toIntOrNull() ?: 0) else 0
                val finalBillingCycleDay = if (enableRealTimeTracking && mainCategoryInput == "Credit Cards") (billingCycleDayStr.toIntOrNull() ?: 0) else 0
                val finalPaymentDueDay = if (enableRealTimeTracking && mainCategoryInput == "Credit Cards") (paymentDueDayStr.toIntOrNull() ?: 0) else 0

                val isAdvancedChanged = isAssetOrLiability && (
                    finalOpenBalance != category.openingBalance ||
                    finalLast4 != category.lastFourDigits ||
                    finalOwner != category.ownerUserId ||
                    finalAsOfDate != category.asOfDate ||
                    finalRate != category.annualInterestRate ||
                    finalEmi != category.monthlyEmi ||
                    finalTenure != category.remainingMonths ||
                    finalBillingCycleDay != category.billingCycleDay ||
                    finalPaymentDueDay != category.paymentDueDay
                )

                val isSomethingChanged = isIconChanged || isNameChanged || isGroupChanged || isAdvancedChanged
                
                Button(
                    onClick = {
                        val finalName = renameName.trim()
                        if (finalName.isNotEmpty() || category.isSystemDefault) {
                            viewModel.updateCategory(
                                category.copy(
                                    accountName = if (category.isSystemDefault) category.accountName else finalName,
                                    customMainCategory = if (category.isSystemDefault) category.mainCategory else mainCategoryInput.trim(),
                                    customSubCategory = if (category.isSystemDefault) category.name else finalName,
                                    iconName = iconSelected,
                                    openingBalance = finalOpenBalance,
                                    lastFourDigits = finalLast4,
                                    ownerUserId = finalOwner,
                                    asOfDate = finalAsOfDate,
                                    annualInterestRate = finalRate,
                                    monthlyEmi = finalEmi,
                                    remainingMonths = finalTenure,
                                    billingCycleDay = finalBillingCycleDay,
                                    paymentDueDay = finalPaymentDueDay
                                )
                            )
                            editingCategory = null
                        }
                    },
                    enabled = (category.isSystemDefault && isSomethingChanged) || 
                              (!category.isSystemDefault && renameName.trim().isNotEmpty() && isSomethingChanged),
                    modifier = Modifier.testTag("rename_category_confirm_button")
                ) {
                    Text("Save Changes", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!category.isSystemDefault) {
                        Button(
                            onClick = { confirmDelete = category },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.testTag("delete_category_button")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                    TextButton(
                        onClick = { editingCategory = null },
                        modifier = Modifier.testTag("close_category_dialog_button")
                    ) {
                        Text("Close")
                    }
                }
            }
        )
    }
}

@Composable
private fun GroupHeader(title: String, trailing: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp, start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/**
 * One account: name, owner and last 4 digits, balance, and when it was last checked against
 * the bank. Overdue reconciliations are flagged (30 days for banks and cards, 60 for the rest).
 */
@Composable
private fun AccountRow(
    account: Account,
    balance: Double,
    owner: String,
    now: Long,
    onClick: () -> Unit,
    onReconcile: () -> Unit
) {
    val isLiability = account.accountType == "LIABILITY"
    val days = ReconcileRules.daysSince(account, now)
    val overdue = days >= ReconcileRules.staleAfterDays(account)
    val reconciledText = when {
        account.lastReconciledDate == null -> "Never reconciled"
        days == 0 -> "Reconciled today"
        else -> "Reconciled $days day${if (days == 1) "" else "s"} ago"
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).testTag("hierarchical_item_${account.id}")
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                getCategoryIcon(account.name, account.iconName),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(account.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(owner, account.lastFourDigits.takeIf { it.isNotBlank() }?.let { "••$it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    reconciledText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.clickable(onClick = onReconcile)
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatAmount(kotlin.math.abs(balance)),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isLiability && kotlin.math.abs(balance) > 0.5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                if (isLiability) Text("owed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onReconcile, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp), modifier = Modifier.height(28.dp)) {
                    Text(if (overdue) "Reconcile now" else "Reconcile", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
