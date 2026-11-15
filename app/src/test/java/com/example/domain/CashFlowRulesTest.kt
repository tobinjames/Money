package com.example.domain

import com.example.data.Account
import com.example.data.Transaction
import com.example.data.TransactionType
import com.example.domain.PayerRules.Who
import org.junit.Assert.assertEquals
import org.junit.Test

class CashFlowRulesTest {

    private val me = "me"
    private val partner = "partner"
    private val accounts = listOf(
        Account(accountId = "myCard", accountType = "LIABILITY", ownerUserId = me),
        Account(accountId = "theirBank", accountType = "ASSET", ownerUserId = partner),
        Account(accountId = "joint", accountType = "ASSET", ownerUserId = "SHARED")
    ).associateBy { it.accountId }
    private val expenseCategories = setOf("Food", "Clothes")

    private fun tx(amount: Double, type: TransactionType, account: String, category: String, paidFor: String = "BOTH") =
        Transaction(amount = amount, type = type, accountId = account, category = category, paidByUserId = paidFor)

    private val summary = CashFlowRules.summary(
        listOf(
            tx(3_000.0, TransactionType.EXPENSE, "myCard", "Food"),
            tx(1_000.0, TransactionType.EXPENSE, "theirBank", "Food"),
            tx(500.0, TransactionType.EXPENSE, "joint", "Food"),
            tx(800.0, TransactionType.EXPENSE, "myCard", "Clothes", paidFor = partner),
            tx(200.0, TransactionType.EXPENSE, "theirBank", "Clothes", paidFor = partner),
            // A refund to my card of a family spend
            tx(400.0, TransactionType.INCOME, "myCard", "Food"),
            tx(50_000.0, TransactionType.INCOME, "theirBank", "Salary"),
            tx(2_000.0, TransactionType.INCOME, "joint", "Interest"),
            // Moving money is neither spending nor income
            tx(9_000.0, TransactionType.TRANSFER, "theirBank", "Transfer"),
            tx(700.0, TransactionType.EXPENSE, "myCard", "Food").copy(isIgnored = true)
        ),
        accounts, me, partner
    ) { it in expenseCategories }

    @Test
    fun familyExpensesAreSplitByWhoPaid() {
        assertEquals(2_600.0, summary.familyPaidBy(Who.ME), 0.001)
        assertEquals(1_000.0, summary.familyPaidBy(Who.PARTNER), 0.001)
        assertEquals(500.0, summary.familyPaidBy(Who.JOINT), 0.001)
        assertEquals(4_100.0, summary.familyTotal, 0.001)
        assertEquals(1_600.0, summary.familyLead, 0.001)
    }

    @Test
    fun spendingForOnePersonKeepsWhoPaid() {
        assertEquals(800.0, summary.paid(Who.ME, Who.PARTNER), 0.001)
        assertEquals(200.0, summary.paid(Who.PARTNER, Who.PARTNER), 0.001)
        assertEquals(1_000.0, summary.totalFor(Who.PARTNER), 0.001)
        assertEquals(0.0, summary.totalFor(Who.ME), 0.001)
    }

    @Test
    fun incomeGoesToWhoeverReceivedIt() {
        assertEquals(50_000.0, summary.received[Who.PARTNER] ?: 0.0, 0.001)
        assertEquals(2_000.0, summary.received[Who.JOINT] ?: 0.0, 0.001)
        assertEquals(0.0, summary.received[Who.ME] ?: 0.0, 0.001)
    }
}
