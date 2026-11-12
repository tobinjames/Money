package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RequestQuote
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.Account
import com.example.data.AccountType
import com.example.data.FirebaseService
import com.example.data.Transaction
import com.example.data.TransactionType
import com.example.domain.LedgerRules
import com.example.ui.viewmodel.ExpenseViewModel
import kotlin.math.abs

// ------------------------------------------------------------------------------------------------
// Shared vocabulary for accounts and categories, so every screen names and orders them the same way.
// ------------------------------------------------------------------------------------------------

/** The kinds of real-money account, in the order they are listed everywhere. */
enum class AccountKind(
    val label: String,
    val groupTitle: String,
    val accountClass: String,
    val mainCategory: String,
    val type: AccountType,
    val icon: ImageVector
) {
    BANK("Bank account", "Bank & cash", "ASSET", "Bank Accounts", AccountType.SAVINGS_ACCOUNT, Icons.Default.AccountBalance),
    CASH("Cash", "Bank & cash", "ASSET", "Cash", AccountType.SAVINGS_ACCOUNT, Icons.Default.AccountBalanceWallet),
    CARD("Credit card", "Credit cards", "LIABILITY", "Credit Cards", AccountType.CREDIT_CARD, Icons.Default.CreditCard),
    LOAN("Loan", "Loans", "LIABILITY", "Loans", AccountType.LOAN, Icons.Default.RequestQuote),
    FIXED_DEPOSIT("Fixed deposit", "Investments", "ASSET", "Fixed Deposits", AccountType.FIXED_DEPOSIT, Icons.Default.Savings),
    MUTUAL_FUND("Mutual fund", "Investments", "ASSET", "Mutual Funds", AccountType.MUTUAL_FUND, Icons.Default.TrendingUp)
}

val accountGroupOrder = listOf("Bank & cash", "Credit cards", "Loans", "Investments")

fun accountKindOf(account: Account): AccountKind = when {
    account.accountType == "LIABILITY" && account.type == AccountType.LOAN -> AccountKind.LOAN
    account.accountType == "LIABILITY" -> AccountKind.CARD
    account.type == AccountType.FIXED_DEPOSIT -> AccountKind.FIXED_DEPOSIT
    account.type == AccountType.MUTUAL_FUND -> AccountKind.MUTUAL_FUND
    account.mainCategory.equals("Cash", ignoreCase = true) -> AccountKind.CASH
    else -> AccountKind.BANK
}

fun accountIcon(account: Account): ImageVector = accountKindOf(account).icon

fun isJointOwner(ownerUserId: String): Boolean =
    ownerUserId.isBlank() || ownerUserId.equals("SHARED", true) || ownerUserId.equals("Combined", true)

/** "Yours", the partner's name, or "Joint". */
fun accountOwnerLabel(account: Account, myUid: String?, partnerName: String?): String = when {
    isJointOwner(account.ownerUserId) -> "Joint"
    account.ownerUserId == myUid -> "Yours"
    else -> partnerName?.takeIf { it.isNotBlank() } ?: "Partner"
}

/** "Yours · ••1234": who owns the account and the first of its saved last digits. */
fun accountSubtitle(account: Account, myUid: String?, partnerName: String?): String {
    val digits = account.lastFourDigits.split(',', ' ', '/').firstOrNull { it.isNotBlank() }
    return listOfNotNull(accountOwnerLabel(account, myUid, partnerName), digits?.let { "••$it" }).joinToString(" · ")
}

/** Real accounts in display order: bank & cash, cards, loans, investments. */
fun groupAccounts(accounts: List<Account>): List<Pair<String, List<Account>>> {
    val byGroup = accounts.groupBy { accountKindOf(it).groupTitle }
    return accountGroupOrder.mapNotNull { title ->
        byGroup[title]?.sortedBy { it.name.lowercase() }?.let { title to it }
    }
}

private val plainGroupNames = setOf(
    "", "general", "indirect expenses", "direct expenses", "direct income", "indirect income", "expense", "income"
)

