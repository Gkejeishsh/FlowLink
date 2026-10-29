package com.loopjet.app.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Every path that arrives from the network goes through here.
 *
 * The rules are deliberately strict: no absolute paths, no `..`, no control characters, no Windows
 * reserved device names, no trailing dots or spaces, bounded component and total length, and a
 * fallback name when nothing usable survives. A rejected file is reported to the user; it is never
 * silently renamed into something unexpected and never escapes the receive directory.
 */
object SafePath {
    private val ILLEGAL = charArrayOf('<', '>', ':', '"', '\\', '|', '?', '*')
    private val RESERVED = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    )
    private const val MAX_COMPONENT = 96
    private const val MAX_PATH = 200
    private const val MAX_DEPTH = 24

    /** Returns a safe relative path, or null when the name is unusable even after cleaning. */
    fun sanitize(raw: String, fallback: String = "received_file"): String? {
        if (raw.isBlank()) return fallback
        val unified = raw.replace('\\', '/').trim()
        val parts = unified.split('/')
        val out = ArrayList<String>(parts.size)
        for (part in parts) {
            if (part.isEmpty() || part == "." ) continue
            if (part == "..") return null
            val cleaned = sanitizeComponent(part) ?: continue
            out.add(cleaned)
        }
        if (out.isEmpty()) return fallback
        while (out.size > MAX_DEPTH) out.removeAt(0)
        var joined = out.joinToString("/")
        if (joined.length > MAX_PATH) {
            // Trim directory depth first, keeping the file name intact.
            while (out.size > 1 && joined.length > MAX_PATH) {
                out.removeAt(0)
                joined = out.joinToString("/")
            }
            if (joined.length > MAX_PATH) {
                val name = out.removeAt(out.size - 1)
                val trimmed = if (name.length > MAX_COMPONENT) name.takeLast(MAX_COMPONENT) else name
                out.add(trimmed)
                joined = out.joinToString("/")
            }
        }
        return joined.ifBlank { fallback }
    }

    fun sanitizeComponent(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch.code < 0x20 || ch.code == 0x7F -> sb.append('_')
                ILLEGAL.contains(ch) -> sb.append('_')
                else -> sb.append(ch)
            }
        }
        s = sb.toString().trimEnd('.', ' ')
        if (s.isEmpty() || s == "." || s == "..") return null
        if (s.length > MAX_COMPONENT) {
            val dot = s.lastIndexOf('.')
            s = if (dot > 0 && s.length - dot <= 10) {
                val ext = s.substring(dot)
                s.substring(0, MAX_COMPONENT - ext.length) + ext
            } else {
                s.substring(0, MAX_COMPONENT)
            }
        }
        if (RESERVED.contains(s.substringBefore('.').lowercase())) s = "_$s"
        return s
    }

    /** Allocates a non-colliding file under [dir] (`name (1).ext`, `name (2).ext`, ...). */
    fun uniqueFile(dir: File, fileName: String): File {
        val base = File(dir, fileName)
        if (!base.exists() && !File(dir, "$fileName.part").exists()) return base
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var i = 1
        while (i < 10_000) {
            val candidate = File(dir, "$stem ($i)$ext")
            if (!candidate.exists() && !File(dir, "${candidate.name}.part").exists()) return candidate
            i++
        }
        return File(dir, "$stem-${System.currentTimeMillis()}$ext")
    }
}

object Fmt {
    fun bytes(value: Long): String {
        val v = value.coerceAtLeast(0)
        return when {
            v < 1024 -> "$v B"
            v < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", v / 1024.0)
            v < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", v / (1024.0 * 1024))
            v < 1024L * 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", v / (1024.0 * 1024 * 1024))
            else -> String.format(Locale.US, "%.2f TB", v / (1024.0 * 1024 * 1024 * 1024))
        }
    }

    fun rate(bytesPerSecond: Double): String = when {
        bytesPerSecond < 1 -> "0 MB/s"
        bytesPerSecond < 1024 * 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024)
        else -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024))
    }

    fun duration(millis: Long): String {
        if (millis <= 0) return "--"
        val totalSeconds = millis / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.US, "%dm %02ds", m, s)
            else -> String.format(Locale.US, "%ds", s)
        }
    }

    fun date(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestamp))

    fun dayFolder(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))
}

// --- transfer model ------------------------------------------------------------------------------

enum class TransferState {
    IDLE, CONNECTING, WAITING_ACCEPT, TRANSFERRING, PAUSED, VERIFYING, DONE, FAILED, CANCELLED
}

enum class Direction { SEND, RECEIVE }

