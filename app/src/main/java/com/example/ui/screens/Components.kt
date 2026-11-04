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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountOrNodeDialog(
    viewModel: com.example.ui.viewmodel.ExpenseViewModel,
    onDismissRequest: () -> Unit,
    initialSelectedClass: String = "ASSET"
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val categories by viewModel.categories.collectAsState()
    val currentUserFirstName by viewModel.currentUserFirstName.collectAsState()
    val spouseUserFirstName by viewModel.spouseUserFirstName.collectAsState()
    val partnerAName = currentUserFirstName ?: "Tobin"
    val partnerBName = spouseUserFirstName ?: "Soumya"

    val masterGroups = listOf("ASSET", "LIABILITY", "INCOME", "EXPENSE")
    var selectedClass by remember { mutableStateOf(initialSelectedClass) }

    val defaultMainCategories = when (selectedClass) {
        "ASSET" -> listOf("Bank Accounts", "Fixed Deposits", "Mutual Funds", "Cash")
        "LIABILITY" -> listOf("Credit Cards", "Loans", "Other Liabilities")
        "INCOME" -> listOf("Direct Income", "Indirect Income")
        else -> listOf("Indirect Expenses", "Direct Expenses", "Tax", "Finance Costs")
    }

    var mainCategoryExpanded by remember { mutableStateOf(false) }
    var selectedMainCategory by remember { mutableStateOf(defaultMainCategories.firstOrNull() ?: "") }
    var isNewMainCategory by remember { mutableStateOf(false) }
    var newMainCategoryName by remember { mutableStateOf("") }

    val mergedMainCategories = remember(categories, selectedClass, defaultMainCategories) {
        val dbMains = categories.filter { 
            it.accountClass.uppercase() == selectedClass.uppercase() 
        }.map { it.mainCategory }.distinct()
        (defaultMainCategories + dbMains).distinct().filter { it.isNotBlank() }
    }

    LaunchedEffect(selectedClass) {
        selectedMainCategory = defaultMainCategories.firstOrNull() ?: ""
        isNewMainCategory = false
        newMainCategoryName = ""
    }

    val existingSubcategories = remember(categories, selectedClass, selectedMainCategory, isNewMainCategory, newMainCategoryName) {
        val cleanMain = if (isNewMainCategory) newMainCategoryName.trim() else selectedMainCategory.trim()
        if (isNewMainCategory || cleanMain.isEmpty()) {
            emptyList()
        } else {
            categories.filter { 
                it.accountClass.uppercase() == selectedClass.uppercase() && 
                it.mainCategory.uppercase() == cleanMain.uppercase()
            }.map { it.name }.distinct()
        }
    }

    var subcategoryExpanded by remember { mutableStateOf(false) }
    var selectedSubcategory by remember { mutableStateOf("") }
    var isNewSubcategory by remember { mutableStateOf(false) }
    var newSubcategoryName by remember { mutableStateOf("") }

    LaunchedEffect(existingSubcategories, isNewMainCategory) {
        if (isNewMainCategory) {
            selectedSubcategory = "+ Create New Subcategory..."
            isNewSubcategory = true
        } else if (existingSubcategories.isNotEmpty()) {
            selectedSubcategory = existingSubcategories.first()
            isNewSubcategory = false
        } else {
            selectedSubcategory = "+ Create New Subcategory..."
            isNewSubcategory = true
        }
    }

    val isAssetOrLiability = selectedClass == "ASSET" || selectedClass == "LIABILITY"
    var enableRealTimeTracking by remember { mutableStateOf(isAssetOrLiability) }
    
    LaunchedEffect(selectedClass) {
        enableRealTimeTracking = isAssetOrLiability
    }

    var initialBalanceStr by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("Combined") }
    var asOfDate by remember { mutableStateOf(System.currentTimeMillis()) }
    val calendar = remember { Calendar.getInstance() }

    var annualRateStr by remember { mutableStateOf("") }
    var remainingMonthsStr by remember { mutableStateOf("") }
    var emiStr by remember { mutableStateOf("") }
    var lastFourDigits by remember { mutableStateOf("") }
    var billingCycleDayStr by remember { mutableStateOf("") }
    var paymentDueDayStr by remember { mutableStateOf("") }
    
    var iconSelected by remember { mutableStateOf("Info") }
    LaunchedEffect(selectedClass) {
        iconSelected = when (selectedClass) {
            "ASSET" -> "Star"
            "LIABILITY" -> "CreditCard"
            "INCOME" -> "Salary"
            "EXPENSE" -> "Shopping"
            else -> "Info"
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DashboardCustomize,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Ledger & Account Setup",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Configure a tracking account or categorization category",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalMainCategory = if (isNewMainCategory) newMainCategoryName.trim() else selectedMainCategory.trim()
                    val finalSubcategory = if (isNewSubcategory) newSubcategoryName.trim() else selectedSubcategory.trim()
                    
                    if (finalMainCategory.isNotEmpty() && finalSubcategory.isNotEmpty()) {
                        if (enableRealTimeTracking) {
                            val accountType = when (finalMainCategory) {
                                "Bank Accounts" -> AccountType.SAVINGS_ACCOUNT
                                "Credit Cards" -> AccountType.CREDIT_CARD
                                "Fixed Deposits" -> AccountType.FIXED_DEPOSIT
                                "Mutual Funds" -> AccountType.MUTUAL_FUND
                                "Loans" -> AccountType.LOAN
                                else -> {
                                    if (selectedClass == "LIABILITY") AccountType.CREDIT_CARD else AccountType.SAVINGS_ACCOUNT
                                }
                            }

                            val initBal = initialBalanceStr.toDoubleOrNull() ?: 0.0
                            viewModel.addAccount(
                                context = context,
                                name = finalSubcategory,
                                type = accountType,
                                balance = initBal,
                                owner = owner,
                                openingBalance = initBal,
                                asOfDate = asOfDate,
                                annualInterestRate = annualRateStr.toDoubleOrNull() ?: 0.0,
                                remainingMonths = remainingMonthsStr.toIntOrNull() ?: 0,
                                monthlyEmi = emiStr.toDoubleOrNull() ?: 0.0,
                                accountClass = selectedClass,
                                mainCategory = finalMainCategory,
                                subCategory = finalSubcategory,
                                lastFourDigits = lastFourDigits.trim(),
                                iconName = iconSelected,
                                billingCycleDay = billingCycleDayStr.toIntOrNull() ?: 0,
                                paymentDueDay = paymentDueDayStr.toIntOrNull() ?: 0
                            )
                        } else {
                            viewModel.addCategory(
                                Account(
                                    accountId = UUID.randomUUID().toString(),
                                    accountName = finalSubcategory,
                                    accountType = selectedClass,
                                    isSystemDefault = false,
                                    customAccountClass = selectedClass,
                                    customMainCategory = finalMainCategory,
                                    customSubCategory = finalSubcategory,
                                    iconName = iconSelected
                                )
                            )
                        }
                        onDismissRequest()
                    }
                },
                enabled = (if (isNewMainCategory) newMainCategoryName.trim().isNotEmpty() else selectedMainCategory.isNotEmpty() && selectedMainCategory != "+ Create New Main Category...") &&
                          (if (isNewSubcategory) newSubcategoryName.trim().isNotEmpty() else selectedSubcategory.isNotEmpty() && selectedSubcategory != "+ Create New Subcategory..."),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("save_node_and_account_btn")
            ) {
                Text("Save Configuration", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.testTag("cancel_node_and_account_btn")
            ) {
                Text("Cancel", fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                item {
                    Column {
                        Text(
                            text = "Account Classification",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val classes = listOf(
                                Triple("ASSET", Icons.Default.AccountBalance, MaterialTheme.colorScheme.primary),
                                Triple("LIABILITY", Icons.Default.CreditCard, MaterialTheme.colorScheme.error),
                                Triple("EQUITY", Icons.Default.PieChart, Color(0xFF8B5CF6)),
                                Triple("INCOME", Icons.Default.ArrowUpward, Color(0xFF10B981)),
                                Triple("EXPENSE", Icons.Default.ArrowDownward, Color(0xFFF59E0B))
                            )
                            classes.forEach { (cls, icon, activeColor) ->
                                val isSel = selectedClass == cls
                                Surface(
                                    onClick = { 
                                        selectedClass = cls
                                        selectedMainCategory = when (cls) {
                                            "ASSET" -> "Bank Accounts"
                                            "LIABILITY" -> "Credit Cards"
                                            "EQUITY" -> "Owner's Capital"
                                            "INCOME" -> "Salary"
                                            "EXPENSE" -> "Food & Dining"
                                            else -> "General"
                                        }
                                        isNewMainCategory = false
                                        selectedSubcategory = ""
                                        isNewSubcategory = false
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSel) activeColor.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.5.dp,
                                        if (isSel) activeColor else Color.Transparent
                                    ),
                                    modifier = Modifier.weight(1f).testTag("class_filter_chip_$cls")
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = if (isSel) activeColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = cls,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSel) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Column {
                        Text(
                            text = "Category (Main Group)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable { mainCategoryExpanded = true }
                                .padding(horizontal = 16.dp)
                                .testTag("main_category_dropdown_trigger"),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(selectedMainCategory, style = MaterialTheme.typography.bodyMedium)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            DropdownMenu(
                                expanded = mainCategoryExpanded,
                                onDismissRequest = { mainCategoryExpanded = false },
                                modifier = Modifier.testTag("main_category_dropdown_menu")
                            ) {
                                mergedMainCategories.forEach { cat ->
                                    DropdownMenuItem(
                                        text = { Text(cat) },
                                        onClick = {
                                            selectedMainCategory = cat
                                            isNewMainCategory = false
                                            mainCategoryExpanded = false
                                        },
                                        modifier = Modifier.testTag("main_category_option_$cat")
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("+ Create New Main Category...", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                                    onClick = {
                                        selectedMainCategory = "+ Create New Main Category..."
                                        isNewMainCategory = true
                                        mainCategoryExpanded = false
                                    },
                                    modifier = Modifier.testTag("main_category_option_create_new")
                                )
                            }
                        }
                    }
                }

                if (isNewMainCategory) {
                    item {
                        OutlinedTextField(
                            value = newMainCategoryName,
                            onValueChange = { newMainCategoryName = it },
                            label = { Text("New Main Category Group") },
                            placeholder = { Text("e.g. Utility Bills, Investments") },
                            leadingIcon = { Icon(Icons.Default.AddCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("new_main_category_name_input"),
                            singleLine = true
                        )
                    }
                }

                item {
                    Column {
                        Text(
                            text = "Sub Category / Name",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .clickable { subcategoryExpanded = true }
                                .padding(horizontal = 16.dp)
                                .testTag("subcategory_dropdown_trigger"),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(selectedSubcategory, style = MaterialTheme.typography.bodyMedium)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            DropdownMenu(
                                expanded = subcategoryExpanded,
                                onDismissRequest = { subcategoryExpanded = false },
                                modifier = Modifier.testTag("subcategory_dropdown_menu")
                            ) {
                                existingSubcategories.forEach { sub ->
                                    DropdownMenuItem(
                                        text = { Text(sub) },
                                        onClick = {
                                            selectedSubcategory = sub
                                            isNewSubcategory = false
                                            subcategoryExpanded = false
                                        },
                                        modifier = Modifier.testTag("subcategory_option_$sub")
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("+ Create New Subcategory...", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                                    onClick = {
                                        selectedSubcategory = "+ Create New Subcategory..."
                                        isNewSubcategory = true
                                        subcategoryExpanded = false
                                    },
                                    modifier = Modifier.testTag("subcategory_option_create_new")
                                )
                            }
                        }
                    }
                }

                if (isNewSubcategory) {
                    item {
                        OutlinedTextField(
                            value = newSubcategoryName,
                            onValueChange = { newSubcategoryName = it },
                            label = { Text("New Subcategory / Account Name") },
                            placeholder = { Text("e.g. HDFC Salary, SBI Loan, Uber") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("new_subcategory_name_input"),
                            singleLine = true
                        )
                    }
                }

                item {
                    Column {
                        Text(
                            text = "Assign Category Icon",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            categoryIconPack.forEach { (name, iconVector) ->
                                item {
                                    val isSelected = iconSelected == name
                                    Surface(
                                        onClick = { iconSelected = name },
                                        shape = CircleShape,
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = iconVector,
                                                contentDescription = name,
                                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (isAssetOrLiability) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1.0f)) {
                                        Text(
                                            "Real-Time Tracking",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                        Text(
                                            "Onboard starting balance & auto-sync future SMS.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                        )
                                    }
                                    Switch(
                                        checked = enableRealTimeTracking,
                                        onCheckedChange = { enableRealTimeTracking = it },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.secondary,
                                            checkedTrackColor = MaterialTheme.colorScheme.secondaryContainer
                                        ),
                                        modifier = Modifier.testTag("real_time_tracking_switch")
                                    )
                                }

                                AnimatedVisibility(visible = enableRealTimeTracking) {
                                    Column(
                                        modifier = Modifier.padding(top = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Divider(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f))
                                        Spacer(modifier = Modifier.height(4.dp))

                                        val activeBalanceLabel = if (selectedMainCategory == "Loans") "Outstanding Principal" else "Starting Ledger Balance"
                                        val prefixSymbol = if (selectedMainCategory == "Loans") "-" else "₹ "
                                        
                                        OutlinedTextField(
                                            value = initialBalanceStr,
                                            onValueChange = { initialBalanceStr = it },
                                            label = { Text(activeBalanceLabel) },
                                            leadingIcon = { 
                                                Text(
                                                    prefixSymbol, 
                                                    fontWeight = FontWeight.Bold, 
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(start = 12.dp)
                                                ) 
                                            },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth().testTag("starting_balance_input"),
                                            singleLine = true
                                        )

                                        OutlinedTextField(
                                            value = lastFourDigits,
                                            onValueChange = { input ->
                                                if (input.all { char -> char.isDigit() || char == ',' || char == ' ' || char == '/' }) {
                                                    lastFourDigits = input
                                                }
                                            },
                                            label = { Text("Account & Debit Card Last Digits") },
                                            placeholder = { Text("e.g. 5678, 1234 (Account #, Debit Card)") },
                                            supportingText = { Text("Enter Account # and Debit Card last digits separated by comma", fontSize = 10.sp) },
                                            leadingIcon = { Icon(Icons.Default.Pin, contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth().testTag("last_four_digits_input"),
                                            singleLine = true
                                        )

                                        Column {
                                            Text(
                                                "Account Ownership", 
                                                style = MaterialTheme.typography.labelSmall, 
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.padding(bottom = 6.dp)
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                val owners = listOf(
                                                    Triple("User 1", partnerAName, Icons.Default.Person),
                                                    Triple("User 2", partnerBName, Icons.Default.Person),
                                                    Triple("Combined", "Family", Icons.Default.Group)
                                                )
                                                owners.forEach { (v, lbl, icon) ->
                                                    val isSel = owner == v
                                                    Surface(
                                                        onClick = { owner = v },
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                                        border = androidx.compose.foundation.BorderStroke(
                                                            1.5.dp,
                                                            if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                                        ),
                                                        modifier = Modifier.weight(1f).testTag("ownership_radio_row_$v")
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.Center,
                                                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = icon,
                                                                contentDescription = null,
                                                                tint = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text(
                                                                text = lbl, 
                                                                fontSize = 11.sp, 
                                                                fontWeight = FontWeight.Bold,
                                                                color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        val dateText = remember(asOfDate) {
                                            val d = Date(asOfDate)
                                            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(d)
                                        }
                                        OutlinedTextField(
                                            value = dateText,
                                            onValueChange = {},
                                            label = { Text("Balance Effective Date") },
                                            readOnly = true,
                                            shape = RoundedCornerShape(12.dp),
                                            leadingIcon = { Icon(Icons.Default.Event, contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
                                            trailingIcon = {
                                                IconButton(
                                                    onClick = {
                                                        DatePickerDialog(
                                                            context,
                                                            { _, year, month, day ->
                                                                calendar.set(year, month, day)
                                                                asOfDate = calendar.timeInMillis
                                                            },
                                                            calendar.get(Calendar.YEAR),
                                                            calendar.get(Calendar.MONTH),
                                                            calendar.get(Calendar.DAY_OF_MONTH)
                                                        ).show()
                                                    },
                                                    modifier = Modifier.testTag("date_picker_button")
                                                ) {
                                                    Icon(Icons.Default.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().testTag("balance_effective_date_field")
                                        )

                                        AnimatedVisibility(visible = selectedMainCategory == "Loans") {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                                    .padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    "Loan Configuration (Auto-Interest Splits)", 
                                                    fontSize = 11.sp, 
                                                    fontWeight = FontWeight.Bold, 
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                                
                                                OutlinedTextField(
                                                    value = annualRateStr,
                                                    onValueChange = { annualRateStr = it },
                                                    label = { Text("Annual Interest Rate (%)") },
                                                    placeholder = { Text("e.g. 11.5") },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier.fillMaxWidth().testTag("annual_rate_input"),
                                                    singleLine = true
                                                )
                                                OutlinedTextField(
                                                    value = emiStr,
                                                    onValueChange = { emiStr = it },
                                                    label = { Text("Monthly EMI Amount") },
                                                    placeholder = { Text("e.g. 5000") },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier.fillMaxWidth().testTag("emi_amount_input"),
                                                    singleLine = true
                                                )
                                                OutlinedTextField(
                                                    value = remainingMonthsStr,
                                                    onValueChange = { remainingMonthsStr = it },
                                                    label = { Text("Remaining Tenure (Months)") },
                                                    placeholder = { Text("e.g. 24") },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier.fillMaxWidth().testTag("remaining_months_input"),
                                                    singleLine = true
                                                )
                                            }
                                        }

                                        AnimatedVisibility(visible = selectedMainCategory == "Credit Cards") {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                                    .padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    "Credit Card Bill Configuration", 
                                                    fontSize = 11.sp, 
                                                    fontWeight = FontWeight.Bold, 
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                
                                                OutlinedTextField(
                                                     value = billingCycleDayStr,
                                                     onValueChange = { input ->
                                                         if (input.isEmpty() || (input.all { it.isDigit() } && (input.toIntOrNull() ?: 0) <= 31)) {
                                                             billingCycleDayStr = input
                                                         }
                                                     },
                                                     label = { Text("Billing Cycle Date (Day of Month)") },
                                                     placeholder = { Text("e.g. 15") },
                                                     keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                     shape = RoundedCornerShape(10.dp),
                                                     modifier = Modifier.fillMaxWidth().testTag("billing_cycle_day_input"),
                                                     singleLine = true
                                                )

                                                OutlinedTextField(
                                                     value = paymentDueDayStr,
                                                     onValueChange = { input ->
                                                         if (input.isEmpty() || (input.all { it.isDigit() } && (input.toIntOrNull() ?: 0) <= 31)) {
                                                             paymentDueDayStr = input
                                                         }
                                                     },
                                                     label = { Text("Payment Due Date (Day of Month)") },
                                                     placeholder = { Text("e.g. 5") },
                                                     keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                     shape = RoundedCornerShape(10.dp),
                                                     modifier = Modifier.fillMaxWidth().testTag("payment_due_day_input"),
                                                     singleLine = true
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

// ------------------------------------------------------------------------------------------------
// MODERN SEARCHABLE COA PICKERS (DESIGNED FOR SCALABLE & HIERARCHICAL CHART OF ACCOUNTS)
// ------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchableAccountPickerBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    accounts: List<Account>,
    selectedAccountId: String,
    onAccountSelected: (Account) -> Unit,
    title: String = "Select Account",
    subtitle: String = "Choose an account from your active ledgers",
    excludedAccountId: String? = null,
    onAddNewAccount: (() -> Unit)? = null
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var selectedGroupFilter by remember { mutableStateOf("ALL") }

    val eligibleAccounts = remember(accounts, excludedAccountId) {
        if (excludedAccountId != null) {
            accounts.filter { it.id != excludedAccountId }
        } else {
            accounts
        }
    }

    // Group tabs (Bank Accounts, Credit Cards, Investments, Cash, Loans, Equity)
    val availableGroups = remember(eligibleAccounts) {
        val groups = eligibleAccounts.map { it.mainCategory }.filter { it.isNotBlank() }.distinct()
        listOf("ALL") + groups
    }

    val filteredAccounts = remember(eligibleAccounts, searchQuery, selectedGroupFilter) {
        eligibleAccounts.filter { acc ->
            val matchesGroup = if (selectedGroupFilter == "ALL") true else acc.mainCategory.equals(selectedGroupFilter, ignoreCase = true)
            val q = searchQuery.trim().lowercase()
            val matchesQuery = if (q.isEmpty()) true else {
                acc.name.lowercase().contains(q) ||
                acc.mainCategory.lowercase().contains(q) ||
                acc.accountClass.lowercase().contains(q) ||
                acc.lastFourDigits.contains(q) ||
                acc.ownerFirstName.lowercase().contains(q)
            }
            matchesGroup && matchesQuery
        }
    }

    val groupedAccounts = remember(filteredAccounts) {
        filteredAccounts.groupBy { it.mainCategory.ifBlank { "Other Ledgers" } }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("account_picker_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("account_picker_close_btn")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("account_picker_search_input"),
                placeholder = { Text("Search account name, bank, last 4 digits...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                )
            )

            // Category Group Filter Chips
            if (availableGroups.size > 2) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(availableGroups) { group ->
                        val isSelected = selectedGroupFilter == group
                        val count = if (group == "ALL") eligibleAccounts.size else eligibleAccounts.count { it.mainCategory.equals(group, ignoreCase = true) }
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedGroupFilter = group },
                            label = {
                                Text(
                                    text = if (group == "ALL") "All ($count)" else "$group ($count)",
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("account_picker_group_$group")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Account List Grouped by Main Category
            if (filteredAccounts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No accounts match '$searchQuery'",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    groupedAccounts.forEach { (mainCategory, accList) ->
                        item(key = "header_$mainCategory") {
                            Text(
                                text = mainCategory.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                            )
                        }

                        items(accList, key = { it.id }) { acc ->
                            val isSelected = acc.id == selectedAccountId
                            val icon = getCategoryIcon(acc.name, acc.iconName)
                            val isLiability = acc.accountClass.uppercase() == "LIABILITY" || acc.mainCategory == "Credit Cards" || acc.mainCategory == "Loans"
                            val balanceColor = if (isLiability || acc.balance < 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)

                            Surface(
                                onClick = {
                                    onAccountSelected(acc)
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (isSelected) 1.5.dp else 1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("account_picker_item_${acc.id}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }

                                        Column {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = acc.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (acc.lastFourDigits.isNotBlank()) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                                                    ) {
                                                        Text(
                                                            text = "••${acc.lastFourDigits}",
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.padding(top = 2.dp)
                                            ) {
                                                Text(
                                                    text = acc.mainCategory,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                if (acc.ownerFirstName.isNotBlank()) {
                                                    Text(
                                                        text = "• ${acc.ownerFirstName}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = formatAmount(if (isLiability) Math.abs(acc.balance) else acc.balance),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = balanceColor
                                        )
                                        if (isSelected) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                                modifier = Modifier.padding(top = 2.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Text(
                                                    text = "Selected",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Optional Add New Account Button
            if (onAddNewAccount != null) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onAddNewAccount()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("account_picker_add_new_btn"),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("+ Configure / Add New Account", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchableCategoryPickerBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    categories: List<Account>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    transactionType: TransactionType = TransactionType.EXPENSE,
    title: String = "Select Category",
    onAddNewCategory: (() -> Unit)? = null
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    val targetClass = if (transactionType == TransactionType.INCOME) "INCOME" else "EXPENSE"

    // Extract categories matching class
    val eligibleCategoryList = remember(categories, transactionType) {
        val filtered = categories.filter {
            it.accountClass.equals(targetClass, ignoreCase = true) ||
            it.accountType.equals(targetClass, ignoreCase = true) ||
            (it.accountClass.isBlank() && it.accountType.isBlank())
        }.filter { it.name.isNotBlank() && !it.name.equals("Transfer", ignoreCase = true) }

        if (filtered.isNotEmpty()) {
            filtered
        } else {
            // Fallback default list if no DB categories loaded
            val fallbackNames = if (transactionType == TransactionType.INCOME) {
                listOf("Salary", "Investment Return", "Refund", "Gifts", "Rental Income", "Business Revenue", "Others")
            } else {
                listOf("Food & Dining", "Groceries", "Utilities", "Housing", "Shopping", "Travel", "Entertainment", "Healthcare", "Finance: Loan Interest", "Others")
            }
            fallbackNames.map { name ->
                Account(
                    accountId = name,
                    accountName = name,
                    accountType = targetClass,
                    customAccountClass = targetClass,
                    customMainCategory = if (transactionType == TransactionType.INCOME) "Income" else "Expense",
                    customSubCategory = name,
                    iconName = name
                )
            }
        }
    }

    var selectedGroupFilter by remember { mutableStateOf("ALL") }
    val availableGroups = remember(eligibleCategoryList) {
        val groups = eligibleCategoryList.map { it.mainCategory }.filter { it.isNotBlank() }.distinct()
        listOf("ALL") + groups
    }

    val filteredCategories = remember(eligibleCategoryList, searchQuery, selectedGroupFilter) {
        eligibleCategoryList.filter { cat ->
            val matchesGroup = if (selectedGroupFilter == "ALL") true else cat.mainCategory.equals(selectedGroupFilter, ignoreCase = true)
            val q = searchQuery.trim().lowercase()
            val matchesQuery = if (q.isEmpty()) true else {
                cat.name.lowercase().contains(q) ||
                cat.mainCategory.lowercase().contains(q) ||
                cat.accountClass.lowercase().contains(q)
            }
            matchesGroup && matchesQuery
        }
    }

    val groupedCategories = remember(filteredCategories) {
        filteredCategories.groupBy { it.mainCategory.ifBlank { if (transactionType == TransactionType.INCOME) "Income Categories" else "Expense Categories" } }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.testTag("category_picker_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (transactionType == TransactionType.INCOME) "Assign an Income ledger stream" else "Categorize this expense for analytics & budgeting",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("category_picker_close_btn")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search Box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("category_picker_search_input"),
                placeholder = { Text("Search category, food, shopping, bills...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                )
            )

            // Group Chips
            if (availableGroups.size > 2) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(availableGroups) { group ->
                        val isSelected = selectedGroupFilter == group
                        val count = if (group == "ALL") eligibleCategoryList.size else eligibleCategoryList.count { it.mainCategory.equals(group, ignoreCase = true) }
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedGroupFilter = group },
                            label = {
                                Text(
                                    text = if (group == "ALL") "All ($count)" else "$group ($count)",
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("category_picker_group_$group")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Category List Grouped
            if (filteredCategories.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No categories match '$searchQuery'",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    groupedCategories.forEach { (mainCategory, catList) ->
                        item(key = "header_$mainCategory") {
                            Text(
                                text = mainCategory.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                            )
                        }

                        items(catList, key = { it.id.ifBlank { it.name } }) { cat ->
                            val isSelected = cat.name.equals(selectedCategory, ignoreCase = true)
                            val catColor = getCategoryColor(cat.name)
                            val icon = getCategoryIcon(cat.name, cat.iconName)

                            Surface(
                                onClick = {
                                    onCategorySelected(cat.name)
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = androidx.compose.foundation.BorderStroke(
                                    if (isSelected) 1.5.dp else 1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("category_picker_item_${cat.name}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = catColor.copy(alpha = 0.15f),
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = null,
                                                    tint = catColor,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }

                                        Column {
                                            Text(
                                                text = cat.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = cat.mainCategory,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Optional Add New Category Button
            if (onAddNewCategory != null) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onAddNewCategory()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("category_picker_add_new_btn"),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("+ Add / Create New Category", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AccountPickerField(
    label: String,
    selectedAccount: Account?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "Select account..."
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("account_picker_field")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedAccount != null) {
                    val icon = getCategoryIcon(selectedAccount.name, selectedAccount.iconName)
                    val isLiability = selectedAccount.accountClass.uppercase() == "LIABILITY" || selectedAccount.mainCategory == "Credit Cards" || selectedAccount.mainCategory == "Loans"
                    val balanceColor = if (isLiability || selectedAccount.balance < 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            modifier = Modifier.size(30.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = selectedAccount.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (selectedAccount.lastFourDigits.isNotBlank()) {
                                    Text(
                                        text = "(xx${selectedAccount.lastFourDigits})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            Text(
                                text = selectedAccount.mainCategory,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = formatAmount(if (isLiability) Math.abs(selectedAccount.balance) else selectedAccount.balance),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = balanceColor
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Open account selector",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Open account selector",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryPickerField(
    label: String,
    selectedCategory: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onQuickSelect: ((String) -> Unit)? = null,
    quickSuggestions: List<String> = emptyList()
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            if (enabled) {
                Text(
                    text = "Browse all ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onClick() }
                        .padding(bottom = 4.dp)
                )
            }
        }

        // Active Category Card
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("category_picker_field")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val catColor = getCategoryColor(selectedCategory)
                val icon = getCategoryIcon(selectedCategory)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = catColor.copy(alpha = 0.15f),
                        modifier = Modifier.size(30.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = catColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Text(
                        text = selectedCategory.ifBlank { "Select Category" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Open category selector",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Quick Suggestions Row (if any)
        if (quickSuggestions.isNotEmpty() && onQuickSelect != null && enabled) {
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(quickSuggestions) { quickCat ->
                    val isSelected = quickCat.equals(selectedCategory, ignoreCase = true)
                    Surface(
                        onClick = { onQuickSelect(quickCat) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                        )
                    ) {
                        Text(
                            text = quickCat,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
