package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import android.app.DatePickerDialog
import com.example.data.*
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

val CategoryColors = mapOf(
    "Food" to Color(0xFFC2410C),
    "Groceries" to Color(0xFF15803D),
    "Utilities" to Color(0xFF0369A1),
    "Investment" to Color(0xFF7E22CE),
    "Housing" to Color(0xFFB45309),
    "Shopping" to Color(0xFFBE185D),
    "Travel" to Color(0xFF0F766E),
    "Entertainment" to Color(0xFF4338CA),
    "Finance: Loan Interest" to Color(0xFFB91C1C),
    "Transfer" to Color(0xFF334155),
    "Others" to Color(0xFF57534E)
)

private val dynamicCategoryPalette = listOf(
    Color(0xFF0284C7), // Sky Blue
    Color(0xFF0D9488), // Teal
    Color(0xFF16A34A), // Green
    Color(0xFF65A30D), // Lime
    Color(0xFFCA8A04), // Yellow-amber
    Color(0xFFD97706), // Amber
    Color(0xFFEA580C), // Orange
    Color(0xFFDC2626), // Red
    Color(0xFFE11D48), // Rose
    Color(0xFFC026D3), // Fuchsia
    Color(0xFF9333EA), // Purple
    Color(0xFF4F46E5), // Indigo
    Color(0xFF2563EB), // Blue
    Color(0xFF0891B2), // Cyan
    Color(0xFF059669)  // Emerald
)

fun getCategoryColor(category: String): Color {
    val predefined = CategoryColors[category]
    if (predefined != null) return predefined
    if (category.isBlank() || category.equals("Others", ignoreCase = true) || category.equals("Unknown", ignoreCase = true)) {
        return Color(0xFF64748B)
    }
    val index = Math.abs(category.trim().lowercase().hashCode()) % dynamicCategoryPalette.size
    return dynamicCategoryPalette[index]
}

val categoryIconPack = mapOf(
    "Food" to Icons.Default.Restaurant,
    "Groceries" to Icons.Default.ShoppingCart,
    "Housing" to Icons.Default.Home,
    "Utilities" to Icons.Default.Bolt,
    "Investment" to Icons.Default.TrendingUp,
    "SIP" to Icons.Default.ShowChart,
    "Travel" to Icons.Default.Flight,
    "Salary" to Icons.Default.Work,
    "Refund" to Icons.Default.Payments,
    "Shopping" to Icons.Default.LocalMall,
    "Entertainment" to Icons.Default.Movie,
    "Medical" to Icons.Default.MedicalServices,
    "Education" to Icons.Default.School,
    "Gifts" to Icons.Default.CardGiftcard,
    "Mobile" to Icons.Default.PhoneAndroid,
    "CreditCard" to Icons.Default.CreditCard,
    "Transfer" to Icons.Default.SwapHoriz,
    "Warning" to Icons.Default.Warning,
    "Info" to Icons.Default.Info,
    "Star" to Icons.Default.Star,
    "Settings" to Icons.Default.Settings,
    "Heart" to Icons.Default.Favorite,
    "Car" to Icons.Default.DirectionsCar,
    "Receipt" to Icons.Default.Receipt,
    "Pets" to Icons.Default.Pets,
    "Fitness" to Icons.Default.FitnessCenter,
    "Gas" to Icons.Default.LocalGasStation,
    "Coffee" to Icons.Default.LocalCafe
)

fun getCategoryIcon(category: String, iconName: String = ""): ImageVector {
    val nameToUse = if (iconName.isNotBlank()) iconName else category
    return categoryIconPack[nameToUse]
        ?: categoryIconPack[category]
        ?: when (category) {
            "Housing" -> Icons.Default.Home
            "Investment" -> Icons.Default.TrendingUp
            "SIP" -> Icons.Default.ShowChart
            "Finance: Loan Interest" -> Icons.Default.Warning
            "Transfer" -> Icons.Default.SwapHoriz
            else -> Icons.Default.Info
        }
}

fun formatAmount(amount: Double): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    return formatter.format(amount)
}

fun formatDate(timestamp: Long): String {
    val date = Date(timestamp)
    val format = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    return format.format(date)
}

@Composable
fun MetricCard(
    title: String,
    amount: Double,
    isNegativeAllowed: Boolean = false,
    gradientColors: List<Color>,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(24.dp))
            .background(
                brush = Brush.linearGradient(colors = gradientColors),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(20.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            val isDebt = amount < 0 && isNegativeAllowed
            val displayAmt = if (isDebt) Math.abs(amount) else amount
            val prefix = if (isDebt) "-" else ""
            Text(
                text = "$prefix${formatAmount(displayAmt)}",
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )
            )
        }
    }
}

