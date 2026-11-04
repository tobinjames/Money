package com.example.ui.screens

import android.app.DatePickerDialog
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
    onNavigateFamilySettlement: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val accounts by viewModel.accounts.collectAsState()
    val senders by viewModel.whitelistedSenders.collectAsState()
    val privacyKeywords by viewModel.privacyKeywords.collectAsState()
    
    val user1Name by viewModel.currentUserFirstName.collectAsState()
    val user2Name by viewModel.spouseUserFirstName.collectAsState()
    val partnerAName = user1Name ?: "User 1"
    val partnerBName = user2Name ?: "User 2"
    
    val isFallback by FirebaseService.useFallback.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var showAddSender by remember { mutableStateOf(false) }
    var showAddKeyword by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen")
            .padding(horizontal = 16.dp)
    ) {
        // --- 1. PREMIUM HEADER BANNER WITH CUSTOM GENERATED HERO IMAGE ---
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    // Hero Image
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = R.drawable.img_settings_banner_1782846109983),
                        contentDescription = "Control Center Banner",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                    
                    // Dark Overlay for Contrast
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.8f)
                                    )
                                )
                            )
                    )
                    
                    // Content Layer
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(20.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "FAMILY CENTRAL HQ",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontSize = 9.sp,
                                letterSpacing = 1.sp
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        Text(
                            text = "Control Center",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        
                        Text(
                            text = "Adjust security filters, ledgers, and family automation rules",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        // --- 2. FAMILY HOUSEHOLD PROFILE HUB ---
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "INTEGRATED HOUSEHOLD",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                    
                    Spacer(modifier = Modifier.height(14.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // User Avatar A
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = partnerAName.take(1).uppercase(),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        
                        // Sync Icon representation
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Synchronized Link",
                            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )

                        // User Avatar B
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = partnerBName.take(1).uppercase(),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        // Workspace Tag
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "nestwise-5c65e",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = "Active Ledger",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // --- 3. SYSTEM AUTOMATIONS BLOCK ---
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
                    Text(
                        text = "CORE SYNCHRONIZATION",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    // Cloud Sync Row (Non-interactive display)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFE8F5E9)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Firestore Sync Active",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Real-time updates secured",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 8.dp))
                    
                    // SMS Auto Scraping Row
                    val isAutoSyncEnabled by viewModel.isSmsAutoSyncEnabled.collectAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isAutoSyncEnabled) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                tint = if (isAutoSyncEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Automatic SMS Scans",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                if (isAutoSyncEnabled) "Enabled (Automated background parsing)" else "Paused (Manual dashboard sync only)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isAutoSyncEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Switch(
                            checked = isAutoSyncEnabled,
                            onCheckedChange = { isChecked ->
                                viewModel.setSmsAutoSyncSetting(context, isChecked)
                                if (isChecked) {
                                    viewModel.triggerToast("Automatic SMS scanning enabled.")
                                    viewModel.runSmsInboxReconciliation(context)
                                } else {
                                    viewModel.triggerToast("Automatic scanning paused. Manual sync option active on Dashboard.")
                                }
                            },
                            modifier = Modifier.testTag("sms_auto_sync_toggle")
                        )
                    }
                }
            }
        }

        // --- 4. PREFERENCE STYLE DIRECT NAVIGATION REGISTRY ---
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
                    Text(
                        text = "FINANCIAL REGISTRY & SETTLEMENTS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    // Preference Row 1: Chart of Accounts
                    SettingsPreferenceRow(
                        icon = Icons.Default.AccountBalance,
                        iconContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = "Chart of Accounts",
                        subtitle = "Configure ledgers (assets, liabilities, expenses)",
                        onClick = onNavigateCategoryManagement,
                        modifier = Modifier.testTag("navigate_categories_button")
                    )
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    // Preference Row 2: SMS Smart Override Rules
                    SettingsPreferenceRow(
                        icon = Icons.Default.Rule,
                        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        iconTint = MaterialTheme.colorScheme.secondary,
                        title = "SMS Smart Override Rules",
                        subtitle = "Configure automated merchant keyword mappings",
                        onClick = onNavigateSmartRules,
                        modifier = Modifier.testTag("navigate_smart_rules_button")
                    )
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    // Preference Row 3: Monthly Family Settlement
                    SettingsPreferenceRow(
                        icon = Icons.Default.Refresh,
                        iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        title = "Monthly Family Settlement",
                        subtitle = "Calculate dual-user balance sheets & settlements",
                        onClick = onNavigateFamilySettlement,
                        modifier = Modifier.testTag("navigate_family_settlement_button")
                    )
                }
            }
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
                                text = "SMS GATEKEEPER WHITELIST",
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
                                text = "PRIVACY TERM FILTER (BLOCKLIST)",
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

        // --- 8. DANGER MAINTENANCE ZONE ---
        item {
            var showClearConfirm by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.05f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "DANGER ZONE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Allows full resetting of transaction logs. Permanent, irreversible action.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    
                    Button(
                        onClick = { showClearConfirm = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Remove All Transactions", style = MaterialTheme.typography.labelMedium)
                    }

                    if (showClearConfirm) {
                        AlertDialog(
                            onDismissRequest = { showClearConfirm = false },
                            title = { Text("Danger: Clear All Records", fontWeight = FontWeight.Bold) },
                            text = { Text("This permanently deletes all synchronization history inside this household workspace. Proceed with caution.") },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showClearConfirm = false
                                        viewModel.deleteAllTransactions { success, errMsg ->
                                            if (success) {
                                                viewModel.triggerToast("Success: Deleted all transactions.")
                                            } else {
                                                viewModel.triggerToast("Clear failed: $errMsg")
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Confirm Delete")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showClearConfirm = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.app_logo_emblem_1786874063889),
                    contentDescription = "Family Expense Tracker Logo",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Family Expense Tracker",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                Text(
                    text = "Version 2.4.0 (Secure Build)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(100.dp))
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
