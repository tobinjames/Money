package com.example.ai

import android.util.Log
import com.example.BuildConfig
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import com.example.data.*

@JsonClass(generateAdapter = true)
data class ParsedTransactionResponse(
    val transactionType: String, // "DEBIT" or "CREDIT"
    val amount: Double,
    val vendor: String,
    val date: String?,
    val suggestedCategory: String = "",
    val assignedAccount: String? = null,
    val matchedCategory: String? = null,
    val matchedAccount: String? = null,
    val ignore: Boolean? = null
)

object GeminiSmsParser {
    private const val TAG = "GeminiSmsParser"
    private const val MODEL_NAME = "gemini-3.5-flash"
    
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Parses the raw SMS contents using Google's Gemini-3.5-flash model via direct REST invocation.
     * Incorporates dual-layer local merchant rules and custom category overrides.
     */
    suspend fun parseSms(
        rawText: String,
        merchantRules: List<MerchantRule>,
        categories: List<Account>,
        accountsList: List<Account>
    ): ParsedTransactionResponse? = withContext(Dispatchers.IO) {
        val lowerText = rawText.lowercase()
        val isStatementOrBillAlert = (
            lowerText.contains("statement") ||
            lowerText.contains("bill") ||
            lowerText.contains("min due") ||
            lowerText.contains("minimum due") ||
            lowerText.contains("payment due") ||
            lowerText.contains("total due") ||
            lowerText.contains("due date")
        ) && !(
            lowerText.contains("debited") ||
            lowerText.contains("credited") ||
            lowerText.contains("withdrawn") ||
            lowerText.contains("deposited") ||
            lowerText.contains("received") ||
            lowerText.contains("refund") ||
            lowerText.contains("spent") ||
            lowerText.contains("paid rs") ||
            lowerText.contains("paid to")
        )
        if (isStatementOrBillAlert) {
            Log.d(TAG, "Local intercept: classified statement/bill notification as ignore.")
            return@withContext null
        }

        // Step 1: Local Pattern Check
        var matchedCategory: String? = null
        for (rule in merchantRules) {
            if (rule.keyword.isNotEmpty() && rawText.contains(rule.keyword, ignoreCase = true)) {
                matchedCategory = rule.assignedCategory
                break
            }
        }

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY" || apiKey == "MY_NEW_API_KEY_DEFAULT_VALUE") {
            Log.e(TAG, "Gemini API Key is missing or default. Falling back to local regex backup parser.")
            return@withContext runLocalRegexBackupParser(rawText, matchedCategory, categories, accountsList)
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent?key=$apiKey"
        
        val bankAccountsList = accountsList.map { "${it.accountName} (xx${it.lastFourDigits})" }.joinToString(", ")
        
        // Step 2: Formulate dynamic prompt payload
        val systemInstruction = if (matchedCategory != null) {
            """
                Analyze this SMS text for actual transaction data and return a JSON object containing amount, date, and payee name.
                If the SMS does NOT represent a real financial transaction (e.g. if it is a reminder, general notification, OTP/verification code, balance inquiry, promotional spam, or other non-transactional message), set the 'ignore' field to true.
                
                1. Identify the Bank Account involved by matching text identifiers to this list: [$bankAccountsList]. Return the matching account name as the 'matchedAccount'.
                
                Your output must be a single, raw, unformatted JSON object and nothing else. No markdown wraps (no ```json code blocks), no explanations.
                JSON fields to extract:
                1. "transactionType": must be either "DEBIT" or "CREDIT".
                2. "amount": total transactional value as a floating-point number.
                3. "vendor": name of merchant, vendor, or beneficiary.
                4. "date": date extracted from message.
                5. "matchedAccount": the matching account name selected from the list provided.
                6. "matchedCategory": "$matchedCategory"
                7. "ignore": boolean. True if the SMS is not an actual financial transaction (reminders, notifications, balance inquiry, OTP, spam etc.), false otherwise.
            """.trimIndent()
        } else {
            val categoriesList = categories.map { it.name }.joinToString(", ")
            """
                Analyze this SMS text for actual transaction data and return a JSON object containing amount, date, and payee name.
                If the SMS does NOT represent a real financial transaction (e.g. if it is a reminder, general notification, OTP/verification code, balance inquiry, promotional spam, or other non-transactional message), set the 'ignore' field to true.
                
                1. Identify the Bank Account involved by matching text identifiers to this list: [$bankAccountsList]. Return the matching account name as the 'matchedAccount'.
                2. If the category wasn't resolved locally, classify the transaction by selecting an item strictly from this list: [$categoriesList]. Return it as the 'matchedCategory'. If unsure, return 'Unknown'.
                
                Your output must be a single, raw, unformatted JSON object and nothing else. No markdown wraps (no ```json code blocks), no explanations.
                JSON fields to extract:
                1. "transactionType": must be either "DEBIT" or "CREDIT".
                2. "amount": total transactional value as a floating-point number.
                3. "vendor": name of merchant, vendor, or beneficiary.
                4. "date": date extracted from message.
                5. "matchedAccount": the matching account name selected from the list provided.
                6. "matchedCategory": recommended category from categories list strictly, or "Unknown".
                7. "ignore": boolean. True if the SMS is not an actual financial transaction (reminders, notifications, balance inquiry, OTP, spam etc.), false otherwise.
            """.trimIndent()
        }

        val jsonRequest = """
            {
               "contents": [
                 {
                   "parts": [
                     { "text": ${escapeJsonString(rawText)} }
                   ]
                 }
               ],
               "systemInstruction": {
                 "parts": [
                   { "text": ${escapeJsonString(systemInstruction)} }
                 ]
               },
               "generationConfig": {
                 "responseMimeType": "application/json",
                 "temperature": 0.1
               }
             }
        """.trimIndent()

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonRequest.toRequestBody(mediaType)
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Gemini REST request failed with code: ${response.code}. Body: ${response.body?.string()}")
                    return@withContext runLocalRegexBackupParser(rawText, matchedCategory, categories, accountsList)
                }
                
