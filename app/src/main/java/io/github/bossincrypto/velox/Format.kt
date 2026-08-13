package io.github.bossincrypto.velox

import java.util.Locale

/** Tiny formatting helpers shared by the library and the player overlay. */
object Format {

    /** Builds "1:02:03" or "2:05" - no leading hours when the video is under an hour. */
    fun duration(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }

    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var i = 0
        while (value >= 1024 && i < units.lastIndex) {
            value /= 1024
            i++
        }
        return String.format(Locale.US, "%.1f %s", value, units[i])
    }

    /** "1.5x" but "1x" for whole numbers. */
    fun speed(value: Float): String =
        if (value == value.toInt().toFloat()) {
            "${value.toInt()}x"
        } else {
            String.format(Locale.US, "%.2fx", value).replace("0x", "x")
        }
}
