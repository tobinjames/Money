package com.example.domain

import com.example.data.Account
import com.example.data.Transaction
import com.example.data.TransactionType
import java.util.Calendar
import kotlin.math.abs

/** Things worked out from the history for the user: repeating payments and the monthly summary. */
object InsightRules {

    data class Recurring(
        val name: String,
        /** The usual amount (the most recent one). */
        val amount: Double,
        val yearly: Double,
        val months: Int,
        val lastDate: Long,
        val category: String,
        val dayOfMonth: Int
    )

    /** How many of the last months to look at, and how many of them a payment must appear in. */
    const val LOOKBACK_MONTHS = 4
    const val MIN_MONTHS = 3

    /**
     * Payments that repeat every month: the same payee, for about the same amount (within 10%),
     * in at least [MIN_MONTHS] of the last [LOOKBACK_MONTHS] months, once a month. Subscriptions,
     * rent, SIPs and the like. Largest first.
     */
    fun recurring(txs: List<Transaction>, now: Long): List<Recurring> {
        val since = Calendar.getInstance().apply {
            timeInMillis = LedgerRules.monthBounds(now).first
            add(Calendar.MONTH, -(LOOKBACK_MONTHS - 1))
        }.timeInMillis
        return txs
            .filter { LedgerRules.isCounted(it) && it.type == TransactionType.EXPENSE && !it.isLoanEmi && it.date in since..now }
            .filter { !it.category.equals(LedgerRules.RECONCILIATION_CATEGORY, true) && !LedgerRules.isNonSpending(it.category) }
            .groupBy { payeeKey(it) }
            .filterKeys { it.isNotBlank() }
            .mapNotNull { (_, list) ->
                val latest = list.maxByOrNull { it.date } ?: return@mapNotNull null
                // Only payments close to the latest amount: a monthly bill, not every coffee.
                val similar = list.filter { abs(it.amount - latest.amount) <= 0.1 * latest.amount }
                val byMonth = similar.groupBy { FreeToSpendRules.monthKey(it.date) }
                if (byMonth.size < MIN_MONTHS || byMonth.values.any { it.size > 1 }) return@mapNotNull null
                Recurring(
                    name = displayName(latest),
                    amount = latest.amount,
                    yearly = latest.amount * 12,
                    months = byMonth.size,
                    lastDate = latest.date,
                    category = latest.category,
                    dayOfMonth = ReminderRules.dayOfMonth(latest.date)
                )
            }
            .sortedByDescending { it.amount }
    }

    private fun displayName(t: Transaction): String =
        t.payeeName.trim().ifBlank { t.narration.substringAfter(" - ").trim() }

    private fun payeeKey(t: Transaction): String =
        displayName(t).lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    data class MonthSummary(
        val spent: Double,
        val previousSpent: Double,
        val income: Double,
        /** Biggest categories, largest first. */
        val top: List<Pair<String, Double>>
    ) {
        val saved: Double get() = income - spent
    }

    /** The calendar month containing [inMonth], compared with the one before it. */
    fun monthSummary(txs: List<Transaction>, categories: List<Account>, inMonth: Long): MonthSummary {
        val isExpense = BudgetRules.isExpenseCategory(categories)
        val (start, end) = LedgerRules.monthBounds(inMonth)
        val (prevStart, _) = LedgerRules.monthBounds(start - 1)
        val month = txs.filter { it.date in start until end }
        val byCategory = HashMap<String, Double>()
        month.forEach { t ->
            val spend = LedgerRules.spendOf(t, isExpense)
            if (spend != 0.0) byCategory[t.category] = (byCategory[t.category] ?: 0.0) + spend
        }
        return MonthSummary(
            spent = month.sumOf { LedgerRules.spendOf(it, isExpense) },
            previousSpent = txs.filter { it.date in prevStart until start }.sumOf { LedgerRules.spendOf(it, isExpense) },
            income = month.sumOf { LedgerRules.incomeOf(it, isExpense) },
            top = byCategory.filterValues { it > 0.0 }.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }
        )
    }
}
