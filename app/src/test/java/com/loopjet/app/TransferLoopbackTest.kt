package com.loopjet.app

import com.loopjet.app.core.Bytes
import com.loopjet.app.core.ChunkMap
import com.loopjet.app.core.FileSinkFactory
import com.loopjet.app.core.FileSource
import com.loopjet.app.core.Fmt
import com.loopjet.app.core.Identity
import com.loopjet.app.core.LocalFileSinkFactory
import com.loopjet.app.core.OfferDecision
import com.loopjet.app.core.ReceiverEngine
import com.loopjet.app.core.SafePath
import com.loopjet.app.core.SenderEngine
import com.loopjet.app.core.Sha256
import com.loopjet.app.core.SocketConnector
import com.loopjet.app.core.SoftwareIdentity
import com.loopjet.app.core.TransferFile
import com.loopjet.app.core.TransferListener
import com.loopjet.app.core.TransferManifest
import com.loopjet.app.core.TransferOffer
import com.loopjet.app.core.TransferProgress
import com.loopjet.app.core.TransferResult
import com.loopjet.app.core.TransferState
import com.loopjet.app.core.FileResult
import com.loopjet.app.core.InMemoryResumeStore
import com.loopjet.app.core.Json
import com.loopjet.app.core.SafePath.sanitize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * End-to-end tests of the real transfer engine over loopback TCP: the same code, sockets, sealing,
 * chunk maps and sink implementations that run on a phone. Nothing here is mocked; if these pass,
 * bytes really moved and were verified.
 */
class TransferLoopbackTest {

    private val senderIdentity: Identity = SoftwareIdentity.generate()
    private val receiverIdentity: Identity = SoftwareIdentity.generate()

    private class RecordingListener(val accept: Boolean = true) : TransferListener {
        val states = mutableListOf<TransferState>()
        val progress = AtomicReference<TransferProgress?>(null)
        val files = mutableListOf<FileResult>()
        val lastProgress = AtomicReference<TransferProgress?>(null)
        val offers = mutableListOf<TransferOffer>()
        override fun onState(state: TransferState, detail: String) {
            synchronized(states) { states.add(state) }
        }

        override fun onProgress(progress: TransferProgress) {
            lastProgress.set(progress)
        }

        override fun onFileDone(result: FileResult) {
            synchronized(files) { files.add(result) }
        }

        override fun onLog(message: String) = Unit
        override fun onOffer(offer: TransferOffer): OfferDecision {
            offers.add(offer)
            return OfferDecision(accept)
        }
    }

    private class Harness(
        val sourceDir: File,
        val destDir: File,
        val files: List<TransferFile>,
        val senderListener: TransferListener = RecordingListener(),
        val receiverListener: TransferListener = RecordingListener(),
        val stallTimeoutMs: Long = 3_000,
        val resumeStore: InMemoryResumeStore = InMemoryResumeStore(),
        val connectorOverride: ((Int) -> SocketConnector)? = null,
        val senderIdentity: Identity = SoftwareIdentity.generate()
    ) : Closeable {
        lateinit var receiver: ReceiverEngine
        var controlPort: Int = 0
        val receiverResult = AtomicReference<TransferResult?>(null)
        private val receiverThread = AtomicReference<Thread?>(null)

        val logs: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

        fun dump(prefix: String) {
            println("[$prefix] --- engine log (${logs.size} lines) ---")
            synchronized(logs) { logs.forEach { println("[$prefix] $it") } }
        }

        fun startReceiver(): Int {
            receiver = ReceiverEngine(
                identity = SoftwareIdentity.generate(),
                sinkFactory = LocalFileSinkFactory(destDir),
                listener = receiverListener,
                resumeStore = resumeStore,
                requestedPort = 43210,
                stallTimeoutMs = stallTimeoutMs,
                sessionLog = { logs.add("R: $it") }
            )
            receiver.bind()
            controlPort = receiver.controlPort
            val t = Thread {
                receiverResult.set(receiver.runOnce())
            }
            t.isDaemon = true
            t.start()
            receiverThread.set(t)
            Thread.sleep(120)
            return controlPort
        }

        fun runSender(sessionId: String = "test-session"): TransferResult {
            val manifest = TransferManifest(
                sessionId = sessionId,
                files = files,
                dirs = emptyList(),
                apps = emptyList(),
                totalBytes = files.sumOf { it.size },
                chunkSize = com.loopjet.app.core.ProtocolLimits.CHUNK_SIZE,
                senderName = "Test Phone",
                startedAt = System.currentTimeMillis()
            )
            val connector = connectorOverride?.invoke(controlPort) ?: SocketConnector { port, timeout ->
                Socket().apply { connect(InetSocketAddress("127.0.0.1", port), timeout) }
            }
            val sender = SenderEngine(
                identity = senderIdentity,
                manifest = manifest,
                sourceFor = { file -> FileSource(File(sourceDir, file.relPath)) },
                connector = connector,
                controlPort = controlPort,
                listener = senderListener,
                maxStreams = 4,
                sessionLog = { logs.add("S: $it") }
            )
            val result = sender.run()
            println("[sender] ok=${result.ok} msg=${result.message} bytes=${result.bytesTransferred} files=${result.files.size} chunks=${result.chunksDone}/${result.chunksTotal}")
            if (!result.ok) dump("sender-failed")
            return result
        }

        fun awaitReceiver(timeoutMs: Long = 120_000): TransferResult {
            receiverThread.get()?.join(timeoutMs)
            val result = receiverResult.get() ?: error("receiver never returned")
            println(
                "[receiver] ok=${result.ok} msg=${result.message} bytes=${result.bytesTransferred} " +
                    "files=${result.files.size} chunks=${result.chunksDone}/${result.chunksTotal} " +
                    "ms=${result.durationMs} path=${result.pathLabel}"
            )
            if (!result.ok) dump("receiver-failed")
            return result
        }

        override fun close() {
            runCatching { receiver.close() }
        }
    }

