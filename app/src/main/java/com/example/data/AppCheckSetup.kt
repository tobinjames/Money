package com.example.data

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import java.io.File

/**
 * App Check for a sideloaded, personal-use app: every install (debug or release) uses the
 * debug provider. On first launch it logs, once, a line like
 *   "Enter this debug secret into the allow list in the Firebase Console for your project: <token>"
 * Add each phone's token under Firebase console > App Check > Apps > Manage debug tokens.
 * Reinstalling or clearing app data creates a new token, which must be added again.
 * The token is also shown in Settings > Health with a Copy button (see [debugToken]), so no adb
 * is needed.
 *
 * If the app is ever published on Google Play, switch to
 * PlayIntegrityAppCheckProviderFactory (dependency firebase-appcheck-playintegrity).
 */
object AppCheckSetup {
    fun install() {
        Firebase.appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }

    /**
     * This phone's App Check debug token, or null if the debug provider has not made one yet.
     * The SDK keeps it in a shared-preferences file named
     * "com.google.firebase.appcheck.debug.store.<app key>" under a key ending in DEBUG_SECRET.
     */
    fun debugToken(context: Context): String? {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        val names = dir.listFiles()
            ?.map { it.name.removeSuffix(".xml") }
            ?.filter { it.startsWith("com.google.firebase.appcheck.debug.store") }
            ?: return null
        for (name in names) {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            prefs.all.entries.firstOrNull { it.key.endsWith("DEBUG_SECRET") }
                ?.value?.toString()?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return null
    }

    /** As [debugToken], first asking App Check for a token so the debug provider makes one. */
    fun fetchDebugToken(context: Context, onResult: (String?) -> Unit) {
        val app = context.applicationContext
        debugToken(app)?.let { onResult(it); return }
        try {
            Firebase.appCheck.getAppCheckToken(false).addOnCompleteListener { onResult(debugToken(app)) }
        } catch (e: Exception) {
            onResult(debugToken(app))
        }
    }
}
