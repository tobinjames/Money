package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.Account
import com.example.domain.NetWorthRules
import kotlin.math.abs

/** Names offered when making a group, so the common ones are one tap away. */
internal val SuggestedNetWorthGroups = listOf("Retirement", "Short term", "Medium term", "Long term", "Child")

private fun nameClash(name: String, taken: List<String>, except: String?): Boolean {
    val clean = name.trim()
    if (clean.equals(NetWorthRules.UNGROUPED, ignoreCase = true)) return true
    return taken.any { it.equals(clean, ignoreCase = true) && !it.equals(except?.trim(), ignoreCase = true) }
}

/**
 * The user's own groups on the Net worth screen, such as Retirement, Short term or Child:
 * what each one is worth, with its accounts a tap away. Accounts in no group are listed last
 * so the card adds up to net worth.
 */
@Composable
internal fun NetWorthGroupsCard(
    groups: List<NetWorthRules.Group>,
    ownerLabel: (Account) -> String,
    onNew: () -> Unit,
    onEdit: (String) -> Unit
) {
    NetWorthCard("net_worth_groups") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your groups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onNew, modifier = Modifier.testTag("net_worth_new_group")) { Text("+ New group") }
        }
        if (groups.isEmpty()) {
            Text(
                "Sort your money by what it is for: Retirement, Short term, Medium term, Child, or any name you like. " +
                    "Make a group and pick the accounts and investments that belong in it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@NetWorthCard
        }
        val biggest = groups.maxOf { abs(it.net) }.coerceAtLeast(1.0)
        groups.forEach { g -> GroupRow(g, biggest, ownerLabel, onEdit) }
    }
}

@Composable
private fun GroupRow(
    group: NetWorthRules.Group,
    biggest: Double,
    ownerLabel: (Account) -> String,
    onEdit: (String) -> Unit
) {
    var open by rememberSaveable(group.name) { mutableStateOf(false) }
    val negative = group.net < -0.005
    HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                group.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (group.custom) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when (group.members.size) {
                    0 -> "Nothing in this view"
                    1 -> "1 account"
                    else -> "${group.members.size} accounts"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            formatAmount(group.net),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        if (group.custom) {
            IconButton(onClick = { onEdit(group.name) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Edit ${group.name}", modifier = Modifier.size(18.dp))
            }
        } else {
            Spacer(Modifier.width(36.dp))
        }
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (open) "Collapse" else "Expand", modifier = Modifier.size(18.dp))
    }
    LinearProgressIndicator(
        progress = { (abs(group.net) / biggest).toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(4.dp),
        color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant
    )
    if (!open) return
    Spacer(Modifier.height(4.dp))
    group.members.forEach { m ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(m.account.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(
                " · ${accountKindOf(m.account).label} · ${ownerLabel(m.account)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                formatAmount(m.value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (m.value < -0.005) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Make, rename, refill or remove one group. [editing] is the group's current name, null for a
 * new one. An account is in one group at a time, so ticking one that is elsewhere moves it here.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NetWorthGroupSheet(
    accounts: List<Account>,
    existingNames: List<String>,
    editing: String?,
    ownerLabel: (Account) -> String,
    onSave: (name: String, memberIds: Set<String>) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable { mutableStateOf(editing.orEmpty()) }
    var picked by rememberSaveable {
        mutableStateOf<List<String>>(
            if (editing == null) emptyList()
            else accounts.filter { NetWorthRules.groupName(it).equals(editing, ignoreCase = true) }.map { it.accountId }
        )
    }
    val clash = nameClash(name, existingNames, editing)
    val clashText: (@Composable () -> Unit)? =
        if (clash) { { Text("You already have a group with this name.") } } else null
    val canSave = name.isNotBlank() && !clash && picked.isNotEmpty()
    val sections = remember(accounts) { groupAccounts(accounts) }
    val suggestions = SuggestedNetWorthGroups.filter { s -> existingNames.none { it.equals(s, ignoreCase = true) } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.testTag("net_worth_group_sheet")) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 20.dp)
        ) {
            Text(if (editing == null) "New group" else "Edit group", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Both of you see the same groups.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 30) name = it },
                label = { Text("Group name") },
                placeholder = { Text("e.g. Retirement") },
                isError = clash,
                supportingText = clashText,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("net_worth_group_name")
            )
            if (editing == null && suggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    suggestions.forEach { s ->
                        FilterChip(selected = name.trim().equals(s, ignoreCase = true), onClick = { name = s }, label = { Text(s) })
                    }
                }
            }

            Text("What goes in it?", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp))
            if (accounts.isEmpty()) {
                Text("No accounts yet. Add them in Settings > Accounts & categories.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            sections.forEach { (title, list) ->
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                )
                list.forEach { acc ->
                    val on = acc.accountId in picked
                    val other = NetWorthRules.groupName(acc).takeIf { it.isNotEmpty() && !it.equals(editing, ignoreCase = true) }
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { picked = if (on) picked - acc.accountId else picked + acc.accountId }
                            .testTag("net_worth_group_account_${acc.accountId}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp, top = 10.dp, bottom = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(acc.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    acc.lastFourDigits.takeIf { it.isNotBlank() }?.let { "••$it" },
                                    ownerLabel(acc),
                                    other?.let { if (on) "moves from $it" else "in $it" }
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(formatAmount(abs(acc.balance)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Button(
                onClick = { onSave(name.trim(), picked.toSet()) },
                enabled = canSave,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("net_worth_group_save")
            ) { Text(if (picked.isEmpty()) "Pick at least one account" else "Save group") }
            if (editing != null) {
                TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth().testTag("net_worth_group_remove")) {
                    Text("Remove this group (accounts stay)", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * Picks an account's Net worth group inside the account form: none, one of the groups there
 * already are, a suggested one, or a new name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NetWorthGroupChoice(existingNames: List<String>, value: String, onChange: (String) -> Unit) {
    val names = remember(existingNames) {
        existingNames + SuggestedNetWorthGroups.filter { s -> existingNames.none { it.equals(s, ignoreCase = true) } }
    }
    var typing by rememberSaveable { mutableStateOf(value.isNotBlank() && names.none { it.equals(value.trim(), ignoreCase = true) }) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !typing && value.isBlank(), onClick = { typing = false; onChange("") }, label = { Text("None") })
        names.forEach { n ->
            FilterChip(
                selected = !typing && value.trim().equals(n, ignoreCase = true),
                onClick = { typing = false; onChange(n) },
                label = { Text(n) }
            )
        }
        FilterChip(selected = typing, onClick = { typing = true; onChange("") }, label = { Text("+ New") })
    }
    if (typing) {
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.length <= 30) onChange(it) },
            placeholder = { Text("Group name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().testTag("account_net_worth_group_input")
        )
    }
}
