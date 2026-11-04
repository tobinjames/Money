package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
        
    val manualMappings = FirebaseService.getManualMappings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val budgets = FirebaseService.getBudgets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val softBudgetWarnings = FirebaseService.softBudgetWarnings
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
    private val _currentFilter = MutableStateFlow<ViewFilter>(ViewFilter.Combined)
    val currentFilter: StateFlow<ViewFilter> = _currentFilter.asStateFlow()

    private val _scopeFilter = MutableStateFlow<TransactionScope?>(null) // null means both
    val scopeFilter: StateFlow<TransactionScope?> = _scopeFilter.asStateFlow()

    // Combined calculations (Stateflows derived from raw data and views filters)
    val filteredAccounts = combine(accounts, currentFilter, currentUserUid, spouseUserUid) { list, filter, myUid, herUid ->
        val user1Uid = myUid
        val user2Uid = herUid
        val user1Name = currentUserFirstName.value
        val user2Name = spouseUserFirstName.value
        
        fun isUser1Strict(id: String?): Boolean {
            if (id.isNullOrBlank() || id.equals("SHARED", ignoreCase = true) || id.equals("USER_UNKNOWN", ignoreCase = true)) return false
            return (user1Uid != null && id == user1Uid) || id == "User 1" || (!user1Name.isNullOrBlank() && id.equals(user1Name, ignoreCase = true))
        }
        
        fun isUser2Strict(id: String?): Boolean {
            if (id.isNullOrBlank() || id.equals("SHARED", ignoreCase = true) || id.equals("USER_UNKNOWN", ignoreCase = true)) return false
            return (user2Uid != null && id == user2Uid) || id == "User 2" || (!user2Name.isNullOrBlank() && id.equals(user2Name, ignoreCase = true))
        }

        fun isSharedStrict(id: String?): Boolean {
            return id.isNullOrBlank() || id.equals("SHARED", ignoreCase = true) || id.equals("USER_UNKNOWN", ignoreCase = true)
        }

        when (filter) {
            ViewFilter.Combined -> list.filter { isSharedStrict(it.ownerUserId) }
            ViewFilter.User1 -> list.filter { isUser1Strict(it.ownerUserId) }
            ViewFilter.User2 -> list.filter { isUser2Strict(it.ownerUserId) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredTransactions = combine(transactions, accounts, currentFilter, scopeFilter, currentUserUid) { txList, accList, ownerFilter, scope, myUid ->
        val user1Uid = myUid
        val user2Uid = spouseUserUid.value
        val user1Name = currentUserFirstName.value
        val user2Name = spouseUserFirstName.value
        
        fun isUser1Strict(id: String?): Boolean {
            if (id.isNullOrBlank() || id.equals("SHARED", ignoreCase = true) || id.equals("BOTH", ignoreCase = true) || id.equals("USER_UNKNOWN", ignoreCase = true)) return false
            return (user1Uid != null && id == user1Uid) || id == "User 1" || (!user1Name.isNullOrBlank() && id.equals(user1Name, ignoreCase = true))
        }
        
        fun isUser2Strict(id: String?): Boolean {
            if (id.isNullOrBlank() || id.equals("SHARED", ignoreCase = true) || id.equals("BOTH", ignoreCase = true) || id.equals("USER_UNKNOWN", ignoreCase = true)) return false
            return (user2Uid != null && id == user2Uid) || id == "User 2" || (!user2Name.isNullOrBlank() && id.equals(user2Name, ignoreCase = true))
        }

        fun isSharedStrict(id: String?): Boolean {
            return id.isNullOrBlank() || id.equals("SHARED", ignoreCase = true) || id.equals("BOTH", ignoreCase = true) || id.equals("USER_UNKNOWN", ignoreCase = true)
        }

        fun txBelongsToUser1(tx: Transaction): Boolean {
            val srcAcc = accList.find { it.id == tx.accountId }
            val dstAcc = if (!tx.targetAccountId.isNullOrBlank()) accList.find { it.id == tx.targetAccountId } else null
            
            val accIsUser1 = srcAcc != null && isUser1Strict(srcAcc.ownerUserId)
            val dstIsUser1 = dstAcc != null && isUser1Strict(dstAcc.ownerUserId)
            val ownerIsUser1 = isUser1Strict(tx.ownerUserId) || (tx.ownerFirstName.isNotBlank() && user1Name != null && tx.ownerFirstName.equals(user1Name, ignoreCase = true))
            val paidByIsUser1 = isUser1Strict(tx.paidByUserId)
            
            return accIsUser1 || dstIsUser1 || ownerIsUser1 || paidByIsUser1
        }

        fun txBelongsToUser2(tx: Transaction): Boolean {
            val srcAcc = accList.find { it.id == tx.accountId }
            val dstAcc = if (!tx.targetAccountId.isNullOrBlank()) accList.find { it.id == tx.targetAccountId } else null
            
            val accIsUser2 = srcAcc != null && isUser2Strict(srcAcc.ownerUserId)
            val dstIsUser2 = dstAcc != null && isUser2Strict(dstAcc.ownerUserId)
            val ownerIsUser2 = isUser2Strict(tx.ownerUserId) || (tx.ownerFirstName.isNotBlank() && user2Name != null && tx.ownerFirstName.equals(user2Name, ignoreCase = true))
            val paidByIsUser2 = isUser2Strict(tx.paidByUserId)
            
            return accIsUser2 || dstIsUser2 || ownerIsUser2 || paidByIsUser2
        }

        fun txBelongsToShared(tx: Transaction): Boolean {
            val srcAcc = accList.find { it.id == tx.accountId }
            val dstAcc = if (!tx.targetAccountId.isNullOrBlank()) accList.find { it.id == tx.targetAccountId } else null
            
            val srcIsShared = srcAcc != null && isSharedStrict(srcAcc.ownerUserId)
            val dstIsShared = dstAcc != null && isSharedStrict(dstAcc.ownerUserId)
            val isExplicitlyShared = tx.paidByUserId.equals("BOTH", ignoreCase = true) || tx.paidByUserId.equals("SHARED", ignoreCase = true) || tx.ownerUserId.equals("SHARED", ignoreCase = true)
            val isUnassigned = !txBelongsToUser1(tx) && !txBelongsToUser2(tx)

            return srcIsShared || dstIsShared || isExplicitlyShared || isUnassigned
        }

        txList.filter { tx ->
            val matchesOwner = when (ownerFilter) {
                ViewFilter.Combined -> txBelongsToShared(tx)
                ViewFilter.User1 -> txBelongsToUser1(tx)
                ViewFilter.User2 -> txBelongsToUser2(tx)
            }
            val matchesScope = scope == null || tx.scope == scope
            matchesOwner && matchesScope
        }.sortedByDescending { it.timestamp }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NetFinancials())

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
    fun runSmsInboxReconciliation(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val lastTimestamp = SmsProcessor.getLastProcessedTimestamp(context)
            if (lastTimestamp == 0L) {
                // Initialize default to 1 day ago to prevent scanning extensive device histories on fresh install
                SmsProcessor.updateLastProcessedTimestamp(context, System.currentTimeMillis() - (24 * 60 * 60 * 1000))
                Log.d(TAG, "Reconciliation: SharedPreferences timestamp initialized to 1 day ago.")
                return@launch
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

                        SmsProcessor.processSms(context, sender, body, date)
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
                val potentialLoanAcc = targetAccountId?.let { id -> rawAccounts.find { id == id && it.type == AccountType.LOAN } }
                
                val ownerUid = currentAccount?.ownerUserId?.takeIf { it.isNotEmpty() } ?: currentUserUid.value ?: "user_default"
                val ownerName = currentAccount?.ownerFirstName?.takeIf { it.isNotEmpty() } ?: currentUserFirstName.value ?: "User"

                if (type == TransactionType.TRANSFER && potentialLoanAcc != null) {
                    // Manual transfer mapping representing EMI payment. Dynamically split interest component!
                    val loanAcc = potentialLoanAcc
                    val outstanding = Math.abs(loanAcc.balance)
                    val annualRate = loanAcc.annualInterestRate
                    
                    val interestComponent = (outstanding * (annualRate / 100.0)) / 12.0
                    val principalComponent = (amount - interestComponent).coerceAtLeast(0.0)

                    // Adjust balances on Loan: reduce liability by principal
                    val updatedLoanBalance = loanAcc.balance + principalComponent
                    FirebaseService.updateAccount(loanAcc.copy(balance = updatedLoanBalance))

                    // Track principal transfer transaction
                    val principalTx = Transaction(
                        id = UUID.randomUUID().toString(),
                        amount = principalComponent,
                        type = TransactionType.TRANSFER,
                        category = "Transfer",
                        timestamp = dateMillis,
                        accountId = accountId,
                        targetAccountId = loanAcc.id,
                        paidByUserId = paidBy,
                        ownerUserId = ownerUid,
                        ownerFirstName = ownerName,
                        scope = scope,
                        narration = "Manual EMI Principal: $narration",
                        status = TransactionStatus.CONFIRMED,
                        isLoanEmi = true,
                        principalComponent = principalComponent,
                        tag = tag.trim()
                    )
                    FirebaseService.addTransaction(principalTx)

                    // Track interest expense transaction
                    val interestTx = Transaction(
                        id = UUID.randomUUID().toString(),
                        amount = interestComponent,
                        type = TransactionType.EXPENSE,
                        category = "Finance: Loan Interest",
                        timestamp = dateMillis,
                        accountId = accountId,
                        paidByUserId = paidBy,
                        ownerUserId = ownerUid,
                        ownerFirstName = ownerName,
                        scope = scope,
                        narration = "Manual EMI Interest: $narration",
                        status = TransactionStatus.CONFIRMED,
                        isLoanEmi = true,
                        interestComponent = interestComponent,
                        tag = tag.trim()
                    )
                    FirebaseService.addTransaction(interestTx)
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
                    paidByUserId = paidBy,
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

    fun exportTransactionsCsv(
        context: Context,
        txList: List<Transaction>,
        title: String = "NestWise_Transactions_Report"
    ) {
        try {
            val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val accountsMap = accounts.value.associate { it.id to it.name }
            
            val csvBuilder = StringBuilder()
            csvBuilder.append("Date,Type,Category,Payee/Narration,Amount,Account,Target Account,Scope,Paid By,Tag,Status\n")
            
            txList.sortedByDescending { it.timestamp }.forEach { tx ->
                val dateStr = simpleDateFormat.format(Date(tx.timestamp))
                val typeStr = tx.type.name
                val catStr = escapeCsv(tx.category)
                val narrationStr = escapeCsv(tx.narration.ifEmpty { tx.payeeName })
                val amountStr = String.format(Locale.US, "%.2f", tx.amount)
                val accountName = escapeCsv(accountsMap[tx.accountId] ?: tx.accountId)
                val targetAccountName = escapeCsv(tx.targetAccountId?.let { accountsMap[it] ?: it } ?: "")
                val scopeStr = tx.scope.name
                val paidByStr = escapeCsv(if (tx.ownerFirstName.isNotEmpty()) tx.ownerFirstName else tx.paidByUserId)
                val tagStr = escapeCsv(tx.tag)
                val statusStr = tx.status.name
                
                csvBuilder.append("$dateStr,$typeStr,$catStr,$narrationStr,$amountStr,$accountName,$targetAccountName,$scopeStr,$paidByStr,$tagStr,$statusStr\n")
            }
            
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "$title - Export")
                putExtra(Intent.EXTRA_TEXT, csvBuilder.toString())
            }
            val chooser = Intent.createChooser(sendIntent, "Export Transactions Report")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            triggerToast("Report generated successfully!")
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting CSV: ${e.message}", e)
            triggerToast("Error exporting report: ${e.message}")
        }
    }

    private fun escapeCsv(value: String): String {
        val clean = value.replace("\"", "\"\"")
        return if (clean.contains(",") || clean.contains("\n") || clean.contains("\"")) {
            "\"$clean\""
        } else {
            clean
        }
    }

    fun confirmPendingReview(transactionId: String, correctedCategory: String) {
        viewModelScope.launch {
            val oldTx = transactions.value.find { it.id == transactionId } ?: return@launch
            val updated = oldTx.copy(
                category = correctedCategory,
                status = TransactionStatus.CONFIRMED
            )
            FirebaseService.updateTransaction(updated)
        }
    }

    fun ignoreTransaction(transactionId: String) {
        viewModelScope.launch {
            try {
                FirebaseService.deleteTransaction(transactionId)
            } catch (e: Exception) {
                Log.e(TAG, "ignoreTransaction error: ${e.message}", e)
            }
        }
    }

    fun updateTransaction(transaction: Transaction) {
        viewModelScope.launch {
            try {
                FirebaseService.updateTransaction(transaction)
            } catch (e: Exception) {
                Log.e("ExpenseViewModel", "Error updating transaction: ${e.message}")
            }
        }
    }

    fun deleteTransaction(transactionId: String) {
        viewModelScope.launch {
            try {
                FirebaseService.deleteTransaction(transactionId)
            } catch (e: Exception) {
                Log.e("ExpenseViewModel", "Error deleting transaction: ${e.message}")
            }
        }
    }

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
        paymentDueDay: Int = 0
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
                paymentDueDay = paymentDueDay
            )
            FirebaseService.addAccount(acc)
            
            // Trigger historical bank SMS scan
            backfillHistoricalSmsForAccount(context, acc)
        }
    }

    fun updateAccountLastFourDigits(accountId: String, lastFourDigits: String) {
        viewModelScope.launch {
            val oldAcc = accounts.value.find { it.id == accountId } ?: return@launch
            val updated = oldAcc.copy(lastFourDigits = lastFourDigits)
            FirebaseService.updateAccount(updated)
        }
    }

    private fun backfillHistoricalSmsForAccount(context: Context, account: Account) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting historical SMS backfill for account '${account.name}' (As of date: ${account.asOfDate})...")
            
            val uri = Uri.parse("content://sms/inbox")
            val selection = "date >= ?"
            val selectionArgs = arrayOf(account.asOfDate.toString())
            val projection = arrayOf("address", "body", "date")
            val sortOrder = "date ASC"

            val cleanNameWords = account.name.split(Regex("[\\s_.-]+"))
                .map { it.filter { c -> c.isLetterOrDigit() }.lowercase() }
                .filter { it.length >= 3 }

            if (cleanNameWords.isEmpty()) return@launch

            try {
                val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
                cursor?.use { c ->
                    val addressCol = c.getColumnIndexOrThrow("address")
                    val bodyCol = c.getColumnIndexOrThrow("body")
                    val dateCol = c.getColumnIndexOrThrow("date")

                    var matchCount = 0
                    while (c.moveToNext()) {
                        val sender = c.getString(addressCol) ?: ""
                        val body = c.getString(bodyCol) ?: ""
                        val date = c.getLong(dateCol)

                        val bodyLower = body.lowercase()
                        val senderLower = sender.lowercase()

                        val isKeywordMatch = cleanNameWords.any { word ->
                            bodyLower.contains(word) || senderLower.contains(word)
                        }

                        if (isKeywordMatch) {
                            Log.d(TAG, "Historical Scan Match: Processing message from $sender dated $date")
                            SmsProcessor.processSms(context, sender, body, date)
                            matchCount++
                        }
                    }
                    Log.d(TAG, "Historical scan completed: processed $matchCount relevant bank transactions.")
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Historical scan canceled: Missing READ_SMS permission.")
            } catch (e: Exception) {
                Log.e(TAG, "Historical scan error: ${e.message}", e)
            }
        }
    }

    fun reconcileAccount(accountId: String, closingDate: Long, statementBalance: Double) {
        viewModelScope.launch {
            val oldAcc = accounts.value.find { it.id == accountId } ?: return@launch
            val updated = oldAcc.copy(
                lastReconciledDate = closingDate,
                reconciledBalance = statementBalance,
                balance = statementBalance
            )
            FirebaseService.updateAccount(updated)
            
            // Auto-ignore all transactions pending review before locking date
            val pendingBeforeLock = transactions.value.filter {
                it.status == TransactionStatus.PENDING_REVIEW &&
                (it.accountId == accountId || it.targetAccountId == accountId) &&
                it.timestamp < closingDate &&
                !it.isIgnored
            }
            pendingBeforeLock.forEach { tx ->
                try {
                    FirebaseService.updateTransaction(tx.copy(isIgnored = true))
                } catch (e: Exception) {
                    Log.e("ExpenseViewModel", "Error auto-ignoring pending transaction: ${e.message}")
                }
            }
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

    fun addManualMapping(senderId: String, cardDigits: String, accountId: String) {
        viewModelScope.launch {
            FirebaseService.addManualMapping(
                ManualMapping(
                    senderId = senderId.trim().uppercase(),
                    cardDigits = cardDigits.trim(),
                    accountId = accountId
                )
            )
        }
    }

    fun deleteManualMapping(id: String) {
        viewModelScope.launch {
            FirebaseService.deleteManualMapping(id)
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
                    accountId = accountId
                )
            )
        }
    }

    fun updateReminder(reminder: Reminder) {
        viewModelScope.launch {
            FirebaseService.updateReminder(reminder)
        }
    }

    fun deleteReminder(reminderId: String) {
        viewModelScope.launch {
            FirebaseService.deleteReminder(reminderId)
        }
    }

    fun getCardBillingCycleBalance(account: Account): Double {
        val cycleDay = if (account.billingCycleDay > 0) account.billingCycleDay else 15
        val calNow = java.util.Calendar.getInstance()
        val currentDay = calNow.get(java.util.Calendar.DAY_OF_MONTH)
        
        val cycleCal = java.util.Calendar.getInstance().apply {
            if (currentDay < cycleDay) {
                add(java.util.Calendar.MONTH, -1)
            }
            val maxDay = getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
            val d = if (cycleDay > maxDay) maxDay else cycleDay
            set(java.util.Calendar.DAY_OF_MONTH, d)
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 59)
            set(java.util.Calendar.SECOND, 59)
            set(java.util.Calendar.MILLISECOND, 999)
        }
        val billingCycleTimeMs = cycleCal.timeInMillis
        
        val txs = transactions.value
        val nonIgnoredTx = txs.filter { !it.isIgnored && it.status == TransactionStatus.CONFIRMED }
        val debitSum = nonIgnoredTx.filter { it.debitAccountId == account.accountId && it.date <= billingCycleTimeMs }.sumOf { it.amount }
        val creditSum = nonIgnoredTx.filter { it.creditAccountId == account.accountId && it.date <= billingCycleTimeMs }.sumOf { it.amount }
        
        return account.openingBalance + creditSum - debitSum
    }

    fun runRemindersCheck(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            combine(reminders, accounts, transactions) { recs, accs, _ -> Pair(recs, accs) }
                .collect { (list, accs) ->
                    checkAndGenerateCreditCardReminders(list, accs)
                    checkAndGenerateNextRecurringReminders(list)
                    checkAndTriggerReminderNotifications(context, list, accs)
                }
        }
    }

    private suspend fun checkAndGenerateCreditCardReminders(remindersList: List<Reminder>, accountsList: List<Account>) {
        val ccAccounts = accountsList.filter { 
            (it.mainCategory.equals("Credit Cards", ignoreCase = true) || 
             it.customMainCategory.equals("Credit Cards", ignoreCase = true) || 
             it.type == AccountType.CREDIT_CARD || 
             it.accountType == "LIABILITY") && 
            it.billingCycleDay > 0 && it.paymentDueDay > 0 
        }
        if (ccAccounts.isEmpty()) return

        val txs = transactions.value

        val calendar = java.util.Calendar.getInstance()
        val currentYear = calendar.get(java.util.Calendar.YEAR)
        val currentMonth = calendar.get(java.util.Calendar.MONTH) // 0-11

        // Check current month's cycle and previous month's cycle
        val cyclesToCheck = listOf(
            // Current month billing cycle
            Pair(currentYear, currentMonth),
            // Previous month billing cycle
            if (currentMonth == 0) Pair(currentYear - 1, 11) else Pair(currentYear, currentMonth - 1)
        )

        for (account in ccAccounts) {
            for ((cycleYear, cycleMonth) in cyclesToCheck) {
                // Calculate billing cycle end date
                val billCal = java.util.Calendar.getInstance().apply {
                    clear()
                    set(java.util.Calendar.YEAR, cycleYear)
                    set(java.util.Calendar.MONTH, cycleMonth)
                    val maxDay = getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
                    val d = if (account.billingCycleDay > maxDay) maxDay else account.billingCycleDay
                    set(java.util.Calendar.DAY_OF_MONTH, d)
                    set(java.util.Calendar.HOUR_OF_DAY, 23)
                    set(java.util.Calendar.MINUTE, 59)
                    set(java.util.Calendar.SECOND, 59)
                    set(java.util.Calendar.MILLISECOND, 999)
                }
                val billingCycleTimeMs = billCal.timeInMillis

                // If billing cycle is in the future, we cannot calculate closing balance yet
                if (billingCycleTimeMs > System.currentTimeMillis()) continue

                // Calculate payment due date
                val dueCal = java.util.Calendar.getInstance().apply {
                    clear()
                    set(java.util.Calendar.YEAR, cycleYear)
                    set(java.util.Calendar.MONTH, cycleMonth)
                    if (account.paymentDueDay < account.billingCycleDay) {
                        add(java.util.Calendar.MONTH, 1)
                    }
                    val maxDay = getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
                    val d = if (account.paymentDueDay > maxDay) maxDay else account.paymentDueDay
                    set(java.util.Calendar.DAY_OF_MONTH, d)
                    set(java.util.Calendar.HOUR_OF_DAY, 18) // 6 PM
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                val dueTimeMs = dueCal.timeInMillis

                // Uniquely identify reminder for this credit card and billing cycle
                val uniqueId = "cc_rem_${account.accountId}_${cycleYear}_${cycleMonth}"

                // Check if a reminder already exists with this ID or matches this dueTimeMs and accountId
                val existing = remindersList.find { 
                    it.id == uniqueId || (it.accountId == account.accountId && it.dueDate == dueTimeMs) 
                }

                // Compute Closing Balance strictly up to billingCycleTimeMs
                val nonIgnoredTx = txs.filter { !it.isIgnored && it.status == TransactionStatus.CONFIRMED }
                val debitSum = nonIgnoredTx.filter { it.debitAccountId == account.accountId && it.date <= billingCycleTimeMs }.sumOf { it.amount }
                val creditSum = nonIgnoredTx.filter { it.creditAccountId == account.accountId && it.date <= billingCycleTimeMs }.sumOf { it.amount }
                
                val closingBalance = account.openingBalance + creditSum - debitSum

                if (existing == null) {
                    // Only create a reminder if there's a positive due balance
                    if (closingBalance > 0) {
                        val reminderTitle = "${account.name} Bill (${getMonthName(cycleMonth)} Cycle)"
                        val cardReminder = Reminder(
                            id = uniqueId,
                            title = reminderTitle,
                            amount = closingBalance,
                            type = "Credit Card",
                            isRecurring = false,
                            isPaid = false,
                            dueDate = dueTimeMs,
                            accountId = account.accountId
                        )
                        Log.d(TAG, "Automatically creating credit card reminder: $reminderTitle due on $dueTimeMs, amount $closingBalance")
                        FirebaseService.addReminder(cardReminder)
                    }
                } else if (!existing.isPaid) {
                    // Use the balance till the billing cycle end date to update credit card bill payable
                    if (closingBalance > 0 && Math.abs(existing.amount - closingBalance) > 0.01) {
                        val updatedReminder = existing.copy(
                            amount = closingBalance,
                            title = if (existing.title.isBlank()) "${account.name} Bill (${getMonthName(cycleMonth)} Cycle)" else existing.title
                        )
                        Log.d(TAG, "Updating credit card bill payable for ${account.name} to $closingBalance")
                        FirebaseService.updateReminder(updatedReminder)
                    }
                }
            }
        }
    }

    private fun getMonthName(month: Int): String {
        val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return if (month in 0..11) names[month] else ""
    }

    private fun getNextMonthDueDate(currentDueDateMs: Long): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = currentDueDateMs
            add(java.util.Calendar.MONTH, 1)
        }
        return cal.timeInMillis
    }

    private suspend fun checkAndGenerateNextRecurringReminders(remindersList: List<Reminder>) {
        val now = System.currentTimeMillis()
        val fifteenDaysInMs = 15L * 24 * 60 * 60 * 1000
        
        val newRemindersToCreate = mutableListOf<Reminder>()
        
        for (reminder in remindersList) {
            if (!reminder.isRecurring) continue
            
            // Calculate next instance due date
            val nextDue = getNextMonthDueDate(reminder.dueDate)
            
            // Check if we are within 15 days of the next due date
            if (now >= nextDue - fifteenDaysInMs) {
                // Check if an instance already exists for this title & nextDue to avoid duplicates
                val alreadyExists = remindersList.any { 
                    it.title.equals(reminder.title, ignoreCase = true) && it.dueDate == nextDue 
                }
                if (!alreadyExists) {
                    val nextInstance = Reminder(
                        title = reminder.title,
                        amount = reminder.amount,
                        type = reminder.type,
                        dueDate = nextDue,
                        isRecurring = true,
                        isPaid = false,
                        accountId = reminder.accountId
                    )
                    newRemindersToCreate.add(nextInstance)
                }
            }
        }
        
        for (newReminder in newRemindersToCreate) {
            Log.d(TAG, "Auto-generating recurring reminder 15 days in advance: ${newReminder.title} due on ${newReminder.dueDate}")
            FirebaseService.addReminder(newReminder)
        }
    }

    private fun checkAndTriggerReminderNotifications(context: Context, remindersList: List<Reminder>, accountsList: List<Account>) {
        val now = System.currentTimeMillis()
        val oneDayMs = 24L * 60 * 60 * 1000
        val currentUid = FirebaseService.getCurrentUserId()
        val spouseUid = FirebaseService.spouseUserUid.value ?: ""
        val currentFirstName = FirebaseService.currentUserFirstName.value ?: "Me"
        val spouseFirstName = FirebaseService.spouseUserFirstName.value ?: "Partner"
        
        for (reminder in remindersList) {
            if (reminder.isPaid) continue
            
            val diffMs = reminder.dueDate - now
            val daysRemaining = Math.ceil(diffMs.toDouble() / oneDayMs.toDouble()).toInt()
            val matchedAccount = accountsList.find { it.id == reminder.accountId }
            
            val isMutualFundReminder = reminder.type.equals("SIP", ignoreCase = true) ||
                                       reminder.type.equals("Mutual Fund", ignoreCase = true) ||
                                       reminder.title.contains("Mutual Fund", ignoreCase = true) ||
                                       reminder.title.contains("SIP", ignoreCase = true) ||
                                       (matchedAccount?.accountName?.contains("Mutual Fund", ignoreCase = true) == true) ||
                                       (matchedAccount?.accountName?.contains("SIP", ignoreCase = true) == true)

            // Notification intervals requested: 15, 10, 7, 5, and daily after that (which means 4, 3, 2, 1, 0, or overdue/negative days)
            // Mutual Fund special rule: Trigger exactly one alert 5 days before. No repeated or daily follow-ups.
            val shouldTrigger = if (isMutualFundReminder) {
                daysRemaining == 5
            } else {
                when {
                    daysRemaining == 15 -> true
                    daysRemaining == 10 -> true
                    daysRemaining == 7 -> true
                    daysRemaining == 5 -> true
                    daysRemaining < 5 -> true
                    else -> false
                }
            }
            
            if (shouldTrigger) {
                // Check owner notification constraints
                var notifyThisDeviceUser = false
                var relationLabel = ""
                
                if (matchedAccount != null) {
                    val owner = matchedAccount.ownerUserId
                    if (owner.equals("SHARED", ignoreCase = true)) {
                        // Shared accounts: remind both at all times
                        notifyThisDeviceUser = true
                        relationLabel = "Shared Account"
                    } else if (owner == currentUid) {
                        // Current logged in user is the owner: remind at all times
                        notifyThisDeviceUser = true
                        relationLabel = "Owner ($currentFirstName)"
                    } else if (owner == spouseUid) {
                        // Spouse is the owner. Current is "other user": remind ONLY if <= 5 days remaining
                        if (daysRemaining <= 5) {
                            notifyThisDeviceUser = true
                            relationLabel = "Spouse Account ($spouseFirstName) - Backup Reminder"
                        }
                    } else {
                        // Default fallback logic
                        notifyThisDeviceUser = true
                        relationLabel = "Account Remind"
                    }
                } else {
                    // No account linkage, notify both/any (backwards compatibility)
                    notifyThisDeviceUser = true
                    relationLabel = "Unlinked Scheduled Payment"
                }

                if (notifyThisDeviceUser && shouldNotifyToday(context, reminder.id)) {
                    val title: String
                    val message: String
                    val prefix = if (relationLabel.isNotEmpty()) "[$relationLabel] " else ""
                    
                    if (daysRemaining < 0) {
                        val overdueDays = Math.abs(daysRemaining)
                        title = "🚨 ${prefix}OVERDUE: ${reminder.title}"
                        message = "Your payment of ₹${reminder.amount} was due $overdueDays days ago! Please pay immediately."
                    } else if (daysRemaining == 0) {
                        title = "⏳ ${prefix}DUE TODAY: ${reminder.title}"
                        message = "Your payment of ₹${reminder.amount} is due today! Avoid late fees."
                    } else {
                        title = "🔔 ${prefix}Upcoming Bill: ${reminder.title}"
                        message = "Your payment of ₹${reminder.amount} is due in $daysRemaining days."
                    }
                    
                    triggerNotificationForReminder(context, reminder.id, title, message)
                }
            }
        }
    }

    private fun shouldNotifyToday(context: Context, reminderId: String): Boolean {
        val prefs = context.getSharedPreferences("reminder_notifications_prefs", Context.MODE_PRIVATE)
        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val lastNotified = prefs.getString("last_notif_$reminderId", "")
        if (lastNotified == todayStr) {
            return false // Already notified today
        }
        prefs.edit().putString("last_notif_$reminderId", todayStr).apply()
        return true
    }

    private fun triggerNotificationForReminder(context: Context, reminderId: String, title: String, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "reminder_alerts_channel"
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Payment Reminders",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when payments and recurring bill items are upcoming or overdue"
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            
        notificationManager.notify(reminderId.hashCode(), builder.build())
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

    fun deleteAllTransactions(onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                FirebaseService.deleteAllTransactions()
                onComplete(true, null)
            } catch (e: Exception) {
                onComplete(false, e.localizedMessage ?: e.message)
            }
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
                                tokens.any { token -> smsBody.contains(token) }
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
