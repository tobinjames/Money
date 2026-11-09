package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.FirebaseService
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.ExpenseViewModel

sealed class ScreenTab(val route: String, val title: String) {
    object Dashboard : ScreenTab("dashboard", "Dashboard")
    object Transactions : ScreenTab("transactions", "Ledger")
    object Analytics : ScreenTab("analytics", "Analytics")
    object Reminders : ScreenTab("reminders", "Schedules")
    object Settings : ScreenTab("settings", "Settings")
}

class MainActivity : ComponentActivity() {
    private val viewModel: ExpenseViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Firebase service (supports remote Sync or automatic dynamic local fallback)
        FirebaseService.initialize(applicationContext)

        // Reminder checks and alerts run in the background (daily, and now).
        com.example.reminders.ReminderWorker.schedule(applicationContext)

        // Load the user's preference for automatic SMS scanning
        viewModel.loadSmsAutoSyncSetting(applicationContext)
        viewModel.loadMoneyPrefs(applicationContext)

        // Request SMS permissions natively for maximum security on startup
        requestSmsPermissions()

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val context = androidx.compose.ui.platform.LocalContext.current
                LaunchedEffect(Unit) {
                    viewModel.toastEvent.collect { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
                val authState by com.example.data.FirebaseService.authStateVal.collectAsState()
                LaunchedEffect(authState) {
                    // Run the reminder check as soon as the household is open after sign-in.
                    if (authState == com.example.data.AuthState.AUTHORIZED) {
                        com.example.reminders.ReminderWorker.schedule(applicationContext)
                    }
                }
                var selectedTab by remember { mutableStateOf<ScreenTab>(ScreenTab.Dashboard) }
                var activeSubPage by remember { mutableStateOf<String?>(null) } // "categories", "rules" or "reserves"

                if (authState == com.example.data.AuthState.NEEDS_HOUSEHOLD) {
                    HouseholdSetupScreen()
                } else if (authState != com.example.data.AuthState.AUTHORIZED) {
                    AuthScreen(viewModel = viewModel)
                } else if (activeSubPage == "categories") {
                    CategoryManagementScreen(
                        viewModel = viewModel,
                        onBackClick = { activeSubPage = null }
                    )
                } else if (activeSubPage == "networth") {
                    NetWorthScreen(
                        viewModel = viewModel,
                        onBackClick = { activeSubPage = null }
                    )
                } else if (activeSubPage == "reserves") {
                    ReservesScreen(
                        viewModel = viewModel,
                        onBackClick = { activeSubPage = null }
                    )
                } else if (activeSubPage == "rules") {
                    SmartRulesScreen(
                        viewModel = viewModel,
                        onBackClick = { activeSubPage = null }
                    )
                } else {
                    Scaffold(
                        bottomBar = {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp,
                                modifier = Modifier.testTag("bottom_nav_bar")
                            ) {
                                val items = listOf(
                                    ScreenTab.Dashboard to Icons.Default.Home,
                                    ScreenTab.Transactions to Icons.Default.List,
                                    ScreenTab.Analytics to Icons.Default.Star,
                                    ScreenTab.Reminders to Icons.Default.Notifications,
                                    ScreenTab.Settings to Icons.Default.Settings
                                )
                                
                                items.forEach { (tab, icon) ->
                                    val selected = selectedTab == tab
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { selectedTab = tab },
                                        label = { Text(tab.title) },
                                        icon = {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = tab.title
                                            )
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        modifier = Modifier.testTag("nav_tab_${tab.route}")
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    ) { innerPadding ->
                        val contentModifier = Modifier.padding(innerPadding)
                        
                        Surface(modifier = Modifier.fillMaxSize()) {
                            when (selectedTab) {
                                ScreenTab.Dashboard -> {
                                    DashboardScreen(
                                        viewModel = viewModel,
                                        onNavigateTransactions = { selectedTab = ScreenTab.Transactions },
                                        onOpenReserves = { activeSubPage = "reserves" },
                                        onOpenNetWorth = { activeSubPage = "networth" },
                                        modifier = contentModifier
                                    )
                                }
                                ScreenTab.Transactions -> {
                                    TransactionsScreen(
                                        viewModel = viewModel,
                                        modifier = contentModifier
                                    )
                                }
                                ScreenTab.Analytics -> {
                                    AnalyticsScreen(
                                        viewModel = viewModel,
                                        modifier = contentModifier
                                    )
                                }
                                ScreenTab.Reminders -> {
                                    RemindersScreen(
                                        viewModel = viewModel,
                                        modifier = contentModifier
                                    )
                                }
                                ScreenTab.Settings -> {
                                    SettingsScreen(
                                        viewModel = viewModel,
                                        onNavigateCategoryManagement = { activeSubPage = "categories" },
                                        onNavigateSmartRules = { activeSubPage = "rules" },
                                        onNavigateReserves = { activeSubPage = "reserves" },
                                        modifier = contentModifier
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestSmsPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(missing.toTypedArray(), 101)
            }
        } else {
            // Permissions are already granted, trigger background inbox reconciliation if enabled
            if (viewModel.isSmsAutoSyncEnabled.value) {
                viewModel.runSmsInboxReconciliation(applicationContext)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                if (viewModel.isSmsAutoSyncEnabled.value) {
                    Toast.makeText(this, "SMS Integration Sync Enabled", Toast.LENGTH_SHORT).show()
                    viewModel.runSmsInboxReconciliation(applicationContext)
                } else {
                    Toast.makeText(this, "SMS Permissions Granted. Manual sync available.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Manual entry mode active. Grant permissions to sync SMS manually.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
