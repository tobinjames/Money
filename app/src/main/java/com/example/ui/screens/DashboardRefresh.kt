package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import com.example.ui.viewmodel.ExpenseViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The dashboard, with pull down to check the SMS inbox for bank messages that were missed
 * (works whether or not automatic SMS sync is on).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: ExpenseViewModel,
    onNavigateTransactions: () -> Unit,
    onOpenReserves: () -> Unit,
    onOpenNetWorth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(context, "Allow SMS access first, then pull down to sync.", Toast.LENGTH_SHORT).show()
            } else if (!refreshing) {
                refreshing = true
                scope.launch {
                    try {
                        val scan = viewModel.runSmsInboxReconciliation(context.applicationContext)
                        // Kept visible for a moment even when there was nothing new to read.
                        delay(600)
                        scan.join()
                        Toast.makeText(context, "SMS inbox checked", Toast.LENGTH_SHORT).show()
                    } finally {
                        refreshing = false
                    }
                }
            }
        },
        modifier = modifier.testTag("dashboard_pull_to_refresh")
    ) {
        DashboardContent(
            viewModel = viewModel,
            onNavigateTransactions = onNavigateTransactions,
            onOpenReserves = onOpenReserves,
            onOpenNetWorth = onOpenNetWorth
        )
    }
}
