package com.example.domain

import com.example.data.Account
import com.example.data.AccountType
import com.example.data.Reminder
import com.example.data.Reserve
import com.example.data.Transaction
import com.example.data.TransactionStatus
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

/**
 * "Free to spend": the money one person can really spend until their next salary.
 *
 *   bank + cash
 *   - everything owed on their credit cards (billed and not yet billed)
 *   - their bills due before the next salary
 *   - reserves kept in their accounts
 *
 * It is personal: only that person's accounts count, plus joint (SHARED) accounts, which count
 * for both partners. Reserves are shared by the household but live in one account each.
 */
object FreeToSpendRules {

    const val SHARED = "SHARED"

    /** Without a salary day, bills in the next this-many days are counted. */
    const val DEFAULT_HORIZON_DAYS = 30

    data class CardLine(
        val account: Account,
        /** Everything owed on the card now. */
        val owed: Double,
        /** Of [owed], the part already on a statement and not yet paid. */
        val billed: Double,
        /** Purchases since the last statement (what next month's bill is building up to). */
        val spentSinceStatement: Double
    )

    data class BillLine(val reminder: Reminder, val dueDate: Long)

    data class Breakdown(
        val bankAndCash: Double,
        val accounts: List<Pair<Account, Double>>,
        val cardDues: Double,
        val cards: List<CardLine>,
        val upcomingBills: Double,
        val bills: List<BillLine>,
        val reserved: Double,
        val reserves: List<Reserve>,
        /** Bills due up to this time are counted. */
        val horizon: Long,
        val freeToSpend: Double
    )

    /** An account or reminder owner that counts for [myUid]: theirs, or joint. */
    fun countsFor(ownerUserId: String, myUid: String): Boolean =
        ownerUserId == myUid || ownerUserId.equals(SHARED, ignoreCase = true)

    /** A bank or cash account (not FDs or mutual funds, which aren't money in hand). */
    fun isLiquid(account: Account): Boolean =
        account.accountType == "ASSET" && account.type == AccountType.SAVINGS_ACCOUNT

    fun isCard(account: Account): Boolean =
        account.accountType == "LIABILITY" && account.type == AccountType.CREDIT_CARD

    /**
     * The next salary date after [now]: the given day of this month or next (clamped to short
     * months). With no salary day set, [DEFAULT_HORIZON_DAYS] from now.
     */
    fun nextSalary(salaryDay: Int, now: Long): Long {
        if (salaryDay !in 1..31) return now + DEFAULT_HORIZON_DAYS * ReminderRules.DAY_MS
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        var year = cal.get(Calendar.YEAR)
        var month = cal.get(Calendar.MONTH)
        var pay = ReminderRules.dateOnDay(year, month, salaryDay, 0)
        if (pay <= now) {
            if (month == 11) { year += 1; month = 0 } else month += 1
            pay = ReminderRules.dateOnDay(year, month, salaryDay, 0)
        }
        return pay
    }

    /** One card's dues split into billed and still building up. */
    fun cardLine(card: Account, txs: List<Transaction>, now: Long, balance: Double = LedgerRules.balance(card, txs)): CardLine {
        val owed = max(0.0, balance)
        if (card.billingCycleDay !in 1..31) return CardLine(card, owed, owed, 0.0)
        val cycle = ReminderRules.latestStatement(card, now)
        val statement = LedgerRules.balance(card, txs, upTo = cycle.statementDate)
        val paid = ReminderRules.paymentsSince(card, txs, cycle.statementDate)
        val billed = min(owed, max(0.0, statement - paid))
        val spent = txs.filter {
            !it.isIgnored && it.status == TransactionStatus.CONFIRMED &&
                it.creditAccountId == card.accountId && it.date > cycle.statementDate && it.date <= now
        }.sumOf { it.amount }
        return CardLine(card, owed, billed, spent)
    }

