package com.example.domain

import com.example.data.Account
import com.example.data.AccountType
import com.example.data.Budget
import com.example.data.Transaction
import com.example.data.TransactionStatus
import com.example.data.TransactionType
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.min

/**
 * Pure accounting rules, kept free of Android and Firebase so they can be unit tested.
 */
object LedgerRules {

    const val RECONCILIATION_CATEGORY = "Reconciliation Adjustment"

    /** Two SMS for one self-transfer usually arrive within minutes; allow for slow IMPS/NEFT. */
    const val TRANSFER_MATCH_WINDOW_MS = 2L * 60 * 60 * 1000

    /** A card payment's two SMS (bank debit, card "payment received") can be days apart. */
    const val CARD_PAYMENT_MATCH_WINDOW_MS = 4L * 24 * 60 * 60 * 1000

    /** Change in an investment's value found when reconciling: not spending and not income. */
    const val INVESTMENT_CHANGE_CATEGORY = "Investment Gain/Loss"

    const val LOAN_INTEREST_CATEGORY = "Finance: Loan Interest"

    /**
     * Whether a transaction moves a balance. Confirmed ones do, and so do ones still waiting for
     * review: their amount and account are known, only the category is unsure, and leaving them
     * out would overstate the money in hand. Ignored ones and suspected duplicates do not.
     */
    fun countsInBalance(t: Transaction): Boolean =
        !t.isIgnored && (t.status == TransactionStatus.CONFIRMED || !t.isProbablyDuplicate)

    /** Confirmed and not ignored: what spending, income and budgets are worked out from. */
    fun isCounted(t: Transaction): Boolean = !t.isIgnored && t.status == TransactionStatus.CONFIRMED

    /** Categories that are neither spending nor income (investment value changes). */
    fun isNonSpending(category: String): Boolean = category.trim().equals(INVESTMENT_CHANGE_CATEGORY, ignoreCase = true)

    /**
     * What a transaction adds to spending: an expense adds its amount, a refund (income recorded
     * against an expense category) takes it back, anything else is 0.
     */
    fun spendOf(t: Transaction, isExpenseCategory: (String) -> Boolean): Double = when {
        !isCounted(t) || isNonSpending(t.category) -> 0.0
        t.type == TransactionType.EXPENSE -> t.amount
        t.type == TransactionType.INCOME && isExpenseCategory(t.category.trim()) -> -t.amount
        else -> 0.0
    }

    /** What a transaction adds to income: real income only, not refunds or investment changes. */
    fun incomeOf(t: Transaction, isExpenseCategory: (String) -> Boolean): Double =
        if (isCounted(t) && t.type == TransactionType.INCOME && !isNonSpending(t.category) && !isExpenseCategory(t.category.trim())) t.amount else 0.0

    /**
     * Splits a loan payment into interest and principal. Interest is one month on what is
     * outstanding, never more than the payment, and only on the first payment of the month:
     * a second payment or a part-prepayment in the same month is all principal.
     */
    fun emiSplit(amount: Double, outstanding: Double, annualRate: Double, interestAlreadyCharged: Boolean): Pair<Double, Double> {
        if (interestAlreadyCharged || annualRate <= 0.0 || outstanding <= 0.0) return Pair(0.0, amount)
        val interest = min(amount, outstanding * (annualRate / 100.0) / 12.0)
        return Pair(interest, amount - interest)
    }

    /** Whether a payment into [loanId] (a principal transfer) was already recorded in the month of [time]. */
    fun loanPaidInMonth(loanId: String, ledgerTxs: List<Transaction>, time: Long): Boolean {
        val (start, end) = monthBounds(time)
        return ledgerTxs.any { !it.isIgnored && it.isLoanEmi && it.debitAccountId == loanId && it.date in start until end }
    }

    private val NOT_A_TRANSFER_WORDS = listOf("refund", "reversal", "reversed", "cashback", "chargeback")

    /** Fills the double-entry fields (debit/credit) from the legacy fields. */
    fun toLedger(tx: Transaction): Transaction {
        val finalId = if (tx.transactionId.isNotEmpty()) tx.transactionId else tx.id
        val finalDate = if (tx.date != 0L) tx.date else tx.timestamp
        val finalPayee = if (tx.payeeName.isNotEmpty()) tx.payeeName else tx.narration

        val (debit, credit) = when (tx.type) {
            TransactionType.EXPENSE -> Pair(tx.category.ifEmpty { "Others" }, tx.accountId)
            TransactionType.INCOME -> Pair(tx.accountId, tx.category.ifEmpty { "Salary" })
            TransactionType.TRANSFER -> Pair(tx.targetAccountId ?: "", tx.accountId)
        }

        return tx.copy(
            transactionId = finalId,
            id = finalId,
            date = finalDate,
            timestamp = finalDate,
            payeeName = finalPayee,
            narration = finalPayee,
            debitAccountId = debit,
            creditAccountId = credit,
            entryType = tx.type.name
        )
    }

