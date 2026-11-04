package com.example.domain

import com.example.data.Account
import com.example.data.AccountType
import com.example.data.Budget
import com.example.data.Transaction
import com.example.data.TransactionStatus
import com.example.data.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class LedgerRulesTest {

    private val bank = Account(accountId = "bank", accountName = "HDFC", accountType = "ASSET", openingBalance = 1000.0, asOfDate = 0L)
    private val card = Account(accountId = "card", accountName = "Card", accountType = "LIABILITY", openingBalance = 200.0, asOfDate = 0L)
    private val loan = Account(
        accountId = "loan", accountName = "Home Loan", accountType = "LIABILITY",
        openingBalance = -100000.0, asOfDate = 0L, customMainCategory = "Loans", annualInterestRate = 9.0
    )

    private fun time(y: Int, m: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    private fun tx(
        type: TransactionType, amount: Double, account: String, category: String = "Food",
        target: String? = null, at: Long = time(2026, 11, 4), sms: String? = null,
        status: TransactionStatus = TransactionStatus.CONFIRMED
    ) = LedgerRules.toLedger(
        Transaction(
            amount = amount, type = type, category = category, accountId = account,
            targetAccountId = target, timestamp = at, date = at, originalSmsText = sms, status = status
        )
    )

    @Test fun `account type comes from the grouping`() {
        assertEquals(AccountType.LOAN, loan.type)
        assertEquals(AccountType.CREDIT_CARD, card.type)
        assertEquals(AccountType.SAVINGS_ACCOUNT, bank.type)
        val oldLoan = Account(accountType = "LIABILITY", monthlyEmi = 25000.0)
        assertEquals("Loans", oldLoan.mainCategory)
        assertEquals(AccountType.LOAN, oldLoan.type)
    }

    @Test fun `asset balance`() {
        val txs = listOf(
            tx(TransactionType.EXPENSE, 300.0, "bank"),
            tx(TransactionType.INCOME, 50.0, "bank", "Salary"),
            tx(TransactionType.EXPENSE, 999.0, "bank", status = TransactionStatus.PENDING_REVIEW)
        )
        assertEquals(750.0, LedgerRules.balance(bank, txs), 0.001)
    }

    @Test fun `card dues go up with spend and down with payment`() {
        val txs = listOf(
            tx(TransactionType.EXPENSE, 100.0, "card"),
            tx(TransactionType.TRANSFER, 250.0, "bank", "Transfer", target = "card")
        )
        assertEquals(50.0, LedgerRules.balance(card, txs), 0.001)
        assertEquals(750.0, LedgerRules.balance(bank, txs), 0.001)
    }

    @Test fun `loan principal payment moves balance towards zero`() {
        val txs = listOf(tx(TransactionType.TRANSFER, 5000.0, "bank", "Transfer", target = "loan"))
        assertEquals(-95000.0, LedgerRules.balance(loan, txs), 0.001)
    }

    @Test fun `reconciliation adjustment for asset`() {
        val txs = listOf(tx(TransactionType.EXPENSE, 300.0, "bank"))
        val adj = LedgerRules.reconciliationAdjustment(bank, txs, time(2026, 11, 4, 0), 650.0)
        assertNotNull(adj)
        assertEquals(TransactionType.EXPENSE, adj!!.type)
        assertEquals(50.0, adj.amount, 0.001)
        // After posting it, the books agree with the statement.
        assertEquals(650.0, LedgerRules.balance(bank, txs + LedgerRules.toLedger(adj)), 0.001)
    }

    @Test fun `reconciliation adjustment for card and loan`() {
        val cardAdj = LedgerRules.reconciliationAdjustment(card, emptyList(), time(2026, 11, 4), 260.0)!!
        assertEquals(260.0, LedgerRules.balance(card, listOf(LedgerRules.toLedger(cardAdj))), 0.001)

        val loanAdj = LedgerRules.reconciliationAdjustment(loan, emptyList(), time(2026, 11, 4), 99000.0)!!
        assertEquals(-99000.0, LedgerRules.balance(loan, listOf(LedgerRules.toLedger(loanAdj))), 0.001)
    }

    @Test fun `no adjustment when the books agree`() {
        assertNull(LedgerRules.reconciliationAdjustment(bank, emptyList(), time(2026, 11, 4), 1000.0))
    }

    @Test fun `later transactions are not counted in reconciliation`() {
        val later = tx(TransactionType.EXPENSE, 400.0, "bank", at = time(2026, 11, 10))
        assertNull(LedgerRules.reconciliationAdjustment(bank, listOf(later), time(2026, 11, 4), 1000.0))
    }

    @Test fun `transfer counterpart needs same amount, own account and a short gap`() {
        val debit = tx(TransactionType.EXPENSE, 5000.0, "bank", at = time(2026, 11, 4, 10), sms = "Rs 5000 debited from a/c XX12")
        val own = setOf("bank", "card")
        val found = LedgerRules.findTransferCounterpart(listOf(debit), 5000.0, time(2026, 11, 4, 11), true, "card", own, "Rs 5000 credited")
        assertEquals(debit.id, found?.id)

        // Too far apart in time.
        assertNull(LedgerRules.findTransferCounterpart(listOf(debit), 5000.0, time(2026, 11, 4, 18), true, "card", own, "Rs 5000 credited"))
        // A refund is not a transfer.
        assertNull(LedgerRules.findTransferCounterpart(listOf(debit), 5000.0, time(2026, 11, 4, 11), true, "card", own, "Refund of Rs 5000 credited"))
        // Not one of the family's accounts.
        assertNull(LedgerRules.findTransferCounterpart(listOf(debit), 5000.0, time(2026, 11, 4, 11), true, "card", setOf("card"), "Rs 5000 credited"))
        // A manual entry (no SMS) is never merged.
        val manual = debit.copy(originalSmsText = null)
        assertNull(LedgerRules.findTransferCounterpart(listOf(manual), 5000.0, time(2026, 11, 4, 11), true, "card", own, "Rs 5000 credited"))
    }

    @Test fun `monthly spend only counts this month`() {
        val budget = Budget(category = "Food", monthlyLimit = 1000.0)
        val txs = listOf(
            tx(TransactionType.EXPENSE, 400.0, "bank", at = time(2026, 11, 2)),
            tx(TransactionType.EXPENSE, 300.0, "bank", at = time(2026, 10, 30)),
            tx(TransactionType.EXPENSE, 50.0, "bank", "Fuel", at = time(2026, 11, 3)),
            tx(TransactionType.EXPENSE, 70.0, "bank", at = time(2026, 11, 3), status = TransactionStatus.PENDING_REVIEW)
        )
        val spent = LedgerRules.monthlySpent(budget, txs, time(2026, 11, 4)) { Pair("", it) }
        assertEquals(400.0, spent, 0.001)
    }
}