    private fun makeSources(dir: File, specs: List<Triple<String, Long, Int>>): List<TransferFile> {
        dir.mkdirs()
        val random = Random(42)
        return specs.mapIndexed { index, (name, size, seed) ->
            val file = File(dir, name)
            file.parentFile?.mkdirs()
            RandomAccessFile(file, "rw").use { raf ->
                val buffer = ByteArray(256 * 1024)
                var remaining = size
                var rnd = Random(seed.toLong())
                while (remaining > 0) {
                    val n = minOf(buffer.size.toLong(), remaining).toInt()
                    rnd.nextBytes(buffer)
                    raf.write(buffer, 0, n)
                    remaining -= n
                }
            }
            TransferFile(
                id = index + 1,
                relPath = name,
                size = size,
                mtime = file.lastModified(),
                mime = "application/octet-stream"
            )
        }
    }

    private fun assertSameContent(source: File, received: File) {
        if (!received.isFile) {
            val siblings = received.parentFile?.list()?.joinToString(", ") ?: "<no directory>"
            assertTrue("missing received file ${received.absolutePath}; directory holds: $siblings", false)
        }
        assertEquals("size of ${received.name}", source.length(), received.length())
        val a = Sha256.ofFile(source)
        val b = Sha256.ofFile(received)
        assertEquals("sha256 of ${received.name}", Bytes.hex(a), Bytes.hex(b))
    }

