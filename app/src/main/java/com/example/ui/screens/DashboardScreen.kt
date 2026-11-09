package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.Calendar
import android.app.DatePickerDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.example.ui.viewmodel.ViewFilter
import com.example.ui.viewmodel.NetFinancials
import com.example.ui.theme.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Log
import android.widget.Toast

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DashboardScreen(
    viewModel: ExpenseViewModel,
    onNavigateTransactions: () -> Unit,
    onOpenReserves: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val accountsState by viewModel.filteredAccounts.collectAsState()
    val transactionsState by viewModel.filteredTransactions.collectAsState()
    val financials3State by viewModel.netFinancialCalculator.collectAsState()
    val categoriesState by viewModel.categories.collectAsState(initial = emptyList())
    
    val activeFilter by viewModel.currentFilter.collectAsState()
    val activeScope by viewModel.scopeFilter.collectAsState()
    val isAutoSyncEnabled by viewModel.isSmsAutoSyncEnabled.collectAsState()

    // Dynamic SSO login profiles
    val myFirstName by viewModel.currentUserFirstName.collectAsState()
    val spouseFirstName by viewModel.spouseUserFirstName.collectAsState()
    val currentUserUid by viewModel.currentUserUid.collectAsState()
    val spouseUid by viewModel.spouseUserUid.collectAsState()
    
    val myNameStr = myFirstName ?: "You"
    val spouseNameStr = spouseFirstName ?: "Partner"
    
    var showProfileMenu by remember { mutableStateOf(false) }
    var editingTransaction by remember { mutableStateOf<Transaction?>(null) }
    
    // SMS runtime permission check
    val smsReadPermission = rememberPermissionState(permission = Manifest.permission.READ_SMS)
    val smsReceivePermission = rememberPermissionState(permission = Manifest.permission.RECEIVE_SMS)
    
    // Categorize spend types for doughnut display: current month only
    val categoryTotals = rememberInBackground(emptyMap<String, Double>(), transactionsState, activeScope) {
        val totals = mutableMapOf<String, Double>()
        val calCurrent = Calendar.getInstance()
        val currentYear = calCurrent.get(Calendar.YEAR)
        val currentMonth = calCurrent.get(Calendar.MONTH)
        val calTx = Calendar.getInstance()

        transactionsState
            .filter { tx ->
                if (tx.status != TransactionStatus.CONFIRMED) return@filter false
                if (tx.type != TransactionType.EXPENSE) return@filter false
                calTx.timeInMillis = tx.timestamp
                val txYear = calTx.get(Calendar.YEAR)
                val txMonth = calTx.get(Calendar.MONTH)
                txYear == currentYear && txMonth == currentMonth
            }
            .forEach { tx ->
                val current = totals[tx.category] ?: 0.0
                totals[tx.category] = current + tx.amount
            }
        totals
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("dashboard_screen")
            .padding(horizontal = 16.dp)
    ) {
        // Top Header
        item {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        val welcomeSubtitle = when (activeFilter) {
                            ViewFilter.Combined -> "Personal & Family"
                            ViewFilter.User1 -> "$myNameStr's View"
                            ViewFilter.User2 -> "$spouseNameStr's View"
                        }
                        Text(
                            text = welcomeSubtitle.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp),
                            fontWeight = FontWeight.Bold,
                            color = Slate400
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { showProfileMenu = !showProfileMenu }
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = "Expense Ledger",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp
                                ),
                                color = Slate800
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            val authUser = com.example.data.FirebaseService.getFirebaseAuth()?.currentUser
                            val useFallbackState by com.example.data.FirebaseService.useFallback.collectAsState()
                            
                            val badgeText: String
                            val badgeBgColor: Color
                            val badgeBorderColor: Color
                            val badgeTextColor: Color
                            val badgeIcon = if (currentUserUid == null) {
                                badgeText = "Sign In"
                                badgeBgColor = MaterialTheme.colorScheme.surfaceVariant
                                badgeBorderColor = MaterialTheme.colorScheme.outline
                                badgeTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                Icons.Default.Lock
                            } else if (useFallbackState || authUser == null) {
                                badgeText = "Local Demo"
                                badgeBgColor = Color(0xFFFFF3E0)
                                badgeBorderColor = Color(0xFFFFB74D)
                                badgeTextColor = Color(0xFFE65100)
                                Icons.Default.CloudQueue
                            } else {
                                val isGoogleSso = authUser.providerData.any { it.providerId == "google.com" }
                                if (isGoogleSso) {
                                    badgeText = "Verified SSO"
                                    badgeBgColor = Color(0xFFE8F5E9)
                                    badgeBorderColor = Color(0xFF2E7D32)
                                    badgeTextColor = Color(0xFF2E7D32)
                                    Icons.Default.CheckCircle
                                } else {
                                    badgeText = "Verified Email"
                                    badgeBgColor = Color(0xFFE3F2FD)
                                    badgeBorderColor = Color(0xFF1565C0)
                                    badgeTextColor = Color(0xFF1565C0)
                                    Icons.Default.CheckCircle
                                }
                            }

                            Surface(
                                shape = CircleShape,
                                color = badgeBgColor,
                                border = BorderStroke(1.dp, badgeBorderColor)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = badgeIcon,
                                        contentDescription = null,
                                        tint = badgeTextColor,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = badgeText,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = badgeTextColor
                                    )
                                }
                            }
                        }
                    }
                    
                    // Profile Initials Badge with interactive dropdown
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Indigo100, CircleShape)
                            .clickable { showProfileMenu = !showProfileMenu }
                            .testTag("profile_badge"),
                        contentAlignment = Alignment.Center
                    ) {
                        val initials = when (activeFilter) {
                            ViewFilter.Combined -> "${myNameStr.firstOrNull() ?: 'T'}&${spouseNameStr.firstOrNull() ?: 'S'}"
                            ViewFilter.User1 -> "${myNameStr.firstOrNull() ?: 'T'}"
                            ViewFilter.User2 -> "${spouseNameStr.firstOrNull() ?: 'S'}"
                        }
                        Text(
                            text = initials,
                            color = Indigo600,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // COLLAPSIBLE ACTIVE PROFILE CARD
                AnimatedVisibility(
                    visible = showProfileMenu,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Active Session Profile",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Indigo600
                                )
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = null,
                                    tint = Indigo600
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = Slate500,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Authorized User: $myNameStr",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Email,
                                    contentDescription = null,
                                    tint = Slate500,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = FirebaseService.getCurrentUserEmail(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Slate500
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = Slate500,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Connected Partner: $spouseNameStr",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Slate500
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Button(
                                onClick = {
                                    FirebaseService.logout()
                                    showProfileMenu = false
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ExitToApp,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Sign Out Session", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // USER PROFILE DYNAMIC FILTER TABROW
                val tabOptions = remember(myNameStr, spouseNameStr) {
                    listOf(
                        Triple(ViewFilter.User1, myNameStr, Icons.Default.Person),
                        Triple(ViewFilter.User2, spouseNameStr, Icons.Default.Favorite),
                        Triple(ViewFilter.Combined, "Shared", Icons.Default.Group)
                    )
                }
                
                var selectedTabIndex = when (activeFilter) {
                    ViewFilter.User1 -> 0
                    ViewFilter.User2 -> 1
                    ViewFilter.Combined -> 2
                }

                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = Slate100,
                    contentColor = Indigo600,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .padding(2.dp),
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                            color = Indigo600
                        )
                    }
                ) {
                    tabOptions.forEachIndexed { index, (filter, label, icon) ->
                        val isSelected = selectedTabIndex == index
                        Tab(
                            selected = isSelected,
                            onClick = { viewModel.setFilter(filter) },
                            modifier = Modifier.testTag("filter_tab_${label.lowercase()}"),
                            text = {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Indigo600 else Slate500
                                )
                            },
                            icon = {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isSelected) Indigo600 else Slate400
                                )
                            }
                        )
                    }
                }
            }
        }

        // SMS Permissions Helper card
        if (!smsReadPermission.status.isGranted || !smsReceivePermission.status.isGranted) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Auto-Sync SMS Parsing is Paused",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "To enable real-time reading and AI categorization of bank alerts, please grant SMS permissions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                smsReadPermission.launchPermissionRequest()
                                smsReceivePermission.launchPermissionRequest()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Enable Auto-Scan")
                        }
                    }
                }
            }
        } else {
            if (!isAutoSyncEnabled || transactionsState.isEmpty()) {
                item {
                    var syncingSms by remember { mutableStateOf(false) }
                    val coroutineScope = rememberCoroutineScope()
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "SMS Inbox Sync",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (transactionsState.isEmpty())
                                    "Let's get started! Scan your SMS inbox to automatically populate your transaction ledger and accounts."
                                else
                                    "Automatic inbox scanning is disabled to preserve battery and privacy. Click below to manually check for any new transaction alerts in your SMS inbox.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    syncingSms = true
                                    coroutineScope.launch {
                                        viewModel.runSmsInboxReconciliation(context)
                                        kotlinx.coroutines.delay(1200)
                                        syncingSms = false
                                        Toast.makeText(context, "SMS Inbox sync completed successfully!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = !syncingSms,
                                modifier = Modifier.testTag("manual_sms_sync_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                if (syncingSms) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Scanning Inbox...")
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Sync SMS Now")
                                }
                            }
                        }
                    }
                }
            }
        }

        // FREE TO SPEND: personal, until the next salary
        item {
            FreeToSpendCard(viewModel = viewModel, onOpenReserves = onOpenReserves)
        }

        // ACCOUNT SUMMARY METRICS: Net Liquid Assets & Liabilities
        item {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                val isCombined = activeFilter == ViewFilter.Combined
                val user1Id = currentUserUid ?: "user_1"
                val user2Id = viewModel.spouseUserUid.value ?: "user_2"
                
                val displayedNetWorth: Double
                val netWorthTitle: String
                
                if (isCombined) {
                    displayedNetWorth = financials3State.familyNetWorth
                    netWorthTitle = "JOINT FAMILY NET WORTH"
                } else {
                    val targetUid = if (activeFilter == ViewFilter.User1) user1Id else user2Id
                    val targetName = if (activeFilter == ViewFilter.User1) myNameStr else spouseNameStr
                    // Calculate only that specific user's assets minus liabilities
                    val userSpecificAccounts = accountsState
                    val assetsSum = userSpecificAccounts.filter { it.accountClass == "ASSET" }.sumOf { it.balance }
                    val liabilitiesSum = userSpecificAccounts.filter { it.accountClass == "LIABILITY" }.sumOf { Math.abs(it.balance) }
                    displayedNetWorth = assetsSum - liabilitiesSum
                    netWorthTitle = "${targetName.uppercase()}'S INDIVIDUAL NET WORTH"
                }

                // Beautiful, high-contrast, structured Net Worth card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = netWorthTitle,
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatAmount(displayedNetWorth),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("net_worth_val")
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Globally Syncing Real-Time Assets & Liabilities",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        )
                    }
                }

                if (isCombined) {
                    // --- COMBINED VIEW TAB ---
                    Text(
                        text = "Master Family Summaries",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    val totalBankAccountsBalance = accountsState.filter { 
                        it.mainCategory == "Bank Accounts" || it.mainCategory == "Cash" 
                    }.sumOf { it.balance }
                    
                    val totalCreditCardDues = accountsState.filter { 
                        it.mainCategory == "Credit Cards" 
                    }.sumOf { Math.abs(it.balance) }
                    
                    val jointAccounts = accountsState.filter { it.ownerUserId.equals("SHARED", ignoreCase = true) }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Total Bank Accounts (Assets)
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .height(115.dp)
                                .testTag("total_bank_accounts_summary_card"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Icon(Icons.Default.AccountBalance, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                Column {
                                    Text("Total Bank Balance", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                                    Text(formatAmount(totalBankAccountsBalance), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        
                        // Total Credit Cards (Liabilities)
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .height(115.dp)
                                .testTag("total_credit_cards_summary_card"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.2f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Icon(Icons.Default.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                                Column {
                                    Text("Total Credit Card Dues", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                                    Text(formatAmount(totalCreditCardDues), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    
                    if (jointAccounts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Joint & Shared Bank Accounts",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        jointAccounts.forEach { account ->
                            AccountNumberCard(account = account)
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No joint accounts found.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                } else {
                    // --- PERSONAL VIEW TAB ---
                    // 1. Net Liquid Funds balances (savings & cash - credit card dues)
                    val liquidFundsVal = financials3State.liquidFunds
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .testTag("net_liquid_funds_card"),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Net Liquid Funds",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Cash & Savings minus Credit Cards",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = formatAmount(liquidFundsVal),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (liquidFundsVal < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    // 2. Individual account balances (Bank accounts, cash, and credit cards)
                    Text(
                        text = "Your Accounts & Credit Card Balances",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    val personalAccounts = accountsState.filter {
                        it.mainCategory == "Bank Accounts" || it.mainCategory == "Cash" || it.mainCategory == "Credit Cards"
                    }
                    
                    if (personalAccounts.isEmpty()) {
                        Text(
                            text = "No personal accounts found registered under this profile.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        personalAccounts.forEach { account ->
                            AccountNumberCard(account = account)
                        }
                    }
                }
            }
        }

        // INVESTMENTS SECTION (Fixed Deposits & Mutual Funds displayed separately)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Investments & Multi-Funds",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Separate from monthly cash flow",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = formatAmount(financials3State.totalInvestments),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // FDs
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(8.dp).background(Color(0xFFAB47BC), CircleShape))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Fixed Deposits", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                text = formatAmount(financials3State.fixedDeposits),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 14.dp, top = 2.dp)
                            )
                        }
                        
                        Divider(modifier = Modifier.height(40.dp).width(1.dp).align(Alignment.CenterVertically))
                        
                        // MFs
                        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(8.dp).background(Color(0xFFFF7043), CircleShape))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Mutual Funds", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                text = formatAmount(financials3State.mutualFunds),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 14.dp, top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
        
        // LOAN LIABILITIES ONBOARDING / SECTION
        if (financials3State.loanOutstanding != 0.0) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Active Loan Liabilities", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("Outstanding balance representing debt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            text = formatAmount(financials3State.loanOutstanding),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // MULTI-TOGGLES FILTERS FOR GRAPH (Who Paid & Scope)
        item {
            Column(modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                // Scope Filter Selection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Expense Distribution",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // Scope chip options
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = activeScope == null,
                            onClick = { viewModel.setScopeFilter(null) },
                            label = { Text("Combined") },
                            modifier = Modifier.height(32.dp)
                        )
                        FilterChip(
                            selected = activeScope == TransactionScope.FAMILY,
                            onClick = { viewModel.setScopeFilter(TransactionScope.FAMILY) },
                            label = { Text("Family") },
                            modifier = Modifier.height(32.dp)
                        )
                        FilterChip(
                            selected = activeScope == TransactionScope.PERSONAL,
                            onClick = { viewModel.setScopeFilter(TransactionScope.PERSONAL) },
                            label = { Text("Personal") },
                            modifier = Modifier.height(32.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Visual dynamic canvas chart
                InteractiveDoughnutChart(categoryTotals = categoryTotals)
            }
        }

        // PENDING REVIEW TRANSACTIONS (Requires Manual Intervention)
        val pendingReviews = transactionsState.filter { 
            (it.status == TransactionStatus.PENDING_REVIEW || it.category.equals("Unknown", ignoreCase = true)) && !it.isIgnored 
        }
        if (pendingReviews.isNotEmpty()) {
            item {
                Text(
                    text = "Pending Review Queue (${pendingReviews.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            items(pendingReviews, key = { it.id }) { tx ->
                var showIgnoreConfirm by remember { mutableStateOf(false) }
                
                val matchingAcc = accountsState.find { it.id == tx.accountId }
                val isLocked = matchingAcc?.takeIf { it.accountType == "ASSET" || it.accountType == "LIABILITY" }?.lastReconciledDate?.let { tx.timestamp < it && !isSameDay(tx.timestamp, it) } ?: false

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .testTag("pending_card_${tx.id}"),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isLocked) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                         else MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(if (isLocked) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.Info,
                                    contentDescription = null,
                                    tint = if (isLocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tx.narration,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    color = if (isLocked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = formatAmount(tx.amount),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                    val catColor = if (isLocked) MaterialTheme.colorScheme.onSurfaceVariant else getCategoryColor(tx.category)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(catColor.copy(alpha = 0.12f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = tx.category.ifBlank { "Uncategorized" },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = catColor,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (isLocked) {
                                        Text(
                                            text = "• Locked",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                TransactionTagsRow(tx = tx)
                                if (!tx.originalSmsText.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = tx.originalSmsText,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                        
                        // Action buttons row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { showIgnoreConfirm = true },
                                enabled = !isLocked,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.testTag("ignore_review_btn_${tx.id}")
                            ) {
                                Text("Ignore", style = MaterialTheme.typography.labelSmall)
                            }
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            Button(
                                onClick = { editingTransaction = tx },
                                enabled = !isLocked,
                                modifier = Modifier.testTag("approve_review_btn_${tx.id}").height(32.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text("Approve", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                
                if (showIgnoreConfirm) {
                    AlertDialog(
                        onDismissRequest = { showIgnoreConfirm = false },
                        title = { Text("Confirm Ignore") },
                        text = { Text("Ignore transaction? This will permanently delete the transaction from your ledger and cannot be undone.") },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showIgnoreConfirm = false
                                    viewModel.deleteTransaction(tx.id)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Yes")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showIgnoreConfirm = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
        
        // RECENT TRANSACTIONS BLOCK
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Payments",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onNavigateTransactions) {
                    Text("View All")
                }
            }
        }

        val showTxs = transactionsState.filter { it.status == TransactionStatus.CONFIRMED }.take(5)
        if (showTxs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No transactions confirmated yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(showTxs, key = { it.id }) { tx ->
                val matchingAcc = accountsState.find { it.id == tx.accountId }
                val accountName = matchingAcc?.name ?: "Family Reserve"
                val isLocked = matchingAcc?.takeIf { it.accountType == "ASSET" || it.accountType == "LIABILITY" }?.lastReconciledDate?.let { tx.timestamp < it && !isSameDay(tx.timestamp, it) } ?: false
                TransactionRowItem(
                    tx = tx,
                    accountName = accountName,
                    isLocked = isLocked,
                    user1Name = myNameStr,
                    user2Name = spouseNameStr,
                    categories = categoriesState,
                    onNavigateDetail = {}
                )
            }
        }
        
        item {
            Spacer(modifier = Modifier.height(84.dp))
        }
    }

    // --- REAL TRANSACTION EDITING DIALOG (WITH SAFETY LOCK TESTS) ---
    editingTransaction?.let { editingTx ->
        var editAmountStr by remember(editingTx.id) { mutableStateOf(editingTx.amount.toString()) }
        var editNarration by remember(editingTx.id) { mutableStateOf(editingTx.narration) }
        var editTag by remember(editingTx.id) { mutableStateOf(editingTx.tag) }
        var showEditTagField by remember(editingTx.id) { mutableStateOf(editingTx.tag.isNotBlank()) }
        var editCategoryId by remember(editingTx.id) { mutableStateOf(editingTx.category) }
        var editAccountId by remember(editingTx.id) { mutableStateOf(editingTx.accountId) }
        var editTargetAccountId by remember(editingTx.id) { mutableStateOf(editingTx.targetAccountId) }
        var editDateMillis by remember(editingTx.id) { mutableStateOf(editingTx.timestamp) }
        var editPaidBy by remember(editingTx.id) { mutableStateOf(editingTx.paidByUserId) }
        var editScope by remember(editingTx.id) { mutableStateOf(editingTx.scope) }
        
        var editTxType by remember(editingTx.id) { mutableStateOf(editingTx.type) }
        var editErrorText by remember(editingTx.id) { mutableStateOf<String?>(null) }
        
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
            val targetAccObj = accountsState.find { it.id == editAccountId }
            val lastRecDate = targetAccObj?.takeIf { it.accountType == "ASSET" || it.accountType == "LIABILITY" }?.lastReconciledDate
            val isLocked = lastRecDate?.let { editDateMillis < it && !isSameDay(editDateMillis, it) } ?: false
            val isOriginalLocked = lastRecDate?.let { editingTx.timestamp < it && !isSameDay(editingTx.timestamp, it) } ?: false
            
            var isTargetLocked = false
            var isOriginalTargetLocked = false
            if (editTxType == TransactionType.TRANSFER && editTargetAccountId != null) {
                val targetAccObj2 = accountsState.find { it.id == editTargetAccountId }
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
            title = { Text("Edit & Approve Pending Transaction", fontWeight = FontWeight.Bold) },
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
                                tag = editTag.trim(),
                                status = TransactionStatus.CONFIRMED
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
                    Text("Save & Confirm")
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
                        Text("Ignore")
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
                                                if (editTargetAccountId == null && accountsState.size > 1) {
                                                    editTargetAccountId = accountsState.firstOrNull { it.id != editAccountId }?.id
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
                                    .testTag("dashboard_edit_add_tag_button")
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
                                            text = "Optional • Tag for trips, vacations, renovation, etc.",
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
                                        "User 1" -> editPaidBy == "User 1" || (currentUserUid != null && editPaidBy == currentUserUid)
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

                    item {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text("Source Account", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            var expanded by remember { mutableStateOf(false) }
                            val currentAcc = accountsState.find { it.id == editAccountId } ?: accountsState.firstOrNull()
                            val boxLabel = currentAcc?.name ?: "Select account"
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                    .clickable(enabled = !isTxLocked) { expanded = true }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(boxLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    accountsState.forEach { acc ->
                                        DropdownMenuItem(
                                            text = { Text(acc.name, fontSize = 13.sp) },
                                            onClick = {
                                                editAccountId = acc.id
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (editTxType == TransactionType.TRANSFER) {
                        item {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text("Destination Account (Transfer To)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.height(2.dp))
                                var destExpanded by remember { mutableStateOf(false) }
                                val currentDestAcc = accountsState.find { it.id == editTargetAccountId } ?: accountsState.firstOrNull { it.id != editAccountId }
                                val destBoxLabel = currentDestAcc?.name ?: "Select destination account"
                                
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                        .clickable(enabled = !isTxLocked) { destExpanded = true }
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(destBoxLabel, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                                    }
                                    DropdownMenu(
                                        expanded = destExpanded,
                                        onDismissRequest = { destExpanded = false }
                                    ) {
                                        accountsState.filter { it.id != editAccountId }.forEach { acc ->
                                            DropdownMenuItem(
                                                text = { Text(acc.name, fontSize = 13.sp) },
                                                onClick = {
                                                    editTargetAccountId = acc.id
                                                    destExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (editTxType != TransactionType.TRANSFER) {
                        item {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text("Select Category", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.height(4.dp))
                                val targetClass = if (editTxType == TransactionType.INCOME) "INCOME" else "EXPENSE"
                                val availableCats = remember(categoriesState, editTxType) {
                                    if (categoriesState.isNotEmpty()) {
                                        val filtered = categoriesState
                                            .filter {
                                                it.accountClass.equals(targetClass, ignoreCase = true) ||
                                                it.accountType.equals(targetClass, ignoreCase = true) ||
                                                (it.accountClass.isBlank() && it.accountType.isBlank())
                                            }
                                            .map { it.name.trim() }
                                            .filter { it.isNotBlank() && !it.equals("Transfer", ignoreCase = true) }
                                            .distinct()
                                        if (filtered.isNotEmpty()) filtered
                                        else categoriesState.map { it.name.trim() }.filter { it.isNotBlank() && !it.equals("Transfer", ignoreCase = true) }.distinct()
                                    } else {
                                        if (editTxType == TransactionType.INCOME) {
                                            listOf("Salary", "Investment Return", "Refund", "Gifts", "Others")
                                        } else {
                                            CategoryColors.keys.filter { it != "Transfer" }
                                        }
                                    }
                                }

                                availableCats.chunked(3).forEach { chunk ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        chunk.forEach { cat ->
                                            val isSelected = editCategoryId == cat
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(32.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                                    )
                                                    .border(
                                                        width = 1.dp,
                                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                                                else Color.Transparent,
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                    .clickable(enabled = !isTxLocked) { editCategoryId = cat },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = cat,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.padding(horizontal = 4.dp)
                                                )
                                            }
                                        }
                                        // fill empty slots in row
                                        if (chunk.size < 3) {
                                            repeat(3 - chunk.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
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
                                                modifier = Modifier.testTag("toggle_smart_rule_in_dashboard_edit")
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
            }
        )
    }
}

@Composable
fun AccountNumberCard(account: Account) {
    val last4 = remember(account.id, account.name, account.lastFourDigits) {
        if (account.lastFourDigits.isNotBlank()) {
            account.lastFourDigits
        } else {
            val digits = account.name.filter { it.isDigit() }
            if (digits.length >= 4) digits.takeLast(4) else "4812"
        }
    }
    
    val bankName = account.name.split("(").first().trim()
    
    val isAsset = account.accountClass == "ASSET"
    val containerBg = if (isAsset) Color(0xFFF5F6FF) else Color(0xFFFFF5F5)
    val borderStrokeColor = if (isAsset) Color(0xFFD6E4FF) else Color(0xFFFFD6D6)
    val textColor = if (isAsset) Color(0xFF1E293B) else Color(0xFF880E4F)
    val labelColor = if (isAsset) Color(0xFF4F46E5) else Color(0xFFC2185B)
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("account_number_card_${account.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        border = BorderStroke(1.dp, borderStrokeColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = CircleShape,
                    color = labelColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (account.mainCategory == "Credit Cards") Icons.Default.CreditCard else Icons.Default.AccountBalance,
                            contentDescription = null,
                            tint = labelColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                
                Column {
                    Text(
                        text = bankName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (account.mainCategory == "Credit Cards") "Card  •••• $last4" else "Savings  •••• $last4",
                        style = MaterialTheme.typography.labelMedium,
                        color = labelColor.copy(alpha = 0.8f)
                    )
                }
            }
            
            Column(horizontalAlignment = Alignment.End) {
                val displayBal = if (account.mainCategory == "Credit Cards") {
                    Math.abs(account.balance)
                } else {
                    account.balance
                }
                Text(
                    text = formatAmount(displayBal),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (displayBal < 0) MaterialTheme.colorScheme.error else textColor
                )
                Text(
                    text = if (account.mainCategory == "Credit Cards") "OUTSTANDING" else "BALANCE",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, letterSpacing = 0.5.sp),
                    fontWeight = FontWeight.Bold,
                    color = labelColor.copy(alpha = 0.6f)
                )
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
