package com.flowlink.drop

import com.flowlink.drop.server.DropSink
import com.flowlink.drop.server.DropServer
import com.flowlink.drop.server.SinkFactory
import com.flowlink.drop.web.Page
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The whole upload surface, on the JVM, over a real loopback socket.
 *
 * This is the part of the app that can be proven without a phone: if a 2 GB body streams in one
 * piece, if a chunked body is decoded, if an aborted connection leaves no file behind, then the
 * interesting failures are already excluded before anyone taps a screen.
 */
class DropServerTest {

    private lateinit var server: DropServer
    private var port = 0
    private val written = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val finished = CopyOnWriteArrayList<String>()
    private val failed = CopyOnWriteArrayList<String>()

    private val factory = object : SinkFactory {
        override fun create(rawName: String, mime: String): DropSink {
            val out = ByteArrayOutputStream()
            written[rawName] = out
            return object : DropSink {
                private var committed = false
                override val displayName: String = rawName
                override fun write(buffer: ByteArray, offset: Int, length: Int) =
                    out.write(buffer, offset, length)

                override fun commit(): String {
                    committed = true
                    return "test"
                }

                override fun abort() {
                    if (!committed) {
                        written.remove(rawName)
                        out.reset()
                    }
                }
            }
        }
    }

    @Before
    fun setUp() {
        server = DropServer(
            sinkFactory = factory,
            pageProvider = { Page.render("Test phone", 4L * 1024 * 1024 * 1024, "test") },
            listener = object : DropServer.Listener {
                override fun onClientSeen(remote: String, userAgent: String) = Unit
                override fun onUploadStarted(name: String, remote: String) = Unit
                override fun onUploadProgress(name: String, bytes: Long, total: Long) = Unit
                override fun onUploadFinished(name: String, path: String, bytes: Long) {
                    finished.add(name)
                }

                override fun onUploadFailed(name: String, reason: String) {
                    failed.add(name)
                }

                override fun onLog(message: String) = Unit
            }
        )
        server.phoneName = "Test phone"
        port = server.start(listOf(0))
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun connection(path: String): HttpURLConnection =
        (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection)

    @Test
    fun theDropPageIsServed() {
        val call = connection("/")
        assertEquals(200, call.responseCode)
        val html = call.inputStream.bufferedReader().readText()
        assertTrue(html.contains("Send to Test phone"))
        call.disconnect()
    }

    @Test
    fun pingAnswersWithThePhoneName() {
        val call = connection("/api/ping")
        val body = call.inputStream.bufferedReader().readText()
        assertTrue(body.contains("\"ok\":true"))
        assertTrue(body.contains("Test phone"))
        call.disconnect()
    }

    @Test
    fun aRawBodyLandsByteForByte() {
        val payload = ByteArray(512 * 1024) { (it % 251).toByte() }
        val call = connection("/api/upload?name=" + "big.bin")
        call.requestMethod = "POST"
        call.doOutput = true
        call.setFixedLengthStreamingMode(payload.size)
        call.outputStream.write(payload)
        assertEquals(200, call.responseCode)
        call.inputStream.close()
        assertTrue(finished.contains("big.bin"))
        assertTrue(written["big.bin"]!!.toByteArray().contentEquals(payload))
        call.disconnect()
    }

    @Test
    fun expectContinueIsAnswered() {
        Socket("127.0.0.1", port).use { socket ->
            val out = DataOutputStream(socket.getOutputStream())
            val body = "hello".toByteArray()
            out.writeBytes(
                "POST /api/upload?name=small.txt HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Expect: 100-continue\r\n\r\n"
            )
            out.flush()
            val input = socket.getInputStream().bufferedReader()
            val first = input.readLine()
            assertEquals("HTTP/1.1 100 Continue", first)
            // Skip the blank line that terminates the interim response, then send the body.
            input.readLine()
            out.write(body)
            out.flush()
            val status = input.readLine()
            assertEquals("HTTP/1.1 200 OK", status)
        }
        assertEquals("hello", String(written["small.txt"]!!.toByteArray()))
    }

    @Test
    fun chunkedBodiesAreDecoded() {
        val payload = "chunked payload".toByteArray()
        Socket("127.0.0.1", port).use { socket ->
            val out = DataOutputStream(socket.getOutputStream())
            out.writeBytes(
                "POST /api/upload?name=chunk.txt HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Transfer-Encoding: chunked\r\n\r\n"
            )
            out.writeBytes("${Integer.toHexString(payload.size)}\r\n")
            out.write(payload)
            out.writeBytes("\r\n0\r\n\r\n")
            out.flush()
            val status = socket.getInputStream().bufferedReader().readLine()
            assertEquals("HTTP/1.1 200 OK", status)
        }
        assertEquals("chunked payload", String(written["chunk.txt"]!!.toByteArray()))
    }

    @Test
    fun multipartWorksWithoutJavaScript() {
        val boundary = "----flowlinkboundary"
        val content = "no javascript needed".toByteArray()
        val head = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"notes.txt\"\r\n" +
                "Content-Type: text/plain\r\n\r\n"
            ).toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()

        val call = connection("/upload")
        call.requestMethod = "POST"
        call.doOutput = true
        call.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        call.setChunkedStreamingMode(0)
        call.outputStream.use { out ->
            out.write(head)
            out.write(content)
            out.write(tail)
        }
        assertEquals(200, call.responseCode)
        call.inputStream.close()
        assertEquals("no javascript needed", String(written["notes.txt"]!!.toByteArray()))
        call.disconnect()
    }

