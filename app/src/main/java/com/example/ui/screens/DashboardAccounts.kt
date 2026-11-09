package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.Account
import com.example.data.AccountType
import com.example.domain.FreeToSpendRules
import kotlin.math.abs

/** Rows shown per group before "Show N more". */
private const val ROWS_BEFORE_MORE = 4

/**
 * Accounts in one compact card, one short line per account; long groups fold after a few rows.
 * - [currentOnly] (the dashboard): bank & cash and credit cards only, headed by what's in hand
 *   minus what's owed on the cards.
 * - Otherwise (the Net worth screen): investments and loans too, headed by net worth.
 */
@Composable
fun AccountsOverviewCard(
    title: String,
    accounts: List<Account>,
    ownerLabel: (Account) -> String,
    currentOnly: Boolean = false,
    onOpenNetWorth: (() -> Unit)? = null
) {
    val groups = remember(accounts, currentOnly) {
        listOf(
            "Bank & cash" to accounts.filter { FreeToSpendRules.isLiquid(it) },
            "Credit cards" to accounts.filter { FreeToSpendRules.isCard(it) },
            "Investments" to accounts.filter { it.accountType == "ASSET" && (it.type == AccountType.FIXED_DEPOSIT || it.type == AccountType.MUTUAL_FUND) },
            "Loans" to accounts.filter { it.accountType == "LIABILITY" && it.type == AccountType.LOAN }
        ).filter { it.second.isNotEmpty() }
            .filter { !currentOnly || it.first == "Bank & cash" || it.first == "Credit cards" }
            .map { (name, list) -> name to list.sortedByDescending { abs(it.balance) } }
    }
    val counted = groups.flatMap { it.second }
    val assets = counted.filter { it.accountType == "ASSET" }.sumOf { it.balance }
    val owed = counted.filter { it.accountType == "LIABILITY" }.sumOf { abs(it.balance) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("accounts_overview_card"),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        formatAmount(assets - owed),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (assets - owed < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag(if (currentOnly) "current_position_val" else "net_worth_val")
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${if (currentOnly) "In hand" else "Own"} ${formatAmount(assets)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${if (currentOnly) "Cards owe" else "Owe"} ${formatAmount(owed)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (groups.isEmpty()) {
                Text(
                    "No accounts here yet. Add them in Settings > Accounts & categories.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            groups.forEach { (name, list) -> AccountGroup(name, list, ownerLabel) }
            if (onOpenNetWorth != null) {
                TextButton(onClick = onOpenNetWorth, contentPadding = PaddingValues(0.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Text("Net worth: trend, split & investments →", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun AccountGroup(name: String, list: List<Account>, ownerLabel: (Account) -> String) {
    var open by rememberSaveable(name) { mutableStateOf(true) }
    var showAll by rememberSaveable(name + "_all") { mutableStateOf(false) }
    val isLiability = list.firstOrNull()?.accountType == "LIABILITY"
    val total = list.sumOf { abs(it.balance) }

    HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$name (${list.size})", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(
            formatAmount(total),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (isLiability) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (open) "Collapse" else "Expand", modifier = Modifier.size(18.dp))
    }
    if (!open) return
    val shown = if (showAll || list.size <= ROWS_BEFORE_MORE + 1) list else list.take(ROWS_BEFORE_MORE)
    shown.forEach { acc ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                acc.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                listOfNotNull(acc.lastFourDigits.takeIf { it.isNotBlank() }?.let { " ••$it" }, " · ${ownerLabel(acc)}").joinToString(""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                formatAmount(abs(acc.balance)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isLiability && abs(acc.balance) > 0.5) MaterialTheme.colorScheme.error
                    else if (acc.balance < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
    }
    if (list.size > shown.size || showAll && list.size > ROWS_BEFORE_MORE + 1) {
        TextButton(onClick = { showAll = !showAll }, contentPadding = PaddingValues(0.dp)) {
            Text(if (showAll) "Show less" else "Show ${list.size - shown.size} more", style = MaterialTheme.typography.labelMedium)
        }
    }
}
