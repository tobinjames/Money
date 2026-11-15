package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.LedgerRules
import com.example.domain.NetWorthRules
import com.example.domain.PayerRules
import com.example.data.*
import com.example.sms.SmsProcessor
import com.example.ai.GeminiSmsParser
import com.example.ai.ParsedTransactionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.Date
import java.util.Locale
import java.util.Calendar
import java.text.SimpleDateFormat

sealed class ViewFilter {
    object Combined : ViewFilter()
    object User1 : ViewFilter()
    object User2 : ViewFilter()
}

class ExpenseViewModel : ViewModel() {
    private val TAG = "ExpenseViewModel"

    // Raw synced streams from service
    val accounts = FirebaseService.getAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
    val transactions = FirebaseService.getTransactions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
    val reminders = FirebaseService.getReminders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
    val whitelistedSenders = FirebaseService.getWhitelistedSenders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
    val privacyKeywords = FirebaseService.getPrivacyKeywords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
    val budgets = FirebaseService.getBudgets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reserves = FirebaseService.getReserves()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent = _toastEvent.asSharedFlow()

    fun triggerToast(msg: String) {
        viewModelScope.launch {
            _toastEvent.emit(msg)
        }
    }

    private val _isSmsAutoSyncEnabled = MutableStateFlow(true)
    val isSmsAutoSyncEnabled = _isSmsAutoSyncEnabled.asStateFlow()

    fun loadSmsAutoSyncSetting(context: Context) {
        val prefs = context.getSharedPreferences("sms_auto_sync_prefs", Context.MODE_PRIVATE)
        _isSmsAutoSyncEnabled.value = prefs.getBoolean("auto_sync_enabled", true)
    }