    /**
     * When an unpaid (or soon to repeat) reminder next needs money, or null when it doesn't
     * before [horizon]. Card bills are left out: the card's whole balance is already counted.
     */
    fun billDue(r: Reminder, accounts: List<Account>, horizon: Long): Long? {
        val linked = accounts.find { it.accountId == r.accountId }
        if (r.statementDate > 0L || (linked != null && isCard(linked))) return null
        val due = when {
            !r.isPaid -> r.dueDate
            r.isRecurring -> ReminderRules.nextMonthlyDue(r)
            else -> return null
        }
        return if (due <= horizon) due else null
    }

    /** Whose reminder it is: the linked account's owner, else whoever made it. */
    fun reminderOwner(r: Reminder, accounts: List<Account>): String =
        accounts.find { it.accountId == r.accountId }?.ownerUserId?.takeIf { it.isNotBlank() } ?: r.ownerUserId

    /** The account a reserve is kept in decides whose figure it lowers. */
    fun reserveCountsFor(reserve: Reserve, accounts: List<Account>, myUid: String): Boolean {
        val account = accounts.find { it.accountId == reserve.keptInAccountId }
        return if (account != null) countsFor(account.ownerUserId, myUid) else reserve.createdByUserId == myUid
    }

    fun compute(
        myUid: String,
        accounts: List<Account>,
        txs: List<Transaction>,
        reminders: List<Reminder>,
        reserves: List<Reserve>,
        salaryDay: Int,
        now: Long
    ): Breakdown {
        val mine = accounts.filter { countsFor(it.ownerUserId, myUid) }
        val balances = LedgerRules.balances(mine, txs)

        val liquid = mine.filter { isLiquid(it) }.map { it to (balances[it.accountId] ?: 0.0) }
        val cards = mine.filter { isCard(it) }.map { cardLine(it, txs, now, balances[it.accountId] ?: 0.0) }.filter { it.owed > 0.0 || it.spentSinceStatement > 0.0 }

        val horizon = nextSalary(salaryDay, now)
        val bills = reminders
            .filter { r -> reminderOwner(r, accounts).let { it.isBlank() || countsFor(it, myUid) } }
            .mapNotNull { r -> billDue(r, accounts, horizon)?.let { BillLine(r, it) } }
            .sortedBy { it.dueDate }

        val myReserves = reserves.filter { it.amount > 0.0 && reserveCountsFor(it, accounts, myUid) }

        val bankAndCash = liquid.sumOf { it.second }
        val cardDues = cards.sumOf { it.owed }
        val upcoming = bills.sumOf { it.reminder.amount }
        val reserved = myReserves.sumOf { it.amount }
        return Breakdown(
            bankAndCash = bankAndCash,
            accounts = liquid,
            cardDues = cardDues,
            cards = cards,
            upcomingBills = upcoming,
            bills = bills,
            reserved = reserved,
            reserves = myReserves,
            horizon = horizon,
            freeToSpend = bankAndCash - cardDues - upcoming - reserved
        )
    }

    fun monthKey(now: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return "%04d_%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
    }

    /**
     * The monthly top-up, once per month, up to the target. Null when nothing changes. Both
     * phones get the same answer, so running it twice doesn't add twice.
     */
    fun topUp(reserve: Reserve, now: Long): Reserve? {
        if (reserve.monthlyContribution <= 0.0) return null
        val month = monthKey(now)
        if (reserve.lastContributionMonth >= month) return null
        val room = if (reserve.target > 0.0) max(0.0, reserve.target - reserve.amount) else reserve.monthlyContribution
        return reserve.copy(amount = reserve.amount + min(room, reserve.monthlyContribution), lastContributionMonth = month)
    }

    /** What still needs to go in each month to reach the target by its date, or null. */
    fun neededPerMonth(reserve: Reserve, now: Long): Double? {
        if (reserve.target <= 0.0 || reserve.targetDate <= now) return null
        val left = reserve.target - reserve.amount
        if (left <= 0.0) return 0.0
        val a = Calendar.getInstance().apply { timeInMillis = now }
        val b = Calendar.getInstance().apply { timeInMillis = reserve.targetDate }
        val months = (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 12 + (b.get(Calendar.MONTH) - a.get(Calendar.MONTH))
        return left / max(1, months)
    }
}
