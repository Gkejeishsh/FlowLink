package com.loopjet.app.platform

import android.content.Context
import com.loopjet.app.core.FileSource
import com.loopjet.app.core.Fmt
import com.loopjet.app.core.LocalFileSinkFactory
import com.loopjet.app.core.LoggingListener
import com.loopjet.app.core.ProtocolLimits
import com.loopjet.app.core.ReceiverEngine
import com.loopjet.app.core.SenderEngine
import com.loopjet.app.core.SocketConnector
import com.loopjet.app.core.SoftwareIdentity
import com.loopjet.app.core.TransferFile
import com.loopjet.app.core.TransferManifest
import com.loopjet.app.platform.net.NetworkTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * The benchmark that makes the speed claims checkable on the user's own phone.
 *
 * It reports three different numbers, each measured here and now:
 *  1. storage write and read MB/s (the ceiling if the disk is the bottleneck),
 *  2. the Wi-Fi link rate and band reported by the system,
 *  3. achieved throughput of the *real* transfer engine — seal, sockets, chunk map, write, verify —
 *     over loopback, which is the honest way to show what the pipeline costs on this CPU.
 */
class Benchmark(private val context: Context) {

    data class Result(
        val writeMbPerSec: Double,
        val readMbPerSec: Double,
        val engineMbPerSec: Double,
        val encryptMbPerSec: Double,
        val linkMbps: Int,
        val band: String,
        val linkUtilisation: Double,
        val bytesTested: Long,
        val notes: List<String>
    )

    suspend fun run(onStage: (String) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val notes = ArrayList<String>()
        val dir = File(context.cacheDir, "bench").apply { mkdirs() }
        val file = File(dir, "bench.bin")
        val size = 128L * 1024 * 1024
        onStage("Writing 128 MB")
        val writeRate = measureWrite(file, size)
        onStage("Reading back")
        val readRate = measureRead(file)
        onStage("Sealing 128 MB")
        val encryptRate = measureEncrypt(size)
        onStage("Transferring through the engine")
        val engineRate = measureEngine(file, dir)
        file.delete()
        val tools = NetworkTools(context)
        val link = tools.linkSpeedMbps()
        val band = tools.bandLabel()
        val utilisation = if (link > 0) (engineRate * 8) / link else 0.0
        notes.add("Engine throughput is measured over loopback, so it includes AES-256-GCM sealing, chunk maps and disk writes.")
        notes.add("Link rate is what the Wi-Fi chip reports; a router or hotspot relay uses airtime twice.")
        if (link == 0) notes.add("Wi-Fi link rate unavailable — connect to Wi-Fi for a meaningful comparison.")
        Result(
            writeMbPerSec = writeRate,
            readMbPerSec = readRate,
            engineMbPerSec = engineRate,
            encryptMbPerSec = encryptRate,
            linkMbps = link,
            band = band,
            linkUtilisation = utilisation,
            bytesTested = size,
            notes = notes
        )
    }

    private fun measureWrite(file: File, size: Long): Double {
        if (file.exists()) file.delete()
        val buffer = ByteArray(4 * 1024 * 1024)
        java.util.Random(7).nextBytes(buffer)
        val started = System.nanoTime()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(size)
            val channel = raf.channel
            var written = 0L
            while (written < size) {
                val chunk = minOf(buffer.size.toLong(), size - written).toInt()
                channel.write(java.nio.ByteBuffer.wrap(buffer, 0, chunk), written)
                written += chunk
            }
            channel.force(true)
        }
        val seconds = (System.nanoTime() - started) / 1e9
        return size / (1024.0 * 1024.0) / seconds
    }

    private fun measureRead(file: File): Double {
        val started = System.nanoTime()
        var total = 0L
        RandomAccessFile(file, "r").use { raf ->
            val buffer = ByteArray(4 * 1024 * 1024)
            while (true) {
                val n = raf.read(buffer)
                if (n <= 0) break
                total += n
            }
        }
        val seconds = (System.nanoTime() - started) / 1e9
        return (total / (1024.0 * 1024.0)) / seconds
    }

    private fun measureEncrypt(size: Long): Double {
        val key = com.loopjet.app.core.AeadKey(ByteArray(32) { it.toByte() })
        val plain = ByteArray(ProtocolLimits.CHUNK_SIZE)
        val sealed = ByteArray(ProtocolLimits.CHUNK_SIZE + 16)
        val nonce = ByteArray(12)
        val aad = ByteArray(5)
        var done = 0L
        val started = System.nanoTime()
        while (done < size) {
            val n = key.seal(nonce, aad, 0, 5, plain, 0, plain.size, sealed, 0)
            done += n
            nonce[11] = (nonce[11] + 1).toByte()
        }
        val seconds = (System.nanoTime() - started) / 1e9
        return (done / (1024.0 * 1024.0)) / seconds
    }

    private fun measureEngine(file: File, workDir: File): Double {
        val receiveDir = File(workDir, "recv").apply { mkdirs() }
        val receiver = ReceiverEngine(
            identity = SoftwareIdentity.generate(),
            sinkFactory = LocalFileSinkFactory(receiveDir),
            listener = LoggingListener(),
            requestedPort = 43200,
            stallTimeoutMs = 20_000,
            sessionLog = {}
        )
        receiver.bind()
        val controlPort = receiver.controlPort
        val result = AtomicReference<Any?>()
        val thread = Thread { result.set(receiver.runOnce()) }
        thread.start()
        Thread.sleep(120)
        val manifest = TransferManifest(
            sessionId = "benchmark",
            files = listOf(
                TransferFile(1, "bench.bin", file.length(), file.lastModified(), mime = "application/octet-stream")
            ),
            dirs = emptyList(),
            apps = emptyList(),
            totalBytes = file.length(),
            chunkSize = ProtocolLimits.CHUNK_SIZE,
            senderName = "Benchmark",
            startedAt = System.currentTimeMillis()
        )
        val sender = SenderEngine(
            identity = SoftwareIdentity.generate(),
            manifest = manifest,
            sourceFor = { FileSource(file) },
            connector = SocketConnector { port, timeout ->
                Socket().apply { connect(InetSocketAddress("127.0.0.1", port), timeout) }
            },
            controlPort = controlPort,
            listener = LoggingListener(),
            maxStreams = 4,
            sessionLog = {}
        )
        val started = System.nanoTime()
        val senderResult = sender.run()
        val elapsed = (System.nanoTime() - started) / 1e9
        thread.join(30_000)
        receiver.close()
        receiveDir.deleteRecursively()
        val bytes = if (senderResult.bytesTransferred > 0) senderResult.bytesTransferred else 1
        return (bytes / (1024.0 * 1024.0)) / elapsed
    }

    fun format(result: Result): String = buildString {
        appendLine("Storage write : ${"%.0f".format(result.writeMbPerSec)} MB/s")
        appendLine("Storage read  : ${"%.0f".format(result.readMbPerSec)} MB/s")
        appendLine("Encryption    : ${"%.0f".format(result.encryptMbPerSec)} MB/s")
        appendLine("Engine        : ${"%.1f".format(result.engineMbPerSec)} MB/s")
        appendLine("Wi-Fi link    : ${result.linkMbps} Mbps (${result.band})")
        appendLine("Utilisation   : ${"%.0f".format(result.linkUtilisation * 100)} % of link rate")
    }
}
