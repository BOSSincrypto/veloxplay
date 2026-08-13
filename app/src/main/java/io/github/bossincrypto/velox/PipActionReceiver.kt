package io.github.bossincrypto.velox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the play/pause and step buttons rendered inside the picture-in-picture window. */
class PipActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val player = PlayerHolder.peek() ?: return
        when (intent.getIntExtra(EXTRA_ACTION, -1)) {
            ACTION_TOGGLE -> if (player.isPlaying) player.pause() else player.play()
            ACTION_BACK -> player.seekBack()
            ACTION_FORWARD -> player.seekForward()
        }
    }

    companion object {
        const val INTENT_ACTION = "io.github.bossincrypto.velox.PIP_ACTION"
        const val EXTRA_ACTION = "action"
        const val ACTION_TOGGLE = 0
        const val ACTION_BACK = 1
        const val ACTION_FORWARD = 2
    }
}