/** Category group as shown to people: the built-in bookkeeping groups all read as "General". */
fun displayCategoryGroup(raw: String): String =
    if (raw.trim().lowercase() in plainGroupNames) "General" else raw.trim()

/**
 * The categories used most in the last 90 days for this kind of transaction, most used first,
 * so the likely pick is one tap away.
 */
fun topCategories(
    transactions: List<Transaction>,
    categories: List<Account>,
    type: TransactionType,
    limit: Int = 6
): List<String> {
    val targetClass = if (type == TransactionType.INCOME) "INCOME" else "EXPENSE"
    val names = categories.filter { it.accountType.equals(targetClass, true) || it.accountClass.equals(targetClass, true) }
        .map { it.name.trim() }.filter { it.isNotBlank() }
    val canonical = names.associateBy { it.lowercase() }
    val since = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
    val used = transactions.asSequence()
        .filter { LedgerRules.isCounted(it) && it.type == type && it.timestamp >= since }
        .mapNotNull { canonical[it.category.trim().lowercase()] }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.map { it.key }
    return (used + names).distinct().take(limit)
}

@Composable
private fun IconBubble(icon: ImageVector, tint: Color, size: Int = 36) {
    Surface(shape = CircleShape, color = tint.copy(alpha = 0.13f), modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size / 2).dp))
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SheetSearchField(value: String, onChange: (String) -> Unit, placeholder: String, tag: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag(tag)
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun AddRow(text: String, tag: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Default.Add, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Balance as people say it: "₹5,000", or "₹5,000 owed" for cards and loans. */
@Composable
private fun AccountBalanceText(account: Account) {
    val isLiability = account.accountType == "LIABILITY"
    val owes = isLiability && abs(account.balance) > 0.5
    Text(
        formatAmount(if (isLiability) abs(account.balance) else account.balance) + if (owes) " owed" else "",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (owes || account.balance < -0.5 && !isLiability) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        maxLines = 1
    )
}

// ------------------------------------------------------------------------------------------------
// Account picker
// ------------------------------------------------------------------------------------------------

/**
 * Pick the account a transaction was paid from (or moved to). Accounts are listed under
 * "Bank & cash", "Credit cards", "Loans" and "Investments" with owner and balance. Search only
 * appears once the list is long. With a [viewModel], a new account can be added on the spot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchableAccountPickerBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    accounts: List<Account>,
    selectedAccountId: String,
    onAccountSelected: (Account) -> Unit,
    title: String = "Choose account",
    subtitle: String = "",
    excludedAccountId: String? = null,
    viewModel: ExpenseViewModel? = null
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val myUid by FirebaseService.currentUserUid.collectAsState()
    val partnerName by FirebaseService.spouseUserFirstName.collectAsState()
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }

    val eligible = remember(accounts, excludedAccountId) { accounts.filter { it.id != excludedAccountId } }
    val groups = remember(eligible, query) {
        val q = query.trim().lowercase()
        groupAccounts(
            eligible.filter {
                q.isEmpty() || it.name.lowercase().contains(q) || it.lastFourDigits.contains(q) ||
                    accountKindOf(it).label.lowercase().contains(q)
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("account_picker_bottom_sheet")
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
            SheetHeader(title, subtitle)
            if (eligible.size > 7) {
                SheetSearchField(query, { query = it }, "Search by name or last 4 digits", "account_picker_search_input")
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                if (groups.isEmpty()) {
                    item {
                        Text(
                            if (query.isBlank()) "No accounts yet." else "No account matches \"${query.trim()}\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp)
                        )
                    }
                }
                groups.forEach { (groupTitle, list) ->
                    item(key = "h_$groupTitle") { SectionLabel(groupTitle) }
                    items(list, key = { it.id }) { acc ->
                        val selected = acc.id == selectedAccountId
                        Surface(
                            onClick = { onAccountSelected(acc); onDismiss() },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent,
                            modifier = Modifier.fillMaxWidth().testTag("account_picker_item_${acc.id}")
                        ) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconBubble(accountIcon(acc), MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        acc.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        accountSubtitle(acc, myUid, partnerName),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                AccountBalanceText(acc)
                                if (selected) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
                if (viewModel != null) {
                    item(key = "add_account") {
                        Spacer(Modifier.height(6.dp))
                        AddRow("Add an account", "account_picker_add_new_btn") { adding = true }
                    }
                }
            }
        }
    }

    if (adding && viewModel != null) {
        AccountEditorSheet(viewModel = viewModel, existing = null, onDismiss = { adding = false })
    }
}

// ------------------------------------------------------------------------------------------------
// Category picker
// ------------------------------------------------------------------------------------------------

/**
 * Pick a category. The ones used most lately come first as chips, then every category under its
 * group. Typing filters the list, and with a [viewModel] a name that doesn't exist yet can be
 * created and picked in one go. For income, spending categories are offered too, as refunds.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchableCategoryPickerBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    categories: List<Account>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    transactionType: TransactionType = TransactionType.EXPENSE,
    title: String = "Choose category",
    viewModel: ExpenseViewModel? = null,
    suggestions: List<String> = emptyList()
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val isIncome = transactionType == TransactionType.INCOME
    val targetClass = if (isIncome) "INCOME" else "EXPENSE"

    fun isClass(a: Account, cls: String) = a.accountClass.equals(cls, true) || a.accountType.equals(cls, true)

    val own = remember(categories, targetClass) {
        val real = categories.filter { isClass(it, targetClass) && it.name.isNotBlank() && !it.name.equals("Transfer", true) }
        real.ifEmpty {
            // Nothing loaded yet: offer a sensible starter list.
            val names = if (isIncome) listOf("Salary", "Investment Return", "Refund", "Gifts", "Others")
            else listOf("Food", "Groceries", "Utilities", "Housing", "Shopping", "Travel", "Entertainment", "Healthcare", "Others")
            names.map { Account(accountId = it, accountName = it, accountType = targetClass, iconName = it) }
        }
    }
    // Income filed under a spending category is a refund: it gives that budget back.
    val refundable = remember(categories, isIncome) {
        if (!isIncome) emptyList()
        else categories.filter { isClass(it, "EXPENSE") && it.name.isNotBlank() && !it.name.equals("Transfer", true) }
    }

    val q = query.trim().lowercase()
    fun matches(a: Account) = q.isEmpty() || a.name.lowercase().contains(q) || a.mainCategory.lowercase().contains(q)

    val ownGroups = remember(own, q) {
        own.filter { matches(it) }
            .groupBy { displayCategoryGroup(it.mainCategory) }
            .toSortedMap(compareBy<String> { it != "General" }.thenBy { it.lowercase() })
            .mapValues { (_, v) -> v.sortedBy { it.name.lowercase() } }
    }
    val refundMatches = remember(refundable, q) { refundable.filter { matches(it) }.sortedBy { it.name.lowercase() } }
    val nothingFound = ownGroups.isEmpty() && refundMatches.isEmpty()
    val exactExists = (own + refundable).any { it.name.trim().lowercase() == q }
    val shownSuggestions = remember(suggestions, own) {
        suggestions.filter { s -> own.any { it.name.equals(s, true) } }.take(6)
    }

    fun pick(name: String) { onCategorySelected(name); onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("category_picker_bottom_sheet")
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
            SheetHeader(title, if (isIncome) "For a refund, pick the spending category it came from." else "")
            SheetSearchField(query, { query = it }, "Search categories", "category_picker_search_input")

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                if (viewModel != null && q.isNotEmpty() && !exactExists) {
                    item(key = "create_from_search") {
                        Spacer(Modifier.height(6.dp))
                        AddRow("Create \"${query.trim()}\"", "category_picker_create_btn") { creating = true }
                    }
                }
                if (q.isEmpty() && shownSuggestions.isNotEmpty()) {
                    item(key = "suggested") {
                        SectionLabel("Used most")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            shownSuggestions.forEach { name ->
                                val cat = own.firstOrNull { it.name.equals(name, true) }
                                CategoryChip(
                                    name = name,
                                    iconName = cat?.iconName.orEmpty(),
                                    selected = name.equals(selectedCategory, true),
                                    onClick = { pick(name) }
                                )
                            }
                        }
                    }
                }
                if (nothingFound) {
                    item {
                        Text(
                            "No category matches \"${query.trim()}\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp)
                        )
                    }
                }
                ownGroups.forEach { (group, list) ->
                    item(key = "h_$group") { SectionLabel(if (ownGroups.size == 1 && group == "General") (if (isIncome) "Income" else "All categories") else group) }
                    items(list, key = { "c_" + it.id.ifBlank { it.name } }) { cat ->
                        CategoryRow(cat, selected = cat.name.equals(selectedCategory, true)) { pick(cat.name) }
                    }
                }
                if (refundMatches.isNotEmpty()) {
                    item(key = "h_refund") { SectionLabel("Refund of spending") }
                    items(refundMatches, key = { "r_" + it.id.ifBlank { it.name } }) { cat ->
                        CategoryRow(cat, selected = cat.name.equals(selectedCategory, true)) { pick(cat.name) }
                    }
                }
                if (viewModel != null && q.isEmpty()) {
                    item(key = "add_category") {
                        Spacer(Modifier.height(6.dp))
                        AddRow("New category", "category_picker_add_new_btn") { creating = true }
                    }
                }
            }
        }
    }

    if (creating && viewModel != null) {
        CategoryEditorSheet(
            viewModel = viewModel,
            existing = null,
            initialClass = targetClass,
            initialName = query.trim(),
            onDismiss = { creating = false },
            onSaved = { name -> creating = false; pick(name) }
        )
    }
}

@Composable
private fun CategoryRow(cat: Account, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth().testTag("category_picker_item_${cat.name}")
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(getCategoryIcon(cat.name, cat.iconName), getCategoryColor(cat.name))
            Spacer(Modifier.width(12.dp))
            Text(
                cat.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun CategoryChip(name: String, iconName: String, selected: Boolean, onClick: () -> Unit) {
    val tint = getCategoryColor(name)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(getCategoryIcon(name, iconName), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Form fields that open the pickers
// ------------------------------------------------------------------------------------------------

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun FieldBox(onClick: () -> Unit, enabled: Boolean, tag: String, content: @Composable RowScope.() -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.5f else 0.25f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            content()
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
    placeholder: String = "Choose account"
) {
    val myUid by FirebaseService.currentUserUid.collectAsState()
    val partnerName by FirebaseService.spouseUserFirstName.collectAsState()
    Column(modifier = modifier) {
        FieldLabel(label)
        FieldBox(onClick, enabled, "account_picker_field") {
            if (selectedAccount != null) {
                IconBubble(accountIcon(selectedAccount), MaterialTheme.colorScheme.primary, size = 32)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        selectedAccount.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        accountSubtitle(selectedAccount, myUid, partnerName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(8.dp))
                AccountBalanceText(selectedAccount)
            } else {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
            if (enabled) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ExpandMore, contentDescription = "Change account", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * Shows the chosen category and opens the picker. [quickSuggestions] (the categories used most)
 * sit underneath as one-tap chips, wrapping onto more lines so none are hidden off-screen.
 */
@OptIn(ExperimentalLayoutApi::class)
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
        FieldLabel(label)
        FieldBox(onClick, enabled, "category_picker_field") {
            if (selectedCategory.isBlank()) {
                Text(
                    "Choose category",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            } else {
                IconBubble(getCategoryIcon(selectedCategory), getCategoryColor(selectedCategory), size = 32)
                Spacer(Modifier.width(10.dp))
                Text(
                    selectedCategory,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (enabled) {
                Icon(Icons.Default.ExpandMore, contentDescription = "Change category", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }

        val quick = quickSuggestions.filter { !it.equals(selectedCategory, true) }.take(5)
        if (quick.isNotEmpty() && onQuickSelect != null && enabled) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                quick.forEach { name -> CategoryChip(name, "", selected = false, onClick = { onQuickSelect(name) }) }
            }
        }
    }
}
