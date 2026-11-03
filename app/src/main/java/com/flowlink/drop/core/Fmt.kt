package com.flowlink.drop.core

/** Number formatting for anything the user reads. Pure Kotlin so it can be unit tested. */
object Fmt {
    fun bytes(value: Long): String {
        if (value < 0) return "—"
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = value.toDouble()
        var unit = -1
        while (size >= 1024 && unit < units.size - 1) {
            size /= 1024.0
            unit++
        }
        return if (size >= 100) "${size.toInt()} ${units[unit]}"
        else String.format(java.util.Locale.US, "%.1f %s", size, units[unit])
    }

    fun rate(bytesPerSecond: Double): String = when {
        bytesPerSecond <= 0 -> "—"
        else -> bytes(bytesPerSecond.toLong()) + "/s"
    }

    fun percent(done: Long, total: Long): Int = when {
        total <= 0 -> 0
        else -> ((done.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }

    /** Round-trips the short strings stored in preferences. */
    fun parseBytes(text: String): Long = when {
        text.isBlank() -> 0L
        else -> {
            val parts = text.trim().split(" ")
            val number = parts.firstOrNull()?.toDoubleOrNull() ?: 0.0
            val factor = when (parts.getOrNull(1)?.uppercase()) {
                "KB" -> 1024.0
                "MB" -> 1024.0 * 1024
                "GB" -> 1024.0 * 1024 * 1024
                "TB" -> 1024.0 * 1024 * 1024 * 1024
                else -> 1.0
            }
            (number * factor).toLong()
        }
    }
}
