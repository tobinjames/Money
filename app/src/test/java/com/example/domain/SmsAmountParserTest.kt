package com.example.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsAmountParserTest {
    @Test fun `plain debit`() {
        assertEquals(1250.50, SmsAmountParser.extract("Rs.1,250.50 debited from A/c XX1234 on 04-11-26")!!, 0.001)
    }

    @Test fun `skips available balance printed first`() {
        assertEquals(500.0, SmsAmountParser.extract("Avl Bal Rs 12,000.00. Rs 500 debited from A/c XX1234")!!, 0.001)
    }

    @Test fun `card spend with limit after`() {
        assertEquals(799.0, SmsAmountParser.extract("INR 799.00 spent on HDFC Card XX9876 at AMAZON. Avl Lmt INR 45,000")!!, 0.001)
    }

    @Test fun `rupee symbol`() {
        assertEquals(60.0, SmsAmountParser.extract("₹60 paid to SWIGGY via UPI")!!, 0.001)
    }

    @Test fun `does not read hrs as rupees`() {
        assertNull(SmsAmountParser.extract("Your OTP is valid for 24 hrs 10 minutes"))
    }

    @Test fun `no amount`() {
        assertNull(SmsAmountParser.extract("Your statement is ready"))
    }
}
