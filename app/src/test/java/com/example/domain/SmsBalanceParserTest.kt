package com.example.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsBalanceParserTest {

    private fun bal(text: String) = SmsBalanceParser.extract(text)

    @Test fun `reads the available balance after the transaction amount`() {
        assertEquals(12345.67, bal("Rs.500.00 debited from A/c XX1234 on 14-Nov-26 to VPA abc@upi. Avl Bal Rs.12,345.67")!!, 0.001)
        assertEquals(5000.0, bal("INR 2,000 credited to your a/c XX1234. Available balance is INR 5,000")!!, 0.001)
        assertEquals(1234.5, bal("Your A/c XX1234 is debited by Rs 100. Avl Bal:Rs.1234.50 -SBI")!!, 0.001)
        assertEquals(123456.78, bal("Acct XX1234 credited with ₹10. Balance: ₹1,23,456.78")!!, 0.001)
        assertEquals(900.0, bal("Txn of INR 100 on a/c XX1234. Total Avail.bal INR 900.00")!!, 0.001)
        assertEquals(750.25, bal("Rs 50 spent via UPI. AvlBal Rs 750.25")!!, 0.001)
    }

    @Test fun `reads a balance that names the account or has no currency sign`() {
        assertEquals(8000.0, bal("Rs 200 withdrawn. The balance in A/c XX1234 is Rs 8,000.00")!!, 0.001)
        assertEquals(4321.0, bal("A/c XX1234 debited 100.00. Avl Bal: 4,321.00")!!, 0.001)
    }

    @Test fun `an overdrawn balance is negative`() {
        assertEquals(-250.0, bal("A/c XX1234 debited Rs 500. Bal Rs 250.00 DR")!!, 0.001)
        assertEquals(250.0, bal("A/c XX1234 credited Rs 500. Bal Rs 250.00 CR")!!, 0.001)
    }

    @Test fun `limits, dues and minimum balances are not account balances`() {
        assertNull(bal("Rs 500 spent on card XX5678. Avl Lmt Rs 45,000"))
        assertNull(bal("Rs 500 spent on your card XX5678. Available limit: Rs 45,000.00"))
        assertNull(bal("Total outstanding balance Rs 12,000 on card XX5678"))
        assertNull(bal("Please maintain minimum balance of Rs 10,000 in a/c XX1234"))
        assertNull(bal("Min bal Rs 5000 not maintained in a/c XX1234"))
        assertNull(bal("Rs 500 debited from a/c XX1234 to global traders"))
        assertNull(bal("Rs 500 debited from a/c XX1234 on 14-11-26"))
    }
}
