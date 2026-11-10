package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.Account
import com.example.domain.FreeToSpendRules
import com.example.domain.NetWorthRules
import com.example.domain.NetWorthRules.Scope
import com.example.ui.viewmodel.ExpenseViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val SliceColors = listOf(
    Color(0xFF14B8A6), Color(0xFF6366F1), Color(0xFFF59E0B), Color(0xFFEC4899),
    Color(0xFF0EA5E9), Color(0xFF84CC16), Color(0xFF8B5CF6), Color(0xFF64748B)
)

/** Trend ranges in months; 0 means all the history there is. */
private val Ranges = listOf(6 to "6M", 12 to "1Y", 36 to "3Y", 0 to "All")

/**
 * Net worth dashboard: yours, your partner's or both together, how it moved month by month,
 * and how it is spread across kinds of accounts. Joint accounts count half for each partner.
 */
@Composable
fun NetWorthScreen(viewModel: ExpenseViewModel, onBackClick: () -> Unit) {
    BackHandler { onBackClick() }
    val accounts by viewModel.accounts.collectAsState()
    val txs by viewModel.transactions.collectAsState()
    val myUid by viewModel.currentUserUid.collectAsState()
    val myName = viewModel.currentUserFirstName.collectAsState().value ?: "You"
    val partnerName = viewModel.spouseUserFirstName.collectAsState().value ?: "Partner"

    var scope by rememberSaveable { mutableStateOf(Scope.MINE) }
    var range by rememberSaveable { mutableIntStateOf(12) }
    val now = remember { System.currentTimeMillis() }

    val ownerName: (Account) -> String = { acc ->
        when {
            acc.ownerUserId.isBlank() || acc.ownerUserId.equals(FreeToSpendRules.SHARED, true) -> "Joint"
            acc.ownerUserId == myUid -> myName
            else -> partnerName
        }
    }
    val available = rememberInBackground(12, accounts, txs) { NetWorthRules.monthsAvailable(accounts, txs, now) }
    val months = (if (range == 0) available else minOf(range, available)).coerceIn(2, 120)
    val summary = rememberInBackground<NetWorthRules.Summary?>(null, accounts, txs, myUid, scope, months, myName, partnerName) {
        NetWorthRules.summarize(accounts, txs, myUid, scope, months, now, ownerName)
    }
    // Accounts in this view, with joint balances halved for one partner so the list adds up to the total.
    val shownAccounts = remember(accounts, myUid, scope) {
        accounts.filter { NetWorthRules.countsForNetWorth(it) }.mapNotNull { acc ->
            val share = NetWorthRules.share(acc, scope, myUid)
            when {
                share <= 0.0 -> null
                share < 1.0 -> acc.copy(balance = acc.balance * share)
                else -> acc
            }
        }
    }
    val scopeLabel = when (scope) {
        Scope.MINE -> myName
        Scope.PARTNER -> partnerName
        Scope.COMBINED -> "Combined"
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                IconButton(onClick = onBackClick) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                Text("Net worth", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(selected = scope == Scope.MINE, onClick = { scope = Scope.MINE }, label = { Text(myName) })
                FilterChip(selected = scope == Scope.PARTNER, onClick = { scope = Scope.PARTNER }, label = { Text(partnerName) })
                FilterChip(selected = scope == Scope.COMBINED, onClick = { scope = Scope.COMBINED }, label = { Text("Combined") })
            }
        }

        val s = summary
        if (s == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            return@LazyColumn
        }

        item { HeadlineCard(scopeLabel, s, jointNote = scope != Scope.COMBINED) }
        item {
            TrendCard(
                points = s.history,
                range = range,
                onRange = { range = it }
            )
        }
        item { AllocationCard(s, showOwners = scope == Scope.COMBINED) }
        item {
            AccountsOverviewCard(
                title = "$scopeLabel accounts",
                accounts = shownAccounts,
                ownerLabel = { acc ->
                    val name = ownerName(acc)
                    if (name == "Joint" && scope != Scope.COMBINED) "Joint, half" else name
                }
            )
        }
    }
}

