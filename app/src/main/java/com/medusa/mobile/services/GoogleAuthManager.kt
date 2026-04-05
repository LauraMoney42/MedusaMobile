package com.medusa.mobile.services

// mm-019 — Google OAuth2 Manager for Medusa Mobile
//
// Manages Google Sign-In and OAuth2 token acquisition for Google Docs + Drive APIs.
// Uses Google Sign-In SDK (play-services-auth) to get an access token with
// Docs + Drive scopes. The token is cached in memory and refreshed on demand.
//
// Why Google Sign-In instead of Credential Manager?
//   Credential Manager doesn't support OAuth2 scopes for Google APIs yet.
//   Google Sign-In is the official way to get an access token for REST API calls.
//
// Usage flow:
//   1. User taps "Connect Google Account" in Settings (or first tool use prompts)
//   2. GoogleAuthManager.getSignInIntent() → launch Activity result
//   3. GoogleAuthManager.handleSignInResult() → extracts account
//   4. GoogleAuthManager.getAccessToken() → background thread, returns OAuth2 token
//   5. GoogleDocsTool uses token for REST API calls

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Manages Google OAuth2 sign-in for Docs + Drive API access.
 *
 * Scopes requested:
 *   - docs (create/edit Google Docs)
 *   - drive.file (access files created by this app)
 *   - drive.readonly (list/search all Drive files)
 */
class GoogleAuthManager(private val context: Context) {

    companion object {
        // OAuth2 scopes for Google Docs + Drive
        const val SCOPE_DOCS = "https://www.googleapis.com/auth/documents"
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
        const val SCOPE_DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
        // Gmail scope — must be in sign-in options so it's granted during consent
        const val SCOPE_GMAIL = "https://www.googleapis.com/auth/gmail.modify"
        // Sheets scope — must be in sign-in options so it's granted during consent
        const val SCOPE_SHEETS = "https://www.googleapis.com/auth/spreadsheets"
    }

