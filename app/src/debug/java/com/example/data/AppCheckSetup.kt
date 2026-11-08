package com.example.data

import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds use the App Check debug provider. On first run it logs a line like
 * "Enter this debug secret into the allow list in the Firebase Console: <token>".
 * Add that token under Firebase console > App Check > Apps > Manage debug tokens.
 */
object AppCheckSetup {
    fun install() {
        Firebase.appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
