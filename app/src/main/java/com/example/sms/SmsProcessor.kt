package com.example.sms

import android.content.Context
import android.util.Log
import com.example.ai.GeminiSmsParser
import com.example.ai.ParsedTransactionResponse
import com.example.data.*
import kotlinx.coroutines.flow.first
import java.util.UUID

object SmsProcessor {
    private const val TAG = "SmsProcessor"
    private const val PREFS_NAME = "expense_tracker_prefs"
    private const val KEY_LAST_PROCESSED_TIMESTAMP = "last_processed_sms_timestamp"

    /**
     * LOCAL PRE-FILTERING PIPELINE (Security & Cost guardrail)
     */
    fun preFilterSms(
        sender: String,
        body: String,
        whitelistedSenders: List<WhitelistedSender>,
        privacyKeywords: List<PrivacyKeyword>
    ): Boolean {
        val cleanBody = body.lowercase()
        val cleanSender = sender.lowercase().trim()

        // 1. Sender Whitelist Check: Keep ONLY if the sender ID contains a whitelisted sender shortcode
        val isWhitelisted = whitelistedSenders.any { 
            cleanSender.contains(it.senderId.lowercase().trim()) 
        }
        if (!isWhitelisted) {
            Log.d(TAG, "Pre-filter: Rejected SMS. Sender '$sender' is not whitelisted.")
            return false
        }

        // 2. Content Keyword Guardrail (OTP, Password, PIN, Login verification, etc.)
        val hasPrivacyKeyword = privacyKeywords.any { 
            cleanBody.contains(it.keyword.lowercase().trim()) 
        }
        if (hasPrivacyKeyword) {
            Log.d(TAG, "Pre-filter: Rejected SMS containing security sensitive privacy keywords.")
            return false
        }

        // 3. Statement / Bill Alert Pre-filter (Ignore statement generated alerts, minimum due notifications, reminders)
        val isStatementOrBillAlert = (
            cleanBody.contains("statement") ||
            cleanBody.contains("bill") ||
            cleanBody.contains("min due") ||
            cleanBody.contains("minimum due") ||
            cleanBody.contains("payment due") ||
            cleanBody.contains("total due") ||
            cleanBody.contains("due date")
        ) && !(
            cleanBody.contains("debited") ||
            cleanBody.contains("credited") ||
            cleanBody.contains("withdrawn") ||
            cleanBody.contains("deposited") ||
            cleanBody.contains("received") ||
            cleanBody.contains("refund") ||
            cleanBody.contains("spent") ||
            cleanBody.contains("paid rs") ||
            cleanBody.contains("paid to")
        )
        if (isStatementOrBillAlert) {
            Log.d(TAG, "Pre-filter: Rejected SMS. It is a statement notification or bill reminder, not an active spend/credit transaction.")
            return false
        }

        Log.d(TAG, "Pre-filter: SMS passed financial pre-screening successfully.")
        return true
    }

