package io.github.bossincrypto.velox

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Wraps the shared [PlayerHolder] player in a MediaSession so playback survives leaving the
 * Activity and shows up in the notification shade / on the lock screen.
 *
 * It creates no player of its own - same process, same instance, zero IPC on the hot path.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val openPlayer = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, PlayerHolder.get(this))
            .setSessionActivity(openPlayer)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the task away should not leave a paused notification behind. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        PlayerHolder.release()
        super.onDestroy()
    }
}