data class TransferProgress(
    val direction: Direction,
    val sessionId: String,
    val peerName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val instantBytesPerSecond: Long,
    val averageBytesPerSecond: Long,
    val filesDone: Int,
    val filesTotal: Int,
    val currentFiles: List<String>,
    val elapsedMs: Long,
    val etaMs: Long,
    val state: TransferState,
    val pathLabel: String = "",
    val chunkCount: Int = 0,
    val chunksDone: Int = 0,
    /**
     * Bytes written so far, per file id, for files still in flight. Only files with a live sink are
     * included and the map is capped (see [MAX_FILE_PROGRESS_ENTRIES]) so a 2 000-file session does
     * not rebuild a 2 000-entry map every 100 ms — the hot path must not pay for the UI.
     */
    val fileProgress: Map<Int, Long> = emptyMap()
)

/** Upper bound on how many in-flight files a progress tick reports. */
const val MAX_FILE_PROGRESS_ENTRIES: Int = 64

data class TransferOffer(
    val peerName: String,
    val peerFingerprint: String,
    val sas: String,
    val manifest: TransferManifest,
    val freeBytes: Long,
    val knownDevice: Boolean
)

data class OfferDecision(
    val accepted: Boolean,
    val trustDevice: Boolean = false,
    val reason: String = ""
)

data class FileResult(
    val fileId: Int,
    val relPath: String,
    val size: Long,
    val ok: Boolean,
    val message: String = "",
    val installed: Boolean = false
)

class TransferResult(
    val ok: Boolean,
    val message: String,
    val files: List<FileResult>,
    val bytesTransferred: Long,
    val durationMs: Long,
    val chunksTotal: Int,
    val chunksDone: Int,
    val pathLabel: String
)

/** Callbacks from the engine. Implementations must not block the data path. */
interface TransferListener {
    fun onState(state: TransferState, detail: String = "")
    fun onProgress(progress: TransferProgress)
    fun onFileDone(result: FileResult)
    fun onLog(message: String)
    fun onOffer(offer: TransferOffer): OfferDecision = OfferDecision(accepted = true)

    /**
     * Called once every APK of a received app has been committed, so the UI can offer one
     * PackageInstaller session with the base and all splits. The default does nothing (tests, tools).
     */
    fun onAppReady(app: TransferApp, apks: List<File>) {}

    /**
     * Called on the receiving side as soon as the peer's identity is known, so the screen can name it
     * and show the pairing code before the offer dialog appears. Default does nothing.
     */
    fun onPeer(name: String, sas: String) {}
}

/** Simple listener that prints — used by tests and the loopback benchmark. */
class LoggingListener(private val verbose: Boolean = false) : TransferListener {
    val errors = AtomicLong()
    override fun onState(state: TransferState, detail: String) {
        if (verbose) println("state=$state $detail")
    }

    override fun onProgress(progress: TransferProgress) {}

    override fun onFileDone(result: FileResult) {
        if (!result.ok) errors.incrementAndGet()
        if (verbose) println("file ${result.relPath} ok=${result.ok} ${result.message}")
    }

    override fun onLog(message: String) {
        if (verbose) println("log: $message")
    }
}

// --- sinks and sources ---------------------------------------------------------------------------

interface FileSink : Closeable {
    val target: File
    val displayPath: String
    fun writeAt(buffer: ByteArray, offset: Int, length: Int, position: Long)
    /** Finalises the file (rename, index, timestamps). Returns the visible file. */
    fun commit(expectedSha256: String?): File
    fun discard()
}

interface FileSinkFactory {
    fun prepare(relativePath: String, size: Long, modified: Long, mime: String): FileSink
    fun makeDirectory(relativePath: String)
    fun freeBytes(): Long
    fun rootLabel(): String

    /** True when the destination already holds a complete file with this name and size. */
    fun exists(relativePath: String, size: Long): Boolean = false
    /** Bytes that must remain free to consider a file acceptable. */
    fun canStore(size: Long): Boolean = freeBytes() > size + 16L * 1024 * 1024
}

/**
 * Direct, pre-allocated file writes. This is the fast path: no SAF queries, no whole-file buffering,
 * positional writes so several streams can fill one file in parallel.
 */
class LocalFileSink(
    private val file: File,
    private val partFile: File,
    private val modified: Long
) : FileSink {
    // Parent directories must exist before the file is opened. Property initialisers and init
    // blocks run in source order, so the mkdirs() has to come first -- it used to be an init block
    // *after* the RandomAccessFile, which threw for every file staged in a subfolder.
    init {
        partFile.parentFile?.mkdirs()
    }

    private val raf = RandomAccessFile(partFile, "rw")
    private val channel: FileChannel = raf.channel
    private val buffer = ByteBuffer.allocateDirect(0)

    override val target: File get() = file
    override val displayPath: String get() = file.absolutePath

    fun preallocate(size: Long) {
        if (size > 0) {
            raf.setLength(size)
        }
    }

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
        channel.force(true)
        close()
        if (expectedSha256 != null) {
            val actual = Sha256.ofFile(partFile)
            if (Bytes.hex(actual) != expectedSha256.lowercase(Locale.US)) {
                partFile.delete()
                throw java.io.IOException("checksum mismatch")
            }
        }
        // The staged file lives in a hidden folder; the visible one appears only when it is whole.
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        if (!partFile.renameTo(file)) {
            partFile.copyTo(file, overwrite = true)
            partFile.delete()
        }
        if (modified > 0) file.setLastModified(modified)
        return file
    }

    override fun discard() {
        close()
        partFile.delete()
    }

    override fun close() {
        try {
            channel.close()
        } catch (_: Exception) {
        }
        try {
            raf.close()
        } catch (_: Exception) {
        }
    }
}

