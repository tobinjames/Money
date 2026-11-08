package com.example.domain

import com.example.data.Account
import com.example.data.Reminder
import com.example.data.Transaction
import com.example.data.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ReminderRulesTest {

    private fun time(y: Int, m: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    private fun day(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_MONTH)
    private fun month(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.MONTH) + 1

    private val card = Account(
        accountId = "card", accountName = "HDFC Card", accountType = "LIABILITY",
        openingBalance = 0.0, asOfDate = 0L, billingCycleDay = 15, paymentDueDay = 5
    )
    private val bank = Account(accountId = "bank", accountName = "Bank", accountType = "ASSET")

    private fun spend(amount: Double, at: Long) = LedgerRules.toLedger(
        Transaction(amount = amount, type = TransactionType.EXPENSE, category = "Food", accountId = "card", timestamp = at, date = at)
    )
    private fun payCard(amount: Double, at: Long) = LedgerRules.toLedger(
        Transaction(amount = amount, type = TransactionType.TRANSFER, category = "Transfer", accountId = "bank", targetAccountId = "card", timestamp = at, date = at)
    )

    @Test fun `recurring reminder on the 31st does not drift after February`() {
        var r = Reminder(title = "Rent", dueDate = time(2027, 1, 31, 9), isRecurring = true, isPaid = true)
        r = ReminderRules.rollForward(r, time(2027, 2, 20))!!
        assertEquals(28, day(r.dueDate)); assertEquals(2, month(r.dueDate)); assertFalse(r.isPaid)
        r = ReminderRules.rollForward(r.copy(isPaid = true), time(2027, 3, 20))!!
        assertEquals(31, day(r.dueDate)); assertEquals(3, month(r.dueDate))
    }

    @Test fun `unpaid or one-off reminders do not roll forward`() {
        val due = time(2027, 1, 10)
        assertNull(ReminderRules.rollForward(Reminder(dueDate = due, isRecurring = true, isPaid = false), time(2027, 2, 1)))
        assertNull(ReminderRules.rollForward(Reminder(dueDate = due, isRecurring = false, isPaid = true), time(2027, 2, 1)))
    }

    @Test fun `paid recurring reminder waits until 15 days before the next one`() {
        val r = Reminder(dueDate = time(2027, 1, 10), isRecurring = true, isPaid = true)
        assertNull(ReminderRules.rollForward(r, time(2027, 1, 20)))
        assertNotNull(ReminderRules.rollForward(r, time(2027, 1, 27)))
    }

    @Test fun `statement and due dates`() {
        val c = ReminderRules.latestStatement(card, time(2026, 11, 20))
        assertEquals("2026_11", c.key); assertEquals(15, day(c.statementDate))
        assertEquals(12, month(c.dueDate)); assertEquals(5, day(c.dueDate))
        val before = ReminderRules.latestStatement(card, time(2026, 11, 10))
        assertEquals("2026_10", before.key)
        val jan = ReminderRules.latestStatement(card, time(2027, 1, 3))
        assertEquals("2026_12", jan.key); assertEquals(1, month(jan.dueDate))
    }

    @Test fun `new card bill uses balance at statement date and is made once`() {
        val txs = listOf(spend(1000.0, time(2026, 11, 10)), spend(300.0, time(2026, 11, 18)))
        val (bill, cycle) = ReminderRules.newCardBill(card, txs, time(2026, 11, 20))!!
        assertEquals(1000.0, bill.amount, 0.001)
        assertEquals("cc_card_2026_11", bill.id)
        assertFalse(bill.isPaid)
        // Already made for this statement (even if the user deleted it): nothing new.
        assertNull(ReminderRules.newCardBill(card.copy(lastBillReminderCycle = cycle.key), txs, time(2026, 11, 25)))
        // Next statement: a new bill.
        assertNotNull(ReminderRules.newCardBill(card.copy(lastBillReminderCycle = cycle.key), txs, time(2026, 12, 16)))
    }

    @Test fun `card bill is marked paid when a payment covers it`() {
        val txs = mutableListOf(spend(1000.0, time(2026, 11, 10)))
        val (bill, _) = ReminderRules.newCardBill(card, txs, time(2026, 11, 20))!!
        txs += payCard(400.0, time(2026, 11, 25))
        assertNull(ReminderRules.refreshCardBill(bill, card, txs))
        txs += payCard(600.0, time(2026, 11, 28))
        assertTrue(ReminderRules.refreshCardBill(bill, card, txs)!!.isPaid)
    }

    @Test fun `late purchase before the statement updates the bill`() {
        val txs = mutableListOf(spend(1000.0, time(2026, 11, 10)))
        val (bill, _) = ReminderRules.newCardBill(card, txs, time(2026, 11, 20))!!
        txs += spend(200.0, time(2026, 11, 14))
        assertEquals(1200.0, ReminderRules.refreshCardBill(bill, card, txs)!!.amount, 0.001)
    }

    @Test fun `loans and cards without days get no automatic bills`() {
        val loan = card.copy(accountId = "loan", customMainCategory = "Loans")
        assertNull(ReminderRules.newCardBill(loan, emptyList(), time(2026, 11, 20)))
        assertNull(ReminderRules.newCardBill(card.copy(billingCycleDay = 0), emptyList(), time(2026, 11, 20)))
        assertNull(ReminderRules.newCardBill(bank, emptyList(), time(2026, 11, 20)))
    }

    @Test fun `alert stages`() {
        assertNull(ReminderRules.alertStage(20, false, false))
        assertEquals("t15", ReminderRules.alertStage(15, false, false))
        assertEquals("t15", ReminderRules.alertStage(12, false, false))
        assertEquals("t10", ReminderRules.alertStage(9, false, false))
        assertEquals("t5", ReminderRules.alertStage(5, false, false))
        assertEquals("d2", ReminderRules.alertStage(2, false, false))
        assertEquals("d-3", ReminderRules.alertStage(-3, false, false))
        assertNull(ReminderRules.alertStage(-8, false, false))
        assertEquals("mf", ReminderRules.alertStage(3, true, false))
        assertNull(ReminderRules.alertStage(8, true, false))
        // Partner's reminder: only once it is 3 days out and still unpaid, then daily.
        assertNull(ReminderRules.alertStage(9, false, true))
        assertNull(ReminderRules.alertStage(4, false, true))
        assertEquals("d3", ReminderRules.alertStage(3, false, true))
        assertEquals("d-1", ReminderRules.alertStage(-1, false, true))
        assertNull(ReminderRules.alertStage(5, true, true))
        assertEquals("mf", ReminderRules.alertStage(2, true, true))
    }
}
