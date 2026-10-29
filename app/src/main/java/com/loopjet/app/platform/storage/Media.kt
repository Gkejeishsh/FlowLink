package com.loopjet.app.platform

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.loopjet.app.R
import com.loopjet.app.core.Bytes
import com.loopjet.app.core.FileSink
import com.loopjet.app.core.FileSinkFactory
import com.loopjet.app.core.SafePath
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Writes received media straight into the gallery through MediaStore, with `IS_PENDING` set until the
 * bytes and the checksum are final.
 *
 * Why not "write to app storage, then copy into MediaStore": that copy doubles the disk traffic and
 * the free space needed (a 10 GB video would need 20 GB) and used to be the only way this app made a
 * received photo visible. This path writes positionally into the pending item instead, so the file
 * appears in the gallery the moment it is renamed and completed — one write, no duplicate.
 */
class MediaStoreSink(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val pfd: ParcelFileDescriptor,
    private val channel: FileChannel,
    private val modified: Long,
    private val size: Long,
    private val displayName: String
) : FileSink {

    override val target: File get() = File(displayName)
    override val displayPath: String get() = "Gallery/$displayName"

    override fun writeAt(buffer: ByteArray, offset: Int, length: Int, position: Long) {
        if (length == 0) return
        val bb = ByteBuffer.wrap(buffer, offset, length)
        var pos = position
        while (bb.hasRemaining()) {
            val n = channel.write(bb, pos)
            if (n <= 0) throw java.io.IOException("short write at $pos")
            pos += n
        }
    }

    override fun commit(expectedSha256: String?): File {
        runCatching { channel.force(true) }
        if (expectedSha256 != null) {
            val actual = Bytes.hex(hashStream(resolver, uri))
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                discard()
                throw java.io.IOException("checksum mismatch")
            }
        }
        runCatching { channel.close() }
        runCatching { pfd.close() }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (modified > 0) {
                put(MediaStore.MediaColumns.DATE_MODIFIED, modified / 1000)
                put(MediaStore.MediaColumns.DATE_TAKEN, modified)
            }
        }
        resolver.update(uri, values, null, null)
        return File(displayName)
    }

    override fun discard() {
        runCatching { channel.close() }
        runCatching { pfd.close() }
        runCatching { resolver.delete(uri, null, null) }
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { pfd.close() }
    }

    private fun hashStream(resolver: ContentResolver, uri: Uri): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(512 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }
}

/**
 * Chooses the destination per file: media that the gallery should show goes to MediaStore (API 29+,
 * no storage permission needed), everything else goes through the normal file factory.
 */
class GallerySinkFactory(
    private val context: Context,
    private val fallback: FileSinkFactory,
    private val album: String
) : FileSinkFactory {

    private val resolver: ContentResolver get() = context.contentResolver

    override fun prepare(relativePath: String, size: Long, modified: Long, mime: String): FileSink {
        val safe = SafePath.sanitize(relativePath)
            ?: return fallback.prepare(relativePath, size, modified, mime)
        if (Build.VERSION.SDK_INT < 29 || !isGalleryMedia(mime)) {
            return fallback.prepare(relativePath, size, modified, mime)
        }
        val name = safe.substringAfterLast('/')
        val subDir = safe.substringBeforeLast('/', "")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir(mime, subDir))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (modified > 0) put(MediaStore.MediaColumns.DATE_TAKEN, modified)
            put(MediaStore.MediaColumns.SIZE, size)
        }
        val collection = collectionFor(mime)
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
            ?: return fallback.prepare(relativePath, size, modified, mime)
        val pfd = runCatching { resolver.openFileDescriptor(uri, "rw") }.getOrNull()
        if (pfd == null) {
            runCatching { resolver.delete(uri, null, null) }
            return fallback.prepare(relativePath, size, modified, mime)
        }
        val channel = FileOutputStream(pfd.fileDescriptor).channel
        return MediaStoreSink(resolver, uri, pfd, channel, modified, size, name)
    }

    override fun makeDirectory(relativePath: String) {
        // MediaStore has no empty directories; the album folder is created by the first file.
        runCatching { fallback.makeDirectory(relativePath) }
    }

    override fun freeBytes(): Long = fallback.freeBytes()

    override fun rootLabel(): String = "${fallback.rootLabel()} · gallery"

    override fun exists(relativePath: String, size: Long): Boolean = fallback.exists(relativePath, size)

    override fun canStore(size: Long): Boolean = fallback.canStore(size)

    private fun isGalleryMedia(mime: String): Boolean =
        mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")

    private fun collectionFor(mime: String): Uri = when {
        mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    /**
     * Keeps the sender's folder structure inside a top-level album. MediaStore only accepts a
     * standard top-level directory, so anything unusual is flattened into the album.
     */
    private fun relativeDir(mime: String, subDir: String): String {
        val base = when {
            mime.startsWith("image/") -> "DCIM"
            mime.startsWith("video/") -> "DCIM"
            else -> "Music"
        }
        val cleaned = subDir.split('/').filter { it.isNotBlank() && it != ".." }
            .joinToString("/") { SafePath.sanitizeComponent(it) ?: "" }
            .trim('/')
        return if (cleaned.isEmpty()) "$base/$album/" else "$base/$album/$cleaned/"
    }
}

