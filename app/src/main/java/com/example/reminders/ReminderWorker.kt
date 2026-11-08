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
import com.example.domain.ReminderRules
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Runs the reminder checks in the background, about once a day and whenever the app opens:
 * rolls paid recurring reminders forward, creates and settles credit card bills, and sends
 * the due-date alerts. Works without the app being open.
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

        /** Daily check at about 9 AM, plus one right away. Safe to call on every app start. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val daily = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayUntil(9), TimeUnit.MILLISECONDS)
                .setConstraints(network)
                .build()
            wm.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, daily)
            wm.enqueueUniqueWork(
                NOW, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderWorker>().setConstraints(network).build()
            )
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

            // 3. Alerts, from the updated list.
            reminders = FirebaseService.getReminders().first()
            notify(context, reminders, accounts, now)
        }

        private fun notify(context: Context, reminders: List<com.example.data.Reminder>, accounts: List<com.example.data.Account>, now: Long) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return

            val myUid = FirebaseService.getCurrentUserId()
            val partnerUid = FirebaseService.spouseUserUid.value
            val partnerName = FirebaseService.spouseUserFirstName.value ?: "Partner"
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            for (r in reminders) {
                if (r.isPaid) continue
                val account = accounts.find { it.accountId == r.accountId }
                val owner = account?.ownerUserId
                val partnersAccount = owner != null && partnerUid != null && owner == partnerUid && owner != myUid
                val days = ReminderRules.daysRemaining(r.dueDate, now)
                val stage = ReminderRules.alertStage(days, ReminderRules.isMutualFund(r, account), partnersAccount) ?: continue

                // One alert per stage per due date, so changes in stage are caught up after a missed day.
                val key = "${r.id}_${r.dueDate}"
                if (prefs.getString(key, null) == stage) continue
                prefs.edit().putString(key, stage).apply()

                val prefix = if (partnersAccount) "[$partnerName's account] " else ""
                val amount = "₹%,.2f".format(r.amount)
                val (title, text) = when {
                    days < 0 -> "🚨 ${prefix}Overdue: ${r.title}" to "$amount was due ${-days} day${if (days == -1) "" else "s"} ago."
                    days == 0 -> "⏳ ${prefix}Due today: ${r.title}" to "$amount is due today."
                    else -> "🔔 ${prefix}Upcoming: ${r.title}" to "$amount is due in $days day${if (days == 1) "" else "s"}."
                }
                post(context, r.id.hashCode(), title, text)
            }
        }

        private fun post(context: Context, id: Int, title: String, text: String) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Payment Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Upcoming and overdue bills and scheduled payments"
                    }
                )
            }
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            manager.notify(id, notification)
        }
    }
}
