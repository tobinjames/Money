package com.example.sms

import android.content.Context
import android.util.Log
import com.example.ai.GeminiSmsParser
import com.example.ai.ParseOutcome
import com.example.ai.ParsedTransactionResponse
import com.example.data.*
import com.example.domain.FreeToSpendRules
import com.example.domain.LedgerRules
import com.example.domain.SenderRules
import com.example.domain.SmsRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Turns one bank SMS into a transaction. Every path that reads SMS (a new message, the catch-up
 * when the app opens, the scan after adding an account) comes through [processSms], one message
 * at a time, and each message is handled once (see [SmsLog]).
 */
object SmsProcessor {
    private const val TAG = "SmsProcessor"
    private const val PREFS_NAME = "expense_tracker_prefs"
    private const val KEY_LAST_PROCESSED_TIMESTAMP = "last_processed_sms_timestamp"
    private const val DEBIT_MARK = "[Debit SMS]: "
    private const val CREDIT_MARK = "[Credit SMS]: "

    // One message at a time, so two scans can't both save the same SMS.
    private val lock = Mutex()

    /** Keeps only SMS from a saved bank sender that aren't private (OTP...) or statement notices. */
    fun preFilterSms(
        sender: String,
        body: String,
        whitelistedSenders: List<WhitelistedSender>,
        privacyKeywords: List<PrivacyKeyword>
    ): Boolean {
        if (whitelistedSenders.none { SenderRules.matches(sender.lowercase().trim(), it.senderId) }) return false
        if (SmsRules.blockedByPrivacy(body, privacyKeywords.map { it.keyword })) return false
        if (SmsRules.isStatementNotice(body)) return false
        return true
    }

    /**
     * Returns false when the message could not be looked at yet (not signed in, data not loaded)
     * and should be tried again later; true once it has been dealt with one way or another.
     */
    suspend fun processSms(context: Context, sender: String, body: String, timestamp: Long): Boolean =
        lock.withLock { process(context.applicationContext, sender, body, timestamp) }

    /**
     * Sends the SMS Gemini missed to it again. Stops when Gemini is still not answering. What
     * the simple reader made from a message is replaced by Gemini's reading, unless you have
     * already reviewed or deleted it. Returns how many were read.
     */
    suspend fun retryWithGemini(context: Context): Int = lock.withLock {
        val app = context.applicationContext
        if (!FirebaseService.hasSignedInUser() || !FirebaseService.ensureCoreDataLoaded()) return@withLock 0
        val waiting = GeminiStatus.missed(app)
        if (waiting.isEmpty()) return@withLock 0
        GeminiStatus.setRetrying(true)
        var read = 0
        var failures = 0
        try {
            for (item in waiting) {
                val outcome = GeminiSmsParser.parseDetailed(
                    item.body,
                    FirebaseService.getMerchantRules().first(),
                    FirebaseService.getCategories().first(),
                    FirebaseService.getAccounts().first()
                )
                if (!outcome.geminiOk) {
                    GeminiStatus.recordFailure(app, outcome.error)
                    // Give up on a message after two weeks, so one odd SMS can't wait for ever.
                    if (System.currentTimeMillis() - item.timestamp > 14L * 24 * 60 * 60 * 1000) GeminiStatus.removeMissed(app, item)
                    // One failure may be that message; two in a row means Gemini is still down.
                    if (++failures >= 2) break
                    continue
                }
                failures = 0
                GeminiStatus.recordOk(app)
                val made = FirebaseService.getTransactions().first().filter { it.transactionId in item.transactionIds }
                val untouched = made.size == item.transactionIds.size && made.all { it.status == TransactionStatus.PENDING_REVIEW }
                if (untouched) {
                    try {
                        made.forEach { FirebaseService.deleteTransaction(it.transactionId) }
                        SmsLog.forget(app, item.sender, item.body)
                        process(app, item.sender, item.body, item.timestamp, outcome, item.transactionIds.toSet())
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not re-read one SMS: ${e.message}")
                    }
                }
                // Reviewed or deleted by hand already: leave it as the user left it.
                GeminiStatus.removeMissed(app, item)
                read++
            }
        } finally {
            GeminiStatus.setRetrying(false)
        }
        read
    }

