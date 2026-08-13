package io.github.bossincrypto.velox

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Every setting the app has, in one SharedPreferences file.
 *
 * ponytail: no DataStore, no DI. SharedPreferences caches in memory after the first load
 * and the whole file is a few dozen bytes, so the async machinery would cost more than it saves.
 */
object Prefs {

    private const val FILE = "velox"

    private const val KEY_SPEED = "speed"
    private const val KEY_HOLD_SPEED = "hold_speed"
    private const val KEY_SEEK_STEP = "seek_step_ms"
    private const val KEY_RESIZE = "resize_mode"
    private const val KEY_FAST_SEEK = "fast_seek"
    private const val KEY_RESUME = "resume"
    private const val KEY_GESTURES = "gestures"
    private const val KEY_BACKGROUND = "background_play"
    private const val POS_PREFIX = "pos:"

    /** Positions older than this are pruned so the prefs file cannot grow forever. */
    private const val MAX_REMEMBERED = 200

    const val MIN_SPEED = 0.25f
    const val MAX_SPEED = 4.0f

    /** Presets offered in the speed sheet. */
    val SPEED_PRESETS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** Global playback speed - applied to every video, persisted across launches. */
    var speed: Float
        get() = sp.getFloat(KEY_SPEED, 1.0f)
        set(v) = sp.edit { putFloat(KEY_SPEED, v.coerceIn(MIN_SPEED, MAX_SPEED)) }

    /** Speed used while the screen is held down, then released back to [speed]. */
    var holdSpeed: Float
        get() = sp.getFloat(KEY_HOLD_SPEED, 2.0f)
        set(v) = sp.edit { putFloat(KEY_HOLD_SPEED, v.coerceIn(MIN_SPEED, MAX_SPEED)) }

    /** Double-tap / button seek step. */
    var seekStepMs: Int
        get() = sp.getInt(KEY_SEEK_STEP, 10_000)
        set(v) = sp.edit { putInt(KEY_SEEK_STEP, v) }

    /** One of AspectRatioFrameLayout.RESIZE_MODE_*. */
    var resizeMode: Int
        get() = sp.getInt(KEY_RESIZE, 0)
        set(v) = sp.edit { putInt(KEY_RESIZE, v) }

    /** Snap seeks to the nearest keyframe - instant, at most a keyframe off. */
    var fastSeek: Boolean
        get() = sp.getBoolean(KEY_FAST_SEEK, true)
        set(v) = sp.edit { putBoolean(KEY_FAST_SEEK, v) }

    var resumePlayback: Boolean
        get() = sp.getBoolean(KEY_RESUME, true)
        set(v) = sp.edit { putBoolean(KEY_RESUME, v) }

    var gesturesEnabled: Boolean
        get() = sp.getBoolean(KEY_GESTURES, true)
        set(v) = sp.edit { putBoolean(KEY_GESTURES, v) }

    var backgroundPlayback: Boolean
        get() = sp.getBoolean(KEY_BACKGROUND, true)
        set(v) = sp.edit { putBoolean(KEY_BACKGROUND, v) }

    // --- resume positions -------------------------------------------------

    fun positionOf(key: String): Long =
        if (resumePlayback) sp.getLong(POS_PREFIX + key, 0L) else 0L

    fun savePosition(key: String, positionMs: Long, durationMs: Long) {
        if (!resumePlayback) return
        // Near the start or near the end there is nothing worth resuming.
        val meaningful = positionMs > 5_000L &&
            (durationMs <= 0L || positionMs < durationMs - 10_000L)
        sp.edit {
            if (meaningful) putLong(POS_PREFIX + key, positionMs) else remove(POS_PREFIX + key)
        }
        pruneIfNeeded()
    }

    private fun pruneIfNeeded() {
        val keys = sp.all.keys.filter { it.startsWith(POS_PREFIX) }
        if (keys.size <= MAX_REMEMBERED) return
        // ponytail: no LRU bookkeeping - drop an arbitrary slice, upgrade to timestamps
        // only if someone actually complains about losing a position.
        sp.edit { keys.take(keys.size - MAX_REMEMBERED).forEach { remove(it) } }
    }
}
