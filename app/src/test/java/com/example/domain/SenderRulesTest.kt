package com.example.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderRulesTest {
    @Test fun `matches whole header segments`() {
        assertTrue(SenderRules.matches("AX-HDFCBK", "HDFCBK"))
        assertTrue(SenderRules.matches("jd-hdfcbk-s", "HDFCBK"))
        assertTrue(SenderRules.matches("HDFCBK", "hdfcbk"))
        assertTrue(SenderRules.matches("VM-SBIINB-T", "AX-SBIINB".replace("AX-", "")))
    }

    @Test fun `does not match substrings`() {
        assertFalse(SenderRules.matches("AX-SBIINB", "SBI"))
        assertFalse(SenderRules.matches("AX-HDFCBN", "HDFCBK"))
        assertFalse(SenderRules.matches("AX-HDFCBK", ""))
    }

    @Test fun `full saved header`() {
        assertTrue(SenderRules.matches("AX-HDFCBK-S", "AX-HDFCBK"))
        assertTrue(SenderRules.matches("AX-HDFCBK", "AX-HDFCBK"))
        assertFalse(SenderRules.matches("VM-HDFCBK", "AX-HDFCBK"))
    }
}
