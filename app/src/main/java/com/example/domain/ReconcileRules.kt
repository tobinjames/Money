package com.example.domain

import com.example.data.Account
import java.util.Calendar

/**
 * Every bank account, cash wallet and credit card should be checked against its real balance
 * (reconciled) at least once a month. Reminders go out at the weekend, when there is time to
 * do it: Friday evening, Saturday and Sunday.
 */
object ReconcileRules {
    const val STALE_DAYS = 30

    /** Friday from this hour counts as the weekend. */
    const val FRIDAY_FROM_HOUR = 18

    fun isReconcilable(account: Account): Boolean =
        FreeToSpendRules.isLiquid(account) || FreeToSpendRules.isCard(account)

    /** When the account was last reconciled, or created when it never has been. */
    fun lastChecked(account: Account): Long = account.lastReconciledDate ?: account.asOfDate

    fun daysSince(account: Account, now: Long): Int = ((now - lastChecked(account)) / ReminderRules.DAY_MS).toInt()

    /** This person's (and joint) accounts not reconciled for [STALE_DAYS] days, oldest first. */
    fun stale(myUid: String, accounts: List<Account>, now: Long): List<Account> =
        accounts
            .filter { isReconcilable(it) && FreeToSpendRules.countsFor(it.ownerUserId, myUid) }
            .filter { daysSince(it, now) >= STALE_DAYS }
            .sortedBy { lastChecked(it) }

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
