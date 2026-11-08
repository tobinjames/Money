package com.example.data

import java.util.UUID
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.PropertyName

enum class AccountClass {
    ASSET,
    LIABILITY
}

enum class AccountMainCategory {
    SAVINGS,
    CREDIT_CARD,
    CASH,
    FIXED_DEPOSIT,
    MUTUAL_FUND,
    LOAN
}

enum class AccountType {
    SAVINGS_ACCOUNT,
    CREDIT_CARD,
    FIXED_DEPOSIT,
    MUTUAL_FUND,
    LOAN
}

enum class TransactionType {
    INCOME,
    EXPENSE,
    TRANSFER
}

enum class TransactionScope {
    PERSONAL,
    FAMILY
}

enum class TransactionStatus {
    CONFIRMED,
    PENDING_REVIEW
}

data class Account(
    val accountId: String = UUID.randomUUID().toString(),
    val accountName: String = "",
    val accountType: String = "ASSET", // ASSET, LIABILITY, INCOME, EXPENSE
    val openingBalance: Double = 0.0,
    val asOfDate: Long = System.currentTimeMillis(),
    val lastReconciledDate: Long? = null,
    
    // Legacy compatibility fields with default values
    val lastFourDigits: String = "",
    val ownerUserId: String = "", // Firebase Auth UID of the owner (can be "SHARED" for joint accounts)
    val ownerFirstName: String = "", // First Name of the owner (e.g., "Rahul")
    val annualInterestRate: Double = 0.0, // e.g. 8.5 for 8.5%
    val remainingMonths: Int = 0,
    val monthlyEmi: Double = 0.0,
    val reconciledBalance: Double? = null,
    val billingCycleDay: Int = 0, // Day of month for billing cycle (e.g., 15)
    val paymentDueDay: Int = 0, // Day of month for bill payment due (e.g., 5)
    
    // Custom fields for Chart of Accounts (unified category model)
    val customAccountClass: String = "",
    val customMainCategory: String = "",
    val customSubCategory: String = "",
    val iconName: String = "",
    @get:PropertyName("isSystemDefault") @set:PropertyName("isSystemDefault")
    var isSystemDefault: Boolean = false,
    
    // computed balance
    val balance: Double = 0.0
) {
    @get:Exclude
    val id: String get() = accountId
    
    @get:Exclude
    val name: String get() = accountName
    
    @get:Exclude
    val type: AccountType get() = when {
        accountType == "LIABILITY" && mainCategory.equals("Loans", ignoreCase = true) -> AccountType.LOAN
        accountType == "LIABILITY" -> AccountType.CREDIT_CARD
        accountType == "ASSET" && mainCategory.equals("Fixed Deposits", ignoreCase = true) -> AccountType.FIXED_DEPOSIT
        accountType == "ASSET" && mainCategory.equals("Mutual Funds", ignoreCase = true) -> AccountType.MUTUAL_FUND
        else -> AccountType.SAVINGS_ACCOUNT
    }

    // Older loan accounts were saved without customMainCategory; their loan fields still give them away.
    @get:Exclude
    val looksLikeLoan: Boolean get() =
        accountType == "LIABILITY" && (annualInterestRate > 0.0 || monthlyEmi > 0.0 || remainingMonths > 0)
    
    @get:Exclude
    val accountClass: String get() = customAccountClass.ifBlank { accountType }
    
    @get:Exclude
    val mainCategory: String get() = customMainCategory.ifBlank {
        when (accountType) {
            "ASSET" -> "Bank Accounts"
            "LIABILITY" -> if (looksLikeLoan) "Loans" else "Credit Cards"
            "INCOME" -> "Direct Income"
            "EXPENSE" -> "Indirect Expenses"
            else -> "Bank Accounts"
        }
    }
    
    @get:Exclude
    val subCategory: String get() = customSubCategory.ifBlank { accountName }
}

data class Transaction(
    val transactionId: String = UUID.randomUUID().toString(),
    val amount: Double = 0.0,
    val date: Long = System.currentTimeMillis(),
    val payeeName: String = "",
    val debitAccountId: String = "",
    val creditAccountId: String = "",
    val entryType: String = "EXPENSE", // "EXPENSE", "INCOME", "TRANSFER"
    val isIgnored: Boolean = false,
    
    // Legacy compatibility fields with default values
    val id: String = transactionId,
    val timestamp: Long = date,
    val narration: String = payeeName,
    val type: TransactionType = when (entryType) {
        "INCOME" -> TransactionType.INCOME
        "TRANSFER" -> TransactionType.TRANSFER
        else -> TransactionType.EXPENSE
    },
    val category: String = when (entryType) {
        "EXPENSE" -> debitAccountId
        "INCOME" -> creditAccountId
        "TRANSFER" -> "Transfer"
        else -> ""
    },
    val accountId: String = when (entryType) {
        "EXPENSE" -> creditAccountId
        "INCOME" -> debitAccountId
        "TRANSFER" -> creditAccountId
        else -> creditAccountId
    },
    val targetAccountId: String? = when (entryType) {
        "TRANSFER" -> debitAccountId
        else -> null
    },
    val status: TransactionStatus = TransactionStatus.CONFIRMED,
    val paidByUserId: String = "", // "User 1" or "User 2"
    val ownerUserId: String = "", // Firebase Auth UID (owner of card/account or SHARED)
    val ownerFirstName: String = "", // First Name of the user (e.g. "Rahul")
    val scope: TransactionScope = TransactionScope.FAMILY,
    // Interest Split Details if this transaction was a Loan EMI payment
    @get:PropertyName("isLoanEmi") @set:PropertyName("isLoanEmi")
    var isLoanEmi: Boolean = false,
    val principalComponent: Double = 0.0,
    val interestComponent: Double = 0.0,
    val originalSmsText: String? = null,
    @get:PropertyName("isSmartRule") @set:PropertyName("isSmartRule")
    var isSmartRule: Boolean = false,
    @get:PropertyName("isProbablyDuplicate") @set:PropertyName("isProbablyDuplicate")
    var isProbablyDuplicate: Boolean = false,
    val tag: String = ""
)

data class Reminder(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val dueDate: Long = System.currentTimeMillis(),
    val amount: Double = 0.0,
    val type: String = "Credit Card", // "Credit Card", "SIP"
    @get:PropertyName("isRecurring") @set:PropertyName("isRecurring")
    var isRecurring: Boolean = false,
    @get:PropertyName("isPaid") @set:PropertyName("isPaid")
    var isPaid: Boolean = false,
    val accountId: String? = null
)

data class WhitelistedSender(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String = "", // e.g. "HDFC", "ICICI"
    val name: String = ""
)

enum class BudgetType {
    SOFT,
    HARD
}

data class Budget(
    val budgetId: String = UUID.randomUUID().toString(),
    val category: String = "",
    val mainCategory: String = "",
    val subCategory: String = "",
    val monthlyLimit: Double = 0.0,
    val currentSpent: Double = 0.0,
    val budgetType: BudgetType = BudgetType.SOFT
)

data class MerchantRule(
    val ruleId: String = UUID.randomUUID().toString(),
    val keyword: String = "",
    val assignedCategory: String = ""
)

data class PrivacyKeyword(
    val id: String = java.util.UUID.randomUUID().toString(),
    val keyword: String = ""
)
