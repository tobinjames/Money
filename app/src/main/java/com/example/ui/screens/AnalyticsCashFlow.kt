package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.CashFlowRules
import com.example.domain.PayerRules.Who
import kotlin.math.abs

/**
 * Analytics, on a cash basis: who paid for the family's expenses, who paid for each person's
 * own spending, and who received the income. "Paid" and "received" follow whose account the
 * money moved through; "for" is the entry's "Paid for" choice.
 */
@Composable
fun WhoPaidCard(flow: CashFlowRules.Summary, myName: String, partnerName: String) {
    val nameOf = { who: Who ->
        when (who) {
            Who.ME -> myName
            Who.PARTNER -> partnerName
            Who.JOINT -> "Joint accounts"
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("who_paid_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(Modifier.padding(20.dp)) {
            // 1. Family expenses, by who paid
            SectionLabel("WHO PAID FOR FAMILY EXPENSES")
            Text(
                formatAmount(flow.familyTotal),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(8.dp))
            val largest = Who.values().maxOf { abs(flow.familyPaidBy(it)) }
            Who.values().forEach { who ->
                val amount = flow.familyPaidBy(who)
                if (who != Who.JOINT || amount != 0.0) {
                    AmountBar(nameOf(who), amount, largest, colorOf(who), "family_paid_by_${who.name.lowercase()}")
                }
            }
            val lead = flow.familyLead
            if (abs(lead) >= 1.0) {
                Text(
                    if (lead > 0) "$myName paid ${formatAmount(lead)} more than $partnerName."
                    else "$partnerName paid ${formatAmount(-lead)} more than $myName.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp).testTag("family_lead_line")
                )
            }

            // 2. Spending marked as one person's, by who paid
            listOf(Who.ME, Who.PARTNER).filter { flow.totalFor(it) != 0.0 }.forEach { forWhom ->
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                SectionLabel("PAID FOR ${nameOf(forWhom).uppercase()}")
                Who.values().forEach { payer ->
                    val amount = flow.paid(payer, forWhom)
                    if (amount != 0.0) LineRow(if (payer == Who.JOINT) "From joint accounts" else "By ${nameOf(payer)}", amount)
                }
            }

            // 3. Income, by who received it
            if (flow.received.values.any { it != 0.0 }) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                SectionLabel("MONEY RECEIVED")
                Who.values().forEach { who ->
                    val amount = flow.received[who] ?: 0.0
                    if (amount != 0.0) LineRow(nameOf(who), amount)
                }
            }

            Text(
                "Who paid and who received go by whose account or card the money moved through. " +
                    "An entry is a family expense unless its \"Paid for\" is set to one person.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun colorOf(who: Who): Color = when (who) {
    Who.ME -> MaterialTheme.colorScheme.primary
    Who.PARTNER -> MaterialTheme.colorScheme.tertiary
    Who.JOINT -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
}

@Composable
private fun LineRow(label: String, amount: Double) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(formatAmount(amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

/** A name and amount over a bar sized against the largest amount in the group. */
@Composable
private fun AmountBar(label: String, amount: Double, largest: Double, color: Color, tag: String) {
    Column(Modifier.padding(vertical = 4.dp).testTag(tag)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color)
            Text(formatAmount(amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            val share = if (largest > 0.0) (abs(amount) / largest).toFloat().coerceIn(0f, 1f) else 0f
            if (share > 0f) {
                Box(Modifier.fillMaxWidth(share).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
            }
        }
    }
}
