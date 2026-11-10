package com.example.domain

import com.example.data.Account
import com.example.data.Transaction
import com.example.data.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class InsightRulesTest {

    private fun time(y: Int, m: Int, d: Int): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis

    private fun tx(amount: Double, payee: String, at: Long, category: String = "Entertainment", type: TransactionType = TransactionType.EXPENSE) =
        LedgerRules.toLedger(
            Transaction(amount = amount, type = type, accountId = "card", category = category, payeeName = payee,
                narration = "$category - $payee", date = at, timestamp = at)
        )

    private val now = time(2026, 11, 20)

    @Test
    fun findsMonthlyPaymentsButNotEverydaySpends() {
        val txs = listOf(
            tx(649.0, "NETFLIX", time(2026, 9, 5)), tx(649.0, "Netflix", time(2026, 10, 5)), tx(699.0, "NETFLIX", time(2026, 11, 5)),
            // A coffee shop several times a month is not a subscription.
            tx(250.0, "CAFE", time(2026, 9, 2)), tx(250.0, "CAFE", time(2026, 9, 9)), tx(250.0, "CAFE", time(2026, 10, 3)), tx(250.0, "CAFE", time(2026, 11, 3)),
            // Only two months so far.
            tx(199.0, "SPOTIFY", time(2026, 10, 7)), tx(199.0, "SPOTIFY", time(2026, 11, 7)),
            // Too long ago to count.
            tx(999.0, "GYM", time(2026, 3, 1)), tx(999.0, "GYM", time(2026, 4, 1)), tx(999.0, "GYM", time(2026, 5, 1))
        )
        val found = InsightRules.recurring(txs, now)
        assertEquals(listOf("NETFLIX"), found.map { it.name })
        assertEquals(699.0, found[0].amount, 0.01)
        assertEquals(699.0 * 12, found[0].yearly, 0.01)
        assertEquals(5, found[0].dayOfMonth)
    }

    @Test
    fun monthSummaryNetsRefundsAndComparesWithLastMonth() {
        val categories = listOf(
            Account(accountId = "Food", accountName = "Food", accountType = "EXPENSE"),
            Account(accountId = "Travel", accountName = "Travel", accountType = "EXPENSE"),
            Account(accountId = "Salary", accountName = "Salary", accountType = "INCOME")
        )
        val txs = listOf(
            tx(4000.0, "A", time(2026, 10, 3), "Food"), tx(1000.0, "B", time(2026, 10, 9), "Travel"),
            tx(500.0, "A", time(2026, 10, 12), "Food", TransactionType.INCOME), // refund
            tx(50_000.0, "Employer", time(2026, 10, 1), "Salary", TransactionType.INCOME),
            tx(3000.0, "A", time(2026, 9, 3), "Food")
        )
        val s = InsightRules.monthSummary(txs, categories, time(2026, 10, 15))
        assertEquals(4500.0, s.spent, 0.01)
        assertEquals(3000.0, s.previousSpent, 0.01)
        assertEquals(50_000.0, s.income, 0.01)
        assertEquals(listOf("Food" to 3500.0, "Travel" to 1000.0), s.top)
    }

    @Test
    fun paceWarnsOnlyWhenHeadingOver() {
        // 6,000 spent by the 10th of a 30-day month is heading for 18,000.
        assertEquals(8000.0, BudgetRules.headingOverBy(10_000.0, 6000.0, time(2026, 11, 10))!!, 0.01)
        assertNull(BudgetRules.headingOverBy(10_000.0, 2000.0, time(2026, 11, 10)))
        assertNull(BudgetRules.headingOverBy(10_000.0, 12_000.0, time(2026, 11, 10)))
    }
}
