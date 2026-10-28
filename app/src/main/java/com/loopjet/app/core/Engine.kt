package com.loopjet.app.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/** Opens a socket bound to the network the caller decided on (never "whatever the default route is"). */
fun interface SocketConnector {
    fun connect(port: Int, timeoutMs: Int): Socket
}

/** Persistence of chunk maps so an interrupted transfer resumes instead of restarting. */
interface ResumeStore {
    fun save(sessionId: String, chunkSize: Int, maps: Map<Int, ChunkMap>, paths: Map<Int, String>)
    fun load(sessionId: String): Map<Int, ChunkMap>
    fun clear(sessionId: String)
}

class InMemoryResumeStore : ResumeStore {
    private val sessions = ConcurrentHashMap<String, Map<Int, ChunkMap>>()

    override fun save(sessionId: String, chunkSize: Int, maps: Map<Int, ChunkMap>, paths: Map<Int, String>) {
        sessions[sessionId] = maps.mapValues { it.value.copy() }
    }

    override fun load(sessionId: String): Map<Int, ChunkMap> = sessions[sessionId] ?: emptyMap()

    override fun clear(sessionId: String) {
        sessions.remove(sessionId)
    }
}

/** Rolling throughput meter: a 1.5 s window of byte counts, plus a total for the average. */
internal class RateMeter(private val windowMs: Long = 1500) {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var totalBytes = 0L

    @Synchronized
    fun add(bytes: Long) {
        if (bytes <= 0) return
        totalBytes += bytes
        val now = System.nanoTime() / 1_000_000
        samples.addLast(now to totalBytes)
        while (samples.size > 2 && now - samples.first.first > windowMs) samples.removeFirst()
    }

    @Synchronized
    fun instant(): Long {
        if (samples.size < 2) return 0
        val now = System.nanoTime() / 1_000_000
        val first = samples.first
        val elapsed = (now - first.first).coerceAtLeast(1)
        val bytes = totalBytes - first.second
        return (bytes * 1000 / elapsed).coerceAtLeast(0)
    }

    @Synchronized
    fun total(): Long = totalBytes
}

internal fun readFully(input: InputStream, dst: ByteArray, off: Int, len: Int) {
    var done = 0
    while (done < len) {
        val n = input.read(dst, off + done, len - done)
        if (n < 0) throw EOFException("stream closed")
        done += n
    }
}

internal fun Socket.shutDownQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}

// =================================================================================================
// Sender
// =================================================================================================

/**
 * Sends one manifest to one receiver.
 *
 * Owns the control channel, opens up to [maxStreams] parallel sealed data connections on
 * `controlPort + 1`, and feeds them from a shared 256 KiB chunk queue. Small files are packed into
 * bundle records, so 5 000 photos cost a few hundred frames instead of 5 000.
 */
