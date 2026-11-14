package com.example.domain

/**
 * Finds the account balance a bank prints at the end of many SMS ("Avl Bal Rs 12,000.50",
 * "Available balance is INR 5,000", "Bal: 1,234.00 CR"). A card's available limit, an amount
 * outstanding and a minimum balance are not account balances and are left alone.
 */
object SmsBalanceParser {
    private const val LABEL = """(?<![a-z])(?:avl|avail|clr|net|tot|total)?\.?bal(?:ance)?\b"""
    private const val NUMBER = """(-?\d[\d,]*(?:\.\d+)?)"""

    // "Avl Bal Rs.500", "balance is INR 500", "balance in a/c XX1234 is Rs 500".
    private val withCurrency = Regex(
        LABEL + """[\s:.\-]*(?:[a-z][a-z/ ]{0,24}?[\s:\-]*)?(?:[x*]+\d{2,6}[\s:\-]*(?:is)?[\s:\-]*)?(?:rs\b|inr\b|₹)[\s.:]*""" +
            NUMBER + """(?:\s*(cr|dr)\b)?"""
    )

    // No currency sign: only trust a number with paise right after the label ("Avl Bal: 1,234.50").
    private val plain = Regex(LABEL + """[\s:.\-]*(?:is\s*)?(-?\d[\d,]*\.\d{2})(?!\d)(?:\s*(cr|dr)\b)?""")

    private val notABalance = listOf("lmt", "limit", "min", "outstanding", "o/s", "due", "reward", "point", "wallet", "loan", "card")

    /** The balance the message reports, negative when overdrawn ("DR"); null when it has none. */
    fun extract(text: String): Double? {
        val lower = text.lowercase()
        return first(lower, withCurrency) ?: first(lower, plain)
    }

    private fun first(lower: String, regex: Regex): Double? {
        for (match in regex.findAll(lower)) {
            val before = lower.substring(maxOf(0, match.range.first - 16), match.range.first)
            if (notABalance.any { before.contains(it) }) continue
            val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            return if (match.groupValues[2] == "dr") -kotlin.math.abs(value) else value
        }
        return null
    }
}
