package com.example.reminders

import android.content.Context

/**
 * Alert preferences for this phone (each partner chooses their own), plus the time of the last
 * successful background check, shown in Settings > Permissions & health.
 */
object AlertPrefs {
    private const val FILE = "alert_prefs"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun reminderAlerts(context: Context) = prefs(context).getBoolean("reminder_alerts", true)
    fun setReminderAlerts(context: Context, on: Boolean) = prefs(context).edit().putBoolean("reminder_alerts", on).apply()

    /** Alerts for the partner's unpaid reminders, from 3 days before they are due. */
    fun partnerAlerts(context: Context) = prefs(context).getBoolean("partner_alerts", true)
    fun setPartnerAlerts(context: Context, on: Boolean) = prefs(context).edit().putBoolean("partner_alerts", on).apply()

    fun budgetAlerts(context: Context) = prefs(context).getBoolean("budget_alerts", true)
    fun setBudgetAlerts(context: Context, on: Boolean) = prefs(context).edit().putBoolean("budget_alerts", on).apply()

    /** Quiet hours: alerts wait until [quietUntil] when the check runs at or after [quietFrom]. */
    fun quietFrom(context: Context) = prefs(context).getInt("quiet_from", 22)
    fun quietUntil(context: Context) = prefs(context).getInt("quiet_until", 7)
    fun setQuietHours(context: Context, from: Int, until: Int) =
        prefs(context).edit().putInt("quiet_from", from).putInt("quiet_until", until).apply()

    fun lastCheck(context: Context) = prefs(context).getLong("last_check", 0L)
    fun setLastCheck(context: Context, time: Long) = prefs(context).edit().putLong("last_check", time).apply()

    /** Whether [hour] falls in the quiet window, which may wrap past midnight. Equal ends mean off. */
    fun isQuiet(hour: Int, from: Int, until: Int): Boolean = when {
        from == until -> false
        from < until -> hour in from until until
        else -> hour >= from || hour < until
    }
}