    /**
     * Account balance from the ledger.
     * - Assets: opening + debits - credits.
     * - Credit cards: dues, opening + credits - debits.
     * - Loans: shown as a negative outstanding amount, -(|opening| + credits - debits), so a
     *   principal payment (a debit to the loan) moves the balance towards zero.
     *
     * @param upTo only count transactions dated on or before this time, when given.
     */
    fun balance(account: Account, ledgerTxs: List<Transaction>, upTo: Long? = null): Double {
        var debitSum = 0.0
        var creditSum = 0.0
        for (t in ledgerTxs) {
            if (!countsInBalance(t) || (upTo != null && t.date > upTo)) continue
            if (t.debitAccountId == account.accountId) debitSum += t.amount
            if (t.creditAccountId == account.accountId) creditSum += t.amount
        }
        return fromSums(account, debitSum, creditSum)
    }

    /**
     * Every account's balance from a single pass over the transactions (rather than one pass per
     * account), keyed by account id. Same results as [balance].
     */
    fun balances(accounts: List<Account>, ledgerTxs: List<Transaction>): Map<String, Double> {
        val debits = HashMap<String, Double>()
        val credits = HashMap<String, Double>()
        for (t in ledgerTxs) {
            if (!countsInBalance(t)) continue
            debits[t.debitAccountId] = (debits[t.debitAccountId] ?: 0.0) + t.amount
            credits[t.creditAccountId] = (credits[t.creditAccountId] ?: 0.0) + t.amount
        }
        return accounts.associate { it.accountId to fromSums(it, debits[it.accountId] ?: 0.0, credits[it.accountId] ?: 0.0) }
    }

    internal fun fromSums(account: Account, debitSum: Double, creditSum: Double): Double {
        val isAssetOrLiability = account.accountType == "ASSET" || account.accountType == "LIABILITY"
        val opening = if (isAssetOrLiability) account.openingBalance else 0.0
        return when {
            account.type == AccountType.LOAN -> -(abs(opening) + creditSum - debitSum)
            account.accountType == "LIABILITY" -> opening + creditSum - debitSum
            else -> opening + debitSum - creditSum
        }
    }

    /**
     * The entry that brings an account's ledger balance on [closingDate] to [statementBalance].
     * Returns null when they already agree. For loans, [statementBalance] is the outstanding
     * principal and may be given with either sign.
     */
    fun reconciliationAdjustment(
        account: Account,
        ledgerTxs: List<Transaction>,
        closingDate: Long,
        statementBalance: Double
    ): Transaction? {
        val asOf = endOfDay(closingDate)
        val ledgerBalance = balance(account, ledgerTxs, asOf)
        val isLoan = account.type == AccountType.LOAN
        // Work in "natural" terms: asset balance, card dues, or loan outstanding (all positive-up).
        val current = if (isLoan) -ledgerBalance else ledgerBalance
        val target = if (isLoan) abs(statementBalance) else statementBalance
        val diff = target - current
        if (abs(diff) < 0.005) return null

        val raiseBalance = diff > 0
        val type = if (account.accountType == "LIABILITY") {
            // A credit to a liability raises it: that is an expense paid from the account.
            if (raiseBalance) TransactionType.EXPENSE else TransactionType.INCOME
        } else {
            if (raiseBalance) TransactionType.INCOME else TransactionType.EXPENSE
        }

        return Transaction(
            amount = abs(diff),
            date = closingDate,
            timestamp = closingDate,
            type = type,
            // A fund or deposit changing value is not spending or income.
            category = if (account.type == AccountType.FIXED_DEPOSIT || account.type == AccountType.MUTUAL_FUND) INVESTMENT_CHANGE_CATEGORY else RECONCILIATION_CATEGORY,
            accountId = account.accountId,
            payeeName = "Statement reconciliation",
            narration = "Statement reconciliation",
            status = TransactionStatus.CONFIRMED,
            paidByUserId = account.ownerUserId,
            ownerUserId = account.ownerUserId,
            ownerFirstName = account.ownerFirstName
        )
    }

