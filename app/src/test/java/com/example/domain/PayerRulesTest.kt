package com.example.domain

import com.example.data.Account
import com.example.data.Transaction
import com.example.data.TransactionType
import com.example.domain.PayerRules.Who
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayerRulesTest {

    private val me = "me"
    private val partner = "partner"
    private val accounts = listOf(
        Account(accountId = "myCard", accountType = "LIABILITY", ownerUserId = me),
        Account(accountId = "theirBank", accountType = "ASSET", ownerUserId = partner),
        Account(accountId = "joint", accountType = "ASSET", ownerUserId = "SHARED")
    ).associateBy { it.accountId }

    private fun spend(account: String, paidFor: String = "BOTH", owner: String = "") =
        Transaction(amount = 100.0, type = TransactionType.EXPENSE, accountId = account, category = "Food", paidByUserId = paidFor, ownerUserId = owner)

    @Test
    fun whoPaidIsTheOwnerOfTheAccountUsed() {
        assertEquals(Who.ME, PayerRules.paidBy(spend("myCard"), accounts, me, partner))
        assertEquals(Who.PARTNER, PayerRules.paidBy(spend("theirBank"), accounts, me, partner))
        assertEquals(Who.JOINT, PayerRules.paidBy(spend("joint"), accounts, me, partner))
    }

    @Test
    fun whoPaidIgnoresWhoItWasFor() {
        val tx = spend("myCard", paidFor = partner)
        assertEquals(Who.ME, PayerRules.paidBy(tx, accounts, me, partner))
        assertEquals(Who.PARTNER, PayerRules.paidFor(tx, me, partner))
    }

    @Test
    fun aDeletedAccountFallsBackToTheEntrysOwner() {
        assertEquals(Who.PARTNER, PayerRules.paidBy(spend("gone", owner = partner), accounts, me, partner))
        assertEquals(Who.JOINT, PayerRules.paidBy(spend("gone"), accounts, me, partner))
    }

    @Test
    fun paidForReadsFamilyAndOldLabels() {
        assertEquals(Who.JOINT, PayerRules.paidFor(spend("myCard", paidFor = "BOTH"), me, partner))
        assertEquals(Who.JOINT, PayerRules.paidFor(spend("myCard", paidFor = ""), me, partner))
        assertEquals(Who.ME, PayerRules.paidFor(spend("myCard", paidFor = "User 1"), me, partner))
        assertEquals(Who.PARTNER, PayerRules.paidFor(spend("myCard", paidFor = "User 2"), me, partner))
    }

    @Test
    fun eachViewShowsOnlyItsOwnSpending() {
        val mine = spend("myCard", paidFor = partner)
        assertTrue(PayerRules.inView(mine, Who.ME, accounts, me, partner))
        assertFalse(PayerRules.inView(mine, Who.PARTNER, accounts, me, partner))
        assertFalse(PayerRules.inView(mine, Who.JOINT, accounts, me, partner))
    }

    @Test
    fun aTransferShowsForBothEnds() {
        val transfer = Transaction(amount = 500.0, type = TransactionType.TRANSFER, accountId = "theirBank", targetAccountId = "joint")
        assertTrue(PayerRules.inView(transfer, Who.PARTNER, accounts, me, partner))
        assertTrue(PayerRules.inView(transfer, Who.JOINT, accounts, me, partner))
        assertFalse(PayerRules.inView(transfer, Who.ME, accounts, me, partner))
    }

    @Test
    fun paidForChoiceIsSavedAsAUserId() {
        assertEquals(me, PayerRules.paidForId("User 1", me, partner))
        assertEquals(partner, PayerRules.paidForId("User 2", me, partner))
        assertEquals("User 2", PayerRules.paidForId("User 2", me, null))
        assertEquals("BOTH", PayerRules.paidForId("BOTH", me, partner))
    }
}