    fun setSmsAutoSyncSetting(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("sms_auto_sync_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("auto_sync_enabled", enabled).apply()
        _isSmsAutoSyncEnabled.value = enabled
    }

    val categories = FirebaseService.getCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val merchantRules = FirebaseService.getMerchantRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI State variables linked to Firebase SSO
    val currentUserUid = FirebaseService.currentUserUid
    val currentUserFirstName = FirebaseService.currentUserFirstName
    val spouseUserUid = FirebaseService.spouseUserUid
    val spouseUserFirstName = FirebaseService.spouseUserFirstName

    // UI state filters
    private val _currentFilter = MutableStateFlow<ViewFilter>(ViewFilter.User1)
    val currentFilter: StateFlow<ViewFilter> = _currentFilter.asStateFlow()

    private val _scopeFilter = MutableStateFlow<TransactionScope?>(null) // null means both
    val scopeFilter: StateFlow<TransactionScope?> = _scopeFilter.asStateFlow()

    // Combined calculations (Stateflows derived from raw data and views filters)
    private val householdUids = combine(currentUserUid, spouseUserUid) { mine, partners -> mine to partners }

    /** The dashboard tab as a person: me, my partner, or the joint accounts. */
    private fun viewOf(filter: ViewFilter) = when (filter) {
        ViewFilter.User1 -> PayerRules.Who.ME
        ViewFilter.User2 -> PayerRules.Who.PARTNER
        ViewFilter.Combined -> PayerRules.Who.JOINT
    }

    /**
     * The owner id the dashboard's selected tab stands for: my uid, my partner's, or "SHARED".
     * Null when that person isn't known (no partner has joined yet).
     */
    val viewOwnerId: StateFlow<String?> = combine(currentFilter, householdUids) { filter, (myUid, partnerUid) ->
        when (filter) {
            ViewFilter.User1 -> myUid
            ViewFilter.User2 -> partnerUid
            ViewFilter.Combined -> com.example.domain.FreeToSpendRules.SHARED
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Combined calculations (Stateflows derived from raw data and views filters)
    val filteredAccounts = combine(accounts, currentFilter, householdUids) { list, filter, (myUid, partnerUid) ->
        val view = viewOf(filter)
        list.filter { PayerRules.whoIs(it.ownerUserId, myUid, partnerUid) == view }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Each tab shows what was paid from that person's accounts (joint accounts under Shared).
    val filteredTransactions = combine(transactions, accounts, currentFilter, scopeFilter, householdUids) { txList, accList, ownerFilter, scope, (myUid, partnerUid) ->
        val view = viewOf(ownerFilter)
        val byId = accList.associateBy { it.accountId }
        txList.filter { tx ->
            (scope == null || tx.scope == scope) && PayerRules.inView(tx, view, byId, myUid, partnerUid)
        }.sortedByDescending { it.timestamp }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Net Assets/Liabilities Summary: Refactored to support AccountMainCategory & AccountClass (Features 1 & 2)
    val netFinancialCalculator = combine(filteredAccounts, accounts) { filtered, all ->
        // Active filtered level metrics (Savings & Cash are positive, Credit card are dues)
        val savingsCash = filtered.filter { 
            it.mainCategory == "Bank Accounts" || 
            it.mainCategory == "Cash" 
        }.sumOf { it.balance }
        
        val creditDues = filtered.filter { 
            it.mainCategory == "Credit Cards" 
        }.sumOf { Math.abs(it.balance) }
        
        val liquidFundsVal = savingsCash - creditDues
        
        val fdSum = filtered.filter { it.mainCategory == "Fixed Deposits" }.sumOf { it.balance }
        val mfSum = filtered.filter { it.mainCategory == "Mutual Funds" }.sumOf { it.balance }
        val investmentSum = fdSum + mfSum
        
        val loanLiabilities = filtered.filter { it.mainCategory == "Loans" }.sumOf { Math.abs(it.balance) }
        
        // Global calculations (Family level, all documents):
        val allSavingsCash = all.filter { 
            it.mainCategory == "Bank Accounts" || 
            it.mainCategory == "Cash" 
        }.sumOf { it.balance }
        
        val allCreditCardDues = all.filter { 
            it.mainCategory == "Credit Cards" 
        }.sumOf { Math.abs(it.balance) }
        
        val allFds = all.filter { it.mainCategory == "Fixed Deposits" }.sumOf { it.balance }
        val allMfs = all.filter { it.mainCategory == "Mutual Funds" }.sumOf { it.balance }
        
        val allLoans = all.filter { it.mainCategory == "Loans" }.sumOf { Math.abs(it.balance) }
        
        val familyNetWorthVal = (allSavingsCash + allFds + allMfs) - (allCreditCardDues + allLoans)
        
        NetFinancials(
            liquidFunds = liquidFundsVal,
            savingsCashSum = savingsCash,
            creditCardDues = creditDues,
            fixedDeposits = fdSum,
            mutualFunds = mfSum,
            totalInvestments = investmentSum,
            loanOutstanding = loanLiabilities,
            familyNetWorth = familyNetWorthVal
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NetFinancials())

    // ---------------------------------------------------------------------------------------
    // Free to spend (personal) and reserves (shared)
    // ---------------------------------------------------------------------------------------

    /** Day of the month this person's salary arrives, 0 when not set. Kept on this phone. */
    val salaryDay = MutableStateFlow(0)
    val lowBalanceThreshold = MutableStateFlow(0.0)

    fun loadMoneyPrefs(context: Context) {
        salaryDay.value = com.example.reminders.AlertPrefs.salaryDay(context)
        lowBalanceThreshold.value = com.example.reminders.AlertPrefs.lowBalanceThreshold(context)
    }

    fun setSalaryDay(context: Context, day: Int) {
        com.example.reminders.AlertPrefs.setSalaryDay(context, day)
        salaryDay.value = day
    }

    fun setLowBalanceThreshold(context: Context, amount: Double) {
        com.example.reminders.AlertPrefs.setLowBalanceThreshold(context, amount)
        lowBalanceThreshold.value = amount
    }

    private val freeToSpendInputs = combine(accounts, transactions, reminders, reserves) { a, t, r, s -> listOf(a, t, r, s) }

    private fun freeToSpendFor(owner: kotlinx.coroutines.flow.Flow<String?>): StateFlow<com.example.domain.FreeToSpendRules.Breakdown?> =
        combine(freeToSpendInputs, salaryDay, owner) { inputs, day, uid ->
            if (uid == null) return@combine null
            @Suppress("UNCHECKED_CAST")
            com.example.domain.FreeToSpendRules.compute(
                myUid = uid,
                accounts = inputs[0] as List<Account>,
                txs = inputs[1] as List<Transaction>,
                reminders = inputs[2] as List<Reminder>,
                reserves = inputs[3] as List<Reserve>,
                salaryDay = day,
                now = System.currentTimeMillis()
            )
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Mine, whichever dashboard tab is open. */
    val freeToSpend = freeToSpendFor(currentUserUid)

    /** For the dashboard's selected tab: mine, my partner's, or the joint accounts alone. */
    val viewFreeToSpend = freeToSpendFor(viewOwnerId)

    /** New reserves start counting monthly top-ups from next month; the opening amount is this month's. */
    fun saveReserve(reserve: Reserve) {
        viewModelScope.launch {
            val isNew = reserves.value.none { it.id == reserve.id }
            val toSave = if (isNew) reserve.copy(
                createdByUserId = FirebaseService.getCurrentUserId(),
                lastContributionMonth = com.example.domain.FreeToSpendRules.monthKey(System.currentTimeMillis())
            ) else reserve
            FirebaseService.saveReserve(toSave)
        }
    }

    /** Money taken out of a reserve once it is spent on what it was for. */
    fun useReserve(reserve: Reserve, amount: Double) {
        viewModelScope.launch {
            FirebaseService.saveReserve(reserve.copy(amount = (reserve.amount - amount).coerceAtLeast(0.0)))
        }
    }

    fun deleteReserve(id: String) {
        viewModelScope.launch { FirebaseService.deleteReserve(id) }
    }

    // Auto-category helpers
    fun getSuggestedCategoryForVendor(vendorName: String): String? {
        if (vendorName.isEmpty()) return null
        return transactions.value.firstOrNull { 
            val cleanNarration = it.narration
                .replace("Spent at ", "")
                .substringAfter(" - ")
                .trim()
            cleanNarration.equals(vendorName.trim(), ignoreCase = true) ||
            it.narration.replace("Spent at ", "").trim().equals(vendorName.trim(), ignoreCase = true) 
        }?.category
    }

    // Interactive Actions
    fun setFilter(filter: ViewFilter) {
        _currentFilter.value = filter
    }

    fun setScopeFilter(scope: TransactionScope?) {
        _scopeFilter.value = scope
    }

    // CRITICAL RECONCILIATION pipeline for missed SMS due to battery-saver or background termination
    // The returned job ends when the scan does (the dashboard's pull to refresh waits on it).
    fun runSmsInboxReconciliation(context: Context): kotlinx.coroutines.Job {
        return viewModelScope.launch(Dispatchers.IO) {
            var lastTimestamp = SmsProcessor.getLastProcessedTimestamp(context)
            if (lastTimestamp == 0L) {
                // First scan: start a day back, not from the whole history of the phone.
                lastTimestamp = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
                SmsProcessor.updateLastProcessedTimestamp(context, lastTimestamp)
            }

            Log.d(TAG, "Reconciliation: Checking SMS ContentProvider starting from $lastTimestamp...")
            
            val uri = Uri.parse("content://sms/inbox")
            val selection = "date > ?"
            val selectionArgs = arrayOf(lastTimestamp.toString())
            val projection = arrayOf("address", "body", "date")
            val sortOrder = "date ASC"

            try {
                val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
                cursor?.use { c ->
                    val addressCol = c.getColumnIndexOrThrow("address")
                    val bodyCol = c.getColumnIndexOrThrow("body")
                    val dateCol = c.getColumnIndexOrThrow("date")

                    var processedCount = 0
                    while (c.moveToNext()) {
                        val sender = c.getString(addressCol) ?: ""
                        val body = c.getString(bodyCol) ?: ""
                        val date = c.getLong(dateCol)

                        // One message that can't be saved must not hold up the ones after it.
                        val handled = try {
                            SmsProcessor.processSms(context, sender, body, date)
                        } catch (e: Exception) {
                            Log.w(TAG, "Reconciliation: skipped one SMS: ${e.message}")
                            true
                        }
                        if (!handled) break // not signed in yet; resume from here next time
                        // Move the cursor past every scanned SMS, bank or not, so the next scan
                        // doesn't start over from the last bank message.
                        SmsProcessor.updateLastProcessedTimestamp(context, date)
                        processedCount++
                    }
                    Log.d(TAG, "Reconciliation completed: processed $processedCount missed background transactions.")
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Reconciliation canceled: Missing READ_SMS permission.")
            } catch (e: Exception) {
                Log.e(TAG, "Reconciliation error: ${e.message}", e)
            }
        }
    }

    // Mutator Methods
    fun addManualTransaction(
        amount: Double,
        type: TransactionType,
        category: String,
        narration: String,
        accountId: String,
        targetAccountId: String?,
        paidBy: String,
        scope: TransactionScope,
        dateMillis: Long,
        tag: String = ""
    ) {
        viewModelScope.launch {
            try {
                // Handle Interest Calculations dynamically if target option is a loan and custom entry relates to EMI
                val rawAccounts = accounts.value
                val currentAccount = rawAccounts.find { it.id == accountId }
                val potentialLoanAcc = targetAccountId?.let { id -> rawAccounts.find { it.id == id && it.type == AccountType.LOAN } }
                
                val ownerUid = currentAccount?.ownerUserId?.takeIf { it.isNotEmpty() } ?: currentUserUid.value ?: "user_default"
                // "Paid for" is saved as a user id, so it reads the same on both phones.
                val paidFor = PayerRules.paidForId(paidBy, currentUserUid.value, spouseUserUid.value)
                val ownerName = currentAccount?.ownerFirstName?.takeIf { it.isNotEmpty() } ?: currentUserFirstName.value ?: "User"

                if (type == TransactionType.TRANSFER && potentialLoanAcc != null) {
                    // Manual transfer mapping representing EMI payment. Dynamically split interest component!
                    val loanAcc = potentialLoanAcc
                    val (interestComponent, principalComponent) = LedgerRules.emiSplit(
                        amount = amount,
                        outstanding = Math.abs(loanAcc.balance),
                        annualRate = loanAcc.annualInterestRate,
                        interestAlreadyCharged = LedgerRules.loanPaidInMonth(loanAcc.id, transactions.value, dateMillis)
                    )
                    // The loan balance comes from the ledger: the principal transfer below reduces it.
                    // Track principal transfer transaction
                    val principalTx = Transaction(
                        id = UUID.randomUUID().toString(),
                        amount = principalComponent,
                        type = TransactionType.TRANSFER,
                        category = "Transfer",
                        timestamp = dateMillis,
                        accountId = accountId,
                        targetAccountId = loanAcc.id,
                        paidByUserId = paidFor,
                        ownerUserId = ownerUid,
                        ownerFirstName = ownerName,
                        scope = scope,
                        narration = "Manual EMI Principal: $narration",
                        status = TransactionStatus.CONFIRMED,
                        isLoanEmi = true,
                        principalComponent = principalComponent,
                        tag = tag.trim()
                    )
                    if (principalComponent > 0.0) FirebaseService.addTransaction(principalTx)

                    // Track interest expense transaction
                    val interestTx = Transaction(
                        id = UUID.randomUUID().toString(),
                        amount = interestComponent,
                        type = TransactionType.EXPENSE,
                        category = LedgerRules.LOAN_INTEREST_CATEGORY,
                        timestamp = dateMillis,
                        accountId = accountId,
                        paidByUserId = paidFor,
                        ownerUserId = ownerUid,
                        ownerFirstName = ownerName,
                        scope = scope,
                        narration = "Manual EMI Interest: $narration",
                        status = TransactionStatus.CONFIRMED,
                        isLoanEmi = true,
                        interestComponent = interestComponent,
                        tag = tag.trim()
                    )
                    if (interestComponent > 0.0) FirebaseService.addTransaction(interestTx)
                    return@launch
                }

                // Standard Transaction Entry
                val tx = Transaction(
                    id = UUID.randomUUID().toString(),
                    amount = amount,
                    type = type,
                    category = category,
                    timestamp = dateMillis,
                    accountId = accountId,
                    targetAccountId = targetAccountId,
                    paidByUserId = paidFor,
                    ownerUserId = ownerUid,
                    ownerFirstName = ownerName,
                    scope = scope,
                    narration = narration,
                    status = TransactionStatus.CONFIRMED,
                    tag = tag.trim()
                )
                FirebaseService.addTransaction(tx)
            } catch (e: Exception) {
                Log.e(TAG, "addManualTransaction error: ${e.message}", e)
                triggerToast(e.message ?: "Failed to write transaction")
            }
        }
    }

    /** Runs a save and tells the user when it is refused (for example inside a reconciled period). */
    private fun saving(what: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (e: Exception) {
                Log.e(TAG, "$what failed: ${e.message}")
                val locked = e.message?.contains("closed reconciliation", ignoreCase = true) == true
                triggerToast(
                    if (locked) "Not saved: this date is before the account's last reconcile. Reconciled periods are locked."
                    else "Not saved: ${e.message ?: "something went wrong"}"
                )
            }
        }
    }

    fun confirmPendingReview(transactionId: String, correctedCategory: String) = saving("Confirm") {
        val oldTx = transactions.value.find { it.id == transactionId } ?: return@saving
        FirebaseService.updateTransaction(
            oldTx.copy(category = correctedCategory, status = TransactionStatus.CONFIRMED, isProbablyDuplicate = false)
        )
    }

    fun ignoreTransaction(transactionId: String) = saving("Delete") { FirebaseService.deleteTransaction(transactionId) }

    fun updateTransaction(transaction: Transaction) = saving("Update") {
        // "Paid for" is saved as a user id, so it reads the same on both phones.
        val paidFor = PayerRules.paidForId(transaction.paidByUserId, currentUserUid.value, spouseUserUid.value)
        FirebaseService.updateTransaction(if (paidFor == transaction.paidByUserId) transaction else transaction.copy(paidByUserId = paidFor))
    }

    fun deleteTransaction(transactionId: String) = saving("Delete") { FirebaseService.deleteTransaction(transactionId) }

    fun addAccount(
        context: Context,
        name: String,
        type: AccountType,
        balance: Double, // Negative for loans, positive for assets
        owner: String,
        openingBalance: Double,
        asOfDate: Long,
        annualInterestRate: Double = 0.0,
        remainingMonths: Int = 0,
        monthlyEmi: Double = 0.0,
        accountClass: String? = null,
        mainCategory: String? = null,
        subCategory: String? = null,
        lastFourDigits: String = "",
        iconName: String = "",
        billingCycleDay: Int = 0,
        paymentDueDay: Int = 0,
        netWorthGroup: String = ""
    ) {
        viewModelScope.launch {
            val finalClass = accountClass ?: when (type) {
                AccountType.SAVINGS_ACCOUNT, AccountType.FIXED_DEPOSIT, AccountType.MUTUAL_FUND -> "ASSET"
                AccountType.CREDIT_CARD, AccountType.LOAN -> "LIABILITY"
            }
            val finalMain = mainCategory ?: when (type) {
                AccountType.SAVINGS_ACCOUNT -> "Bank Accounts"
                AccountType.CREDIT_CARD -> "Credit Cards"
                AccountType.FIXED_DEPOSIT -> "Fixed Deposits"
                AccountType.MUTUAL_FUND -> "Mutual Funds"
                AccountType.LOAN -> "Loans"
            }
            val finalSub = subCategory ?: name
 
            val finalBalance = if (type == AccountType.LOAN && balance > 0) -balance else balance
            val finalOpening = if (type == AccountType.LOAN && openingBalance > 0) -openingBalance else openingBalance
            
            val mappedOwnerUserId = when (owner) {
                "User 1" -> currentUserUid.value ?: "user_1"
                "User 2" -> spouseUserUid.value ?: "user_2"
                "Combined", "Shared", "SHARED" -> "SHARED"
                else -> owner
            }
            
            val ownerName = when (mappedOwnerUserId) {
                currentUserUid.value ?: "user_1" -> currentUserFirstName.value ?: "User 1"
                spouseUserUid.value ?: "user_2" -> spouseUserFirstName.value ?: "Partner"
                "SHARED" -> "Shared"
                else -> owner
            }
 
            val acc = Account(
                accountId = UUID.randomUUID().toString(),
                accountName = name,
                accountType = finalClass,
                balance = finalBalance,
                ownerUserId = mappedOwnerUserId,
                ownerFirstName = ownerName,
                openingBalance = finalOpening,
                asOfDate = asOfDate,
                annualInterestRate = annualInterestRate,
                remainingMonths = remainingMonths,
                monthlyEmi = monthlyEmi,
                lastFourDigits = lastFourDigits,
                iconName = iconName,
                billingCycleDay = billingCycleDay,
                paymentDueDay = paymentDueDay,
                // Persist the chosen grouping; without it every liability showed up as a credit card.
                customAccountClass = finalClass,
                customMainCategory = finalMain,
                customSubCategory = finalSub,
                netWorthGroup = netWorthGroup
            )
            // Saving the account also starts one background scan of older bank SMS for it.
            FirebaseService.addAccount(acc)
        }
    }

    /**
     * Saves one of the user's own Net worth groups: [memberIds] end up in [name], and accounts that
     * were in [oldName] (when renaming or editing) but are no longer picked leave it.
     * A blank [name] removes the group [oldName]; its accounts stay.
     */
    fun saveNetWorthGroup(oldName: String?, name: String, memberIds: Set<String>) {
        val changes = NetWorthRules.groupChanges(accounts.value, oldName, name, memberIds)
        FirebaseService.setNetWorthGroups(changes)
    }

    /** Items up to [closingDate] on this account that are still waiting for review. */
    fun pendingBefore(accountId: String, closingDate: Long, exactTime: Boolean = false): List<Transaction> {
        val end = if (exactTime) closingDate else LedgerRules.endOfDay(closingDate)
        return transactions.value.filter {
            it.status == TransactionStatus.PENDING_REVIEW && !it.isIgnored &&
                (it.accountId == accountId || it.targetAccountId == accountId) && it.timestamp <= end
        }
    }

    /**
     * [exactTime]: [statementBalance] is the balance at [closingDate] itself (from a bank SMS),
     * not at the end of that day.
     */
    fun reconcileAccount(accountId: String, closingDate: Long, statementBalance: Double, exactTime: Boolean = false) {
        viewModelScope.launch {
            val oldAcc = accounts.value.find { it.id == accountId } ?: return@launch
            var adjustmentId = ""
            try {
                // 1. Settle what is still waiting for review up to the closing date, before the
                // period locks: items already counted in the balance are confirmed as they are,
                // suspected duplicates (not counted) are set aside.
                pendingBefore(accountId, closingDate, exactTime).forEach { tx ->
                    FirebaseService.updateTransaction(
                        if (tx.isProbablyDuplicate) tx.copy(isIgnored = true) else tx.copy(status = TransactionStatus.CONFIRMED)
                    )
                }
                // 2. Balances come from the ledger, so a difference from the statement is booked
                // as an adjustment entry on the closing date.
                adjustmentId = LedgerRules.reconciliationAdjustment(oldAcc, transactions.value, closingDate, statementBalance, exactTime)
                    ?.also { FirebaseService.addTransaction(it) }?.transactionId.orEmpty()
            } catch (e: Exception) {
                Log.e(TAG, "Could not reconcile: ${e.message}", e)
                triggerToast("Could not reconcile: ${e.message}")
                return@launch
            }
            // 3. Lock the period.
            // Saved onto the latest copy, so a bank SMS balance that came in meanwhile is kept.
            val latest = accounts.value.find { it.id == accountId } ?: oldAcc
            FirebaseService.updateAccount(
                latest.copy(
                    lastReconciledDate = closingDate,
                    reconciledBalance = statementBalance,
                    reconcileAdjustmentId = adjustmentId,
                    previousReconciledDate = oldAcc.lastReconciledDate,
                    previousReconciledBalance = oldAcc.reconciledBalance
                )
            )
        }
    }

    /**
     * Takes back the last reconcile: removes the adjustment it added and puts the lock back where
     * it was before (or removes it). Items it confirmed from review stay confirmed.
     */
    fun undoReconcile(accountId: String) {
        viewModelScope.launch {
            val acc = accounts.value.find { it.id == accountId } ?: return@launch
            if (acc.lastReconciledDate == null) return@launch
            try {
                if (acc.reconcileAdjustmentId.isNotBlank()) FirebaseService.deleteTransaction(acc.reconcileAdjustmentId)
            } catch (e: Exception) {
                Log.e(TAG, "Could not undo the reconcile: ${e.message}", e)
                triggerToast("Could not undo the reconcile: ${e.message}")
                return@launch
            }
            FirebaseService.updateAccount(
                acc.copy(
                    lastReconciledDate = acc.previousReconciledDate,
                    reconciledBalance = acc.previousReconciledBalance,
                    reconcileAdjustmentId = "",
                    previousReconciledDate = null,
                    previousReconciledBalance = null
                )
            )
            triggerToast("Reconcile undone for ${acc.name}")
        }
    }

    fun addWhitelistedSender(senderId: String, name: String) {
        viewModelScope.launch {
            FirebaseService.addWhitelistedSender(WhitelistedSender(senderId = senderId.trim(), name = name.trim()))
        }
    }

    fun deleteWhitelistedSender(id: String) {
        viewModelScope.launch {
            FirebaseService.deleteWhitelistedSender(id)
        }
    }

    fun addPrivacyKeyword(keyword: String) {
        viewModelScope.launch {
            FirebaseService.addPrivacyKeyword(PrivacyKeyword(keyword = keyword.trim()))
        }
    }

    fun deletePrivacyKeyword(id: String) {
        viewModelScope.launch {
            FirebaseService.deletePrivacyKeyword(id)
        }
    }

    fun toggleReminderPaid(reminderId: String) {
        viewModelScope.launch {
            Log.d(TAG, "toggleReminderPaid clicked for reminderId: $reminderId")
            val currentReminders = reminders.value
            Log.d(TAG, "Current reminders in state: ${currentReminders.map { "${it.id}: paid=${it.isPaid}" }}")
            val item = currentReminders.find { it.id == reminderId }
            if (item == null) {
                Log.e(TAG, "Could NOT find reminder with ID $reminderId in the list!")
                return@launch
            }
            val nextPaidState = !item.isPaid
            Log.d(TAG, "Found reminder: ${item.title}, toggling isPaid from ${item.isPaid} to $nextPaidState")
            FirebaseService.updateReminder(item.copy(isPaid = nextPaidState))
        }
    }
    
    fun addNewReminder(title: String, amount: Double, type: String, dueDate: Long, isRecurring: Boolean, accountId: String? = null) {
        viewModelScope.launch {
            FirebaseService.addReminder(
                Reminder(
                    title = title,
                    amount = amount,
                    type = type,
                    dueDate = dueDate,
                    isRecurring = isRecurring,
                    isPaid = false,
                    accountId = accountId,
                    dueDay = com.example.domain.ReminderRules.dayOfMonth(dueDate),
                    // A card reminder belongs to the card's owner; anything else to whoever made it.
                    ownerUserId = accounts.value.find { it.accountId == accountId }?.ownerUserId?.takeIf { it.isNotBlank() }
                        ?: FirebaseService.getCurrentUserId()
                )
            )
        }
    }

    fun updateReminder(reminder: Reminder) {
        viewModelScope.launch {
            // A new due date picked by the user becomes the day the series repeats on.
            val before = reminders.value.find { it.id == reminder.id }
            val updated = if (before == null || before.dueDate != reminder.dueDate) {
                reminder.copy(dueDay = com.example.domain.ReminderRules.dayOfMonth(reminder.dueDate))
            } else reminder
            FirebaseService.updateReminder(updated)
        }
    }

    fun deleteReminder(reminderId: String) {
        viewModelScope.launch {
            FirebaseService.deleteReminder(reminderId)
        }
    }

    /** What the card owed at its latest statement date. */
    fun getCardBillingCycleBalance(account: Account): Double {
        // Cards without days set are shown with the screen's defaults (statement 15th, due 5th).
        val withDays = account.copy(
            billingCycleDay = account.billingCycleDay.takeIf { it in 1..31 } ?: 15,
            paymentDueDay = account.paymentDueDay.takeIf { it in 1..31 } ?: 5
        )
        val cycle = com.example.domain.ReminderRules.latestStatement(withDays, System.currentTimeMillis())
        return com.example.domain.ReminderRules.statementBalance(account, transactions.value, cycle.statementDate)
    }

    // --- BUDGET ACTIONS ---
    fun addBudget(budget: Budget) {
        viewModelScope.launch {
            FirebaseService.addBudget(budget)
        }
    }

    fun updateBudget(budget: Budget) {
        viewModelScope.launch {
            FirebaseService.updateBudget(budget)
        }
    }

    fun deleteBudget(budgetId: String) {
        viewModelScope.launch {
            FirebaseService.deleteBudget(budgetId)
        }
    }

    // --- CATEGORY ACTIONS ---
    fun addCategory(category: Account) {
        viewModelScope.launch {
            FirebaseService.addCategory(category)
        }
    }

    fun updateCategory(category: Account) {
        viewModelScope.launch {
            FirebaseService.updateCategory(category)
        }
    }

    fun deleteCategory(categoryId: String) {
        viewModelScope.launch {
            FirebaseService.deleteCategory(categoryId)
        }
    }

    // --- MERCHANT RULE ACTIONS ---
    fun addMerchantRule(rule: MerchantRule) {
        viewModelScope.launch {
            try {
                FirebaseService.addMerchantRule(rule)
                
                // Scan all pending transactions to see if the new rule qualifies to override their category
                val keyword = rule.keyword.trim().uppercase()
                if (keyword.isNotEmpty()) {
                    val pendingToUpdate = transactions.value.filter { tx ->
                        tx.status == TransactionStatus.PENDING_REVIEW && !tx.isIgnored && (
                            tx.narration.contains(keyword, ignoreCase = true) ||
                            (tx.originalSmsText?.contains(keyword, ignoreCase = true) == true)
                        )
                    }
                    if (pendingToUpdate.isNotEmpty()) {
                        pendingToUpdate.forEach { tx ->
                            val updatedTx = tx.copy(category = rule.assignedCategory)
                            FirebaseService.updateTransaction(updatedTx)
                        }
                        triggerToast("Applied smart override rule to ${pendingToUpdate.size} pending transaction(s)!")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying smart override rule: ${e.message}", e)
            }
        }
    }

    fun addMerchantRule(keyword: String, category: String, updateExistingTransactions: Boolean = false) {
        viewModelScope.launch {
            val cleanKeyword = keyword.trim().uppercase()
            val cleanCategory = category.trim()
            if (cleanKeyword.isBlank() || cleanCategory.isBlank()) return@launch

            val existing = merchantRules.value.find { it.keyword.equals(cleanKeyword, ignoreCase = true) }
            val rule = existing?.copy(assignedCategory = cleanCategory) ?: MerchantRule(
                ruleId = UUID.randomUUID().toString(),
                keyword = cleanKeyword,
                assignedCategory = cleanCategory
            )
            FirebaseService.addMerchantRule(rule)

            if (updateExistingTransactions) {
                val matching = transactions.value.filter {
                    !it.isIgnored &&
                    it.type != TransactionType.TRANSFER &&
                    it.category != cleanCategory &&
                    (it.narration.contains(cleanKeyword, ignoreCase = true) ||
                     it.payeeName.contains(cleanKeyword, ignoreCase = true) ||
                     (it.originalSmsText?.contains(cleanKeyword, ignoreCase = true) == true))
                }
                matching.forEach { tx ->
                    try {
                        val updated = tx.copy(
                            category = cleanCategory,
                            debitAccountId = if (tx.type == TransactionType.EXPENSE) cleanCategory else tx.debitAccountId,
                            creditAccountId = if (tx.type == TransactionType.INCOME) cleanCategory else tx.creditAccountId,
                            isSmartRule = true
                        )
                        FirebaseService.updateTransaction(updated)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error backfilling rule to tx: ${e.message}")
                    }
                }
                if (matching.isNotEmpty()) {
                    triggerToast("Smart rule saved for '$cleanKeyword' -> '$cleanCategory'! Updated ${matching.size} past transactions.")
                } else {
                    triggerToast("Smart rule saved for '$cleanKeyword' -> '$cleanCategory'.")
                }
            } else {
                triggerToast("Smart rule saved for '$cleanKeyword' -> '$cleanCategory'.")
            }
        }
    }

    fun deleteMerchantRule(ruleId: String) {
        viewModelScope.launch {
            FirebaseService.deleteMerchantRule(ruleId)
        }
    }

    private val _testSmsResult = MutableStateFlow<TestSmsResult?>(null)
    val testSmsResult: StateFlow<TestSmsResult?> = _testSmsResult.asStateFlow()

    private val _isTestingSms = MutableStateFlow(false)
    val isTestingSms: StateFlow<Boolean> = _isTestingSms.asStateFlow()

    fun testParseSms(smsBody: String) {
        viewModelScope.launch {
            _isTestingSms.value = true
            try {
                val rules = merchantRules.value
                val cats = categories.value
                val accs = accounts.value

                val parsed = GeminiSmsParser.parseSms(smsBody, rules, cats, accs)
                if (parsed == null) {
                    _testSmsResult.value = TestSmsResult(
                        success = false,
                        error = "Gemini/Regex classified this message to be ignored (ignore = true) or returned empty output.",
                        parsed = null,
                        accountingEntry = null
                    )
                } else {
                    var targetAccount: Account? = null
                    if (accs.isNotEmpty()) {
                        val matchedByDigits = accs.find { acc ->
                            if (acc.lastFourDigits.isBlank()) false
                            else {
                                val tokens = acc.lastFourDigits.split(",", "/", ";", " ", "|").map { it.trim() }.filter { it.length >= 3 }
                                tokens.any { token -> com.example.domain.SmsRules.namesDigits(smsBody, token) }
                            }
                        }
                        if (matchedByDigits != null) {
                            targetAccount = matchedByDigits
                        }
                    }
                    if (targetAccount == null && !parsed.assignedAccount.isNullOrEmpty()) {
                        val cleanAssigned = parsed.assignedAccount.lowercase().trim()
                        targetAccount = accs.find { acc ->
                            val tokens = acc.lastFourDigits.split(",", "/", ";", " ", "|").map { it.trim() }.filter { it.length >= 3 }
                            val fullLabel = "${acc.name} (xx${acc.lastFourDigits})".lowercase().trim()
                            cleanAssigned.contains(acc.name.lowercase().trim()) ||
                            cleanAssigned.contains(fullLabel) ||
                            acc.name.lowercase().trim().contains(cleanAssigned) ||
                            tokens.any { token -> cleanAssigned.contains(token) }
                        }
                    }
                    if (targetAccount == null) {
                        targetAccount = accs.find { it.type == AccountType.SAVINGS_ACCOUNT } ?: accs.firstOrNull()
                    }

                    if (targetAccount == null) {
                        _testSmsResult.value = TestSmsResult(
                            success = false,
                            error = "No matching account found to record the transaction.",
                            parsed = parsed,
                            accountingEntry = null
                        )
                    } else {
                        val isUncertain = parsed.suggestedCategory.equals("Others", ignoreCase = true) || 
                                          parsed.suggestedCategory.isEmpty() || 
                                          parsed.suggestedCategory.equals("Unknown", ignoreCase = true)
                        val matchedHistory = transactions.value.firstOrNull { 
                            val cleanNarration = it.narration
                                .replace("Spent at ", "")
                                .substringAfter(" - ")
                                .trim()
                            cleanNarration.equals(parsed.vendor.trim(), ignoreCase = true) ||
                            it.narration.replace("Spent at ", "").trim().equals(parsed.vendor.trim(), ignoreCase = true)
                        }
                        val isBrandNewVendor = matchedHistory == null
                        val isOldSms = true
                        val status = if (isUncertain || isBrandNewVendor || isOldSms) {
                            TransactionStatus.PENDING_REVIEW
                        } else {
                            TransactionStatus.CONFIRMED
                        }

                        val cleanType = if (parsed.transactionType == "CREDIT") TransactionType.INCOME else TransactionType.EXPENSE
                        val debitId = if (cleanType == TransactionType.INCOME) targetAccount.accountId else parsed.suggestedCategory
                        val creditId = if (cleanType == TransactionType.INCOME) parsed.suggestedCategory else targetAccount.accountId

                        val cleanVendor = parsed.vendor.replace("Spent at ", "").trim()
                        val finalNarration = if (cleanVendor.isNotBlank()) "${parsed.suggestedCategory} - $cleanVendor" else parsed.suggestedCategory

                        val accounting = AccountingEntryDisplay(
                            debitAccountId = debitId,
                            creditAccountId = creditId,
                            entryType = cleanType.name,
                            accountName = targetAccount.name,
                            status = status.name,
                            narration = finalNarration,
                            isProbablyDuplicate = false
                        )

                        _testSmsResult.value = TestSmsResult(
                            success = true,
                            error = null,
                            parsed = parsed,
                            accountingEntry = accounting
                        )
                    }
                }
            } catch (e: Exception) {
                _testSmsResult.value = TestSmsResult(
                    success = false,
                    error = e.message ?: "Unknown error occurred",
                    parsed = null,
                    accountingEntry = null
                )
            } finally {
                _isTestingSms.value = false
            }
        }
    }

    fun clearTestResult() {
        _testSmsResult.value = null
    }
}

data class TestSmsResult(
    val success: Boolean,
    val error: String?,
    val parsed: ParsedTransactionResponse?,
    val accountingEntry: AccountingEntryDisplay?
)

data class AccountingEntryDisplay(
    val debitAccountId: String,
    val creditAccountId: String,
    val entryType: String,
    val accountName: String,
    val status: String,
    val narration: String,
    val isProbablyDuplicate: Boolean
)

data class NetFinancials(
    val liquidFunds: Double = 0.0,
    val savingsCashSum: Double = 0.0,
    val creditCardDues: Double = 0.0,
    val fixedDeposits: Double = 0.0,
    val mutualFunds: Double = 0.0,
    val totalInvestments: Double = 0.0,
    val loanOutstanding: Double = 0.0,
    val familyNetWorth: Double = 0.0
)
