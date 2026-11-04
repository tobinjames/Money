package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.Account
import com.example.data.MerchantRule
import com.example.ui.viewmodel.ExpenseViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartRulesScreen(
    viewModel: ExpenseViewModel,
    onBackClick: () -> Unit
) {
    val merchantRules by viewModel.merchantRules.collectAsState()
    val categories by viewModel.categories.collectAsState()
    
    var showAddDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        topBar = {
            TopAppBar(
                title = { Text("SMS Smart Override Rules", fontWeight = FontWeight.Bold) },
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
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_rule_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Rule Override")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Header Stats/Info banner
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Rule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Column {
                        Text(
                            "Local SMS Rule Matches First",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Keyword checks run case-insensitively before querying Gemini AI parsing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            if (merchantRules.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No merchant keyword rules defined.",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "Create rule mappings to force categorizations strictly.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(merchantRules, key = { it.ruleId }) { rule ->
                        MerchantRuleItem(
                            rule = rule,
                            onDelete = { viewModel.deleteMerchantRule(rule.ruleId) }
                        )
                    }
                }
            }
        }
    }
    
    // --- ADD RULE OVERRIDE DIALOG ---
    if (showAddDialog) {
        var keywordStr by remember { mutableStateOf("") }
        var selectedCategoryName by remember { mutableStateOf(categories.firstOrNull()?.name ?: "") }
        var dropdownExpanded by remember { mutableStateOf(false) }
        
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Rule Override") },
            text = {
                Column {
                    OutlinedTextField(
                        value = keywordStr,
                        onValueChange = { keywordStr = it },
                        label = { Text("SMS Keyword trigger") },
                        placeholder = { Text("e.g. ZOMATO, UBER, DIRECTPAY") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rule_keyword_input")
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text("Target Assigned Category", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Dropdown menu
                    ExposedDropdownMenuBox(
                        expanded = dropdownExpanded,
                        onExpandedChange = { dropdownExpanded = !dropdownExpanded },
                        modifier = Modifier.testTag("rule_category_dropdown")
                    ) {
                        OutlinedTextField(
                            value = selectedCategoryName,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(),
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }
                        ) {
                            categories.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.name) },
                                    onClick = {
                                        selectedCategoryName = category.name
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val keywordTrimmed = keywordStr.trim().uppercase()
                        if (keywordTrimmed.isNotEmpty() && selectedCategoryName.isNotEmpty()) {
                            viewModel.addMerchantRule(
                                MerchantRule(
                                    keyword = keywordTrimmed,
                                    assignedCategory = selectedCategoryName
                                )
                            )
                            showAddDialog = false
                        }
                    },
                    enabled = keywordStr.trim().isNotEmpty() && selectedCategoryName.isNotEmpty(),
                    modifier = Modifier.testTag("save_rule_button")
                ) {
                    Text("Add Override")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAddDialog = false },
                    modifier = Modifier.testTag("cancel_rule_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun MerchantRuleItem(
    rule: MerchantRule,
    onDelete: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text(rule.keyword, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        )
                    )
                    
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "maps to",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp)
                    )
                    
                    SuggestionChip(
                        onClick = {},
                        label = { Text(rule.assignedCategory, fontWeight = FontWeight.Medium) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                        )
                    )
                }
                
                Text(
                    text = "Triggers on SMS contains '${rule.keyword}'",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }
            
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_rule_button_${rule.ruleId}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete smart rule mapping",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