    /**
     * Core processing logic: parsing, category matches, accounts mapping, EMI split & updates database
     */
    suspend fun processSms(context: Context, sender: String, body: String, timestamp: Long) {
        Log.d(TAG, "Processing SMS from $sender received at $timestamp: $body")
        
        // Query current senders and accounts
        val whitelisted = FirebaseService.getWhitelistedSenders().first()
        val accounts = FirebaseService.getAccounts().first()
        val historicalTransactions = FirebaseService.getTransactions().first()
        val merchantRules = FirebaseService.getMerchantRules().first()
        val categories = FirebaseService.getCategories().first()
        val privacyKeywords = FirebaseService.getPrivacyKeywords().first()
        
        // 1. Entire SMS Exact Duplicate Check (Ignore the second one)
        val isExactDuplicate = historicalTransactions.any {
            it.originalSmsText?.trim() == body.trim()
        }
        if (isExactDuplicate) {
            Log.d(TAG, "Duplicate Check: Exact duplicate SMS body already processed. Ignoring.")
            return
        }

        // Pre-filter
        if (!preFilterSms(sender, body, whitelisted, privacyKeywords)) {
            return
        }

        // Parse through Gemini (with local regex fallback)
        val parsed = GeminiSmsParser.parseSms(body, merchantRules, categories, accounts) ?: return
        Log.d(TAG, "Parsed Transaction: $parsed")

        // 2. Map transaction to an Account using lastFourDigits match first, then dynamic Gemini matching
        var targetAccount: Account? = null
        
        // Prioritize direct match in the SMS body using the account's last digits (supports multiple comma-separated digits e.g. Account # & Debit Card #)
        if (accounts.isNotEmpty()) {
            val matchedByDigits = accounts.find { acc ->
                if (acc.lastFourDigits.isBlank()) false
                else {
                    val tokens = acc.lastFourDigits.split(",", "/", ";", " ", "|").map { it.trim() }.filter { it.length >= 3 }
                    tokens.any { token -> body.contains(token) }
                }
            }
            if (matchedByDigits != null) {
                targetAccount = matchedByDigits
                Log.d(TAG, "Account matched directly via lastFourDigits in SMS text: ${targetAccount.name}")
            }
        }

        // Fall back to Gemini's assigned account if no direct digit match was found in the raw body
        if (targetAccount == null && !parsed.assignedAccount.isNullOrEmpty()) {
            val cleanAssigned = parsed.assignedAccount.lowercase().trim()
            targetAccount = accounts.find { acc ->
                val tokens = acc.lastFourDigits.split(",", "/", ";", " ", "|").map { it.trim() }.filter { it.length >= 3 }
                val fullLabel = "${acc.name} (xx${acc.lastFourDigits})".lowercase().trim()
                cleanAssigned.contains(acc.name.lowercase().trim()) ||
                cleanAssigned.contains(fullLabel) ||
                acc.name.lowercase().trim().contains(cleanAssigned) ||
                tokens.any { token -> cleanAssigned.contains(token) }
            }
            if (targetAccount != null) {
                Log.d(TAG, "Account matched via Gemini assignedAccount: ${targetAccount.name}")
            }
        }
        
        if (targetAccount == null) {
            Log.w(TAG, "Could not map transaction via direct lastFourDigits or Gemini matching. Finding or creating fallback account.")
            val cleanBody = body.lowercase()
            val cleanAssigned = (parsed.assignedAccount ?: "").lowercase()
            val isCreditCard = cleanBody.contains("credit card") ||
                    cleanBody.contains("card ending") ||
                    cleanBody.contains("card no") ||
                    cleanBody.contains(" cc ") ||
                    cleanAssigned.contains("card") ||
                    cleanAssigned.contains("credit")

            targetAccount = if (isCreditCard) {
                accounts.find { it.accountType == "LIABILITY" || it.type == AccountType.CREDIT_CARD }
                    ?: FirebaseService.getOrCreateFallbackAccount(isCreditCard = true)
            } else {
                accounts.find { it.accountType == "ASSET" || it.type == AccountType.SAVINGS_ACCOUNT }
                    ?: accounts.firstOrNull()
                    ?: FirebaseService.getOrCreateFallbackAccount(isCreditCard = false)
            }
        }

        if (targetAccount == null) {
            targetAccount = FirebaseService.getOrCreateFallbackAccount(isCreditCard = false)
        }

        // 3. SMS Filtering Barrier Check: Compare SMS timestamp against "As Of Date"
        if (timestamp < targetAccount.asOfDate) {
            Log.d(TAG, "Timeline Skip: SMS Timestamp ($timestamp) < Account '${targetAccount.name}' As Of Date (${targetAccount.asOfDate}). Skipped.")
            updateLastProcessedTimestamp(context, timestamp)
            return
        }

        // 3ba. Historical Accounting Guardrail Check
        val isAssetOrLiability = targetAccount.accountType == "ASSET" || targetAccount.accountType == "LIABILITY"
        if (isAssetOrLiability) {
            targetAccount.lastReconciledDate?.let { lastRec ->
                if (timestamp < lastRec) {
                    Log.d(TAG, "Accounting Guardrail Skip: SMS Timestamp ($timestamp) is within closed period < lastReconciledDate ($lastRec) on Account '${targetAccount.name}'. Skipped.")
                    return
                }
            }
        }

        // 3ba_transfer. Double SMS Opposite Transfer Deduplication and Merging Logic
        val isCredit = parsed.transactionType == "CREDIT"
        val oppositeTransferTx = historicalTransactions.find { tx ->
            Math.abs(tx.amount - parsed.amount) < 0.01 &&
            isSameDay(tx.timestamp, timestamp) &&
            tx.accountId != targetAccount.id &&
            !(tx.type == TransactionType.TRANSFER && tx.accountId != null && tx.targetAccountId != null) &&
            if (isCredit) {
                tx.type == TransactionType.EXPENSE
            } else {
                tx.type == TransactionType.INCOME
            }
        }

        if (oppositeTransferTx != null) {
            Log.d(TAG, "Transfer Deduplication: Found opposite transaction of same amount on same day. Merging both SMS into a single TRANSFER transaction.")
            try {
                val oppositeAcc = accounts.find { it.id == oppositeTransferTx.accountId }
                val oppositeAccName = oppositeAcc?.name ?: "Other Account"
                
                val mergedTx = if (isCredit) {
                    // Current SMS is CREDIT to targetAccount. Opposite SMS was DEBIT from oppositeTransferTx.accountId.
                    // This is a TRANSFER from oppositeTransferTx.accountId -> targetAccount.id
                    oppositeTransferTx.copy(
                        type = TransactionType.TRANSFER,
                        category = "Transfer",
                        targetAccountId = targetAccount.id,
                        narration = "Transfer: $oppositeAccName → ${targetAccount.name}",
                        originalSmsText = "${oppositeTransferTx.originalSmsText ?: ""}\n[Credit SMS]: $body".trim(),
                        status = TransactionStatus.CONFIRMED,
                        isProbablyDuplicate = false
                    )
                } else {
                    // Current SMS is DEBIT from targetAccount. Opposite SMS was CREDIT to oppositeTransferTx.accountId.
                    // This is a TRANSFER from targetAccount.id -> oppositeTransferTx.accountId
                    oppositeTransferTx.copy(
                        type = TransactionType.TRANSFER,
                        category = "Transfer",
                        accountId = targetAccount.id,
                        targetAccountId = oppositeTransferTx.accountId,
                        narration = "Transfer: ${targetAccount.name} → $oppositeAccName",
                        originalSmsText = "[Credit SMS]: ${oppositeTransferTx.originalSmsText ?: ""}\n[Debit SMS]: $body".trim(),
                        status = TransactionStatus.CONFIRMED,
                        isProbablyDuplicate = false
                    )
                }
                
                FirebaseService.updateTransaction(mergedTx)
                Log.d(TAG, "Transfer Deduplication: Successfully merged debit & credit SMS into a single Transfer: ${mergedTx.id}")
                updateLastProcessedTimestamp(context, timestamp)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to merge opposite transfer transactions, falling back to standard processing: ${e.message}", e)
            }
        }

        // 3bb. Suspected Duplicate Check: same amount, same account, same calendar date
        val matchingDuplicateTx = historicalTransactions.find {
            Math.abs(it.amount - parsed.amount) < 0.01 &&
            (it.accountId == targetAccount.id || it.targetAccountId == targetAccount.id) &&
            isSameDay(it.timestamp, timestamp)
        }
        val isProbablyDuplicate = matchingDuplicateTx != null
        if (isProbablyDuplicate && matchingDuplicateTx != null) {
            Log.d(TAG, "Duplicate Check: Found potential duplicate transaction on same day with same amount. Setting isProbablyDuplicate = true.")
            // Tag the existing transaction as probably duplicate too, so BOTH get highlighted to the user
            if (!matchingDuplicateTx.isProbablyDuplicate) {
                try {
                    FirebaseService.updateTransaction(matchingDuplicateTx.copy(isProbablyDuplicate = true))
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating existing transaction duplicate tag", e)
                }
            }
        }

        // 4. Auto-Categorization Heuristics
        // Check if any Smart Rule (merchant rule) keyword is found in SMS body or narration
        var matchedRuleCategory: String? = null
        for (rule in merchantRules) {
            if (rule.keyword.isNotEmpty() && body.contains(rule.keyword, ignoreCase = true)) {
                matchedRuleCategory = rule.assignedCategory
                break
            }
        }
        val isSmartRuleMatched = matchedRuleCategory != null

        val matchedHistory = historicalTransactions.firstOrNull { 
            val cleanNarration = it.narration
                .replace("Spent at ", "")
                .substringAfter(" - ")
                .trim()
            cleanNarration.equals(parsed.vendor.trim(), ignoreCase = true) ||
            it.narration.replace("Spent at ", "").trim().equals(parsed.vendor.trim(), ignoreCase = true)
        }
        
        var category = when {
            matchedRuleCategory != null -> {
                Log.d(TAG, "Heuristic: Matched smart merchant rule. Assigning category: $matchedRuleCategory")
                matchedRuleCategory
            }
            matchedHistory != null && categories.any { it.name.equals(matchedHistory.category, ignoreCase = true) } -> {
                Log.d(TAG, "Heuristic: Found vendor in history with valid COA category: ${matchedHistory.category}")
                matchedHistory.category
            }
            else -> {
                parsed.suggestedCategory
            }
        }

        // Validate final category against COA categories
        val matchedCoa = categories.find { it.name.equals(category, ignoreCase = true) }
        if (matchedCoa != null) {
            category = matchedCoa.name
        } else if (category.trim().isEmpty()) {
            category = categories.find { it.name.equals("Others", ignoreCase = true) }?.name ?: "Unknown"
        }

        val isBrandNewVendor = matchedHistory == null
        val isUncertain = parsed.suggestedCategory.equals("Others", ignoreCase = true) || parsed.suggestedCategory.isEmpty() || parsed.suggestedCategory.equals("Unknown", ignoreCase = true)
        
        // Old SMS (e.g. backbilled from around opening date) or transactions with any uncertainty should be held for confirmation.
        val isOldSms = timestamp < (System.currentTimeMillis() - 10 * 60 * 1000)
        val status = if ((isUncertain || isBrandNewVendor || isOldSms) && !isSmartRuleMatched) {
            TransactionStatus.PENDING_REVIEW
        } else {
            TransactionStatus.CONFIRMED
        }

        // Check if this transaction represents a Loan EMI payment
        val isLoanAccount = targetAccount.type == AccountType.LOAN
        val containsEmiKeywords = body.lowercase().contains("emi") || body.lowercase().contains("loan installment") || body.lowercase().contains("installment")
        
        if (isLoanAccount || (containsEmiKeywords && accounts.any { it.type == AccountType.LOAN })) {
            // Find the loan account we are paying
            val loanAcc = if (isLoanAccount) targetAccount else accounts.firstOrNull { it.type == AccountType.LOAN }
            
            if (loanAcc != null) {
                // "Automated Interest Split: When an SMS matching an EMI payment is processed, the app must auto-calculate the split using the formula:
                // Interest Component = (Current Outstanding Loan Balance * Annual Interest Rate) / 12
                // Principal Component = Total EMI Paid - Interest Component"
                val emiPaid = parsed.amount
                val outstandingBalance = Math.abs(loanAcc.balance) // Holds negative balance, take absolute
                val annualRate = loanAcc.annualInterestRate
                
                val interestComponent = (outstandingBalance * (annualRate / 100.0)) / 12.0
                val principalComponent = (emiPaid - interestComponent).coerceAtLeast(0.0)
                
                Log.d(TAG, "EMI Split: EMI=$emiPaid, Outstanding=$outstandingBalance, Rate=$annualRate%, Interest=$interestComponent, Principal=$principalComponent")
                
                // Perform EMI splits Database Actions:
                // Action 1: Reduce the outstanding Loan Account balance by the Principal Component.
                val updatedLoanBalance = loanAcc.balance + principalComponent // outstanding was negative, so balance becomes closer to 0
                FirebaseService.updateAccount(loanAcc.copy(balance = updatedLoanBalance))
                
                // Find source of transfer (Savings/etc)
                val sourceSavingsAcc = accounts.find { it.type == AccountType.SAVINGS_ACCOUNT }
                val sourceId = sourceSavingsAcc?.id ?: "unknown_src"
                
                // Action 2: Log the Principal Component as a "Transfer" from Savings to the Loan Account (hidden from Expense charts)
                val transferTx = Transaction(
                    id = UUID.randomUUID().toString(),
                    amount = principalComponent,
                    type = TransactionType.TRANSFER,
                    category = "Transfer",
                    timestamp = timestamp,
                    accountId = sourceId,
                    targetAccountId = loanAcc.id,
                    paidByUserId = "User 1", // Assume husband by default, can be modified
                    scope = TransactionScope.FAMILY,
                    narration = "EMI Principal Auto-Transfer to ${loanAcc.name}",
                    status = TransactionStatus.CONFIRMED,
                    isLoanEmi = true,
                    principalComponent = principalComponent,
                    interestComponent = 0.0,
                    originalSmsText = body,
                    isProbablyDuplicate = isProbablyDuplicate
                )
                FirebaseService.addTransaction(transferTx)
                
                // Action 3: Log the Interest Component as a "Finance: Loan Interest" Expense (visible on Expense charts)
                val interestTx = Transaction(
                    id = UUID.randomUUID().toString(),
                    amount = interestComponent,
                    type = TransactionType.EXPENSE,
                    category = "Finance: Loan Interest",
                    timestamp = timestamp,
                    accountId = sourceId,
                    paidByUserId = "User 1",
                    scope = TransactionScope.FAMILY,
                    narration = "EMI Interest Expense for ${loanAcc.name}",
                    status = TransactionStatus.CONFIRMED,
                    isLoanEmi = true,
                    principalComponent = 0.0,
                    interestComponent = interestComponent,
                    originalSmsText = body,
                    isProbablyDuplicate = isProbablyDuplicate
                )
                FirebaseService.addTransaction(interestTx)
                
            } else {
                Log.w(TAG, "EMI keyword matched, but no LOAN account found to split EMI. Logging as standard Expense.")
                logStandardTransaction(targetAccount, parsed, timestamp, isUncertain, category, status, body, isSmartRuleMatched, isProbablyDuplicate)
            }
        } else {
            // Log as standard Transaction
            logStandardTransaction(targetAccount, parsed, timestamp, isUncertain, category, status, body, isSmartRuleMatched, isProbablyDuplicate)
        }

        // Save last processed SMS timestamp locally
        updateLastProcessedTimestamp(context, timestamp)
    }

