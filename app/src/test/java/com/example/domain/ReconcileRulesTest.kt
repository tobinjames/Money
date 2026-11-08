package com.example.domain

import com.example.data.Account
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
}
