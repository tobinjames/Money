package com.example.domain

import com.example.data.Account
import com.example.data.AccountType
import com.example.data.Budget
import com.example.data.Transaction
import com.example.data.TransactionStatus
import com.example.data.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            // Waiting for review: the amount and account are known, so it already counts.
            tx(TransactionType.EXPENSE, 100.0, "bank", status = TransactionStatus.PENDING_REVIEW),
            // A suspected duplicate waiting for review does not, and nor does an ignored one.
            tx(TransactionType.EXPENSE, 999.0, "bank", status = TransactionStatus.PENDING_REVIEW).copy(isProbablyDuplicate = true),
            tx(TransactionType.EXPENSE, 500.0, "bank").copy(isIgnored = true)
        )
        assertEquals(650.0, LedgerRules.balance(bank, txs), 0.001)
        assertEquals(650.0, LedgerRules.balances(listOf(bank), txs)["bank"]!!, 0.001)
    }

    @Test fun `card payment sms can be days apart`() {
        val own = setOf("bank", "card")
        val debit = tx(TransactionType.EXPENSE, 5000.0, "bank", at = time(2026, 11, 4, 10), sms = "Rs 5000 debited")
        // Next day's "payment received" on the card still pairs with the bank debit...
        assertNotNull(LedgerRules.findTransferCounterpart(listOf(debit), 5000.0, time(2026, 11, 5, 18), true, "card", own, "Rs 5000 credited to card", setOf("card")))
        // ...but a credit to another bank account a day later does not.
        assertNull(LedgerRules.findTransferCounterpart(listOf(debit), 5000.0, time(2026, 11, 5, 18), true, "bank2", own + "bank2", "Rs 5000 credited", setOf("card")))
        // A bank debit that already named the card became a transfer; the card's SMS joins it once.
        val transfer = tx(TransactionType.TRANSFER, 5000.0, "bank", "Transfer", target = "card", at = time(2026, 11, 4, 10), sms = "[Debit SMS]: paid")
        assertNotNull(LedgerRules.findRecordedTransfer(listOf(transfer), 5000.0, time(2026, 11, 5), true, "card", setOf("card")) { false })
        assertNull(LedgerRules.findRecordedTransfer(listOf(transfer), 5000.0, time(2026, 11, 5), true, "card", setOf("card")) { true })
    }

    @Test fun `loan payment split`() {
        // One month's interest on 1,00,000 at 12% is 1,000.
        assertEquals(Pair(1000.0, 9000.0), LedgerRules.emiSplit(10000.0, 100000.0, 12.0, false))
        // Never more interest than was paid; a second payment in the month is all principal.
        assertEquals(Pair(500.0, 0.0), LedgerRules.emiSplit(500.0, 100000.0, 12.0, false))
        assertEquals(Pair(0.0, 50000.0), LedgerRules.emiSplit(50000.0, 100000.0, 12.0, true))
        assertEquals(Pair(0.0, 10000.0), LedgerRules.emiSplit(10000.0, 100000.0, 0.0, false))
        val paid = tx(TransactionType.TRANSFER, 9000.0, "bank", "Transfer", target = "loan", at = time(2026, 11, 5)).copy(isLoanEmi = true)
        assertTrue(LedgerRules.loanPaidInMonth("loan", listOf(paid), time(2026, 11, 20)))
        assertFalse(LedgerRules.loanPaidInMonth("loan", listOf(paid), time(2026, 12, 5)))
    }

    @Test fun `spending nets refunds and leaves out investment changes`() {
        val isExpense = { c: String -> c == "Food" }
        assertEquals(300.0, LedgerRules.spendOf(tx(TransactionType.EXPENSE, 300.0, "bank"), isExpense), 0.001)
        assertEquals(-50.0, LedgerRules.spendOf(tx(TransactionType.INCOME, 50.0, "bank", "Food"), isExpense), 0.001)
        assertEquals(0.0, LedgerRules.incomeOf(tx(TransactionType.INCOME, 50.0, "bank", "Food"), isExpense), 0.001)
        assertEquals(900.0, LedgerRules.incomeOf(tx(TransactionType.INCOME, 900.0, "bank", "Salary"), isExpense), 0.001)
        assertEquals(0.0, LedgerRules.spendOf(tx(TransactionType.EXPENSE, 700.0, "bank", LedgerRules.INVESTMENT_CHANGE_CATEGORY), isExpense), 0.001)
        assertEquals(0.0, LedgerRules.spendOf(tx(TransactionType.EXPENSE, 70.0, "bank", status = TransactionStatus.PENDING_REVIEW), isExpense), 0.001)
        // A mutual fund losing value is booked as an investment change, not as spending.
        val fund = Account(accountId = "mf", accountType = "ASSET", customMainCategory = "Mutual Funds", openingBalance = 1000.0, asOfDate = 0L)
        assertEquals(LedgerRules.INVESTMENT_CHANGE_CATEGORY, LedgerRules.reconciliationAdjustment(fund, emptyList(), time(2026, 11, 4), 900.0)!!.category)
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

    @Test fun `balances in one pass match balance per account`() {
        val bank = com.example.data.Account(accountId = "b", accountType = "ASSET", openingBalance = 1000.0, asOfDate = 0L)
        val card = com.example.data.Account(accountId = "c", accountType = "LIABILITY", openingBalance = 50.0, asOfDate = 0L)
        val loan = com.example.data.Account(accountId = "l", accountType = "LIABILITY", customMainCategory = "Loans", openingBalance = -5000.0, asOfDate = 0L)
        val txs = listOf(
            LedgerRules.toLedger(com.example.data.Transaction(amount = 200.0, type = com.example.data.TransactionType.EXPENSE, category = "Food", accountId = "c")),
            LedgerRules.toLedger(com.example.data.Transaction(amount = 120.0, type = com.example.data.TransactionType.TRANSFER, accountId = "b", targetAccountId = "c")),
            LedgerRules.toLedger(com.example.data.Transaction(amount = 500.0, type = com.example.data.TransactionType.TRANSFER, accountId = "b", targetAccountId = "l")),
            LedgerRules.toLedger(com.example.data.Transaction(amount = 999.0, type = com.example.data.TransactionType.EXPENSE, category = "Food", accountId = "b", isIgnored = true))
        )
        val all = LedgerRules.balances(listOf(bank, card, loan), txs)
        listOf(bank, card, loan).forEach { org.junit.Assert.assertEquals(LedgerRules.balance(it, txs), all[it.accountId]!!, 0.001) }
        org.junit.Assert.assertEquals(380.0, all["b"]!!, 0.001)
        org.junit.Assert.assertEquals(130.0, all["c"]!!, 0.001)
        org.junit.Assert.assertEquals(-4500.0, all["l"]!!, 0.001)
    }
}
