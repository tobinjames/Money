package com.example.data

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID
import java.util.Calendar
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import com.example.sms.SmsBackfillWorker

// Thrown when an email/password user must open the verification link before signing in.
class EmailVerificationRequiredException(message: String) : Exception(message)

enum class AuthState {
    LOADING,
    UNAUTHENTICATED,
    AUTHORIZED,
    DENIED
}

object FirebaseService {
    private const val TAG = "FirebaseService"
    
    private fun isSameDay(millis1: Long, millis2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = millis1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = millis2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var firestore: FirebaseFirestore? = null
    private var auth: FirebaseAuth? = null
    val useFallback = MutableStateFlow(false)
    val authStateVal = MutableStateFlow<AuthState>(AuthState.LOADING)
    
    // --- Local Fallback State (Ensures full preview functionality immediately) ---
    private val localAccounts = MutableStateFlow<List<Account>>(emptyList())
    private val localTransactions = MutableStateFlow<List<Transaction>>(emptyList())
    private val localReminders = MutableStateFlow<List<Reminder>>(emptyList())
    private val localSenders = MutableStateFlow<List<WhitelistedSender>>(emptyList())
    private val localMappings = MutableStateFlow<List<ManualMapping>>(emptyList())
    private val localBudgets = MutableStateFlow<List<Budget>>(emptyList())
    private val localMerchantRules = MutableStateFlow<List<MerchantRule>>(emptyList())
    private val localPrivacyKeywords = MutableStateFlow<List<PrivacyKeyword>>(emptyList())
    val softBudgetWarnings = MutableStateFlow<List<String>>(emptyList())
    
    private var currentUserId: String? = null
    private var allowlistUid: String? = null
    @Volatile private var accountsLoaded = false
    @Volatile private var transactionsLoaded = false
    private val coreDataMutex = kotlinx.coroutines.sync.Mutex()
    private var remindersListenerRegistered = false
    private var transactionsListenerRegistered = false
    private var accountsListenerRegistered = false
    private var userEmail = ""
    private var userName = "User"
    private var appContext: Context? = null
    private var mockDataInitialized = false

    fun initialize(context: Context) {
        appContext = context.applicationContext
        // Safe to call repeatedly (activity, SMS worker, backfill worker): set up only once per process.
        if (firestore != null && auth != null) return
        setupInitialMockData()
        try {
            // Check if Firebase is already initialized
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                firestore = FirebaseFirestore.getInstance()
                auth = FirebaseAuth.getInstance()
                useFallback.value = false
                Log.d(TAG, "Firebase initialized successfully via Google Services plugin.")
                restoreSession()
                return
            }

            // Programmatic Firebase Setup to bypass missing google-services.json crash
            val options = FirebaseOptions.Builder()
                .setApiKey("AIzaSyA3T3o9-B878GaJeTSABX-ADtjge32qddI")
                .setApplicationId("1:247383093904:android:d882b7dccf937e1e055e15")
                .setProjectId("nestwise-5c65e")
                .setStorageBucket("nestwise-5c65e.firebasestorage.app")
                .build()
                
            FirebaseApp.initializeApp(context, options)
            firestore = FirebaseFirestore.getInstance()
            auth = FirebaseAuth.getInstance()
            useFallback.value = false
            Log.d(TAG, "Firebase initialized programmatically with real settings.")
            restoreSession()
        } catch (e: Exception) {
            Log.e(TAG, "Firebase setup error: ${e.message}.")
            useFallback.value = false
            authStateVal.value = AuthState.UNAUTHENTICATED
        }
    }
    
    // Restores a persisted Firebase Auth session on app start (or in a background worker).
    private fun restoreSession() {
        serviceScope.launch {
            val user = auth?.currentUser
            if (user == null) {
                // Require user to login via the AuthScreen on first-time launch
                authStateVal.value = AuthState.UNAUTHENTICATED
                return@launch
            }
            if (!isEmailVerified(user)) {
                Log.w(TAG, "Stored session has an unverified email; sign-in required.")
                logout()
                return@launch
            }
            val email = user.email ?: ""
            if (checkIsEmailAuthorized(email)) {
                val ownerUid = ownerUidFor(user)
                currentUserUid.value = ownerUid
                currentUserId = ownerUid
                if (currentUserFirstName.value == null) {
                    currentUserFirstName.value = user.displayName?.split(" ")?.firstOrNull() ?: "User"
                }
                userEmail = email
                userName = user.displayName ?: ""
                syncFamilyGroupAndSpouse(ownerUid)
                checkAndSeedFirestoreIfEmpty()
                markAuthorized()
            } else {
                logout()
                authStateVal.value = AuthState.DENIED
            }
        }
    }

