package com.example.data

import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * App Check for a sideloaded, personal-use app: every install (debug or release) uses the
 * debug provider. On first launch it logs, once, a line like
 *   "Enter this debug secret into the allow list in the Firebase Console for your project: <token>"
 * Add each phone's token under Firebase console > App Check > Apps > Manage debug tokens.
 * Reinstalling or clearing app data creates a new token, which must be added again.
 *
 * If the app is ever published on Google Play, switch to
 * PlayIntegrityAppCheckProviderFactory (dependency firebase-appcheck-playintegrity).
 */
object AppCheckSetup {
    fun install() {
        Firebase.appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
