package io.github.bossincrypto.velox

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters

/**
 * One ExoPlayer per process, shared by [PlayerActivity] and [PlaybackService].
 *
 * This is the single most important performance decision in the app: the Activity talks to
 * the player *directly* instead of through a MediaController, so play/pause/seek are plain
 * method calls with no Binder round trip. The service lives in the same process and only
 * wraps the same instance in a MediaSession for the notification and background playback.
 *
 * Keeping the instance alive across Activity recreation also keeps the MediaCodec decoder
 * warm - re-creating it is what makes most players stutter on rotate or PiP transitions.
 */
@OptIn(UnstableApi::class)
object PlayerHolder {

    private var instance: ExoPlayer? = null

    fun get(context: Context): ExoPlayer =
        instance ?: build(context.applicationContext).also { instance = it }

    fun peek(): ExoPlayer? = instance

    fun release() {
        instance?.release()
        instance = null
    }

    /**
     * CLOSEST_SYNC lands on the nearest keyframe: no decode-and-discard pass, so a seek
     * shows a frame in a few milliseconds instead of a few hundred. EXACT is available in
     * settings for people who need frame-accurate positions.
     */
    fun applySeekParameters(player: ExoPlayer) {
        player.setSeekParameters(
            if (Prefs.fastSeek) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT
        )
    }

    private fun build(context: Context): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            // Platform decoders only - no bundled software codecs, no extra MBs in the APK.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            // If a hardware decoder refuses a stream, fall through to the next one instead
            // of failing playback outright.
            .setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 5_000,
                /* maxBufferMs = */ 30_000,
                // Start rendering after 250 ms instead of the 2.5 s default: this is what
                // makes opening a file and resuming after a seek feel instant.
                /* bufferForPlaybackMs = */ 250,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500,
            )
            // Keep 20 s of already-played media in memory so backwards seeks inside that
            // window need no I/O and no decoder flush at all.
            .setBackBuffer(20_000, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .build()

        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(audio, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(Prefs.seekStepMs.toLong())
            .setSeekForwardIncrementMs(Prefs.seekStepMs.toLong())
            .build()
            .apply {
                applySeekParameters(this)
                playbackParameters = playbackParameters.withSpeed(Prefs.speed)
            }
    }
}
