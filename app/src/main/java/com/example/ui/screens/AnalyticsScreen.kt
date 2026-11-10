package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.example.data.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.ExpenseViewModel
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

enum class AnalyticsPeriod {
    MONTHLY,
    QUARTERLY,
    YTD,
    CUSTOM
}

data class MonthlyMetric(
    val monthLabel: String,
    val year: Int,
    val monthIndex: Int,
    val startMillis: Long,
    val endMillis: Long,
    val totalIncome: Double,
    val totalExpense: Double,
    val netSavings: Double,
    val savingsRate: Double,
    val user1Spent: Double,
    val user2Spent: Double,
    val categoryBreakdown: Map<String, Double>
)

data class TagSubLedgerSummary(
    val tag: String,
    val totalExpense: Double,
    val totalIncome: Double,
    val netSpent: Double,
    val txCount: Int,
    val earliestMillis: Long,
    val latestMillis: Long,
    val user1Spent: Double,
    val user2Spent: Double,
    val sharedSpent: Double,
    val categoryBreakdown: Map<String, Double>,
    val transactions: List<Transaction>
)

@Composable
fun AnalyticsScreen(
    viewModel: ExpenseViewModel,
    modifier: Modifier = Modifier
) {
    val transactionsState by viewModel.transactions.collectAsState()
    val budgetsState by viewModel.budgets.collectAsState(initial = emptyList())
    val categoriesState by viewModel.categories.collectAsState(initial = emptyList())
    
    val myNameState by viewModel.currentUserFirstName.collectAsState()
    val spouseNameState by viewModel.spouseUserFirstName.collectAsState()
    val name1 = myNameState ?: "You"
    val name2 = spouseNameState ?: "Partner"
    
    var selectedSubTab by remember { mutableStateOf(0) } // 0 = Spending Insights, 1 = Budget Dashboard
    
    var selectedUserTab by remember { mutableStateOf(2) } // Default to 2 = Combined Joint
    var selectedPeriod by remember { mutableStateOf(AnalyticsPeriod.MONTHLY) }
    
    var customStartDate by remember { mutableStateOf(System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000) } // Default 60 days
    var customEndDate by remember { mutableStateOf(System.currentTimeMillis()) }
    
    var showCustomDatePickerDialog by remember { mutableStateOf(false) }
    
    // Budget popups
    var showAddBudgetDialog by remember { mutableStateOf(false) }
    var showEditBudgetDialog by remember { mutableStateOf<Budget?>(null) }

    // Currency Formatting
    val currencyFormat = remember {
        NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
            maximumFractionDigits = 2
        }
    }
    
    fun formatAmount(amount: Double): String {
        return currencyFormat.format(amount).replace("INR", "₹").replace("Rs.", "₹")
    }
    
    fun formatShortDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    // Dynamic month-to-date spending calculation (from start of current month)
    val currentMonthStart = remember {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    
    // Same calculation as the background budget alerts (ignored items skipped, refunds netted).
    val calculateBudgetSpent = remember(transactionsState, categoriesState, currentMonthStart) {
        { bgt: Budget -> com.example.domain.BudgetRules.spent(bgt, transactionsState, categoriesState, System.currentTimeMillis()) }
    }

    val dynamicMonthlySpentMap = rememberInBackground(emptyMap<String, Double>(), transactionsState, currentMonthStart) {
        transactionsState
            .filter { it.status == TransactionStatus.CONFIRMED && !it.isIgnored && it.type == TransactionType.EXPENSE && it.timestamp >= currentMonthStart }
            .groupBy { it.category }
            .mapValues { entry -> entry.value.sumOf { it.amount } }
    }

    // One definition of spending and income, shared with budgets: confirmed, not ignored,
    // refunds taken off spending (not added to income), investment value changes left out.
    val isExpenseCat = remember(categoriesState) { com.example.domain.BudgetRules.isExpenseCategory(categoriesState) }
    val spendOf = { tx: Transaction -> com.example.domain.LedgerRules.spendOf(tx, isExpenseCat) }
    val incomeOf = { tx: Transaction -> com.example.domain.LedgerRules.incomeOf(tx, isExpenseCat) }

    // 1. Filter transactions by user tab selection for Analytics Insights
    val userFilteredTransactions = rememberInBackground(emptyList<Transaction>(), transactionsState, selectedUserTab, name1, name2) {
        transactionsState.filter { tx ->
            if (!com.example.domain.LedgerRules.isCounted(tx)) return@filter false
            val curId = viewModel.currentUserUid.value
            val spId = viewModel.spouseUserUid.value
            when (selectedUserTab) {
                0 -> tx.paidByUserId == "User 1" || (name1.isNotBlank() && tx.ownerFirstName.equals(name1, ignoreCase = true)) || (curId != null && (tx.ownerUserId == curId || tx.paidByUserId == curId))
                1 -> tx.paidByUserId == "User 2" || (name2.isNotBlank() && tx.ownerFirstName.equals(name2, ignoreCase = true)) || (spId != null && (tx.ownerUserId == spId || tx.paidByUserId == spId))
                else -> true // Combined
            }
        }
    }
    
    // 2. Filter transactions by period selection
    val periodTransactions = remember(userFilteredTransactions, selectedPeriod, customStartDate, customEndDate) {
        val now = System.currentTimeMillis()
        val limitTime = when (selectedPeriod) {
            // This calendar month, the same month budgets use.
            AnalyticsPeriod.MONTHLY -> com.example.domain.LedgerRules.monthBounds(now).first
            AnalyticsPeriod.QUARTERLY -> now - (90L * 24 * 60 * 60 * 1000)
            AnalyticsPeriod.YTD -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.MONTH, Calendar.JANUARY)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.timeInMillis
            }
            AnalyticsPeriod.CUSTOM -> customStartDate
        }
        
        userFilteredTransactions.filter { tx ->
            if (selectedPeriod == AnalyticsPeriod.CUSTOM) {
                tx.timestamp in customStartDate..customEndDate
            } else {
                tx.timestamp >= limitTime && tx.timestamp <= now
            }
        }
    }
    
    // 3. Calculate summary metrics (Income, Expenses, Investment / SIP)
    val totalIncome = remember(periodTransactions, isExpenseCat) { periodTransactions.sumOf { incomeOf(it) } }
    
    val totalExpenses = remember(periodTransactions, isExpenseCat) { periodTransactions.sumOf { spendOf(it) } }
    
    val netSavings = totalIncome - totalExpenses
    
    // SIP Contributions
    val totalSipContributions = remember(periodTransactions) {
        periodTransactions.filter { tx ->
            tx.type == TransactionType.EXPENSE && (
                tx.category.contains("SIP", ignoreCase = true) ||
                tx.category.contains("Mutual Fund", ignoreCase = true) ||
                tx.category.contains("Investment", ignoreCase = true) ||
                tx.category.contains("FD", ignoreCase = true) ||
                tx.category.contains("Fixed Deposit", ignoreCase = true)
            )
        }.sumOf { it.amount }
    }

    // 4. Calculate individual category spending
    val categoryBreakdown = remember(periodTransactions, isExpenseCat) {
        val totals = mutableMapOf<String, Double>()
        periodTransactions.forEach { tx ->
            val spend = spendOf(tx)
            if (spend != 0.0) totals[tx.category] = (totals[tx.category] ?: 0.0) + spend
        }
        totals.entries.filter { it.value > 0.0 }.sortedByDescending { it.value }
    }
    
    val maxCategoryValue = remember(categoryBreakdown) {
        categoryBreakdown.firstOrNull()?.value ?: 1.0
    }
    
    // Individual spends within the active period range for User 1 & User 2
    val user1Spent = remember(periodTransactions, name1) {
        val curId = viewModel.currentUserUid.value
        periodTransactions
            .filter { it.type == TransactionType.EXPENSE && (it.paidByUserId == "User 1" || (name1.isNotBlank() && it.ownerFirstName.equals(name1, ignoreCase = true)) || (curId != null && (it.ownerUserId == curId || it.paidByUserId == curId))) }
            .sumOf { it.amount }
    }
    
    val user2Spent = remember(periodTransactions, name2) {
        val spId = viewModel.spouseUserUid.value
        periodTransactions
            .filter { it.type == TransactionType.EXPENSE && (it.paidByUserId == "User 2" || (name2.isNotBlank() && it.ownerFirstName.equals(name2, ignoreCase = true)) || (spId != null && (it.ownerUserId == spId || it.paidByUserId == spId))) }
            .sumOf { it.amount }
    }

    // 5. Multi-Month Trend Summaries (Last 6 Months) for MoM & Cash Flow
    val monthlySummaries = rememberInBackground(emptyList<MonthlyMetric>(), transactionsState, name1, name2, categoriesState) {
        val result = mutableListOf<MonthlyMetric>()
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val monthFmt = SimpleDateFormat("MMM yy", Locale.getDefault())
        val curId = viewModel.currentUserUid.value
        val spId = viewModel.spouseUserUid.value

        for (i in 0 until 6) {
            val loopCal = cal.clone() as Calendar
            loopCal.add(Calendar.MONTH, -i)
            
            val startM = loopCal.timeInMillis
            val daysInMonth = loopCal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val endCal = loopCal.clone() as Calendar
            endCal.set(Calendar.DAY_OF_MONTH, daysInMonth)
            endCal.set(Calendar.HOUR_OF_DAY, 23)
            endCal.set(Calendar.MINUTE, 59)
            endCal.set(Calendar.SECOND, 59)
            endCal.set(Calendar.MILLISECOND, 999)
            val endM = endCal.timeInMillis

            val monthTxs = transactionsState.filter { 
                com.example.domain.LedgerRules.isCounted(it) && it.timestamp in startM..endM 
            }
            
            val inc = monthTxs.sumOf { incomeOf(it) }
            val exp = monthTxs.sumOf { spendOf(it) }
            val net = inc - exp
            val sRate = if (inc > 0) (net / inc) * 100.0 else 0.0
            
            val u1 = monthTxs.filter { it.type == TransactionType.EXPENSE && (it.paidByUserId == "User 1" || it.ownerFirstName.equals(name1, ignoreCase = true) || it.ownerUserId == curId || it.paidByUserId == curId) }.sumOf { it.amount }
            val u2 = monthTxs.filter { it.type == TransactionType.EXPENSE && (it.paidByUserId == "User 2" || it.ownerFirstName.equals(name2, ignoreCase = true) || it.ownerUserId == spId || it.paidByUserId == spId) }.sumOf { it.amount }

            val catMap = mutableMapOf<String, Double>()
            monthTxs.forEach { tx ->
                val spend = spendOf(tx)
                if (spend != 0.0) catMap[tx.category] = (catMap[tx.category] ?: 0.0) + spend
            }

            result.add(
                MonthlyMetric(
                    monthLabel = monthFmt.format(loopCal.time),
                    year = loopCal.get(Calendar.YEAR),
                    monthIndex = loopCal.get(Calendar.MONTH),
                    startMillis = startM,
                    endMillis = endM,
                    totalIncome = inc,
                    totalExpense = exp,
                    netSavings = net,
                    savingsRate = sRate,
                    user1Spent = u1,
                    user2Spent = u2,
                    categoryBreakdown = catMap
                )
            )
        }
        result.reversed() // Chronological order: Oldest to Newest (Current)
    }

    val currentMonthSummary = remember(monthlySummaries) { monthlySummaries.lastOrNull() }
    val previousMonthSummary = remember(monthlySummaries) { monthlySummaries.getOrNull(monthlySummaries.size - 2) }

    val momSpendDiff = remember(currentMonthSummary, previousMonthSummary) {
        val cur = currentMonthSummary?.totalExpense ?: 0.0
        val prev = previousMonthSummary?.totalExpense ?: 0.0
        cur - prev
    }

    val momSpendPctDiff = remember(currentMonthSummary, previousMonthSummary) {
        val cur = currentMonthSummary?.totalExpense ?: 0.0
        val prev = previousMonthSummary?.totalExpense ?: 0.0
        if (prev > 0) ((cur - prev) / prev) * 100.0 else 0.0
    }

    val momIncomeDiff = remember(currentMonthSummary, previousMonthSummary) {
        val cur = currentMonthSummary?.totalIncome ?: 0.0
        val prev = previousMonthSummary?.totalIncome ?: 0.0
        cur - prev
    }

    val momSavingsRateDiff = remember(currentMonthSummary, previousMonthSummary) {
        val cur = currentMonthSummary?.savingsRate ?: 0.0
        val prev = previousMonthSummary?.savingsRate ?: 0.0
        cur - prev
    }

    // Category MoM shifts (Current Month vs Previous Month)
    val momCategoryShifts = remember(currentMonthSummary, previousMonthSummary) {
        val curMap = currentMonthSummary?.categoryBreakdown ?: emptyMap()
        val prevMap = previousMonthSummary?.categoryBreakdown ?: emptyMap()
        val allCats = (curMap.keys + prevMap.keys).toSet()
        
        allCats.map { cat ->
            val curAmt = curMap[cat] ?: 0.0
            val prevAmt = prevMap[cat] ?: 0.0
            val diff = curAmt - prevAmt
            val pct = if (prevAmt > 0) ((curAmt - prevAmt) / prevAmt) * 100.0 else if (curAmt > 0) 100.0 else 0.0
            Triple(cat, curAmt, diff)
        }.sortedByDescending { kotlin.math.abs(it.third) }
    }

    // 6. Trip & Event Sub-Ledger Summaries (Feature 5)
    val tagSubLedgerSummaries = rememberInBackground(emptyList<TagSubLedgerSummary>(), transactionsState, name1, name2) {
        val curId = viewModel.currentUserUid.value
        val spId = viewModel.spouseUserUid.value
        
        val taggedTxs = transactionsState.filter { 
            it.status == TransactionStatus.CONFIRMED && it.tag.isNotBlank() 
        }

        taggedTxs.groupBy { it.tag.trim() }.map { (tag, txList) ->
            val expenseTotal = txList.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
            val incomeTotal = txList.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
            val net = expenseTotal - incomeTotal
            
            val u1 = txList.filter { it.type == TransactionType.EXPENSE && (it.paidByUserId == "User 1" || (name1.isNotBlank() && it.ownerFirstName.equals(name1, ignoreCase = true)) || (curId != null && (it.ownerUserId == curId || it.paidByUserId == curId))) }.sumOf { it.amount }
            val u2 = txList.filter { it.type == TransactionType.EXPENSE && (it.paidByUserId == "User 2" || (name2.isNotBlank() && it.ownerFirstName.equals(name2, ignoreCase = true)) || (spId != null && (it.ownerUserId == spId || it.paidByUserId == spId))) }.sumOf { it.amount }
            val shared = txList.filter { it.type == TransactionType.EXPENSE && (it.paidByUserId.equals("BOTH", ignoreCase = true) || it.paidByUserId.isBlank()) }.sumOf { it.amount }

            val catMap = mutableMapOf<String, Double>()
            txList.filter { it.type == TransactionType.EXPENSE }.forEach { tx ->
                catMap[tx.category] = (catMap[tx.category] ?: 0.0) + tx.amount
            }

            val timestamps = txList.map { it.timestamp }
            val earliest = timestamps.minOrNull() ?: System.currentTimeMillis()
            val latest = timestamps.maxOrNull() ?: System.currentTimeMillis()

            TagSubLedgerSummary(
                tag = if (tag.startsWith("#")) tag else "#$tag",
                totalExpense = expenseTotal,
                totalIncome = incomeTotal,
                netSpent = net,
                txCount = txList.size,
                earliestMillis = earliest,
                latestMillis = latest,
                user1Spent = u1,
                user2Spent = u2,
                sharedSpent = shared,
                categoryBreakdown = catMap.entries.sortedByDescending { it.value }.associate { it.key to it.value },
                transactions = txList.sortedByDescending { it.timestamp }
            )
        }.sortedByDescending { it.latestMillis }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("analytics_screen")
            .padding(horizontal = 16.dp)
    ) {
        // Headline and Overview
        item {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    text = "Family Analytics & Budgets",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Synchronized family spending insights and real-time budget caps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Sub-Navigation Tabs: 4-way Analytics Suite
        item {
            ScrollableTabRow(
                selectedTabIndex = selectedSubTab,
                edgePadding = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                indicator = {}
            ) {
                Tab(
                    selected = selectedSubTab == 0,
                    onClick = { selectedSubTab = 0 },
                    modifier = Modifier.testTag("tab_spending_insights"),
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PieChart,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (selectedSubTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Spending",
                                fontWeight = FontWeight.Bold,
                                color = if (selectedSubTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
                Tab(
                    selected = selectedSubTab == 1,
                    onClick = { selectedSubTab = 1 },
                    modifier = Modifier.testTag("tab_trends_mom"),
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.TrendingUp,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (selectedSubTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Trends & MoM",
                                fontWeight = FontWeight.Bold,
                                color = if (selectedSubTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
                Tab(
                    selected = selectedSubTab == 2,
                    onClick = { selectedSubTab = 2 },
                    modifier = Modifier.testTag("tab_budget_dashboard"),
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountBalance,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (selectedSubTab == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Budgets",
                                fontWeight = FontWeight.Bold,
                                color = if (selectedSubTab == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
                Tab(
                    selected = selectedSubTab == 3,
                    onClick = { selectedSubTab = 3 },
                    modifier = Modifier.testTag("tab_trips_events"),
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bookmark,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (selectedSubTab == 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Trips & Events",
                                fontWeight = FontWeight.Bold,
                                color = if (selectedSubTab == 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (selectedSubTab == 0) {
            // ================== TAB 1: SPENDING INSIGHTS ==================
            
            // User Filter Selector Tabs
            item {
                TabRow(
                    selectedTabIndex = selectedUserTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    indicator = {}
                ) {
                    listOf(name1, name2, "Combined").forEachIndexed { index, title ->
                        val selected = selectedUserTab == index
                        val icon = when (index) {
                            0 -> Icons.Default.Person
                            1 -> Icons.Default.Person
                            else -> Icons.Default.People
                        }
                        Tab(
                            selected = selected,
                            onClick = { selectedUserTab = index },
                            modifier = Modifier.testTag("user_tab_$index"),
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                
                // Real-time Contributor share overview card for Tobin vs Soumya
                if (selectedUserTab == 2) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Tobin's Share Card
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(MaterialTheme.colorScheme.primary, shape = CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = name1.take(1).uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                                Column {
                                    Text(text = name1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Text(text = formatAmount(user1Spent), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                        
                        // Soumya's Share Card
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.12f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(MaterialTheme.colorScheme.tertiary, shape = CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = name2.take(1).uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiary
                                    )
                                }
                                Column {
                                    Text(text = name2, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                                    Text(text = formatAmount(user2Spent), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            // Date Range Selection Chips
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnalyticsPeriod.values().forEach { period ->
                        val selected = selectedPeriod == period
                        val text = when (period) {
                            AnalyticsPeriod.MONTHLY -> "Monthly"
                            AnalyticsPeriod.QUARTERLY -> "Quarterly"
                            AnalyticsPeriod.YTD -> "YTD"
                            AnalyticsPeriod.CUSTOM -> "Custom"
                        }
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedPeriod = period
                                if (period == AnalyticsPeriod.CUSTOM) {
                                    showCustomDatePickerDialog = true
                                }
                            },
                            label = { Text(text) },
                            modifier = Modifier.testTag("period_chip_${text.lowercase()}")
                        )
                    }
                }
                
                if (selectedPeriod == AnalyticsPeriod.CUSTOM) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable { showCustomDatePickerDialog = true },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Active Range", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = "${formatShortDate(customStartDate)} — ${formatShortDate(customEndDate)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "Change Range")
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            
            // Income & Spending Overview Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "FAMILY INCOME & SPENDING PERIODIC SUMMARY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Total Earned", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = formatAmount(totalIncome),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                            
                            Divider(modifier = Modifier.height(36.dp).width(1.dp).align(Alignment.CenterVertically))
                            
                            Column {
                                Text("Total Expenses", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = formatAmount(totalExpenses),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        val bgSurplusColor = if (netSavings >= 0) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                        val textSurplusColor = if (netSavings >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(bgSurplusColor)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Net Surplus/Savings During Period",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = textSurplusColor
                            )
                            Text(
                                text = formatAmount(netSavings),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = textSurplusColor
                            )
                        }
                    }
                }
            }
            
            // SIP/Mutual Fund Contributions
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SIP & Mutual Fund Investments",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Contributions logged this period",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = formatAmount(totalSipContributions),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Future Assets",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
            
            // Spending Breakdown List Header and Doughnut visualizer (Point 4)
            if (categoryBreakdown.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Interactive Spending Breakdown",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "Visual share of categories for the selected period",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    val categoryBreakdownMap = remember(categoryBreakdown) {
                        categoryBreakdown.associate { it.key to it.value }
                    }
                    InteractiveDoughnutChart(
                        categoryTotals = categoryBreakdownMap,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            } else {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Interactive Spending Breakdown",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
            
            if (categoryBreakdown.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text(
                            text = "No expenses logged during this tracking period.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                items(categoryBreakdown) { (category, total) ->
                    val fraction = if (maxCategoryValue > 0) (total / maxCategoryValue).toFloat() else 0f
                    val percentage = if (totalExpenses > 0) ((total / totalExpenses) * 100).toInt() else 0
                    val categoryColor = CategoryColors[category] ?: MaterialTheme.colorScheme.primary
                    val categoryIcon = getCategoryIcon(category)
                    
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Category Icon Container
                            Surface(
                                modifier = Modifier.size(40.dp),
                                color = categoryColor.copy(alpha = 0.12f),
                                shape = CircleShape
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = categoryIcon,
                                        contentDescription = category,
                                        tint = categoryColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.width(12.dp))
                            
                            // Name and Progress bar
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = category,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "$percentage%",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    
                                    Text(
                                        text = formatAmount(total),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // Colorized Progress bar
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(fraction)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(categoryColor)
                                    )
                                }
                            }
                        }
                    }
                }
            }

        } else if (selectedSubTab == 1) {
            // ================== TAB 1: MULTI-MONTH TRENDS & MOM ==================

            // 1. MoM Flash Headline KPI Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .testTag("mom_comparison_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "MONTH-OVER-MONTH COMPARISON",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "${currentMonthSummary?.monthLabel ?: "Current"} vs ${previousMonthSummary?.monthLabel ?: "Previous"}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CompareArrows,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(8.dp).size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Spending Variance Pill
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (momSpendDiff > 0) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) else Color(0xFFE8F5E9)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Expense Shift",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = (if (momSpendDiff > 0) "+" else "") + formatAmount(momSpendDiff),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (momSpendDiff > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (momSpendDiff > 0) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                            contentDescription = null,
                                            tint = if (momSpendDiff > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = String.format(Locale.getDefault(), "%.1f%%", kotlin.math.abs(momSpendPctDiff)),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (momSpendDiff > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                                        )
                                    }
                                }
                            }

                            // Income Variance Pill
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (momIncomeDiff >= 0) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Inflow Shift",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = (if (momIncomeDiff >= 0) "+" else "") + formatAmount(momIncomeDiff),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (momIncomeDiff >= 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        text = "Total Inflow",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp
                                    )
                                }
                            }

                            // Savings Rate Pill
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Savings Rate",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = String.format(Locale.getDefault(), "%.1f%%", currentMonthSummary?.savingsRate ?: 0.0),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = (if (momSavingsRateDiff >= 0) "+" else "") + String.format(Locale.getDefault(), "%.1f%% pts", momSavingsRateDiff),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (momSavingsRateDiff >= 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. 6-Month Cash Flow Bar Chart Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .testTag("six_month_cashflow_chart"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "6-MONTH CASH FLOW & LIQUIDITY",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Income vs. Expense Outflow Trajectory",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        MultiMonthCashFlowBarChart(
                            summaries = monthlySummaries,
                            currencyFormat = currencyFormat,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(12.dp))

                        // 6-Month Averages Row
                        val avgExp = remember(monthlySummaries) { if (monthlySummaries.isNotEmpty()) monthlySummaries.map { it.totalExpense }.average() else 0.0 }
                        val avgInc = remember(monthlySummaries) { if (monthlySummaries.isNotEmpty()) monthlySummaries.map { it.totalIncome }.average() else 0.0 }
                        val totalNet6M = remember(monthlySummaries) { monthlySummaries.sumOf { it.netSavings } }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("6M Monthly Avg Inflow", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatAmount(avgInc), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                            }
                            Column {
                                Text("6M Monthly Avg Burn", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatAmount(avgExp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("6M Net Capital Growth", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatAmount(totalNet6M), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            // 3. Category Cost Movers (MoM Spending Shifts)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "CATEGORY DRILLDOWN & DELTAS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Month-over-Month Category Shifts",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Tracking which expense categories grew or reduced the most vs last month",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        if (momCategoryShifts.isEmpty()) {
                            Text(
                                text = "No category comparative data recorded across these months yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            momCategoryShifts.take(8).forEach { (category, currentVal, diff) ->
                                val categoryColor = CategoryColors[category] ?: MaterialTheme.colorScheme.primary
                                val isIncrease = diff > 0

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .background(categoryColor.copy(alpha = 0.15f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = getCategoryIcon(category),
                                            contentDescription = category,
                                            tint = categoryColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = category,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = formatAmount(currentVal),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Current Month Total",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = if (isIncrease) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                                    contentDescription = null,
                                                    tint = if (isIncrease) MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = (if (isIncrease) "+" else "") + formatAmount(diff) + " vs last mo",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isIncrease) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                                                )
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                            }
                        }
                    }
                }
            }

            // 4. Partner 6-Month Expenditure Share
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "PARTNER EXPENSE TRAJECTORY",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "$name1 vs. $name2 (Last 6 Months)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        monthlySummaries.forEach { summary ->
                            val totalSpent = summary.user1Spent + summary.user2Spent
                            val u1Ratio = if (totalSpent > 0) (summary.user1Spent / totalSpent).toFloat() else 0.5f

                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = summary.monthLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "$name1: ${formatAmount(summary.user1Spent)} | $name2: ${formatAmount(summary.user2Spent)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .weight(u1Ratio.coerceAtLeast(0.01f))
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .weight((1f - u1Ratio).coerceAtLeast(0.01f))
                                            .background(MaterialTheme.colorScheme.tertiary)
                                    )
                                }
                            }
                        }
                    }
                }
            }

        } else if (selectedSubTab == 2) {
            // ================== TAB 2: BUDGET DASHBOARD ==================
            
            // Overall Budgets Health Summary Card
            item {
                // The family budget when there is one, otherwise the category budgets added up.
                val (totalBudgeted, totalSpent) = com.example.domain.BudgetRules.familyTotals(budgetsState) { calculateBudgetSpent(it) }
                val overallProgress = if (totalBudgeted > 0.0) (totalSpent / totalBudgeted).toFloat().coerceIn(0f, 1f) else 0f
                val activeCount = budgetsState.size
                val violatedCount = budgetsState.count { calculateBudgetSpent(it) > it.monthlyLimit }
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "FAMILY BUDGET ALLOCATION STATUS",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Computed from all active category thresholds",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            // Visual Active/Violated Badge
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (violatedCount > 0) MaterialTheme.colorScheme.errorContainer
                                        else Color(0xFFE8F5E9)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (violatedCount > 0) "$violatedCount ALERT" else "ALL OK",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (violatedCount > 0) MaterialTheme.colorScheme.onErrorContainer else Color(0xFF2E7D32),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Total Allocated", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = formatAmount(totalBudgeted),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Total Monthly Spent", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = formatAmount(totalSpent),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (violatedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Grand Progress Bar
                        LinearProgressIndicator(
                            progress = { overallProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = if (violatedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Active Budget Caps: $activeCount",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val remaining = totalBudgeted - totalSpent
                            val familyPerDay = com.example.domain.BudgetRules.safePerDay(totalBudgeted, totalSpent, System.currentTimeMillis())
                            Text(
                                text = if (remaining >= 0) "Left: ${formatAmount(remaining)} · ${formatAmount(familyPerDay ?: 0.0)}/day" +
                                    (com.example.domain.BudgetRules.headingOverBy(totalBudgeted, totalSpent, System.currentTimeMillis())?.let { "\nAt this pace: over by ${formatAmount(it)}" } ?: "")
                                    else "Over Limit: ${formatAmount(-remaining)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (remaining >= 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Quick Create Budget Button
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Category Budget Targets",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Button(
                        onClick = { showAddBudgetDialog = true },
                        modifier = Modifier.testTag("add_budget_target_btn"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Target", fontSize = 12.sp)
                    }
                }
            }

            if (budgetsState.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No active monthly budgets set up.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Add spending targets to monitor and enforce limits.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(budgetsState) { bgt ->
                    val spent = calculateBudgetSpent(bgt)
                    val isViolated = spent > bgt.monthlyLimit
                    val progress = if (bgt.monthlyLimit > 0.0) (spent / bgt.monthlyLimit).toFloat().coerceIn(0f, 1f) else 0f
                    
                    val iconCategory = bgt.subCategory.ifBlank { bgt.mainCategory.ifBlank { bgt.category } }
                    val displayName = com.example.domain.BudgetRules.label(bgt)
                    val scopeTag = when {
                        bgt.isOverall -> "All spending"
                        bgt.subCategory.isNotBlank() && bgt.mainCategory.isNotBlank() -> bgt.mainCategory
                        bgt.subCategory.isBlank() && bgt.mainCategory.isNotBlank() -> "All ${bgt.mainCategory}"
                        else -> "Budget"
                    }
                    
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .testTag("budget_target_card_${bgt.category}"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Circular category icon
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isViolated) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = getCategoryIcon(iconCategory),
                                            contentDescription = null,
                                            tint = if (isViolated) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    
                                    Column {
                                        Text(
                                            text = displayName,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        
                                        Spacer(modifier = Modifier.height(2.dp))
                                        
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = scopeTag.uppercase(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }

                                        }
                                    }
                                }
                                
                                // Action buttons (Edit & Delete)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { showEditBudgetDialog = bgt },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit budget",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteBudget(bgt.budgetId) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete budget",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // Progress bar
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = if (isViolated) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    if (progress > 0.85f) Color(0xFFFBC02D) // Warning Yellow
                                    else MaterialTheme.colorScheme.primary
                                },
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Spent: ${formatAmount(spent)} of ${formatAmount(bgt.monthlyLimit)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isViolated) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                val diff = bgt.monthlyLimit - spent
                                // "Safe to spend": what's left spread over the rest of the month.
                                val perDay = com.example.domain.BudgetRules.safePerDay(bgt.monthlyLimit, spent, System.currentTimeMillis())
                                Text(
                                    text = if (diff >= 0) (com.example.domain.BudgetRules.headingOverBy(bgt.monthlyLimit, spent, System.currentTimeMillis())?.let { "On pace to go over by ${formatAmount(it)}" }
                                        ?: "${formatAmount(perDay ?: 0.0)}/day left") else "Over by ${formatAmount(-diff)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (diff >= 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // ================== TAB 3: TRIPS & EVENT SUB-LEDGERS ==================

            // 1. Overall Sub-Ledger KPI Overview Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .testTag("trips_overview_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "PROJECT & EVENT SUB-LEDGERS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "Trip, Renovation & Event Budgets",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bookmark,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(8.dp).size(20.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val totalTagExpense = tagSubLedgerSummaries.sumOf { it.totalExpense }
                        val totalTagTxs = tagSubLedgerSummaries.sumOf { it.txCount }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Card(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Active Projects", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("${tagSubLedgerSummaries.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                                    Text("Tagged Sub-Ledgers", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Card(
                                modifier = Modifier.weight(1.5f),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Total Tagged Outflow", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(formatAmount(totalTagExpense), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                                    Text("$totalTagTxs transactions isolated", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            if (tagSubLedgerSummaries.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.BookmarkBorder,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No Tagged Projects or Trips Yet",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Organize group events, vacations, or home projects by assigning a tag like #GoaTrip, #HomeRenovation, or #Wedding to transactions in the ledger. Their dedicated budgets, category shares, and partner splits will automatically compute here!",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(tagSubLedgerSummaries) { subLedger ->
                    SubLedgerCard(
                        summary = subLedger,
                        name1 = name1,
                        name2 = name2,
                        formatAmount = ::formatAmount,
                        formatDate = ::formatShortDate
                    )
                }
            }
        }
        
        item {
            Box(modifier = Modifier.height(80.dp))
        }
    }
    
    // CUSTOM DATE SELECTOR DIALOG
    if (showCustomDatePickerDialog) {
        val context = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(
            onDismissRequest = { showCustomDatePickerDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Custom Range")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "Specify custom start and end dates for detailed tracking, or choose a shortcut preset below:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Start and End Date selection row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Start Date Button
                        OutlinedButton(
                            onClick = {
                                showDatePicker(context, customStartDate) { selectedDate ->
                                    customStartDate = selectedDate
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(10.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("From Date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(customStartDate)),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        
                        // End Date Button
                        OutlinedButton(
                            onClick = {
                                showDatePicker(context, customEndDate) { selectedDate ->
                                    customEndDate = selectedDate
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(10.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("To Date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(customEndDate)),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    
                    Text(
                        text = "Preset Shortcuts",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(15 to "15 Days", 45 to "45 Days", 180 to "180 Days").forEach { (days, label) ->
                            OutlinedButton(
                                onClick = {
                                    customStartDate = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
                                    customEndDate = System.currentTimeMillis()
                                    showCustomDatePickerDialog = false
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                            ) {
                                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showCustomDatePickerDialog = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Apply Range")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDatePickerDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // CREATE BUDGET DIALOG
    if (showAddBudgetDialog) {
        var selectedMainCategory by remember { mutableStateOf("Food & Dining") }
        var selectedSubCategory by remember { mutableStateOf("Groceries") }
        var budgetScope by remember { mutableStateOf(0) } // 0 = Sub-Category, 1 = Entire Main Category
        var showCategoryPicker by remember { mutableStateOf(false) }
        var limitStr by remember { mutableStateOf("") }
        val hasOverall = budgetsState.any { it.isOverall }
        
        val activeCategoryDisplay = if (budgetScope == 1 || selectedSubCategory.isBlank()) selectedMainCategory else selectedSubCategory

        AlertDialog(
            onDismissRequest = { showAddBudgetDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Set Budget Target", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val limitVal = limitStr.toDoubleOrNull() ?: 0.0
                        val finalCategory = if (budgetScope == 1 || selectedSubCategory.isBlank()) selectedMainCategory else selectedSubCategory
                        val finalSub = if (budgetScope == 1) "" else selectedSubCategory
                        if (budgetScope == 2 && limitVal > 0.0 && !hasOverall) {
                            viewModel.addBudget(Budget(category = "All spending", monthlyLimit = limitVal, isOverall = true))
                            showAddBudgetDialog = false
                        } else if (budgetScope != 2 && finalCategory.isNotBlank() && limitVal > 0.0) {
                            viewModel.addBudget(
                                Budget(
                                    category = finalCategory,
                                    mainCategory = selectedMainCategory,
                                    subCategory = finalSub,
                                    monthlyLimit = limitVal
                                )
                            )
                            showAddBudgetDialog = false
                        }
                    },
                    enabled = (limitStr.toDoubleOrNull() ?: 0.0) > 0.0 && !(budgetScope == 2 && hasOverall)
                ) {
                    Text("Save Budget")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddBudgetDialog = false }) {
                    Text("Cancel")
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Budget Target Level",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = { budgetScope = 0 },
                            shape = RoundedCornerShape(10.dp),
                            color = if (budgetScope == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, if (budgetScope == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Sub-Category",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (budgetScope == 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "e.g. Groceries",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            onClick = { budgetScope = 1 },
                            shape = RoundedCornerShape(10.dp),
                            color = if (budgetScope == 1) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, if (budgetScope == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Main Category",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (budgetScope == 1) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "e.g. All Food & Dining",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Whole-family budget: everything the household spends, across categories.
                    Surface(
                        onClick = { budgetScope = 2 },
                        shape = RoundedCornerShape(10.dp),
                        color = if (budgetScope == 2) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, if (budgetScope == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Whole family",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (budgetScope == 2) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (hasOverall) "You already have a family budget; edit it instead" else "All spending, every category",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (budgetScope != 2) {
                        Spacer(modifier = Modifier.height(12.dp))

                        CategoryPickerField(
                            label = if (budgetScope == 0) "Category & Sub-Category" else "Main Category",
                            selectedCategory = activeCategoryDisplay,
                            onClick = { showCategoryPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = limitStr,
                        onValueChange = { limitStr = it },
                        label = { Text("Monthly Budget Limit (₹)", fontSize = 12.sp) },
                        placeholder = { Text("e.g. 10000", fontSize = 12.sp) },
                        leadingIcon = { Text("₹", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "You'll both be alerted at 80% of the limit and again if it goes over.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        SearchableCategoryPickerBottomSheet(
            visible = showCategoryPicker,
            onDismiss = { showCategoryPicker = false },
            categories = categoriesState,
            selectedCategory = activeCategoryDisplay,
            onCategorySelected = { pickedCat ->
                val acc = categoriesState.find { it.name.equals(pickedCat, ignoreCase = true) || it.id.equals(pickedCat, ignoreCase = true) }
                selectedMainCategory = acc?.mainCategory?.ifBlank { acc.name } ?: pickedCat
                selectedSubCategory = if (!acc?.subCategory.isNullOrBlank()) acc!!.subCategory else acc?.name ?: pickedCat
                showCategoryPicker = false
            },
            transactionType = TransactionType.EXPENSE
        )
    }

    // EDIT BUDGET DIALOG
    showEditBudgetDialog?.let { currentBudget ->
        var selectedMainCategory by remember(currentBudget.budgetId) {
            mutableStateOf(currentBudget.mainCategory.ifBlank { currentBudget.category })
        }
        var selectedSubCategory by remember(currentBudget.budgetId) {
            mutableStateOf(currentBudget.subCategory)
        }
        var budgetScope by remember(currentBudget.budgetId) {
            mutableStateOf(if (currentBudget.subCategory.isBlank() && currentBudget.mainCategory.isNotBlank()) 1 else 0)
        }
        var showCategoryPicker by remember { mutableStateOf(false) }
        var limitStr by remember(currentBudget.budgetId) { mutableStateOf(currentBudget.monthlyLimit.toString()) }
        
        val activeCategoryDisplay = if (budgetScope == 1 || selectedSubCategory.isBlank()) selectedMainCategory else selectedSubCategory

        AlertDialog(
            onDismissRequest = { showEditBudgetDialog = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Edit Budget Target", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val limitVal = limitStr.toDoubleOrNull() ?: 0.0
                        val finalCategory = if (budgetScope == 1 || selectedSubCategory.isBlank()) selectedMainCategory else selectedSubCategory
                        val finalSub = if (budgetScope == 1) "" else selectedSubCategory
                        if (currentBudget.isOverall && limitVal > 0.0) {
                            viewModel.updateBudget(currentBudget.copy(monthlyLimit = limitVal))
                            showEditBudgetDialog = null
                        } else if (finalCategory.isNotBlank() && limitVal > 0.0) {
                            viewModel.updateBudget(
                                currentBudget.copy(
                                    category = finalCategory,
                                    mainCategory = selectedMainCategory,
                                    subCategory = finalSub,
                                    monthlyLimit = limitVal
                                )
                            )
                            showEditBudgetDialog = null
                        }
                    },
                    enabled = (limitStr.toDoubleOrNull() ?: 0.0) > 0.0
                ) {
                    Text("Update Budget")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditBudgetDialog = null }) {
                    Text("Cancel")
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (currentBudget.isOverall) {
                        Text("Family budget: all spending, every category", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    } else {
                        Text(
                            text = "Budget Target Level",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                onClick = { budgetScope = 0 },
                                shape = RoundedCornerShape(10.dp),
                                color = if (budgetScope == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, if (budgetScope == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Sub-Category",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (budgetScope == 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "e.g. Groceries",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Surface(
                                onClick = { budgetScope = 1 },
                                shape = RoundedCornerShape(10.dp),
                                color = if (budgetScope == 1) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, if (budgetScope == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Main Category",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (budgetScope == 1) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "e.g. All Food & Dining",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        CategoryPickerField(
                            label = if (budgetScope == 0) "Category & Sub-Category" else "Main Category",
                            selectedCategory = activeCategoryDisplay,
                            onClick = { showCategoryPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = limitStr,
                        onValueChange = { limitStr = it },
                        label = { Text("Monthly Budget Limit (₹)", fontSize = 12.sp) },
                        leadingIcon = { Text("₹", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "You'll both be alerted at 80% of the limit and again if it goes over.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        SearchableCategoryPickerBottomSheet(
            visible = showCategoryPicker,
            onDismiss = { showCategoryPicker = false },
            categories = categoriesState,
            selectedCategory = activeCategoryDisplay,
            onCategorySelected = { pickedCat ->
                val acc = categoriesState.find { it.name.equals(pickedCat, ignoreCase = true) || it.id.equals(pickedCat, ignoreCase = true) }
                selectedMainCategory = acc?.mainCategory?.ifBlank { acc.name } ?: pickedCat
                selectedSubCategory = if (!acc?.subCategory.isNullOrBlank()) acc!!.subCategory else acc?.name ?: pickedCat
                showCategoryPicker = false
            },
            transactionType = TransactionType.EXPENSE
        )
    }
}

private fun showDatePicker(context: Context, initialDateMillis: Long, onDateSelected: (Long) -> Unit) {
    val calendar = Calendar.getInstance().apply { timeInMillis = initialDateMillis }
    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            val selectedCalendar = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, dayOfMonth)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            onDateSelected(selectedCalendar.timeInMillis)
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    ).show()
}

@Composable
fun MultiMonthCashFlowBarChart(
    summaries: List<MonthlyMetric>,
    currencyFormat: NumberFormat,
    modifier: Modifier = Modifier
) {
    if (summaries.isEmpty()) return

    val maxVal = remember(summaries) {
        val maxInc = summaries.maxOfOrNull { it.totalIncome } ?: 0.0
        val maxExp = summaries.maxOfOrNull { it.totalExpense } ?: 0.0
        maxOf(maxInc, maxExp, 1000.0)
    }

    val incomeColor = Color(0xFF2E7D32)
    val expenseColor = Color(0xFFD32F2F)

    Column(modifier = modifier) {
        // Legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.size(10.dp).background(incomeColor, CircleShape))
                Text("Income", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.size(10.dp).background(expenseColor, CircleShape))
                Text("Expense", style = MaterialTheme.typography.labelSmall)
            }
        }

        // Chart Canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val chartHeight = canvasHeight
            val numMonths = summaries.size
            val groupWidth = canvasWidth / numMonths.toFloat()
            val barWidth = (groupWidth * 0.32f).coerceAtMost(20.dp.toPx())

            summaries.forEachIndexed { index, metric ->
                val groupCenterX = groupWidth * index + groupWidth / 2f
                val incBarHeight = ((metric.totalIncome / maxVal) * chartHeight).toFloat().coerceAtLeast(0f)
                val expBarHeight = ((metric.totalExpense / maxVal) * chartHeight).toFloat().coerceAtLeast(0f)

                val incLeft = groupCenterX - barWidth - 2.dp.toPx()
                val expLeft = groupCenterX + 2.dp.toPx()

                // Income Bar
                if (incBarHeight > 0f) {
                    drawRoundRect(
                        color = incomeColor.copy(alpha = 0.85f),
                        topLeft = Offset(incLeft, chartHeight - incBarHeight),
                        size = Size(barWidth, incBarHeight),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                }

                // Expense Bar
                if (expBarHeight > 0f) {
                    drawRoundRect(
                        color = expenseColor.copy(alpha = 0.85f),
                        topLeft = Offset(expLeft, chartHeight - expBarHeight),
                        size = Size(barWidth, expBarHeight),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                }

                // Baseline separator
                drawLine(
                    color = Color.LightGray.copy(alpha = 0.4f),
                    start = Offset(0f, chartHeight - 1f),
                    end = Offset(canvasWidth, chartHeight - 1f),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Month Labels Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            summaries.forEach { summary ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = summary.monthLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "₹${(summary.totalExpense / 1000).toInt()}k",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = if (summary.netSavings >= 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun SubLedgerCard(
    summary: TagSubLedgerSummary,
    name1: String,
    name2: String,
    formatAmount: (Double) -> String,
    formatDate: (Long) -> String,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("subledger_card_${summary.tag}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Tag Header & Total Outflow
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = summary.tag,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${summary.txCount} txs",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = formatAmount(summary.totalExpense),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${formatDate(summary.earliestMillis)} – ${formatDate(summary.latestMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Partner Split Ratio Bar
            val totalParticipantSpent = summary.user1Spent + summary.user2Spent + summary.sharedSpent
            val u1Ratio = if (totalParticipantSpent > 0) (summary.user1Spent / totalParticipantSpent).toFloat() else 0.5f
            val u2Ratio = if (totalParticipantSpent > 0) (summary.user2Spent / totalParticipantSpent).toFloat() else 0.5f

            Text(
                text = "CONTRIBUTOR BREAKDOWN",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                if (summary.user1Spent > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(u1Ratio.coerceAtLeast(0.01f))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                if (summary.user2Spent > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(u2Ratio.coerceAtLeast(0.01f))
                            .background(MaterialTheme.colorScheme.tertiary)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "$name1: ${formatAmount(summary.user1Spent)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$name2: ${formatAmount(summary.user2Spent)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Category Breakdown Inside This Event
            if (summary.categoryBreakdown.isNotEmpty()) {
                Text(
                    text = "CATEGORY ALLOCATION",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(6.dp))

                summary.categoryBreakdown.entries.take(4).forEach { (cat, amt) ->
                    val catColor = getCategoryColor(cat)
                    val pct = if (summary.totalExpense > 0) ((amt / summary.totalExpense) * 100).toInt() else 0

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).background(catColor, CircleShape))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = cat, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            text = "${formatAmount(amt)} ($pct%)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Expandable Transactions Accordion
            OutlinedButton(
                onClick = { isExpanded = !isExpanded },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isExpanded) "Hide Tagged Transactions" else "View ${summary.txCount} Tagged Transactions")
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    summary.transactions.forEach { tx ->
                        val isExpense = tx.type == TransactionType.EXPENSE
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tx.narration.ifBlank { tx.category },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${formatDate(tx.timestamp)} • ${tx.ownerFirstName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = (if (isExpense) "-" else "+") + formatAmount(tx.amount),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isExpense) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    }
                }
            }
        }
    }
}

/**
 * Like `remember(keys) { calc() }`, but the work runs on a background thread so drawing and
 * scrolling stay smooth while years of transactions are summed. Keeps the last result while a
 * new one is worked out.
 */
@Composable
internal fun <T> rememberInBackground(initial: T, vararg keys: Any?, calc: () -> T): T {
    val state = produceState(initial, *keys) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { calc() }
    }
    return state.value
}