class SenderEngine(
    private val identity: Identity,
    val manifest: TransferManifest,
    private val sourceFor: (TransferFile) -> RandomAccessSource,
    private val connector: SocketConnector,
    private val controlPort: Int,
    private val listener: TransferListener,
    private val maxStreams: Int = 4,
    private val expectedPeerFingerprint: String? = null,
    private val sessionLog: (String) -> Unit = {}
) {
    private val cancelled = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val acceptedIds = ConcurrentHashMap.newKeySet<Int>()
    private val completedIds = ConcurrentHashMap.newKeySet<Int>()
    private val failures = ConcurrentLinkedQueue<String>()
    private val rate = RateMeter()
    private val queue = ConcurrentLinkedQueue<WorkItem>()
    private val sources = ConcurrentHashMap<Int, RandomAccessSource>()
    private val chunksDone = AtomicInteger(0)
    private val currentFiles = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var controlSocket: Socket? = null
    @Volatile private var controlChannel: RecordChannel? = null
    @Volatile private var keys: SessionKeys? = null
    private val controlWriteLock = Any()
    private val dataSockets = ConcurrentHashMap<Int, Socket>()
    private val sas = AtomicLong(0)
    private var sessionId: String = ""

    private var totalBytes = 0L
    private var chunksTotal = 0
    private var pathLabel = ""
    private var peerName = ""

    val confirmationCode: String
        get() = sas.get().toString().padStart(6, '0')

    fun requestCancel() {
        cancelled.set(true)
        controlSocket?.shutDownQuietly()
        dataSockets.values.forEach { it.shutDownQuietly() }
    }

    fun requestPause() {
        if (paused.getAndSet(true)) return
        sendControl(Frame.PAUSE, Json.obj("reason" to "user"))
    }

    fun requestResume() {
        if (!paused.getAndSet(false)) return
        sendControl(Frame.RESUME, Json.obj("reason" to "user"))
    }

    fun run(): TransferResult {
        val startedAt = System.currentTimeMillis()
        val startedNanos = System.nanoTime()
        var ok = false
        var message = "Transfer did not start"
        var reporter: Thread? = null
        try {
            listener.onState(TransferState.CONNECTING, "Connecting")
            val control = connector.connect(controlPort, CONNECT_TIMEOUT_MS)
            controlSocket = control
            control.soTimeout = 0
            val io = SocketIo(control)
            val hello = Hello(
                name = manifest.senderName,
                platform = "android",
                sdk = 0,
                deviceId = identity.fingerprintHex,
                capabilities = listOf("resume", "bundles", "aes-gcm", "multi-stream"),
                identitySpki = identity.publicKeySpki,
                ephemeralSpki = ByteArray(0),
                nonce = ByteArray(0)
            )
            val handshake = Handshake.run(io, identity, hello, isClient = true)
            keys = handshake.keys
            sas.set(handshake.localSas.toLongOrNull() ?: 0L)
            peerName = handshake.peer.name
            if (expectedPeerFingerprint != null &&
                !expectedPeerFingerprint.equals(handshake.peerFingerprintHex, ignoreCase = true)
            ) {
                throw ProtocolException("Receiver identity does not match the pinned device")
            }
            sessionLog("handshake complete with ${handshake.peer.name}, code ${keys?.sas}")
            val channel = handshake.channel
            controlChannel = channel
            listener.onState(TransferState.WAITING_ACCEPT, "Waiting for ${handshake.peer.name} to accept")

            channel.writeJson(Frame.MANIFEST, manifest.toJson())
            channel.flush()
            control.soTimeout = 300_000
            val prepare = try {
                awaitPrepare(channel)
            } finally {
                control.soTimeout = 0
            }
            sessionId = manifest.sessionId
            pathLabel = prepare.saveDirectory
            acceptedIds.addAll(prepare.accepted)
            if (acceptedIds.isEmpty()) {
                channel.writeJson(Frame.BYE, Json.obj("reason" to "nothing-to-send"))
                channel.flush()
                listener.onState(TransferState.DONE, "Nothing new to send")
                return TransferResult(
                    ok = true,
                    message = "Everything was already on the receiver",
                    files = emptyList(),
                    bytesTransferred = 0,
                    durationMs = System.currentTimeMillis() - startedAt,
                    chunksTotal = 0,
                    chunksDone = 0,
                    pathLabel = pathLabel
                )
            }

            buildWork(prepare)
            reporter = startReporter(Direction.SEND, startedNanos)
            val controlReader = Thread({ readControlLoop() }, "lj-ctl-read").apply { isDaemon = true; start() }

            listener.onState(TransferState.TRANSFERRING, "Transferring")
            val workerCount = max(1, min(maxStreams, 6))
            val workers = ArrayList<Thread>(workerCount)
            for (i in 0 until workerCount) {
                val t = Thread({ workerLoop(i) }, "lj-send-$i")
                t.isDaemon = true
                t.start()
                workers.add(t)
            }
            for (t in workers) t.join()

            val deadline = System.currentTimeMillis() + COMPLETION_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && !cancelled.get()) {
                if (completedIds.containsAll(acceptedIds)) break
                if (!controlReader.isAlive) break
                Thread.sleep(40)
            }
            try {
                sendControl(Frame.BYE, Json.obj("reason" to if (cancelled.get()) "cancelled" else "finished"))
                controlChannel?.flush()
            } catch (_: Exception) {
            }
            ok = failures.isEmpty() && !cancelled.get()
            message = when {
                cancelled.get() -> "Cancelled"
                failures.isNotEmpty() -> failures.joinToString("; ")
                else -> "Transfer complete"
            }
            if (!ok && cancelled.get()) listener.onState(TransferState.CANCELLED, message)
        } catch (e: Exception) {
            message = e.message ?: e.javaClass.simpleName
            sessionLog("sender stopped: $message")
        } finally {
            finished.set(true)
            reporter?.let { runCatching { it.interrupt() } }
            controlSocket?.shutDownQuietly()
            dataSockets.values.forEach { it.shutDownQuietly() }
            sources.values.forEach { runCatching { it.close() } }
            reporter?.join(500)
        }
        if (ok) listener.onState(TransferState.DONE, message)
        else if (!cancelled.get()) listener.onState(TransferState.FAILED, message)
        val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000
        return TransferResult(
            ok = ok,
            message = message,
            files = emptyList(),
            bytesTransferred = rate.total(),
            durationMs = elapsedMs,
            chunksTotal = chunksTotal,
            chunksDone = chunksDone.get(),
            pathLabel = pathLabel
        )
    }

    private fun awaitPrepare(channel: RecordChannel): PrepareResponse {
        while (true) {
            if (cancelled.get()) throw IOException("Cancelled")
            if (!channel.readHeader()) throw EOFException("Receiver closed the connection")
            when (channel.lastType) {
                Frame.PREPARE -> return PrepareResponse.fromJson(channel.payloadJson())
                Frame.REFUSE -> throw ProtocolException(
                    channel.payloadJson().str("why") ?: "Receiver declined the transfer"
                )
                Frame.ERROR -> throw ProtocolException(
                    channel.payloadJson().str("msg") ?: "Receiver reported an error"
                )
                Frame.BYE -> throw ProtocolException("Receiver ended the session")
                else -> sessionLog("ignoring ${Frame.name(channel.lastType)} before PREPARE")
            }
        }
    }

    // --- work construction -----------------------------------------------------------------------

    private sealed class WorkItem {
        open val bytes: Long = 0
        class Chunk(val file: TransferFile, val index: Int, val offset: Long, val length: Int) : WorkItem() {
            override val bytes: Long get() = length.toLong()
        }

        class Bundle(val parts: List<BundlePart>, override val bytes: Long) : WorkItem()
    }

    private class BundlePart(val file: TransferFile, val length: Int)

    private fun buildWork(prepare: PrepareResponse) {
        val small = ArrayList<TransferFile>()
        val chunkSize = if (prepare.chunkSize > 0) prepare.chunkSize else ProtocolLimits.CHUNK_SIZE
        for (file in manifest.files) {
            if (!acceptedIds.contains(file.id)) continue
            if (file.size <= 0) continue
            val chunkCount = ((file.size + chunkSize - 1) / chunkSize).toInt()
            chunksTotal += chunkCount
            totalBytes += file.size
            val have = prepare.chunks[file.id]
            if (have != null && have.chunkCount == chunkCount && have.isComplete()) continue
            if (file.size <= ProtocolLimits.BUNDLE_FILE_MAX) {
                if (have?.isSet(0) == true) continue
                small.add(file)
                continue
            }
            for (index in 0 until chunkCount) {
                if (have?.isSet(index) == true) {
                    chunksDone.incrementAndGet()
                    continue
                }
                val offset = index.toLong() * chunkSize
                val length = min(chunkSize.toLong(), file.size - offset).toInt()
                queue.add(WorkItem.Chunk(file, index, offset, length))
            }
        }
        var parts = ArrayList<BundlePart>()
        var bundleBytes = 0L
        for (file in small) {
            if (bundleBytes + file.size > ProtocolLimits.BUNDLE_MAX && parts.isNotEmpty()) {
                queue.add(WorkItem.Bundle(ArrayList(parts), bundleBytes))
                parts = ArrayList()
                bundleBytes = 0
            }
            parts.add(BundlePart(file, file.size.toInt()))
            bundleBytes += file.size
        }
        if (parts.isNotEmpty()) queue.add(WorkItem.Bundle(parts, bundleBytes))
        sessionLog("queued ${queue.size} items for $totalBytes bytes ($chunksTotal chunks)")
    }

    // --- workers ---------------------------------------------------------------------------------

    private fun workerLoop(index: Int) {
        var socket: Socket? = null
        var channel: RecordChannel? = null
        val readBuffer = ByteArray(ProtocolLimits.CHUNK_SIZE)
        val staging = ByteArray(ProtocolLimits.MAX_RECORD)
        val digest = ByteArray(32)
        val entryDigests = ByteArray(32)
        try {
            while (!cancelled.get() && !finished.get()) {
                if (paused.get()) {
                    Thread.sleep(25)
                    continue
                }
                val item = queue.poll() ?: break
                var attempts = 0
                var sent = false
                while (attempts < 3 && !sent) {
                    try {
                        if (channel == null) {
                            val pair = openDataStream(index)
                            socket = pair.first
                            channel = pair.second
                            dataSockets[index] = pair.first
                        }
                        when (item) {
                            is WorkItem.Chunk -> sendChunk(channel, item, readBuffer, staging, digest)
                            is WorkItem.Bundle -> sendBundle(channel, item, readBuffer, staging, entryDigests)
                        }
                        sent = true
                    } catch (e: Exception) {
                        attempts++
                        sessionLog("stream $index: ${e.message} (attempt $attempts)")
                        socket?.shutDownQuietly()
                        socket = null
                        channel = null
                        if (cancelled.get() || finished.get()) return
                        if (attempts >= 3) failures.add("send failed: ${e.message}")
                        else Thread.sleep(60L * attempts)
                    }
                }
            }
            runCatching { channel?.flush() }
        } finally {
            socket?.shutDownQuietly()
        }
    }

    private fun openDataStream(index: Int): Pair<Socket, RecordChannel> {
        val master = keys ?: throw IOException("no session keys")
        val socket = connector.connect(controlPort + 1, CONNECT_TIMEOUT_MS)
        val io = SocketIo(socket)
        val clientNonce = Sha256.randomBytes(8)
        io.output.write(clientNonce)
        io.output.flush()
        val serverNonce = ByteArray(8)
        readFully(io.input, serverNonce, 0, 8)
        val salt = Bytes.concat(clientNonce, serverNonce)
        val outKey = Hkdf.derive(master.clientToServer, salt, "LJP1-data".toByteArray(), 32)
        val inKey = Hkdf.derive(master.serverToClient, salt, "LJP1-data".toByteArray(), 32)
        val channel = RecordChannel(
            io,
            AeadKey(outKey),
            AeadKey(inKey),
            outNoncePrefix = byteArrayOf(0, 0, 0, 1),
            inNoncePrefix = byteArrayOf(0, 0, 0, 2)
        )
        sessionLog("data stream $index open")
        return socket to channel
    }

    private fun sendChunk(
        channel: RecordChannel,
        item: WorkItem.Chunk,
        readBuffer: ByteArray,
        staging: ByteArray,
        digest: ByteArray
    ) {
        val file = item.file
        val source = sources.getOrPut(file.id) { sourceFor(file) }
        var offset = item.offset
        var remaining = item.length
        currentFiles.add(file.relPath)
        while (remaining > 0) {
            if (cancelled.get()) throw IOException("Cancelled")
            while (paused.get() && !cancelled.get()) Thread.sleep(15)
            val chunk = min(remaining, ProtocolLimits.CHUNK_SIZE)
            val n = source.readAt(readBuffer, 0, chunk, offset)
            if (n != chunk) throw IOException("short read on ${file.relPath}")
            Sha256.digestTo(readBuffer, 0, n, digest, 0)
            putInt(staging, 0, file.id)
            putLong(staging, 4, offset)
            putInt(staging, 12, n)
            staging[16] = 0
            System.arraycopy(digest, 0, staging, 17, 32)
            System.arraycopy(readBuffer, 0, staging, 49, n)
            channel.writeRecord(Frame.DATA, staging, 0, 49 + n)
            rate.add(n.toLong())
            chunksDone.incrementAndGet()
            offset += n
            remaining -= n
        }
        channel.flush()
        if (currentFiles.size > 6) currentFiles.clear()
    }

    private fun sendBundle(
        channel: RecordChannel,
        item: WorkItem.Bundle,
        readBuffer: ByteArray,
        staging: ByteArray,
        digest: ByteArray
    ) {
        val headerSize = 2 + item.parts.size * 44
        val payloadSize = item.bytes.toInt()
        if (headerSize + payloadSize > staging.size) throw IOException("bundle too large")
        val payload = ByteArray(payloadSize)
        var payloadOffset = 0
        var headerOffset = 2
        staging[0] = (item.parts.size ushr 8).toByte()
        staging[1] = item.parts.size.toByte()
        for (part in item.parts) {
            val source = sources.getOrPut(part.file.id) { sourceFor(part.file) }
            val n = source.readAt(payload, payloadOffset, part.length, 0)
            if (n != part.length) throw IOException("short read on ${part.file.relPath}")
            Sha256.digestTo(payload, payloadOffset, n, digest, 0)
            putInt(staging, headerOffset, part.file.id)
            putInt(staging, headerOffset + 4, 0)
            putInt(staging, headerOffset + 8, n)
            System.arraycopy(digest, 0, staging, headerOffset + 12, 32)
            headerOffset += 44
            payloadOffset += n
            currentFiles.add(part.file.relPath)
        }
        System.arraycopy(payload, 0, staging, headerSize, payloadSize)
        channel.writeRecord(Frame.BUNDLE, staging, 0, headerSize + payloadSize)
        channel.flush()
        rate.add(item.bytes)
        chunksDone.addAndGet(item.parts.size)
        currentFiles.clear()
    }

    // --- control ---------------------------------------------------------------------------------

    private fun readControlLoop() {
        val channel = controlChannel ?: return
        try {
            while (!finished.get()) {
                if (!channel.readHeader()) break
                when (channel.lastType) {
                    Frame.ACK -> Unit // progress accounting is byte-based on this side
                    Frame.COMPLETE -> {
                        val m = channel.payloadJson()
                        val fileId = m.int("id") ?: -1
                        if (fileId >= 0) completedIds.add(fileId)
                    }
                    Frame.ERROR -> failures.add(channel.payloadJson().str("msg") ?: "Receiver error")
                    Frame.BYE -> break
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            if (!finished.get()) sessionLog("control read ended: ${e.message}")
        }
    }

    private fun sendControl(type: Int, json: Map<String, Any?>) {
        val channel = controlChannel ?: return
        try {
            synchronized(controlWriteLock) {
                channel.writeJson(type, json)
                channel.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun startReporter(direction: Direction, startedNanos: Long): Thread {
        val thread = Thread({
            while (!finished.get()) {
                val elapsedMs = ((System.nanoTime() - startedNanos) / 1_000_000).coerceAtLeast(1)
                val transferred = rate.total()
                val average = transferred * 1000.0 / elapsedMs
                val remaining = (totalBytes - transferred).coerceAtLeast(0)
                val eta = if (average > 1) (remaining / average).toLong() else 0
                listener.onProgress(
                    TransferProgress(
                        direction = direction,
                        sessionId = sessionId,
                        peerName = peerName,
                        totalBytes = totalBytes,
                        transferredBytes = transferred,
                        instantBytesPerSecond = rate.instant(),
                        averageBytesPerSecond = average.toLong(),
                        filesDone = completedIds.size,
                        filesTotal = acceptedIds.size,
                        currentFiles = currentFiles.toList(),
                        elapsedMs = elapsedMs,
                        etaMs = eta,
                        state = when {
                            cancelled.get() -> TransferState.CANCELLED
                            paused.get() -> TransferState.PAUSED
                            else -> TransferState.TRANSFERRING
                        },
                        pathLabel = pathLabel,
                        chunkCount = chunksTotal,
                        chunksDone = chunksDone.get()
                    )
                )
                Thread.sleep(100)
            }
        }, "lj-progress")
        thread.isDaemon = true
        thread.start()
        return thread
    }

    private fun putLong(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = (v ushr (56 - 8 * i)).toByte()
    }

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 6000
        const val COMPLETION_TIMEOUT_MS = 90_000L
    }
}

// =================================================================================================
// Receiver
// =================================================================================================

class ReceiverEngine(
    private val identity: Identity,
    private val sinkFactory: FileSinkFactory,
    private val listener: TransferListener,
    private val resumeStore: ResumeStore = InMemoryResumeStore(),
    private val requestedPort: Int = ProtocolLimits.DEFAULT_PORT,
    private val bindAddress: InetAddress? = null,
    private val maxStreams: Int = 8,
    private val expectedPeerFingerprint: String? = null,
    private val sessionLog: (String) -> Unit = {},
    private val onCommitted: (TransferFile, java.io.File) -> Unit = { _, _ -> },
    private val stallTimeoutMs: Long = 45_000L,
    private val sessionTimeoutMs: Long = 6L * 60 * 60 * 1000
) {
    private var controlServer: ServerSocket? = null
    private var dataServer: ServerSocket? = null
    private val closed = AtomicBoolean(false)

    var controlPort: Int = 0
        private set
    var dataPort: Int = 0
        private set

    val localAddress: String
        get() = bindAddress?.hostAddress ?: "0.0.0.0"

    /** Binds control and data ports (control, control+1). Throws when the range is unusable. */
    fun bind() {
        var port = requestedPort
        var lastError: Exception? = null
        while (port < requestedPort + 64) {
            try {
                val c = ServerSocket()
                c.reuseAddress = true
                c.bind(java.net.InetSocketAddress(bindAddress, port))
                val d = ServerSocket()
                d.reuseAddress = true
                d.bind(java.net.InetSocketAddress(bindAddress, port + 1))
                controlServer = c
                dataServer = d
                controlPort = port
                dataPort = port + 1
                return
            } catch (e: Exception) {
                lastError = e
                controlServer?.shutDownQuietly()
                controlServer = null
                port += 2
            }
        }
        throw IOException("no free port pair: ${lastError?.message}")
    }

    fun close() {
        closed.set(true)
        controlServer?.shutDownQuietly()
        dataServer?.shutDownQuietly()
    }

    private class Session(
        val manifest: TransferManifest,
        val peerName: String,
        val peerFingerprint: String,
        val sas: String,
        val channel: RecordChannel,
        val controlSocket: Socket
    )

    /**
     * Accepts exactly one session. Returns when the session ends (complete, refused, cancelled or
     * dropped). Call [bind] first.
     */
    fun runOnce(): TransferResult {
        val startedAt = System.currentTimeMillis()
        val startedNanos = System.nanoTime()
        val server = controlServer ?: throw IllegalStateException("bind() not called")
        var session: Session? = null
        var ok = false
        var message = "No connection"
        val results = ArrayList<FileResult>()
        var totalBytes = 0L
        var transferred = 0L
        var pathLabel = ""
        var chunksTotal = 0
        var chunksDone = 0
        val rate = RateMeter()
        val progressState = AtomicReference(TransferState.WAITING_ACCEPT)
        val doneLatch = CountDownLatch(1)

        try {
            server.soTimeout = ACCEPT_TIMEOUT_MS
            val control = server.accept()
            control.soTimeout = 0
            val io = SocketIo(control)
            val handshake = Handshake.run(
                io, identity,
                Hello(
                    name = "Receiver", platform = "android", sdk = 0,
                    deviceId = identity.fingerprintHex, capabilities = listOf("resume"),
                    identitySpki = identity.publicKeySpki, ephemeralSpki = ByteArray(0), nonce = ByteArray(0)
                ),
                isClient = false
            )
            if (expectedPeerFingerprint != null &&
                !expectedPeerFingerprint.equals(handshake.peerFingerprintHex, ignoreCase = true)
            ) {
                handshake.channel.writeJson(Frame.REFUSE, Json.obj("why" to "Unrecognised sender"))
                handshake.channel.flush()
                throw ProtocolException("Sender identity does not match the pinned device")
            }
            activeKeys = handshake.keys
            sessionLog("sender ${handshake.peer.name} connected, code ${handshake.keys.sas}")

            val channel = handshake.channel
            var manifest: TransferManifest? = null
            while (manifest == null) {
                if (!channel.readHeader()) throw EOFException("Sender closed the connection")
                when (channel.lastType) {
                    Frame.MANIFEST -> manifest = TransferManifest.fromJson(channel.payloadJson())
                    Frame.BYE -> throw ProtocolException("Sender cancelled")
                    Frame.ERROR -> throw ProtocolException(channel.payloadJson().str("msg") ?: "Sender error")
                    else -> Unit
                }
            }
            val m = manifest
            val current = Session(m, handshake.peer.name, handshake.peerFingerprintHex, handshake.keys.sas, channel, control)
            session = current
            totalBytes = m.totalBytes
            chunksTotal = m.files.sumOf { file ->
                if (file.size <= 0) 0 else ((file.size + m.chunkSize - 1) / m.chunkSize).toInt()
            }

            // Space and skip plan.
            val free = sinkFactory.freeBytes()
            val saved = resumeStore.load(m.sessionId)
            val accepted = ArrayList<Int>()
            val skipped = LinkedHashMap<Int, String>()
            var needed = 0L
            for (file in m.files) {
                if (file.size <= 0) {
                    accepted.add(file.id)
                    continue
                }
                val safe = SafePath.sanitize(file.relPath)
                if (safe == null) {
                    skipped[file.id] = "unsafe-name"
                    continue
                }
                if (sinkFactory.exists(safe, file.size)) {
                    skipped[file.id] = "have"
                    continue
                }
                needed += file.size
                accepted.add(file.id)
            }
            if (!SpaceGuard.hasRoom(free, needed)) {
                channel.writeJson(
                    Frame.REFUSE,
                    Json.obj("why" to "Not enough free space: need ${Fmt.bytes(needed)}, have ${Fmt.bytes(free)}")
                )
                channel.flush()
                throw ProtocolException("Not enough free space on the receiving device")
            }

            val decision = listener.onOffer(
                TransferOffer(
                    peerName = handshake.peer.name,
                    peerFingerprint = handshake.peerFingerprintHex,
                    sas = handshake.keys.sas,
                    manifest = m,
                    freeBytes = free,
                    knownDevice = false
                )
            )
            if (!decision.accepted) {
                channel.writeJson(Frame.REFUSE, Json.obj("why" to (decision.reason.ifBlank { "Declined" })))
                channel.flush()
                listener.onState(TransferState.CANCELLED, "Offer declined")
                return TransferResult(
                    false, "Declined", emptyList(), 0,
                    System.currentTimeMillis() - startedAt, 0, 0, ""
                )
            }

            pathLabel = sinkFactory.rootLabel()
            for (dir in m.dirs) sinkFactory.makeDirectory(dir)
            val sinks = ConcurrentHashMap<Int, FileSink>()
            val maps = ConcurrentHashMap<Int, ChunkMap>()
            val remaining = AtomicInteger(0)
            val haveMap = HashMap<Int, ChunkMap>()
            for (file in m.files) {
                if (!accepted.contains(file.id)) continue
                val sink = sinkFactory.prepare(file.relPath, file.size, file.mtime, file.mime)
                sinks[file.id] = sink
                val map = ChunkMap.forSize(file.size, m.chunkSize)
                val resumed = saved[file.id]
                if (resumed != null && resumed.chunkCount == map.chunkCount) {
                    for (i in 0 until map.chunkCount) if (resumed.isSet(i)) map.set(i)
                }
                maps[file.id] = map
                haveMap[file.id] = map.copy()
                remaining.addAndGet(map.chunkCount - map.count())
            }

            channel.writeJson(
                Frame.PREPARE,
                PrepareResponse(
                    accepted = accepted,
                    skipped = skipped,
                    freeBytes = free,
                    chunkSize = m.chunkSize,
                    chunks = haveMap,
                    saveDirectory = pathLabel
                ).toJson()
            )
            channel.flush()
            progressState.set(TransferState.TRANSFERRING)
            listener.onState(TransferState.TRANSFERRING, "Receiving from ${handshake.peer.name}")

            val pendingFiles = ConcurrentHashMap<Int, TransferFile>()
            for (file in m.files) if (accepted.contains(file.id)) pendingFiles[file.id] = file
            val emptyFiles = pendingFiles.values.filter { it.size <= 0 }

            // Empty files exist immediately.
            for (file in emptyFiles) {
                val sink = sinks[file.id] ?: continue
                try {
                    val committed = sink.commit(file.sha256)
                    onCommitted(file, committed)
                    results.add(FileResult(file.id, file.relPath, 0, true, "created"))
                } catch (e: Exception) {
                    results.add(FileResult(file.id, file.relPath, 0, false, e.message ?: "failed"))
                }
                sinks.remove(file.id)
                pendingFiles.remove(file.id)
                channel.writeJson(
                    Frame.COMPLETE,
                    Json.obj("id" to file.id, "ok" to true, "p" to file.relPath, "s" to 0)
                )
            }
            channel.flush()

            val lastActivity = AtomicLong(System.currentTimeMillis())
            val committedFiles = ConcurrentHashMap.newKeySet<Int>()
            val commitLock = Any()
            val progressThread = Thread({
                while (!closed.get() && doneLatch.count > 0) {
                    val transferredNow = rate.total()
                    val elapsedMs = ((System.nanoTime() - startedNanos) / 1_000_000).coerceAtLeast(1)
                    val average = transferredNow * 1000.0 / elapsedMs
                    if (progressState.get() == TransferState.TRANSFERRING) {
                        synchronized(commitLock) {
                            runCatching {
                                channel.writeJson(
                                    Frame.ACK,
                                    Json.obj(
                                        "d" to transferredNow,
                                        "q" to 0,
                                        "r" to remaining.get(),
                                        "t" to (System.currentTimeMillis() - lastActivity.get())
                                    )
                                )
                                channel.flush()
                            }
                        }
                    }
                    listener.onProgress(
                        TransferProgress(
                            direction = Direction.RECEIVE,
                            sessionId = m.sessionId,
                            peerName = handshake.peer.name,
                            totalBytes = totalBytes,
                            transferredBytes = transferredNow,
                            instantBytesPerSecond = rate.instant(),
                            averageBytesPerSecond = average.toLong(),
                            filesDone = committedFiles.size,
                            filesTotal = pendingFiles.size + results.size,
                            currentFiles = emptyList(),
                            elapsedMs = elapsedMs,
                            etaMs = if (average > 1) ((totalBytes - transferredNow).coerceAtLeast(0) / average).toLong() else 0,
                            state = progressState.get(),
                            pathLabel = pathLabel,
                            chunkCount = chunksTotal,
                            chunksDone = chunksTotal - remaining.get()
                        )
                    )
                    Thread.sleep(100)
                }
            }, "lj-recv-progress").apply { isDaemon = true; start() }

            // Data acceptor.
            val pool = Executors.newFixedThreadPool(maxStreams)
            dataServer!!.soTimeout = 600
            val finishedAccepting = AtomicBoolean(false)
            val maybeFinish = Runnable {
                if (remaining.get() <= 0 && !finishedAccepting.getAndSet(true)) {
                    doneLatch.countDown()
                }
            }
            val acceptThread = Thread({
                while (!closed.get() && !finishedAccepting.get()) {
                    try {
                        val socket = dataServer!!.accept()
                        pool.execute { handleDataStream(socket, m, sinks, maps, remaining, rate, lastActivity, channel, commitLock, committedFiles, results, onCommitted, pendingFiles, maybeFinish) }
                    } catch (_: SocketTimeoutException) {
                        if (remaining.get() <= 0) break
                    } catch (_: Exception) {
                        break
                    }
                }
                maybeFinish.run()
            }, "lj-recv-accept").apply { isDaemon = true; start() }

            maybeFinish.run()

            // Control reader: sender may pause, resume, cancel or vanish.
            val controlReader = Thread({
                try {
                    while (!closed.get()) {
                        if (!channel.readHeader()) break
                        when (channel.lastType) {
                            Frame.PAUSE -> {
                                progressState.set(TransferState.PAUSED)
                                listener.onState(TransferState.PAUSED, "Paused by sender")
                            }
                            Frame.RESUME -> {
                                progressState.set(TransferState.TRANSFERRING)
                                listener.onState(TransferState.TRANSFERRING, "Resumed by sender")
                            }
                            Frame.BYE -> {
                                finishedAccepting.set(true)
                                doneLatch.countDown()
                                break
                            }
                            Frame.ERROR -> sessionLog("sender error: ${channel.payloadJson().str("msg")}")
                            else -> Unit
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    if (remaining.get() > 0) doneLatch.countDown()
                }
            }, "lj-recv-control").apply { isDaemon = true; start() }

            // Watchdog: a silent link for STALL_TIMEOUT_MS ends the session so the user is told.
            val watchdog = Thread({
                while (doneLatch.count > 0 && !closed.get()) {
                    Thread.sleep(1000)
                    val idle = System.currentTimeMillis() - lastActivity.get()
                    if (idle > stallTimeoutMs && remaining.get() > 0) {
                        sessionLog("stalled for ${idle}ms, ending session")
                        doneLatch.countDown()
                        break
                    }
                }
            }, "lj-recv-watchdog").apply { isDaemon = true; start() }

            doneLatch.await(sessionTimeoutMs, TimeUnit.MILLISECONDS)
            finishedAccepting.set(true)
            pool.shutdownNow()
            dataServer?.shutDownQuietly()
            progressState.set(if (remaining.get() <= 0) TransferState.DONE else TransferState.PAUSED)

            // Finish whatever is complete, keep partial files for resume.
            val pending = ArrayList<TransferFile>()
            for (file in pendingFiles.values) {
                val sink = sinks[file.id] ?: continue
                val map = maps[file.id] ?: continue
                if (map.isComplete()) {
                    try {
                        val committed = sink.commit(file.sha256)
                        onCommitted(file, committed)
                        results.add(FileResult(file.id, file.relPath, file.size, true, "received"))
                        synchronized(commitLock) {
                            channel.writeJson(
                                Frame.COMPLETE,
                                Json.obj("id" to file.id, "ok" to true, "p" to file.relPath, "s" to file.size)
                            )
                        }
                    } catch (e: Exception) {
                        results.add(FileResult(file.id, file.relPath, file.size, false, e.message ?: "verify failed"))
                        synchronized(commitLock) {
                            channel.writeJson(
                                Frame.COMPLETE,
                                Json.obj(
                                    "id" to file.id, "ok" to false, "p" to file.relPath, "s" to file.size,
                                    "msg" to (e.message ?: "verify failed")
                                )
                            )
                        }
                    }
                    sinks.remove(file.id)
                    resumeStore.save(m.sessionId, m.chunkSize, maps.filterValues { !it.isComplete() }, emptyMap())
                } else {
                    pending.add(file)
                    sink.close()
                }
            }
            synchronized(commitLock) {
                runCatching {
                    channel.writeJson(
                        Frame.BYE,
                        Json.obj(
                            "reason" to if (remaining.get() <= 0) "complete" else "partial",
                            "pending" to pending.map { it.id }
                        )
                    )
                    channel.flush()
                }
            }
            if (remaining.get() <= 0) {
                resumeStore.clear(m.sessionId)
            }
            progressThread.interrupt()
            controlReader.interrupt()
            watchdog.interrupt()
            transferred = rate.total()
            chunksDone = chunksTotal - remaining.get()
            ok = remaining.get() <= 0
            message = when {
                ok -> "Transfer complete"
                pending.isNotEmpty() -> "Interrupted — ${pending.size} file(s) can resume"
                else -> "Transfer incomplete"
            }
        } catch (e: SocketTimeoutException) {
            message = "No sender connected"
        } catch (e: Exception) {
            message = e.message ?: e.javaClass.simpleName
        } finally {
            try {
                session?.channel?.flush()
            } catch (_: Exception) {
            }
            session?.controlSocket?.shutDownQuietly()
            close()
        }
        return TransferResult(
            ok = ok,
            message = message,
            files = results,
            bytesTransferred = transferred,
            durationMs = (System.nanoTime() - startedNanos) / 1_000_000,
            chunksTotal = chunksTotal,
            chunksDone = chunksDone,
            pathLabel = pathLabel
        )
    }

    private fun handleDataStream(
        socket: Socket,
        manifest: TransferManifest,
        sinks: ConcurrentHashMap<Int, FileSink>,
        maps: ConcurrentHashMap<Int, ChunkMap>,
        remaining: AtomicInteger,
        rate: RateMeter,
        lastActivity: AtomicLong,
        control: RecordChannel,
        commitLock: Any,
        committedFiles: MutableSet<Int>,
        results: MutableList<FileResult>,
        onCommitted: (TransferFile, java.io.File) -> Unit,
        pendingFiles: ConcurrentHashMap<Int, TransferFile>,
        maybeFinish: Runnable
    ) {
        var channel: RecordChannel? = null
        try {
            socket.soTimeout = DATA_READ_TIMEOUT_MS
            val input = socket.getInputStream().buffered(64 * 1024)
            val output = socket.getOutputStream().buffered(64 * 1024)
            val clientNonce = ByteArray(8)
            readFully(input, clientNonce, 0, 8)
            val serverNonce = Sha256.randomBytes(8)
            output.write(serverNonce)
            output.flush()
            val master = sessionKeysFor(manifest) ?: throw IOException("no session")
            val salt = Bytes.concat(clientNonce, serverNonce)
            val c2s = Hkdf.derive(master.clientToServer, salt, "LJP1-data".toByteArray(), 32)
            val s2c = Hkdf.derive(master.serverToClient, salt, "LJP1-data".toByteArray(), 32)
            // Reuse the socket's streams through a lightweight adapter so RecordChannel keeps buffering.
            val adapter = AdapterIo(socket, input, output)
            channel = RecordChannel(adapter, AeadKey(s2c), AeadKey(c2s),
                byteArrayOf(0, 0, 0, 2), byteArrayOf(0, 0, 0, 1))
            val payload = channel.plaintextBuffer
            while (!closed.get()) {
                if (!channel.readHeader()) break
                when (channel.lastType) {
                    Frame.DATA -> {
                        if (channel.lastPlainLength < 49) throw ProtocolException("short DATA record")
                        val fileId = Bytes.readU32(payload, 0)
                        val offset = Bytes.readU64(payload, 4)
                        val length = Bytes.readU32(payload, 12)
                        val file = pendingFiles[fileId] ?: continue
                        if (channel.lastPlainLength < 49 + length) throw ProtocolException("truncated DATA record")
                        val actual = Sha256.digest(payload, 49, length)
                        var mismatched = false
                        for (i in 0 until 32) if (actual[i] != payload[17 + i]) { mismatched = true; break }
                        if (mismatched) throw ProtocolException("chunk checksum mismatch on ${file.relPath}")
                        val sink = sinks[fileId] ?: continue
                        synchronized(sink) { sink.writeAt(payload, 49, length, offset) }
                        rate.add(length.toLong())
                        lastActivity.set(System.currentTimeMillis())
                        val map = maps[fileId]
                        if (map != null) {
                            val chunkSize = manifest.chunkSize
                            val firstChunk = (offset / chunkSize).toInt()
                            val lastChunk = ((offset + length - 1) / chunkSize).toInt()
                            for (i in firstChunk..lastChunk) {
                                if (!map.isSet(i)) {
                                    map.set(i)
                                    remaining.decrementAndGet()
                                }
                            }
                            if (map.isComplete()) {
                                finishFile(file, sinks, map, committedFiles, results, control, commitLock, onCommitted)
                            }
                        }
                    }
                    Frame.BUNDLE -> {
                        val count = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                        var headerOffset = 2
                        var payloadOffset = 2 + count * 44
                        for (i in 0 until count) {
                            val fileId = Bytes.readU32(payload, headerOffset)
                            val length = Bytes.readU32(payload, headerOffset + 8)
                            val file = pendingFiles[fileId]
                            val sink = sinks[fileId]
                            if (file != null && sink != null) {
                                val digest = Sha256.digest(payload, payloadOffset, length)
                                var bad = false
                                for (b in 0 until 32) if (digest[b] != payload[headerOffset + 12 + b]) { bad = true; break }
                                if (bad) throw ProtocolException("bundle checksum mismatch on ${file.relPath}")
                                synchronized(sink) { sink.writeAt(payload, payloadOffset, length, 0) }
                                rate.add(length.toLong())
                                val map = maps[fileId]
                                if (map != null && map.chunkCount > 0 && !map.isSet(0)) {
                                    map.set(0)
                                    remaining.decrementAndGet()
                                }
                                if (map != null && map.isComplete()) {
                                    finishFile(file, sinks, map, committedFiles, results, control, commitLock, onCommitted)
                                }
                            }
                            headerOffset += 44
                            payloadOffset += length
                        }
                        lastActivity.set(System.currentTimeMillis())
                    }
                    else -> sessionLog("data stream: ignoring ${Frame.name(channel.lastType)}")
                }
                maybeFinish.run()
            }
        } catch (e: Exception) {
            sessionLog("data stream ended: ${e.message}")
        } finally {
            try {
                channel?.close()
            } catch (_: Exception) {
            }
            socket.shutDownQuietly()
        }
    }

    @Volatile private var activeKeys: SessionKeys? = null

    private fun sessionKeysFor(manifest: TransferManifest): SessionKeys? = activeKeys

    private fun finishFile(
        file: TransferFile,
        sinks: ConcurrentHashMap<Int, FileSink>,
        map: ChunkMap,
        committedFiles: MutableSet<Int>,
        results: MutableList<FileResult>,
        control: RecordChannel,
        commitLock: Any,
        onCommitted: (TransferFile, java.io.File) -> Unit
    ) {
        if (!committedFiles.add(file.id)) return
        val sink = sinks.remove(file.id) ?: return
        var ok = true
        var message = "received"
        try {
            val committed = synchronized(sink) { sink.commit(file.sha256) }
            onCommitted(file, committed)
        } catch (e: Exception) {
            ok = false
            message = e.message ?: "verification failed"
        }
        synchronized(results) {
            results.add(FileResult(file.id, file.relPath, file.size, ok, message))
        }
        synchronized(commitLock) {
            runCatching {
                control.writeJson(
                    Frame.COMPLETE,
                    Json.obj("id" to file.id, "ok" to ok, "p" to file.relPath, "s" to file.size, "msg" to message)
                )
                control.flush()
            }
        }
        listener.onFileDone(FileResult(file.id, file.relPath, file.size, ok, message))
    }

    /** Adapter so the accepted-socket streams are reused instead of wrapped twice. */
    private class AdapterIo(
        private val socket: Socket,
        override val input: InputStream,
        override val output: java.io.OutputStream
    ) : StreamIo {
        init {
            socket.tcpNoDelay = true
            runCatching {
                socket.sendBufferSize = 4 * 1024 * 1024
                socket.receiveBufferSize = 4 * 1024 * 1024
            }
        }

        override fun close() {
            try {
                output.flush()
            } catch (_: Exception) {
            }
            socket.shutDownQuietly()
        }
    }

    companion object {
        const val ACCEPT_TIMEOUT_MS = 180_000
        const val SESSION_TIMEOUT_MS = 6L * 60 * 60 * 1000
        const val STALL_TIMEOUT_MS = 45_000L
        const val DATA_READ_TIMEOUT_MS = 60_000
    }
}