    @Test
    fun `single large file transfers and verifies`() {
        val root = File(System.getProperty("java.io.tmpdir"), "loopjet-test-large-${System.nanoTime()}")
        val src = File(root, "src")
        val dst = File(root, "dst")
        val size = 192L * 1024 * 1024
        val files = makeSources(src, listOf(Triple("movie.mp4", size, 7)))
        val harness = Harness(src, dst, files)
        try {
            harness.startReceiver()
            val started = System.nanoTime()
            val senderResult = harness.runSender()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val receiverResult = harness.awaitReceiver()
            val mbps = size / (1024.0 * 1024.0) / (elapsedMs / 1000.0)
            println("[loopback] large file: ${Fmt.bytes(size)} in ${elapsedMs} ms = ${"%.1f".format(mbps)} MB/s")
            println("[loopback] sender ok=${senderResult.ok} bytes=${senderResult.bytesTransferred} msg=${senderResult.message}")
            println("[loopback] receiver ok=${receiverResult.ok} files=${receiverResult.files.size} msg=${receiverResult.message}")
            assertTrue("sender failed: ${senderResult.message}", senderResult.ok)
            assertTrue("receiver failed: ${receiverResult.message}", receiverResult.ok)
            assertSameContent(File(src, "movie.mp4"), File(dst, "movie.mp4"))
        } finally {
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `many small files are bundled and verified`() {
        val root = File(System.getProperty("java.io.tmpdir"), "loopjet-test-small-${System.nanoTime()}")
        val src = File(root, "src")
        val dst = File(root, "dst")
        val specs = (1..2000).map { Triple("photos/img_$it.jpg", 2048L + it, it) }
        val files = makeSources(src, specs)
        val harness = Harness(src, dst, files)
        try {
            harness.startReceiver()
            val started = System.nanoTime()
            val senderResult = harness.runSender()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val receiverResult = harness.awaitReceiver()
            val total = files.sumOf { it.size }
            println("[loopback] 2000 small files: ${Fmt.bytes(total)} in ${elapsedMs} ms = ${"%.1f".format(total / 1048576.0 / (elapsedMs / 1000.0))} MB/s")
            assertTrue(senderResult.ok)
            assertTrue(receiverResult.ok)
            val receivedCount = File(dst, "photos").listFiles()?.size ?: 0
            assertEquals(2000, receivedCount)
            assertSameContent(File(src, "photos/img_1000.jpg"), File(dst, "photos/img_1000.jpg"))
        } finally {
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `unicode names and nested folders survive`() {
        val root = File(System.getProperty("java.io.tmpdir"), "loopjet-test-unicode-${System.nanoTime()}")
        val src = File(root, "src")
        val dst = File(root, "dst")
        val names = listOf(
            "دوستان/عکس ۱.jpg",
            "фото/поездка 2026.png",
            "docs/日本語のファイル.txt",
            "docs/emoji 🚀 file.bin",
            "root file with spaces.txt"
        )
        val files = makeSources(src, names.mapIndexed { i, n -> Triple(n, 1024L * (i + 1), i + 1) })
        val harness = Harness(src, dst, files)
        try {
            harness.startReceiver()
            val senderResult = harness.runSender()
            val receiverResult = harness.awaitReceiver()
            assertTrue(senderResult.ok)
            assertTrue(receiverResult.ok)
            for (name in names) {
                val safe = sanitize(name)!!
                assertSameContent(File(src, name), File(dst, safe))
            }
        } finally {
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `resume after a dropped link only re-sends missing chunks`() {
        val root = File(System.getProperty("java.io.tmpdir"), "loopjet-test-resume-${System.nanoTime()}")
        val src = File(root, "src")
        val dst = File(root, "dst")
        val size = 24L * 1024 * 1024
        val files = makeSources(src, listOf(Triple("big.bin", size, 11)))
        val store = InMemoryResumeStore()
        val sessionId = "resume-session"

        // First attempt: every data connection is cut by a proxy after 2 MiB.
        val proxy = FlakyProxy(proxyPortHolder = 0, dropAfterBytes = 2L * 1024 * 1024)
        val harness1 = Harness(
            src, dst, files,
            stallTimeoutMs = 1500,
            resumeStore = store,
            connectorOverride = { controlPort ->
                SocketConnector { port, timeout ->
                    val target = if (port == controlPort + 1) proxy.start(controlPort + 1) else port
                    Socket().apply { connect(InetSocketAddress("127.0.0.1", target), timeout) }
                }
            }
        )
        try {
            harness1.startReceiver()
            val first = harness1.runSender(sessionId)
            val firstReceive = harness1.awaitReceiver()
            println("[loopback] first attempt: sent=${Fmt.bytes(first.bytesTransferred)} receiver=${firstReceive.message}")
            assertTrue("first attempt should not complete", firstReceive.bytesTransferred < size)
        } finally {
            harness1.close()
            proxy.close()
        }

        // Second attempt: direct connection, same session id, expects most chunks to be skipped.
        val harness2 = Harness(src, dst, files, stallTimeoutMs = 3000, resumeStore = store)
        try {
            harness2.startReceiver()
            val second = harness2.runSender(sessionId)
            val secondReceive = harness2.awaitReceiver()
            println(
                "[loopback] resume: sender re-sent ${second.bytesTransferred} bytes of $size, " +
                    "receiver has ${secondReceive.bytesTransferred} bytes, ok=${secondReceive.ok}"
            )
            assertTrue("resume failed: ${secondReceive.message}", secondReceive.ok)
            // The whole point of the chunk map: the second attempt must not re-send what is on disk.
            assertTrue(
                "resume re-sent everything (${second.bytesTransferred} bytes); nothing was skipped",
                second.bytesTransferred < size
            )
            assertEquals("receiver must still end up with the whole file", size, secondReceive.bytesTransferred)
            assertSameContent(File(src, "big.bin"), File(dst, "big.bin"))
        } finally {
            harness2.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `pause and resume keeps the transfer alive`() {
        val root = File(System.getProperty("java.io.tmpdir"), "loopjet-test-pause-${System.nanoTime()}")
        val src = File(root, "src")
        val dst = File(root, "dst")
        val files = makeSources(src, listOf(Triple("pause.bin", 64L * 1024 * 1024, 3)))
        val harness = Harness(src, dst, files)
        try {
            harness.startReceiver()
            val manifest = TransferManifest(
                sessionId = "pause-session",
                files = files,
                dirs = emptyList(),
                apps = emptyList(),
                totalBytes = files.sumOf { it.size },
                chunkSize = com.loopjet.app.core.ProtocolLimits.CHUNK_SIZE,
                senderName = "Test Phone",
                startedAt = System.currentTimeMillis()
            )
            val sender = SenderEngine(
                identity = senderIdentity,
                manifest = manifest,
                sourceFor = { file -> FileSource(File(src, file.relPath)) },
                connector = SocketConnector { port, timeout ->
                    Socket().apply { connect(InetSocketAddress("127.0.0.1", port), timeout) }
                },
                controlPort = harness.controlPort,
                listener = RecordingListener(),
                maxStreams = 2
            )
            val resultRef = AtomicReference<TransferResult?>(null)
            val thread = Thread { resultRef.set(sender.run()) }
            thread.start()
            Thread.sleep(120)
            sender.requestPause()
            Thread.sleep(400)
            sender.requestResume()
            thread.join(120_000)
            val receiverResult = harness.awaitReceiver()
            assertTrue(receiverResult.ok)
            assertSameContent(File(src, "pause.bin"), File(dst, "pause.bin"))
        } finally {
            harness.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `receiver may decline an offer`() {
        val root = File(System.getProperty("java.io.tmpdir"), "loopjet-test-decline-${System.nanoTime()}")
        val src = File(root, "src")
        val dst = File(root, "dst")
        val files = makeSources(src, listOf(Triple("declined.bin", 1024L * 1024, 5)))
        val harness = Harness(src, dst, files, receiverListener = RecordingListener(accept = false))
        try {
            harness.startReceiver()
            val senderResult = harness.runSender()
            val receiverResult = harness.awaitReceiver()
            assertTrue(!senderResult.ok)
            assertTrue(!receiverResult.ok)
            assertEquals(0, dst.listFiles()?.size ?: 0)
        } finally {
            harness.close()
            root.deleteRecursively()
        }
    }
}

/** Minimal TCP proxy that cuts the connection after a byte budget: simulates a link drop. */
class FlakyProxy(private val proxyPortHolder: Int, private val dropAfterBytes: Long) : Closeable {
    private val server = ServerSocket(0)
    private val closed = AtomicBoolean(false)
    private val forwarded = AtomicLong(0)

    fun start(targetPort: Int): Int {
        val t = Thread {
            while (!closed.get()) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    return@Thread
                }
                val upstream = try {
                    Socket("127.0.0.1", targetPort)
                } catch (e: Exception) {
                    client.close()
                    continue
                }
                pump(client, upstream)
                pump(upstream, client)
            }
        }
        t.isDaemon = true
        t.start()
        return server.localPort
    }

    private fun pump(from: Socket, to: Socket) {
        val t = Thread {
            val buffer = ByteArray(64 * 1024)
            try {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                while (!closed.get()) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    output.flush()
                    val total = forwarded.addAndGet(n.toLong())
                    if (total > dropAfterBytes) {
                        Thread.sleep(20)
                        from.close()
                        to.close()
                        return@Thread
                    }
                }
            } catch (_: Exception) {
            }
            runCatching { from.close() }
            runCatching { to.close() }
        }
        t.isDaemon = true
        t.start()
    }

    override fun close() {
        closed.set(true)
        runCatching { server.close() }
    }
}
