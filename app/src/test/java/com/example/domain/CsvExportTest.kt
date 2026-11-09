package com.example.domain

import com.example.data.Account
import com.example.data.Transaction
import com.example.data.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExportTest {
    @Test fun `escapes commas, quotes and formulas`() {
        assertEquals("\"Food, drinks\"", CsvExport.escape("Food, drinks"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExport.escape("say \"hi\""))
        assertEquals("'=SUM(A1)", CsvExport.escape("=SUM(A1)"))
        assertEquals("-500.00", CsvExport.escape("-500.00"))
    }

    @Test fun `one row per transaction with account names`() {
        val tx = LedgerRules.toLedger(Transaction(amount = 120.5, type = TransactionType.EXPENSE, category = "Food", accountId = "bank", payeeName = "Cafe, MG Road", date = 0L, timestamp = 0L))
        val csv = CsvExport.transactions(listOf(tx), listOf(Account(accountId = "bank", accountName = "HDFC Savings")))
        val lines = csv.trim().split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("EXPENSE,120.50,Food,HDFC Savings,,\"Cafe, MG Road\""))
    }
}
