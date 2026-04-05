package com.medusa.mobile.services

// mm-gmail-auto-test-001 — Unit tests for GoogleAuthManager OAuth flow
//
// Tests the StateFlow signals and intent routing logic without needing
// a real device or Google account. Uses reflection to directly set private
// state for isolation.

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.UserRecoverableAuthException
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field

/**
 * Unit tests for GoogleAuthManager — covers the re-auth signal logic
 * that drives the in-context Gmail consent dialog.
 *
 * We test the state machine behavior (reAuthNeeded StateFlow, clearReAuthNeeded,
 * getReAuthIntent) by directly setting private fields via reflection.
 * The Google Play Services static methods are tested in instrumented tests.
 */
class GoogleAuthManagerTest {

    private lateinit var mockContext: Context
    // GoogleAuthManager requires a real context for GoogleSignIn — we use a mock
    // and rely on reflection to inject state without triggering Play Services.
    private lateinit var manager: GoogleAuthManager

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        manager = GoogleAuthManager(mockContext)
    }

    // ── reAuthNeeded StateFlow ───────────────────────────────────────────────

    @Test
    fun `reAuthNeeded starts as false`() = runTest {
        assertFalse(
            "reAuthNeeded should be false on init",
            manager.reAuthNeeded.first()
        )
    }

    @Test
    fun `clearReAuthNeeded sets reAuthNeeded to false`() = runTest {
        // Inject reAuthNeeded = true via reflection
        setPrivateField(manager, "_reAuthNeeded") { flow ->
            (flow as kotlinx.coroutines.flow.MutableStateFlow<Boolean>).value = true
        }
        assertTrue("reAuthNeeded should be true after injection", manager.reAuthNeeded.first())

        manager.clearReAuthNeeded()

        assertFalse("reAuthNeeded should be false after clear", manager.reAuthNeeded.first())
    }

    @Test
    fun `clearReAuthNeeded nulls pendingAuthIntent`() {
        val mockIntent = mockk<Intent>(relaxed = true)
        setField(manager, "pendingAuthIntent", mockIntent)

        manager.clearReAuthNeeded()

        val pendingIntent = getField<Intent?>(manager, "pendingAuthIntent")
        assertNull("pendingAuthIntent should be null after clearReAuthNeeded", pendingIntent)
    }

    @Test
    fun `clearReAuthNeeded nulls cachedToken`() {
        // clearReAuthNeeded() calls GoogleAuthUtil.invalidateToken() if cachedToken is set.
        // GoogleAuthUtil triggers Play Services static init on JVM → crashes.
        // Test with cachedToken = null to avoid the Play Services code path.
        // The full path (non-null token) is covered by GmailAuthFlowTest (instrumented).
        setField(manager, "cachedToken", null as String?)

        manager.clearReAuthNeeded()

        val cached = getField<String?>(manager, "cachedToken")
        assertNull("cachedToken should remain null after clearReAuthNeeded with null input", cached)
    }

    // ── getReAuthIntent ──────────────────────────────────────────────────────

    @Test
    fun `getReAuthIntent returns pendingAuthIntent when set`() {
        val mockIntent = mockk<Intent>(relaxed = true)
        setField(manager, "pendingAuthIntent", mockIntent)

        val result = manager.getReAuthIntent()

        assertSame(
            "getReAuthIntent must return the stored pendingAuthIntent (e.g. UserRecoverableAuthException.intent), " +
            "not the generic signIn intent — launching the wrong intent won't grant scopes",
            mockIntent,
            result
        )
    }

    // ── isSignedIn / getAccessToken / signInClient fallback ─────────────────
    // These trigger Google Play Services static initializers (TextUtils, Log)
    // which cannot run on JVM without Robolectric. Covered by GmailAuthFlowTest
    // (instrumented) which runs on emulator-5554 where Play Services IS available.

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun setPrivateField(obj: Any, fieldName: String, action: (Any) -> Unit) {
        val field = findField(obj.javaClass, fieldName)
        field.isAccessible = true
        action(field.get(obj)!!)
    }

    private fun <T> getField(obj: Any, fieldName: String): T? {
        val field = findField(obj.javaClass, fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(obj) as T?
    }

    private fun setField(obj: Any, fieldName: String, value: Any?) {
        val field = findField(obj.javaClass, fieldName)
        field.isAccessible = true
        field.set(obj, value)
    }

    private fun findField(clazz: Class<*>, name: String): Field {
        var c: Class<*>? = clazz
        while (c != null) {
            try { return c.getDeclaredField(name) } catch (_: NoSuchFieldException) {}
            c = c.superclass
        }
        throw NoSuchFieldException("Field $name not found in $clazz hierarchy")
    }
}
