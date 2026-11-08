package com.example.domain

import com.example.data.Account
import com.example.data.Reminder
import com.example.data.Reserve
import com.example.data.Transaction
import com.example.data.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class FreeToSpendRulesTest {

    private fun time(y: Int, m: Int, d: Int, h: Int = 12): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    private fun day(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_MONTH)
    private fun month(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.MONTH) + 1

    private val me = "me"
    private val partner = "partner"
    private val myBank = Account(accountId = "myBank", accountType = "ASSET", openingBalance = 50_000.0, asOfDate = 0L, ownerUserId = me)
    private val joint = Account(accountId = "joint", accountType = "ASSET", openingBalance = 20_000.0, asOfDate = 0L, ownerUserId = "SHARED")
    private val theirBank = Account(accountId = "theirBank", accountType = "ASSET", openingBalance = 90_000.0, asOfDate = 0L, ownerUserId = partner)
    private val fd = Account(accountId = "fd", accountType = "ASSET", customMainCategory = "Fixed Deposits", openingBalance = 100_000.0, asOfDate = 0L, ownerUserId = me)
    private val myCard = Account(accountId = "myCard", accountName = "My Card", accountType = "LIABILITY", asOfDate = 0L, ownerUserId = me, billingCycleDay = 15, paymentDueDay = 5)
    private val theirCard = Account(accountId = "theirCard", accountType = "LIABILITY", asOfDate = 0L, ownerUserId = partner)
    private val accounts = listOf(myBank, joint, theirBank, fd, myCard, theirCard)

    private fun spend(amount: Double, card: String, at: Long) = LedgerRules.toLedger(
        Transaction(amount = amount, type = TransactionType.EXPENSE, category = "Food", accountId = card, timestamp = at, date = at)
    )
    private fun pay(amount: Double, card: String, at: Long) = LedgerRules.toLedger(
        Transaction(amount = amount, type = TransactionType.TRANSFER, category = "Transfer", accountId = "myBank", targetAccountId = card, timestamp = at, date = at)
    )

    @Test fun `only my accounts, joint accounts and my cards count`() {
        val now = time(2026, 11, 20)
        val txs = listOf(
            spend(10_000.0, "myCard", time(2026, 11, 10)),
            spend(3_000.0, "myCard", time(2026, 11, 18)),
            spend(7_000.0, "theirCard", time(2026, 11, 18))
        )
        val b = FreeToSpendRules.compute(me, accounts, txs, emptyList(), emptyList(), 0, now)
        assertEquals(70_000.0, b.bankAndCash, 0.01)   // my bank + joint; not the FD or partner's bank
        assertEquals(13_000.0, b.cardDues, 0.01)      // billed and unbilled; not the partner's card
        assertEquals(57_000.0, b.freeToSpend, 0.01)

        val partnerView = FreeToSpendRules.compute(partner, accounts, txs, emptyList(), emptyList(), 0, now)
        assertEquals(110_000.0 - 7_000.0, partnerView.freeToSpend, 0.01)
    }

    @Test fun `card dues split into billed and spent since statement`() {
        val txs = listOf(
            spend(10_000.0, "myCard", time(2026, 11, 10)),
            pay(4_000.0, "myCard", time(2026, 11, 17)),
            spend(3_000.0, "myCard", time(2026, 11, 18))
        )
        val line = FreeToSpendRules.cardLine(myCard, txs, time(2026, 11, 20))
        assertEquals(9_000.0, line.owed, 0.01)
        assertEquals(6_000.0, line.billed, 0.01)
        assertEquals(3_000.0, line.spentSinceStatement, 0.01)
    }

    @Test fun `bills before the next salary count, card bills and partner's bills do not`() {
        val now = time(2026, 11, 20)
        val reminders = listOf(
            Reminder(id = "rent", amount = 15_000.0, dueDate = time(2026, 11, 28), ownerUserId = me),
            Reminder(id = "late", amount = 2_000.0, dueDate = time(2026, 12, 5), ownerUserId = me),
            Reminder(id = "overdue", amount = 500.0, dueDate = time(2026, 11, 15), ownerUserId = me),
            Reminder(id = "theirs", amount = 4_000.0, dueDate = time(2026, 11, 25), ownerUserId = partner),
            Reminder(id = "jointBill", amount = 1_000.0, dueDate = time(2026, 11, 25), accountId = "joint"),
            Reminder(id = "card", amount = 9_000.0, dueDate = time(2026, 11, 25), accountId = "myCard", statementDate = time(2026, 11, 15)),
            Reminder(id = "paid", amount = 800.0, dueDate = time(2026, 11, 22), isPaid = true, ownerUserId = me)
        )
        val b = FreeToSpendRules.compute(me, accounts, emptyList(), reminders, emptyList(), salaryDay = 1, now = now)
        assertEquals(1, day(b.horizon)); assertEquals(12, month(b.horizon))
        assertEquals(listOf("overdue", "jointBill", "rent"), b.bills.map { it.reminder.id })
        assertEquals(16_500.0, b.upcomingBills, 0.01)
    }

    @Test fun `a paid monthly bill counts again when the next one is before salary`() {
        val r = Reminder(amount = 1_200.0, dueDate = time(2026, 11, 2), isRecurring = true, isPaid = true, ownerUserId = me)
        val horizon = FreeToSpendRules.nextSalary(5, time(2026, 11, 20))
        assertEquals(time(2026, 12, 2), FreeToSpendRules.billDue(r, accounts, horizon))
        assertNull(FreeToSpendRules.billDue(r, accounts, FreeToSpendRules.nextSalary(1, time(2026, 11, 20))))
    }

    @Test fun `salary day is clamped and rolls to next month once passed`() {
        val feb = FreeToSpendRules.nextSalary(31, time(2027, 2, 10))
        assertEquals(28, day(feb)); assertEquals(2, month(feb))
        val next = FreeToSpendRules.nextSalary(10, time(2026, 11, 10, 9))
        assertEquals(10, day(next)); assertEquals(12, month(next))
        val noDay = FreeToSpendRules.nextSalary(0, time(2026, 11, 10))
        assertEquals(time(2026, 12, 10), noDay)
    }

    @Test fun `reserves lower the figure of whoever owns the account they are kept in`() {
        val reserves = listOf(
            Reserve(id = "insurance", amount = 12_000.0, keptInAccountId = "myBank"),
            Reserve(id = "trip", amount = 5_000.0, keptInAccountId = "joint"),
            Reserve(id = "gift", amount = 3_000.0, keptInAccountId = "theirBank"),
            Reserve(id = "loose", amount = 1_000.0, createdByUserId = me)
        )
        val mine = FreeToSpendRules.compute(me, accounts, emptyList(), emptyList(), reserves, 0, time(2026, 11, 20))
        assertEquals(18_000.0, mine.reserved, 0.01)
        val theirs = FreeToSpendRules.compute(partner, accounts, emptyList(), emptyList(), reserves, 0, time(2026, 11, 20))
        assertEquals(8_000.0, theirs.reserved, 0.01)
    }

    @Test fun `monthly top-up happens once a month and stops at the target`() {
        val r = Reserve(amount = 9_000.0, target = 10_000.0, monthlyContribution = 2_000.0, lastContributionMonth = "2026_10")
        val topped = FreeToSpendRules.topUp(r, time(2026, 11, 1))!!
        assertEquals(10_000.0, topped.amount, 0.01)
        assertEquals("2026_11", topped.lastContributionMonth)
        assertNull(FreeToSpendRules.topUp(topped, time(2026, 11, 20)))
        assertEquals(1_000.0, FreeToSpendRules.topUp(Reserve(monthlyContribution = 1_000.0), time(2026, 11, 1))!!.amount, 0.01)
    }

    @Test fun `needed per month to reach a target on time`() {
        val r = Reserve(amount = 6_000.0, target = 24_000.0, targetDate = time(2027, 5, 1))
        assertEquals(3_000.0, FreeToSpendRules.neededPerMonth(r, time(2026, 11, 20))!!, 0.01)
    }
}
