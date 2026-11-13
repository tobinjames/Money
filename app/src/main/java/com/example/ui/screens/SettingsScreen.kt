package com.example.ui.screens

import com.example.sms.GeminiStatus
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.BuildConfig
import com.example.domain.CsvExport
import com.example.reminders.AlertPrefs
import com.example.reminders.ReminderWorker
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.example.R
import com.example.data.*
import com.example.ui.viewmodel.ExpenseViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SettingsScreen(
    viewModel: ExpenseViewModel,
    onNavigateCategoryManagement: () -> Unit,
    onNavigateSmartRules: () -> Unit,
    onNavigateReserves: () -> Unit,
    onNavigateRecurring: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val senders by viewModel.whitelistedSenders.collectAsState()
    val privacyKeywords by viewModel.privacyKeywords.collectAsState()

    var showAddSender by remember { mutableStateOf(false) }
    var showAddKeyword by remember { mutableStateOf(false) }

    // Sub-pages inside Settings: "main", "sms", "notifications", "advanced".
    var page by rememberSaveable { mutableStateOf("main") }
    BackHandler(enabled = page != "main") { page = "main" }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen")
            .padding(horizontal = 16.dp)
    ) {
        when (page) {
            "main" -> {
                item { SettingsTitle("Settings", null) }

                item { SectionLabel("Household") }
                item { HouseholdInviteCard() }

                item { SectionLabel("SMS & automation") }
                item {
                    SettingsCard {
                        val isAutoSyncEnabled by viewModel.isSmsAutoSyncEnabled.collectAsState()
                        ToggleRow(
                            icon = Icons.Default.Sync,
                            title = "Read bank SMS automatically",
                            subtitle = if (isAutoSyncEnabled) "New bank SMS are added as they arrive" else "Paused; use Sync on the Dashboard",
                            checked = isAutoSyncEnabled,
                            onCheckedChange = { isChecked ->
                                viewModel.setSmsAutoSyncSetting(context, isChecked)
                                if (isChecked) viewModel.runSmsInboxReconciliation(context)
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        SettingsPreferenceRow(
                            icon = Icons.Default.Message,
                            iconContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = "Bank senders & privacy words",
                            subtitle = "${senders.size} senders read · ${privacyKeywords.size} words skipped",
                            onClick = { page = "sms" }
                        )
                        Spacer(Modifier.height(8.dp))
                        SettingsPreferenceRow(
                            icon = Icons.Default.Rule,
                            iconContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            iconTint = MaterialTheme.colorScheme.secondary,
                            title = "Merchant rules",
                            subtitle = "Always file a merchant under a category",
                            onClick = onNavigateSmartRules
                        )
                    }
                }

                item { SectionLabel("Money setup") }
                item {
                    SettingsCard {
                        SettingsPreferenceRow(
                            icon = Icons.Default.AccountBalance,
                            iconContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = "Accounts & categories",
                            subtitle = "Banks, cards, loans, income and expense categories",
                            onClick = onNavigateCategoryManagement
                        )
                        SettingsPreferenceRow(
                            icon = Icons.Default.Savings,
                            iconContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = "Reserves",
                            subtitle = "Money set aside for what's coming, shared with your partner",
                            onClick = onNavigateReserves
                        )
                        SettingsPreferenceRow(
                            icon = Icons.Default.Refresh,
                            iconContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = "Monthly payments",
                            subtitle = "Subscriptions and other payments that repeat, and what they cost a year",
                            onClick = onNavigateRecurring
                        )
                    }
                }
                item { FreeToSpendSettingsCard(viewModel) }

                item { SectionLabel("Notifications") }
                item {
                    SettingsCard {
                        SettingsPreferenceRow(
                            icon = Icons.Default.Notifications,
                            iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            title = "Alerts",
                            subtitle = "Bills, partner backup, budgets, reconcile · quiet ${hourLabel(AlertPrefs.quietFrom(context))}–${hourLabel(AlertPrefs.quietUntil(context))}",
                            onClick = { page = "notifications" }
                        )
                    }
                }

                item { SectionLabel("Permissions & health") }
                item { HealthCard() }

                item { SectionLabel("Advanced") }
                item {
                    SettingsCard {
                        SettingsPreferenceRow(
                            icon = Icons.Default.Build,
                            iconContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                            title = "Advanced",
                            subtitle = "Test an SMS, export to CSV",
                            onClick = { page = "advanced" }
                        )
                    }
                }

                item { SectionLabel("Account") }
                item { AccountCard() }
                item { Spacer(modifier = Modifier.height(100.dp)) }
            }

            "sms" -> {
                item { SettingsTitle("Bank senders & privacy words") { page = "main" } }
                item {
                    Text(
                        text = "Only SMS from these senders are read (matched on the part after the dash, e.g. AX-HDFCBK → HDFCBK). Messages containing a privacy word, like OTPs, are never read.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
        // --- 5. GATEKEEPER SMS WHITELIST ---
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "BANK SMS SENDERS",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.outline,
                                letterSpacing = 1.sp
                            )
                            Text(
                                "Approve specific shortcode alpha-keys",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showAddSender = true }) {
                            Icon(Icons.Default.AddCircle, contentDescription = "Add Whitelist", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    if (senders.isEmpty()) {
                        Text(
                            "No specific shortcodes configured yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    } else {
                        senders.forEach { snd ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(snd.senderId, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                        Text(snd.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButton(onClick = { viewModel.deleteWhitelistedSender(snd.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }

        // --- 6. SECURITY PRIVACY KEYWORD BLOCKLIST ---
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("privacy_settings_card"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "PRIVACY WORDS (SMS SKIPPED)",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.outline,
                                letterSpacing = 1.sp
                            )
                            Text(
                                "Silently drop sensitive message patterns",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { showAddKeyword = true },
                            modifier = Modifier.testTag("add_privacy_keyword_icon")
                        ) {
                            Icon(Icons.Default.AddCircle, contentDescription = "Add Keyword", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    if (privacyKeywords.isEmpty()) {
                        Text(
                            "No blocked privacy terms configured yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    } else {
                        privacyKeywords.forEach { kw ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .testTag("privacy_keyword_row_${kw.id}"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = kw.keyword, 
                                        fontWeight = FontWeight.Bold, 
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.testTag("keyword_text_${kw.id}")
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.deletePrivacyKeyword(kw.id) },
                                    modifier = Modifier.testTag("delete_keyword_btn_${kw.id}")
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }

                item { Spacer(modifier = Modifier.height(100.dp)) }
            }

            "notifications" -> {
                item { SettingsTitle("Alerts") { page = "main" } }
                item { AlertSettingsCard() }
                item { Spacer(modifier = Modifier.height(100.dp)) }
            }

            "advanced" -> {
                item { SettingsTitle("Advanced") { page = "main" } }
        // --- 7.5. SMS TESTING & SIMULATION PLAYGROUND ---
        item {
            val testSmsResult by viewModel.testSmsResult.collectAsState()
            val isTestingSms by viewModel.isTestingSms.collectAsState()
            var smsInputText by remember { mutableStateOf("") }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("sms_testing_card"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "SMS TESTING & SIMULATION",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Paste a raw bank transaction SMS text here to test the Gemini parsing accuracy and verify how the double-entry system will account for the transaction.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = smsInputText,
                        onValueChange = { smsInputText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sms_test_input"),
                        label = { Text("Raw SMS Message Text") },
                        placeholder = { Text("e.g., Your A/c xx1234 has been debited with INR 1,500.00 at Starbucks.") },
                        maxLines = 4,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                if (smsInputText.isNotBlank()) {
                                    viewModel.testParseSms(smsInputText)
                                }
                            },
                            enabled = smsInputText.isNotBlank() && !isTestingSms,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sms_test_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isTestingSms) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Parsing...")
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Test Parsing")
                            }
                        }

                        if (testSmsResult != null) {
                            OutlinedButton(
                                onClick = {
                                    smsInputText = ""
                                    viewModel.clearTestResult()
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("sms_test_clear")
                            ) {
                                Text("Clear")
                            }
                        }
                    }

                    testSmsResult?.let { result ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(12.dp))

                        if (!result.success) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = result.error ?: "Failed to parse message.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        } else {
                            Text(
                                text = "PARSING RESULTS & SUMMARY",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            result.parsed?.let { parsed ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = "Gemini AI Parsing Summary",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Type:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(parsed.transactionType, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = if (parsed.transactionType == "DEBIT") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Amount:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("₹${parsed.amount}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Payee / Vendor:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(parsed.vendor, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Suggested Category:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(parsed.suggestedCategory, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Target Account:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(parsed.assignedAccount ?: "Unknown", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            result.accountingEntry?.let { entry ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
                                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)), RoundedCornerShape(12.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = "Double-Entry Accounting Record",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Debit Account (Value In):", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(entry.debitAccountId, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Credit Account (Value Out):", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(entry.creditAccountId, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Record Status:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (entry.status == "CONFIRMED") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = entry.status,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (entry.status == "CONFIRMED") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                                fontSize = 9.sp
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Narration:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(entry.narration, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    }
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Divider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    
                                    Text(
                                        text = if (entry.status == "CONFIRMED") {
                                            "✓ This transaction contains familiar vendor history & categories and will be Auto-Confirmed into balances."
                                        } else {
                                            "⚠ This contains uncertain or brand new vendor activity and will be flagged for your Manual Review under 'Pending Reviews' without affecting your balances until confirmed."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (entry.status == "CONFIRMED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

                item { ExportCard() }
                item { Spacer(modifier = Modifier.height(100.dp)) }
            }
        }
    }

    // --- POPUP DIALOGS ---

    // 1. Add Whitelisted Shortcode
    if (showAddSender) {
        var senderId by remember { mutableStateOf("") }
        var senderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddSender = false },
            title = { Text("Whitelist Banking Sender ID") },
            confirmButton = {
                Button(
                    onClick = {
                        if (senderId.isNotEmpty()) {
                            viewModel.addWhitelistedSender(senderId, senderName)
                            showAddSender = false
                            senderId = ""
                            senderName = ""
                        }
                    },
                    enabled = senderId.isNotEmpty()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddSender = false }) {
                    Text("Cancel")
                }
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = senderId,
                        onValueChange = { senderId = it },
                        label = { Text("Sender Alpha Key") },
                        placeholder = { Text("e.g. HDFCBK, SBICRD") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = senderName,
                        onValueChange = { senderName = it },
                        label = { Text("Bank / Sender Label") },
                        placeholder = { Text("e.g. HDFC Bank Credit") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        singleLine = true
                    )
                }
            }
        )
    }

    // 2. Add Privacy Keyword Dialog
    if (showAddKeyword) {
        var keywordInput by remember { mutableStateOf("") }
        
        AlertDialog(
            onDismissRequest = { showAddKeyword = false },
            title = { Text("Blocked Privacy Keyword") },
            confirmButton = {
                Button(
                    onClick = {
                        if (keywordInput.trim().isNotEmpty()) {
                            viewModel.addPrivacyKeyword(keywordInput.trim())
                            showAddKeyword = false
                            keywordInput = ""
                        }
                    },
                    enabled = keywordInput.trim().isNotEmpty(),
                    modifier = Modifier.testTag("confirm_add_keyword_btn")
                ) {
                    Text("Add Keyword")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddKeyword = false }) {
                    Text("Cancel")
                }
            },
            text = {
                Column {
                    Text("Enter a security-sensitive term (e.g., OTP, Verification, Password, Auth). Incoming messages containing this keyword will be silently dropped.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    OutlinedTextField(
                        value = keywordInput,
                        onValueChange = { keywordInput = it },
                        label = { Text("Privacy/Security Keyword") },
                        placeholder = { Text("e.g. OTP") },
                        modifier = Modifier.fillMaxWidth().testTag("add_keyword_text_field"),
                        singleLine = true
                    )
                }
            }
        )
    }
}


private fun hourLabel(hour: Int): String = when {
    hour == 0 -> "12 AM"
    hour < 12 -> "$hour AM"
    hour == 12 -> "12 PM"
    else -> "${hour - 12} PM"
}

@Composable
private fun SettingsTitle(title: String, onBack: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp), content = content)
    }
}

@Composable
private fun ToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Free to spend settings for this phone: they are personal, like the figure. */
@Composable
private fun FreeToSpendSettingsCard(viewModel: ExpenseViewModel) {
    val context = LocalContext.current
    val salaryDay by viewModel.salaryDay.collectAsState()
    val threshold by viewModel.lowBalanceThreshold.collectAsState()
    var lowAlerts by remember { mutableStateOf(AlertPrefs.lowBalanceAlerts(context)) }
    var thresholdText by remember(threshold) { mutableStateOf("%.0f".format(threshold)) }
    var dayMenu by remember { mutableStateOf(false) }

    SettingsCard {
        Text("Free to spend", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        Text(
            "Bills due before your next salary are taken off what you can spend. Without a salary day, the next 30 days are used.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Box(Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { dayMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (salaryDay in 1..31) "Salary arrives on day $salaryDay of the month" else "Set your salary day")
            }
            DropdownMenu(expanded = dayMenu, onDismissRequest = { dayMenu = false }) {
                DropdownMenuItem(text = { Text("Not set (next 30 days)") }, onClick = { viewModel.setSalaryDay(context, 0); dayMenu = false })
                (1..31).forEach { d ->
                    DropdownMenuItem(text = { Text("Day $d") }, onClick = { viewModel.setSalaryDay(context, d); dayMenu = false })
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        ToggleRow(null, "Low balance alert", "When Free to spend drops below the amount below", lowAlerts) {
            lowAlerts = it; AlertPrefs.setLowBalanceAlerts(context, it)
        }
        if (lowAlerts) {
            OutlinedTextField(
                value = thresholdText,
                onValueChange = { v ->
                    thresholdText = v.filter { it.isDigit() }
                    thresholdText.toDoubleOrNull()?.let { viewModel.setLowBalanceThreshold(context, it) }
                },
                label = { Text("Alert below (₹)") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            )
        }
    }
}

/** Alert preferences for this phone; each partner sets their own. */
@Composable
private fun AlertSettingsCard() {
    val context = LocalContext.current
    var reminders by remember { mutableStateOf(AlertPrefs.reminderAlerts(context)) }
    var partner by remember { mutableStateOf(AlertPrefs.partnerAlerts(context)) }
    var budgets by remember { mutableStateOf(AlertPrefs.budgetAlerts(context)) }
    var reconcile by remember { mutableStateOf(AlertPrefs.reconcileAlerts(context)) }
    var summary by remember { mutableStateOf(AlertPrefs.monthlySummary(context)) }
    var quietFrom by remember { mutableStateOf(AlertPrefs.quietFrom(context)) }
    var quietUntil by remember { mutableStateOf(AlertPrefs.quietUntil(context)) }

    SettingsCard {
        ToggleRow(null, "Bill & payment reminders", "15, 10, 7 and 5 days before, then daily", reminders) {
            reminders = it; AlertPrefs.setReminderAlerts(context, it)
        }
        ToggleRow(null, "Partner backup alerts", "Your partner's bills, if still unpaid 3 days before", partner) {
            partner = it; AlertPrefs.setPartnerAlerts(context, it)
        }
        ToggleRow(null, "Budget alerts", "At 80% and when a budget goes over", budgets) {
            budgets = it; AlertPrefs.setBudgetAlerts(context, it)
        }
        ToggleRow(null, "Reconcile reminders", "Friday evening and weekends: banks and cards after 30 days, FDs, funds and loans after 60", reconcile) {
            reconcile = it; AlertPrefs.setReconcileAlerts(context, it)
        }
        ToggleRow(null, "Monthly summary", "At the start of each month: what you spent, on what, and how it compares", summary) {
            summary = it; AlertPrefs.setMonthlySummary(context, it)
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text("Quiet hours", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        Text(
            "Alerts due in this window arrive when it ends. Pick the same hour twice to turn it off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HourPicker("From", quietFrom, Modifier.weight(1f)) { quietFrom = it; AlertPrefs.setQuietHours(context, it, quietUntil) }
            HourPicker("Until", quietUntil, Modifier.weight(1f)) { quietUntil = it; AlertPrefs.setQuietHours(context, quietFrom, it) }
        }
    }
}

@Composable
private fun HourPicker(label: String, hour: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label ${hourLabel(hour)}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (0..23).forEach { h ->
                DropdownMenuItem(text = { Text(hourLabel(h)) }, onClick = { onPick(h); open = false })
            }
        }
    }
}

/** Live checks for what the background features need, each with a way to fix it. */
@Composable
private fun HealthCard() {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var refresh by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun openAppSettings() = context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Android stops asking after repeated denials; send the user to the app's settings instead.
        if (result.values.any { !it }) openAppSettings()
        refresh++
    }

    // Read again whenever the screen resumes (e.g. back from system settings).
    val smsOk = remember(refresh) { granted(Manifest.permission.READ_SMS) && granted(Manifest.permission.RECEIVE_SMS) }
    val notificationsOk = remember(refresh) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val batteryOk = remember(refresh) {
        (context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    }
    val lastCheck = remember(refresh) { AlertPrefs.lastCheck(context) }

    SettingsCard {
        HealthRow(
            "Read SMS", smsOk,
            if (smsOk) "Allowed" else "Needed to add bank transactions automatically"
        ) { permissionLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) }
        HealthRow(
            "Notifications", notificationsOk,
            if (notificationsOk) "Allowed" else "Needed for bill and budget alerts"
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            else openAppSettings()
        }
        GeminiHealthRow()
        HealthRow(
            "Background running", batteryOk,
            if (batteryOk) "Not restricted" else "Battery saving may delay alerts and SMS; set the app to Unrestricted"
        ) {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Last background check", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (lastCheck == 0L) "Not run yet"
                    else SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(lastCheck)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = {
                ReminderWorker.schedule(context)
                android.widget.Toast.makeText(context, "Checking now", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("Check now") }
        }
    }
}

@Composable
private fun GeminiHealthRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gemini by GeminiStatus.state.collectAsState()
    LaunchedEffect(Unit) { GeminiStatus.load(context) }
    HealthRow(
        "Gemini (reads your SMS)",
        ok = !gemini.unavailable && gemini.waiting == 0,
        detail = geminiStatusText(gemini),
        fixLabel = if (gemini.retrying) "Trying…" else "Try again",
        canFix = gemini.waiting > 0 && !gemini.retrying
    ) { retryGemini(context, scope) }
    AppCheckTokenRow()
}

/**
 * This phone's App Check debug token, with a Copy button. Once App Check is enforced, Gemini only answers phones whose token
 * is added in Firebase console > App Check > Apps > Manage debug tokens (no adb needed).
 */
@Composable
private fun AppCheckTokenRow() {
    val context = LocalContext.current
    var token by remember { mutableStateOf(com.example.data.AppCheckSetup.debugToken(context)) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            Text("App Check token (this phone)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                (token ?: "Not made yet. Tap Get.") +
                    "\nOnly needed if App Check is enforced: add it in Firebase > App Check > Apps > Manage debug tokens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = {
            com.example.data.AppCheckSetup.fetchDebugToken(context) { t ->
                token = t
                if (t == null) {
                    android.widget.Toast.makeText(context, "No token yet. Try again in a moment.", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("App Check debug token", t))
                    android.widget.Toast.makeText(context, "Token copied", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }) { Text(if (token == null) "Get" else "Copy") }
    }
}

@Composable
private fun HealthRow(
    title: String, ok: Boolean, detail: String, fixLabel: String = "Fix", canFix: Boolean = true, onFix: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = if (ok) "OK" else "Needs attention",
            tint = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok && (canFix || fixLabel != "Fix")) TextButton(onClick = onFix, enabled = canFix) { Text(fixLabel) }
    }
}

/** Shares every transaction as a CSV file (opens the share sheet: Drive, email, etc.). */
@Composable
private fun ExportCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SettingsCard {
        Text("Export transactions", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        Text(
            "A CSV file of every transaction, for a spreadsheet or a backup.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { scope.launch {
                try {
                    // Straight from the household data, which is always loaded while signed in.
                    val csv = CsvExport.transactions(
                        FirebaseService.getTransactions().first(),
                        FirebaseService.getCategories().first()
                    )
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    val file = File(dir, "transactions-$stamp.csv").apply { writeText(csv) }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/csv")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Export transactions").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Export CSV")
        }
    }
}

@Composable
private fun AccountCard() {
    val name = FirebaseService.getCurrentUserDisplayName()
    val email = FirebaseService.getCurrentUserEmail()
    val context = LocalContext.current
    var lock by remember { mutableStateOf(AlertPrefs.appLock(context)) }
    SettingsCard {
        ToggleRow(Icons.Default.Lock, "App lock", "Ask for your fingerprint, face or phone PIN when the app opens", lock) { on ->
            if (on && !com.example.MainActivity.canLock(context)) {
                android.widget.Toast.makeText(context, "Set a screen lock on this phone first.", android.widget.Toast.LENGTH_LONG).show()
            } else {
                lock = on; AlertPrefs.setAppLock(context, on)
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(name.ifBlank { "Signed in" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
        Text(email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { FirebaseService.logout() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Sign out")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Family Expense Tracker · version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

// --- PREFERENCE ROW AUXILIARY COMPOSABLE ---
@Composable
fun SettingsPreferenceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconContainerColor: Color,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconContainerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
