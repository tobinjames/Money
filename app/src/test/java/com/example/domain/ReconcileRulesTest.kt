package com.example.domain

import com.example.data.Account
import com.example.data.Transaction
import com.example.data.TransactionType
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ReconcileRulesTest {

    private fun time(y: Int, m: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    private val now = time(2026, 11, 14) // a Saturday

    @Test fun `bank and cards after 30 days, FDs and loans after 60, oldest first`() {
        val accounts = listOf(
            Account(accountId = "fresh", accountType = "ASSET", ownerUserId = "me", asOfDate = 0L, lastReconciledDate = time(2026, 11, 1)),
            Account(accountId = "old", accountType = "ASSET", ownerUserId = "me", asOfDate = 0L, lastReconciledDate = time(2026, 10, 1)),
            Account(accountId = "older", accountType = "LIABILITY", ownerUserId = "SHARED", asOfDate = 0L, lastReconciledDate = time(2026, 9, 1)),
            Account(accountId = "neverButNew", accountType = "ASSET", ownerUserId = "me", asOfDate = time(2026, 11, 1)),
            Account(accountId = "neverOld", accountType = "ASSET", ownerUserId = "me", asOfDate = time(2026, 8, 1)),
            Account(accountId = "theirs", accountType = "ASSET", ownerUserId = "partner", asOfDate = 0L),
            Account(accountId = "fdRecent", accountType = "ASSET", customMainCategory = "Fixed Deposits", ownerUserId = "me", asOfDate = 0L, lastReconciledDate = time(2026, 9, 20)),
            Account(accountId = "loanOld", accountType = "LIABILITY", customMainCategory = "Loans", ownerUserId = "me", asOfDate = 0L, lastReconciledDate = time(2026, 9, 1)),
            Account(accountId = "food", accountType = "EXPENSE", ownerUserId = "me", asOfDate = 0L)
        )
        assertEquals(listOf("neverOld", "older", "loanOld", "old"), ReconcileRules.stale("me", accounts, now).map { it.accountId })
    }

    @Test fun `reminders only on Friday evening and the weekend`() {
        assertFalse(ReconcileRules.isWeekendReminderTime(time(2026, 11, 13, 17))) // Friday afternoon
        assertTrue(ReconcileRules.isWeekendReminderTime(time(2026, 11, 13, 21)))  // Friday night
        assertTrue(ReconcileRules.isWeekendReminderTime(time(2026, 11, 14, 9)))   // Saturday
        assertTrue(ReconcileRules.isWeekendReminderTime(time(2026, 11, 15, 15)))  // Sunday
        assertFalse(ReconcileRules.isWeekendReminderTime(time(2026, 11, 16, 9)))  // Monday
    }

    private fun spend(amount: Double, at: Long) =
        LedgerRules.toLedger(Transaction(amount = amount, date = at, timestamp = at, type = TransactionType.EXPENSE, category = "Food", accountId = "bank"))

    private val bank = Account(
        accountId = "bank", accountType = "ASSET", ownerUserId = "me", openingBalance = 1000.0, asOfDate = time(2026, 9, 1),
        smsBalance = 700.0, smsBalanceDate = time(2026, 11, 10)
    )

    @Test fun `bank SMS balance is compared with the app at the moment of the SMS`() {
        val txs = listOf(spend(300.0, time(2026, 11, 10)), spend(50.0, time(2026, 11, 12)))
        val check = ReconcileRules.bankCheck(bank, txs)!!
        assertEquals(700.0, check.appBalance, 0.001)
        assertTrue(check.matches)

        // A spend the app missed: the bank has less than the app shows.
        val off = ReconcileRules.bankCheck(bank.copy(smsBalance = 620.0), txs)!!
        assertFalse(off.matches)
        assertEquals(-80.0, off.difference, 0.001)
    }

    @Test fun `a matching bank SMS counts as checked, a differing one does not`() {
        val txs = listOf(spend(300.0, time(2026, 11, 10)))
        assertEquals(emptyList<String>(), ReconcileRules.stale("me", listOf(bank), now, txs).map { it.accountId })
        assertEquals(listOf("bank"), ReconcileRules.stale("me", listOf(bank.copy(smsBalance = 1.0)), now, txs).map { it.accountId })
    }

    @Test fun `no bank check for cash, cards, or after a later reconcile by hand`() {
        assertNull(ReconcileRules.bankCheck(bank.copy(customMainCategory = "Cash"), emptyList()))
        assertNull(ReconcileRules.bankCheck(bank.copy(accountType = "LIABILITY"), emptyList()))
        assertNull(ReconcileRules.bankCheck(bank.copy(smsBalance = null), emptyList()))
        assertNull(ReconcileRules.bankCheck(bank.copy(lastReconciledDate = time(2026, 11, 10)), emptyList()))
    }

    @Test fun `closing time is now for today and the end of the day for a past date`() {
        assertEquals(now, ReconcileRules.closingTime(2026, 10, 14, now))
        assertEquals(now, ReconcileRules.closingTime(2026, 11, 1, now))
        val past = ReconcileRules.closingTime(2026, 10, 1, now)
        assertTrue(past > time(2026, 11, 1, 23) && past < time(2026, 11, 2, 0))
    }
}