    private val signInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope(SCOPE_DOCS),
                Scope(SCOPE_DRIVE_FILE),
                Scope(SCOPE_DRIVE_READONLY),
                // Gmail + Sheets scopes must be requested here so they're
                // granted during the sign-in consent screen. Without this,
                // GoogleAuthUtil.getToken() throws UserRecoverableAuthException
                // even when the user IS signed in, causing false "not connected" prompts.
                Scope(SCOPE_GMAIL),
                Scope(SCOPE_SHEETS)
            )
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    // Cached access token (in-memory only — refreshed each session)
    @Volatile
    private var cachedToken: String? = null

    // ── Re-auth signal ───────────────────────────────────────────────────
    // Set to true when a tool call needs user interaction to complete auth.
    // Two cases trigger this:
    //   1. No account (never signed in / signed out) → pendingAuthIntent = signInClient.signInIntent
    //   2. UserRecoverableAuthException → pendingAuthIntent = e.intent (the OAuth2 grant screen)
    // IMPORTANT: case 2 requires launching e.intent specifically — it IS the permission grant
    // screen. Launching signInClient.signInIntent in case 2 shows the account picker, which
    // completes without granting the new scopes, so getToken() keeps throwing the same exception.
    private val _reAuthNeeded = MutableStateFlow(false)
    val reAuthNeeded: StateFlow<Boolean> = _reAuthNeeded.asStateFlow()

    // Holds the correct intent to launch for the current re-auth scenario.
    // Must be launched by the UI — not signInClient.signInIntent (which won't grant scopes).
    @Volatile private var pendingAuthIntent: Intent? = null

    /**
     * Returns the intent to launch for re-auth. Always use this instead of getSignInIntent()
     * when responding to a reAuthNeeded signal — it may be a scope-grant intent from a
     * UserRecoverableAuthException, not a sign-in intent.
     */
    fun getReAuthIntent(): Intent = pendingAuthIntent ?: signInClient.signInIntent

    /**
     * Called by UI after the consent activity finishes. Clears the signal and forces
     * a fresh token fetch on the next tool call.
     *
     * Also invalidates any cached token in Play Services so that stale "denied" state
     * is cleared — without this, Play Services can serve a cached denial even after
     * the user grants permission, causing UserRecoverableAuthException on the next call.
     */
    fun clearReAuthNeeded() {
        _reAuthNeeded.value = false
        pendingAuthIntent = null
        // Invalidate cached token in Play Services (best-effort — runs on calling thread).
        // This ensures the next getToken() call fetches a truly fresh token from Google's
        // servers, not a stale cached result from before the user granted consent.
        cachedToken?.let { staleToken ->
            try {
                com.google.android.gms.auth.GoogleAuthUtil.invalidateToken(context, staleToken)
            } catch (_: Exception) { /* best-effort — ignore */ }
        }
        cachedToken = null
    }

    // ── Sign-In Flow ────────────────────────────────────────────────────

    /**
     * Returns the Intent to launch the Google Sign-In UI.
     * Caller should launch this with Activity.startActivityForResult() or
     * ActivityResultLauncher.
     */
    fun getSignInIntent(): Intent = signInClient.signInIntent

    /**
     * Process the result from the sign-in Activity.
     * Returns the signed-in account or null on failure.
     */
    fun handleSignInResult(data: Intent?): GoogleSignInAccount? {
        val task: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(data)
        return try {
            task.getResult(ApiException::class.java)
        } catch (e: ApiException) {
            null
        }
    }

    // ── Token Management ────────────────────────────────────────────────

    /**
     * Gets a valid OAuth2 access token for Google API calls.
     *
     * Flow:
     *   1. Check if user is signed in (silently, from last session)
     *   2. If signed in, get token via GoogleAuthUtil
     *   3. Cache and return
     *
     * Must be called from a coroutine (runs on IO dispatcher).
     * Returns null if not signed in — caller should prompt sign-in.
     */
    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        val scopeString = "oauth2:$SCOPE_DOCS $SCOPE_DRIVE_FILE $SCOPE_DRIVE_READONLY $SCOPE_GMAIL $SCOPE_SHEETS"
        try {
            // Check for existing sign-in.
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account == null) {
                pendingAuthIntent = signInClient.signInIntent
                _reAuthNeeded.value = true
                return@withContext null
            }

            // account.account (the underlying Android Account) can be null on some devices
            // when the Google account sync is broken or the account was removed from Settings.
            // Treat this as a sign-in failure — prompt re-sign-in via the standard flow.
            val androidAccount = account.account
            if (androidAccount == null) {
                pendingAuthIntent = signInClient.signInIntent
                _reAuthNeeded.value = true
                return@withContext null
            }

            // Get fresh access token. All scopes bundled in one request.
            val token = com.google.android.gms.auth.GoogleAuthUtil.getToken(
                context,
                androidAccount,
                scopeString
            )
            cachedToken = token
            token
        } catch (e: UserRecoverableAuthException) {
            // Signed in but scopes not yet granted.
            // CRITICAL: e.intent is the specific OAuth2 permission grant screen for the
            // denied scope. This is NOT the same as signInClient.signInIntent. Launching
            // the wrong intent (sign-in picker) completes without granting the scope, so
            // getToken() keeps throwing this exception on every attempt.
            cachedToken = null
            pendingAuthIntent = e.intent
            _reAuthNeeded.value = true
            null
        } catch (e: GoogleAuthException) {
            // Auth-specific error (bad client ID, account issue, etc.) — prompt re-sign-in
            cachedToken = null
            pendingAuthIntent = signInClient.signInIntent
            _reAuthNeeded.value = true
            null
        } catch (e: Exception) {
            // Non-auth error (network, IO, etc.) — clear cache but don't show auth dialog
            cachedToken = null
            null
        }
    }

    /**
     * Returns cached token if available, otherwise fetches a new one.
     */
    suspend fun getValidToken(): String? {
        return cachedToken ?: getAccessToken()
    }

    /**
     * Check if user has signed in to Google (may still need token refresh).
     */
    fun isSignedIn(): Boolean {
        return GoogleSignIn.getLastSignedInAccount(context) != null
    }

    /**
     * Get the signed-in user's email for display.
     */
    fun getSignedInEmail(): String? {
        return GoogleSignIn.getLastSignedInAccount(context)?.email
    }

    /**
     * Sign out and clear cached tokens.
     */
    suspend fun signOut() {
        cachedToken = null
        withContext(Dispatchers.IO) {
            signInClient.signOut()
        }
    }
}
