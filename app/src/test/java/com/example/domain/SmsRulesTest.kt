package com.example.domain

import com.example.data.Account
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsRulesTest {

    private val keywords = listOf("OTP", "PIN", "Password", "One Time Password")
    private val bank = Account(accountId = "bank", accountName = "HDFC", accountType = "ASSET", lastFourDigits = "1234, 9876")
    private val card = Account(accountId = "card", accountName = "ICICI Card", accountType = "LIABILITY", lastFourDigits = "5678")
    private val fd = Account(accountId = "fd", accountName = "FD", accountType = "ASSET", customMainCategory = "Fixed Deposits", lastFourDigits = "2026")

    @Test
    fun privacyWordsMatchWholeWordsOnly() {
        assertFalse(SmsRules.blockedByPrivacy("Rs 500 spent at AMAZON SHOPPING on card XX5678", keywords))
        assertFalse(SmsRules.blockedByPrivacy("INR 1,200 paid via PINE LABS, pincode 560001", keywords))
        assertTrue(SmsRules.blockedByPrivacy("Your OTP is 482913. Do not share it.", keywords))
        assertTrue(SmsRules.blockedByPrivacy("Use PIN 4821 to log in", keywords))
    }

    @Test
    fun transactionWithSafetyFooterIsKept() {
        assertFalse(SmsRules.blockedByPrivacy("Rs.2,000 debited from a/c XX1234 on 10-Nov. Never share your OTP or PIN.", keywords))
        // An OTP for a payment is not the payment.
        assertTrue(SmsRules.blockedByPrivacy("OTP 123456 for txn of Rs 2,000 at AMAZON. Do not share.", keywords))
    }

    @Test
    fun digitsMustFollowAnAccountMarker() {
        assertTrue(SmsRules.namesDigits("Rs 500 debited from A/c XX1234 on 10-11-2026", "1234"))
        assertTrue(SmsRules.namesDigits("spent on card ending 5678 at SHOP", "5678"))
        assertTrue(SmsRules.namesDigits("Acct *1234 credited", "1234"))
        assertTrue(SmsRules.namesDigits("A/c no. XXXXXXXX1234 debited", "1234"))
        // A year, an amount and a reference number are not account numbers.
        assertFalse(SmsRules.namesDigits("Rs 500 debited on 10-11-2026 from a/c XX1234", "2026"))
        assertFalse(SmsRules.namesDigits("Rs 1234.00 spent. UPI Ref 412345678", "1234"))
        assertFalse(SmsRules.namesDigits("a/c XX12345 debited", "1234"))
    }

    @Test
    fun findsNamedAccountsAndCardPayments() {
        val all = listOf(bank, card, fd)
        assertEquals(listOf("bank"), SmsRules.accountsNamed("Rs 500 debited from A/c XX9876 on 10-11-2026", all).map { it.accountId })
        val named = SmsRules.accountsNamed("Rs 5000 debited from a/c XX1234 towards credit card XX5678", all)
        val payment = SmsRules.cardPayment("Rs 5000 debited from a/c XX1234 towards credit card XX5678", named)!!
        assertEquals("bank", payment.first.accountId)
        assertEquals("card", payment.second.accountId)
        assertNull(SmsRules.cardPayment("Rs 500 spent on card XX5678", listOf(card)))
    }

    @Test
    fun recognisesTheSameSmsAgain() {
        val body = "Rs 500 debited from a/c XX1234"
        assertEquals(SmsRules.fingerprint("AX-HDFCBK", body), SmsRules.fingerprint("ax-hdfcbk", "  Rs 500  debited from a/c XX1234 "))
        assertNotEquals(SmsRules.fingerprint("AX-HDFCBK", body), SmsRules.fingerprint("AX-HDFCBK", body + "5"))
        assertTrue(SmsRules.sameSms("[Debit SMS]: $body\n[Credit SMS]: Rs 500 credited to card", body))
        assertFalse(SmsRules.sameSms(null, body))
    }

    @Test
    fun offersAreNotTransactionsAndForeignAmountsAreFlagged() {
        assertFalse(SmsRules.looksLikeTransaction("Pre-approved loan of Rs 5,00,000 for you. Apply now"))
        assertTrue(SmsRules.looksLikeTransaction("Rs 500 debited from a/c XX1234"))
        assertTrue(SmsRules.foreignCurrencyOnly("USD 50.00 spent on card XX5678 at AWS"))
        assertFalse(SmsRules.foreignCurrencyOnly("USD 50.00 (INR 4,200.00) spent on card XX5678"))
        assertTrue(SmsRules.isUnknownVendor("Local Transaction"))
    }
}
