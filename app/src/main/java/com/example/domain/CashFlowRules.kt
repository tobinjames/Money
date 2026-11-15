package com.example.domain

import com.example.data.Account
import com.example.data.Transaction
import com.example.domain.PayerRules.Who

/**
 * Money by person, on a cash basis: who paid, whom it was for, and who received.
 *
 * "Paid" follows the account the money left (its owner, or joint). "For" is the entry's
 * "Paid for" choice: Family, or one person. "Received" follows the account income came into.
 */
object CashFlowRules {

    data class Summary(
        /** Spending by (who paid, whom it was for); [Who.JOINT] as "for" means Family. */
        val paid: Map<Pair<Who, Who>, Double>,
        /** Income by who received it. */
        val received: Map<Who, Double>
    ) {
        fun paid(by: Who, forWhom: Who): Double = paid[by to forWhom] ?: 0.0

        /** Family expenses by who paid them. */
        fun familyPaidBy(who: Who): Double = paid(who, Who.JOINT)

        val familyTotal: Double get() = Who.values().sumOf { familyPaidBy(it) }

        /** Everything spent for one person (or Family), whoever paid. */
        fun totalFor(forWhom: Who): Double = Who.values().sumOf { paid(it, forWhom) }

        /**
         * How much more of the family expenses I paid from my own accounts than my partner did
         * from theirs (negative when they paid more). Joint account spending is left out.
         */
        val familyLead: Double get() = familyPaidBy(Who.ME) - familyPaidBy(Who.PARTNER)
    }

    fun summary(
        txs: List<Transaction>,
        accounts: Map<String, Account>,
        myUid: String?,
        partnerUid: String?,
        isExpenseCategory: (String) -> Boolean
    ): Summary {
        val paid = mutableMapOf<Pair<Who, Who>, Double>()
        val received = mutableMapOf<Who, Double>()
        txs.forEach { tx ->
            // The account the money went through decides both who paid and who received.
            val owner = PayerRules.paidBy(tx, accounts, myUid, partnerUid)
            val spend = LedgerRules.spendOf(tx, isExpenseCategory)
            if (spend != 0.0) {
                val key = owner to PayerRules.paidFor(tx, myUid, partnerUid)
                paid[key] = (paid[key] ?: 0.0) + spend
            }
            val income = LedgerRules.incomeOf(tx, isExpenseCategory)
            if (income != 0.0) received[owner] = (received[owner] ?: 0.0) + income
        }
        return Summary(paid, received)
    }
}
