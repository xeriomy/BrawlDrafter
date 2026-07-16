package com.xeriomy.brawldrafter.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Lightweight AccessibilityService that ONLY tracks which app is in the foreground.
 *
 * Why: Brawl Stars is a Unity game — its entire UI is one SurfaceView,
 * so AccessibilityService CANNOT read individual brawler names or buttons.
 * But it CAN tell us the foreground package name, which is the most
 * reliable way to prevent scanning the wrong app.
 *
 * Usage: FloatingButtonService checks [currentPackage] before each scan.
 * If the user isn't in Brawl Stars, the scan is skipped entirely —
 * no OCR, no fake data, no wasted processing.
 */
class AppWatcherService : AccessibilityService() {

    companion object {
        /** The package name of the currently foregrounded app, or null if unknown. */
        var currentPackage: String? = null
            private set

        /** Whether the user has enabled this accessibility service. */
        var isEnabled: Boolean = false
            private set

        const val BRAWL_STARS_PACKAGE = "com.supercell.brawlstars"

        fun isBrawlStarsForeground(): Boolean {
            return currentPackage == BRAWL_STARS_PACKAGE
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isEnabled = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Track foreground app on every window state change
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { pkg ->
                currentPackage = pkg
            }
        }
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        super.onDestroy()
        isEnabled = false
        currentPackage = null
    }
}