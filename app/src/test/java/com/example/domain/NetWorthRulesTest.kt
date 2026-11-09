package com.example.domain

import com.example.data.Account
import com.example.data.Transaction
import com.example.data.TransactionType
import com.example.domain.NetWorthRules.Scope
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class NetWorthRulesTest {

    private fun time(y: Int, m: Int, d: Int): Long =
        Calendar.getInstance().apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis

    private val me = "me"
    private val partner = "partner"
    private val myBank = Account(accountId = "myBank", accountType = "ASSET", openingBalance = 50_000.0, asOfDate = 0L, ownerUserId = me)
    private val joint = Account(accountId = "joint", accountType = "ASSET", openingBalance = 20_000.0, asOfDate = 0L, ownerUserId = "SHARED")
    private val theirFd = Account(accountId = "theirFd", accountType = "ASSET", customMainCategory = "Fixed Deposits", openingBalance = 100_000.0, asOfDate = 0L, ownerUserId = partner)
    private val myCard = Account(accountId = "myCard", accountType = "LIABILITY", asOfDate = 0L, ownerUserId = me)
    private val loan = Account(accountId = "loan", accountType = "LIABILITY", customMainCategory = "Loans", openingBalance = 30_000.0, asOfDate = 0L, ownerUserId = partner)
    private val salary = Account(accountId = "Salary", accountType = "INCOME", ownerUserId = me)
    private val accounts = listOf(myBank, joint, theirFd, myCard, loan, salary)

    private fun tx(amount: Double, type: TransactionType, account: String, date: Long, category: String = "Food") =
        LedgerRules.toLedger(Transaction(amount = amount, type = type, accountId = account, category = category, date = date, timestamp = date))

    private val txs = listOf(
        tx(5_000.0, TransactionType.EXPENSE, "myCard", time(2026, 9, 10)),
        tx(10_000.0, TransactionType.INCOME, "myBank", time(2026, 10, 1), "Salary")
    )
    private val now = time(2026, 11, 9)

    @Test
    fun scopesAddUpWithJointSplitInHalf() {
        val mine = NetWorthRules.summarize(accounts, txs, me, Scope.MINE, 3, now).netWorth
        val theirs = NetWorthRules.summarize(accounts, txs, me, Scope.PARTNER, 3, now).netWorth
        val all = NetWorthRules.summarize(accounts, txs, me, Scope.COMBINED, 3, now).netWorth
        // Mine: 60k bank + 10k half joint - 5k card. Partner: 100k FD + 10k half joint - 30k loan.
        assertEquals(65_000.0, mine, 0.01)
        assertEquals(80_000.0, theirs, 0.01)
        assertEquals(145_000.0, all, 0.01)
    }

    @Test
    fun historyTracksMonthEnds() {
        val h = NetWorthRules.history(accounts, txs, me, Scope.MINE, 3, now)
        assertEquals(listOf(65_000.0 - 10_000.0, 65_000.0, 65_000.0), h.map { it.value })
        val first = Calendar.getInstance().apply { timeInMillis = h.first().monthStart }
        assertEquals(Calendar.SEPTEMBER, first.get(Calendar.MONTH))
        assertEquals(1, first.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun allocationSharesAddUpAndOwedIsSeparate() {
        val s = NetWorthRules.summarize(accounts, txs, me, Scope.COMBINED, 2, now) { if (it.ownerUserId == me) "Tobin" else if (it.ownerUserId == partner) "Partner" else "Joint" }
        assertEquals(1.0, s.byKind.sumOf { it.share }, 1e-9)
        assertEquals("Fixed deposits", s.byKind.first().label)
        assertEquals(180_000.0, s.assets, 0.01)
        assertEquals(35_000.0, s.owed, 0.01)
        assertEquals(listOf("Loans", "Credit cards"), s.owedByKind.map { it.label })
        assertEquals(setOf("Tobin", "Partner", "Joint"), s.byOwner.map { it.label }.toSet())
    }
}
