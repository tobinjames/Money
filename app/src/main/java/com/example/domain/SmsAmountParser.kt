package com.example.domain

/**
 * Finds the transaction amount in a bank SMS without picking up the balance or limit that many
 * banks also print ("Avl Bal Rs 12,000", "Avl Lmt INR 50,000").
 */
object SmsAmountParser {
    private val currencyAmount = Regex("""(?:\brs\.?|\binr|\busd|\$|₹)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val plainDecimal = Regex("""\b\d[\d,]*\.\d{2}\b""")
    private val balanceHints = listOf("bal", "avl", "available", "limit", "lmt", "outstanding", "o/s")

    /** Returns the amount, or null when no positive amount is found. */
    fun extract(text: String): Double? {
        val lower = text.lowercase()
        firstNonBalance(lower, currencyAmount) { it.groupValues[1] }?.let { return it }
        return firstNonBalance(lower, plainDecimal) { it.value }
    }

    /**
     * First positive amount whose label (the text since the previous amount, at most 20 chars)
     * does not mark it as a balance or limit.
     */
    private fun firstNonBalance(lower: String, regex: Regex, number: (MatchResult) -> String): Double? {
        var previousEnd = 0
        for (match in regex.findAll(lower)) {
            val label = lower.substring(maxOf(previousEnd, match.range.first - 20), match.range.first)
            previousEnd = match.range.last + 1
            if (balanceHints.any { label.contains(it) }) continue
            val value = number(match).replace(",", "").toDoubleOrNull()
            if (value != null && value > 0.0) return value
        }
        return null
    }
}