    private suspend fun logStandardTransaction(
        account: Account,
        parsed: ParsedTransactionResponse,
        timestamp: Long,
        isUncertain: Boolean,
        category: String,
        status: TransactionStatus,
        body: String,
        isSmartRule: Boolean,
        isProbablyDuplicate: Boolean
    ) {
        val cleanType = if (parsed.transactionType == "CREDIT") TransactionType.INCOME else TransactionType.EXPENSE
        val cleanVendor = parsed.vendor.replace("Spent at ", "").trim()
        val finalNarration = if (cleanVendor.isNotBlank()) "$category - $cleanVendor" else category

        val entryTypeStr = cleanType.name
        val debitId = if (cleanType == TransactionType.INCOME) account.accountId else category
        val creditId = if (cleanType == TransactionType.INCOME) category else account.accountId

        val tx = Transaction(
            transactionId = UUID.randomUUID().toString(),
            id = UUID.randomUUID().toString(),
            amount = parsed.amount,
            type = cleanType,
            category = category,
            timestamp = timestamp,
            date = timestamp,
            accountId = account.accountId,
            debitAccountId = debitId,
            creditAccountId = creditId,
            entryType = entryTypeStr,
            payeeName = cleanVendor,
            narration = finalNarration,
            paidByUserId = if (account.ownerUserId.isNotEmpty()) account.ownerUserId else "User 1",
            scope = TransactionScope.FAMILY,
            status = status,
            originalSmsText = body,
            isSmartRule = isSmartRule,
            isProbablyDuplicate = isProbablyDuplicate
        )
        FirebaseService.addTransaction(tx)
    }

