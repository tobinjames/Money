package com.example.domain

import com.example.data.Account
import com.example.data.AccountType
import com.example.data.Transaction
import com.example.data.TransactionStatus
import java.util.Calendar

/**
 * Net worth for one person or the whole family: what it is now, how it moved month by month,
 * and how it is spread across kinds of accounts. Pure, so it can be unit tested.
 *
 * Joint accounts count half for each partner, so "Mine" plus "Partner" equals "Combined".
 */
object NetWorthRules {

    enum class Scope { MINE, PARTNER, COMBINED }

    data class Point(val monthStart: Long, val value: Double)

    data class Slice(val label: String, val amount: Double, val share: Double)

    data class Summary(
        val netWorth: Double,
        val assets: Double,
        val owed: Double,
        /** Month-end net worth, oldest first; the last point is today. */
        val history: List<Point>,
        /** Assets by kind, largest first; shares add up to 1. */
        val byKind: List<Slice>,
        /** Net worth by owner (Combined only); shares of the positive total. */
        val byOwner: List<Slice>,
        /** What is owed, by kind (cards, loans), largest first. */
        val owedByKind: List<Slice>
    )

    /** How much of [account] belongs to [scope]: 0, ½ (a joint account for one partner) or 1. */
    fun share(account: Account, scope: Scope, myUid: String?): Double {
        if (scope == Scope.COMBINED || myUid == null) return 1.0
        val joint = account.ownerUserId.isBlank() || account.ownerUserId.equals(FreeToSpendRules.SHARED, true)
        if (joint) return 0.5
        val mine = account.ownerUserId == myUid
        return when (scope) {
            Scope.MINE -> if (mine) 1.0 else 0.0
            else -> if (mine) 0.0 else 1.0
        }
    }

    fun countsForNetWorth(account: Account) = account.accountType == "ASSET" || account.accountType == "LIABILITY"

    /** Signed contribution to net worth: assets add, card dues and loans take away. */
    fun signed(account: Account, balance: Double): Double = when {
        account.accountType == "ASSET" -> balance
        account.type == AccountType.LOAN -> balance // already negative
        account.accountType == "LIABILITY" -> -balance // card dues are positive
        else -> 0.0
    }

    /** A label for the kind of asset, from the account's group in the chart of accounts. */
    fun kindOf(account: Account): String = when (account.type) {
        AccountType.FIXED_DEPOSIT -> "Fixed deposits"
        AccountType.MUTUAL_FUND -> "Mutual funds"
        AccountType.LOAN -> "Loans"
        AccountType.CREDIT_CARD -> "Credit cards"
        AccountType.SAVINGS_ACCOUNT -> when {
            account.mainCategory.equals("Bank Accounts", true) -> "Bank"
            account.mainCategory.equals("Cash", true) -> "Cash"
            else -> account.mainCategory.ifBlank { "Other assets" }
        }
    }

    fun summarize(
        accounts: List<Account>,
        ledgerTxs: List<Transaction>,
        myUid: String?,
        scope: Scope,
        months: Int,
        now: Long,
        ownerName: (Account) -> String = { "" }
    ): Summary {
        val counted = accounts.filter { countsForNetWorth(it) && share(it, scope, myUid) > 0.0 }
        val balances = LedgerRules.balances(counted, ledgerTxs)
        val value = counted.associate { it.accountId to signed(it, balances[it.accountId] ?: 0.0) * share(it, scope, myUid) }

        val assets = counted.sumOf { (value[it.accountId] ?: 0.0).coerceAtLeast(0.0) }
        val owed = counted.sumOf { -(value[it.accountId] ?: 0.0).coerceAtMost(0.0) }

        fun slices(groups: Map<String, Double>): List<Slice> {
            val positive = groups.filterValues { it > 0.005 }
            val total = positive.values.sum()
            if (total <= 0.0) return emptyList()
            return positive.map { (k, v) -> Slice(k, v, v / total) }.sortedByDescending { it.amount }
        }

        val byKind = slices(counted.groupBy { kindOf(it) }
            .mapValues { (_, list) -> list.sumOf { (value[it.accountId] ?: 0.0).coerceAtLeast(0.0) } })
        val owedByKind = slices(counted.groupBy { kindOf(it) }
            .mapValues { (_, list) -> list.sumOf { -(value[it.accountId] ?: 0.0).coerceAtMost(0.0) } })
        val byOwner = if (scope == Scope.COMBINED) {
            slices(counted.groupBy { ownerName(it) }.mapValues { (_, list) -> list.sumOf { value[it.accountId] ?: 0.0 } })
        } else emptyList()

        return Summary(
            netWorth = assets - owed,
            assets = assets,
            owed = owed,
            history = history(counted, ledgerTxs, myUid, scope, months, now),
            byKind = byKind,
            byOwner = byOwner,
            owedByKind = owedByKind
        )
    }

    /**
     * Net worth at the end of each of the last [months] months (the current month up to now),
     * in one pass over the transactions. Opening balances count from the start, as they do for
     * today's balances, so a month before any activity shows the opening position.
     */
    fun history(
        accounts: List<Account>,
        ledgerTxs: List<Transaction>,
        myUid: String?,
        scope: Scope,
        months: Int,
        now: Long
    ): List<Point> {
        if (months <= 0) return emptyList()
        val counted = accounts.filter { countsForNetWorth(it) && share(it, scope, myUid) > 0.0 }
        val ids = counted.map { it.accountId }.toHashSet()
        val starts = monthStarts(months, now)
        val ends = starts.drop(1) + (now + 1)

        val txs = ledgerTxs.filter {
            LedgerRules.countsInBalance(it) && it.date < ends.last() &&
                (it.debitAccountId in ids || it.creditAccountId in ids)
        }.sortedBy { it.date }

        val debits = HashMap<String, Double>()
        val credits = HashMap<String, Double>()
        var i = 0
        return starts.indices.map { m ->
            while (i < txs.size && txs[i].date < ends[m]) {
                val t = txs[i]
                if (t.debitAccountId in ids) debits[t.debitAccountId] = (debits[t.debitAccountId] ?: 0.0) + t.amount
                if (t.creditAccountId in ids) credits[t.creditAccountId] = (credits[t.creditAccountId] ?: 0.0) + t.amount
                i++
            }
            val total = counted.sumOf {
                signed(it, LedgerRules.fromSums(it, debits[it.accountId] ?: 0.0, credits[it.accountId] ?: 0.0)) * share(it, scope, myUid)
            }
            Point(starts[m], total)
        }
    }

    /** How many months of history there are: from the oldest transaction or account start to now (at least 2). */
    fun monthsAvailable(accounts: List<Account>, ledgerTxs: List<Transaction>, now: Long): Int {
        val oldest = (ledgerTxs.asSequence().map { it.date }.filter { it > 0 } +
            accounts.asSequence().filter { countsForNetWorth(it) }.map { it.asOfDate }.filter { it > 0 })
            .minOrNull() ?: now
        val a = Calendar.getInstance().apply { timeInMillis = minOf(oldest, now) }
        val b = Calendar.getInstance().apply { timeInMillis = now }
        val diff = (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 12 + (b.get(Calendar.MONTH) - a.get(Calendar.MONTH)) + 1
        return diff.coerceAtLeast(2)
    }

    private fun monthStarts(months: Int, now: Long): List<Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, -(months - 1))
        }
        return (0 until months).map { cal.timeInMillis.also { _ -> cal.add(Calendar.MONTH, 1) } }
    }
}
