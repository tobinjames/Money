package com.example.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.Account
import com.example.ui.viewmodel.ExpenseViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

private fun plainNumber(value: Double): String =
    if (value == 0.0) "" else value.toBigDecimal().stripTrailingZeros().toPlainString()

private fun dayOrBlank(day: Int): String = if (day in 1..31) day.toString() else ""

@Composable
private fun FormLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
    )
}

/** A selectable pill, used for account type, owner, and category group. */
@Composable
private fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    suffix: String? = null,
    decimal: Boolean = true,
    supporting: String? = null,
    isError: Boolean = false
) {
    val prefixContent: (@Composable () -> Unit)? = if (prefix != null) { { Text(prefix) } } else null
    val suffixContent: (@Composable () -> Unit)? = if (suffix != null) { { Text(suffix) } } else null
    val supportingContent: (@Composable () -> Unit)? = if (supporting != null) { { Text(supporting) } } else null
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            // A leading minus is allowed in amounts (an overdrawn account).
            val body = if (decimal) input.removePrefix("-") else input
            val ok = if (decimal) body.count { it == '.' } <= 1 && body.all { it.isDigit() || it == '.' } else body.all { it.isDigit() }
            if (ok && input.length <= 12) onChange(input)
        },
        label = { Text(label) },
        prefix = prefixContent,
        suffix = suffixContent,
        supportingText = supportingContent,
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    )
}