class LocalFileSinkFactory(
    private val root: File,
    private val freeSpaceProbe: (() -> Long)? = null
) : FileSinkFactory {
    companion object {
        /** Hidden (dot-prefixed) folder holding every incomplete file. */
        const val STAGING_DIR = ".loopjet-partial"
    }

    private val lock = Any()

    init {
        // A missing root reports usableSpace == 0, which used to look like a full disk.
        if (!root.exists()) root.mkdirs()
    }

    private fun probeFree(): Long = freeSpaceProbe?.invoke() ?: SpaceGuard.usable(root)

    /**
     * Staging root. Half-written files used to sit next to the user's photos as
     * "IMG_1234.jpg.part", which is confusing, gets picked up by galleries and file managers, and
     * looks like corruption. Everything incomplete now lives in this hidden folder -- hidden because
     * of the leading dot -- and appears in the real folder with its real name only when it is whole
     * and verified.
     */
    private val staging: File get() = File(root, STAGING_DIR)

    override fun prepare(relativePath: String, size: Long, modified: Long, mime: String): FileSink {
        val safe = SafePath.sanitize(relativePath) ?: throw java.io.IOException("unsafe file name")
        val target = synchronized(lock) {
            val candidate = File(root, safe)
            val dir = candidate.parentFile ?: root
            dir.mkdirs()
            // A file we are already resuming keeps its name; a genuine name clash gets " (1)".
            if (staged(candidate).exists()) candidate else SafePath.uniqueFile(dir, candidate.name)
        }
        val sink = LocalFileSink(target, staged(target), modified)
        sink.preallocate(size)
        return sink
    }

    private fun staged(target: File): File = File(staging, target.relativeTo(root).path)

    /** Removes the hidden staging folder when nothing is left to resume. */
    fun cleanupStaging() {
        runCatching {
            val empty = staging.walkBottomUp().filter { it != staging && it.isDirectory && it.list()?.isEmpty() == true }
            empty.forEach { it.delete() }
            if (staging.list()?.isEmpty() == true) staging.delete()
        }
    }

    override fun makeDirectory(relativePath: String) {
        val safe = SafePath.sanitize(relativePath) ?: return
        File(root, safe).mkdirs()
    }

    override fun freeBytes(): Long = probeFree()

    override fun rootLabel(): String = root.absolutePath

    /** True when a hidden partial exists for this entry (i.e. a previous session can resume it). */
    fun hasPartial(relativePath: String): Boolean {
        val safe = SafePath.sanitize(relativePath) ?: return false
        return staged(File(root, safe)).isFile
    }

    override fun exists(relativePath: String, size: Long): Boolean {
        val safe = SafePath.sanitize(relativePath) ?: return false
        val f = File(root, safe)
        return f.isFile && f.length() == size
    }
}

interface RandomAccessSource : Closeable {
    val size: Long
    fun readAt(buffer: ByteArray, offset: Int, length: Int, position: Long): Int
}

class FileSource(private val file: File) : RandomAccessSource {
    private val raf = RandomAccessFile(file, "r")
    private val channel = raf.channel
    override val size: Long = raf.length()

    /**
     * Positional read, no lock.
     *
     * The sender runs several workers over the same file. A shared RandomAccessFile needs a seek
     * before every read, so it needs a lock around it -- and that lock meant six streams took turns
     * reading, which is exactly the case a large file hits. FileChannel.read(buf, position) is
     * stateless from the caller's point of view, so the workers run in parallel without a lock.
     */
    override fun readAt(buffer: ByteArray, offset: Int, length: Int, position: Long): Int {
        val bb = java.nio.ByteBuffer.wrap(buffer, offset, length)
        var pos = position
        while (bb.hasRemaining()) {
            val n = channel.read(bb, pos)
            if (n <= 0) break
            pos += n
        }
        return bb.position() - offset
    }

    override fun close() {
        try {
            raf.close()
        } catch (_: Exception) {
        }
    }
}

/** Free-space guard that keeps 16 MiB of headroom so a full disk cannot corrupt the last file. */
object SpaceGuard {
    fun hasRoom(free: Long, needed: Long): Boolean = free > needed + 16L * 1024 * 1024

    /** usableSpace of the nearest existing ancestor: a missing directory reports 0, not "full". */
    fun usable(dir: File?): Long {
        var probe = dir
        while (probe != null && !probe.exists()) probe = probe.parentFile
        return probe?.usableSpace ?: 0L
    }
}
