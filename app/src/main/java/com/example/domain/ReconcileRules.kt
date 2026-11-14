package com.example.domain

import com.example.data.Account
import com.example.data.Transaction
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max

/**
 * Every bank account, cash wallet and credit card should be checked against its real balance
 * (reconciled) at least once a month; FDs, mutual funds and loans every two months. Reminders go out at the weekend, when there is time to
 * do it: Friday evening, Saturday and Sunday.
 */
object ReconcileRules {
    const val STALE_DAYS = 30

    /** FDs, mutual funds and loans move slowly, so every two months is enough. */
    const val SLOW_STALE_DAYS = 60

    /** Friday from this hour counts as the weekend. */
    const val FRIDAY_FROM_HOUR = 18

    fun isReconcilable(account: Account): Boolean =
        account.accountType == "ASSET" || account.accountType == "LIABILITY"

    /** How long an account can go between reconciliations. */
    fun staleAfterDays(account: Account): Int =
        if (FreeToSpendRules.isLiquid(account) || FreeToSpendRules.isCard(account)) STALE_DAYS else SLOW_STALE_DAYS

    /** A bank account (not a cash wallet): the only kind whose SMS carry a balance to check against. */
    fun isBank(account: Account): Boolean =
        FreeToSpendRules.isLiquid(account) && !account.mainCategory.equals("Cash", ignoreCase = true)

    /** The bank's own figure from its latest SMS, next to what the app had at that moment. */
    data class BankCheck(val bankBalance: Double, val at: Long, val appBalance: Double) {
        /** How much more the bank has than the app (negative: the app shows more). */
        val difference: Double get() = bankBalance - appBalance
        val matches: Boolean get() = abs(difference) < 0.01
    }

    /**
     * Compares the balance in the bank's latest SMS with the app's balance at that same moment.
     * Null when there is nothing to compare: not a bank account, no SMS balance yet, or the
     * account was reconciled by hand at or after that SMS (which settles it).
     */
    fun bankCheck(account: Account, ledgerTxs: List<Transaction>): BankCheck? {
        if (!isBank(account)) return null
        val bankBalance = account.smsBalance ?: return null
        val at = account.smsBalanceDate ?: return null
        if (account.lastReconciledDate?.let { it >= at } == true) return null
        return BankCheck(bankBalance, at, LedgerRules.balance(account, ledgerTxs, at))
    }

    /**
     * When the account was last checked: reconciled by hand, or matched by a bank SMS balance
     * (which counts as reconciled without locking anything). Created date when never.
     * Pass [ledgerTxs] to take SMS balances into account.
     */
    fun lastChecked(account: Account, ledgerTxs: List<Transaction>? = null): Long {
        val byHand = account.lastReconciledDate ?: account.asOfDate
        val bySms = ledgerTxs?.let { bankCheck(account, it) }?.takeIf { it.matches }?.at ?: return byHand
        return max(byHand, bySms)
    }

    fun daysSince(account: Account, now: Long, ledgerTxs: List<Transaction>? = null): Int =
        ((now - lastChecked(account, ledgerTxs)) / ReminderRules.DAY_MS).toInt()

    /** This person's (and joint) accounts overdue for reconciling, oldest first. */
    fun stale(myUid: String, accounts: List<Account>, now: Long, ledgerTxs: List<Transaction>? = null): List<Account> =
        accounts
            .filter { isReconcilable(it) && FreeToSpendRules.countsFor(it.ownerUserId, myUid) }
            .filter { daysSince(it, now, ledgerTxs) >= staleAfterDays(it) }
            .sortedBy { lastChecked(it, ledgerTxs) }

    /**
     * The moment a reconcile is "as on" for a day picked on the calendar: now for today (later
     * entries today stay outside it), the end of the day for a past date. Never in the future.
     */
    fun closingTime(year: Int, month: Int, day: Int, now: Long): Long {
        val end = Calendar.getInstance().apply {
            clear()
            set(year, month, day, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        return if (end >= now) now else end
    }

    fun isWeekendReminderTime(now: Long): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.FRIDAY -> cal.get(Calendar.HOUR_OF_DAY) >= FRIDAY_FROM_HOUR
            Calendar.SATURDAY, Calendar.SUNDAY -> true
            else -> false
        }
    }

    /** One reminder per day: the key for today. */
    fun dayKey(now: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }
}
