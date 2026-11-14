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

    // Balances as the screen has them: card dues positive, loans negative.
    private val balances = mapOf("myBank" to 60_000.0, "joint" to 20_000.0, "theirFd" to 100_000.0, "myCard" to 5_000.0, "loan" to -30_000.0)
    private val grouped = accounts.map {
        when (it.accountId) {
            "myBank", "myCard" -> it.copy(netWorthGroup = "Daily")
            "joint" -> it.copy(netWorthGroup = "Emergency fund")
            "theirFd" -> it.copy(netWorthGroup = "emergency fund ")
            else -> it
        }
    }

    @Test
    fun noGroupsUntilOneIsMade() {
        assertEquals(emptyList<NetWorthRules.Group>(), NetWorthRules.customGroups(accounts, me, Scope.COMBINED) { balances[it.accountId] ?: 0.0 })
    }

    @Test
    fun customGroupsAddUpToNetWorth() {
        val all = NetWorthRules.customGroups(grouped, me, Scope.COMBINED) { balances[it.accountId] ?: 0.0 }
        // Emergency fund: 20k joint + 100k FD. Daily: 60k bank - 5k card. Left over: the 30k loan.
        assertEquals(listOf("Emergency fund", "Daily", NetWorthRules.UNGROUPED), all.map { it.name })
        assertEquals(listOf(120_000.0, 55_000.0, -30_000.0), all.map { it.net })
        assertEquals(145_000.0, all.sumOf { it.net }, 0.01)
        assertEquals(listOf(true, true, false), all.map { it.custom })

        // Mine: the joint account counts half, and the partner's FD and loan drop out.
        val mine = NetWorthRules.customGroups(grouped, me, Scope.MINE) { balances[it.accountId] ?: 0.0 }
        assertEquals(listOf("Daily", "Emergency fund"), mine.map { it.name })
        assertEquals(listOf(55_000.0, 10_000.0), mine.map { it.net })
        assertEquals(65_000.0, mine.sumOf { it.net }, 0.01)
    }

    @Test
    fun groupSlicesSkipGroupsThatOweMore() {
        val all = NetWorthRules.customGroups(grouped, me, Scope.COMBINED) { balances[it.accountId] ?: 0.0 }
        val slices = NetWorthRules.groupSlices(all)
        assertEquals(listOf("Emergency fund", "Daily"), slices.map { it.label })
        assertEquals(1.0, slices.sumOf { it.share }, 1e-9)
    }

    @Test
    fun groupChangesCoverCreateRenameAndRemove() {
        // New group with two accounts; one of them moves out of "Daily".
        assertEquals(
            mapOf("myBank" to "Travel", "loan" to "Travel"),
            NetWorthRules.groupChanges(grouped, null, " Travel ", setOf("myBank", "loan"))
        )
        // Rename "Daily" and drop the card from it.
        assertEquals(
            mapOf("myBank" to "Everyday", "myCard" to ""),
            NetWorthRules.groupChanges(grouped, "Daily", "Everyday", setOf("myBank"))
        )
        // Saving without changes only tidies the stray spelling.
        assertEquals(
            mapOf("theirFd" to "Emergency fund"),
            NetWorthRules.groupChanges(grouped, "Emergency fund", "Emergency fund", setOf("joint", "theirFd"))
        )
        // Removing a group frees its accounts and touches nothing else.
        assertEquals(
            mapOf("joint" to "", "theirFd" to ""),
            NetWorthRules.groupChanges(grouped, "emergency fund", "", emptySet())
        )
    }
}