    private suspend fun process(
        context: Context, sender: String, body: String, timestamp: Long,
        alreadyRead: ParseOutcome? = null, replacing: Set<String> = emptySet()
    ): Boolean {
        // Nothing can be read or saved before sign-in (Firestore rules). Leave the message in the
        // inbox, without moving the "last processed" marker, so the catch-up picks it up later.
        if (!FirebaseService.hasSignedInUser()) return false
        // Wait for real data: a freshly started background process starts with empty lists.
        if (!FirebaseService.ensureCoreDataLoaded()) return false

        val whitelisted = FirebaseService.getWhitelistedSenders().first()
        val privacyKeywords = FirebaseService.getPrivacyKeywords().first()
        if (!preFilterSms(sender, body, whitelisted, privacyKeywords)) return true
        if (SmsLog.isHandled(context, sender, body)) return true

        val accounts = FirebaseService.getAccounts().first()
        // On a second read, what the first read made is being replaced: don't match against it.
        val history = FirebaseService.getTransactions().first().filterNot { it.transactionId in replacing }
        val merchantRules = FirebaseService.getMerchantRules().first()
        val categories = FirebaseService.getCategories().first()

        // What this message was saved as, in case Gemini has to read it again later.
        val savedIds = ArrayList<String>()
        var geminiMissed = false
        suspend fun add(tx: Transaction) {
            FirebaseService.addTransaction(tx)
            savedIds.add(tx.transactionId)
        }
        fun done(): Boolean {
            if (geminiMissed) GeminiStatus.addMissed(context, GeminiStatus.Missed(sender, body, timestamp, savedIds.toList()))
            SmsLog.markHandled(context, sender, body)
            updateLastProcessedTimestamp(context, timestamp)
            return true
        }

        // Already saved (this phone's log was lost, or the partner's phone saw the same SMS).
        if (history.any { SmsRules.sameSms(it.originalSmsText, body) }) return done()

        val outcome = alreadyRead ?: GeminiSmsParser.parseDetailed(body, merchantRules, categories, accounts)
        if (outcome.geminiOk) GeminiStatus.recordOk(context) else GeminiStatus.recordFailure(context, outcome.error)
        // Gemini couldn't be reached: keep the message so it can be sent again when it is back.
        geminiMissed = !outcome.geminiOk
        val parsed = outcome.parsed ?: return done()
        if (parsed.amount <= 0.0) return done()
        val isCredit = parsed.transactionType == "CREDIT"

        // --- Which account ---
        val named = SmsRules.accountsNamed(body, accounts)
        val payment = SmsRules.cardPayment(body, named)
        var guessed = false
        val targetAccount: Account = when {
            // "Debited from a/c XX1234 towards card XX5678": the bank for a debit, the card for a credit.
            payment != null -> if (isCredit) payment.second else payment.first
            named.isNotEmpty() -> named.first()
            else -> accountFromParser(parsed, accounts) ?: run {
                guessed = true
                guessAccount(body, parsed, accounts)
            }
        }

        // Before the account's start date: its opening balance already includes this.
        if (timestamp < targetAccount.asOfDate) return done()
        // Inside a period already reconciled: the reconciled balance already includes this.
        if (targetAccount.lastReconciledDate?.let { timestamp < it } == true) return done()

        // --- Transfers between the family's own accounts ---
        val ownAccountIds = accounts.map { it.id }.toSet()
        val cardIds = accounts.filter { FreeToSpendRules.isCard(it) }.map { it.id }.toSet()
        val mark = if (isCredit) CREDIT_MARK else DEBIT_MARK

        // The other side's SMS already made this a transfer: add this SMS to it and stop.
        LedgerRules.findRecordedTransfer(history, parsed.amount, timestamp, isCredit, targetAccount.id, cardIds) {
            it.originalSmsText?.contains(mark) == true
        }?.let { transfer ->
            if (trySave { FirebaseService.updateTransaction(transfer.copy(originalSmsText = "${transfer.originalSmsText}\n$mark$body")) }) {
                geminiMissed = false
                return done()
            }
        }

        // The other side's SMS was saved as a plain expense or income: join the two into a transfer.
        LedgerRules.findTransferCounterpart(
            history = history.filter { it.type != TransactionType.TRANSFER },
            amount = parsed.amount,
            timestamp = timestamp,
            isCredit = isCredit,
            accountId = targetAccount.id,
            ownAccountIds = ownAccountIds,
            smsBody = body,
            cardAccountIds = cardIds
        )?.let { other ->
            val otherAccount = accounts.find { it.id == other.accountId }
            val from = if (isCredit) otherAccount else targetAccount
            val to = if (isCredit) targetAccount else otherAccount
            val label = "Transfer: ${from?.name ?: "Other account"} → ${to?.name ?: "Other account"}"
            val otherText = other.originalSmsText ?: ""
            val merged = other.copy(
                type = TransactionType.TRANSFER,
                category = "Transfer",
                accountId = from?.id ?: other.accountId,
                targetAccountId = to?.id ?: targetAccount.id,
                narration = label,
                payeeName = label,
                originalSmsText = if (isCredit) "$DEBIT_MARK$otherText\n$CREDIT_MARK$body" else "$DEBIT_MARK$body\n$CREDIT_MARK$otherText",
                status = TransactionStatus.CONFIRMED,
                isProbablyDuplicate = false
            )
            if (trySave { FirebaseService.updateTransaction(merged) }) {
                geminiMissed = false
                return done()
            }
        }

        // A bill payment that names both the bank account and the card: a transfer straight away.
        if (payment != null) {
            val (bank, card) = payment
            if (card.lastReconciledDate?.let { timestamp < it } != true) {
                val label = "Card payment: ${bank.name} → ${card.name}"
                add(
                    Transaction(
                        amount = parsed.amount,
                        type = TransactionType.TRANSFER,
                        category = "Transfer",
                        timestamp = timestamp,
                        date = timestamp,
                        accountId = bank.id,
                        targetAccountId = card.id,
                        paidByUserId = payerOf(bank),
                        narration = label,
                        payeeName = label,
                        status = TransactionStatus.CONFIRMED,
                        originalSmsText = mark + body
                    )
                )
                return done()
            }
        }

        // --- Suspected duplicate: same amount, same account, same day (both get flagged) ---
        val twin = history.find {
            Math.abs(it.amount - parsed.amount) < 0.01 && !it.isIgnored &&
                (it.accountId == targetAccount.id || it.targetAccountId == targetAccount.id) &&
                isSameDay(it.timestamp, timestamp)
        }
        val isProbablyDuplicate = twin != null
        // The earlier one keeps counting; flagging it only highlights the pair on screen.
        if (twin != null && !twin.isProbablyDuplicate && twin.status == TransactionStatus.CONFIRMED) {
            trySave { FirebaseService.updateTransaction(twin.copy(isProbablyDuplicate = true)) }
        }

        // --- Category ---
        val rule = merchantRules.firstOrNull { it.keyword.isNotEmpty() && body.contains(it.keyword, ignoreCase = true) }
        val vendorKnown = !SmsRules.isUnknownVendor(parsed.vendor)
        val matchedHistory = if (!vendorKnown) null else history.firstOrNull {
            val vendor = parsed.vendor.trim()
            it.narration.replace("Spent at ", "").substringAfter(" - ").trim().equals(vendor, ignoreCase = true) ||
                it.narration.replace("Spent at ", "").trim().equals(vendor, ignoreCase = true)
        }
        var category = when {
            rule != null -> rule.assignedCategory
            matchedHistory != null && categories.any { it.name.equals(matchedHistory.category, ignoreCase = true) } -> matchedHistory.category
            else -> parsed.suggestedCategory
        }
        val inChart = categories.find { it.name.equals(category, ignoreCase = true) }
        if (inChart != null) category = inChart.name
        else if (category.isBlank()) category = categories.find { it.name.equals("Others", ignoreCase = true) }?.name ?: "Unknown"

        val isUncertain = parsed.suggestedCategory.isBlank() ||
            parsed.suggestedCategory.equals("Others", ignoreCase = true) || parsed.suggestedCategory.equals("Unknown", ignoreCase = true)
        val isOldSms = timestamp < System.currentTimeMillis() - 10 * 60 * 1000
        val foreign = SmsRules.foreignCurrencyOnly(body)
        // Anything the app had to guess is held for review. Review items still count in balances.
        val needsReview = guessed || parsed.fromBackup || foreign || isProbablyDuplicate ||
            (rule == null && (isUncertain || matchedHistory == null || isOldSms))
        val status = if (needsReview) TransactionStatus.PENDING_REVIEW else TransactionStatus.CONFIRMED

        // --- A rule that points at an investment account: money moved there, not spent ---
        val investment = if (rule != null && !isCredit && targetAccount.accountType == "ASSET") {
            accounts.find {
                it.id != targetAccount.id && it.accountType == "ASSET" && !FreeToSpendRules.isLiquid(it) &&
                    it.name.equals(rule.assignedCategory, ignoreCase = true)
            }
        } else null
        if (investment != null && investment.lastReconciledDate?.let { timestamp < it } != true) {
            val label = "Investment: ${targetAccount.name} → ${investment.name}"
            add(
                Transaction(
                    amount = parsed.amount,
                    type = TransactionType.TRANSFER,
                    category = "Transfer",
                    timestamp = timestamp,
                    date = timestamp,
                    accountId = targetAccount.id,
                    targetAccountId = investment.id,
                    paidByUserId = payerOf(targetAccount),
                    narration = label,
                    payeeName = label,
                    status = TransactionStatus.CONFIRMED,
                    originalSmsText = body,
                    isSmartRule = true,
                    isProbablyDuplicate = isProbablyDuplicate
                )
            )
            return done()
        }

        // --- Loan payments: interest is an expense, the rest pays down the loan ---
        val lowerBody = body.lowercase()
        val mentionsEmi = lowerBody.contains("emi") || lowerBody.contains("installment") || lowerBody.contains("instalment")
        val loans = accounts.filter { it.type == AccountType.LOAN }
        // An EMI debited from a bank account must name the loan (digits or name), or say "loan"
        // when there is only one. Card "converted to EMI" messages are not loan payments.
        val loan: Account? = when {
            targetAccount.type == AccountType.LOAN -> targetAccount
            mentionsEmi && !isCredit && targetAccount.accountType == "ASSET" && !lowerBody.contains("converted") ->
                loans.firstOrNull { l ->
                    SmsRules.digitTokens(l).any { SmsRules.namesDigits(body, it) } || (l.name.length >= 3 && lowerBody.contains(l.name.lowercase()))
                } ?: loans.singleOrNull()?.takeIf { lowerBody.contains("loan") }
            else -> null
        }
        if (loan != null && !isCredit && loan.lastReconciledDate?.let { timestamp < it } != true) {
            val source = if (targetAccount.id != loan.id) targetAccount
                else accounts.find { FreeToSpendRules.isLiquid(it) } ?: FirebaseService.getOrCreateFallbackAccount(isCreditCard = false)
            val (interest, principal) = LedgerRules.emiSplit(
                amount = parsed.amount,
                outstanding = Math.abs(loan.balance),
                annualRate = loan.annualInterestRate,
                interestAlreadyCharged = LedgerRules.loanPaidInMonth(loan.id, history, timestamp)
            )
            // The paying account was a guess when the SMS was matched to the loan itself.
            val emiStatus = if (targetAccount.id == loan.id || guessed) TransactionStatus.PENDING_REVIEW else TransactionStatus.CONFIRMED
            if (principal > 0.0) {
                add(
                    Transaction(
                        amount = principal,
                        type = TransactionType.TRANSFER,
                        category = "Transfer",
                        timestamp = timestamp,
                        date = timestamp,
                        accountId = source.id,
                        targetAccountId = loan.id,
                        paidByUserId = payerOf(source),
                        narration = "EMI principal to ${loan.name}",
                        status = emiStatus,
                        isLoanEmi = true,
                        principalComponent = principal,
                        originalSmsText = body,
                        isProbablyDuplicate = isProbablyDuplicate
                    )
                )
            }
            if (interest > 0.0) {
                add(
                    Transaction(
                        amount = interest,
                        type = TransactionType.EXPENSE,
                        category = LedgerRules.LOAN_INTEREST_CATEGORY,
                        timestamp = timestamp,
                        date = timestamp,
                        accountId = source.id,
                        paidByUserId = payerOf(source),
                        narration = "EMI interest for ${loan.name}",
                        status = emiStatus,
                        isLoanEmi = true,
                        interestComponent = interest,
                        originalSmsText = body,
                        isProbablyDuplicate = isProbablyDuplicate
                    )
                )
            }
            return done()
        }

        // --- An ordinary expense or income ---
        val type = if (isCredit) TransactionType.INCOME else TransactionType.EXPENSE
        val vendor = if (vendorKnown) parsed.vendor.replace("Spent at ", "").trim() else ""
        val note = if (foreign) "Check amount (foreign currency): " else ""
        add(
            Transaction(
                amount = parsed.amount,
                type = type,
                category = category,
                timestamp = timestamp,
                date = timestamp,
                accountId = targetAccount.accountId,
                entryType = type.name,
                payeeName = vendor,
                narration = note + if (vendor.isNotBlank()) "$category - $vendor" else category,
                paidByUserId = payerOf(targetAccount),
                scope = TransactionScope.FAMILY,
                status = status,
                originalSmsText = body,
                isSmartRule = rule != null,
                isProbablyDuplicate = isProbablyDuplicate
            )
        )
        return done()
    }