/**
 * Add or edit a bank account, cash wallet, credit card, loan or investment, in plain words.
 * The form only asks what that kind of account needs: a card gets its statement and due days,
 * a loan its EMI, and so on. [existing] null means a new account.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AccountEditorSheet(
    viewModel: ExpenseViewModel,
    existing: Account?,
    onDismiss: () -> Unit,
    initialKind: AccountKind = AccountKind.BANK
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val allEntries by viewModel.categories.collectAsState()
    val myUid by viewModel.currentUserUid.collectAsState()
    val partnerUid by viewModel.spouseUserUid.collectAsState()
    val myName by viewModel.currentUserFirstName.collectAsState()
    val partnerName by viewModel.spouseUserFirstName.collectAsState()

    val isNew = existing == null
    var kind by remember { mutableStateOf(existing?.let { accountKindOf(it) } ?: initialKind) }
    var name by remember { mutableStateOf(existing?.accountName.orEmpty()) }
    // "ME", "PARTNER", "JOINT", or "" when an old account's owner can't be told.
    var owner by remember {
        mutableStateOf(
            when {
                existing == null -> "ME"
                isJointOwner(existing.ownerUserId) -> "JOINT"
                existing.ownerUserId == viewModel.currentUserUid.value -> "ME"
                existing.ownerUserId == viewModel.spouseUserUid.value -> "PARTNER"
                else -> ""
            }
        )
    }
    var balanceStr by remember { mutableStateOf(existing?.let { plainNumber(if (kind == AccountKind.LOAN) abs(it.openingBalance) else it.openingBalance) }.orEmpty()) }
    var asOfDate by remember { mutableStateOf(existing?.asOfDate ?: System.currentTimeMillis()) }
    var digits by remember { mutableStateOf(existing?.lastFourDigits.orEmpty()) }
    var statementDay by remember { mutableStateOf(dayOrBlank(existing?.billingCycleDay ?: 0)) }
    var dueDay by remember { mutableStateOf(dayOrBlank(existing?.paymentDueDay ?: 0)) }
    var rateStr by remember { mutableStateOf(plainNumber(existing?.annualInterestRate ?: 0.0)) }
    var emiStr by remember { mutableStateOf(plainNumber(existing?.monthlyEmi ?: 0.0)) }
    var monthsStr by remember { mutableStateOf(existing?.remainingMonths?.takeIf { it > 0 }?.toString().orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val cleanName = name.trim()
    val nameTaken = cleanName.isNotEmpty() && allEntries.any {
        it.accountId != existing?.accountId && it.accountName.trim().equals(cleanName, ignoreCase = true)
    }
    fun badDay(s: String) = s.isNotEmpty() && (s.toIntOrNull() ?: 0) !in 1..31
    val canSave = cleanName.isNotEmpty() && !nameTaken && !badDay(statementDay) && !badDay(dueDay)

    val isCard = kind == AccountKind.CARD
    val isLoan = kind == AccountKind.LOAN
    val usesSms = kind == AccountKind.BANK || isCard || isLoan

    fun save() {
        val balance = balanceStr.toDoubleOrNull() ?: 0.0
        val ownerId = when (owner) {
            "ME" -> myUid ?: existing?.ownerUserId ?: "SHARED"
            "PARTNER" -> partnerUid ?: existing?.ownerUserId ?: "SHARED"
            "JOINT" -> "SHARED"
            else -> existing?.ownerUserId ?: "SHARED"
        }
        val ownerFirstName = when (owner) {
            "ME" -> myName ?: ""
            "PARTNER" -> partnerName ?: ""
            "JOINT" -> "Shared"
            else -> existing?.ownerFirstName ?: ""
        }
        val cycle = if (isCard) statementDay.toIntOrNull() ?: 0 else 0
        val due = if (isCard) dueDay.toIntOrNull() ?: 0 else 0
        val rate = if (isLoan) rateStr.toDoubleOrNull() ?: 0.0 else 0.0
        val emi = if (isLoan) emiStr.toDoubleOrNull() ?: 0.0 else 0.0
        val months = if (isLoan) monthsStr.toIntOrNull() ?: 0 else 0
        val savedDigits = if (kind == AccountKind.CASH) "" else digits.trim().trim(',').trim()

        if (existing == null) {
            viewModel.addAccount(
                context = context,
                name = cleanName,
                type = kind.type,
                balance = balance,
                owner = ownerId,
                openingBalance = balance,
                asOfDate = asOfDate,
                annualInterestRate = rate,
                remainingMonths = months,
                monthlyEmi = emi,
                accountClass = kind.accountClass,
                mainCategory = kind.mainCategory,
                subCategory = cleanName,
                lastFourDigits = savedDigits,
                iconName = "",
                billingCycleDay = cycle,
                paymentDueDay = due
            )
        } else {
            viewModel.updateCategory(
                existing.copy(
                    accountName = cleanName,
                    customSubCategory = cleanName,
                    ownerUserId = ownerId,
                    ownerFirstName = ownerFirstName,
                    // Loans are stored as a negative amount outstanding.
                    openingBalance = if (isLoan) -abs(balance) else balance,
                    asOfDate = asOfDate,
                    lastFourDigits = savedDigits,
                    billingCycleDay = cycle,
                    paymentDueDay = due,
                    annualInterestRate = rate,
                    monthlyEmi = emi,
                    remainingMonths = months
                )
            )
        }
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("account_editor_sheet")
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Text(
                if (isNew) "Add account" else "Edit ${kind.label.lowercase()}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (isNew) {
                FormLabel("What kind of account?")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountKind.values().forEach { k ->
                        ChoiceChip(k.label, selected = kind == k, onClick = { kind = k }, icon = k.icon, modifier = Modifier.testTag("account_kind_${k.name}"))
                    }
                }
            }

            FormLabel("Name")
            val nameSupport: (@Composable () -> Unit)? = if (nameTaken) { { Text("That name is already in use.") } } else null
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 40) name = it },
                placeholder = {
                    Text(
                        when (kind) {
                            AccountKind.BANK -> "e.g. HDFC Salary"
                            AccountKind.CASH -> "e.g. Wallet"
                            AccountKind.CARD -> "e.g. ICICI Amazon Pay"
                            AccountKind.LOAN -> "e.g. SBI Home Loan"
                            AccountKind.FIXED_DEPOSIT -> "e.g. HDFC FD 2027"
                            AccountKind.MUTUAL_FUND -> "e.g. Parag Parikh Flexi Cap"
                        }
                    )
                },
                isError = nameTaken,
                supportingText = nameSupport,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("account_name_input")
            )

            FormLabel("Whose is it?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ChoiceChip("Mine", owner == "ME", { owner = "ME" }, Modifier.weight(1f).testTag("account_owner_me"))
                if (partnerUid != null) {
                    ChoiceChip(
                        partnerName?.takeIf { it.isNotBlank() } ?: "Partner",
                        owner == "PARTNER",
                        { owner = "PARTNER" },
                        Modifier.weight(1f).testTag("account_owner_partner")
                    )
                }
                ChoiceChip("Joint", owner == "JOINT", { owner = "JOINT" }, Modifier.weight(1f).testTag("account_owner_joint"))
            }
            Text(
                when (owner) {
                    "JOINT" -> "Counts in full for both of you in Free to spend, and half each in net worth."
                    "PARTNER" -> "Counts only in ${partnerName?.takeIf { it.isNotBlank() } ?: "your partner"}'s Free to spend."
                    "ME" -> "Counts only in your Free to spend."
                    else -> "Pick an owner so this account counts for the right person."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (owner.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            FormLabel(
                when {
                    isCard -> if (isNew) "How much do you owe on it?" else "Starting amount owed"
                    isLoan -> if (isNew) "How much is left to repay?" else "Starting loan outstanding"
                    kind == AccountKind.FIXED_DEPOSIT || kind == AccountKind.MUTUAL_FUND -> if (isNew) "What is it worth?" else "Starting value"
                    else -> if (isNew) "How much is in it?" else "Starting balance"
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                NumberField(
                    value = balanceStr,
                    onChange = { balanceStr = it },
                    label = "Amount",
                    prefix = "₹ ",
                    modifier = Modifier.weight(1f).testTag("starting_balance_input")
                )
                val dateText = remember(asOfDate) { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(asOfDate)) }
                Surface(
                    onClick = {
                        val cal = Calendar.getInstance().apply { timeInMillis = asOfDate }
                        DatePickerDialog(
                            context,
                            { _, y, m, d ->
                                asOfDate = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                            },
                            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
                        ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.padding(top = 8.dp).height(56.dp).testTag("balance_effective_date_field")
                ) {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text("As on", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(dateText, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Text(
                if (isNew) "Transactions after this date are added on top of it."
                else "This is where the account started. To correct today's balance, use Reconcile instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            if (kind != AccountKind.CASH) {
                FormLabel(if (isCard) "Card's last 4 digits" else "Last 4 digits")
                OutlinedTextField(
                    value = digits,
                    onValueChange = { input -> if (input.length <= 30 && input.all { it.isDigit() || it == ',' || it == ' ' }) digits = input },
                    placeholder = { Text(if (kind == AccountKind.BANK) "e.g. 5678, 1234" else "e.g. 5678") },
                    supportingText = {
                        Text(
                            when {
                                kind == AccountKind.BANK -> "Add the account number's and the debit card's last 4 digits, separated by a comma, so bank SMS land in this account."
                                usesSms -> "Lets the app match SMS to this account."
                                else -> "Optional."
                            }
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("last_four_digits_input")
                )
            }

            if (isCard) {
                FormLabel("Bill dates")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        value = statementDay,
                        onChange = { if (it.length <= 2) statementDay = it },
                        label = "Statement day",
                        decimal = false,
                        isError = badDay(statementDay),
                        modifier = Modifier.weight(1f).testTag("billing_cycle_day_input")
                    )
                    NumberField(
                        value = dueDay,
                        onChange = { if (it.length <= 2) dueDay = it },
                        label = "Payment due day",
                        decimal = false,
                        isError = badDay(dueDay),
                        modifier = Modifier.weight(1f).testTag("payment_due_day_input")
                    )
                }
                Text(
                    "Day of the month (1 to 31). With these the app can show what's billed, what's building up for next month, and remind you before the due date.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (isLoan) {
                FormLabel("Loan details")
                NumberField(
                    value = emiStr,
                    onChange = { emiStr = it },
                    label = "Monthly EMI",
                    prefix = "₹ ",
                    modifier = Modifier.fillMaxWidth().testTag("emi_amount_input")
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        value = rateStr,
                        onChange = { rateStr = it },
                        label = "Interest rate",
                        suffix = "% a year",
                        modifier = Modifier.weight(1f).testTag("annual_rate_input")
                    )
                    NumberField(
                        value = monthsStr,
                        onChange = { if (it.length <= 3) monthsStr = it },
                        label = "Months left",
                        decimal = false,
                        modifier = Modifier.weight(1f).testTag("remaining_months_input")
                    )
                }
                Text(
                    "The EMI is set aside in Free to spend each month, and the rate splits each payment into interest and principal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { save() },
                enabled = canSave,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("save_account_btn")
            ) {
                Text(if (isNew) "Add account" else "Save", fontWeight = FontWeight.Bold)
            }
            if (existing != null && !existing.isSystemDefault) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().testTag("delete_account_btn")) {
                    Text("Delete this account", color = MaterialTheme.colorScheme.error)
                }
            } else if (existing != null) {
                Text(
                    "This is a starter account. You can rename it, but it can't be deleted because unmatched SMS fall back to it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = { Text("Its transactions stay, but they will no longer count towards any balance. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteCategory(existing.id); confirmDelete = false; onDismiss() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

/**
 * Add or edit a spending or income category: a name, an optional group to file it under, and
 * an icon. [onSaved] gets the saved name, so a picker can select a category made on the spot.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoryEditorSheet(
    viewModel: ExpenseViewModel,
    existing: Account?,
    onDismiss: () -> Unit,
    initialClass: String = "EXPENSE",
    initialName: String = "",
    onSaved: (String) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val allEntries by viewModel.categories.collectAsState()

    val isNew = existing == null
    val locked = existing?.isSystemDefault == true
    var cls by remember { mutableStateOf(existing?.accountClass?.uppercase() ?: initialClass) }
    var name by remember { mutableStateOf(existing?.accountName ?: initialName) }
    val originalGroup = remember { existing?.let { displayCategoryGroup(it.mainCategory) } ?: "General" }
    var group by remember { mutableStateOf(originalGroup) }
    var addingGroup by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(existing?.iconName.orEmpty()) }
    var iconChosen by remember { mutableStateOf(existing != null && existing.iconName.isNotBlank()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val cleanName = name.trim()
    val nameTaken = cleanName.isNotEmpty() && allEntries.any {
        it.accountId != existing?.accountId && it.accountName.trim().equals(cleanName, ignoreCase = true)
    }
    val groups = remember(allEntries, cls, originalGroup) {
        (listOf("General", originalGroup) + allEntries.filter { it.accountClass.equals(cls, true) }.map { displayCategoryGroup(it.mainCategory) })
            .distinct()
            .sortedWith(compareBy<String> { it != "General" }.thenBy { it.lowercase() })
    }
    // Until an icon is picked by hand, guess one from the name ("Coffee shops" gets the coffee cup).
    val shownIcon = if (iconChosen) icon else categoryIconPack.keys.firstOrNull { cleanName.contains(it, ignoreCase = true) } ?: "Receipt"
    val finalGroup = if (addingGroup) newGroup.trim() else group
    val canSave = cleanName.isNotEmpty() && !nameTaken && finalGroup.isNotEmpty()

    fun save() {
        val storedGroup = when {
            // Unchanged: keep whatever was stored, so budgets on the old group name still match.
            existing != null && finalGroup.equals(originalGroup, true) -> existing.customMainCategory
            finalGroup.equals("General", true) -> ""
            else -> finalGroup
        }
        if (existing == null) {
            viewModel.addCategory(
                Account(
                    accountId = UUID.randomUUID().toString(),
                    accountName = cleanName,
                    accountType = cls,
                    isSystemDefault = false,
                    customAccountClass = cls,
                    customMainCategory = storedGroup,
                    customSubCategory = cleanName,
                    iconName = shownIcon
                )
            )
        } else {
            viewModel.updateCategory(
                existing.copy(
                    accountName = if (locked) existing.accountName else cleanName,
                    customSubCategory = if (locked) existing.customSubCategory else cleanName,
                    customMainCategory = storedGroup,
                    iconName = shownIcon
                )
            )
        }
        onSaved(if (locked && existing != null) existing.accountName else cleanName)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("category_editor_sheet")
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Text(if (isNew) "New category" else "Edit category", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            if (isNew) {
                FormLabel("What is it for?")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ChoiceChip("Spending", cls == "EXPENSE", { cls = "EXPENSE"; group = "General" }, Modifier.weight(1f).testTag("category_class_EXPENSE"))
                    ChoiceChip("Income", cls == "INCOME", { cls = "INCOME"; group = "General" }, Modifier.weight(1f).testTag("category_class_INCOME"))
                }
            }

            FormLabel("Name")
            val nameHint = when {
                nameTaken -> "That name is already in use."
                locked -> "Built-in category: the name can't be changed."
                !isNew -> "Renaming moves its transactions, budgets and rules to the new name."
                else -> null
            }
            val nameSupport: (@Composable () -> Unit)? = if (nameHint != null) { { Text(nameHint) } } else null
            OutlinedTextField(
                value = name,
                onValueChange = { if (!locked && it.length <= 40) name = it },
                readOnly = locked,
                placeholder = { Text(if (cls == "INCOME") "e.g. Freelance" else "e.g. Eating out") },
                isError = nameTaken,
                supportingText = nameSupport,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("category_name_input")
            )

            FormLabel("Group")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { g ->
                    ChoiceChip(g, selected = !addingGroup && group == g, onClick = { group = g; addingGroup = false })
                }
                ChoiceChip("+ New group", selected = addingGroup, onClick = { addingGroup = true })
            }
            if (addingGroup) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newGroup,
                    onValueChange = { if (it.length <= 30) newGroup = it },
                    placeholder = { Text("e.g. Home, Kids, Car") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("category_new_group_input")
                )
            }
            Text(
                "Groups keep related categories together in lists and reports.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            FormLabel("Icon")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                categoryIconPack.forEach { (iconName, vector) ->
                    val selected = shownIcon == iconName
                    Surface(
                        onClick = { icon = iconName; iconChosen = true },
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (selected) Icons.Default.Check else vector,
                                contentDescription = iconName,
                                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { save() },
                enabled = canSave,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp).testTag("save_category_btn")
            ) {
                Text(if (isNew) "Add category" else "Save", fontWeight = FontWeight.Bold)
            }
            if (existing != null && !locked) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().testTag("delete_category_button")) {
                    Text("Delete this category", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = { Text("Transactions filed under it keep the name but won't belong to a category. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteCategory(existing.id); confirmDelete = false; onDismiss() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}