/**
 * Makes already-written files visible immediately and keeps the original modified date.
 *
 * Used for files that were written by the file factory (documents, APKs, or media on API 26-28) and
 * for completed browser uploads. Nothing is copied here when the file already lives in a public
 * collection; only files inside app-private storage are re-created in MediaStore.
 */
object MediaIndex {
    private const val TAG = "FlowLinkMedia"

    fun index(context: Context, file: File, mime: String) {
        if (!file.isFile) return
        try {
            if (Build.VERSION.SDK_INT >= 29 && isMedia(mime) && !isPubliclyVisible(file)) {
                // The file sits in app-private storage where no gallery looks; publish it.
                if (publishCopy(context, file, mime)) return
            }
            if (Build.VERSION.SDK_INT >= 29 && isMedia(mime) && isPubliclyVisible(file)) {
                scan(context, file, mime)
                stampModified(context, file, mime)
                return
            }
            scan(context, file, mime)
        } catch (t: Throwable) {
            Log.w(TAG, "could not index ${file.name}", t)
        }
    }

    private fun isMedia(mime: String): Boolean =
        mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")

    private fun scan(context: Context, file: File, mime: String) {
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
    }

    /** True when the file is somewhere MediaScanner can publish without an app-storage rewrite. */
    private fun isPubliclyVisible(file: File): Boolean {
        val path = file.absolutePath
        val root = Environment.getExternalStorageDirectory().absolutePath
        if (!path.startsWith(root)) return false
        val relative = path.removePrefix(root)
        return !relative.startsWith("/Android/")
    }

    private fun stampModified(context: Context, file: File, mime: String) {
        if (file.lastModified() <= 0) return
        runCatching {
            val collection = when {
                mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATE_MODIFIED, file.lastModified() / 1000)
            }
            context.contentResolver.update(
                collection,
                values,
                "${MediaStore.MediaColumns.DATA}=?",
                arrayOf(file.absolutePath)
            )
        }
    }

    /**
     * Re-creates an app-private file as a MediaStore item. Only used when the receiver had no
     * all-files access; the gallery copy is what the user sees, so the private copy is removed.
     */
    private fun publishCopy(context: Context, file: File, mime: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/FlowLink/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, file.lastModified())
        }
        val collection = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val resolver = context.contentResolver
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return false
        val ok = runCatching {
            // Streamed, never read into memory: this path can carry a multi-gigabyte video.
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out, 512 * 1024) }
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }.isSuccess
        if (ok) {
            file.delete()
            return true
        }
        runCatching { resolver.delete(uri, null, null) }
        return false
    }

    fun notifyFinished(context: Context, ok: Boolean, message: String) {
        val notification = NotificationCompat.Builder(context, FlowLinkApp.CHANNEL_TRANSFER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (ok) "FlowLink finished" else "FlowLink stopped")
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
