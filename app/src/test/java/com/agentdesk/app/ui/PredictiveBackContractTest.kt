package com.agentdesk.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates system manifest contracts for Predictive Back navigation,
 * IME window insets, and Zero Cloud Egress invariants.
 */
class PredictiveBackContractTest {

    @Test
    fun testAndroidManifestPredictiveBackAndEdgeToEdgeContracts() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())

        val manifestContent = manifestFile.readText()

        // 1. Predictive Back Navigation contract (Android 13+ / 14+)
        assertTrue(
            "AndroidManifest.xml must declare android:enableOnBackInvokedCallback=\"true\"",
            manifestContent.contains("android:enableOnBackInvokedCallback=\"true\"")
        )

        // 2. IME soft input resize contract for edge-to-edge keyboard insets
        assertTrue(
            "MainActivity must declare android:windowSoftInputMode=\"adjustResize\"",
            manifestContent.contains("android:windowSoftInputMode=\"adjustResize\"")
        )

        // 3. Zero Cloud Egress invariant: no active internet permission
        val hasActiveInternetPermission = manifestContent.lines().any { line ->
            val trimmed = line.trim()
            trimmed.startsWith("<uses-permission") && trimmed.contains("android.permission.INTERNET")
        }
        assertFalse(
            "Zero Cloud Egress violation: android.permission.INTERNET must not be active",
            hasActiveInternetPermission
        )
    }
}
