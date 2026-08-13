package io.github.bossincrypto.velox

import android.app.Application

/**
 * Deliberately almost empty: everything heavy (the decoder, the MediaStore scan) is created
 * lazily on the screen that needs it, so cold start does no work it can avoid.
 */
class VeloxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}
