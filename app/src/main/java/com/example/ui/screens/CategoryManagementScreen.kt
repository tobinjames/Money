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
import com.example.ui.viewmodel.ExpenseViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagementScreen(
    viewModel: ExpenseViewModel,
    onBackClick: () -> Unit
) {
    val categories by viewModel.categories.collectAsState()
    
    var selectedTab by remember { mutableStateOf(0) } // 0 = ALL, 1 = ASSET, 2 = LIABILITY, 3 = EQUITY, 4 = INCOME, 5 = EXPENSE
    val masterGroups = listOf("ALL", "ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE")
    var searchQuery by remember { mutableStateOf("") }
    
    // Collapsed groups state
    var collapsedGroups by remember { mutableStateOf(setOf<String>()) }
    
    val filteredCategories = remember(categories, selectedTab, searchQuery) {
        val targetClass = masterGroups[selectedTab]
        val byClass = if (targetClass == "ALL") {
            categories
        } else {
            categories.filter { it.accountClass.uppercase() == targetClass }
        }
        
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            byClass
        } else {
            byClass.filter {
                it.name.lowercase().contains(q) ||
                it.mainCategory.lowercase().contains(q) ||
                it.accountClass.lowercase().contains(q) ||
                it.lastFourDigits.contains(q) ||
                it.ownerFirstName.lowercase().contains(q)
            }
        }
    }
    
    val groupedByClass = remember(filteredCategories) {
        filteredCategories.groupBy { it.accountClass.uppercase().ifBlank { "OTHER" } }
    }
    
    var showAddDialog by remember { mutableStateOf(false) }
    var initialAddClass by remember { mutableStateOf("ASSET") }
    var editingCategory by remember { mutableStateOf<Account?>(null) }
    var reconcilingAccount by remember { mutableStateOf<Account?>(null) }
    
    Scaffold(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Chart of Accounts", fontWeight = FontWeight.Bold)
                        Text(
                            text = "${categories.size} Active Ledgers & Categories across 5 Heads", 
                            style = MaterialTheme.typography.bodySmall, 
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { 
                    initialAddClass = if (selectedTab in 1..5) masterGroups[selectedTab] else "ASSET"
                    showAddDialog = true 
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag("add_category_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Ledger / Node", fontWeight = FontWeight.Bold)
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 5 Major Heads Tab Bar
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                edgePadding = 12.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                val tabs = listOf(
                    Triple("ALL", Icons.Default.AccountTree, categories.size),
                    Triple("ASSETS", Icons.Default.AccountBalance, categories.count { it.accountClass.uppercase() == "ASSET" }),
                    Triple("LIABILITIES", Icons.Default.CreditCard, categories.count { it.accountClass.uppercase() == "LIABILITY" }),
                    Triple("EQUITY", Icons.Default.PieChart, categories.count { it.accountClass.uppercase() == "EQUITY" }),
                    Triple("INCOME", Icons.Default.ArrowUpward, categories.count { it.accountClass.uppercase() == "INCOME" }),
                    Triple("EXPENSES", Icons.Default.ArrowDownward, categories.count { it.accountClass.uppercase() == "EXPENSE" })
                )
                
                tabs.forEachIndexed { idx, (label, icon, count) ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = { 
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(label, fontWeight = if (selectedTab == idx) FontWeight.Bold else FontWeight.Medium, fontSize = 11.sp)
                                Surface(
                                    shape = CircleShape,
                                    color = if (selectedTab == idx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                    modifier = Modifier.padding(start = 2.dp)
                                ) {
                                    Text(
                                        text = "$count",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selectedTab == idx) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        },
                        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            // Search and Controls Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search account name, group, last 4 digits...", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    )
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Showing ${filteredCategories.size} accounts / nodes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Expand All",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { collapsedGroups = emptySet() }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Collapse All",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    val allGroupKeys = mutableSetOf<String>()
                                    groupedByClass.forEach { (cls, list) ->
                                        list.groupBy { it.mainCategory }.keys.forEach { main ->
                                            allGroupKeys.add("${cls}_$main")
                                        }
                                    }
                                    collapsedGroups = allGroupKeys
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            
            // Content
            if (filteredCategories.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.AccountTree,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No accounts match '$searchQuery'" else "No accounts or categories in ${masterGroups[selectedTab]}.",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap + to configure a tracking ledger or category inside this group.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                initialAddClass = if (selectedTab in 1..5) masterGroups[selectedTab] else "ASSET"
                                showAddDialog = true
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create Node")
                        }
                    }
                }
            } else {
                // Scalable Hierarchical Accordion View
                val primaryColor = MaterialTheme.colorScheme.primary
                val errorColor = MaterialTheme.colorScheme.error
                val secondaryColor = MaterialTheme.colorScheme.secondary

                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 80.dp, top = 4.dp)
                ) {
                    groupedByClass.forEach { (accountClass, classAccounts) ->
                        val classHeadColor = when (accountClass) {
                            "ASSET" -> primaryColor
                            "LIABILITY" -> errorColor
                            "EQUITY" -> Color(0xFF8B5CF6)
                            "INCOME" -> Color(0xFF10B981)
                            "EXPENSE" -> Color(0xFFF59E0B)
                            else -> secondaryColor
                        }

                        val classHeadIcon = when (accountClass) {
                            "ASSET" -> Icons.Default.AccountBalance
                            "LIABILITY" -> Icons.Default.CreditCard
                            "EQUITY" -> Icons.Default.PieChart
                            "INCOME" -> Icons.Default.ArrowUpward
                            "EXPENSE" -> Icons.Default.ArrowDownward
                            else -> Icons.Default.AccountTree
                        }

                        // Head Title Banner (shown when in ALL mode)
                        if (selectedTab == 0) {
                            item(key = "head_header_$accountClass") {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = classHeadColor.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, classHeadColor.copy(alpha = 0.25f)),
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = classHeadIcon,
                                                contentDescription = null,
                                                tint = classHeadColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = "MAJOR HEAD: $accountClass",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = classHeadColor
                                            )
                                        }

                                        Text(
                                            text = "${classAccounts.size} Nodes",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = classHeadColor
                                        )
                                    }
                                }
                            }
                        }

                        // Group by Main Category within Head
                        val byMainCategory = classAccounts.groupBy { it.mainCategory.ifBlank { "General" } }

                        byMainCategory.forEach { (mainCategory, subList) ->
                            val groupKey = "${accountClass}_$mainCategory"
                            val isCollapsed = collapsedGroups.contains(groupKey)

                            item(key = "group_card_$groupKey") {
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
                                    modifier = Modifier.fillMaxWidth().testTag("accordion_group_$groupKey")
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        // Accordion Header
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    collapsedGroups = if (isCollapsed) {
                                                        collapsedGroups - groupKey
                                                    } else {
                                                        collapsedGroups + groupKey
                                                    }
                                                }
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = classHeadColor.copy(alpha = 0.15f),
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = getCategoryIcon(mainCategory),
                                                            contentDescription = null,
                                                            tint = classHeadColor,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }

                                                Column {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Text(
                                                            text = mainCategory,
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        Surface(
                                                            shape = CircleShape,
                                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                                        ) {
                                                            Text(
                                                                text = "${subList.size}",
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                if (accountClass == "ASSET" || accountClass == "LIABILITY") {
                                                    val groupTotal = subList.sumOf { it.balance }
                                                    Text(
                                                        text = formatAmount(if (accountClass == "LIABILITY") Math.abs(groupTotal) else groupTotal),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (accountClass == "LIABILITY") MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                                                    )
                                                }

                                                IconButton(
                                                    onClick = {
                                                        collapsedGroups = if (isCollapsed) collapsedGroups - groupKey else collapsedGroups + groupKey
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                                        contentDescription = if (isCollapsed) "Expand" else "Collapse",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }

                                        // Accordion Children
                                        AnimatedVisibility(
                                            visible = !isCollapsed,
                                            enter = expandVertically() + fadeIn(),
                                            exit = shrinkVertically() + fadeOut()
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Divider(
                                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                                                    modifier = Modifier.padding(bottom = 4.dp)
                                                )

                                                subList.forEach { account ->
                                                    HierarchicalAccountRowItem(
                                                        account = account,
                                                        accountClass = accountClass,
                                                        onClick = { editingCategory = account },
                                                        onReconcileClick = { reconcilingAccount = account }
                                                    )
                                                }
                                            }
                                        }
                                    }
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
            initialSelectedClass = initialAddClass
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
                            onClick = {
                                viewModel.deleteCategory(category.id)
                                editingCategory = null
                            },
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
fun HierarchicalAccountRowItem(
    account: Account,
    accountClass: String,
    onClick: () -> Unit,
    onReconcileClick: () -> Unit
) {
    val isAssetOrLiability = accountClass == "ASSET" || accountClass == "LIABILITY"
    val isRealTimeTracking = account.lastFourDigits.isNotBlank() || account.openingBalance != 0.0
    val icon = getCategoryIcon(account.name, account.iconName)
    val balanceColor = if (account.balance < 0 || accountClass == "LIABILITY") MaterialTheme.colorScheme.error else Color(0xFF2E7D32)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hierarchical_item_${account.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = account.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (account.lastFourDigits.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    text = "••${account.lastFourDigits}",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }

                        if (account.isSystemDefault) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                                contentColor = MaterialTheme.colorScheme.secondary
                            ) {
                                Text("System", fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp))
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        if (account.ownerFirstName.isNotBlank()) {
                            Text(
                                text = "Owner: ${account.ownerFirstName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                fontWeight = FontWeight.Medium
                            )
                        } else {
                            Text(
                                text = account.mainCategory,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (account.annualInterestRate > 0) {
                            Text(
                                text = "• ${account.annualInterestRate}% p.a.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Right side: Balance + Action buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isAssetOrLiability) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = formatAmount(if (accountClass == "LIABILITY") Math.abs(account.balance) else account.balance),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = balanceColor
                        )

                        account.lastReconciledDate?.let { lastRec ->
                            Text(
                                text = "Reconciled: ${SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(lastRec))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF2E7D32),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (isAssetOrLiability && isRealTimeTracking) {
                    IconButton(
                        onClick = onReconcileClick,
                        modifier = Modifier.size(28.dp).testTag("reconcile_button_${account.id}")
                    ) {
                        Icon(
                            imageVector = if (account.lastReconciledDate != null) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "Reconcile Account",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Edit Account",
                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