    // Firestore rules require a verified email. Google accounts are always verified.
    private fun isEmailVerified(user: com.google.firebase.auth.FirebaseUser): Boolean {
        return user.isEmailVerified || user.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }
    }

    // The family's owner id: an explicit `uid` on the allowlist entry wins (keeps records created
    // under an older id), otherwise the Firebase Auth uid.
    private fun ownerUidFor(user: com.google.firebase.auth.FirebaseUser): String = allowlistUid ?: user.uid

    /**
     * Loads accounts and transactions from Firestore once per process, before SMS processing reads
     * them. Without this, a cold background process sees empty lists, so duplicate checks pass and
     * account matching falls back to creating placeholder accounts.
     */
    suspend fun ensureCoreDataLoaded() {
        val fs = firestore ?: return
        if (auth?.currentUser == null) return
        if (accountsLoaded && transactionsLoaded) return
        coreDataMutex.withLock {
            try {
                if (!accountsLoaded) {
                    val remote = fs.collection("ChartOfAccounts").get().awaitTask().documents.mapNotNull { doc ->
                        try { doc.toObject(Account::class.java) } catch (e: Exception) { null }
                    }
                    val merged = localAccounts.value.toMutableList()
                    remote.forEach { acc ->
                        val idx = merged.indexOfFirst { it.accountId == acc.accountId }
                        if (idx != -1) merged[idx] = acc else merged.add(acc)
                    }
                    localAccounts.value = merged
                    accountsLoaded = true
                }
                if (!transactionsLoaded) {
                    val remote = fs.collection("Transactions").get().awaitTask().documents.mapNotNull { doc ->
                        try { doc.toObject(Transaction::class.java) } catch (e: Exception) { null }
                    }
                    val remoteIds = remote.map { it.transactionId }.toSet()
                    // Keep anything added locally that has not reached the snapshot yet.
                    localTransactions.value = remote + localTransactions.value.filter { it.transactionId !in remoteIds }
                    transactionsLoaded = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not preload accounts/transactions: ${e.message}")
            }
        }
    }

    private fun markAuthorized() {
        startRealtimeListeners()
        authStateVal.value = AuthState.AUTHORIZED
    }

    // Attaches the long-lived Firestore listeners (each getter registers its listener once).
    private fun startRealtimeListeners() {
        getAccounts()
        getTransactions()
        getReminders()
    }

    // True once Firebase Auth has a signed-in user (used by background SMS processing).
    fun hasSignedInUser(): Boolean = auth?.currentUser != null

    fun getDefaultMockTransactions(): List<Transaction> {
        return emptyList()
    }
    
    fun getDummyTransactions(): List<Transaction> {
        val currentMonthStart = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        
        val previousMonthStart = Calendar.getInstance().apply {
            add(Calendar.MONTH, -1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        return listOf(
            Transaction(
                transactionId = "tx_001",
                amount = 45000.0,
                date = currentMonthStart + 12 * 60 * 60 * 1000L,
                payeeName = "Acme Corp Salary",
                debitAccountId = "acc_hdfc_01",
                creditAccountId = "Salary",
                entryType = "INCOME",
                isIgnored = false,
                id = "tx_001",
                timestamp = currentMonthStart + 12 * 60 * 60 * 1000L,
                narration = "Acme Corp Salary",
                type = TransactionType.INCOME,
                category = "Salary",
                accountId = "acc_hdfc_01",
                status = TransactionStatus.CONFIRMED,
                paidByUserId = "User 1",
                ownerUserId = "User 1",
                ownerFirstName = "User 1",
                scope = TransactionScope.FAMILY
            ),
            Transaction(
                transactionId = "tx_002",
                amount = 40000.0,
                date = currentMonthStart + 13 * 60 * 60 * 1000L,
                payeeName = "Globex Corp Salary",
                debitAccountId = "acc_icici_02",
                creditAccountId = "Salary",
                entryType = "INCOME",
                isIgnored = false,
                id = "tx_002",
                timestamp = currentMonthStart + 13 * 60 * 60 * 1000L,
                narration = "Globex Corp Salary",
                type = TransactionType.INCOME,
                category = "Salary",
                accountId = "acc_icici_02",
                status = TransactionStatus.CONFIRMED,
                paidByUserId = "User 2",
                ownerUserId = "User 2",
                ownerFirstName = "User 2",
                scope = TransactionScope.FAMILY
            ),
            Transaction(
                transactionId = "tx_003",
                amount = 1200.0,
                date = currentMonthStart + 4 * 24 * 60 * 60 * 1000L + 20 * 60 * 60 * 1000L,
                payeeName = "ZOMATO RESTAURANTS",
                debitAccountId = "Food",
                creditAccountId = "acc_card_03",
                entryType = "EXPENSE",
                isIgnored = false,
                id = "tx_003",
                timestamp = currentMonthStart + 4 * 24 * 60 * 60 * 1000L + 20 * 60 * 60 * 1000L,
                narration = "Zomato dining order",
                type = TransactionType.EXPENSE,
                category = "Food",
                accountId = "acc_card_03",
                status = TransactionStatus.CONFIRMED,
                paidByUserId = "User 1",
                ownerUserId = "User 1",
                ownerFirstName = "User 1",
                scope = TransactionScope.FAMILY,
                isSmartRule = true
            ),
            Transaction(
                transactionId = "tx_004",
                amount = 2400.0,
                date = currentMonthStart + 7 * 24 * 60 * 60 * 1000L + 11 * 60 * 60 * 1000L,
                payeeName = "BIGBASKET GROCERIES",
                debitAccountId = "Groceries",
                creditAccountId = "acc_hdfc_01",
                entryType = "EXPENSE",
                isIgnored = false,
                id = "tx_004",
                timestamp = currentMonthStart + 7 * 24 * 60 * 60 * 1000L + 11 * 60 * 60 * 1000L,
                narration = "Weekly groceries restock",
                type = TransactionType.EXPENSE,
                category = "Groceries",
                accountId = "acc_hdfc_01",
                status = TransactionStatus.CONFIRMED,
                paidByUserId = "User 2",
                ownerUserId = "User 2",
                ownerFirstName = "User 2",
                scope = TransactionScope.FAMILY
            ),
            Transaction(
                transactionId = "tx_005",
                amount = 3000.0,
                date = currentMonthStart + 9 * 24 * 60 * 60 * 1000L + 15 * 60 * 60 * 1000L,
                payeeName = "ELECTRICITY BOARD",
                debitAccountId = "Utilities",
                creditAccountId = "acc_icici_02",
                entryType = "EXPENSE",
                isIgnored = false,
                id = "tx_005",
                timestamp = currentMonthStart + 9 * 24 * 60 * 60 * 1000L + 15 * 60 * 60 * 1000L,
                narration = "Electricity bill payment",
                type = TransactionType.EXPENSE,
                category = "Utilities",
                accountId = "acc_icici_02",
                status = TransactionStatus.CONFIRMED,
                paidByUserId = "User 1",
                ownerUserId = "User 1",
                ownerFirstName = "User 1",
                scope = TransactionScope.FAMILY
            ),
            Transaction(
                transactionId = "tx_006",
                amount = 850.0,
                date = currentMonthStart + 11 * 24 * 60 * 60 * 1000L + 18 * 60 * 60 * 1000L,
                payeeName = "UBER RIDE",
                debitAccountId = "Travel",
                creditAccountId = "acc_card_03",
                entryType = "EXPENSE",
                isIgnored = false,
                id = "tx_006",
                timestamp = currentMonthStart + 11 * 24 * 60 * 60 * 1000L + 18 * 60 * 60 * 1000L,
                narration = "Weekend commute Uber",
                type = TransactionType.EXPENSE,
                category = "Travel",
                accountId = "acc_card_03",
                status = TransactionStatus.CONFIRMED,
                paidByUserId = "User 2",
                ownerUserId = "User 2",
                ownerFirstName = "User 2",
                scope = TransactionScope.FAMILY,
                isSmartRule = true
            ),
            Transaction(
                transactionId = "tx_007_p",
                amount = 1800.0,
                date = currentMonthStart + 14 * 24 * 60 * 60 * 1000L + 10 * 60 * 60 * 1000L,
                payeeName = "HDFC Personal Loan EMI",
                debitAccountId = "acc_loan_04",
                creditAccountId = "acc_hdfc_01",
                entryType = "TRANSFER",
                isIgnored = false,
                id = "tx_007_p",
                timestamp = currentMonthStart + 14 * 24 * 60 * 60 * 1000L + 10 * 60 * 60 * 1000L,
                narration = "Manual EMI Principal: Loan Payment",
                type = TransactionType.TRANSFER,
                category = "Transfer",
                accountId = "acc_hdfc_01",
                targetAccountId = "acc_loan_04",
                status = TransactionStatus.CONFIRMED,
                paidByUserId = "User 1",
                ownerUserId = "User 1",
                ownerFirstName = "User 1",
                scope = TransactionScope.FAMILY,
                isLoanEmi = true,
                principalComponent = 1800.0
            ),
            Transaction(
                transactionId = "tx_007_i",
                amount = 700.0,
                date = currentMonthStart + 14 * 24 * 60 * 60 * 1000L + 10 * 60 * 60 * 1000L,
                payeeName = "HDFC Personal Loan Interest",
                debitAccountId = "Finance: Loan Interest",
                creditAccountId = "acc_hdfc_01",
                entryType = "EXPENSE",
                isIgnored = false,
                id = "tx_007_i",
                timestamp = currentMonthStart + 14 * 24 * 60 * 60 * 1000L + 10 * 60 * 60 * 1000L,
                narration = "Manual EMI Interest: Loan Payment",
                type = TransactionType.EXPENSE,
                category = "Finance: Loan Interest",
                accountId = "acc_hdfc_01",
                status = TransactionStatus.CONFIRMED,
                paidByUserId = "User 1",
                ownerUserId = "User 1",
                ownerFirstName = "User 1",
                scope = TransactionScope.FAMILY,
                isLoanEmi = true,
                interestComponent = 700.0
            ),
            Transaction(
                transactionId = "tx_008",
                amount = 1500.0,
                date = currentMonthStart + 17 * 24 * 60 * 60 * 1000L + 14 * 60 * 60 * 1000L,
                payeeName = "AMAZON INDIA",
                debitAccountId = "Others",
                creditAccountId = "acc_card_03",
                entryType = "EXPENSE",
                isIgnored = false,
                id = "tx_008",
                timestamp = currentMonthStart + 17 * 24 * 60 * 60 * 1000L + 14 * 60 * 60 * 1000L,
                narration = "Amazon shopping",
                type = TransactionType.EXPENSE,
                category = "Others",
                accountId = "acc_card_03",
                status = TransactionStatus.CONFIRMED,
                paidByUserId = "User 1",
                ownerUserId = "User 1",
                ownerFirstName = "User 1",
                scope = TransactionScope.FAMILY
            ),
            Transaction(
                transactionId = "tx_009",
                amount = 650.0,
                date = currentMonthStart + 21 * 24 * 60 * 60 * 1000L + 16 * 60 * 60 * 1000L,
                payeeName = "STARBUCKS COFFEE",
                debitAccountId = "Food",
                creditAccountId = "acc_hdfc_01",
                entryType = "EXPENSE",
                isIgnored = false,
                id = "tx_009",
                timestamp = currentMonthStart + 21 * 24 * 60 * 60 * 1000L + 16 * 60 * 60 * 1000L,
                narration = "Coffee meetup",
                type = TransactionType.EXPENSE,
                category = "Food",
                accountId = "acc_hdfc_01",
                status = TransactionStatus.CONFIRMED,
                paidByUserId = "User 1",
                ownerUserId = "User 1",
                ownerFirstName = "User 1",
                scope = TransactionScope.FAMILY
            ),
            Transaction(
                transactionId = "tx_010",
                amount = 450.0,
                date = currentMonthStart + 24 * 24 * 60 * 60 * 1000L + 12 * 60 * 60 * 1000L,
                payeeName = "AMAZON REFUND",
                debitAccountId = "acc_card_03",
                creditAccountId = "Refund",
                entryType = "INCOME",
                isIgnored = false,
                id = "tx_010",
                timestamp = currentMonthStart + 24 * 24 * 60 * 60 * 1000L + 12 * 60 * 60 * 1000L,
                narration = "Amazon refund for return",
                type = TransactionType.INCOME,
                category = "Refund",
                accountId = "acc_card_03",
                status = TransactionStatus.CONFIRMED,
                paidByUserId = "User 1",
                ownerUserId = "User 1",
                ownerFirstName = "User 1",
                scope = TransactionScope.FAMILY
            ),
            Transaction(
                transactionId = "tx_011",
                amount = 45000.0,
                date = previousMonthStart + 12 * 60 * 60 * 1000L,
                payeeName = "Acme Corp Salary",
                debitAccountId = "acc_hdfc_01",
                creditAccountId = "Salary",
                entryType = "INCOME",
                isIgnored = false,
                id = "tx_011",
                timestamp = previousMonthStart + 12 * 60 * 60 * 1000L,
                narration = "Acme Corp Salary",
                type = TransactionType.INCOME,
                category = "Salary",
                accountId = "acc_hdfc_01",
                status = TransactionStatus.CONFIRMED,
                paidByUserId = "User 1",
                ownerUserId = "User 1",
                ownerFirstName = "User 1",
                scope = TransactionScope.FAMILY
            ),
            Transaction(
                transactionId = "tx_012",
                amount = 15000.0,
                date = previousMonthStart + 2 * 24 * 60 * 60 * 1000L + 10 * 60 * 60 * 1000L,
                payeeName = "RENT PAYMENT",
                debitAccountId = "Others",
                creditAccountId = "acc_hdfc_01",
                entryType = "EXPENSE",
                isIgnored = false,
                id = "tx_012",
                timestamp = previousMonthStart + 2 * 24 * 60 * 60 * 1000L + 10 * 60 * 60 * 1000L,
                narration = "Monthly apartment rent",
                type = TransactionType.EXPENSE,
                category = "Others",
                accountId = "acc_hdfc_01",
                status = TransactionStatus.CONFIRMED,
                paidByUserId = "User 1",
                ownerUserId = "User 1",
                ownerFirstName = "User 1",
                scope = TransactionScope.FAMILY
            ),
            Transaction(
                transactionId = "tx_013",
                amount = 3100.0,
                date = previousMonthStart + 9 * 24 * 60 * 60 * 1000L + 15 * 60 * 60 * 1000L,
                payeeName = "BIGBASKET GROCERIES",
                debitAccountId = "Groceries",
                creditAccountId = "acc_icici_02",
                entryType = "EXPENSE",
                isIgnored = false,
                id = "tx_013",
                timestamp = previousMonthStart + 9 * 24 * 60 * 60 * 1000L + 15 * 60 * 60 * 1000L,
                narration = "Groceries shopping",
                type = TransactionType.EXPENSE,
                category = "Groceries",
                accountId = "acc_icici_02",
                status = TransactionStatus.CONFIRMED,
                paidByUserId = "User 2",
                ownerUserId = "User 2",
                ownerFirstName = "User 2",
                scope = TransactionScope.FAMILY
            )
        )
    }

    private fun setupInitialMockData() {
        if (mockDataInitialized) return
        mockDataInitialized = true
        
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
        
        localAccounts.value = listOf(
            // Asset default accounts
            Account(
                accountId = "acc_primary_bank",
                accountName = "Primary Bank Account",
                accountType = "ASSET",
                openingBalance = 0.0,
                isSystemDefault = true,
                iconName = "Bank",
                ownerUserId = "SHARED",
                ownerFirstName = ""
            ),
            Account(
                accountId = "acc_cash_wallet",
                accountName = "Cash / Wallet",
                accountType = "ASSET",
                openingBalance = 0.0,
                isSystemDefault = true,
                iconName = "Cash",
                ownerUserId = "SHARED",
                ownerFirstName = ""
            ),
            // Liability default accounts
            Account(
                accountId = "acc_primary_card",
                accountName = "Primary Credit Card",
                accountType = "LIABILITY",
                openingBalance = 0.0,
                isSystemDefault = true,
                iconName = "Credit Card",
                ownerUserId = "SHARED",
                ownerFirstName = ""
            ),
            // Income categories as accounts
            Account(accountId = "Salary", accountName = "Salary", accountType = "INCOME", openingBalance = 0.0, isSystemDefault = true, iconName = "Salary"),
            Account(accountId = "Refund", accountName = "Refund", accountType = "INCOME", openingBalance = 0.0, isSystemDefault = true, iconName = "Refund"),
            
            // Expense categories as accounts
            Account(accountId = "Food", accountName = "Food", accountType = "EXPENSE", openingBalance = 0.0, isSystemDefault = true, iconName = "Food"),
            Account(accountId = "Groceries", accountName = "Groceries", accountType = "EXPENSE", openingBalance = 0.0, isSystemDefault = true, iconName = "Groceries"),
            Account(accountId = "Utilities", accountName = "Utilities", accountType = "EXPENSE", openingBalance = 0.0, isSystemDefault = true, iconName = "Utilities"),
            Account(accountId = "Travel", accountName = "Travel", accountType = "EXPENSE", openingBalance = 0.0, isSystemDefault = true, iconName = "Travel"),
            Account(accountId = "Others", accountName = "Others", accountType = "EXPENSE", openingBalance = 0.0, isSystemDefault = true, iconName = "Others")
        )
        
        localBudgets.value = emptyList()
        
        localTransactions.value = emptyList()
        
        localReminders.value = emptyList()
        
        localSenders.value = listOf(
            WhitelistedSender("send_001", "HDFCBK", "HDFC Bank"),
            WhitelistedSender("send_002", "ICICIBK", "ICICI Bank"),
            WhitelistedSender("send_003", "SBIBNK", "SBI Bank")
        )
        
        localMappings.value = emptyList()
        

        localMerchantRules.value = listOf(
            MerchantRule(ruleId = "rule_001", keyword = "UBER", assignedCategory = "Travel"),
            MerchantRule(ruleId = "rule_002", keyword = "ZOMATO", assignedCategory = "Food")
        )

        localPrivacyKeywords.value = listOf(
            PrivacyKeyword("priv_001", "OTP"),
            PrivacyKeyword("priv_002", "One Time Password"),
            PrivacyKeyword("priv_003", "PIN"),
            PrivacyKeyword("priv_004", "Password"),
            PrivacyKeyword("priv_005", "Login verification")
        )
    }

    private suspend fun seedTransactionsOnly() = withContext(Dispatchers.IO) {
        val fs = firestore ?: return@withContext
        setupInitialMockData()
        val activeUid = getCurrentUserId()
        val spouseUid = spouseUserUid.value ?: ""
        try {
            val deferreds = getDefaultMockTransactions().map { transaction ->
                val finalTx = transaction.copy(
                    ownerUserId = if (transaction.ownerUserId.isBlank() || transaction.ownerUserId == "User 1") activeUid else transaction.ownerUserId,
                    paidByUserId = if (transaction.paidByUserId.isBlank() || transaction.paidByUserId == "User 1") activeUid else transaction.paidByUserId
                )
                async { fs.collection("Transactions").document(finalTx.transactionId).set(finalTx).awaitTask() }
            }
            deferreds.awaitAll()
            Log.d(TAG, "Successfully seeded transactions only.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed seeding transactions: ${e.message}")
        }
    }

    suspend fun seedSystemDefaultsOnly() = withContext(Dispatchers.IO) {
        val fs = firestore ?: throw Exception("Firestore database is not initialized.")
        setupInitialMockData()
        try {
            coroutineScope {
                val deferreds = mutableListOf<kotlinx.coroutines.Deferred<Any>>()
                
                // Seed System Default Categories/Accounts
                localAccounts.value.forEach { account ->
                    if (account.isSystemDefault) {
                        deferreds.add(async { fs.collection("ChartOfAccounts").document(account.accountId).set(account).awaitTask() })
                    }
                }
                
                // Seed Whitelisted Senders
                localSenders.value.forEach { s ->
                    deferreds.add(async { fs.collection("whitelisted_senders").document(s.id).set(s).awaitTask() })
                }
                
                deferreds.awaitAll()
                Log.d(TAG, "Successfully seeded system defaults (categories and whitelisted senders) only.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed seeding system defaults: ${e.message}")
        }
    }

    private fun checkAndSeedFirestoreIfEmpty() {
        val fs = firestore ?: return
        if (auth?.currentUser == null) {
            Log.d(TAG, "User not authenticated yet; skipping Firestore seeding check.")
            return
        }
        fs.collection("ChartOfAccounts").limit(1).get()
            .addOnSuccessListener { querySnapshot ->
                if (querySnapshot.isEmpty) {
                    Log.d(TAG, "Firestore 'ChartOfAccounts' collection is empty. Seeding system default categories and whitelisted senders...")
                    serviceScope.launch {
                        try {
                            seedSystemDefaultsOnly()
                        } catch (e: Exception) {
                            Log.w(TAG, "Automatic database seeding skipped/failed: ${e.message}")
                        }
                    }
                } else {
                    Log.d(TAG, "Firestore 'ChartOfAccounts' is already seeded. Skipping automatic seeding.")
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Firestore seeding check skipped: ${e.message}")
            }
    }

    suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        this.addOnSuccessListener { result ->
            continuation.resume(result)
        }.addOnFailureListener { exception ->
            continuation.resumeWithException(exception)
        }
    }

    fun triggerSeeding(callback: (Boolean, String?) -> Unit) {
        serviceScope.launch {
            try {
                seedRealFirestoreData()
                withContext(Dispatchers.Main) {
                    callback(true, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Triggered seeding database failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback(false, e.localizedMessage ?: e.message)
                }
            }
        }
    }

    suspend fun seedRealFirestoreData() = withContext(Dispatchers.IO) {
        val fs = firestore ?: throw Exception("Firestore database is not initialized.")
        
        // Setup mock data locally first to get the lists
        setupInitialMockData()
        
        val activeUid = getCurrentUserId()
        val spouseUid = spouseUserUid.value ?: ""
        
        try {
            coroutineScope {
                val deferreds = mutableListOf<kotlinx.coroutines.Deferred<Any>>()
                
                // Seed Accounts
                localAccounts.value.forEach { account ->
                    val finalAccount = account.copy(
                        ownerUserId = if (account.ownerUserId.isNullOrBlank() || account.ownerUserId == "User 1") activeUid else account.ownerUserId
                    )
                    deferreds.add(async { fs.collection("ChartOfAccounts").document(finalAccount.accountId).set(finalAccount).awaitTask() })
                }
                
                // Seed Budgets
                localBudgets.value.forEach { budget ->
                    deferreds.add(async { fs.collection("budgets").document(budget.budgetId).set(budget).awaitTask() })
                }
                
                // Seed Transactions
                getDefaultMockTransactions().forEach { transaction ->
                    val finalTx = transaction.copy(
                        ownerUserId = if (transaction.ownerUserId.isBlank() || transaction.ownerUserId == "User 1") activeUid else transaction.ownerUserId,
                        paidByUserId = if (transaction.paidByUserId.isBlank() || transaction.paidByUserId == "User 1") activeUid else transaction.paidByUserId
                    )
                    deferreds.add(async { fs.collection("Transactions").document(finalTx.transactionId).set(finalTx).awaitTask() })
                }
                
                // Seed Reminders
                localReminders.value.forEach { r ->
                    deferreds.add(async { fs.collection("reminders").document(r.id).set(r).awaitTask() })
                }
                
                // Seed Senders
                localSenders.value.forEach { s ->
                    deferreds.add(async { fs.collection("whitelisted_senders").document(s.id).set(s).awaitTask() })
                }
                
                // Seed Mappings
                localMappings.value.forEach { m ->
                    deferreds.add(async { fs.collection("manual_mappings").document(m.id).set(m).awaitTask() })
                }
                
                // Seed Merchant Rules
                localMerchantRules.value.forEach { r ->
                    deferreds.add(async { fs.collection("merchant_rules").document(r.ruleId).set(r).awaitTask() })
                }
                
                deferreds.awaitAll()
            }
            
            // Seed default family group
            createDefaultFamilyGroup(activeUid)
        } catch (e: Exception) {
            Log.e(TAG, "Failed seeding Cloud Firestore: ${e.message}")
            throw e
        }
    }

    suspend fun runFirestoreDiagnostics(): String {
        val fs = firestore ?: return "Error: Cloud Firestore instance is null. Please ensure Firebase initialization did not skip on boot."
        val uid = getCurrentUserId()
        val testDocId = "test_$uid"
        val testCollection = fs.collection("connection_tests")
        val timestamp = System.currentTimeMillis()
        val testData = mapOf(
            "testedBy" to uid,
            "testerEmail" to getCurrentUserEmail(),
            "timestamp" to timestamp,
            "status" to "Active & Connected",
            "message" to "Congratulations! Your Nestwise App is successfully communicating with Cloud Firestore in real-time."
        )
        return try {
            Log.d(TAG, "Running Firestore write diagnostics test...")
            testCollection.document(testDocId).set(testData).awaitTask()
            
            Log.d(TAG, "Running Firestore read diagnostics test...")
            val resultDoc = testCollection.document(testDocId).get().awaitTask()
            if (resultDoc.exists()) {
                "SUCCESS: Read & Write tests succeeded!\n\nA document under ID \"$testDocId\" was successfully created inside the \"connection_tests\" collection.\n\nYou should now see a new collection named \"connection_tests\" in your Firebase Console Firestore Database!"
            } else {
                "ERROR: Document was successfully written, but reading it back returned empty. Please check your Firestore rules or latency."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Diagnostics failed with error: ${e.message}")
            "FAILED: ${e.localizedMessage ?: e.message}\n\nTroubleshooting Guide:\n1. Ensure you have created a Cloud Firestore Database in the Firebase Console (nestwise-5c65e).\n2. Deploy the security rules from firestore.rules in the repository (Firebase Console > Firestore Database > Rules), and make sure your email is in the AuthorizedUsers collection. Never use 'allow read, write: if true'.\n3. Make sure the database has been initialized in 'production mode' or 'test mode' and is active."
        }
    }

    // --- Current user helpers ---
    val currentUserUid = MutableStateFlow<String?>(null)
    val currentUserFirstName = MutableStateFlow<String?>(null)
    val spouseUserUid = MutableStateFlow<String?>(null)
    val spouseUserFirstName = MutableStateFlow<String?>(null)

    fun getFirebaseAuth(): FirebaseAuth? = auth

    fun triggerSmsBackfill(accountId: String? = null, senderId: String? = null) {
        val context = appContext ?: return
        try {
            val dataBuilder = androidx.work.Data.Builder()
            if (accountId != null) {
                dataBuilder.putString("accountId", accountId)
            }
            if (senderId != null) {
                dataBuilder.putString("senderId", senderId)
            }
            val request = OneTimeWorkRequestBuilder<SmsBackfillWorker>()
                .setInputData(dataBuilder.build())
                .build()
            
            val uniqueName = when {
                accountId != null -> "SmsBackfill_Account_$accountId"
                senderId != null -> "SmsBackfill_Sender_$senderId"
                else -> "SmsBackfillUniqueWork"
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.d(TAG, "Successfully enqueued SMS backfill worker with name $uniqueName (accountId: $accountId, senderId: $senderId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue SmsBackfillWorker: ${e.message}")
        }
    }

    fun getCurrentUserEmail(): String = auth?.currentUser?.email ?: userEmail
    fun getCurrentUserDisplayName(): String = auth?.currentUser?.displayName ?: userName
    fun getCurrentUserId(): String = auth?.currentUser?.uid ?: (currentUserUid.value ?: (currentUserId ?: "user_default"))
    
    // Whitelist Checking Method against Cloud Firestore
    suspend fun checkIsEmailAuthorized(email: String): Boolean {
        val fs = firestore ?: run {
            Log.e(TAG, "Firestore instance is null during authorization check.")
            return false
        }
        val emailLower = email.lowercase().trim()
        if (emailLower.isEmpty()) return false
        allowlistUid = null

        return try {
            // 1. Direct document lookup by email ID in "AuthorizedUsers" collection
            var docDirect = fs.collection("AuthorizedUsers").document(emailLower).get().awaitTask()
            if (!docDirect.exists()) {
                docDirect = fs.collection("whitelisted_userlist").document(emailLower).get().awaitTask()
            }
            if (docDirect.exists()) {
                val fName = docDirect.getString("firstName") ?: docDirect.getString("displayName") ?: docDirect.getString("name")
                val sEmail = docDirect.getString("spouseEmail")
                val sName = docDirect.getString("spouseFirstName") ?: docDirect.getString("partnerFirstName")
                allowlistUid = docDirect.getString("uid") ?: docDirect.getString("userId")
                val uId = allowlistUid ?: auth?.currentUser?.uid
                val sUid = docDirect.getString("spouseUid") ?: docDirect.getString("partnerUid")
                val isActive = docDirect.getBoolean("isActive") ?: docDirect.getBoolean("enabled") ?: true

                if (!isActive) {
                    Log.w(TAG, "User $emailLower is marked inactive/disabled in AuthorizedUsers collection.")
                    return false
                }

                if (fName != null) {
                    currentUserFirstName.value = fName
                    userName = fName
                }
                if (uId != null) {
                    currentUserUid.value = uId
                    currentUserId = uId
                }
                if (sName != null) spouseUserFirstName.value = sName
                if (sUid != null) spouseUserUid.value = sUid
                return true
            }

            // 2. Query documents in "AuthorizedUsers" where field "email" == emailLower
            var queryResult = fs.collection("AuthorizedUsers")
                .whereEqualTo("email", emailLower)
                .get()
                .awaitTask()
            if (queryResult.isEmpty) {
                queryResult = fs.collection("whitelisted_userlist")
                    .whereEqualTo("email", emailLower)
                    .get()
                    .awaitTask()
            }
            if (!queryResult.isEmpty) {
                val doc = queryResult.documents.first()
                val fName = doc.getString("firstName") ?: doc.getString("displayName") ?: doc.getString("name")
                val sEmail = doc.getString("spouseEmail")
                val sName = doc.getString("spouseFirstName") ?: doc.getString("partnerFirstName")
                allowlistUid = doc.getString("uid") ?: doc.getString("userId")
                val uId = allowlistUid ?: auth?.currentUser?.uid
                val sUid = doc.getString("spouseUid") ?: doc.getString("partnerUid")
                val isActive = doc.getBoolean("isActive") ?: doc.getBoolean("enabled") ?: true

                if (!isActive) {
                    Log.w(TAG, "User $emailLower is marked inactive/disabled in AuthorizedUsers collection.")
                    return false
                }

                if (fName != null) {
                    currentUserFirstName.value = fName
                    userName = fName
                }
                if (uId != null) {
                    currentUserUid.value = uId
                    currentUserId = uId
                }
                if (sName != null) spouseUserFirstName.value = sName
                if (sUid != null) spouseUserUid.value = sUid
                return true
            }

            // 3. Document storing a strict array of permitted email IDs (master_whitelist)
            var masterDoc = fs.collection("AuthorizedUsers").document("master_whitelist").get().awaitTask()
            if (!masterDoc.exists()) {
                masterDoc = fs.collection("whitelisted_userlist").document("master_whitelist").get().awaitTask()
            }
            if (masterDoc.exists()) {
                val emailsArray = masterDoc.get("emails") as? List<*>
                val contains = emailsArray?.any { it.toString().lowercase().trim() == emailLower } == true
                if (contains) {
                    val rawName = emailLower.substringBefore("@")
                    val capName = if (rawName.isNotEmpty()) rawName.substring(0, 1).uppercase() + rawName.substring(1) else "User"
                    currentUserFirstName.value = capName
                    userName = capName
                    currentUserUid.value = auth?.currentUser?.uid ?: "user_${UUID.randomUUID()}"
                    currentUserId = currentUserUid.value
                    return true
                }
            }

            // If not found in any authorized document in the Firestore collection
            Log.w(TAG, "Email $emailLower is not found in Firestore AuthorizedUsers collection.")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error querying Firestore AuthorizedUsers collection: ${e.message}", e)
            false
        }
    }

    private suspend fun saveUserProfileToFirestore(uid: String, email: String, displayName: String) {
        val fs = firestore ?: return
        try {
            val firstName = currentUserFirstName.value ?: displayName.split(" ").firstOrNull() ?: "User"
            val userProfile = mapOf(
                "userId" to uid,
                "email" to email,
                "userFirstName" to firstName,
                "spouseUserId" to (spouseUserUid.value ?: ""),
                "householdId" to "default_family_group"
            )
            fs.collection("users").document(uid).set(userProfile, SetOptions.merge()).awaitTask()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save user profile: ${e.message}")
        }
    }

    // Google Single Sign-On: exchange the Google ID token for a real Firebase Auth session,
    // so Firestore requests carry request.auth and the session survives app restarts.
    suspend fun signInWithGoogleIdToken(idToken: String) {
        val fbAuth = auth ?: throw Exception("Cannot connect to server: Firebase Authentication is unavailable.")
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val user = fbAuth.signInWithCredential(credential).awaitTask().user
            ?: throw Exception("Google sign-in succeeded but no Firebase user was returned.")
        val email = user.email ?: ""
        val displayName = user.displayName ?: email.substringBefore("@")
        val authorized = checkIsEmailAuthorized(email)
        if (authorized) {
            val ownerUid = ownerUidFor(user)
            currentUserUid.value = ownerUid
            currentUserId = ownerUid
            currentUserFirstName.value = currentUserFirstName.value ?: displayName.split(" ").firstOrNull() ?: "User"
            userEmail = email
            userName = displayName
            syncFamilyGroupAndSpouse(ownerUid)
            saveUserProfileToFirestore(user.uid, email, displayName)
            checkAndSeedFirestoreIfEmpty()
            markAuthorized()
        } else {
            logout()
            authStateVal.value = AuthState.DENIED
            throw Exception("Access Denied: The Google account ($email) is not in the Firestore 'AuthorizedUsers' collection.")
        }
    }

    suspend fun signInWithEmail(email: String, password: String): String {
        val fbAuth = auth ?: throw Exception("Cannot connect to server: Firebase Authentication is unavailable.")
        val user = fbAuth.signInWithEmailAndPassword(email, password).awaitTask().user
            ?: throw Exception("Login succeeded but user is null")
        if (!isEmailVerified(user)) {
            try {
                user.sendEmailVerification().awaitTask()
            } catch (e: Exception) {
                Log.w(TAG, "Could not send verification email: ${e.message}")
            }
            logout()
            throw EmailVerificationRequiredException("Please verify your email address first. We've sent a verification link to ${user.email ?: email}; open it, then sign in again.")
        }
        val uEmail = user.email ?: email
        if (!checkIsEmailAuthorized(uEmail)) {
            logout()
            authStateVal.value = AuthState.DENIED
            throw Exception("Access Denied: Your email address ($uEmail) is not in the 'AuthorizedUsers' collection.")
        }
        val ownerUid = ownerUidFor(user)
        val displayName = user.displayName ?: email.substringBefore("@")
        currentUserUid.value = ownerUid
        currentUserId = ownerUid
        currentUserFirstName.value = currentUserFirstName.value ?: displayName.split(" ").firstOrNull() ?: "User"
        userEmail = uEmail
        userName = displayName
        syncFamilyGroupAndSpouse(ownerUid)
        saveUserProfileToFirestore(user.uid, uEmail, displayName)
        checkAndSeedFirestoreIfEmpty()
        markAuthorized()
        return user.uid
    }

    // Creates the account, then checks the allowlist. The allowlist can only be read by a
    // signed-in user (see firestore.rules), so the check has to happen after sign-up.
    suspend fun signUpWithEmail(email: String, password: String, displayName: String): String {
        val fbAuth = auth ?: throw Exception("Cannot connect to server: Firebase Authentication is unavailable.")
        val user = fbAuth.createUserWithEmailAndPassword(email, password).awaitTask().user
            ?: throw Exception("Sign up returned success but user is null")
        if (!checkIsEmailAuthorized(user.email ?: email)) {
            try {
                user.delete().awaitTask()
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove unauthorized account: ${e.message}")
            }
            logout()
            authStateVal.value = AuthState.DENIED
            throw Exception("Access Denied: Your email address ($email) is not in the 'AuthorizedUsers' collection.")
        }
        try {
            user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(displayName).build()).awaitTask()
        } catch (e: Exception) {
            Log.w(TAG, "Could not set display name: ${e.message}")
        }
        user.sendEmailVerification().awaitTask()
        logout()
        throw EmailVerificationRequiredException("Account created. We've sent a verification link to $email; open it, then sign in.")
    }

    // Sign out helper
    fun logout() {
        auth?.signOut()
        appContext?.let { ctx ->
            try {
                com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(
                    ctx,
                    com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
                ).signOut()
            } catch (e: Exception) {
                Log.w(TAG, "Google sign-out failed: ${e.message}")
            }
        }
        allowlistUid = null
        currentUserUid.value = null
        currentUserFirstName.value = null
        spouseUserUid.value = null
        spouseUserFirstName.value = null
        userName = "Guest"
        userEmail = ""
        authStateVal.value = AuthState.UNAUTHENTICATED
    }

    // Query shared family project group in Firestore
    suspend fun syncFamilyGroupAndSpouse(currentUid: String) {
        val fs = firestore ?: return
        
        try {
            // Fetch family group document where members map contains currentUid
            fs.collection("family_groups")
                .whereEqualTo("members.$currentUid.exists", true)
                .get()
                .addOnSuccessListener { querySnapshot ->
                    if (!querySnapshot.isEmpty) {
                        val doc = querySnapshot.documents.first()
                        val members = doc.get("members") as? Map<String, Any> ?: emptyMap()
                        // Find a member that is NOT currentUid, which represents the spouse
                        val spouseId = members.keys.firstOrNull { it != currentUid }
                        if (spouseId != null) {
                            val spouseData = members[spouseId] as? Map<String, Any> ?: emptyMap()
                            val sName = spouseData["firstName"] as? String ?: "Partner"
                            spouseUserUid.value = spouseId
                            spouseUserFirstName.value = sName
                            Log.d(TAG, "Spouse loaded from family group: $sName ($spouseId)")
                        }
                    } else {
                        // Create default family group if no match found
                        createDefaultFamilyGroup(currentUid)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to query family group: ${e.message}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying family group: ${e.message}")
        }
    }

    private fun createDefaultFamilyGroup(currentUid: String) {
        val fs = firestore ?: return
        val currentFirstName = currentUserFirstName.value ?: "User"
        val spouseUid = spouseUserUid.value
        val spouseFirstName = spouseUserFirstName.value

        val membersMap = mutableMapOf<String, Any>(
            currentUid to mapOf("firstName" to currentFirstName, "exists" to true)
        )
        if (!spouseUid.isNullOrBlank()) {
            membersMap[spouseUid] = mapOf("firstName" to (spouseFirstName ?: "Partner"), "exists" to true)
        }

        val groupData = mapOf(
            "groupId" to "fg_${UUID.randomUUID()}",
            "groupName" to "Family Group of $currentFirstName",
            "members" to membersMap
        )
        fs.collection("family_groups").document("default_family_group").set(groupData, SetOptions.merge())
            .addOnSuccessListener {
                Log.d(TAG, "Created default family group")
            }
    }

    // --- ACCOUNTS DATA INTERACTION (Real-time synced) ---
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getAccounts(): Flow<List<Account>> {
        val fs = firestore
        // Only attach once signed in: Firestore rules reject anonymous reads, and a rejected
        // listener stops for good. startRealtimeListeners() attaches it after sign-in.
        if (fs != null && auth?.currentUser != null && !useFallback.value && !accountsListenerRegistered) {
            accountsListenerRegistered = true
            fs.collection("ChartOfAccounts")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "ChartOfAccounts listener error: ${error.message}.")
                        accountsListenerRegistered = false
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val accounts = snapshot.mapNotNull { doc ->
                            try {
                                doc.toObject(Account::class.java)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        val updatedLocal = localAccounts.value.toMutableList()
                        accounts.forEach { remote ->
                            val idx = updatedLocal.indexOfFirst { it.accountId == remote.accountId }
                            if (idx != -1) {
                                updatedLocal[idx] = remote
                            } else {
                                updatedLocal.add(remote)
                            }
                        }
                        localAccounts.value = updatedLocal
                        accountsLoaded = true
                    }
                }
        }

        val rawAccountsFlow = localAccounts.map { list ->
            list.filter { it.accountType == "ASSET" || it.accountType == "LIABILITY" }
        }

        return combine(rawAccountsFlow, getTransactions()) { rawAccounts, transactions ->
            rawAccounts.map { account ->
                val nonIgnoredTx = transactions.filter { !it.isIgnored && it.status == TransactionStatus.CONFIRMED }
                val debitSum = nonIgnoredTx.filter { it.debitAccountId == account.accountId }.sumOf { it.amount }
                val creditSum = nonIgnoredTx.filter { it.creditAccountId == account.accountId }.sumOf { it.amount }
                
                val isAssetOrLiability = account.accountType == "ASSET" || account.accountType == "LIABILITY"
                val baseOpening = if (isAssetOrLiability) account.openingBalance else 0.0
                
                val currentBalance = if (account.accountType == "LIABILITY") {
                    baseOpening + creditSum - debitSum
                } else {
                    baseOpening + debitSum - creditSum
                }
                
                account.copy(balance = currentBalance)
            }
        }
    }

    suspend fun ensureCategoryForAccount(account: Account) {
        // No-op under unified Chart of Accounts setup
    }

    suspend fun addAccount(account: Account) {
        val list = localAccounts.value.toMutableList()
        if (list.none { it.accountId == account.accountId }) {
            list.add(account)
            localAccounts.value = list
        }
        triggerSmsBackfill(accountId = account.accountId)
        if (useFallback.value || firestore == null) {
            return
        }
        try {
            firestore!!.collection("ChartOfAccounts").document(account.accountId).set(account).addOnFailureListener {
                Log.e(TAG, "Error writing account to remote: ${it.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error: ${e.message}")
        }
    }

    suspend fun updateAccount(account: Account) {
        val list = localAccounts.value.map { if (it.accountId == account.accountId) account else it }
        localAccounts.value = list
        if (useFallback.value || firestore == null) {
            return
        }
        try {
            firestore!!.collection("ChartOfAccounts").document(account.accountId).set(account, SetOptions.merge())
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error: ${e.message}")
        }
    }

    suspend fun deleteAccount(accountId: String) {
        localAccounts.value = localAccounts.value.filter { it.accountId != accountId }
        if (useFallback.value || firestore == null) {
            return
        }
        try {
            firestore!!.collection("ChartOfAccounts").document(accountId).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error: ${e.message}")
        }
    }

    suspend fun getOrCreateFallbackAccount(
        isCreditCard: Boolean,
        ownerUid: String = getCurrentUserId(),
        ownerName: String = currentUserFirstName.value ?: "User"
    ): Account {
        val currentAccounts = localAccounts.value
        val fallbackId = if (isCreditCard) "acc_primary_card" else "acc_primary_bank"
        val existing = currentAccounts.find { it.accountId == fallbackId }
            ?: currentAccounts.find { 
                if (isCreditCard) it.accountType == "LIABILITY" || it.customMainCategory.equals("Credit Cards", ignoreCase = true)
                else it.accountType == "ASSET" || it.customMainCategory.equals("Bank Accounts", ignoreCase = true)
            }
        if (existing != null) {
            return existing
        }

        val newFallback = if (isCreditCard) {
            Account(
                accountId = "acc_primary_card",
                accountName = "Primary Credit Card",
                accountType = "LIABILITY",
                openingBalance = 0.0,
                isSystemDefault = true,
                iconName = "Credit Card",
                ownerUserId = ownerUid,
                ownerFirstName = ownerName
            )
        } else {
            Account(
                accountId = "acc_primary_bank",
                accountName = "Primary Bank Account",
                accountType = "ASSET",
                openingBalance = 0.0,
                isSystemDefault = true,
                iconName = "Bank",
                ownerUserId = ownerUid,
                ownerFirstName = ownerName
            )
        }

        addAccount(newFallback)
        return newFallback
    }

    // --- TRANSACTIONS DATA INTERACTION ---
    fun translateTransactionToLedger(tx: Transaction): Transaction {
        val finalId = if (tx.transactionId.isNotEmpty()) tx.transactionId else tx.id
        val finalDate = if (tx.date != 0L) tx.date else tx.timestamp
        val finalPayee = if (tx.payeeName.isNotEmpty()) tx.payeeName else tx.narration
        
        val entryTypeStr = tx.type.name
        val (debit, credit) = when (tx.type) {
            TransactionType.EXPENSE -> {
                Pair(tx.category.ifEmpty { "Others" }, tx.accountId)
            }
            TransactionType.INCOME -> {
                Pair(tx.accountId, tx.category.ifEmpty { "Salary" })
            }
            TransactionType.TRANSFER -> {
                Pair(tx.targetAccountId ?: "", tx.accountId)
            }
        }
        
        return tx.copy(
            transactionId = finalId,
            id = finalId,
            amount = tx.amount,
            date = finalDate,
            timestamp = finalDate,
            payeeName = finalPayee,
            narration = finalPayee,
            debitAccountId = debit,
            creditAccountId = credit,
            entryType = entryTypeStr,
            isIgnored = tx.isIgnored,
            category = tx.category,
            accountId = tx.accountId,
            targetAccountId = tx.targetAccountId,
            tag = tx.tag
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getTransactions(): Flow<List<Transaction>> {
        val fs = firestore
        // Only attach once signed in: Firestore rules reject anonymous reads, and a rejected
        // listener stops for good. startRealtimeListeners() attaches it after sign-in.
        if (fs != null && auth?.currentUser != null && !useFallback.value && !transactionsListenerRegistered) {
            transactionsListenerRegistered = true
            fs.collection("Transactions")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Transactions listener error: ${error.message}")
                        transactionsListenerRegistered = false
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val items = snapshot.mapNotNull { doc ->
                            try {
                                doc.toObject(Transaction::class.java)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        localTransactions.value = items
                        transactionsLoaded = true
                    }
                }
        }
        
        return localTransactions.map { list ->
            list.map { translateTransactionToLedger(it) }
        }
    }

    // Matchmaker logic for automatic transfer reconciliation (Feature 5)
    private suspend fun matchAndReconcileTransfer(newTx: Transaction): Boolean {
        return false // Unified double-entry system handles transfers in a single document
    }

    private suspend fun processBudgetEnforcement(tx: Transaction) {
        if (tx.type != TransactionType.EXPENSE) return
        
        val category = tx.category.trim()
        val catObj = localAccounts.value.find { it.name.equals(category, ignoreCase = true) || it.id.equals(category, ignoreCase = true) }
        val mainCat = catObj?.mainCategory?.trim() ?: ""
        val subCat = catObj?.subCategory?.trim() ?: category

        val allBudgets = localBudgets.value.toMutableList()
        var modified = false

        for (i in allBudgets.indices) {
            val budget = allBudgets[i]
            val matchesSub = budget.subCategory.isNotBlank() && (budget.subCategory.equals(subCat, ignoreCase = true) || budget.subCategory.equals(category, ignoreCase = true))
            val matchesMain = budget.mainCategory.isNotBlank() && (budget.mainCategory.equals(mainCat, ignoreCase = true) || budget.mainCategory.equals(category, ignoreCase = true)) && budget.subCategory.isBlank()
            val matchesLegacy = budget.category.isNotBlank() && (budget.category.equals(category, ignoreCase = true) || budget.category.equals(mainCat, ignoreCase = true) || budget.category.equals(subCat, ignoreCase = true))

            if (matchesSub || matchesMain || matchesLegacy) {
                val newSpent = budget.currentSpent + tx.amount
                val updatedBudget = budget.copy(currentSpent = newSpent)
                allBudgets[i] = updatedBudget
                modified = true

                if (newSpent > budget.monthlyLimit) {
                    val label = if (budget.subCategory.isNotBlank()) "${budget.mainCategory} › ${budget.subCategory}" else budget.mainCategory.ifBlank { budget.category }
                    if (budget.budgetType == BudgetType.SOFT) {
                        val warnString = "SOFT Limit Warning: $label spent ₹$newSpent exceeds monthly limit ₹${budget.monthlyLimit} (Difference: ₹${newSpent - budget.monthlyLimit})"
                        softBudgetWarnings.value = softBudgetWarnings.value + warnString
                        Log.d("BudgetEnforcement", "Append soft budget alert: $warnString")
                    } else if (budget.budgetType == BudgetType.HARD) {
                        Log.d("BudgetEnforcement", "Triggering local background push notification for Hard Budget category '$label'!")
                        appContext?.let { ctx ->
                            try {
                                triggerLocalNotification(ctx, label, budget.monthlyLimit, newSpent)
                            } catch (e: Exception) {
                                Log.e("BudgetEnforcement", "Notification error: ${e.message}")
                            }
                        }
                    }
                }

                firestore?.let { fs ->
                    try {
                        fs.collection("budgets").document(updatedBudget.budgetId).set(updatedBudget, SetOptions.merge())
                    } catch (e: Exception) {
                        Log.e("FirebaseService", "Firestore budget writing error: ${e.message}")
                    }
                }
            }
        }

        if (modified) {
            localBudgets.value = allBudgets
        }
    }

    private fun triggerLocalNotification(context: Context, category: String, limit: Double, spent: Double) {
        Log.d(TAG, "Posting Push Notification: Hard budget exceeded for $category")
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "budget_alerts_channel"
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Budget Critical Alerts",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies family devices when critical Hard limits are crossed"
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🚨 HARD Limit Violation: $category")
            .setContentText("Total spending (₹$spent) exceeds limit of ₹$limit. Alerts dispatched.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    suspend fun addTransaction(transaction: Transaction) {
        val ledgerTx = translateTransactionToLedger(transaction)
        
        // Enforce write guardrails: verify its date is greater than targeted account's lastReconciledDate (ONLY for assets and liabilities)
        if (ledgerTx.status != TransactionStatus.PENDING_REVIEW) {
            val debitAcc = findAccountImmediate(ledgerTx.debitAccountId)
            if (debitAcc != null && (debitAcc.accountType == "ASSET" || debitAcc.accountType == "LIABILITY")) {
                val lastRec = debitAcc.lastReconciledDate
                if (lastRec != null && ledgerTx.date < lastRec && !isSameDay(ledgerTx.date, lastRec)) {
                    val errorMsg = "Write Blocked: Transaction falls within a closed reconciliation period."
                    Log.e(TAG, errorMsg)
                    throw java.lang.IllegalArgumentException(errorMsg)
                }
            }
            val creditAcc = findAccountImmediate(ledgerTx.creditAccountId)
            if (creditAcc != null && (creditAcc.accountType == "ASSET" || creditAcc.accountType == "LIABILITY")) {
                val lastRec = creditAcc.lastReconciledDate
                if (lastRec != null && ledgerTx.date < lastRec && !isSameDay(ledgerTx.date, lastRec)) {
                    val errorMsg = "Write Blocked: Transaction falls within a closed reconciliation period."
                    Log.e(TAG, errorMsg)
                    throw java.lang.IllegalArgumentException(errorMsg)
                }
            }
        }

        // ALWAYS update local state to ensure instant visual updates in UI
        val list = localTransactions.value.toMutableList()
        if (list.none { it.transactionId == ledgerTx.transactionId }) {
            list.add(ledgerTx)
            localTransactions.value = list
        }
        
        if (ledgerTx.status == TransactionStatus.CONFIRMED) {
            processBudgetEnforcement(ledgerTx)
        }

        if (useFallback.value || firestore == null) {
            return
        }
        try {
            firestore!!.collection("Transactions").document(ledgerTx.transactionId).set(ledgerTx)
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error: ${e.message}")
        }
    }

    suspend fun updateTransaction(transaction: Transaction) {
        val ledgerTx = translateTransactionToLedger(transaction)
        
        // Enforce write guardrails (ONLY for assets and liabilities)
        if (ledgerTx.status != TransactionStatus.PENDING_REVIEW) {
            val debitAcc = findAccountImmediate(ledgerTx.debitAccountId)
            if (debitAcc != null && (debitAcc.accountType == "ASSET" || debitAcc.accountType == "LIABILITY")) {
                val lastRec = debitAcc.lastReconciledDate
                if (lastRec != null && ledgerTx.date < lastRec && !isSameDay(ledgerTx.date, lastRec)) {
                    val errorMsg = "Write Blocked: Transaction falls within a closed reconciliation period."
                    Log.e(TAG, errorMsg)
                    throw java.lang.IllegalArgumentException(errorMsg)
                }
            }
            val creditAcc = findAccountImmediate(ledgerTx.creditAccountId)
            if (creditAcc != null && (creditAcc.accountType == "ASSET" || creditAcc.accountType == "LIABILITY")) {
                val lastRec = creditAcc.lastReconciledDate
                if (lastRec != null && ledgerTx.date < lastRec && !isSameDay(ledgerTx.date, lastRec)) {
                    val errorMsg = "Write Blocked: Transaction falls within a closed reconciliation period."
                    Log.e(TAG, errorMsg)
                    throw java.lang.IllegalArgumentException(errorMsg)
                }
            }
        }

        val updatedList = localTransactions.value.map { if (it.transactionId == ledgerTx.transactionId) ledgerTx else it }
        localTransactions.value = updatedList

        if (useFallback.value || firestore == null) {
            return
        }
        try {
            firestore!!.collection("Transactions").document(ledgerTx.transactionId).set(ledgerTx, SetOptions.merge())
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error: ${e.message}")
        }
    }

    suspend fun deleteTransaction(transactionId: String) {
        val oldTx = localTransactions.value.find { it.transactionId == transactionId || it.id == transactionId } ?: return
        
        // Enforce write guardrails (ONLY for assets and liabilities)
        if (oldTx.status != TransactionStatus.PENDING_REVIEW) {
            val debitAcc = findAccountImmediate(oldTx.debitAccountId)
            if (debitAcc != null && (debitAcc.accountType == "ASSET" || debitAcc.accountType == "LIABILITY")) {
                val lastRec = debitAcc.lastReconciledDate
                if (lastRec != null && oldTx.date < lastRec && !isSameDay(oldTx.date, lastRec)) {
                    val errorMsg = "Write Blocked: Transaction falls within a closed reconciliation period."
                    Log.e(TAG, errorMsg)
                    throw java.lang.IllegalArgumentException(errorMsg)
                }
            }
            val creditAcc = findAccountImmediate(oldTx.creditAccountId)
            if (creditAcc != null && (creditAcc.accountType == "ASSET" || creditAcc.accountType == "LIABILITY")) {
                val lastRec = creditAcc.lastReconciledDate
                if (lastRec != null && oldTx.date < lastRec && !isSameDay(oldTx.date, lastRec)) {
                    val errorMsg = "Write Blocked: Transaction falls within a closed reconciliation period."
                    Log.e(TAG, errorMsg)
                    throw java.lang.IllegalArgumentException(errorMsg)
                }
            }
        }

        val list = localTransactions.value.toMutableList()
        list.removeAll { it.transactionId == transactionId || it.id == transactionId }
        localTransactions.value = list

        if (useFallback.value || firestore == null) {
            return
        }
        try {
            firestore!!.collection("Transactions").document(transactionId).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error: ${e.message}")
        }
    }

    suspend fun deleteAllTransactions() {
        if (useFallback.value || firestore == null) {
            localTransactions.value = emptyList()
            // Reset account balances to default baseline or ignore for testing
            return
        }
        val fs = firestore ?: return
        try {
            val querySnapshot = fs.collection("Transactions").get().awaitTask()
            val dbBatch = fs.batch()
            querySnapshot.documents.forEach { doc ->
                dbBatch.delete(doc.reference)
            }
            dbBatch.commit().awaitTask()
            localTransactions.value = emptyList()
            Log.d(TAG, "All transactions deleted from Firestore successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete all transactions: ${e.message}")
            throw e
        }
    }

    private suspend fun adjustAccountBalance(tx: Transaction, isAddition: Boolean) {
        val multiplier = if (isAddition) 1.0 else -1.0
        
        // Income adds to account, Expense subtracts from it, Transfer moves from src to target
        when (tx.type) {
            TransactionType.INCOME -> {
                val account = findAccountImmediate(tx.accountId)
                if (account != null) {
                    val newBalance = account.balance + (tx.amount * multiplier)
                    updateAccount(account.copy(balance = newBalance))
                }
            }
            TransactionType.EXPENSE -> {
                val account = findAccountImmediate(tx.accountId)
                if (account != null) {
                    val newBalance = if (account.type == AccountType.CREDIT_CARD) {
                        account.balance + (tx.amount * multiplier) // CC dues increase
                    } else if (account.type == AccountType.LOAN) {
                        account.balance + (tx.amount * multiplier) // Loan liability increases
                    } else {
                        account.balance - (tx.amount * multiplier) // Standard accounts decrease
                    }
                    updateAccount(account.copy(balance = newBalance))
                }
            }
            TransactionType.TRANSFER -> {
                val srcAcc = findAccountImmediate(tx.accountId)
                val targetAcc = tx.targetAccountId?.let { findAccountImmediate(it) }
                
                if (srcAcc != null) {
                    val newSrcBalance = if (srcAcc.type == AccountType.CREDIT_CARD) {
                        srcAcc.balance + (tx.amount * multiplier)
                    } else {
                        srcAcc.balance - (tx.amount * multiplier)
                    }
                    updateAccount(srcAcc.copy(balance = newSrcBalance))
                }
                
                if (targetAcc != null) {
                    val newTargetBalance = if (targetAcc.type == AccountType.CREDIT_CARD) {
                        targetAcc.balance - (tx.amount * multiplier) // Paying card dues
                    } else if (targetAcc.type == AccountType.LOAN) {
                        targetAcc.balance + (tx.amount * multiplier) // Paying principal reduces liability (adds to negative balance)
                    } else {
                        targetAcc.balance + (tx.amount * multiplier)
                    }
                    updateAccount(targetAcc.copy(balance = newTargetBalance))
                }
            }
        }
    }

    private fun findAccountImmediate(id: String): Account? {
        return localAccounts.value.find { it.id == id }
    }

    // --- REMINDERS DATA INTERACTION ---
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getReminders(): Flow<List<Reminder>> {
        val fs = firestore
        // Only attach once signed in: Firestore rules reject anonymous reads, and a rejected
        // listener stops for good. startRealtimeListeners() attaches it after sign-in.
        if (fs != null && auth?.currentUser != null && !useFallback.value && !remindersListenerRegistered) {
            remindersListenerRegistered = true
            fs.collection("reminders")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Reminders listener error: ${error.message}")
                        remindersListenerRegistered = false
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val items = snapshot.mapNotNull { doc ->
                            try {
                                doc.toObject(Reminder::class.java)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error deserializing reminder in listener: ${e.message}", e)
                                null
                            }
                        }
                        localReminders.value = items
                    }
                }
        }
        return localReminders
    }

    suspend fun addReminder(reminder: Reminder) {
        val list = localReminders.value.toMutableList()
        if (list.none { it.id == reminder.id }) {
            list.add(reminder)
            localReminders.value = list
        }

        if (useFallback.value || firestore == null) {
            return
        }
        try {
            firestore!!.collection("reminders").document(reminder.id).set(reminder)
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error adding reminder: ${e.message}")
        }
    }

    suspend fun updateReminder(reminder: Reminder) {
        Log.d(TAG, "updateReminder called for: $reminder")
        val list = localReminders.value.map { if (it.id == reminder.id) reminder else it }
        localReminders.value = list
        Log.d(TAG, "updateReminder local state updated. New list size: ${list.size}, matches target paid status: ${list.find { it.id == reminder.id }?.isPaid}")

        if (useFallback.value || firestore == null) {
            Log.d(TAG, "updateReminder: using fallback or firestore is null. Completed offline update.")
            return
        }
        try {
            Log.d(TAG, "updateReminder: sending to Firestore collection 'reminders' document '${reminder.id}'")
            firestore!!.collection("reminders").document(reminder.id).set(reminder, SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "updateReminder: Firestore write SUCCEEDED for ${reminder.id}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "updateReminder: Firestore write FAILED for ${reminder.id}: ${e.message}", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error updating reminder: ${e.message}", e)
        }
    }

    suspend fun deleteReminder(reminderId: String) {
        localReminders.value = localReminders.value.filter { it.id != reminderId }
        if (useFallback.value || firestore == null) {
            return
        }
        try {
            firestore!!.collection("reminders").document(reminderId).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error deleting reminder: ${e.message}", e)
        }
    }

    // --- SENDER WHITELIST INTERACTION ---
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getWhitelistedSenders(): Flow<List<WhitelistedSender>> {
        return useFallback.flatMapLatest { fallback ->
            if (fallback || firestore == null) {
                localSenders
            } else {
                callbackFlow {
                    val listenerRegistration: ListenerRegistration = firestore!!.collection("whitelisted_senders")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w(TAG, "Senders listener error: ${error.message}. Emitting local senders.")
                                trySend(localSenders.value)
                                return@addSnapshotListener
                            }
                            if (snapshot != null) {
                                val items = snapshot.mapNotNull { it.toObject(WhitelistedSender::class.java) }
                                trySend(items)
                            }
                        }
                    awaitClose { listenerRegistration.remove() }
                }
            }
        }
    }

    suspend fun addWhitelistedSender(sender: WhitelistedSender) {
        val list = localSenders.value.toMutableList()
        if (list.none { it.id == sender.id }) {
            list.add(sender)
            localSenders.value = list
        }
        triggerSmsBackfill()
        if (useFallback.value || firestore == null) {
            return
        }
        firestore!!.collection("whitelisted_senders").document(sender.id).set(sender)
    }

    suspend fun deleteWhitelistedSender(id: String) {
        if (useFallback.value || firestore == null) {
            localSenders.value = localSenders.value.filter { it.id != id }
            return
        }
        firestore!!.collection("whitelisted_senders").document(id).delete()
    }

    // --- MANUAL SENDER MAPPINGS INTERACTION ---
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getManualMappings(): Flow<List<ManualMapping>> {
        return useFallback.flatMapLatest { fallback ->
            if (fallback || firestore == null) {
                localMappings
            } else {
                callbackFlow {
                    val listenerRegistration: ListenerRegistration = firestore!!.collection("manual_mappings")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w(TAG, "Mappings listener error: ${error.message}. Emitting local mappings.")
                                trySend(localMappings.value)
                                return@addSnapshotListener
                            }
                            if (snapshot != null) {
                                val items = snapshot.mapNotNull { it.toObject(ManualMapping::class.java) }
                                trySend(items)
                            }
                        }
                    awaitClose { listenerRegistration.remove() }
                }
            }
        }
    }

    suspend fun addManualMapping(mapping: ManualMapping) {
        if (useFallback.value || firestore == null) {
            val list = localMappings.value.toMutableList()
            list.add(mapping)
            localMappings.value = list
            return
        }
        firestore!!.collection("manual_mappings").document(mapping.id).set(mapping)
    }

    suspend fun deleteManualMapping(id: String) {
        if (useFallback.value || firestore == null) {
            localMappings.value = localMappings.value.filter { it.id != id }
            return
        }
        firestore!!.collection("manual_mappings").document(id).delete()
    }

    // --- BUDGETS DATA INTERACTION ---
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getBudgets(): Flow<List<Budget>> {
        return useFallback.flatMapLatest { fallback ->
            if (fallback || firestore == null) {
                localBudgets
            } else {
                callbackFlow {
                    val listenerRegistration = firestore!!.collection("budgets")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w(TAG, "Budgets listener error: ${error.message}. Emitting local budgets.")
                                trySend(localBudgets.value)
                                return@addSnapshotListener
                            }
                            if (snapshot != null) {
                                val items = snapshot.mapNotNull { doc ->
                                    try {
                                        doc.toObject(Budget::class.java)
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                                trySend(items)
                            }
                        }
                    awaitClose { listenerRegistration.remove() }
                }
            }
        }
    }

    suspend fun addBudget(budget: Budget) {
        if (useFallback.value || firestore == null) {
            val list = localBudgets.value.toMutableList()
            list.add(budget)
            localBudgets.value = list
            return
        }
        try {
            firestore!!.collection("budgets").document(budget.budgetId).set(budget)
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error: ${e.message}")
        }
    }

    suspend fun updateBudget(budget: Budget) {
        if (useFallback.value || firestore == null) {
            val list = localBudgets.value.map { if (it.budgetId == budget.budgetId) budget else it }
            localBudgets.value = list
            return
        }
        try {
            firestore!!.collection("budgets").document(budget.budgetId).set(budget, SetOptions.merge())
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error: ${e.message}")
        }
    }

    suspend fun deleteBudget(budgetId: String) {
        if (useFallback.value || firestore == null) {
            localBudgets.value = localBudgets.value.filter { it.budgetId != budgetId }
            return
        }
        try {
            firestore!!.collection("budgets").document(budgetId).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error: ${e.message}")
        }
    }

    // --- CATEGORIES (Feature: Dynamic Category Management) ---
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getCategories(): Flow<List<Account>> {
        return useFallback.flatMapLatest { fallback ->
            if (fallback || firestore == null) {
                localAccounts.map { list -> list }
            } else {
                callbackFlow {
                    val listenerRegistration: ListenerRegistration = firestore!!.collection("ChartOfAccounts")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w(TAG, "ChartOfAccounts listener error in getCategories: ${error.message}.")
                                trySend(localAccounts.value)
                                return@addSnapshotListener
                            }
                            if (snapshot != null) {
                                val accountsList = snapshot.mapNotNull { doc ->
                                    try {
                                        doc.toObject(Account::class.java)
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                                trySend(accountsList)
                            }
                        }
                    awaitClose { listenerRegistration.remove() }
                }
            }
        }
    }

    suspend fun addCategory(category: Account) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val list = localAccounts.value.toMutableList()
        if (list.none { it.name.equals(category.name, ignoreCase = true) }) {
            list.add(category)
            localAccounts.value = list
        }
        
        if (useFallback.value || firestore == null) {
            return@withContext
        }
        try {
            val querySnapshot = firestore!!.collection("ChartOfAccounts").whereEqualTo("accountName", category.name).get()
            val matchingDocs = com.google.android.gms.tasks.Tasks.await(querySnapshot)
            if (matchingDocs.isEmpty) {
                firestore!!.collection("ChartOfAccounts").document(category.accountId).set(category)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error adding Category (Account): ${e.message}")
            try {
                firestore!!.collection("ChartOfAccounts").document(category.accountId).set(category)
            } catch (ex: Exception) {
                Log.e(TAG, "Firestore nested error: ${ex.message}")
            }
        }
    }

    suspend fun updateCategory(category: Account) {
        if (useFallback.value || firestore == null) {
            val list = localAccounts.value.map { if (it.accountId == category.accountId) category else it }
            localAccounts.value = list
            return
        }
        try {
            firestore!!.collection("ChartOfAccounts").document(category.accountId).set(category, SetOptions.merge())
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error updating Category (Account): ${e.message}")
        }
    }

    suspend fun deleteCategory(categoryId: String) {
        if (useFallback.value || firestore == null) {
            localAccounts.value = localAccounts.value.filter { it.accountId != categoryId }
            return
        }
        try {
            firestore!!.collection("ChartOfAccounts").document(categoryId).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error deleting Category (Account): ${e.message}")
        }
    }

    // --- MERCHANT RULES (Feature: Smart Rules Dashboard) ---
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getMerchantRules(): Flow<List<MerchantRule>> {
        return useFallback.flatMapLatest { fallback ->
            if (fallback || firestore == null) {
                localMerchantRules
            } else {
                callbackFlow {
                    val listenerRegistration: ListenerRegistration = firestore!!.collection("merchant_rules")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w(TAG, "Merchant rules listener error: ${error.message}. Emitting local merchant rules.")
                                trySend(localMerchantRules.value)
                                return@addSnapshotListener
                            }
                            if (snapshot != null) {
                                val rules = snapshot.mapNotNull { doc ->
                                    try {
                                        doc.toObject(MerchantRule::class.java)
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                                if (rules.isEmpty()) {
                                    val defaults = listOf(
                                        MerchantRule(ruleId = "rule_001", keyword = "UBER", assignedCategory = "Travel"),
                                        MerchantRule(ruleId = "rule_002", keyword = "ZOMATO", assignedCategory = "Food")
                                    )
                                    defaults.forEach { d ->
                                        firestore!!.collection("merchant_rules").document(d.ruleId).set(d)
                                    }
                                    trySend(defaults)
                                } else {
                                    trySend(rules)
                                }
                            }
                        }
                    awaitClose { listenerRegistration.remove() }
                }
            }
        }
    }

    suspend fun addMerchantRule(rule: MerchantRule) {
        if (useFallback.value || firestore == null) {
            val list = localMerchantRules.value.toMutableList()
            list.add(rule)
            localMerchantRules.value = list
            return
        }
        try {
            firestore!!.collection("merchant_rules").document(rule.ruleId).set(rule)
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error adding MerchantRule: ${e.message}")
        }
    }

    suspend fun deleteMerchantRule(ruleId: String) {
        if (useFallback.value || firestore == null) {
            localMerchantRules.value = localMerchantRules.value.filter { it.ruleId != ruleId }
            return
        }
        try {
            firestore!!.collection("merchant_rules").document(ruleId).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error deleting MerchantRule: ${e.message}")
        }
    }

    // --- PRIVACY KEYWORDS ---
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getPrivacyKeywords(): Flow<List<PrivacyKeyword>> {
        return useFallback.flatMapLatest { fallback ->
            if (fallback || firestore == null) {
                localPrivacyKeywords
            } else {
                callbackFlow {
                    val listenerRegistration: ListenerRegistration = firestore!!.collection("privacy_keywords")
                        .addSnapshotListener { snapshot, error ->
                            if (error != null) {
                                Log.w(TAG, "Privacy keywords listener error: ${error.message}. Emitting local defaults.")
                                trySend(localPrivacyKeywords.value)
                                return@addSnapshotListener
                            }
                            if (snapshot != null) {
                                val items = snapshot.mapNotNull { it.toObject(PrivacyKeyword::class.java) }
                                if (items.isEmpty()) {
                                    // Seed Firestore automatically if empty
                                    serviceScope.launch {
                                        localPrivacyKeywords.value.forEach { defaultKeyword ->
                                            firestore!!.collection("privacy_keywords").document(defaultKeyword.id).set(defaultKeyword)
                                        }
                                    }
                                    trySend(localPrivacyKeywords.value)
                                } else {
                                    trySend(items)
                                }
                            }
                        }
                    awaitClose { listenerRegistration.remove() }
                }
            }
        }
    }

    suspend fun addPrivacyKeyword(keyword: PrivacyKeyword) {
        if (useFallback.value || firestore == null) {
            val list = localPrivacyKeywords.value.toMutableList()
            list.add(keyword)
            localPrivacyKeywords.value = list
            return
        }
        try {
            firestore!!.collection("privacy_keywords").document(keyword.id).set(keyword)
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error adding PrivacyKeyword: ${e.message}")
        }
    }

    suspend fun deletePrivacyKeyword(id: String) {
        if (useFallback.value || firestore == null) {
            localPrivacyKeywords.value = localPrivacyKeywords.value.filter { it.id != id }
            return
        }
        try {
            firestore!!.collection("privacy_keywords").document(id).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Firestore error deleting PrivacyKeyword: ${e.message}")
        }
    }
}
