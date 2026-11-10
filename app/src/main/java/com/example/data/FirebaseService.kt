package com.example.data

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.domain.LedgerRules
import com.example.sms.SmsBackfillWorker
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An invitation to join someone's household, keyed by the invitee's email. */
data class HouseholdInvite(val householdId: String, val householdName: String, val invitedByName: String) {
    companion object {
        fun from(data: Map<String, Any>): HouseholdInvite? {
            val hid = data["householdId"] as? String ?: return null
            return HouseholdInvite(hid, data["householdName"] as? String ?: "", data["invitedByName"] as? String ?: "")
        }
    }
}

enum class AuthState {
    LOADING,
    UNAUTHENTICATED,
    // Signed in, but not yet part of a household: create one or accept a partner's invite.
    NEEDS_HOUSEHOLD,
    AUTHORIZED,
    DENIED
}

/**
 * Firebase access for the app.
 *
 * Data layout (see firestore.rules):
 *   users/{uid}                          -> { userId, email, firstName, householdId }
 *   households/{householdId}             -> { householdId, name, ownerUid, memberUids, members, createdAt }
 *   households/{householdId}/accounts    -> chart of accounts: bank/card/loan accounts and categories
 *   households/{householdId}/transactions
 *   households/{householdId}/budgets
 *   households/{householdId}/reminders
 *   households/{householdId}/reserves     -> money set aside, shared by the household
 *   households/{householdId}/smsSenders
 *   households/{householdId}/merchantRules
 *   households/{householdId}/privacyKeywords
 *   invites/{email}                      -> { email, householdId, householdName, invitedByUid, invitedByName, createdAt }
 *
 * Every household collection is mirrored into an in-memory StateFlow by a snapshot listener,
 * so the UI and the SMS pipeline read local state and writes show up immediately.
 */
object FirebaseService {
    private const val TAG = "FirebaseService"

    private const val COL_ACCOUNTS = "accounts"
    private const val COL_TRANSACTIONS = "transactions"
    private const val COL_BUDGETS = "budgets"
    private const val COL_REMINDERS = "reminders"
    private const val COL_RESERVES = "reserves"
    private const val COL_SENDERS = "smsSenders"
    private const val COL_RULES = "merchantRules"
    private const val COL_KEYWORDS = "privacyKeywords"
    // The household document itself (names and partner), loaded alongside the collections.
    private const val HOUSEHOLD_DOC = "household"
    // Transactions older than the live window, read once per session (see loadOlderTransactions).
    private const val OLDER_TRANSACTIONS = "olderTransactions"
    /** Transactions from this many months back (from the 1st) are kept live; older ones are read once. */
    private const val LIVE_MONTHS = 13
    private val ALL_COLLECTIONS = setOf(HOUSEHOLD_DOC, OLDER_TRANSACTIONS, COL_ACCOUNTS, COL_TRANSACTIONS, COL_BUDGETS, COL_REMINDERS, COL_RESERVES, COL_SENDERS, COL_RULES, COL_KEYWORDS)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var firestore: FirebaseFirestore? = null
    private var auth: FirebaseAuth? = null
    private var appContext: Context? = null

    // Kept for the screens that still read it; there is no offline-only mode any more.
    val useFallback = MutableStateFlow(false)
    val authStateVal = MutableStateFlow(AuthState.LOADING)

    val currentUserUid = MutableStateFlow<String?>(null)
    val currentUserFirstName = MutableStateFlow<String?>(null)
    val spouseUserUid = MutableStateFlow<String?>(null)
    val spouseUserFirstName = MutableStateFlow<String?>(null)
    val householdName = MutableStateFlow<String?>(null)
    val pendingInvite = MutableStateFlow<HouseholdInvite?>(null)
    // Everyone in the household as (first name, email), you first.
    val householdMembers = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    private val householdId = MutableStateFlow<String?>(null)
    private var userEmail = ""
    private var userName = ""

    // --- Local mirrors of the household collections ---
    private val localAccounts = MutableStateFlow<List<Account>>(emptyList())
    // Every transaction: the live recent window merged with the older history.
    private val localTransactions = MutableStateFlow<List<Transaction>>(emptyList())
    private val recentTransactions = MutableStateFlow<List<Transaction>>(emptyList())
    private val olderTransactions = MutableStateFlow<List<Transaction>>(emptyList())
    // Transactions dated before this are not in the live window.
    private var liveCutoff = 0L
    private val localReminders = MutableStateFlow<List<Reminder>>(emptyList())
    private val localReserves = MutableStateFlow<List<Reserve>>(emptyList())
    private val localSenders = MutableStateFlow<List<WhitelistedSender>>(emptyList())
    private val localBudgets = MutableStateFlow<List<Budget>>(emptyList())
    private val localMerchantRules = MutableStateFlow<List<MerchantRule>>(emptyList())
    private val localPrivacyKeywords = MutableStateFlow<List<PrivacyKeyword>>(emptyList())

