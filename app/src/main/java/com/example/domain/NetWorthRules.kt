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
        AccountType.MUTUAL_FUND -> "Investments"
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

    /** One of the user's own groups, or [UNGROUPED] for everything not in one. */
    data class Group(
        val name: String,
        /** Assets minus what's owed in this group, for the scope. */
        val net: Double,
        /** Accounts of this scope in the group with their signed value, largest first. */
        val members: List<Member>,
        val custom: Boolean = true
    )

    data class Member(val account: Account, val value: Double)

    /** Label for the accounts that are in none of the user's groups. */
    const val UNGROUPED = "Not in a group"

    fun groupName(account: Account): String = account.netWorthGroup.trim()

    /** The names of the user's groups across the whole household, A to Z, whatever the scope. */
    fun groupNames(accounts: List<Account>): List<String> =
        accounts.filter { countsForNetWorth(it) }.map { groupName(it) }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    /**
     * Net worth split by the user's own groups, largest first, with the accounts in no group last.
     * Each account is in at most one group, so the groups add up to net worth. Joint accounts are
     * halved for one partner like everywhere else. Empty when no group has been made.
     * A group with nothing in this scope (all its accounts are the partner's) still shows, at zero.
     */
    fun customGroups(
        accounts: List<Account>,
        myUid: String?,
        scope: Scope,
        balanceOf: (Account) -> Double = { it.balance }
    ): List<Group> {
        val names = groupNames(accounts)
        if (names.isEmpty()) return emptyList()
        val members = accounts.filter { countsForNetWorth(it) && share(it, scope, myUid) > 0.0 }
            .map { Member(it, signed(it, balanceOf(it)) * share(it, scope, myUid)) }
        fun group(name: String, list: List<Member>, custom: Boolean) =
            Group(name, list.sumOf { it.value }, list.sortedByDescending { kotlin.math.abs(it.value) }, custom)

        val byName = members.groupBy { groupName(it.account).lowercase() }
        val groups = names.map { group(it, byName[it.lowercase()].orEmpty(), true) }.sortedByDescending { it.net }
        val rest = byName[""].orEmpty()
        return if (rest.isEmpty()) groups else groups + group(UNGROUPED, rest, false)
    }

    /** Donut slices for the groups: only groups with a positive net get a slice. */
    fun groupSlices(groups: List<Group>): List<Slice> {
        val positive = groups.filter { it.net > 0.005 }
        val total = positive.sumOf { it.net }
        if (total <= 0.0) return emptyList()
        return positive.map { Slice(it.name, it.net, it.net / total) }.sortedByDescending { it.amount }
    }

    /**
     * What to save when a group is created, edited, renamed or removed: account id to its new
     * group name. [memberIds] end up in [name]; accounts that were in [oldName] and are no longer
     * picked leave it. A blank [name] removes [oldName]. Accounts that don't change are left out.
     */
    fun groupChanges(accounts: List<Account>, oldName: String?, name: String, memberIds: Set<String>): Map<String, String> {
        val newName = name.trim()
        val old = oldName?.trim().orEmpty()
        val changes = LinkedHashMap<String, String>()
        accounts.filter { countsForNetWorth(it) }.forEach { acc ->
            val current = groupName(acc)
            val wasIn = old.isNotEmpty() && current.equals(old, ignoreCase = true)
            val target = when {
                newName.isNotEmpty() && acc.accountId in memberIds -> newName
                wasIn -> ""
                else -> return@forEach
            }
            if (acc.netWorthGroup != target) changes[acc.accountId] = target
        }
        return changes
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
