package com.example.domain

import com.example.data.Account
import com.example.data.Transaction
import com.example.data.TransactionType

/**
 * Whose a transaction is, two ways:
 *  - who paid: the owner of the bank account, card or cash it went through;
 *  - who it was for: the "Paid for" choice on the entry (Family, or one person).
 *
 * Ids are Firebase user ids, "SHARED" for joint accounts, "BOTH" for family spending. Entries
 * saved before ids were used hold "User 1" / "User 2", read as me / my partner.
 */
object PayerRules {

    enum class Who { ME, PARTNER, JOINT }

    fun whoIs(id: String?, myUid: String?, partnerUid: String?): Who = when {
        id.isNullOrBlank() -> Who.JOINT
        myUid != null && id == myUid -> Who.ME
        partnerUid != null && id == partnerUid -> Who.PARTNER
        id == "User 1" -> Who.ME
        id == "User 2" -> Who.PARTNER
        else -> Who.JOINT // "SHARED", "BOTH", or someone no longer in the household
    }

    /** Owner of the account the money moved through; the entry's own owner when that account is gone. */
    fun paidBy(tx: Transaction, accounts: Map<String, Account>, myUid: String?, partnerUid: String?): Who {
        val owner = accounts[tx.accountId]?.ownerUserId?.takeIf { it.isNotBlank() } ?: tx.ownerUserId
        return whoIs(owner, myUid, partnerUid)
    }

    /** The "Paid for" choice on the entry. */
    fun paidFor(tx: Transaction, myUid: String?, partnerUid: String?): Who =
        whoIs(tx.paidByUserId, myUid, partnerUid)

    /**
     * Whether [tx] shows in one person's (or the joint) view: it was paid from their account, or
     * it is a transfer into one of their accounts.
     */
    fun inView(tx: Transaction, view: Who, accounts: Map<String, Account>, myUid: String?, partnerUid: String?): Boolean {
        if (paidBy(tx, accounts, myUid, partnerUid) == view) return true
        if (tx.type != TransactionType.TRANSFER) return false
        val target = tx.targetAccountId?.let { accounts[it] } ?: return false
        return whoIs(target.ownerUserId, myUid, partnerUid) == view
    }

    /** What to save for a "Paid for" choice, so both phones read it the same way. */
    fun paidForId(choice: String, myUid: String?, partnerUid: String?): String = when (choice) {
        "User 1" -> myUid ?: choice
        "User 2" -> partnerUid ?: choice
        else -> choice
    }
}
