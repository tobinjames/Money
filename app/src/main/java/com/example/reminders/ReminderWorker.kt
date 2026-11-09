package com.example.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import com.example.data.FirebaseService
import com.example.domain.FreeToSpendRules
import com.example.domain.ReconcileRules
import com.example.domain.ReminderRules
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * The background check, run on both phones every few hours, when the app opens, and shortly
 * after a transaction is saved: rolls paid recurring reminders forward, creates and settles
 * credit card bills, tops up reserves, and sends due-date, budget, low Free to spend and weekend reconcile alerts. Works without the app being open.
 * Alerts wait out the quiet hours set in Settings (10 PM to 7 AM by default).
 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        FirebaseService.initialize(applicationContext)
        if (!FirebaseService.hasSignedInUser()) return Result.success()
        if (!FirebaseService.ensureCoreDataLoaded()) {
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }
        return try {
            runChecks(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Reminder check failed: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "ReminderWorker"
        private const val DAILY = "reminder_daily_check"
        private const val NOW = "reminder_check_now"
        private const val CHANNEL = "reminder_alerts_channel"
        private const val PREFS = "reminder_alert_stages"

        private const val BUDGET_CHANNEL = "budget_alerts_channel"
        private const val BUDGET_PREFS = "budget_alert_stages"
        private const val SOON = "budget_check_soon"

        private const val MONEY_CHANNEL = "free_to_spend_channel"
        private const val LOW_SENT = "low_balance_alert_sent"
        private const val RECONCILE_SENT = "reconcile_alert_day"

        /** A check every 6 hours from about 9 AM, plus one right away. Safe to call on every app start. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val daily = PeriodicWorkRequestBuilder<ReminderWorker>(6, TimeUnit.HOURS)
                .setInitialDelay(delayUntil(9), TimeUnit.MILLISECONDS)
                .setConstraints(network)
                .build()
            wm.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, daily)
            wm.enqueueUniqueWork(
                NOW, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderWorker>().setConstraints(network).build()
            )
        }

        /**
         * A check a minute after a transaction is saved. Each call restarts the minute, so a burst
         * of SMS or a backfill results in a single check.
         */
        fun checkSoon(context: Context) {
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    SOON, ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(1, TimeUnit.MINUTES).build()
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not schedule budget check: ${e.message}")
            }
        }

        private fun isQuietHours(context: Context, now: Long): Boolean {
            val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
            return AlertPrefs.isQuiet(hour, AlertPrefs.quietFrom(context), AlertPrefs.quietUntil(context))
        }

        private fun delayUntil(hour: Int): Long {
            val now = Calendar.getInstance()
            val next = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
            }
            return next.timeInMillis - now.timeInMillis
        }

        suspend fun runChecks(context: Context) {
            val now = System.currentTimeMillis()
            val txs = FirebaseService.getTransactions().first()
            val accounts = FirebaseService.getCategories().first()
            var reminders = FirebaseService.getReminders().first()

            // 1. Paid recurring reminders move to their next month.
            reminders.forEach { r ->
                ReminderRules.rollForward(r, now)?.let { FirebaseService.updateReminder(it) }
            }

            // 2. Credit card bills: one per statement; settle or update open ones.
            accounts.filter { ReminderRules.hasAutoBills(it) }.forEach { card ->
                ReminderRules.newCardBill(card, txs, now)?.let { (bill, cycle) ->
                    if (reminders.none { it.id == bill.id }) FirebaseService.addReminder(bill)
                    FirebaseService.updateAccount(card.copy(lastBillReminderCycle = cycle.key))
                }
                reminders.filter { it.accountId == card.accountId }.forEach { r ->
                    ReminderRules.refreshCardBill(r, card, txs)?.let { FirebaseService.updateReminder(it) }
                }
            }

            // 3. Reserves: this month's automatic top-ups (the same result on both phones).
            var reserves = FirebaseService.getReserves().first()
            reserves.forEach { r -> FreeToSpendRules.topUp(r, now)?.let { FirebaseService.saveReserve(it) } }

            // 4. Alerts, from the updated lists. Stages not yet sent are picked up after quiet hours.
            AlertPrefs.setLastCheck(context, now)
            if (isQuietHours(context, now)) return
            reminders = FirebaseService.getReminders().first()
            if (AlertPrefs.reminderAlerts(context)) notify(context, reminders, accounts, now)
            if (AlertPrefs.budgetAlerts(context)) notifyBudgets(context, FirebaseService.getBudgets().first(), txs, accounts, now)
            if (AlertPrefs.reconcileAlerts(context)) notifyReconcile(context, accounts, now)
            if (AlertPrefs.lowBalanceAlerts(context)) {
                reserves = FirebaseService.getReserves().first()
                val breakdown = FreeToSpendRules.compute(
                    FirebaseService.getCurrentUserId(), accounts, txs, reminders, reserves, AlertPrefs.salaryDay(context), now
                )
                notifyLowBalance(context, breakdown)
            }
        }

        /** Weekend nudge (Friday evening, Saturday, Sunday; once a day) for accounts not reconciled in 30 days. */
        private fun notifyReconcile(context: Context, accounts: List<com.example.data.Account>, now: Long) {
            if (!ReconcileRules.isWeekendReminderTime(now) || !canNotify(context)) return
            val stale = ReconcileRules.stale(FirebaseService.getCurrentUserId(), accounts, now)
            if (stale.isEmpty()) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val today = ReconcileRules.dayKey(now)
            if (prefs.getString(RECONCILE_SENT, null) == today) return
            prefs.edit().putString(RECONCILE_SENT, today).apply()

            val title = if (stale.size == 1) "🧾 Time to reconcile ${stale[0].accountName}"
                else "🧾 ${stale.size} accounts to reconcile"
            val text = stale.joinToString("\n") { a ->
                val days = ReconcileRules.daysSince(a, now)
                "${a.accountName}: " + if (a.lastReconciledDate == null) "never reconciled" else "last done $days days ago"
            } + "\nCheck each balance against your bank or card app in Accounts & categories."
            post(context, MONEY_CHANNEL, "Free to spend", RECONCILE_SENT.hashCode(), title, text)
        }

        /** Once each time Free to spend drops below the threshold; again only after it recovers. */
        private fun notifyLowBalance(context: Context, b: FreeToSpendRules.Breakdown) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val threshold = AlertPrefs.lowBalanceThreshold(context)
            if (b.freeToSpend >= threshold) {
                prefs.edit().putBoolean(LOW_SENT, false).apply()
                return
            }
            if (prefs.getBoolean(LOW_SENT, false) || !canNotify(context)) return
            prefs.edit().putBoolean(LOW_SENT, true).apply()
            val (title, text) = if (b.freeToSpend < 0) {
                "🚨 You're ₹%,.0f short".format(-b.freeToSpend) to
                    "Card dues (₹%,.0f), bills before salary (₹%,.0f) and reserves (₹%,.0f) add up to more than your bank and cash (₹%,.0f)."
                        .format(b.cardDues, b.upcomingBills, b.reserved, b.bankAndCash)
            } else {
                "⚠️ Only ₹%,.0f free to spend".format(b.freeToSpend) to
                    "That's after card dues of ₹%,.0f, bills before salary and reserves.".format(b.cardDues)
            }
            post(context, MONEY_CHANNEL, "Free to spend", LOW_SENT.hashCode(), title, text)
        }

        /** 80% and over-limit alerts, once per budget per month, on every member's phone. */
        private fun notifyBudgets(
            context: Context,
            budgets: List<com.example.data.Budget>,
            txs: List<com.example.data.Transaction>,
            categories: List<com.example.data.Account>,
            now: Long
        ) {
            if (!canNotify(context)) return
            val prefs = context.getSharedPreferences(BUDGET_PREFS, Context.MODE_PRIVATE)
            val month = com.example.domain.LedgerRules.monthBounds(now).first
            for (b in budgets) {
                val spent = com.example.domain.BudgetRules.spent(b, txs, categories, now)
                val stage = com.example.domain.BudgetRules.alertStage(spent, b.monthlyLimit) ?: continue
                val key = "${b.budgetId}_$month"
                val previous = prefs.getString(key, null)
                // "over" follows "80"; never go back to an 80% alert in the same month.
                if (previous == stage || previous == "over") continue
                prefs.edit().putString(key, stage).apply()

                val name = com.example.domain.BudgetRules.label(b)
                val limit = "₹%,.0f".format(b.monthlyLimit)
                val (title, text) = if (stage == "over") {
                    "🚨 Over budget: $name" to "₹%,.0f spent this month against $limit.".format(spent)
                } else {
                    val perDay = com.example.domain.BudgetRules.safePerDay(b.monthlyLimit, spent, now) ?: 0.0
                    "⚠️ $name at ${(spent / b.monthlyLimit * 100).toInt()}%" to
                        "₹%,.0f of $limit spent. About ₹%,.0f a day left for the rest of the month.".format(spent, perDay)
                }
                post(context, BUDGET_CHANNEL, "Budget alerts", key.hashCode(), title, text)
            }
        }

        private fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        private fun notify(context: Context, reminders: List<com.example.data.Reminder>, accounts: List<com.example.data.Account>, now: Long) {
            if (!canNotify(context)) return

            val myUid = FirebaseService.getCurrentUserId()
            val partnerUid = FirebaseService.spouseUserUid.value
            val partnerName = FirebaseService.spouseUserFirstName.value ?: "Partner"
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            for (r in reminders) {
                if (r.isPaid) continue
                val account = accounts.find { it.accountId == r.accountId }
                // The card's owner when linked to an account, otherwise whoever made the reminder.
                val owner = account?.ownerUserId?.takeIf { it.isNotBlank() } ?: r.ownerUserId
                val partnersAccount = partnerUid != null && owner == partnerUid && owner != myUid
                if (partnersAccount && !AlertPrefs.partnerAlerts(context)) continue
                val days = ReminderRules.daysRemaining(r.dueDate, now)
                val stage = ReminderRules.alertStage(days, ReminderRules.isMutualFund(r, account), partnersAccount) ?: continue

                // One alert per stage per due date, so changes in stage are caught up after a missed day.
                val key = "${r.id}_${r.dueDate}"
                if (prefs.getString(key, null) == stage) continue
                prefs.edit().putString(key, stage).apply()

                val prefix = if (partnersAccount) "[$partnerName's, still unpaid] " else ""
                val amount = "₹%,.2f".format(r.amount)
                val (title, text) = when {
                    days < 0 -> "🚨 ${prefix}Overdue: ${r.title}" to "$amount was due ${-days} day${if (days == -1) "" else "s"} ago."
                    days == 0 -> "⏳ ${prefix}Due today: ${r.title}" to "$amount is due today."
                    else -> "🔔 ${prefix}Upcoming: ${r.title}" to "$amount is due in $days day${if (days == 1) "" else "s"}."
                }
                post(context, CHANNEL, "Payment Reminders", r.id.hashCode(), title, text)
            }
        }

        private fun post(context: Context, channel: String, channelName: String, id: Int, title: String, text: String) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_HIGH))
            }
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .apply {
                    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                        setContentIntent(
                            android.app.PendingIntent.getActivity(
                                context, 0, launch,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                            )
                        )
                    }
                }
                .build()
            manager.notify(id, notification)
        }
    }
}