    /**
     * Finds the other half of a transfer between two of the family's own accounts: an
     * opposite-direction SMS transaction for the same amount, on another known account, close in
     * time. Refunds, reversals and cashback are never treated as transfers.
     */
    fun findTransferCounterpart(
        history: List<Transaction>,
        amount: Double,
        timestamp: Long,
        isCredit: Boolean,
        accountId: String,
        ownAccountIds: Set<String>,
        smsBody: String,
        cardAccountIds: Set<String> = emptySet()
    ): Transaction? {
        if (looksLikeRefund(smsBody)) return null
        return history
            .filter { tx ->
                // A bank debit and the card's "payment received" for it: the card is the side credited.
                val cardPayment = if (isCredit) accountId in cardAccountIds && tx.accountId !in cardAccountIds
                    else tx.accountId in cardAccountIds && accountId !in cardAccountIds
                val window = if (cardPayment) CARD_PAYMENT_MATCH_WINDOW_MS else TRANSFER_MATCH_WINDOW_MS
                abs(tx.amount - amount) < 0.01 &&
                    abs(tx.timestamp - timestamp) <= window &&
                    tx.accountId != accountId &&
                    tx.accountId in ownAccountIds &&
                    !tx.isIgnored &&
                    tx.originalSmsText != null &&
                    !looksLikeRefund(tx.originalSmsText) &&
                    tx.type == (if (isCredit) TransactionType.EXPENSE else TransactionType.INCOME)
            }
            .minByOrNull { abs(it.timestamp - timestamp) }
    }

    /**
     * A transfer already recorded from the other side's SMS, which this SMS is the second half
     * of: a credit into [accountId], or a debit from [accountId] that went to one of the cards.
     * [alreadyHas] says whether a saved transfer already includes this side's SMS.
     */
    fun findRecordedTransfer(
        history: List<Transaction>,
        amount: Double,
        timestamp: Long,
        isCredit: Boolean,
        accountId: String,
        cardAccountIds: Set<String>,
        alreadyHas: (Transaction) -> Boolean
    ): Transaction? =
        history.filter { tx ->
            tx.type == TransactionType.TRANSFER && !tx.isIgnored && tx.originalSmsText != null &&
                abs(tx.amount - amount) < 0.01 &&
                abs(tx.timestamp - timestamp) <= CARD_PAYMENT_MATCH_WINDOW_MS &&
                (if (isCredit) tx.targetAccountId == accountId else tx.accountId == accountId && tx.targetAccountId in cardAccountIds) &&
                !alreadyHas(tx)
        }.minByOrNull { abs(it.timestamp - timestamp) }

    private fun looksLikeRefund(text: String): Boolean {
        val lower = text.lowercase()
        return NOT_A_TRANSFER_WORDS.any { lower.contains(it) }
    }

    /** Whether an expense in [category] (with its main/sub grouping) counts toward [budget]. */
    fun budgetMatches(budget: Budget, category: String, mainCategory: String, subCategory: String): Boolean {
        if (budget.isOverall) return true
        val matchesSub = budget.subCategory.isNotBlank() &&
            (budget.subCategory.equals(subCategory, ignoreCase = true) || budget.subCategory.equals(category, ignoreCase = true))
        val matchesMain = budget.subCategory.isBlank() && budget.mainCategory.isNotBlank() &&
            (budget.mainCategory.equals(mainCategory, ignoreCase = true) || budget.mainCategory.equals(category, ignoreCase = true))
        val matchesLegacy = budget.category.isNotBlank() &&
            (budget.category.equals(category, ignoreCase = true) ||
                budget.category.equals(mainCategory, ignoreCase = true) ||
                budget.category.equals(subCategory, ignoreCase = true))
        return matchesSub || matchesMain || matchesLegacy
    }

    /**
     * Confirmed, non-ignored expenses toward [budget] in the calendar month containing [now],
     * less refunds: income recorded against an expense category (see [isExpenseCategory]).
     * [grouping] maps a category name to its (main, sub) grouping.
     */
    fun monthlySpent(
        budget: Budget,
        txs: List<Transaction>,
        now: Long,
        isExpenseCategory: (String) -> Boolean = { false },
        grouping: (String) -> Pair<String, String>
    ): Double {
        val (start, end) = monthBounds(now)
        return txs.filter { it.timestamp in start until end }.sumOf { tx ->
            val amount = spendOf(tx, isExpenseCategory)
            if (amount == 0.0) return@sumOf 0.0
            val category = tx.category.trim()
            val (main, sub) = grouping(category)
            if (budgetMatches(budget, category, main, sub)) amount else 0.0
        }
    }

    fun monthBounds(time: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = time
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        return Pair(start, cal.timeInMillis)
    }

    fun endOfDay(time: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = time
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return cal.timeInMillis
    }
}
