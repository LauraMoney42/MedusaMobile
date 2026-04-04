package com.medusa.mobile

import android.app.Application

/**
 * Application subclass — global init point for Medusa Mobile.
 * Initializes singletons (memory store, notification listener registration, etc.)
 * as tools are added by the team.
 */
class MedusaMobileApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Future: init memory store, register notification channels, etc.
    }
}