@Composable
fun InteractiveDoughnutChart(
    categoryTotals: Map<String, Double>,
    modifier: Modifier = Modifier
) {
    val items = categoryTotals.toList().filter { it.second > 0 }.sortedByDescending { it.second }
    val total = items.sumOf { it.second }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("pie_chart_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Expense Breakdown",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (total == 0.0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No spending logged yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Custom Canvas Doughnut
                    Box(
                        modifier = Modifier.size(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            var startAngle = -90f
                            items.forEach { (cat, valAmt) ->
                                val sweepAngle = ((valAmt / total) * 360f).toFloat()
                                val color = getCategoryColor(cat)
                                drawArc(
                                    color = color,
                                    startAngle = startAngle,
                                    sweepAngle = sweepAngle,
                                    useCenter = false,
                                    size = Size(size.width, size.height),
                                    style = Stroke(width = 24.dp.toPx(), cap = StrokeCap.Round)
                                )
                                startAngle += sweepAngle
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Total spent",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatAmount(total),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Legend Column
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        items.take(4).forEach { (cat, amt) ->
                            val color = getCategoryColor(cat)
                            val percentage = (amt / total) * 100
                            Row(
                                modifier = Modifier.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(color, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = cat,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${formatAmount(amt)} (${String.format("%.1f", percentage)}%)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (items.size > 4) {
                            Text(
                                text = "+ ${items.size - 4} other categories",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 20.dp, top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TransactionRowItem(
    tx: Transaction,
    accountName: String,
    isLocked: Boolean = false,
    merchantRules: List<MerchantRule> = emptyList(),
    user1Name: String = "Tobin",
    user2Name: String = "Soumya",
    categories: List<Account> = emptyList(),
    onNavigateDetail: () -> Unit = {}
) {
    val myUid by FirebaseService.currentUserUid.collectAsState()
    val spouseUid by FirebaseService.spouseUserUid.collectAsState()

    val paidByLabel = remember(tx.paidByUserId, user1Name, user2Name, myUid, spouseUid) {
        when {
            tx.paidByUserId.equals("BOTH", ignoreCase = true) || tx.paidByUserId.isBlank() -> "Shared Split"
            tx.paidByUserId == "User 1" || (myUid != null && tx.paidByUserId == myUid) -> "For $user1Name"
            tx.paidByUserId == "User 2" || (spouseUid != null && tx.paidByUserId == spouseUid) -> "For $user2Name"
            else -> "For ${tx.paidByUserId}"
        }
    }

    val matchingCategory = remember(categories, tx.category) {
        categories.firstOrNull { it.name.equals(tx.category, ignoreCase = true) }
    }
    val iconName = matchingCategory?.iconName ?: ""

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .then(if (isLocked) Modifier else Modifier.clickable { onNavigateDetail() })
            .testTag("transaction_row_${tx.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isLocked) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                             else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon Container
            val color = if (isLocked) MaterialTheme.colorScheme.onSurfaceVariant else getCategoryColor(tx.category)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(color.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getCategoryIcon(tx.category, iconName),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))

            // Body content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tx.narration,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isLocked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = accountName,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isLocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    
                    // Elegant Category Badge
                    val catColor = if (isLocked) MaterialTheme.colorScheme.onSurfaceVariant else getCategoryColor(tx.category)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(catColor.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = tx.category,
                            style = MaterialTheme.typography.labelSmall,
                            color = catColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = formatDate(tx.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                TransactionTagsRow(tx = tx, merchantRules = merchantRules)
                if (!tx.originalSmsText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = tx.originalSmsText,
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Amount and Who Paid Badge
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                val isIncome = tx.type == TransactionType.INCOME
                val isTransfer = tx.type == TransactionType.TRANSFER
                val amtColor = when {
                    isLocked -> MaterialTheme.colorScheme.onSurfaceVariant
                    isIncome -> Color(0xFF15803D) // Accessible dark green
                    isTransfer -> Color(0xFF4F46E5) // Nice indigo
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val prefix = when {
                    isIncome -> "+"
                    isTransfer -> "⇄"
                    else -> "-"
                }
                Text(
                    text = "$prefix ${formatAmount(tx.amount)}",
                    fontWeight = FontWeight.Bold,
                    color = amtColor,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    if (isLocked) {
                        Icon(
                             imageVector = Icons.Default.Lock,
                             contentDescription = "Locked",
                             tint = MaterialTheme.colorScheme.onSurfaceVariant,
                             modifier = Modifier.size(11.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Locked",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    
                    // Elegant "Paid By" pill indicator
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = paidByLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TransactionTagsRow(
    tx: Transaction,
    merchantRules: List<MerchantRule> = emptyList(),
    modifier: Modifier = Modifier
) {
    val tags = remember(tx, merchantRules) {
        val list = mutableListOf<Pair<String, ColorPair>>()
        if (tx.tag.isNotBlank()) {
            val formattedTag = if (tx.tag.startsWith("#")) tx.tag else "#${tx.tag}"
            list.add(formattedTag to ColorPair(Color(0xFF0D9488), Color(0xFFCCFBF1)))
        }
        if (tx.isProbablyDuplicate) {
            list.add("Suspected Duplicate" to ColorPair(Color(0xFFD93025), Color(0xFFFCE8E6)))
        }
        if (tx.originalSmsText.isNullOrBlank()) {
            list.add("Manual" to ColorPair(Color(0xFF5F6368), Color(0xFFF1F3F4)))
        } else {
            list.add("SMS" to ColorPair(Color(0xFF1A73E8), Color(0xFFE8F0FE)))
            list.add("AI" to ColorPair(Color(0xFF7B1FA2), Color(0xFFF3E5F5)))
            
            val isSmart = tx.isSmartRule || merchantRules.any { rule ->
                val kw = rule.keyword.trim().uppercase()
                kw.isNotEmpty() && (
                    tx.narration.uppercase().contains(kw) ||
                    (tx.originalSmsText.uppercase().contains(kw))
                )
            }
            if (isSmart) {
                list.add("Smart Rule" to ColorPair(Color(0xFF137333), Color(0xFFE6F4EA)))
            }
        }
        list
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tags.forEach { (text, colors) ->
            Surface(
                color = colors.background,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = text,
                    color = colors.text,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private data class ColorPair(val text: Color, val background: Color)