@Composable
private fun NetWorthCard(tag: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag(tag),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun HeadlineCard(label: String, s: NetWorthRules.Summary, jointNote: Boolean) {
    val prev = s.history.getOrNull(s.history.size - 2)?.value
    val change = prev?.let { s.netWorth - it }
    NetWorthCard("net_worth_headline") {
        Text("NET WORTH · ${label.uppercase()}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            formatAmount(s.netWorth),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = if (s.netWorth < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("net_worth_val")
        )
        if (change != null) {
            ChangeText(change, prev, "this month")
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Own", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatAmount(s.assets), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text("Owe", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatAmount(s.owed), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            }
        }
        if (jointNote) {
            Text(
                "Joint accounts count half for each of you.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ChangeText(change: Double, base: Double, period: String) {
    val up = change >= 0
    val pct = if (abs(base) >= 1.0) " (${if (up) "+" else "−"}${"%.1f".format(abs(change / abs(base)) * 100)}%)" else ""
    Text(
        "${if (up) "▲" else "▼"} ${formatAmount(abs(change))}$pct $period",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (up) Color(0xFF0F9F6E) else MaterialTheme.colorScheme.error
    )
}

private fun monthLabel(time: Long, withYear: Boolean = true): String =
    SimpleDateFormat(if (withYear) "MMM yy" else "MMM", Locale.getDefault()).format(Date(time))

/** ₹12.3K, ₹4.5L, ₹1.2Cr: short amounts for the chart's scale. */
internal fun formatCompact(amount: Double): String {
    val a = abs(amount)
    val sign = if (amount < 0) "−" else ""
    return sign + when {
        a >= 1e7 -> "₹%.1fCr".format(a / 1e7)
        a >= 1e5 -> "₹%.1fL".format(a / 1e5)
        a >= 1e3 -> "₹%.0fK".format(a / 1e3)
        else -> "₹%.0f".format(a)
    }
}

@Composable
private fun TrendCard(points: List<NetWorthRules.Point>, range: Int, onRange: (Int) -> Unit) {
    var selected by remember(points) { mutableIntStateOf(points.lastIndex) }
    NetWorthCard("net_worth_trend") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Ranges.forEach { (months, label) ->
                val on = range == months
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .pointerInput(months) { detectTapGestures { onRange(months) } }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        if (points.size < 2) {
            Text("Not enough history yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        } else {
            TrendBody(points, selected) { selected = it }
        }
    }
}

@Composable
private fun TrendBody(points: List<NetWorthRules.Point>, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        val sel = points[selected.coerceIn(0, points.lastIndex)]
        Spacer(Modifier.height(8.dp))
        Text(
            "${SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(sel.monthStart))}: ${formatAmount(sel.value)}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        ChangeText(points.last().value - points.first().value, points.first().value, "over ${points.size - 1} months")
        Spacer(Modifier.height(8.dp))
        LineChart(points, selected, onSelect)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val mid = points[points.size / 2]
            listOf(points.first(), mid, points.last()).forEach {
                Text(monthLabel(it.monthStart), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LineChart(points: List<NetWorthRules.Point>, selected: Int, onSelect: (Int) -> Unit) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val zero = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
    val values = points.map { it.value }
    val rawMin = values.min()
    val rawMax = values.max()
    val pad = ((rawMax - rawMin) * 0.12).takeIf { it > 0 } ?: (abs(rawMax) * 0.1).coerceAtLeast(1.0)
    val lo = rawMin - pad
    val hi = rawMax + pad

    fun indexAt(x: Float, width: Float) =
        ((x / width) * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)

    Box(Modifier.fillMaxWidth().height(180.dp)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(points) { detectTapGestures { onSelect(indexAt(it.x, size.width.toFloat())) } }
                .pointerInput(points) {
                    detectHorizontalDragGestures { change, _ -> onSelect(indexAt(change.position.x, size.width.toFloat())) }
                }
        ) {
            val w = size.width
            val h = size.height
            fun x(i: Int) = if (points.size == 1) w / 2 else w * i / (points.size - 1)
            fun y(v: Double) = (h * (1 - (v - lo) / (hi - lo))).toFloat()

            for (k in 0..3) {
                val gy = h * k / 3
                drawLine(grid.copy(alpha = 0.5f), Offset(0f, gy), Offset(w, gy), strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }
            if (lo < 0 && hi > 0) {
                drawLine(zero, Offset(0f, y(0.0)), Offset(w, y(0.0)), strokeWidth = 1.5f)
            }

            val path = Path()
            values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            val area = Path().apply {
                addPath(path)
                lineTo(x(points.lastIndex), h)
                lineTo(x(0), h)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.28f), line.copy(alpha = 0.02f))))
            drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            val si = selected.coerceIn(0, points.lastIndex)
            val sx = x(si)
            val sy = y(values[si])
            drawLine(line.copy(alpha = 0.4f), Offset(sx, 0f), Offset(sx, h), strokeWidth = 1.5f)
            drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(sx, sy))
            drawCircle(line, radius = 4.dp.toPx(), center = Offset(sx, sy))
        }
        Text(formatCompact(rawMax), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.TopEnd))
        Text(formatCompact(rawMin), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun AllocationCard(s: NetWorthRules.Summary, showOwners: Boolean) {
    var byOwner by rememberSaveable { mutableStateOf(false) }
    val owners = showOwners && byOwner
    val slices = if (owners) s.byOwner else s.byKind
    NetWorthCard("net_worth_allocation") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Where it is", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (showOwners) {
                FilterChip(selected = !byOwner, onClick = { byOwner = false }, label = { Text("By type") })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = byOwner, onClick = { byOwner = true }, label = { Text("By person") })
            }
        }
        if (slices.isEmpty()) {
            Text("No assets yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 22.dp.toPx()
                        val gap = if (slices.size > 1) 2f else 0f
                        var start = -90f
                        slices.forEachIndexed { i, sl ->
                            val sweep = (sl.share * 360).toFloat()
                            drawArc(
                                SliceColors[i % SliceColors.size],
                                startAngle = start + gap / 2,
                                sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                                useCenter = false,
                                topLeft = Offset(stroke / 2, stroke / 2),
                                size = Size(size.width - stroke, size.height - stroke),
                                style = Stroke(width = stroke)
                            )
                            start += sweep
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (owners) "Net" else "Assets", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatCompact(slices.sumOf { it.amount }), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    slices.forEachIndexed { i, sl -> LegendRow(SliceColors[i % SliceColors.size], sl) }
                }
            }
        }
        if (!owners && s.owedByKind.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text("What's owed", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            s.owedByKind.forEach { sl ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sl.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(formatAmount(sl.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                }
                LinearProgressIndicator(
                    progress = { sl.share.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.error,
                    trackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                )
            }
            if (s.assets > 0) {
                Text(
                    "You owe ${"%.0f".format(s.owed / s.assets * 100)}% of what you own.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun LegendRow(color: Color, slice: NetWorthRules.Slice) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Text(slice.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(formatCompact(slice.amount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${(slice.share * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}