    private val loadedCollections = MutableStateFlow<Set<String>>(emptySet())
    private var listeners: List<ListenerRegistration> = emptyList()

    // ---------------------------------------------------------------------------------------
    // Setup and session
    // ---------------------------------------------------------------------------------------

    /** Safe to call repeatedly (activity, SMS worker, backfill worker): sets up once per process. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (firestore != null && auth != null) return
        try {
            // Configuration comes from app/google-services.json via the google-services plugin.
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            // Proves requests come from this app on a registered phone (see AppCheckSetup).
            AppCheckSetup.install()
            firestore = FirebaseFirestore.getInstance()
            auth = FirebaseAuth.getInstance()
            restoreSession()
        } catch (e: Exception) {
            Log.e(TAG, "Firebase setup error: ${e.message}")
            authStateVal.value = AuthState.UNAUTHENTICATED
        }
    }

    private fun restoreSession() {
        serviceScope.launch {
            val user = auth?.currentUser
            if (user == null) {
                authStateVal.value = AuthState.UNAUTHENTICATED
                return@launch
            }
            if (!isEmailVerified(user)) {
                logout()
                return@launch
            }
            try {
                completeSignIn(user)
            } catch (e: Exception) {
                Log.e(TAG, "Could not restore session: ${e.message}")
                authStateVal.value = AuthState.UNAUTHENTICATED
            }
        }
    }

    fun getFirebaseAuth(): FirebaseAuth? = auth
    fun hasSignedInUser(): Boolean = auth?.currentUser != null
    fun getCurrentUserEmail(): String = auth?.currentUser?.email ?: userEmail
    fun getCurrentUserDisplayName(): String = auth?.currentUser?.displayName ?: userName
    fun getCurrentUserId(): String = auth?.currentUser?.uid ?: currentUserUid.value ?: "user_default"

    // Sign-in is Google only. Firestore rules also require a verified email, which Google accounts have.
    private fun isEmailVerified(user: FirebaseUser): Boolean =
        user.isEmailVerified || user.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }

    /** Google sign-in: exchanges the Google ID token for a Firebase Auth session. */
    suspend fun signInWithGoogleIdToken(idToken: String) {
        val fbAuth = auth ?: throw Exception("Cannot connect to server: Firebase Authentication is unavailable.")
        val user = fbAuth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).awaitTask().user
            ?: throw Exception("Google sign-in succeeded but no Firebase user was returned.")
        completeSignIn(user)
    }

    /**
     * After Firebase Auth succeeds: load the user's household, or join one they were invited to.
     * Ends in AUTHORIZED, or NEEDS_HOUSEHOLD when they have neither.
     */
    private suspend fun completeSignIn(user: FirebaseUser) {
        val fs = firestore ?: throw Exception("Firestore is unavailable.")
        val email = (user.email ?: "").lowercase().trim()
        val displayName = user.displayName ?: email.substringBefore("@")
        currentUserUid.value = user.uid
        currentUserFirstName.value = displayName.split(" ").firstOrNull()?.ifBlank { null } ?: "User"
        userEmail = email
        userName = displayName

        val profile = fs.collection("users").document(user.uid).get().awaitTask()
        val savedHousehold = profile.getString("householdId")
        if (!savedHousehold.isNullOrBlank() && isMemberOf(savedHousehold, user.uid)) {
            openHousehold(savedHousehold)
            return
        }

        // Invites are never accepted silently: the setup screen shows who invited you and asks.
        pendingInvite.value = findInvite(email)?.let { HouseholdInvite.from(it) }
        authStateVal.value = AuthState.NEEDS_HOUSEHOLD
    }

    private suspend fun isMemberOf(hid: String, uid: String): Boolean = try {
        val doc = firestore!!.collection("households").document(hid).get().awaitTask()
        (doc.get("memberUids") as? List<*>)?.contains(uid) == true
    } catch (e: Exception) {
        // Permission denied means we are no longer a member.
        false
    }

    private suspend fun findInvite(email: String): Map<String, Any>? = try {
        if (email.isBlank()) null
        else firestore!!.collection("invites").document(email).get().awaitTask().data
    } catch (e: Exception) {
        Log.w(TAG, "Invite lookup failed: ${e.message}")
        null
    }

    /** Creates a new household with this user as its owner and fills in the default setup. */
    suspend fun createHousehold(name: String) {
        val fs = firestore ?: throw Exception("Firestore is unavailable.")
        val user = auth?.currentUser ?: throw Exception("Please sign in first.")
        val ref = fs.collection("households").document()
        val firstName = currentUserFirstName.value ?: "User"
        val household = mapOf(
            "householdId" to ref.id,
            "name" to name.trim().ifBlank { "$firstName's family" },
            "ownerUid" to user.uid,
            "memberUids" to listOf(user.uid),
            "members" to mapOf(user.uid to mapOf("firstName" to firstName, "email" to userEmail)),
            "createdAt" to System.currentTimeMillis()
        )
        fs.batch()
            .set(ref, household)
            .set(fs.collection("users").document(user.uid), userProfile(user.uid, ref.id), SetOptions.merge())
            .commit().awaitTask()
        // Membership must exist before the rules allow writes inside the household.
        seedDefaults(ref.id)
        openHousehold(ref.id)
    }

    /** Looks for an invite again (the "Check for invite" button on the setup screen). */
    suspend fun checkForInvite(): Boolean {
        pendingInvite.value = findInvite(userEmail)?.let { HouseholdInvite.from(it) }
        return pendingInvite.value != null
    }

    /** Turns down the pending invite and removes it. */
    suspend fun declineInvite() {
        firestore?.collection("invites")?.document(userEmail)?.delete()?.awaitTask()
        pendingInvite.value = null
    }

    /** Joins the household from the pending invite, after the user confirmed it. */
    suspend fun acceptInvite() {
        val fs = firestore ?: throw Exception("Firestore is unavailable.")
        val user = auth?.currentUser ?: throw Exception("Please sign in first.")
        val hid = pendingInvite.value?.householdId ?: throw Exception("There is no invite to accept.")
        val firstName = currentUserFirstName.value ?: "User"
        fs.collection("households").document(hid).update(
            mapOf(
                "memberUids" to FieldValue.arrayUnion(user.uid),
                "members.${user.uid}" to mapOf("firstName" to firstName, "email" to userEmail)
            )
        ).awaitTask()
        fs.collection("users").document(user.uid).set(userProfile(user.uid, hid), SetOptions.merge()).awaitTask()
        try {
            fs.collection("invites").document(userEmail).delete().awaitTask()
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove used invite: ${e.message}")
        }
        pendingInvite.value = null
        openHousehold(hid)
    }

    private fun userProfile(uid: String, hid: String) = mapOf(
        "userId" to uid,
        "email" to userEmail,
        "firstName" to (currentUserFirstName.value ?: "User"),
        "householdId" to hid
    )

    /** Lets the partner join: they sign in with this email and are added automatically. */
    suspend fun invitePartner(email: String) {
        val fs = firestore ?: throw Exception("Firestore is unavailable.")
        val hid = householdId.value ?: throw Exception("Create your household first.")
        val clean = email.lowercase().trim()
        require(clean.contains("@")) { "Enter a valid email address." }
        fs.collection("invites").document(clean).set(
            mapOf(
                "email" to clean,
                "householdId" to hid,
                "householdName" to (householdName.value ?: ""),
                "invitedByUid" to getCurrentUserId(),
                "invitedByName" to (currentUserFirstName.value ?: ""),
                "createdAt" to System.currentTimeMillis()
            )
        ).awaitTask()
    }

    /** Renames the household for both members. */
    suspend fun renameHousehold(name: String) {
        val hid = householdId.value ?: throw Exception("No household is open.")
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Enter a name." }
        firestore!!.collection("households").document(hid).update("name", clean).awaitTask()
    }

    private fun openHousehold(hid: String) {
        householdId.value = hid
        attachListeners(hid)
        authStateVal.value = AuthState.AUTHORIZED
    }

    fun logout() {
        detachListeners()
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
        householdId.value = null
        householdName.value = null
        householdMembers.value = emptyList()
        pendingInvite.value = null
        currentUserUid.value = null
        currentUserFirstName.value = null
        spouseUserUid.value = null
        spouseUserFirstName.value = null
        userEmail = ""
        userName = ""
        localAccounts.value = emptyList()
        localTransactions.value = emptyList()
        recentTransactions.value = emptyList()
        olderTransactions.value = emptyList()
        localReminders.value = emptyList()
        localReserves.value = emptyList()
        localSenders.value = emptyList()
        localBudgets.value = emptyList()
        localMerchantRules.value = emptyList()
        localPrivacyKeywords.value = emptyList()
        authStateVal.value = AuthState.UNAUTHENTICATED
    }

    // ---------------------------------------------------------------------------------------
    // Listeners
    // ---------------------------------------------------------------------------------------

    private fun col(name: String): CollectionReference? {
        val hid = householdId.value ?: return null
        return firestore?.collection("households")?.document(hid)?.collection(name)
    }

    private fun attachListeners(hid: String) {
        detachListeners()
        val fs = firestore ?: return
        val base = fs.collection("households").document(hid)
        loadedCollections.value = emptySet()
        val liveCutoff = liveWindowStart(System.currentTimeMillis())
        this.liveCutoff = liveCutoff

        fun <T> listen(
            name: String, type: Class<T>, sink: MutableStateFlow<List<T>>,
            query: com.google.firebase.firestore.Query = base.collection(name),
            onUpdate: () -> Unit = {}
        ): ListenerRegistration =
            query.addSnapshotListener { snapshot: QuerySnapshot?, error ->
                if (error != null) {
                    Log.w(TAG, "$name listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    sink.value = snapshot.documents.mapNotNull { doc ->
                        try { doc.toObject(type) } catch (e: Exception) { null }
                    }
                    onUpdate()
                    loadedCollections.value = loadedCollections.value + name
                }
            }

        val householdListener = base.addSnapshotListener { doc, error ->
            if (error != null || doc == null || !doc.exists()) return@addSnapshotListener
            householdName.value = doc.getString("name")
            val myUid = auth?.currentUser?.uid
            val members = doc.get("members") as? Map<*, *> ?: emptyMap<String, Any>()
            val partner = members.keys.map { it.toString() }.firstOrNull { it != myUid }
            spouseUserUid.value = partner
            householdMembers.value = members.entries
                .sortedBy { if (it.key == myUid) 0 else 1 }
                .map { (_, v) -> val m = v as? Map<*, *>; Pair(m?.get("firstName") as? String ?: "", m?.get("email") as? String ?: "") }
            spouseUserFirstName.value = partner?.let { (members[it] as? Map<*, *>)?.get("firstName") as? String } ?: null
            loadedCollections.value = loadedCollections.value + HOUSEHOLD_DOC
        }

        loadOlderTransactions(hid, liveCutoff)

        listeners = listOf(
            householdListener,
            listen(COL_ACCOUNTS, Account::class.java, localAccounts),
            // Only the recent window is live: a fresh listener re-reads every matching document,
            // so years of history would eat into the free plan's daily reads on every app start.
            listen(
                COL_TRANSACTIONS, Transaction::class.java, recentTransactions,
                base.collection(COL_TRANSACTIONS).whereGreaterThanOrEqualTo("date", liveCutoff),
                onUpdate = ::remergeTransactions
            ),
            listen(COL_BUDGETS, Budget::class.java, localBudgets),
            listen(COL_REMINDERS, Reminder::class.java, localReminders),
            listen(COL_RESERVES, Reserve::class.java, localReserves),
            listen(COL_SENDERS, WhitelistedSender::class.java, localSenders),
            listen(COL_RULES, MerchantRule::class.java, localMerchantRules),
            listen(COL_KEYWORDS, PrivacyKeyword::class.java, localPrivacyKeywords)
        )
    }

    /** The 1st of the month [LIVE_MONTHS] months ago. */
    private fun liveWindowStart(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.MONTH, -LIVE_MONTHS)
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * History before [cutoff] rarely changes, so it is read from the phone's own cache (free)
     * and refreshed from the server at most once a day (to pick up the partner's rare edits).
     */
    private fun loadOlderTransactions(hid: String, cutoff: Long) {
        val fs = firestore ?: return
        val query = fs.collection("households").document(hid).collection(COL_TRANSACTIONS).whereLessThan("date", cutoff)
        val prefs = appContext?.getSharedPreferences("transaction_cache", Context.MODE_PRIVATE)
        val key = "older_refreshed_$hid"
        serviceScope.launch {
            val now = System.currentTimeMillis()
            val recentlyRefreshed = now - (prefs?.getLong(key, 0L) ?: 0L) < 24L * 60 * 60 * 1000
            val snapshot = (if (recentlyRefreshed) {
                try { query.get(com.google.firebase.firestore.Source.CACHE).awaitTask() } catch (e: Exception) { null }
            } else null) ?: try {
                query.get(com.google.firebase.firestore.Source.SERVER).awaitTask().also { prefs?.edit()?.putLong(key, now)?.apply() }
            } catch (e: Exception) {
                Log.w(TAG, "Older transactions not refreshed: ${e.message}")
                try { query.get(com.google.firebase.firestore.Source.CACHE).awaitTask() } catch (e2: Exception) { null }
            }
            if (householdId.value != hid) return@launch
            olderTransactions.value = snapshot?.documents?.mapNotNull { doc ->
                try { doc.toObject(Transaction::class.java) } catch (e: Exception) { null }
            } ?: emptyList()
            remergeTransactions()
            loadedCollections.value = loadedCollections.value + OLDER_TRANSACTIONS
        }
    }

    private fun detachListeners() {
        listeners.forEach { it.remove() }
        listeners = emptyList()
        loadedCollections.value = emptySet()
    }

    /**
     * Waits until the household is open and every collection has delivered its first snapshot,
     * so SMS processing in a freshly started background process sees real data.
     * Returns false when there is no signed-in household member (processing should wait).
     */
    suspend fun ensureCoreDataLoaded(): Boolean {
        if (auth?.currentUser == null) return false
        val ready = withTimeoutOrNull(20_000) {
            householdId.first { it != null }
            loadedCollections.first { it.containsAll(ALL_COLLECTIONS) }
            true
        }
        if (ready != true) Log.w(TAG, "Household data not ready; deferring.")
        return ready == true
    }

    // ---------------------------------------------------------------------------------------
    // Defaults for a new household
    // ---------------------------------------------------------------------------------------

    private suspend fun seedDefaults(hid: String) {
        val fs = firestore ?: return
        val base = fs.collection("households").document(hid)
        val batch = fs.batch()
        defaultAccounts().forEach { batch.set(base.collection(COL_ACCOUNTS).document(it.accountId), it) }
        defaultSenders().forEach { batch.set(base.collection(COL_SENDERS).document(it.id), it) }
        defaultMerchantRules().forEach { batch.set(base.collection(COL_RULES).document(it.ruleId), it) }
        defaultPrivacyKeywords().forEach { batch.set(base.collection(COL_KEYWORDS).document(it.id), it) }
        batch.commit().awaitTask()
    }

    private fun defaultAccounts(): List<Account> = listOf(
        Account(accountId = "acc_primary_bank", accountName = "Primary Bank Account", accountType = "ASSET", isSystemDefault = true, iconName = "Bank", ownerUserId = "SHARED", customMainCategory = "Bank Accounts"),
        Account(accountId = "acc_cash_wallet", accountName = "Cash / Wallet", accountType = "ASSET", isSystemDefault = true, iconName = "Cash", ownerUserId = "SHARED", customMainCategory = "Cash"),
        Account(accountId = "acc_primary_card", accountName = "Primary Credit Card", accountType = "LIABILITY", isSystemDefault = true, iconName = "Credit Card", ownerUserId = "SHARED", customMainCategory = "Credit Cards"),
        Account(accountId = "Salary", accountName = "Salary", accountType = "INCOME", isSystemDefault = true, iconName = "Salary"),
        Account(accountId = "Refund", accountName = "Refund", accountType = "INCOME", isSystemDefault = true, iconName = "Refund"),
        Account(accountId = "Food", accountName = "Food", accountType = "EXPENSE", isSystemDefault = true, iconName = "Food"),
        Account(accountId = "Groceries", accountName = "Groceries", accountType = "EXPENSE", isSystemDefault = true, iconName = "Groceries"),
        Account(accountId = "Utilities", accountName = "Utilities", accountType = "EXPENSE", isSystemDefault = true, iconName = "Utilities"),
        Account(accountId = "Travel", accountName = "Travel", accountType = "EXPENSE", isSystemDefault = true, iconName = "Travel"),
        Account(accountId = "Others", accountName = "Others", accountType = "EXPENSE", isSystemDefault = true, iconName = "Others")
    )

    // Sender IDs as they appear in bank SMS headers (e.g. "AX-HDFCBK"); matched as a whole segment.
    private fun defaultSenders() = listOf(
        WhitelistedSender("send_001", "HDFCBK", "HDFC Bank"),
        WhitelistedSender("send_002", "ICICIB", "ICICI Bank"),
        WhitelistedSender("send_003", "SBIINB", "SBI"),
        WhitelistedSender("send_004", "SBIUPI", "SBI UPI"),
        WhitelistedSender("send_005", "ATMSBI", "SBI ATM/Card"),
        WhitelistedSender("send_006", "AXISBK", "Axis Bank"),
        WhitelistedSender("send_007", "KOTAKB", "Kotak Bank")
    )

    private fun defaultMerchantRules() = listOf(
        MerchantRule(ruleId = "rule_001", keyword = "UBER", assignedCategory = "Travel"),
        MerchantRule(ruleId = "rule_002", keyword = "ZOMATO", assignedCategory = "Food")
    )

    private fun defaultPrivacyKeywords() = listOf(
        PrivacyKeyword("priv_001", "OTP"),
        PrivacyKeyword("priv_002", "One Time Password"),
        PrivacyKeyword("priv_003", "PIN"),
        PrivacyKeyword("priv_004", "Password"),
        PrivacyKeyword("priv_005", "Login verification")
    )

    // ---------------------------------------------------------------------------------------
    // Small write helpers
    // ---------------------------------------------------------------------------------------

    private fun write(collection: String, id: String, data: Any, merge: Boolean = false) {
        val ref = col(collection)?.document(id)
        if (ref == null) {
            Log.w(TAG, "No household open; '$collection/$id' was not saved.")
            return
        }
        val task = if (merge) ref.set(data, SetOptions.merge()) else ref.set(data)
        task.addOnFailureListener { Log.e(TAG, "Write to $collection failed: ${it.message}") }
    }

    private fun remove(collection: String, id: String) {
        col(collection)?.document(id)?.delete()
            ?.addOnFailureListener { Log.e(TAG, "Delete in $collection failed: ${it.message}") }
    }

    private fun <T> upsert(flow: MutableStateFlow<List<T>>, item: T, sameId: (T) -> Boolean) {
        val list = flow.value
        flow.value = if (list.any(sameId)) list.map { if (sameId(it)) item else it } else list + item
    }

    // ---------------------------------------------------------------------------------------
    // Accounts (chart of accounts: real accounts and categories)
    // ---------------------------------------------------------------------------------------

    /** Bank, card and loan accounts with their ledger balances, worked out in one pass. */
    fun getAccounts(): Flow<List<Account>> = combine(localAccounts, localTransactions) { all, transactions ->
        val accounts = all.filter { it.accountType == "ASSET" || it.accountType == "LIABILITY" }
        val balances = LedgerRules.balances(accounts, transactions)
        accounts.map { it.copy(balance = balances[it.accountId] ?: 0.0) }
    }.flowOn(Dispatchers.Default)

    /** Every chart-of-accounts entry, including income and expense categories. */
    fun getCategories(): Flow<List<Account>> = localAccounts

    suspend fun addAccount(account: Account) {
        upsert(localAccounts, account) { it.accountId == account.accountId }
        write(COL_ACCOUNTS, account.accountId, account)
        triggerSmsBackfill(accountId = account.accountId)
    }

    suspend fun updateAccount(account: Account) {
        upsert(localAccounts, account) { it.accountId == account.accountId }
        write(COL_ACCOUNTS, account.accountId, account, merge = true)
    }

    suspend fun deleteAccount(accountId: String) {
        localAccounts.value = localAccounts.value.filter { it.accountId != accountId }
        remove(COL_ACCOUNTS, accountId)
    }

    suspend fun addCategory(category: Account) {
        if (localAccounts.value.any { it.name.equals(category.name, ignoreCase = true) }) return
        upsert(localAccounts, category) { it.accountId == category.accountId }
        write(COL_ACCOUNTS, category.accountId, category)
    }

    /**
     * Saves a category or account. Transactions, budgets and rules refer to a category by its
     * name, so when an income or expense category is renamed they are moved to the new name.
     */
    suspend fun updateCategory(category: Account) {
        val before = findAccountImmediate(category.accountId)
        updateAccount(category)
        val oldName = before?.accountName ?: return
        val newName = category.accountName
        val isCategory = category.accountType == "INCOME" || category.accountType == "EXPENSE"
        if (!isCategory || oldName == newName || oldName.isBlank() || newName.isBlank()) return
        // Another category still using the old name keeps its transactions.
        if (localAccounts.value.any { it.accountId != category.accountId && it.accountName.equals(oldName, ignoreCase = true) }) return

        fun same(s: String) = s.trim().equals(oldName, ignoreCase = true)
        val txs = localTransactions.value.filter { it.type != TransactionType.TRANSFER && same(it.category) }
            .map { LedgerRules.toLedger(it.copy(category = newName)) }
        val budgets = localBudgets.value.filter { same(it.category) || same(it.subCategory) }.map {
            it.copy(category = if (same(it.category)) newName else it.category, subCategory = if (same(it.subCategory)) newName else it.subCategory)
        }
        val rules = localMerchantRules.value.filter { same(it.assignedCategory) }.map { it.copy(assignedCategory = newName) }

        val txIds = txs.associateBy { it.transactionId }
        localTransactions.value = localTransactions.value.map { txIds[it.transactionId] ?: it }
        olderTransactions.value = olderTransactions.value.map { txIds[it.transactionId] ?: it }
        recentTransactions.value = recentTransactions.value.map { txIds[it.transactionId] ?: it }
        budgets.forEach { b -> upsert(localBudgets, b) { it.budgetId == b.budgetId } }
        rules.forEach { r -> upsert(localMerchantRules, r) { it.ruleId == r.ruleId } }

        val fs = firestore ?: return
        val writes: List<Pair<com.google.firebase.firestore.DocumentReference, Any>> =
            txs.mapNotNull { t -> col(COL_TRANSACTIONS)?.document(t.transactionId)?.let { it to t } } +
                budgets.mapNotNull { b -> col(COL_BUDGETS)?.document(b.budgetId)?.let { it to b } } +
                rules.mapNotNull { r -> col(COL_RULES)?.document(r.ruleId)?.let { it to r } }
        // Firestore takes at most 500 writes in one batch.
        writes.chunked(400).forEach { chunk ->
            val batch = fs.batch()
            chunk.forEach { (ref, data) -> batch.set(ref, data, SetOptions.merge()) }
            batch.commit().addOnFailureListener { Log.e(TAG, "Category rename not fully saved: ${it.message}") }
        }
    }

    suspend fun deleteCategory(categoryId: String) = deleteAccount(categoryId)

    suspend fun getOrCreateFallbackAccount(
        isCreditCard: Boolean,
        ownerUid: String = getCurrentUserId(),
        ownerName: String = currentUserFirstName.value ?: "User"
    ): Account {
        val accounts = localAccounts.value
        val fallbackId = if (isCreditCard) "acc_primary_card" else "acc_primary_bank"
        accounts.find { it.accountId == fallbackId }?.let { return it }
        accounts.find {
            if (isCreditCard) it.accountType == "LIABILITY" && it.type == AccountType.CREDIT_CARD
            else it.accountType == "ASSET"
        }?.let { return it }

        val created = Account(
            accountId = fallbackId,
            accountName = if (isCreditCard) "Primary Credit Card" else "Primary Bank Account",
            accountType = if (isCreditCard) "LIABILITY" else "ASSET",
            customMainCategory = if (isCreditCard) "Credit Cards" else "Bank Accounts",
            isSystemDefault = true,
            iconName = if (isCreditCard) "Credit Card" else "Bank",
            ownerUserId = ownerUid,
            ownerFirstName = ownerName
        )
        upsert(localAccounts, created) { it.accountId == created.accountId }
        write(COL_ACCOUNTS, created.accountId, created)
        return created
    }

    private fun findAccountImmediate(id: String): Account? = localAccounts.value.find { it.accountId == id }

    // ---------------------------------------------------------------------------------------
    // Transactions
    // ---------------------------------------------------------------------------------------

    fun translateTransactionToLedger(tx: Transaction): Transaction = LedgerRules.toLedger(tx)

    /**
     * Recent and older merged (the live copy wins) and converted to ledger form once, here,
     * rather than by every reader. Runs before the collection counts as loaded, so background
     * workers never see a half-built list.
     */
    private fun remergeTransactions() {
        val recent = recentTransactions.value
        val recentIds = recent.mapTo(HashSet()) { it.transactionId }
        localTransactions.value = (olderTransactions.value.filter { it.transactionId !in recentIds } + recent)
            .map { LedgerRules.toLedger(it) }
    }

    fun getTransactions(): Flow<List<Transaction>> = localTransactions

    /** Keeps an edit to an old transaction from being undone by the next live snapshot. */
    private fun editOlder(change: (List<Transaction>) -> List<Transaction>) {
        olderTransactions.value = change(olderTransactions.value)
        remergeTransactions()
    }

    /** Puts [tx] in the older history when it is dated before the live window, else takes it out. */
    private fun placeInOlder(tx: Transaction) = editOlder { list ->
        list.filter { it.transactionId != tx.transactionId } + (if (tx.date < liveCutoff) listOf(tx) else emptyList())
    }

    /** Blocks edits that fall inside a period already reconciled on an asset or liability account. */
    private fun checkNotInClosedPeriod(tx: Transaction) {
        if (tx.status == TransactionStatus.PENDING_REVIEW) return
        listOf(tx.debitAccountId, tx.creditAccountId).forEach { id ->
            val acc = findAccountImmediate(id) ?: return@forEach
            if (acc.accountType != "ASSET" && acc.accountType != "LIABILITY") return@forEach
            val lastRec = acc.lastReconciledDate ?: return@forEach
            if (tx.date < lastRec && !isSameDay(tx.date, lastRec)) {
                throw IllegalArgumentException("Write Blocked: Transaction falls within a closed reconciliation period.")
            }
        }
    }

    suspend fun addTransaction(transaction: Transaction) {
        val ledgerTx = LedgerRules.toLedger(transaction)
        checkNotInClosedPeriod(ledgerTx)
        placeInOlder(ledgerTx)
        if (localTransactions.value.none { it.transactionId == ledgerTx.transactionId }) {
            localTransactions.value = localTransactions.value + ledgerTx
        }
        write(COL_TRANSACTIONS, ledgerTx.transactionId, ledgerTx)
        scheduleBudgetCheck()
    }

    /** Whether an edit leaves every balance as it was (a new category, payee, note or tag). */
    private fun sameMoney(a: Transaction, b: Transaction): Boolean =
        a.amount == b.amount && a.date == b.date && a.type == b.type && a.accountId == b.accountId &&
            (a.targetAccountId ?: "") == (b.targetAccountId ?: "") &&
            LedgerRules.countsInBalance(a) == LedgerRules.countsInBalance(b)

    suspend fun updateTransaction(transaction: Transaction) {
        val ledgerTx = LedgerRules.toLedger(transaction)
        val before = localTransactions.value.find { it.transactionId == ledgerTx.transactionId }
        // A reconciled period is locked for anything that would change a balance, not for relabelling.
        if (before == null || !sameMoney(before, ledgerTx)) {
            before?.let { checkNotInClosedPeriod(it) }
            checkNotInClosedPeriod(ledgerTx)
        }
        placeInOlder(ledgerTx)
        upsert(localTransactions, ledgerTx) { it.transactionId == ledgerTx.transactionId }
        write(COL_TRANSACTIONS, ledgerTx.transactionId, ledgerTx, merge = true)
        // Confirming a reviewed item, or changing its category or amount, can move a budget.
        scheduleBudgetCheck()
    }

    suspend fun deleteTransaction(transactionId: String) {
        val oldTx = localTransactions.value.find { it.transactionId == transactionId || it.id == transactionId } ?: return
        checkNotInClosedPeriod(LedgerRules.toLedger(oldTx))
        editOlder { list -> list.filter { it.transactionId != oldTx.transactionId } }
        localTransactions.value = localTransactions.value.filter { it.transactionId != oldTx.transactionId }
        remove(COL_TRANSACTIONS, oldTx.transactionId)
    }

    // ---------------------------------------------------------------------------------------
    // Budgets
    // ---------------------------------------------------------------------------------------

    fun getBudgets(): Flow<List<Budget>> = localBudgets

    suspend fun addBudget(budget: Budget) {
        upsert(localBudgets, budget) { it.budgetId == budget.budgetId }
        write(COL_BUDGETS, budget.budgetId, budget)
    }

    suspend fun updateBudget(budget: Budget) {
        upsert(localBudgets, budget) { it.budgetId == budget.budgetId }
        write(COL_BUDGETS, budget.budgetId, budget, merge = true)
    }

    suspend fun deleteBudget(budgetId: String) {
        localBudgets.value = localBudgets.value.filter { it.budgetId != budgetId }
        remove(COL_BUDGETS, budgetId)
    }

    /** Budget alerts are worked out by the background check, which both phones run. */
    private fun scheduleBudgetCheck() {
        appContext?.let { com.example.reminders.ReminderWorker.checkSoon(it) }
    }

    // ---------------------------------------------------------------------------------------
    // Reminders
    // ---------------------------------------------------------------------------------------

    fun getReminders(): Flow<List<Reminder>> = localReminders

    suspend fun addReminder(reminder: Reminder) {
        upsert(localReminders, reminder) { it.id == reminder.id }
        write(COL_REMINDERS, reminder.id, reminder)
    }

    suspend fun updateReminder(reminder: Reminder) {
        upsert(localReminders, reminder) { it.id == reminder.id }
        write(COL_REMINDERS, reminder.id, reminder, merge = true)
    }

    suspend fun deleteReminder(reminderId: String) {
        localReminders.value = localReminders.value.filter { it.id != reminderId }
        remove(COL_REMINDERS, reminderId)
    }

    // ---------------------------------------------------------------------------------------
    // Reserves (shared by the household)
    // ---------------------------------------------------------------------------------------

    fun getReserves(): Flow<List<Reserve>> = localReserves

    suspend fun saveReserve(reserve: Reserve) {
        upsert(localReserves, reserve) { it.id == reserve.id }
        write(COL_RESERVES, reserve.id, reserve)
    }

    suspend fun deleteReserve(id: String) {
        localReserves.value = localReserves.value.filter { it.id != id }
        remove(COL_RESERVES, id)
    }

    // ---------------------------------------------------------------------------------------
    // SMS settings: bank senders, merchant rules, privacy keywords
    // ---------------------------------------------------------------------------------------

    fun getWhitelistedSenders(): Flow<List<WhitelistedSender>> = localSenders

    suspend fun addWhitelistedSender(sender: WhitelistedSender) {
        upsert(localSenders, sender) { it.id == sender.id }
        write(COL_SENDERS, sender.id, sender)
        triggerSmsBackfill(senderId = sender.senderId)
    }

    suspend fun deleteWhitelistedSender(id: String) {
        localSenders.value = localSenders.value.filter { it.id != id }
        remove(COL_SENDERS, id)
    }

    fun getMerchantRules(): Flow<List<MerchantRule>> = localMerchantRules

    suspend fun addMerchantRule(rule: MerchantRule) {
        upsert(localMerchantRules, rule) { it.ruleId == rule.ruleId }
        write(COL_RULES, rule.ruleId, rule)
    }

    suspend fun deleteMerchantRule(ruleId: String) {
        localMerchantRules.value = localMerchantRules.value.filter { it.ruleId != ruleId }
        remove(COL_RULES, ruleId)
    }

    fun getPrivacyKeywords(): Flow<List<PrivacyKeyword>> = localPrivacyKeywords

    suspend fun addPrivacyKeyword(keyword: PrivacyKeyword) {
        upsert(localPrivacyKeywords, keyword) { it.id == keyword.id }
        write(COL_KEYWORDS, keyword.id, keyword)
    }

    suspend fun deletePrivacyKeyword(id: String) {
        localPrivacyKeywords.value = localPrivacyKeywords.value.filter { it.id != id }
        remove(COL_KEYWORDS, id)
    }

    // ---------------------------------------------------------------------------------------
    // SMS backfill
    // ---------------------------------------------------------------------------------------

    fun triggerSmsBackfill(accountId: String? = null, senderId: String? = null) {
        val context = appContext ?: return
        try {
            val data = androidx.work.Data.Builder()
            accountId?.let { data.putString("accountId", it) }
            senderId?.let { data.putString("senderId", it) }
            val request = OneTimeWorkRequestBuilder<SmsBackfillWorker>().setInputData(data.build()).build()
            val uniqueName = when {
                accountId != null -> "SmsBackfill_Account_$accountId"
                senderId != null -> "SmsBackfill_Sender_$senderId"
                else -> "SmsBackfillUniqueWork"
            }
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue SmsBackfillWorker: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------------------

    suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
    }

    private fun isSameDay(millis1: Long, millis2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = millis1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = millis2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
}
