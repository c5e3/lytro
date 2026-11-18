package app.refocus.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds.coerceAtLeast(1)} s"
    seconds < 3600 -> "${(seconds + 30) / 60} min"
    else -> "%d h %02d min".format(seconds / 3600, (seconds % 3600) / 60)
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.0f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

private val zone: ZoneId get() = ZoneId.systemDefault()

fun formatDay(t: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()).format(t.atZone(zone))

fun formatDateTime(t: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()).format(t.atZone(zone))

fun formatShutter(seconds: Double): String =
    if (seconds >= 0.5) "%.1f s".format(seconds) else "1/%d s".format((1 / seconds).let { Math.round(it) })
