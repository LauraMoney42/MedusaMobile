package com.medusa.mobile.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred

/**
 * PermissionManager — bridges Android's ActivityResultLauncher permission flow
 * into a coroutine-friendly suspend API so tools can request permissions inline
 * without callbacks.
 *
 * Lifecycle:
 *   1. MainActivity.onCreate() registers an ActivityResultLauncher and calls
 *      PermissionManager.initialize(launcher).
 *   2. ToolDispatcher (or any coroutine) calls ensurePermission(context, permission).
 *      If not granted, it shows the native Android dialog and suspends.
 *   3. MainActivity's ActivityResultCallback calls onPermissionResult(results),
 *      completing the suspended coroutine.
 *
 * Why a singleton object?
 *   Permission requests must be initiated by the Activity (via its registered launcher).
 *   Tools only have a Context, not an Activity reference. A singleton lets tools reach
 *   the launcher through the process without threading an Activity reference everywhere.
 *
 * Thread safety: Call from the main thread (viewModelScope, LaunchedEffect, etc.).
 *   Permission dialogs are shown by Android on the main thread and are serial by design.
 */
object PermissionManager {

    // ── Permissions required for Medusa's full feature set ───────────────────

    /** All permissions requested on the first-launch screen. */
    val REQUIRED_PERMISSIONS: List<String> = buildList {
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        // Photo permissions differ by Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    // ── Internal state ───────────────────────────────────────────────────────

    private var launcher: ActivityResultLauncher<Array<String>>? = null

    // Only one permission request can be in flight at a time — Android enforces this anyway.
    private var pendingDeferred: CompletableDeferred<Map<String, Boolean>>? = null

    // ── Activity wiring ──────────────────────────────────────────────────────

    /**
     * Must be called from MainActivity.onCreate() (before setContent) with the
     * ActivityResultLauncher registered via registerForActivityResult().
     */
    fun initialize(launcher: ActivityResultLauncher<Array<String>>) {
        this.launcher = launcher
    }

    /**
     * Called from the ActivityResultCallback registered in MainActivity.
     * Completes any suspended coroutine waiting on a permission result.
     */
    fun onPermissionResult(results: Map<String, Boolean>) {
        pendingDeferred?.complete(results)
        pendingDeferred = null
    }

    // ── Permission checks ────────────────────────────────────────────────────

    /** Returns true if the given permission is currently granted. */
    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Returns true if ALL required permissions are granted. */
    fun allRequiredGranted(context: Context): Boolean =
        REQUIRED_PERMISSIONS.all { isGranted(context, it) }

    /** Returns which required permissions are still missing. */
    fun missingPermissions(context: Context): List<String> =
        REQUIRED_PERMISSIONS.filter { !isGranted(context, it) }

    // ── Permission requests (suspend) ────────────────────────────────────────

    /**
     * Shows the native Android permission dialog for the given permissions.
     * Suspends until the user responds (accepts or denies).
     *
     * Returns a map of permission → was it granted?
     *
     * If the launcher isn't initialized (shouldn't happen after MainActivity.onCreate),
     * returns all false immediately rather than crashing.
     */
    suspend fun requestPermissions(vararg permissions: String): Map<String, Boolean> {
        val l = launcher
            ?: return permissions.associateWith { false }

        val deferred = CompletableDeferred<Map<String, Boolean>>()
        pendingDeferred = deferred
        l.launch(arrayOf(*permissions))
        return deferred.await()
    }

    /**
     * Ensures a single permission is granted.
     * Shows the dialog if not already granted.
     * Returns true if the permission is now granted, false if denied.
     */
    suspend fun ensurePermission(context: Context, permission: String): Boolean {
        if (isGranted(context, permission)) return true
        val results = requestPermissions(permission)
        return results[permission] == true
    }

    /**
     * Requests all currently-missing required permissions in one batch.
     * Used by PermissionsSetupScreen on first launch.
     */
    suspend fun requestAllRequired(context: Context): Map<String, Boolean> {
        val missing = missingPermissions(context)
        if (missing.isEmpty()) return REQUIRED_PERMISSIONS.associateWith { true }
        return requestPermissions(*missing.toTypedArray())
    }
}
