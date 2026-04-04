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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
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
    }

    private val signInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope(SCOPE_DOCS),
                Scope(SCOPE_DRIVE_FILE),
                Scope(SCOPE_DRIVE_READONLY)
            )
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    // Cached access token (in-memory only — refreshed each session)
    @Volatile
    private var cachedToken: String? = null

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
        try {
            // Check for existing sign-in
            val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null

            // Get fresh access token using GoogleAuthUtil
            val token = com.google.android.gms.auth.GoogleAuthUtil.getToken(
                context,
                account.account!!,
                "oauth2:$SCOPE_DOCS $SCOPE_DRIVE_FILE $SCOPE_DRIVE_READONLY"
            )
            cachedToken = token
            token
        } catch (e: Exception) {
            // Token might be expired — clear cache and return null
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
