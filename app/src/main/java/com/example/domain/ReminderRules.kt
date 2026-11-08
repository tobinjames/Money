package com.example.domain

import com.example.data.Account
import com.example.data.AccountType
import com.example.data.Reminder
import com.example.data.Transaction
import com.example.data.TransactionStatus
import java.util.Calendar
import kotlin.math.ceil

/**
 * Pure rules for payment reminders: due dates, recurring roll-forward, credit card bills and
 * when to alert. Everything is deterministic for a given input, so two phones running the
 * daily check write the same documents instead of duplicates.
 */
object ReminderRules {
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** How early the next occurrence of a paid recurring reminder appears. */
    const val ROLL_FORWARD_DAYS = 15

    /** The partner is alerted about the other person's unpaid reminders from this many days before. */
    const val PARTNER_BACKUP_DAYS = 3

    /** Overdue alerts repeat daily for this many days, then stop. */
    const val MAX_OVERDUE_ALERT_DAYS = 7

    /** [day] in the given month, clamped to the month's length (31 -> 28/29 in February). */
    fun dateOnDay(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, second: Int = 0, millis: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.DAY_OF_MONTH, day.coerceIn(1, getActualMaximum(Calendar.DAY_OF_MONTH)))
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, millis)
        }.timeInMillis

    fun dayOfMonth(time: Long): Int = Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.DAY_OF_MONTH)

    /** Whole days until [due], rounded up (12 hours away counts as 1 day; just past due is 0). */
    fun daysRemaining(due: Long, now: Long): Int = ceil((due - now).toDouble() / DAY_MS).toInt()

    /** The anchor day of a monthly reminder: the day the user picked, even after a short month. */
    fun anchorDay(reminder: Reminder): Int = if (reminder.dueDay in 1..31) reminder.dueDay else dayOfMonth(reminder.dueDate)

    /** The next monthly due date after [reminder]'s, on its anchor day, at the same time of day. */
    fun nextMonthlyDue(reminder: Reminder): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = reminder.dueDate }
        cal.add(Calendar.MONTH, 1)
        return dateOnDay(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), anchorDay(reminder),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
        )
    }

    /**
     * A paid recurring reminder moves to its next occurrence (unpaid) once that is
     * [ROLL_FORWARD_DAYS] days away. One document per series, so nothing is duplicated and
     * deleting it stops the series. Returns null when nothing changes.
     */
    fun rollForward(reminder: Reminder, now: Long): Reminder? {
        if (!reminder.isRecurring || !reminder.isPaid) return null
        val next = nextMonthlyDue(reminder)
        if (now < next - ROLL_FORWARD_DAYS * DAY_MS) return null
        return reminder.copy(dueDate = next, isPaid = false, dueDay = anchorDay(reminder))
    }

    /** A card that gets automatic bill reminders: a credit card (not a loan) with both days set. */
    fun hasAutoBills(account: Account): Boolean =
        account.accountType == "LIABILITY" && account.type == AccountType.CREDIT_CARD &&
            account.billingCycleDay in 1..31 && account.paymentDueDay in 1..31

    data class BillCycle(val key: String, val statementDate: Long, val dueDate: Long, val month: Int)

    /** The most recent statement on or before [now], and when it must be paid. */
    fun latestStatement(account: Account, now: Long): BillCycle {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        var year = cal.get(Calendar.YEAR)
        var month = cal.get(Calendar.MONTH)
        var statement = dateOnDay(year, month, account.billingCycleDay, 23, 59, 59, 999)
        if (statement > now) {
            if (month == 0) { year -= 1; month = 11 } else month -= 1
            statement = dateOnDay(year, month, account.billingCycleDay, 23, 59, 59, 999)
        }
        // Due in the same month when the due day comes after the statement day, else the next month.
        var dueYear = year
        var dueMonth = month
        if (account.paymentDueDay <= account.billingCycleDay) {
            if (dueMonth == 11) { dueYear += 1; dueMonth = 0 } else dueMonth += 1
        }
        val due = dateOnDay(dueYear, dueMonth, account.paymentDueDay, 18)
        val key = "%04d_%02d".format(year, month + 1)
        return BillCycle(key, statement, due, month)
    }

    /** What was owed on the card at the statement date. */
    fun statementBalance(account: Account, txs: List<Transaction>, statementDate: Long): Double =
        LedgerRules.balance(account, txs, upTo = statementDate)

    /** Payments into the card (debits to the liability) after the statement date. */
    fun paymentsSince(account: Account, txs: List<Transaction>, statementDate: Long): Double =
        txs.filter {
            !it.isIgnored && it.status == TransactionStatus.CONFIRMED &&
                it.debitAccountId == account.accountId && it.date > statementDate
        }.sumOf { it.amount }

    fun cardBillId(account: Account, cycle: BillCycle) = "cc_${account.accountId}_${cycle.key}"

    /**
     * The bill reminder for the latest statement, when one hasn't been made for it yet
     * (tracked on the account, so a bill you delete is not recreated). Null when nothing is owed.
     */
    fun newCardBill(account: Account, txs: List<Transaction>, now: Long): Pair<Reminder, BillCycle>? {
        if (!hasAutoBills(account)) return null
        val cycle = latestStatement(account, now)
        if (account.lastBillReminderCycle >= cycle.key) return null
        val owed = statementBalance(account, txs, cycle.statementDate)
        val reminder = Reminder(
            id = cardBillId(account, cycle),
            title = "${account.name} Bill (${MONTHS[cycle.month]} Cycle)",
            dueDate = cycle.dueDate,
            amount = owed,
            type = "Credit Card",
            isRecurring = false,
            isPaid = owed <= 0.0 || paymentsSince(account, txs, cycle.statementDate) >= owed - 1.0,
            accountId = account.accountId,
            statementDate = cycle.statementDate
        )
        return reminder.copy(ownerUserId = account.ownerUserId) to cycle
    }

    /**
     * Keeps an unpaid card bill current: late-arriving purchases change the amount, and payments
     * after the statement that cover it mark it paid. Null when nothing changes.
     */
    fun refreshCardBill(reminder: Reminder, account: Account, txs: List<Transaction>): Reminder? {
        if (reminder.isPaid || reminder.statementDate <= 0L || reminder.accountId != account.accountId) return null
        val owed = statementBalance(account, txs, reminder.statementDate)
        val paid = owed <= 0.0 || paymentsSince(account, txs, reminder.statementDate) >= owed - 1.0
        val amountChanged = owed > 0.0 && kotlin.math.abs(owed - reminder.amount) > 0.01
        if (!paid && !amountChanged) return null
        return reminder.copy(amount = if (owed > 0.0) owed else reminder.amount, isPaid = paid)
    }

    fun isMutualFund(reminder: Reminder, account: Account?): Boolean {
        fun mf(s: String?) = s != null && (s.contains("Mutual Fund", true) || s.contains("SIP", true))
        return reminder.type.equals("SIP", true) || reminder.type.equals("Mutual Fund", true) ||
            mf(reminder.title) || mf(account?.accountName)
    }

    /**
     * The alert stage an unpaid reminder is in, or null when no alert is due. An alert is sent
     * whenever the stage changes, so a day the phone was off is caught up with one alert.
     * - Normal: 15, 10, 7 and 5 days before, then daily, then daily overdue for a week.
     * - Mutual funds / SIPs: one alert in the last 5 days.
     * - The partner's reminders (their card or a reminder they made): this phone joins in only
     *   when it is still unpaid [PARTNER_BACKUP_DAYS] days before the due date, as a backup.
     */
    fun alertStage(daysRemaining: Int, mutualFund: Boolean, partnersAccount: Boolean): String? {
        if (daysRemaining < -MAX_OVERDUE_ALERT_DAYS) return null
        if (partnersAccount && daysRemaining > PARTNER_BACKUP_DAYS) return null
        if (mutualFund) return if (daysRemaining <= 5) "mf" else null
        return when {
            daysRemaining <= 4 -> "d$daysRemaining"
            daysRemaining <= 5 -> "t5"
            daysRemaining <= 7 -> "t7"
            daysRemaining <= 10 -> "t10"
            daysRemaining <= 15 -> "t15"
            else -> null
        }
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
}
