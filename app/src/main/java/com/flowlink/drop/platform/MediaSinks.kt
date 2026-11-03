package com.flowlink.drop.platform

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.flowlink.drop.core.Names
import com.flowlink.drop.server.DropSink
import com.flowlink.drop.server.SinkFactory
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Writes arrivals into the place a human will look for them.
 *
 * On Android 10+ this goes through MediaStore with `IS_PENDING = 1`, which means: no storage
 * permission at all, and the platform itself hides the file until the last byte is in — the
 * "no .part files" rule becomes a property of the OS instead of a convention in this app.
 *
 * On Android 8–9 there is no pending flag, so the file is written as `.part` next to its destination
 * and renamed on commit. Nothing half-written is ever visible either way.
 */
class AndroidSinkFactory(
    private val context: Context,
    private val folder: String
) : SinkFactory {

    override fun create(rawName: String, mime: String): DropSink {
        val name = Names.sanitize(rawName)
        return if (Build.VERSION.SDK_INT >= 29) pendingSink(name, mime) else legacySink(name, mime)
    }

    // ------------------------------------------------------------------ Android 10+

    private fun pendingSink(rawName: String, mime: String): DropSink {
        val resolver = context.contentResolver
        val target = target(rawName, mime)
        val name = uniqueName(resolver, target.collection, target.relative, rawName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(target.collection, values)
            ?: throw java.io.IOException("could not create $name")
        val stream = resolver.openOutputStream(uri)
            ?: throw java.io.IOException("could not open $name")
        return PendingSink(resolver, uri, stream, name, target.label)
    }

    private class PendingSink(
        private val resolver: ContentResolver,
        private val uri: Uri,
        private val stream: OutputStream,
        override val displayName: String,
        private val label: String
    ) : DropSink {
        private var closed = false

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            stream.write(buffer, offset, length)
        }

        override fun commit(): String {
            if (closed) return label
            closed = true
            runCatching { stream.flush() }
            runCatching { stream.close() }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            }
            resolver.update(uri, values, null, null)
            return label
        }

        override fun abort() {
            if (!closed) {
                closed = true
                runCatching { stream.close() }
            }
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    private data class Target(
        val collection: Uri,
        val relative: String,
        val label: String
    )

    private fun target(name: String, mime: String): Target = when {
        Names.isImage(mime) -> Target(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "${Environment.DIRECTORY_PICTURES}/$folder",
            "Pictures/$folder"
        )
        Names.isVideo(mime) -> Target(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "${Environment.DIRECTORY_MOVIES}/$folder",
            "Movies/$folder"
        )
        Names.isAudio(mime) -> Target(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "${Environment.DIRECTORY_MUSIC}/$folder",
            "Music/$folder"
        )
        else -> Target(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "${Environment.DIRECTORY_DOWNLOADS}/$folder",
            "Download/$folder"
        )
    }

    /** Asks MediaStore which names already exist in the destination and picks a free one. */
    private fun uniqueName(
        resolver: ContentResolver,
        collection: Uri,
        relative: String,
        desired: String
    ): String {
        val taken = HashSet<String>()
        runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(relative),
                null
            )?.use { cursor ->
                val column = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext() && column >= 0) {
                    cursor.getString(column)?.let { taken.add(it) }
                }
            }
        }
        return Names.unique(desired, taken::contains)
    }

    // ------------------------------------------------------------------ Android 8–9

    private fun legacySink(name: String, mime: String): DropSink {
        val root = when {
            Names.isImage(mime) -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            Names.isVideo(mime) -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            Names.isAudio(mime) -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }
        val dir = File(root, folder)
        dir.mkdirs()
        val existing = dir.list()?.toHashSet() ?: HashSet()
        val unique = Names.unique(name, existing::contains)
        val target = File(dir, unique)
        val part = File(dir, "$unique.part")
        val stream = FileOutputStream(part).buffered(256 * 1024)
        return LegacySink(context, stream, part, target, unique, folder)
    }

    private class LegacySink(
        private val context: Context,
        private val stream: OutputStream,
        private val part: File,
        private val target: File,
        override val displayName: String,
        private val label: String
    ) : DropSink {
        private var closed = false

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            stream.write(buffer, offset, length)
        }

        override fun commit(): String {
            if (closed) return label
            closed = true
            runCatching { stream.flush() }
            runCatching { stream.close() }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            runCatching {
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(target.absolutePath), null, null
                )
            }
            return label
        }

        override fun abort() {
            if (!closed) {
                closed = true
                runCatching { stream.close() }
            }
            runCatching { part.delete() }
        }
    }
}
