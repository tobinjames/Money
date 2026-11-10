package com.example.domain

import com.example.data.Account

/**
 * Pure rules for reading bank SMS: which messages to keep, which account one belongs to, and
 * how to recognise a message that was already handled.
 */
object SmsRules {

    private val TRANSACTION_WORDS = listOf(
        "debited", "credited", "spent", "withdrawn", "deposited", "received", "paid", "sent",
        "purchase", "txn", "transferred", "refund"
    )
    private val STATEMENT_WORDS = listOf("statement", "bill", "min due", "minimum due", "payment due", "total due", "due date")
    private val STATEMENT_OVERRIDES = listOf("debited", "credited", "withdrawn", "deposited", "received", "refund", "spent", "paid rs", "paid to")
    private val CARD_PAYMENT_WORDS = listOf("payment", "paid", "towards", "bill", "transfer")
    private val UNKNOWN_VENDORS = setOf("", "unknown", "local transaction", "na", "n/a")
    private val foreignAmount = Regex("""(?:\busd|\beur|\bgbp|\baed|\bsgd|\bcad|\baud|\$|€|£)\s*[\d,]+(?:\.\d+)?""", RegexOption.IGNORE_CASE)
    private val rupeeAmount = Regex("""(?:\brs\.?|\binr|₹)\s*[\d,]+(?:\.\d+)?""", RegexOption.IGNORE_CASE)

    /** Says money moved (not an offer, an OTP or a reminder). */
    fun looksLikeTransaction(body: String): Boolean {
        val lower = body.lowercase()
        return TRANSACTION_WORDS.any { containsWord(lower, it) }
    }

    /** A statement or due-date notice, which is not itself a transaction. */
    fun isStatementNotice(body: String): Boolean {
        val lower = body.lowercase()
        return STATEMENT_WORDS.any { lower.contains(it) } && STATEMENT_OVERRIDES.none { lower.contains(it) }
    }

    /**
     * Whether a privacy keyword (OTP, PIN, password...) keeps this message out. The keyword must
     * be a whole word ("PIN" does not match "shopping" or "Pine Labs"), and a message that
     * clearly reports a transaction is kept even when it ends with "never share your OTP".
     */
    fun blockedByPrivacy(body: String, keywords: List<String>): Boolean {
        val lower = body.lowercase()
        val hit = keywords.any { k -> k.isNotBlank() && containsWord(lower, k.lowercase().trim()) }
        if (!hit) return false
        val moneyMoved = listOf("debited", "credited", "spent", "withdrawn", "deposited").any { containsWord(lower, it) }
        return !(moneyMoved && rupeeAmount.containsMatchIn(lower))
    }

    /** [word] in [lower] with no letter or digit directly before or after it. */
    fun containsWord(lower: String, word: String): Boolean {
        if (word.isEmpty()) return false
        var from = 0
        while (true) {
            val i = lower.indexOf(word, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else lower[i - 1]
            val end = i + word.length
            val after = if (end >= lower.length) ' ' else lower[end]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = i + 1
        }
    }

    /** The saved last digits of an account (several allowed: account number and debit card). */
    fun digitTokens(account: Account): List<String> =
        account.lastFourDigits.split(",", "/", ";", " ", "|").map { it.trim() }.filter { it.length >= 3 }

    /**
     * Whether the SMS names an account by these last digits. The digits must follow a marker such
     * as "XX", "*", "ending", "a/c" or "card", so a year, an amount or a reference number that
     * happens to contain them does not count.
     */
    fun namesDigits(body: String, digits: String): Boolean {
        if (digits.length < 3) return false
        val d = Regex.escape(digits.lowercase())
        val lower = body.lowercase()
        // Masked digits written together: "XX1234", "*1234", "XXXXXXXX1234".
        if (Regex("""[x*]+\d*$d(?!\d)""").containsMatchIn(lower)) return true
        val marker = """\b(?:ending(?:\s+(?:with|in))?|a/c(?:\s*no)?|ac(?:ct?)?(?:\s*no)?|account(?:\s*no)?|card(?:\s*no)?)"""
        return Regex("""$marker[\s:.#\-]*[x*\d]*$d(?!\d)""").containsMatchIn(lower)
    }

    /** Every account the SMS names by its last digits. */
    fun accountsNamed(body: String, accounts: List<Account>): List<Account> =
        accounts.filter { acc -> digitTokens(acc).any { namesDigits(body, it) } }

    /**
     * A bank debit that names one of the family's cards as where the money went (or the card's
     * own "payment received" naming the bank account): a bill payment, bank account to card.
     * Returns (from, to) or null.
     */
    fun cardPayment(body: String, named: List<Account>): Pair<Account, Account>? {
        val lower = body.lowercase()
        if (CARD_PAYMENT_WORDS.none { lower.contains(it) }) return null
        val banks = named.filter { FreeToSpendRules.isLiquid(it) }
        val cards = named.filter { FreeToSpendRules.isCard(it) }
        return if (banks.size == 1 && cards.size == 1) Pair(banks[0], cards[0]) else null
    }

    /** A vendor name that tells us nothing, so it must not be matched against history. */
    fun isUnknownVendor(vendor: String): Boolean = vendor.trim().lowercase() in UNKNOWN_VENDORS

    /** The amount is in a foreign currency and no rupee amount is given, so it needs a look. */
    fun foreignCurrencyOnly(body: String): Boolean =
        foreignAmount.containsMatchIn(body) && !rupeeAmount.containsMatchIn(body)

    /** The message text with spacing evened out, for comparing two copies of the same SMS. */
    fun normalize(body: String): String = body.trim().replace(Regex("\\s+"), " ")

    /** A short, stable fingerprint of a message, for the log of messages already handled. */
    fun fingerprint(sender: String, body: String): String {
        val text = sender.uppercase().trim() + "|" + normalize(body)
        var h = -0x340d631b7bdddcdbL // FNV-1a, 64 bit
        for (c in text) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h)
    }

    /** Whether [body] is the SMS (or one of the SMS) a saved transaction came from. */
    fun sameSms(savedText: String?, body: String): Boolean {
        if (savedText.isNullOrBlank()) return false
        val b = normalize(body)
        return b.isNotEmpty() && normalize(savedText).contains(b)
    }
}