    private fun payerOf(account: Account): String = account.ownerUserId.ifBlank { FreeToSpendRules.SHARED }

    /** A save that may be refused (a reconciled period); false when it was. */
    private suspend fun trySave(save: suspend () -> Unit): Boolean = try {
        save()
        true
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Not saved: ${e.message}")
        false
    }

    /** The account Gemini (or the backup reader) picked, matched back to a real account by name. */
    private fun accountFromParser(parsed: ParsedTransactionResponse, accounts: List<Account>): Account? {
        val assigned = parsed.assignedAccount?.lowercase()?.trim().orEmpty()
        // The backup reader falls back to "the first account", which is a guess, not a match.
        if (assigned.isEmpty() || parsed.fromBackup) return null
        return accounts.find { acc ->
            val name = acc.name.lowercase().trim()
            name.isNotEmpty() && (assigned.contains(name) || name.contains(assigned) ||
                SmsRules.digitTokens(acc).any { assigned.contains(it) })
        }
    }

    /** No account could be matched: the first card or bank account, and the item goes to review. */
    private suspend fun guessAccount(body: String, parsed: ParsedTransactionResponse, accounts: List<Account>): Account {
        val lower = body.lowercase()
        val assigned = (parsed.assignedAccount ?: "").lowercase()
        val looksLikeCard = lower.contains("credit card") || lower.contains("card ending") || lower.contains("card no") ||
            lower.contains(" cc ") || assigned.contains("card") || assigned.contains("credit")
        return if (looksLikeCard) {
            accounts.find { FreeToSpendRules.isCard(it) } ?: FirebaseService.getOrCreateFallbackAccount(isCreditCard = true)
        } else {
            accounts.find { FreeToSpendRules.isLiquid(it) } ?: FirebaseService.getOrCreateFallbackAccount(isCreditCard = false)
        }
    }

    fun getLastProcessedTimestamp(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_PROCESSED_TIMESTAMP, 0L)

    fun updateLastProcessedTimestamp(context: Context, timestamp: Long) {
        if (timestamp > getLastProcessedTimestamp(context)) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_PROCESSED_TIMESTAMP, timestamp).apply()
        }
    }

    private fun isSameDay(t1: Long, t2: Long): Boolean {
        val cal1 = java.util.Calendar.getInstance().apply { timeInMillis = t1 }
        val cal2 = java.util.Calendar.getInstance().apply { timeInMillis = t2 }
        return cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
            cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR)
    }
}
