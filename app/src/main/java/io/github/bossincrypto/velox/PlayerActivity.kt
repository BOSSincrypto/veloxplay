package io.github.bossincrypto.velox

import android.Manifest
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import io.github.bossincrypto.velox.databinding.ActivityPlayerBinding
import io.github.bossincrypto.velox.databinding.SheetSpeedBinding
import kotlin.math.abs

/**
 * The player screen.
 *
 * Performance notes, because they drove most of the code here:
 *  - the ExoPlayer instance comes from [PlayerHolder] and is never rebuilt by this Activity,
 *    so rotation and PiP transitions do not touch the decoder;
 *  - video renders into a plain SurfaceView, which the compositor can put on a hardware
 *    overlay - no GPU copy per frame the way a TextureView needs;
 *  - the progress ticker only runs while something is actually playing, and only touches
 *    views while the overlay is visible;
 *  - dragging the seek bar turns on Media3 scrubbing mode, which keeps the pipeline in a
 *    seek-optimised state instead of doing a full flush per drag event.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var player: ExoPlayer
    private lateinit var audioManager: AudioManager

    private val handler = Handler(Looper.getMainLooper())

    private var resumeKey: String? = null
    private var videoAspect = 16f / 9f
    private var zoom = 1f
    private var scrubbing = false
    private var holdingSpeed = false
    private var abStart = C.TIME_UNSET
    private var abEnd = C.TIME_UNSET

    private var gestureMode = GESTURE_NONE
    private var gestureStartValue = 0f
    private var seekTarget = 0L

    private val hideControls = Runnable { setControlsVisible(false) }

    /** Without this the media notification is hidden on Android 13+, so background
     *  playback would run with no way to control or stop it. */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val tick = object : Runnable {
        override fun run() {
            syncProgress()
            checkAbLoop()
            // 4 Hz is plenty for a seek bar and costs nothing; skip entirely when paused.
            if (player.isPlaying) handler.postDelayed(this, 250L)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height == 0) return
            videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            applyAspect()
            updatePipParams()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.play.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            binding.root.keepScreenOn = isPlaying
            handler.removeCallbacks(tick)
            if (isPlaying) handler.post(tick) else syncProgress()
            updatePipParams()
        }

        override fun onPlaybackStateChanged(state: Int) {
            binding.buffering.isVisible = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) syncProgress()
        }
    }

    // --- lifecycle --------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        audioManager = getSystemService(AudioManager::class.java)

        player = PlayerHolder.get(this)
        player.addListener(playerListener)

        binding.videoFrame.resizeMode = Prefs.resizeMode
        binding.surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = player.setVideoSurfaceView(binding.surface)
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })

        wireControls()
        wireGestures()
        askForNotificationsIfNeeded()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        immersive()
        player.setVideoSurfaceView(binding.surface)
        if (player.isPlaying) handler.post(tick)
        syncProgress()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        handler.removeCallbacks(tick)
        if (!isInPictureInPictureMode && !Prefs.backgroundPlayback) player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player.removeListener(playerListener)
        if (isFinishing && !player.isPlaying) {
            player.clearMediaItems()
            stopService(Intent(this, PlaybackService::class.java))
        }
        super.onDestroy()
    }

    private fun askForNotificationsIfNeeded() {
        if (!Prefs.backgroundPlayback) return
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // --- media ------------------------------------------------------------

    private fun handleIntent(intent: Intent) {
        val uri: Uri = intent.data ?: return
        val key = uri.toString()
        // Re-entering the same video (PiP restore, notification tap) must not reload it.
        if (resumeKey == key && player.mediaItemCount > 0) return

        savePosition()
        resumeKey = key
        binding.title.text = intent.getStringExtra(EXTRA_TITLE)
            ?: uri.lastPathSegment.orEmpty()

        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        val resumeAt = Prefs.positionOf(key)
        if (resumeAt > 0L) player.seekTo(resumeAt)
        player.setPlaybackSpeed(Prefs.speed)
        player.playWhenReady = true

        binding.speed.text = Format.speed(Prefs.speed)
        startService(Intent(this, PlaybackService::class.java))
    }

    private fun savePosition() {
        val key = resumeKey ?: return
        if (player.mediaItemCount == 0) return
        Prefs.savePosition(key, player.currentPosition, player.duration)
    }

    private fun seekBy(deltaMs: Long) {
        val duration = player.duration
        val target = (player.currentPosition + deltaMs)
            .coerceAtLeast(0L)
            .let { if (duration > 0) it.coerceAtMost(duration) else it }
        player.seekTo(target)
        showHud(
            (if (deltaMs > 0) "+" else "") + (deltaMs / 1000) + "s\n" + Format.duration(target)
        )
    }

    // --- controls ---------------------------------------------------------

    private fun wireControls() {
        binding.back.setOnClickListener { finish() }
        binding.play.setOnClickListener {
            if (player.isPlaying) player.pause() else player.play()
            showControlsTemporarily()
        }
        binding.rewind.setOnClickListener {
            seekBy(-Prefs.seekStepMs.toLong()); showControlsTemporarily()
        }
        binding.forward.setOnClickListener {
            seekBy(Prefs.seekStepMs.toLong()); showControlsTemporarily()
        }
        binding.pip.setOnClickListener { enterPip() }
        binding.speed.setOnClickListener { showSpeedSheet() }
        binding.resize.setOnClickListener { cycleResizeMode() }
        binding.snapshot.setOnClickListener { saveSnapshot() }
        binding.abLoop.setOnClickListener { cycleAbLoop() }
        binding.speed.text = Format.speed(Prefs.speed)

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = player.duration
                if (duration <= 0) return
                val target = duration * progress / SEEK_BAR_MAX
                binding.position.text = Format.duration(target)
                player.seekTo(target)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                scrubbing = true
                handler.removeCallbacks(hideControls)
                // Tells Media3 to stay in a seek-optimised state for the whole drag.
                player.setScrubbingModeEnabled(true)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                scrubbing = false
                player.setScrubbingModeEnabled(false)
                showControlsTemporarily()
            }
        })
        binding.seekBar.max = SEEK_BAR_MAX
        showControlsTemporarily()
    }

    private fun syncProgress() {
        if (!binding.controls.isVisible || scrubbing) return
        val duration = player.duration
        val position = player.currentPosition
        binding.position.text = Format.duration(position)
        binding.duration.text = Format.duration(duration)
        binding.seekBar.progress =
            if (duration > 0) (position * SEEK_BAR_MAX / duration).toInt() else 0
        binding.seekBar.secondaryProgress =
            if (duration > 0) (player.bufferedPosition * SEEK_BAR_MAX / duration).toInt() else 0
    }

    private fun setControlsVisible(visible: Boolean) {
        binding.controls.isVisible = visible
        if (visible) {
            syncProgress()
            immersive()
        }
    }

    private fun showControlsTemporarily() {
        setControlsVisible(true)
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, CONTROLS_TIMEOUT)
    }

    private fun immersive() {
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // --- speed ------------------------------------------------------------

    private fun showSpeedSheet() {
        handler.removeCallbacks(hideControls)
        val sheet = SheetSpeedBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)

        fun apply(value: Float) {
            val clamped = value.coerceIn(Prefs.MIN_SPEED, Prefs.MAX_SPEED)
            Prefs.speed = clamped
            player.setPlaybackSpeed(clamped)
            sheet.value.text = Format.speed(clamped)
            binding.speed.text = Format.speed(clamped)
        }

        sheet.slider.valueFrom = Prefs.MIN_SPEED
        sheet.slider.valueTo = Prefs.MAX_SPEED
        sheet.slider.stepSize = 0.05f
        sheet.slider.value = Prefs.speed
        sheet.value.text = Format.speed(Prefs.speed)
        sheet.slider.addOnChangeListener(Slider.OnChangeListener { _, v, _ -> apply(v) })

        Prefs.SPEED_PRESETS.forEachIndexed { index, preset ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = Format.speed(preset)
                isCheckable = false
                id = View.generateViewId()
                setOnClickListener { sheet.slider.value = preset }
            }
            sheet.presets.addView(chip, index)
        }

        dialog.setOnDismissListener { showControlsTemporarily() }
        dialog.show()
    }

    // --- picture in picture ----------------------------------------------

    private fun enterPip() {
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    private fun updatePipParams() {
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun pipParams(): PictureInPictureParams {
        // Android rejects aspect ratios outside roughly 1:2.39 .. 2.39:1.
        val ratio = videoAspect.coerceIn(0.45f, 2.35f)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
            .setSeamlessResizeEnabled(true)
            // Auto-enter is why pressing Home mid-video slides straight into PiP.
            .setAutoEnterEnabled(true)
            .setActions(pipActions())
            .build()
    }

    private fun pipActions(): List<RemoteAction> {
        fun action(iconRes: Int, labelRes: Int, code: Int) = RemoteAction(
            Icon.createWithResource(this, iconRes),
            getString(labelRes),
            getString(labelRes),
            android.app.PendingIntent.getBroadcast(
                this,
                code,
                Intent(PipActionReceiver.INTENT_ACTION)
                    .setComponent(ComponentName(this, PipActionReceiver::class.java))
                    .putExtra(PipActionReceiver.EXTRA_ACTION, code),
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return listOf(
            action(R.drawable.ic_rewind, R.string.rewind, PipActionReceiver.ACTION_BACK),
            if (player.isPlaying) {
                action(R.drawable.ic_pause, R.string.pause, PipActionReceiver.ACTION_TOGGLE)
            } else {
                action(R.drawable.ic_play, R.string.play, PipActionReceiver.ACTION_TOGGLE)
            },
            action(R.drawable.ic_forward, R.string.forward, PipActionReceiver.ACTION_FORWARD),
        )
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            handler.removeCallbacks(hideControls)
            setControlsVisible(false)
        } else {
            showControlsTemporarily()
        }
    }

    // --- framing, snapshot, A-B ------------------------------------------

    private fun applyAspect() {
        binding.videoFrame.setAspectRatio(videoAspect)
        binding.videoFrame.scaleX = zoom
        binding.videoFrame.scaleY = zoom
    }

    private fun cycleResizeMode() {
        val next = when (Prefs.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        Prefs.resizeMode = next
        binding.videoFrame.resizeMode = next
        // Cycling the frame mode is also the way to undo a pinch zoom.
        zoom = 1f
        applyAspect()
        showHud(
            getString(
                when (next) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> R.string.resize_crop
                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> R.string.resize_stretch
                    else -> R.string.resize_fit
                }
            )
        )
        showControlsTemporarily()
    }

    private fun cycleAbLoop() {
        when {
            abStart == C.TIME_UNSET -> {
                abStart = player.currentPosition
                showHud(getString(R.string.ab_start_set, Format.duration(abStart)))
            }
            abEnd == C.TIME_UNSET -> {
                val candidate = player.currentPosition
                if (candidate <= abStart) {
                    showHud(getString(R.string.ab_invalid))
                } else {
                    abEnd = candidate
                    showHud(getString(R.string.ab_loop_on))
                }
            }
            else -> {
                abStart = C.TIME_UNSET
                abEnd = C.TIME_UNSET
                showHud(getString(R.string.ab_loop_off))
            }
        }
        binding.abLoop.isSelected = abStart != C.TIME_UNSET
        showControlsTemporarily()
    }

    private fun checkAbLoop() {
        if (abStart == C.TIME_UNSET || abEnd == C.TIME_UNSET) return
        if (player.currentPosition >= abEnd) player.seekTo(abStart)
    }

    private fun saveSnapshot() {
        val uri = intent.data ?: return
        val positionUs = player.currentPosition * 1000
        val name = "velox_" + System.currentTimeMillis() + ".jpg"
        showHud(getString(R.string.snapshot_saving))
        VideoLibrary.submit {
            val frame = runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(this, uri)
                    retriever.getFrameAtTime(
                        positionUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                }
            }.getOrNull()
            val ok = frame != null && writeToGallery(frame, name)
            frame?.recycle()
            binding.root.post {
                showHud(getString(if (ok) R.string.snapshot_saved else R.string.snapshot_failed))
            }
        }
    }

    private fun writeToGallery(bitmap: Bitmap, name: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Velox")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = contentResolver.insert(collection, values) ?: return false
        return runCatching {
            contentResolver.openOutputStream(target)!!.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(target, values, null, null)
            true
        }.getOrDefault(false)
    }

    // --- gestures ---------------------------------------------------------

    private fun wireGestures() {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    zoom = (zoom * detector.scaleFactor).coerceIn(1f, 3f)
                    applyAspect()
                    showHud((zoom * 100).toInt().toString() + "%")
                    return true
                }
            },
        )

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (binding.controls.isVisible) setControlsVisible(false) else showControlsTemporarily()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!Prefs.gesturesEnabled) return false
                val step = Prefs.seekStepMs.toLong()
                seekBy(if (e.x < binding.root.width / 2f) -step else step)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!Prefs.gesturesEnabled || holdingSpeed) return
                holdingSpeed = true
                player.setPlaybackSpeed(Prefs.holdSpeed)
                showHud(Format.speed(Prefs.holdSpeed))
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (!Prefs.gesturesEnabled || e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (gestureMode == GESTURE_NONE) {
                    if (abs(dx) < TOUCH_SLOP && abs(dy) < TOUCH_SLOP) return false
                    gestureMode = when {
                        abs(dx) > abs(dy) -> GESTURE_SEEK.also { seekTarget = player.currentPosition }
                        e1.x < binding.root.width / 2f -> GESTURE_BRIGHTNESS.also {
                            gestureStartValue = currentBrightness()
                        }
                        else -> GESTURE_VOLUME.also {
                            gestureStartValue =
                                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                        }
                    }
                }
                when (gestureMode) {
                    GESTURE_SEEK -> previewSeek(dx)
                    GESTURE_BRIGHTNESS -> adjustBrightness(dy)
                    GESTURE_VOLUME -> adjustVolume(dy)
                }
                return true
            }
        })

        binding.root.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            // A second finger means the user is zooming, not seeking - drop any drag in flight.
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                gestureMode = GESTURE_NONE
            }
            if (!scaleDetector.isInProgress) detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                if (holdingSpeed) {
                    holdingSpeed = false
                    player.setPlaybackSpeed(Prefs.speed)
                    hideHud()
                }
                if (gestureMode == GESTURE_SEEK) player.seekTo(seekTarget)
                if (gestureMode != GESTURE_NONE) {
                    gestureMode = GESTURE_NONE
                    hideHud()
                }
                view.performClick()
            }
            true
        }
    }

    private fun previewSeek(dx: Float) {
        val duration = player.duration
        if (duration <= 0) return
        // A full-width swipe covers 90 s, which keeps fine control on long videos.
        val deltaMs = (dx / binding.root.width * 90_000L).toLong()
        seekTarget = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        showHud(Format.duration(seekTarget) + " / " + Format.duration(duration))
    }

    private fun currentBrightness(): Float {
        val current = window.attributes.screenBrightness
        return if (current < 0f) 0.5f else current
    }

    private fun adjustBrightness(dy: Float) {
        val delta = -dy / binding.root.height
        val value = (gestureStartValue + delta).coerceIn(0.01f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = value }
        showHud(getString(R.string.brightness) + "  " + (value * 100).toInt() + "%")
    }

    private fun adjustVolume(dy: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val delta = -dy / binding.root.height * max
        val value = (gestureStartValue + delta).coerceIn(0f, max.toFloat()).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        showHud(getString(R.string.volume) + "  " + (value * 100 / max) + "%")
    }

    private fun showHud(text: String) {
        binding.hud.text = text
        binding.hud.isVisible = true
        handler.removeCallbacks(hideHudRunnable)
        handler.postDelayed(hideHudRunnable, HUD_TIMEOUT)
    }

    private fun hideHud() {
        handler.removeCallbacks(hideHudRunnable)
        binding.hud.isVisible = false
    }

    private val hideHudRunnable = Runnable { binding.hud.isVisible = false }

    companion object {
        const val EXTRA_TITLE = "title"
        private const val SEEK_BAR_MAX = 10_000
        private const val CONTROLS_TIMEOUT = 3_500L
        private const val HUD_TIMEOUT = 700L
        private const val TOUCH_SLOP = 24f

        private const val GESTURE_NONE = 0
        private const val GESTURE_SEEK = 1
        private const val GESTURE_BRIGHTNESS = 2
        private const val GESTURE_VOLUME = 3
    }
}

