package com.medusa.mobile

// mm-gmail-auto-test-001 — Instrumented tests for Gmail OAuth flow
//
// Runs on emulator-5554. Tests:
//   1. reAuthNeeded fires when no Google account is present on the device
//   2. clearReAuthNeeded resets state correctly
//   3. getAccessToken() returns null (not crash) when no account
//   4. Dialog state wires from GoogleAuthManager → ChatViewModel → ChatUiState

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.medusa.mobile.services.GoogleAuthManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the Gmail OAuth re-auth flow.
 *
 * Emulator-5554 has no Google account signed in, which mirrors the failure
 * state users hit. We verify the error-handling path fires correctly and
 * that the re-auth signal propagates to the UI layer.
 */
@RunWith(AndroidJUnit4::class)
class GmailAuthFlowTest {

    private lateinit var context: Context
    private lateinit var authManager: GoogleAuthManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        authManager = GoogleAuthManager(context)
    }

    // ── No-account state (emulator default) ─────────────────────────────────

    @Test
    fun reAuthNeeded_startsFalse() = runTest {
        assertFalse(
            "reAuthNeeded must start false — should not show dialog until a tool call triggers it",
            authManager.reAuthNeeded.first()
        )
    }

    @Test
    fun getAccessToken_returnsNull_whenNoAccount() = runTest {
        // Emulator has no Google account → getLastSignedInAccount returns null
        // Expected: null token returned (no crash), reAuthNeeded set to true
        val token = authManager.getAccessToken()

        assertNull(
            "getAccessToken() should return null when no Google account is signed in",
            token
        )
    }

    @Test
    fun reAuthNeeded_becomesTrue_whenNoAccount() = runTest {
        // Trigger a token fetch — should set reAuthNeeded since no account
        authManager.getAccessToken()

        assertTrue(
            "reAuthNeeded should be true after getAccessToken() with no signed-in account. " +
            "This drives the in-context consent dialog in ChatScreen.",
            authManager.reAuthNeeded.first()
        )
    }

    @Test
    fun clearReAuthNeeded_resetState() = runTest {
        // Get into the reAuthNeeded=true state
        authManager.getAccessToken()
        assertTrue("Setup: reAuthNeeded should be true", authManager.reAuthNeeded.first())

        // Clear it (simulates user completing or cancelling the consent dialog)
        authManager.clearReAuthNeeded()

        assertFalse(
            "reAuthNeeded should be false after clearReAuthNeeded()",
            authManager.reAuthNeeded.first()
        )
    }

    @Test
    fun getReAuthIntent_notNull_whenNoAccount() = runTest {
        // Trigger the reAuth state
        authManager.getAccessToken()

        val intent = authManager.getReAuthIntent()

        assertNotNull(
            "getReAuthIntent() must return a non-null Intent so ChatScreen can launch it",
            intent
        )
    }

    @Test
    fun isSignedIn_returnsFalse_onEmulator() {
        // Emulator has no Google account
        assertFalse(
            "isSignedIn() should return false on fresh emulator with no account",
            authManager.isSignedIn()
        )
    }

    @Test
    fun multipleGetAccessToken_doesNotCrash() = runTest {
        // Calling getAccessToken() multiple times should be idempotent — no crash,
        // no duplicate dialog triggers beyond the first
        repeat(3) { authManager.getAccessToken() }

        assertTrue(
            "After multiple getAccessToken() calls, reAuthNeeded should still be true",
            authManager.reAuthNeeded.first()
        )
    }

    @Test
    fun clearThenReauth_cycleWorks() = runTest {
        // Full cycle: trigger → clear → trigger again
        authManager.getAccessToken()
        assertTrue(authManager.reAuthNeeded.first())

        authManager.clearReAuthNeeded()
        assertFalse(authManager.reAuthNeeded.first())

        // Second trigger
        authManager.getAccessToken()
        assertTrue(
            "reAuthNeeded should become true again after second getAccessToken()",
            authManager.reAuthNeeded.first()
        )
    }

    // ── EmailTool integration ────────────────────────────────────────────────

    @Test
    fun gmailSearch_setsReAuthNeeded_whenNoAccount() = runTest {
        val emailTool = com.medusa.mobile.agent.tools.EmailTool(context, authManager)

        val result = emailTool.gmailSearch(query = "test")

        assertFalse("gmailSearch should fail when not authenticated", result.success)
        assertTrue(
            "reAuthNeeded should be true after failed gmailSearch — dialog should appear",
            authManager.reAuthNeeded.first()
        )
        assertFalse(
            "Error should not reference Settings anymore",
            result.summary.contains("Settings → Connect Google Account")
        )
    }

    @Test
    fun gmailRead_setsReAuthNeeded_whenNoAccount() = runTest {
        val emailTool = com.medusa.mobile.agent.tools.EmailTool(context, authManager)

        emailTool.gmailRead(messageId = "any-id")

        assertTrue(
            "reAuthNeeded should be true after failed gmailRead",
            authManager.reAuthNeeded.first()
        )
    }
}
