package com.example.domain

import com.example.data.Account
import com.example.data.Transaction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plain CSV of the household's transactions, newest first, for a spreadsheet or a backup. */
object CsvExport {
    private val HEADER = listOf("Date", "Type", "Amount", "Category", "Account", "To account", "Payee", "Status", "Ignored", "Owner", "Tag")

    fun transactions(txs: List<Transaction>, accounts: List<Account>): String {
        val names = accounts.associate { it.accountId to it.accountName }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val rows = txs.sortedByDescending { it.date }.map { tx ->
            listOf(
                dateFormat.format(Date(tx.date)),
                tx.type.name,
                "%.2f".format(Locale.US, tx.amount),
                tx.category,
                names[tx.accountId] ?: tx.accountId,
                tx.targetAccountId?.let { names[it] ?: it } ?: "",
                tx.payeeName,
                tx.status.name,
                if (tx.isIgnored) "yes" else "",
                tx.ownerFirstName,
                tx.tag
            )
        }
        return (listOf(HEADER) + rows).joinToString("\n") { row -> row.joinToString(",") { escape(it) } } + "\n"
    }

    /** Quotes a field when needed; a leading =, +, - or @ is prefixed so spreadsheets don't run it as a formula. */
    fun escape(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@" && value.toDoubleOrNull() == null) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
