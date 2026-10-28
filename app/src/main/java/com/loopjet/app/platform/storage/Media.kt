package com.loopjet.app.platform

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.loopjet.app.R
import java.io.File

/**
 * Makes received media visible immediately and keeps the original modified date.
 *
 * We write files directly (no SAF round-trip) and then ask MediaStore to index them. On API 29+ the
 * insert happens with `IS_PENDING` so the gallery never shows a half-written file.
 */
object MediaIndex {
    private const val TAG = "LoopJetMedia"

    fun index(context: Context, file: File, mime: String) {
        if (!file.isFile) return
        try {
            if (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) {
                if (Build.VERSION.SDK_INT >= 29) {
                    insertIntoMediaStore(context, file, mime)
                } else {
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
                }
            } else {
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not index ${file.name}", t)
        }
        // Preserve the sender's timestamp — gallery sorting depends on it.
        if (file.lastModified() > 0) file.setLastModified(file.lastModified())
    }

    private fun insertIntoMediaStore(context: Context, file: File, mime: String) {
        val collection = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val relative = relativeToStorage(file) ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri: Uri? = runCatching { resolver.insert(collection, values) }.getOrNull()
        if (uri == null) {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
            return
        }
        runCatching {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 512 * 1024) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            // The MediaStore copy now owns the bytes; remove the duplicate on disk.
            file.delete()
        }.onFailure {
            Log.w(TAG, "MediaStore insert failed, keeping file on disk", it)
            runCatching { resolver.delete(uri, null, null) }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        }
    }

    private fun relativeToStorage(file: File): String? {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val path = file.absolutePath
        if (!path.startsWith(root)) return null
        val relative = path.removePrefix(root).trimStart('/')
        val dir = relative.substringBeforeLast('/', "")
        return if (dir.isEmpty()) null else "$dir/"
    }

    fun notifyFinished(context: Context, ok: Boolean, message: String) {
        val notification = NotificationCompat.Builder(context, LoopJetApp.CHANNEL_TRANSFER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (ok) "LoopJet finished" else "LoopJet stopped")
            .setContentText(message)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(FINISHED_ID, notification)
        }
    }

    private const val FINISHED_ID = 4102
}
