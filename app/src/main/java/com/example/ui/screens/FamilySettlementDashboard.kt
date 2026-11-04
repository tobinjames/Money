package com.example.ui.screens

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.*
import com.example.ui.viewmodel.ExpenseViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilySettlementDashboard(
    viewModel: ExpenseViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val accounts by viewModel.accounts.collectAsState()
    val transactions by viewModel.transactions.collectAsState()
    
    // Extracted dynamic profile names
    val user1Name by viewModel.currentUserFirstName.collectAsState()
    val user2Name by viewModel.spouseUserFirstName.collectAsState()
    val partnerAName = user1Name ?: "Tobin"
    val partnerBName = user2Name ?: "Soumya"

    val calendar = remember { Calendar.getInstance() }
    
    // 1. Date Range picker - default to current month
    var startMillis by remember {
        mutableStateOf(
            Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        )
    }
    var endMillis by remember {
        mutableStateOf(
            Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }.timeInMillis
        )
    }

    // 2. Customizable split percentage factor
    var partnerASplitPercentage by remember { mutableStateOf(50f) } // Default 50% / 50%
    val partnerBSplitPercentage = 100f - partnerASplitPercentage

    // Active sub-ledger calculations tab
    var selectedTab by remember { mutableStateOf(0) } // 0 = Direct Spends, 1 = Joint Contributions

    // 3. Filtered ledger subsets
    val familyScopeTransactions = remember(transactions, startMillis, endMillis) {
        transactions.filter {
            it.status == TransactionStatus.CONFIRMED &&
            it.scope == TransactionScope.FAMILY &&
            it.timestamp in startMillis..endMillis
        }
    }

    // Account filtering using dynamic matches for User 1 and User 2
    val partnerAAccounts = remember(accounts) {
        val curId = viewModel.currentUserUid.value
        accounts.filter { it.ownerUserId == "User 1" || (curId != null && it.ownerUserId == curId) }
    }
    val partnerBAccounts = remember(accounts) {
        val spId = viewModel.spouseUserUid.value
        accounts.filter { it.ownerUserId == "User 2" || (spId != null && it.ownerUserId == spId) }
    }
    val jointAccounts = remember(accounts) {
        accounts.filter { it.ownerUserId == "SHARED" || it.ownerUserId == "Shared" }
    }

    val partnerAAccountIds = remember(partnerAAccounts) { partnerAAccounts.map { it.id }.toSet() }
    val partnerBAccountIds = remember(partnerBAccounts) { partnerBAccounts.map { it.id }.toSet() }
    val jointAccountIds = remember(jointAccounts) { jointAccounts.map { it.id }.toSet() }

    // --- MATH SECTION 1: DIRECT PERSONAL SPENDS SPLIT ---
    // Physical payment matching functions based on whose account/card was used
    val curId = viewModel.currentUserUid.value
    val spId = viewModel.spouseUserUid.value

    fun isPartnerAPayer(tx: Transaction): Boolean {
        return partnerAAccountIds.contains(tx.accountId) || tx.ownerUserId == "User 1" || (curId != null && tx.ownerUserId == curId)
    }

    fun isPartnerBPayer(tx: Transaction): Boolean {
        return partnerBAccountIds.contains(tx.accountId) || tx.ownerUserId == "User 2" || (spId != null && tx.ownerUserId == spId)
    }

    // Helper to determine who is responsible for the expense
    // Returns: "A" (Partner A), "B" (Partner B), or "BOTH" (Split)
    fun getResponsibleParty(tx: Transaction): String {
        return when {
            tx.paidByUserId == "User 1" || (curId != null && tx.paidByUserId == curId) -> "A"
            tx.paidByUserId == "User 2" || (spId != null && tx.paidByUserId == spId) -> "B"
            else -> "BOTH"
        }
    }

    // Personal family transactions are those in family scope, not transfers, and paid from either Partner A or B's personal accounts
    val personalFamilyTransactions = remember(familyScopeTransactions, partnerAAccountIds, partnerBAccountIds) {
        familyScopeTransactions.filter { tx ->
            tx.type != TransactionType.TRANSFER && (isPartnerAPayer(tx) || isPartnerBPayer(tx))
        }
    }

    // Spends calculations of Partners (how much they physically paid out-of-pocket)
    val partnerASpents = remember(personalFamilyTransactions) {
        personalFamilyTransactions
            .filter { isPartnerAPayer(it) }
            .sumOf { it.amount }
    }
    val partnerBSpents = remember(personalFamilyTransactions) {
        personalFamilyTransactions
            .filter { isPartnerBPayer(it) }
            .sumOf { it.amount }
    }
    val totalSpends = partnerASpents + partnerBSpents

    // Net transfers between Partner A's personal accounts and Partner B's personal accounts
    val transfersBetweenCouple = remember(transactions, startMillis, endMillis) {
        transactions.filter {
            it.status == TransactionStatus.CONFIRMED &&
            it.type == TransactionType.TRANSFER &&
            it.timestamp in startMillis..endMillis
        }
    }

    val transferAtoB = remember(transfersBetweenCouple, partnerAAccountIds, partnerBAccountIds) {
        transfersBetweenCouple.filter { tx ->
            partnerAAccountIds.contains(tx.accountId) && partnerBAccountIds.contains(tx.targetAccountId)
        }.sumOf { it.amount }
    }

    val transferBtoA = remember(transfersBetweenCouple, partnerAAccountIds, partnerBAccountIds) {
        transfersBetweenCouple.filter { tx ->
            partnerBAccountIds.contains(tx.accountId) && partnerAAccountIds.contains(tx.targetAccountId)
        }.sumOf { it.amount }
    }

    val netTransfersAtoB = transferAtoB - transferBtoA

    // Dynamic Math Formulation:
    // Partner A spend balance = (Partner A family payments) - (Total Family Spends * Partner A share factor) + Net transfers (A to B)
    val shareFactorA = partnerASplitPercentage / 100.0
    val shareFactorB = partnerBSplitPercentage / 100.0

    // Expected responsibility calculations based on "Paid For / Responsibility" selection
    val expectedA = remember(personalFamilyTransactions, shareFactorA) {
        personalFamilyTransactions.sumOf { tx ->
            when (getResponsibleParty(tx)) {
                "A" -> tx.amount
                "B" -> 0.0
                else -> tx.amount * shareFactorA
            }
        }
    }

    val expectedB = remember(personalFamilyTransactions, shareFactorB) {
        personalFamilyTransactions.sumOf { tx ->
            when (getResponsibleParty(tx)) {
                "A" -> 0.0
                "B" -> tx.amount
                else -> tx.amount * shareFactorB
            }
        }
    }

    // Partner spend balance credits
    val spendBalanceA = partnerASpents - expectedA + netTransfersAtoB
    val spendBalanceB = partnerBSpents - expectedB - netTransfersAtoB


    // --- MATH SECTION 2: CONTRIBUTIONS TO JOINT ACCOUNTS ---
    // Transfers from Partner A's personal accounts to Joint Accounts during calculations period
    val transferListAtoJoint = remember(transfersBetweenCouple, partnerAAccountIds, jointAccountIds) {
        transfersBetweenCouple.filter { tx ->
            partnerAAccountIds.contains(tx.accountId) && jointAccountIds.contains(tx.targetAccountId)
        }
    }
    val jointContributionsA = remember(transferListAtoJoint) {
        transferListAtoJoint.sumOf { it.amount }
    }

    // Transfers from Partner B's personal accounts to Joint Accounts during calculations period
    val transferListBtoJoint = remember(transfersBetweenCouple, partnerBAccountIds, jointAccountIds) {
        transfersBetweenCouple.filter { tx ->
            partnerBAccountIds.contains(tx.accountId) && jointAccountIds.contains(tx.targetAccountId)
        }
    }
    val jointContributionsB = remember(transferListBtoJoint) {
        transferListBtoJoint.sumOf { it.amount }
    }

    val totalJointContributions = jointContributionsA + jointContributionsB

    // Expected Joint Contributions based on the custom split percentage
    val expectedJointContribA = totalJointContributions * shareFactorA
    val expectedJointContribB = totalJointContributions * shareFactorB

    // Contribution overpay (+) / underpay (-) balances
    val jointContribBalanceA = jointContributionsA - expectedJointContribA
    val jointContribBalanceB = jointContributionsB - expectedJointContribB

    // Expenses paid directly *from* "SHARED" accounts inside this time period
    val jointAccountDirectExpensesList = remember(familyScopeTransactions, jointAccountIds) {
        familyScopeTransactions.filter { tx ->
            tx.type == TransactionType.EXPENSE && jointAccountIds.contains(tx.accountId)
        }
    }
    val totalJointAccountDirectExpenses = remember(jointAccountDirectExpensesList) {
        jointAccountDirectExpensesList.sumOf { it.amount }
    }

    // Sorted list of joint contributions for the lazy list, safe from composable remember inside lazy context
    val allJointContributions = remember(transferListAtoJoint, transferListBtoJoint) {
        (transferListAtoJoint + transferListBtoJoint).sortedByDescending { it.timestamp }
    }


    // Reconciling Account Selections for settling up in-place
    var selectedAccountIdA by remember { mutableStateOf("") }
    var selectedAccountIdB by remember { mutableStateOf("") }

    LaunchedEffect(partnerAAccounts, partnerBAccounts) {
        if (selectedAccountIdA.isEmpty() && partnerAAccounts.isNotEmpty()) {
            selectedAccountIdA = partnerAAccounts.first().id
        }
        if (selectedAccountIdB.isEmpty() && partnerBAccounts.isNotEmpty()) {
            selectedAccountIdB = partnerBAccounts.first().id
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Family Settlement Dashboard", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        },
        modifier = modifier
            .fillMaxSize()
            .testTag("family_settlement_screen")
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Dynamic Settlements & Contributions",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Audit direct spends on individual cards or contributions made to fund common joint accounts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // --- DATE RANGE SELECTION PICKER CARD ---
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Calculation Period",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val pickStartText = remember(startMillis) {
                                SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date(startMillis))
                            }
                            val pickEndText = remember(endMillis) {
                                SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date(endMillis))
                            }

                            // Start Date
                            OutlinedButton(
                                onClick = {
                                    DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            val c = Calendar.getInstance()
                                            c.set(y, m, d)
                                            c.set(Calendar.HOUR_OF_DAY, 0)
                                            c.set(Calendar.MINUTE, 0)
                                            c.set(Calendar.SECOND, 0)
                                            c.set(Calendar.MILLISECOND, 0)
                                            startMillis = c.timeInMillis
                                        },
                                        calendar.get(Calendar.YEAR),
                                        calendar.get(Calendar.MONTH),
                                        calendar.get(Calendar.DAY_OF_MONTH)
                                    ).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(pickStartText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }

                            // End Date
                            OutlinedButton(
                                onClick = {
                                    DatePickerDialog(
                                        context,
                                        { _, y, m, d ->
                                            val c = Calendar.getInstance()
                                            c.set(y, m, d)
                                            c.set(Calendar.HOUR_OF_DAY, 23)
                                            c.set(Calendar.MINUTE, 59)
                                            c.set(Calendar.SECOND, 59)
                                            c.set(Calendar.MILLISECOND, 999)
                                            endMillis = c.timeInMillis
                                        },
                                        calendar.get(Calendar.YEAR),
                                        calendar.get(Calendar.MONTH),
                                        calendar.get(Calendar.DAY_OF_MONTH)
                                    ).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(pickEndText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            // --- SPLITS & CONTRIBUTION CONFIGURATION ---
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Contribution Ratios Agreement",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Define the proportional target share of combined family inputs expected from each partner.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$partnerAName (Tobin): ${partnerASplitPercentage.toInt()}%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "$partnerBName (Soumya): ${partnerBSplitPercentage.toInt()}%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        Slider(
                            value = partnerASplitPercentage,
                            onValueChange = { partnerASplitPercentage = it.coerceIn(0f, 100f) },
                            valueRange = 0f..100f,
                            steps = 9,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // --- MATERIAL 3 TABS FOR EXCELLENT DISCOVERY/NAVIGATION ---
            item {
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Direct Spends", fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Joint Accounts", fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Default.AccountBox, contentDescription = null) }
                    )
                }
            }

            // ================== TAB 0 CONTENT: DIRECT SPENDS SPLIT ==================
            if (selectedTab == 0) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Direct Payment Ledgers",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            
                            Divider()

                            // A Spent
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("$partnerAName Spends Paid:", style = MaterialTheme.typography.bodyMedium)
                                Text(formatAmount(partnerASpents), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }

                            // B Spent
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("$partnerBName Spends Paid:", style = MaterialTheme.typography.bodyMedium)
                                Text(formatAmount(partnerBSpents), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }

                            // Total spends
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Total Direct Joint Spends:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(formatAmount(totalSpends), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }

                            Divider(modifier = Modifier.padding(vertical = 4.dp))

                            // Transfers
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("$partnerAName to $partnerBName Transfers:", style = MaterialTheme.typography.bodyMedium)
                                Text(formatAmount(transferAtoB), style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("$partnerBName to $partnerAName Transfers:", style = MaterialTheme.typography.bodyMedium)
                                Text(formatAmount(transferBtoA), style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Net Direct Transfers Offset:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                val symSign = if (netTransfersAtoB >= 0) "➔ To " + partnerBName else "➔ To " + partnerAName
                                Text("$symSign : ${formatAmount(Math.abs(netTransfersAtoB))}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            }

                            Divider(modifier = Modifier.padding(vertical = 4.dp))

                            // Shares values expected
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Expected direct share ($partnerAName):", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatAmount(expectedA), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Expected direct share ($partnerBName):", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatAmount(expectedB), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // --- RESULTING OWE CARD (WHO OWES WHO) ---
                item {
                    val finalOwedAmount = Math.abs(spendBalanceA)
                    val owingUserLabel = if (spendBalanceA < 0) partnerAName else partnerBName
                    val owedUserLabel = if (spendBalanceA < 0) partnerBName else partnerAName
                    val isSettled = finalOwedAmount <= 0.01

                    val cardBg = if (isSettled) Color(0xFFE8F5E9) else if (spendBalanceA < 0) Color(0xFFFFF3E0) else Color(0xFFF1F3F9)
                    val borderStrokeColor = if (isSettled) Color(0xFFA5D6A7) else if (spendBalanceA < 0) Color(0xFFFFCC80) else Color(0xFFD6E4FF)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = BorderStroke(1.5.dp, borderStrokeColor)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "DIRECT SPENDS SETTLEMENT VERDICT",
                                style = MaterialTheme.typography.titleSmall,
                                letterSpacing = 1.2.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            if (isSettled) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(56.dp))
                                Text(
                                    text = "Perfectly Settled!",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E7D32)
                                )
                                Text(
                                    text = "All direct payments match your custom split agreements exactly.",
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    color = Color(0xFF2E7D32)
                                )
                            } else {
                                Text(
                                    text = "$owingUserLabel owes $owedUserLabel",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                
                                Text(
                                    text = formatAmount(finalOwedAmount),
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Text(
                                    text = "Formula: Payment Spend - Target Percentage Share + Direct transfers",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )

                                Divider(modifier = Modifier.padding(vertical = 4.dp))
                                
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Setup Settlement Direct Transfer",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )

                                    // Owing Source Bank Picker
                                    Column {
                                        val activeOwingAccounts = if (spendBalanceA < 0) partnerAAccounts else partnerBAccounts
                                        val selectedOwingId = if (spendBalanceA < 0) selectedAccountIdA else selectedAccountIdB
                                        val owingAccObj = activeOwingAccounts.find { it.id == selectedOwingId }
                                        
                                        Text(
                                            text = "Source Account ($owingUserLabel):",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        
                                        var owingExpanded by remember { mutableStateOf(false) }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(44.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surface)
                                                .clickable { owingExpanded = true }
                                                .padding(horizontal = 12.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text(owingAccObj?.name ?: "Select funding account...", fontSize = 12.sp)
                                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                            DropdownMenu(expanded = owingExpanded, onDismissRequest = { owingExpanded = false }) {
                                                activeOwingAccounts.forEach { acc ->
                                                    DropdownMenuItem(
                                                        text = { Text("${acc.name} (${formatAmount(acc.balance)})", fontSize = 12.sp) },
                                                        onClick = {
                                                            if (spendBalanceA < 0) selectedAccountIdA = acc.id else selectedAccountIdB = acc.id
                                                            owingExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Owed Target Bank Picker
                                    Column {
                                        val activeOwedAccounts = if (spendBalanceA < 0) partnerBAccounts else partnerAAccounts
                                        val selectedOwedId = if (spendBalanceA < 0) selectedAccountIdB else selectedAccountIdA
                                        val owedAccObj = activeOwedAccounts.find { it.id == selectedOwedId }
                                        
                                        Text(
                                            text = "Destination Account ($owedUserLabel):",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        
                                        var owedExpanded by remember { mutableStateOf(false) }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(44.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surface)
                                                .clickable { owedExpanded = true }
                                                .padding(horizontal = 12.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text(owedAccObj?.name ?: "Select recipient account...", fontSize = 12.sp)
                                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                            DropdownMenu(expanded = owedExpanded, onDismissRequest = { owedExpanded = false }) {
                                                activeOwedAccounts.forEach { acc ->
                                                    DropdownMenuItem(
                                                        text = { Text("${acc.name} (${formatAmount(acc.balance)})", fontSize = 12.sp) },
                                                        onClick = {
                                                            if (spendBalanceA < 0) selectedAccountIdB = acc.id else selectedAccountIdA = acc.id
                                                            owedExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                val finalSrcAccId = if (spendBalanceA < 0) selectedAccountIdA else selectedAccountIdB
                                val finalDstAccId = if (spendBalanceA < 0) selectedAccountIdB else selectedAccountIdA

                                Button(
                                    onClick = {
                                        if (finalSrcAccId.isNotEmpty() && finalDstAccId.isNotEmpty()) {
                                            val srcAccObj = accounts.find { it.id == finalSrcAccId }
                                            val dstAccObj = accounts.find { it.id == finalDstAccId }
                                            val srcLastRec = srcAccObj?.takeIf { it.accountType == "ASSET" || it.accountType == "LIABILITY" }?.lastReconciledDate
                                            val dstLastRec = dstAccObj?.takeIf { it.accountType == "ASSET" || it.accountType == "LIABILITY" }?.lastReconciledDate
                                            val currentTms = System.currentTimeMillis()
                                            
                                            val isSrcLocked = srcLastRec?.let { currentTms < it } ?: false
                                            val isDstLocked = dstLastRec?.let { currentTms < it } ?: false
                                            
                                            if (isSrcLocked || isDstLocked) {
                                                Toast.makeText(context, "Locked Period: Accounts belong to already closed/reconciled financial periods.", Toast.LENGTH_LONG).show()
                                            } else {
                                                val owingUserUid = if (spendBalanceA < 0) "User 1" else "User 2"
                                                viewModel.addManualTransaction(
                                                    amount = finalOwedAmount,
                                                    type = TransactionType.TRANSFER,
                                                    category = "Transfer",
                                                    narration = "Family Direct Settlement: $owingUserLabel to $owedUserLabel",
                                                    accountId = finalSrcAccId,
                                                    targetAccountId = finalDstAccId,
                                                    paidBy = owingUserUid,
                                                    scope = TransactionScope.FAMILY,
                                                    dateMillis = currentTms
                                                )
                                                Toast.makeText(context, "Settlement transfer recorded inside ledger successfully!", Toast.LENGTH_LONG).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "Please select both accounts first.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("settle_balance_button")
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Settle Direct Spends via Transfer", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // ================== TAB 1 CONTENT: JOINT CO contributions ==================
            if (selectedTab == 1) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Joint Accounts Funding Pool",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Summary of transfers made from personal funds to Shared/Joint bank balances or investment items (owner = 'SHARED')",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Divider()

                            // Partner A contributions
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("$partnerAName (Tobin) Contributions:", style = MaterialTheme.typography.bodyMedium)
                                Text(formatAmount(jointContributionsA), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }

                            // Partner B contributions
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("$partnerBName (Soumya) Contributions:", style = MaterialTheme.typography.bodyMedium)
                                Text(formatAmount(jointContributionsB), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }

                            // Total Pool Contribution
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Total Pooled Funding:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(formatAmount(totalJointContributions), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }

                            Divider(modifier = Modifier.padding(vertical = 4.dp))

                            // Expected shares of Contribution
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Your expected contribution share (${partnerASplitPercentage.toInt()}%):", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatAmount(expectedJointContribA), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${partnerBName}'s expected contribution share (${partnerBSplitPercentage.toInt()}%):", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatAmount(expectedJointContribB), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // --- RESULTING OWE CARD FOR JOINT CONTRIBUTIONS ---
                item {
                    val finalJointOwed = Math.abs(jointContribBalanceA)
                    val owingUserLabel = if (jointContribBalanceA < 0) partnerAName else partnerBName
                    val owedUserLabel = if (jointContribBalanceA < 0) partnerBName else partnerAName
                    val isSettled = finalJointOwed <= 0.01

                    val cardBg = if (isSettled) Color(0xFFE8F5E9) else if (jointContribBalanceA < 0) Color(0xFFFFF3E0) else Color(0xFFF1F3F9)
                    val borderStrokeColor = if (isSettled) Color(0xFFA5D6A7) else if (jointContribBalanceA < 0) Color(0xFFFFCC80) else Color(0xFFD6E4FF)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = BorderStroke(1.5.dp, borderStrokeColor)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "JOINT POOL SPLIT VERDICT",
                                style = MaterialTheme.typography.titleSmall,
                                letterSpacing = 1.2.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            if (isSettled) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(56.dp))
                                Text(
                                    text = "Ratios Aligned!",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E7D32)
                                )
                                Text(
                                    text = "Your pooling inputs match your customizable contribution agreement ratio of ${partnerASplitPercentage.toInt()}/${partnerBSplitPercentage.toInt()}.",
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    color = Color(0xFF2E7D32)
                                )
                            } else {
                                Text(
                                    text = "$owingUserLabel must contribute / pay $owedUserLabel",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                
                                Text(
                                    text = formatAmount(finalJointOwed),
                                    style = MaterialTheme.typography.displayMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Text(
                                    text = "Formula: Actual Contributed Pool - Target Contribution Share",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )

                                Divider(modifier = Modifier.padding(vertical = 4.dp))

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Setup Joint Settle Transfer",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )

                                    // Owing Source Bank Picker
                                    Column {
                                        val activeOwingAccounts = if (jointContribBalanceA < 0) partnerAAccounts else partnerBAccounts
                                        val selectedOwingId = if (jointContribBalanceA < 0) selectedAccountIdA else selectedAccountIdB
                                        val owingAccObj = activeOwingAccounts.find { it.id == selectedOwingId }
                                        
                                        Text(
                                            text = "Source Account ($owingUserLabel):",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        
                                        var owingExpanded by remember { mutableStateOf(false) }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(44.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surface)
                                                .clickable { owingExpanded = true }
                                                .padding(horizontal = 12.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text(owingAccObj?.name ?: "Select funding account...", fontSize = 12.sp)
                                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                            DropdownMenu(expanded = owingExpanded, onDismissRequest = { owingExpanded = false }) {
                                                activeOwingAccounts.forEach { acc ->
                                                    DropdownMenuItem(
                                                        text = { Text("${acc.name} (${formatAmount(acc.balance)})", fontSize = 12.sp) },
                                                        onClick = {
                                                            if (jointContribBalanceA < 0) selectedAccountIdA = acc.id else selectedAccountIdB = acc.id
                                                            owingExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Owed Target Bank Picker
                                    Column {
                                        val activeOwedAccounts = if (jointContribBalanceA < 0) partnerBAccounts else partnerAAccounts
                                        val selectedOwedId = if (jointContribBalanceA < 0) selectedAccountIdB else selectedAccountIdA
                                        val mapAccObj = activeOwedAccounts.find { it.id == selectedOwedId }
                                        
                                        Text(
                                            text = "Destination Account ($owedUserLabel):",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        
                                        var owedExpanded by remember { mutableStateOf(false) }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(44.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.surface)
                                                .clickable { owedExpanded = true }
                                                .padding(horizontal = 12.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text(mapAccObj?.name ?: "Select recipient account...", fontSize = 12.sp)
                                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                            DropdownMenu(expanded = owedExpanded, onDismissRequest = { owedExpanded = false }) {
                                                activeOwedAccounts.forEach { acc ->
                                                    DropdownMenuItem(
                                                        text = { Text("${acc.name} (${formatAmount(acc.balance)})", fontSize = 12.sp) },
                                                        onClick = {
                                                            if (jointContribBalanceA < 0) selectedAccountIdB = acc.id else selectedAccountIdA = acc.id
                                                            owedExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                val finalJointSrcAcc = if (jointContribBalanceA < 0) selectedAccountIdA else selectedAccountIdB
                                val finalJointDstAcc = if (jointContribBalanceA < 0) selectedAccountIdB else selectedAccountIdA

                                Button(
                                    onClick = {
                                        if (finalJointSrcAcc.isNotEmpty() && finalJointDstAcc.isNotEmpty()) {
                                            val srcAccObj = accounts.find { it.id == finalJointSrcAcc }
                                            val dstAccObj = accounts.find { it.id == finalJointDstAcc }
                                            val srcLastRec = srcAccObj?.takeIf { it.accountType == "ASSET" || it.accountType == "LIABILITY" }?.lastReconciledDate
                                            val dstLastRec = dstAccObj?.takeIf { it.accountType == "ASSET" || it.accountType == "LIABILITY" }?.lastReconciledDate
                                            val currentTms = System.currentTimeMillis()
                                            
                                            val isSrcLocked = srcLastRec?.let { currentTms < it } ?: false
                                            val isDstLocked = dstLastRec?.let { currentTms < it } ?: false
                                            
                                            if (isSrcLocked || isDstLocked) {
                                                Toast.makeText(context, "Locked Period Error: Action touches locked closed account books.", Toast.LENGTH_LONG).show()
                                            } else {
                                                val owingUserUid = if (jointContribBalanceA < 0) "User 1" else "User 2"
                                                viewModel.addManualTransaction(
                                                    amount = finalJointOwed,
                                                    type = TransactionType.TRANSFER,
                                                    category = "Transfer",
                                                    narration = "Joint Pool Settlement: $owingUserLabel to $owedUserLabel",
                                                    accountId = finalJointSrcAcc,
                                                    targetAccountId = finalJointDstAcc,
                                                    paidBy = owingUserUid,
                                                    scope = TransactionScope.FAMILY,
                                                    dateMillis = currentTms
                                                )
                                                Toast.makeText(context, "Joint pooling settlement contribution recorded successfully!", Toast.LENGTH_LONG).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "Please select both bank accounts.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("settle_joint_contributions_button")
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Settle Joint Pooling Deficit", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // --- JOINT ACCOUNT TOTAL EXPENDITURES AUDIT ---
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Joint Account Expenditures Audit",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = formatAmount(totalJointAccountDirectExpenses),
                                    fontWeight = FontWeight.ExtraBold,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Text(
                                text = "This evaluates family expenditures that were processed directly from the pooled SHARED accounts.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                // --- DETAILED TRANSACTIONS LIST INBOUNDS/OUTBOUNDS FOR TRANSPARENCY ---
                item {
                    Text(
                        text = "Recent Joint Pool Contributions Audit",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (allJointContributions.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No pooled transfer contributions found in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                } else {
                    items(allJointContributions) { tx ->
                        val fromLabel = if (partnerAAccountIds.contains(tx.accountId)) "$partnerAName (Tobin)" else "$partnerBName (Soumya)"
                        val toAccountName = jointAccounts.find { it.id == tx.targetAccountId }?.name ?: "Joint Account"
                        
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Contribution by $fromLabel",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "To: $toAccountName",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val dateText = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(tx.timestamp))
                                    Text(text = dateText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    text = formatAmount(tx.amount),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Family Expenses Paid from Joint Pool",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (jointAccountDirectExpensesList.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No direct joint account expenses recorded in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                } else {
                    items(jointAccountDirectExpensesList) { tx ->
                        val sourceAccountName = jointAccounts.find { it.id == tx.accountId }?.name ?: "Joint Account"
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tx.narration.ifEmpty { tx.category },
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "Paid from: $sourceAccountName",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val dateText = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(tx.timestamp))
                                    Text(text = dateText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    text = formatAmount(tx.amount),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }
}
