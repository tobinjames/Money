package com.example.domain

import com.example.data.Account
import com.example.data.Budget
import com.example.data.Transaction
import com.example.data.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class BudgetRulesTest {
    private fun time(y: Int, m: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    private val categories = listOf(
        Account(accountId = "Groceries", accountName = "Groceries", accountType = "EXPENSE", customMainCategory = "Household"),
        Account(accountId = "Food", accountName = "Food", accountType = "EXPENSE", customMainCategory = "Eating out"),
        Account(accountId = "Salary", accountName = "Salary", accountType = "INCOME")
    )

    private fun tx(type: TransactionType, amount: Double, category: String, at: Long = time(2026, 11, 5), ignored: Boolean = false) =
        LedgerRules.toLedger(Transaction(amount = amount, type = type, category = category, accountId = "bank", timestamp = at, date = at, isIgnored = ignored))

    private val txs = listOf(
        tx(TransactionType.EXPENSE, 3000.0, "Groceries"),
        tx(TransactionType.EXPENSE, 1000.0, "Food"),
        tx(TransactionType.EXPENSE, 500.0, "Food", ignored = true),
        tx(TransactionType.INCOME, 400.0, "Groceries"), // refund
        tx(TransactionType.INCOME, 50000.0, "Salary"),
        tx(TransactionType.EXPENSE, 9999.0, "Groceries", at = time(2026, 10, 30))
    )
    private val now = time(2026, 11, 20)

    @Test fun `category budget counts this month, skips ignored, nets refunds`() {
        assertEquals(2600.0, BudgetRules.spent(Budget(mainCategory = "Household", subCategory = "Groceries", category = "Groceries"), txs, categories, now), 0.001)
        assertEquals(1000.0, BudgetRules.spent(Budget(mainCategory = "Eating out", category = "Eating out"), txs, categories, now), 0.001)
    }

    @Test fun `overall budget covers all spending, not income`() {
        assertEquals(3600.0, BudgetRules.spent(Budget(isOverall = true), txs, categories, now), 0.001)
    }

    @Test fun `alert stages`() {
        assertNull(BudgetRules.alertStage(700.0, 1000.0))
        assertEquals("80", BudgetRules.alertStage(800.0, 1000.0))
        assertEquals("80", BudgetRules.alertStage(1000.0, 1000.0))
        assertEquals("over", BudgetRules.alertStage(1000.5, 1000.0))
        assertNull(BudgetRules.alertStage(10.0, 0.0))
    }

    @Test fun `safe to spend per day`() {
        // 20 November: 11 days left including today.
        assertEquals(11, BudgetRules.daysLeftInMonth(now))
        assertEquals(409.09, BudgetRules.safePerDay(12000.0, 7500.0, now)!!, 0.01)
        assertNull(BudgetRules.safePerDay(1000.0, 1200.0, now))
    }

    @Test fun `family totals prefer the overall budget`() {
        val a = Budget(category = "Food", monthlyLimit = 5000.0)
        val b = Budget(category = "Groceries", monthlyLimit = 8000.0)
        assertEquals(13000.0, BudgetRules.familyTotals(listOf(a, b)) { 100.0 }.first, 0.001)
        val all = Budget(isOverall = true, monthlyLimit = 40000.0)
        assertEquals(40000.0, BudgetRules.familyTotals(listOf(a, b, all)) { 100.0 }.first, 0.001)
    }
}
