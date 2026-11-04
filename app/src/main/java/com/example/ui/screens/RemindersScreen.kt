package com.example.ui.screens

import android.app.DatePickerDialog
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
import androidx.compose.ui.graphics.Color
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
import com.example.data.Reminder
import com.example.ui.viewmodel.ExpenseViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RemindersScreen(
    viewModel: ExpenseViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val reminders by viewModel.reminders.collectAsState()
    val accounts by viewModel.accounts.collectAsState(initial = emptyList())
    
    val (completedReminders, activeReminders) = remember(reminders) {
        reminders.partition { it.isPaid }
    }
    
    var showCompleted by remember { mutableStateOf(false) }
    var showAddReminderDialog by remember { mutableStateOf(false) }
    
    // Reminder form states
    var title by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var reminderType by remember { mutableStateOf("Credit Card") } // "Credit Card", "SIP", "Others"
    var isRecurring by remember { mutableStateOf(false) }
    var dueDateMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var selectedAccountId by remember { mutableStateOf<String?>(null) }
    val calendar = remember { Calendar.getInstance() }

    val otherSuggestions = listOf("Rent", "Broadband", "Electricity", "Water", "Gas", "WiFi", "Mobile", "Insurance", "Subscription", "School Fee")

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { 
                    title = ""
                    amountStr = ""
                    reminderType = "Credit Card"
                    isRecurring = false
                    dueDateMillis = System.currentTimeMillis()
                    selectedAccountId = null
                    showAddReminderDialog = true 
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_reminder_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Schedule payment reminder")
            }
        },
        modifier = modifier
            .fillMaxSize()
            .testTag("reminders_screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Upcoming Schedules",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            
            Text(
                text = "Keep track of credit card statements, SIPs, rent, broadband & utilities",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            val creditCards = remember(accounts) {
                accounts.filter { 
                    it.accountType == "LIABILITY" || 
                    it.type == AccountType.CREDIT_CARD || 
                    it.customMainCategory.equals("Credit Cards", ignoreCase = true) ||
                    it.mainCategory.equals("Credit Cards", ignoreCase = true)
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // --- CREDIT CARD BILLING CYCLE & DUE TRACKER ---
                if (creditCards.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.CreditCard,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Credit Card Cycles & Dues",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = "${creditCards.size} Cards",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(10.dp))
                                
                                creditCards.forEach { card ->
                                    val calNow = Calendar.getInstance()
                                    val currentDay = calNow.get(Calendar.DAY_OF_MONTH)
                                    
                                    val cycleDay = if (card.billingCycleDay > 0) card.billingCycleDay else 15
                                    val dueDay = if (card.paymentDueDay > 0) card.paymentDueDay else 5
                                    
                                    val daysUntilStatement = if (currentDay <= cycleDay) {
                                        cycleDay - currentDay
                                    } else {
                                        val daysInMonth = calNow.getActualMaximum(Calendar.DAY_OF_MONTH)
                                        (daysInMonth - currentDay) + cycleDay
                                    }
                                    
                                    val daysUntilDue = if (currentDay <= dueDay) {
                                        dueDay - currentDay
                                    } else {
                                        val daysInMonth = calNow.getActualMaximum(Calendar.DAY_OF_MONTH)
                                        (daysInMonth - currentDay) + dueDay
                                    }
                                    
                                    val isUrgent = daysUntilDue <= 3
                                    val isWarning = daysUntilDue in 4..7

                                    // Balance calculated up to the billing cycle end date
                                    val statementBalance = viewModel.getCardBillingCycleBalance(card)
                                    
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isUrgent) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                             else if (isWarning) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
                                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                        )
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = card.name + (if (card.lastFourDigits.isNotBlank()) " (••${card.lastFourDigits})" else ""),
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                    if (statementBalance > 0) {
                                                        Text(
                                                            text = "Statement Bill (Cycle End): ₹${String.format(Locale.US, "%,.2f", statementBalance)}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                    Text(
                                                        text = "Current Outstanding: ₹${String.format(Locale.US, "%,.2f", Math.abs(card.balance))}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (card.balance < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                
                                                val badgeBg = if (isUrgent) MaterialTheme.colorScheme.errorContainer
                                                else if (isWarning) MaterialTheme.colorScheme.tertiaryContainer
                                                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                                
                                                val badgeTc = if (isUrgent) MaterialTheme.colorScheme.onErrorContainer
                                                else if (isWarning) MaterialTheme.colorScheme.onTertiaryContainer
                                                else MaterialTheme.colorScheme.onPrimaryContainer
                                                
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = badgeBg
                                                ) {
                                                    Text(
                                                        text = if (daysUntilDue == 0) "Due Today!" else "Due in $daysUntilDue d",
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = badgeTc
                                                    )
                                                }
                                            }
                                            
                                            Spacer(modifier = Modifier.height(8.dp))
                                            
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "Statement: Day $cycleDay (in $daysUntilStatement d)",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                
                                                TextButton(
                                                    onClick = {
                                                        title = "${card.name} Bill"
                                                        val fillAmount = if (statementBalance > 0) statementBalance else if (card.balance != 0.0) Math.abs(card.balance) else 5000.0
                                                        amountStr = String.format(Locale.US, "%.2f", fillAmount)
                                                        reminderType = "Credit Card"
                                                        selectedAccountId = card.id
                                                        isRecurring = true
                                                        showAddReminderDialog = true
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Icon(Icons.Default.Alarm, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Set Reminder", fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (reminders.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(56.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "No Payment Schedules Configured",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Tap the + button below or 'Set Reminder' on your cards above.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                } else {
                    // Render Active/Pending Reminders Selection
                    if (activeReminders.isNotEmpty()) {
                        items(activeReminders, key = { it.id }) { reminder ->
                            ReminderRowItem(
                                reminder = reminder,
                                accountsList = accounts,
                                onTogglePaid = { viewModel.toggleReminderPaid(reminder.id) },
                                onDelete = { viewModel.deleteReminder(reminder.id) }
                            )
                        }
                    } else if (completedReminders.isNotEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✨ You are all caught up for now!",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    
                    // Render Collapsible Completed Section
                    if (completedReminders.isNotEmpty()) {
                        item {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .clickable { showCompleted = !showCompleted },
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (showCompleted) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                                            contentDescription = if (showCompleted) "Hide Completed" else "Show Completed",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Completed (${completedReminders.size})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    
                                    Text(
                                        text = if (showCompleted) "Hide" else "Show",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        
                        if (showCompleted) {
                            items(completedReminders, key = { it.id }) { reminder ->
                                ReminderRowItem(
                                    reminder = reminder,
                                    accountsList = accounts,
                                    onTogglePaid = { viewModel.toggleReminderPaid(reminder.id) },
                                    onDelete = { viewModel.deleteReminder(reminder.id) }
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
        
        // Add Reminder Dialog Screen
        if (showAddReminderDialog) {
            AlertDialog(
                onDismissRequest = { showAddReminderDialog = false },
                title = { Text("Schedule Payable", fontWeight = FontWeight.Bold) },
                confirmButton = {
                    Button(
                        onClick = {
                            val amt = amountStr.toDoubleOrNull() ?: 0.0
                            if (title.isNotBlank() && amt > 0) {
                                viewModel.addNewReminder(
                                    title = title.trim(),
                                    amount = amt,
                                    type = reminderType,
                                    dueDate = dueDateMillis,
                                    isRecurring = isRecurring,
                                    accountId = selectedAccountId
                                )
                                // Reset fields
                                title = ""
                                amountStr = ""
                                reminderType = "Credit Card"
                                isRecurring = false
                                dueDateMillis = System.currentTimeMillis()
                                selectedAccountId = null
                                showAddReminderDialog = false
                            }
                        },
                        enabled = title.isNotBlank() && amountStr.toDoubleOrNull() != null
                    ) {
                        Text("Add Schedule")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        selectedAccountId = null
                        showAddReminderDialog = false 
                    }) {
                        Text("Cancel")
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Payable Title
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Payable Title", fontSize = 11.sp) },
                            placeholder = { Text("e.g. House Rent, Airtel Broadband, HDFC Card", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            singleLine = true
                        )
                        
                        // Expected Amount
                        OutlinedTextField(
                            value = amountStr,
                            onValueChange = { amountStr = it },
                            label = { Text("Expected Amount (₹)", fontSize = 11.sp) },
                            placeholder = { Text("e.g. 15000", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            leadingIcon = { Icon(Icons.Default.CurrencyRupee, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            singleLine = true
                        )
                        
                        // Type of Payable (Surface Chips: Credit Card / SIP / Others)
                        Column(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text("Type of Payable", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val types = listOf("Credit Card", "SIP", "Others")
                                types.forEach { t ->
                                    val isSelected = reminderType == t
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { reminderType = t }
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = t,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Quick suggestion chips when Others is selected
                        if (reminderType == "Others") {
                            Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                Text("Quick Presets", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(4.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(otherSuggestions) { suggestion ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                            modifier = Modifier.clickable {
                                                if (title.isBlank() || otherSuggestions.contains(title)) {
                                                    title = suggestion
                                                } else {
                                                    title = "$suggestion - $title"
                                                }
                                            }
                                        ) {
                                            Text(
                                                text = suggestion,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Account / Owner Selector Dropdown
                        var accountDropdownExpanded by remember { mutableStateOf(false) }
                        val matchedAccount = accounts.find { it.id == selectedAccountId }
                        val selectedAccountLabel = when {
                            matchedAccount != null -> {
                                val ownerLabel = if (matchedAccount.ownerUserId == "SHARED") "Shared" else matchedAccount.ownerFirstName.ifEmpty { "Personal" }
                                "${matchedAccount.name} ($ownerLabel)"
                            }
                            else -> "None (Unlinked / General Notification)"
                        }

                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            OutlinedTextField(
                                value = selectedAccountLabel,
                                onValueChange = {},
                                label = { Text("Associated Account / Owner", fontSize = 11.sp) },
                                readOnly = true,
                                shape = RoundedCornerShape(10.dp),
                                trailingIcon = {
                                    IconButton(onClick = { accountDropdownExpanded = true }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Account")
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { accountDropdownExpanded = true }
                            )
                            DropdownMenu(
                                expanded = accountDropdownExpanded,
                                onDismissRequest = { accountDropdownExpanded = false },
                                modifier = Modifier.fillMaxWidth(0.85f)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("None (Unlinked / General Notification)", fontSize = 12.sp) },
                                    onClick = {
                                        selectedAccountId = null
                                        accountDropdownExpanded = false
                                    }
                                )
                                accounts.forEach { acc ->
                                    val ownerLabel = if (acc.ownerUserId == "SHARED") "Shared" else acc.ownerFirstName.ifEmpty { "Personal" }
                                    DropdownMenuItem(
                                        text = { Text("${acc.name} ($ownerLabel - ${acc.accountClass})", fontSize = 12.sp) },
                                        onClick = {
                                            selectedAccountId = acc.id
                                            accountDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // Date Selector Trigger
                        val pickDateText = remember(dueDateMillis) {
                            val d = Date(dueDateMillis)
                            SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(d)
                        }
                        OutlinedTextField(
                            value = pickDateText,
                            onValueChange = {},
                            label = { Text("Due Date", fontSize = 11.sp) },
                            readOnly = true,
                            shape = RoundedCornerShape(10.dp),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        DatePickerDialog(
                                            context,
                                            { _, year, month, day ->
                                                calendar.set(year, month, day)
                                                dueDateMillis = calendar.timeInMillis
                                            },
                                            calendar.get(Calendar.YEAR),
                                            calendar.get(Calendar.MONTH),
                                            calendar.get(Calendar.DAY_OF_MONTH)
                                        ).show()
                                    }
                                ) {
                                    Icon(Icons.Default.DateRange, contentDescription = "Pick date", modifier = Modifier.size(18.dp))
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        )
                        
                        // Recurring Switch
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Auto-Repeat Monthly?", style = MaterialTheme.typography.bodyMedium, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Automatically schedules for next month when settled", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                            Switch(checked = isRecurring, onCheckedChange = { isRecurring = it })
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun ReminderRowItem(
    reminder: Reminder,
    accountsList: List<Account>,
    onTogglePaid: () -> Unit,
    onDelete: () -> Unit
) {
    val matchedAccount = accountsList.find { it.id == reminder.accountId }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Schedule", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete '${reminder.title}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("reminder_item_${reminder.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (reminder.isPaid) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (reminder.isPaid) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                // Done indicator / icon based on type
                val icon = when (reminder.type) {
                    "SIP" -> Icons.Default.TrendingUp
                    "Credit Card" -> Icons.Default.CreditCard
                    else -> Icons.Default.ReceiptLong
                }
                val tint = if (reminder.isPaid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(tint.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = reminder.title,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (reminder.isPaid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (reminder.type.isNotBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            ) {
                                Text(
                                    text = reminder.type,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "Due: " + formatDueDate(reminder.dueDate) + if (reminder.isRecurring) " • Monthly" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    if (matchedAccount != null) {
                        val ownerLabel = if (matchedAccount.ownerUserId == "SHARED") "Shared" else matchedAccount.ownerFirstName.ifEmpty { "Personal" }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountBox,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${matchedAccount.name} ($ownerLabel)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "₹${String.format(Locale.US, "%,.2f", reminder.amount)}",
                    fontWeight = FontWeight.Bold,
                    color = if (reminder.isPaid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = onTogglePaid,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (reminder.isPaid) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            }
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        val label = if (reminder.isPaid) "Settled" else "Mark Paid"
                        val labelColor = if (reminder.isPaid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer
                        Text(label, color = labelColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete Reminder",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatDueDate(millis: Long): String {
    return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))
}
