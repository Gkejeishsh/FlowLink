package com.flowlink.drop.server

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * The last [limit] requests, kept for the diagnostics screen.
 *
 * This exists because "it didn't work" is not a bug report. With a request log the phone can say
 * *which* request arrived, from where, and how it ended — which is usually the whole answer.
 */
class RequestLog(private val limit: Int = 50) {

    data class Entry(
        val time: Long,
        val remote: String,
        val method: String,
        val path: String,
        val bytes: Long,
        val status: Int,
        val durationMs: Long
    )

    private val entries = ArrayDeque<Entry>()

    fun add(entry: Entry) = synchronized(entries) {
        entries.addLast(entry)
        while (entries.size > limit) entries.removeFirst()
    }

    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    fun clear() = synchronized(entries) { entries.clear() }

    fun asText(): String {
        val format = SimpleDateFormat("HH:mm:ss", Locale.US)
        val rows = snapshot().map { entry ->
            val direction = if (entry.bytes > 0) "${entry.bytes} B" else "-"
            "${format.format(Date(entry.time))}  ${entry.method.padEnd(4)} ${entry.path.take(48)}"
                .padEnd(64) + " ${entry.status}  $direction  ${entry.durationMs}ms  from ${entry.remote}"
        }
        return if (rows.isEmpty()) "no requests yet" else rows.joinToString("\n")
    }
}
