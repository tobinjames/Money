package com.example.domain

import com.example.data.Account
import com.example.data.Budget
import com.example.data.Transaction
import java.util.Calendar

/**
 * Budget figures shared by the Budgets screen and the background alerts, so both always agree.
 */
object BudgetRules {

    /** Maps a transaction's category name to its (main, sub) grouping in the chart of accounts. */
    fun grouping(categories: List<Account>): (String) -> Pair<String, String> = { category ->
        val cat = find(categories, category)
        Pair(cat?.mainCategory?.trim() ?: "", cat?.subCategory?.trim() ?: category)
    }

    /** Whether [category] is an expense category (income recorded against one is a refund). */
    fun isExpenseCategory(categories: List<Account>): (String) -> Boolean = { category ->
        find(categories, category)?.accountType == "EXPENSE"
    }

    private fun find(categories: List<Account>, name: String): Account? =
        categories.find { it.name.equals(name, ignoreCase = true) || it.id.equals(name, ignoreCase = true) }

    /** This month's spending toward [budget], net of refunds. */
    fun spent(budget: Budget, txs: List<Transaction>, categories: List<Account>, now: Long): Double =
        LedgerRules.monthlySpent(budget, txs, now, isExpenseCategory(categories), grouping(categories))

    /** "80" from 80% of the limit, "over" once past it, else null. One alert per stage per month. */
    fun alertStage(spent: Double, limit: Double): String? = when {
        limit <= 0.0 -> null
        spent > limit -> "over"
        spent >= 0.8 * limit -> "80"
        else -> null
    }

    /** Days left in the month, counting today. */
    fun daysLeftInMonth(now: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH) - cal.get(Calendar.DAY_OF_MONTH) + 1
    }

    /** What can still be spent per day for the rest of the month without going over; null when over. */
    fun safePerDay(limit: Double, spent: Double, now: Long): Double? {
        val left = limit - spent
        if (left < 0.0) return null
        return left / daysLeftInMonth(now)
    }

    /**
     * The family's total limit and spend for the summary card: the overall budget when there is
     * one, otherwise the category budgets added up.
     */
    fun familyTotals(budgets: List<Budget>, spentOf: (Budget) -> Double): Pair<Double, Double> {
        val overall = budgets.firstOrNull { it.isOverall }
        if (overall != null) return overall.monthlyLimit to spentOf(overall)
        return budgets.sumOf { it.monthlyLimit } to budgets.sumOf { spentOf(it) }
    }

    fun label(budget: Budget): String = when {
        budget.isOverall -> "Family budget"
        budget.subCategory.isNotBlank() -> budget.subCategory
        budget.mainCategory.isNotBlank() -> budget.mainCategory
        else -> budget.category
    }
}