                val responseBody = response.body?.string() ?: return@withContext runLocalRegexBackupParser(rawText, matchedCategory, categories, accountsList)
                Log.d(TAG, "Gemini Response Raw: $responseBody")
                
                val extractedText = extractTextFromGeminiJson(responseBody) ?: return@withContext runLocalRegexBackupParser(rawText, matchedCategory, categories, accountsList)
                Log.d(TAG, "Extracted JSON String from Gemini: $extractedText")
                
                // Record telemetry audit log
                GeminiTelemetryLogger.logInteraction(rawText, extractedText)
                
                val adapter = moshi.adapter(ParsedTransactionResponse::class.java)
                val parsedObj = adapter.fromJson(extractedText.trim())
                
                return@withContext parsedObj?.let { obj ->
                    if (obj.ignore == true) {
                        Log.d(TAG, "Gemini classified this message as non-transactional (ignore = true). Skipping from accounting.")
                        return@withContext null
                    }
                    val finalCat = obj.matchedCategory ?: obj.suggestedCategory.ifEmpty { "Unknown" }
                    val finalAcc = obj.matchedAccount ?: obj.assignedAccount
                    obj.copy(
                        suggestedCategory = finalCat,
                        assignedAccount = finalAcc
                    )
                } ?: runLocalRegexBackupParser(rawText, matchedCategory, categories, accountsList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Gemini SMS parsing REST call: ${e.message}", e)
            return@withContext runLocalRegexBackupParser(rawText, matchedCategory, categories, accountsList)
        }
    }

    private fun extractTextFromGeminiJson(rawJson: String): String? {
        try {
            // Traverse response structure: candidates[0].content.parts[0].text
            val mapAdapter = moshi.adapter(Map::class.java)
            val root = mapAdapter.fromJson(rawJson) as? Map<*, *> ?: return null
            val candidates = root["candidates"] as? List<*> ?: return null
            val firstCandidate = candidates.firstOrNull() as? Map<*, *> ?: return null
            val content = firstCandidate["content"] as? Map<*, *> ?: return null
            val parts = content["parts"] as? List<*> ?: return null
            val firstPart = parts.firstOrNull() as? Map<*, *> ?: return null
            return firstPart["text"] as? String
        } catch (e: Exception) {
            Log.e(TAG, "Error structural-parsing Gemini JSON response: ${e.message}")
            return null
        }
    }

    private fun escapeJsonString(input: String): String {
        return moshi.adapter(String::class.java).toJson(input)
    }

    /**
     * Highly robust Regex Backup Parser that kicks in if Gemini API key is missing or calls fail.
     * Ensures perfect out-of-the-box demo functionality for credit and debit SMS parsing!
     */
    private fun runLocalRegexBackupParser(
        smsText: String,
        matchedCategory: String?,
        categories: List<Account>,
        accountsList: List<Account>
    ): ParsedTransactionResponse {
        val lower = smsText.lowercase()
        val isCredit = lower.contains("credited") || lower.contains("received") || lower.contains("deposited") || lower.contains("refund")
        val isDebit = lower.contains("debited") || lower.contains("spent") || lower.contains("withdrawn") || lower.contains("paid for") || lower.contains("alert:")
        
        val transactionType = if (isCredit && !isDebit) "CREDIT" else "DEBIT"
        
        // Extract amount: locate numbers following currency indicators (Rs, INR, $, USD) or standard digits
        var amount = 0.0
        val amountRegex = Regex("""(?:rs\.?|inr|usd|\$)\s*([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE)
        val match = amountRegex.find(lower)
        if (match != null) {
            val amtStr = match.groupValues[1].replace(",", "")
            amount = amtStr.toDoubleOrNull() ?: 0.0
        } else {
            // Try standard fallback
            val simpleDigitRegex = Regex("""\b\d+\.\d{2}\b""")
            val simpleMatch = simpleDigitRegex.find(lower)
            if (simpleMatch != null) {
                amount = simpleMatch.value.toDoubleOrNull() ?: 0.0
            }
        }
        
        // Extract vendor
        var vendor = "Unknown"
        val vendorRegexes = listOf(
            Regex("""(?:at|to|in)\s+([a-zA-Z0-9\s#\-]+?)(?:\s+on|\s*info|\s*using|\s*of|\s*\.)""", RegexOption.IGNORE_CASE),
            Regex("""spent\s+([\d,.]+)\s+on\s+([a-zA-Z0-9\s#\-]+)""", RegexOption.IGNORE_CASE)
        )
        for (regex in vendorRegexes) {
            val vMatch = regex.find(smsText)
            if (vMatch != null) {
                val candidate = if (vMatch.groupValues.size > 2) vMatch.groupValues[2] else vMatch.groupValues[1]
                vendor = candidate.trim().uppercase()
                break
            }
        }
        
        if (vendor == "Unknown" || vendor.isEmpty()) {
            vendor = "Local Transaction"
        }
        
        // Suggested category mapping using intelligent multi-tier COA matching
        val suggestedCategory: String
        if (matchedCategory != null && matchedCategory.isNotBlank()) {
            // If merchant rules provided a category, confirm if it exists in COA or match closest
            val coaMatch = categories.find { it.name.equals(matchedCategory, ignoreCase = true) }
            suggestedCategory = coaMatch?.name ?: matchedCategory
        } else {
            suggestedCategory = resolveCategoryFromSms(lower, vendor, transactionType, categories)
        }

        // Match account using lastFourDigits or name keywords in the SMS body
        var matchedAccount: Account? = null
        if (accountsList.isNotEmpty()) {
            // 1. Try matching by lastFourDigits (supports multiple comma-separated digits for account + debit card)
            matchedAccount = accountsList.find { acc ->
                if (acc.lastFourDigits.isBlank()) false
                else {
                    val tokens = acc.lastFourDigits.split(",", "/", ";", " ", "|").map { it.trim() }.filter { it.length >= 3 }
                    tokens.any { token -> smsText.contains(token) }
                }
            }
            // 2. Try matching by account name keywords (e.g., "HDFC", "ICICI")
            if (matchedAccount == null) {
                matchedAccount = accountsList.find { acc ->
                    acc.name.isNotBlank() && smsText.contains(acc.name, ignoreCase = true)
                }
            }
            // 3. Fallback to first savings account or first account
            if (matchedAccount == null) {
                matchedAccount = accountsList.find { it.type == AccountType.SAVINGS_ACCOUNT } ?: accountsList.firstOrNull()
            }
        }

        return ParsedTransactionResponse(
            transactionType = transactionType,
            amount = amount,
            vendor = vendor,
            date = "Current",
            suggestedCategory = suggestedCategory,
            assignedAccount = matchedAccount?.accountName,
            matchedAccount = matchedAccount?.accountName,
            matchedCategory = suggestedCategory
        )
    }

    /**
     * Resolves the most accurate COA category based on SMS content, vendor, transaction type,
     * and user's dynamically defined Chart of Accounts (COA).
     */
    private fun resolveCategoryFromSms(
        lowerText: String,
        vendor: String,
        txType: String,
        categories: List<Account>
    ): String {
        if (categories.isEmpty()) {
            return if (txType == "CREDIT") "Salary" else "Others"
        }

        val targetClass = if (txType == "CREDIT") "INCOME" else "EXPENSE"
        val candidateCategories = categories.filter {
            it.accountClass.equals(targetClass, ignoreCase = true) ||
            it.accountType.equals(targetClass, ignoreCase = true) ||
            (it.accountClass.isBlank() && it.accountType.isBlank())
        }.ifEmpty { categories }

        // Tier 1: Direct exact match of any COA category name in SMS text or vendor
        val directMatch = candidateCategories.find { cat ->
            val catName = cat.name.trim().lowercase()
            catName.isNotBlank() && (
                lowerText.contains(catName) || 
                vendor.lowercase().contains(catName)
            )
        }
        if (directMatch != null) {
            return directMatch.name
        }

        // Tier 2: Domain-specific keyword semantic mapping to user COA categories
        val semanticCategory = matchSemanticKeywordsToCoa(lowerText, vendor, candidateCategories)
        if (semanticCategory != null) {
            return semanticCategory
        }

        // Tier 3: Word/token overlap matching against category names in user's COA
        for (cat in candidateCategories) {
            val tokens = cat.name.lowercase().split(Regex("[^a-zA-Z0-9]+")).filter { it.length > 2 }
            for (token in tokens) {
                if (lowerText.contains(token) || vendor.lowercase().contains(token)) {
                    return cat.name
                }
            }
        }

        // Tier 4: Fallback to "Others", "General Expense", or first expense category
        val fallbackOthers = candidateCategories.find {
            it.name.equals("Others", ignoreCase = true) || 
            it.name.equals("Miscellaneous", ignoreCase = true) ||
            it.name.equals("General", ignoreCase = true)
        }
        return fallbackOthers?.name ?: candidateCategories.firstOrNull()?.name ?: "Others"
    }

    private fun matchSemanticKeywordsToCoa(
        lowerText: String,
        vendor: String,
        categories: List<Account>
    ): String? {
        val combined = "$lowerText ${vendor.lowercase()}"

        val rules = listOf(
            // Food & Dining
            listOf("food", "dining", "restaurant", "cafe", "coffee", "swiggy", "zomato", "starbucks", "mcdonald", "pizza", "burger", "kfc", "dominos", "bakery", "subway", "eatclub") to
                listOf("food", "dining", "restaurant", "cafe", "meals", "eating out"),
            
            // Groceries & Supermarket
            listOf("grocery", "groceries", "supermarket", "blinkit", "zepto", "instamart", "bigbasket", "dmart", "reliance fresh", "market", "vegetables", "fruits", "milk", "dairy", "spencer") to
                listOf("groceries", "grocery", "supermarket", "household", "supplies", "food"),
            
            // Utilities & Bills
            listOf("utility", "utilities", "electricity", "water bill", "gas bill", "airtel", "jio", "vi ", "broadband", "wifi", "internet", "dth", "tneb", "bescom", "bill payment", "recharge") to
                listOf("utilities", "bills", "electricity", "mobile", "internet", "recharge"),
            
            // Housing & Rent
            listOf("rent", "housing", "landlord", "flat", "apartment", "maintenance fee", "society", "mortgage") to
                listOf("housing", "rent", "home", "maintenance"),
            
            // Travel, Fuel & Commute
            listOf("travel", "uber", "ola", "rapido", "metro", "cab", "taxi", "flight", "airways", "indigo", "air india", "train", "irctc", "petrol", "fuel", "diesel", "shell", "hpcl", "bpcl", "ioc", "toll", "fastag", "parking") to
                listOf("travel", "fuel", "transport", "commute", "vehicle", "auto"),
            
            // Entertainment & Subscriptions
            listOf("entertainment", "netflix", "prime", "hotstar", "spotify", "youtube", "movie", "cinema", "bookmyshow", "pvr", "inox", "theatre", "concert", "game", "steam", "playstation") to
                listOf("entertainment", "movies", "subscriptions", "recreation", "media"),
            
            // Shopping & Ecommerce
            listOf("shopping", "amazon", "flipkart", "myntra", "ajio", "zara", "h&m", "uniqlo", "retail", "clothing", "apparel", "electronics", "croma", "reliancedigital") to
                listOf("shopping", "clothing", "apparel", "electronics", "ecommerce", "personal"),
            
            // Health & Medical
            listOf("health", "medical", "pharmacy", "hospital", "clinic", "doctor", "medicine", "apollo", "1mg", "pharmeasy", "medplus", "dental", "diagnostic", "lab") to
                listOf("health", "medical", "pharmacy", "doctor", "healthcare", "fitness"),
            
            // Investment & Finance
            listOf("investment", "mutual fund", "sip", "zerodha", "groww", "upstox", "stock", "shares", "deposit", "gold", "crypto", "binance", "coinbase") to
                listOf("investment", "mutual", "stock", "wealth", "savings"),

            // Income / Salary
            listOf("salary", "payroll", "stipend", "bonus", "dividend", "interest credited", "cashback", "reward", "refund") to
                listOf("salary", "income", "refund", "bonus", "cashback", "rewards")
        )

        for ((triggerKeywords, coaTargets) in rules) {
            val triggered = triggerKeywords.any { combined.contains(it) }
            if (triggered) {
                // Find category in COA that matches any of the coaTargets
                for (target in coaTargets) {
                    val match = categories.find { cat ->
                        cat.name.lowercase().contains(target)
                    }
                    if (match != null) {
                        return match.name
                    }
                }
            }
        }
        return null
    }
}
