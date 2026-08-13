package io.github.bossincrypto.velox

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * Deliberately almost empty: everything heavy (the decoder, the MediaStore scan) is created
 * lazily on the screen that needs it, so cold start does no work it can avoid.
 */
class VeloxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        // Wallpaper-derived palette where the device supports it. Done here rather than
        // through the theme parent so the themes stay NoActionBar - every screen supplies
        // its own Toolbar, and a decor action bar underneath one is a startup crash.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}