package com.example.domain

import com.example.data.Account
import com.example.data.AccountType
import com.example.data.Budget
import com.example.data.Transaction
import com.example.data.TransactionStatus
import com.example.data.TransactionType
import java.util.Calendar
import kotlin.math.abs

/**
 * Pure accounting rules, kept free of Android and Firebase so they can be unit tested.
 */
object LedgerRules {

    const val RECONCILIATION_CATEGORY = "Reconciliation Adjustment"

    /** Two SMS for one self-transfer usually arrive within minutes; allow for slow IMPS/NEFT. */
    const val TRANSFER_MATCH_WINDOW_MS = 2L * 60 * 60 * 1000

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
        val counted = ledgerTxs.filter {
            !it.isIgnored && it.status == TransactionStatus.CONFIRMED && (upTo == null || it.date <= upTo)
        }
        val debitSum = counted.filter { it.debitAccountId == account.accountId }.sumOf { it.amount }
        val creditSum = counted.filter { it.creditAccountId == account.accountId }.sumOf { it.amount }
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
            category = RECONCILIATION_CATEGORY,
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
        smsBody: String
    ): Transaction? {
        if (looksLikeRefund(smsBody)) return null
        return history
            .filter { tx ->
                abs(tx.amount - amount) < 0.01 &&
                    abs(tx.timestamp - timestamp) <= TRANSFER_MATCH_WINDOW_MS &&
                    tx.accountId != accountId &&
                    tx.accountId in ownAccountIds &&
                    !tx.isIgnored &&
                    tx.originalSmsText != null &&
                    !looksLikeRefund(tx.originalSmsText) &&
                    tx.type == (if (isCredit) TransactionType.EXPENSE else TransactionType.INCOME)
            }
            .minByOrNull { abs(it.timestamp - timestamp) }
    }

    private fun looksLikeRefund(text: String): Boolean {
        val lower = text.lowercase()
        return NOT_A_TRANSFER_WORDS.any { lower.contains(it) }
    }

    /** Whether an expense in [category] (with its main/sub grouping) counts toward [budget]. */
    fun budgetMatches(budget: Budget, category: String, mainCategory: String, subCategory: String): Boolean {
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
     * Confirmed, non-ignored expenses toward [budget] in the calendar month containing [now].
     * [grouping] maps a category name to its (main, sub) grouping.
     */
    fun monthlySpent(
        budget: Budget,
        txs: List<Transaction>,
        now: Long,
        grouping: (String) -> Pair<String, String>
    ): Double {
        val (start, end) = monthBounds(now)
        return txs.filter {
            it.type == TransactionType.EXPENSE &&
                it.status == TransactionStatus.CONFIRMED &&
                !it.isIgnored &&
                it.timestamp in start until end
        }.filter {
            val category = it.category.trim()
            val (main, sub) = grouping(category)
            budgetMatches(budget, category, main, sub)
        }.sumOf { it.amount }
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
