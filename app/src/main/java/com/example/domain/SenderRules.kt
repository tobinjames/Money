package com.example.domain

/**
 * Matches an SMS sender against a saved bank sender ID. Indian bank SMS come from headers like
 * "AX-HDFCBK" or "JD-HDFCBK-S", so a saved "HDFCBK" matches when it is one whole segment, not
 * any substring (a short ID such as "SBI" would otherwise match unrelated senders). A saved
 * full header such as "AX-HDFCBK" matches that header with or without a suffix.
 */
object SenderRules {
    private fun normalize(s: String) = s.uppercase().trim().replace(Regex("[^A-Z0-9-]"), "")

    fun matches(sender: String, savedId: String): Boolean {
        val s = normalize(sender)
        val id = normalize(savedId)
        if (id.isEmpty() || s.isEmpty()) return false
        if (s == id) return true
        if (id.contains('-')) return s.startsWith("$id-")
        return s.split('-').any { it == id }
    }
}