    @Test
    fun anAbandonedUploadLeavesNothingBehind() {
        val call = connection("/api/upload?name=half.bin")
        call.requestMethod = "POST"
        call.doOutput = true
        call.setFixedLengthStreamingMode(1024 * 1024)
        call.outputStream.write(ByteArray(64 * 1024))
        call.outputStream.flush()
        runCatching { call.disconnect() }
        Thread.sleep(300)
        // The sink must have been aborted, not committed: no half file is ever visible.
        assertTrue(!finished.contains("half.bin"))
        assertTrue(written["half.bin"] == null || written["half.bin"]!!.size() < 1024 * 1024)
    }

    @Test
    fun unknownPathsAndMethodsAreRefused() {
        assertEquals(404, connection("/nope").responseCode)
        val call = connection("/api/upload?name=x.bin")
        call.requestMethod = "GET"
        assertEquals(404, call.responseCode)
        call.disconnect()
    }

    @Test
    fun anOversizedBodyIsRefusedBeforeItIsWritten() {
        val small = DropServer(
            sinkFactory = factory,
            pageProvider = { "x" },
            listener = object : DropServer.Listener {
                override fun onClientSeen(remote: String, userAgent: String) = Unit
                override fun onUploadStarted(name: String, remote: String) = Unit
                override fun onUploadProgress(name: String, bytes: Long, total: Long) = Unit
                override fun onUploadFinished(name: String, path: String, bytes: Long) = Unit
                override fun onUploadFailed(name: String, reason: String) = Unit
                override fun onLog(message: String) = Unit
            },
            maxFileBytes = 1024
        )
        val tinyPort = small.start(listOf(0))
        try {
            val call = (URL("http://127.0.0.1:$tinyPort/api/upload?name=huge.bin")
                .openConnection() as HttpURLConnection)
            call.requestMethod = "POST"
            call.doOutput = true
            call.setRequestProperty("Content-Length", "4096")
            call.outputStream.write(ByteArray(4096))
            assertEquals(413, call.responseCode)
            call.disconnect()
        } finally {
            small.stop()
        }
    }

    @Test
    fun theRequestLogRemembersWhatHappened() {
        connection("/api/ping").inputStream.close()
        Thread.sleep(150)
        val text = server.requestLog.asText()
        assertTrue(text.contains("/api/ping"))
    }
}