    /**
     * SharedPreferences persistence for reconciliation
     */
    fun getLastProcessedTimestamp(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_PROCESSED_TIMESTAMP, 0L)
    }

    fun updateLastProcessedTimestamp(context: Context, timestamp: Long) {
        val currentMax = getLastProcessedTimestamp(context)
        if (timestamp > currentMax) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_LAST_PROCESSED_TIMESTAMP, timestamp).apply()
            Log.d(TAG, "Successfully updated last_processed_sms_timestamp locally to: $timestamp")
        }
    }

    private fun extractLast4Digits(text: String): String? {
        // Search 4 consecutive digits matching cards or accounts
        val cardRegex = Regex("""\b\d{4}\b""")
        val matches = cardRegex.findAll(text).toList()
        return if (matches.isNotEmpty()) {
            // Often, it's the last 4 sequence, let's take the first or last depending on common templates
            // Usually, the card/account numbers are the only 4 digit isolated sequences
            matches.last().value
        } else {
            null
        }
    }

    private fun isSameDay(t1: Long, t2: Long): Boolean {
        val cal1 = java.util.Calendar.getInstance().apply { timeInMillis = t1 }
        val cal2 = java.util.Calendar.getInstance().apply { timeInMillis = t2 }
        return cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
               cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR)
    }
}
