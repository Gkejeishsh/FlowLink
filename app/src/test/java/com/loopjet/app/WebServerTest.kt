package com.loopjet.app

import com.loopjet.app.core.SharedFile
import com.loopjet.app.core.WebConfig
import com.loopjet.app.core.WebMode
import com.loopjet.app.core.WebServer
import com.loopjet.app.core.WebServerEvents
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.zip.ZipInputStream

/**
 * The browser side of Web Share and Invite, exercised over real loopback sockets.
 *
 * These tests exist because two of the things a browser needs most — an exact `Content-Length` on the
 * streamed ZIP (so it can show progress) and a correct 206 for resumed downloads — are easy to get
 * subtly wrong and impossible to eyeball on a phone. Nothing here is mocked: a real socket, the real
 * request parser, the real ZIP writer.
 */
class WebServerTest {

    private lateinit var root: File
    private val running = ArrayList<WebServer>()

    private class Events : WebServerEvents {
        val logs: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
        val uploads: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
        override fun onLog(message: String) {
            logs.add(message)
        }

        override fun onDownload(name: String, sent: Long, total: Long) {}
        override fun onUpload(name: String, received: Long, total: Long) {}
        override fun onClientSeen(remote: String, userAgent: String) {}
        override fun onFinished(reason: String) {}
        override fun onUploadFinished(name: String, file: File) {
            uploads.add(name)
        }
    }

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "loopjet-web-" + System.nanoTime())
        assertTrue(root.mkdirs() || root.isDirectory)
    }

    @After
    fun tearDown() {
        running.forEach { runCatching { it.close() } }
        running.clear()
        root.deleteRecursively()
    }

    private fun start(
        files: List<SharedFile>,
        uploadDir: File? = null,
        allowUpload: Boolean = false,
        pin: String? = null
    ): WebServer {
        val config = WebConfig(
            title = "Test phone",
            versionName = "1.0.0",
            mode = WebMode.SHARE,
            files = files,
            root = uploadDir ?: root,
            uploadDir = uploadDir,
            allowUpload = allowUpload,
            bindAddress = InetAddress.getByName("127.0.0.1"),
            pin = pin,
            requirePin = pin != null,
            maxConnections = 4,
            idleTimeoutMs = 10_000
        )
        val server = WebServer(config, Events())
        server.start()
        running.add(server)
        return server
    }

    private class Response(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
        val headText: String
    )

    /** One request on a fresh connection, read exactly as a browser would read it. */
    private fun request(
        server: WebServer,
        method: String,
        path: String,
        extraHeaders: List<String> = emptyList(),
        body: ByteArray? = null
    ): Response {
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 20_000
            val out = socket.getOutputStream()
            val head = StringBuilder()
            head.append("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nUser-Agent: loopjet-test\r\n")
            extraHeaders.forEach { head.append(it).append("\r\n") }
            if (body != null) head.append("Content-Length: ${body.size}\r\n")
            head.append("\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) out.write(body)
            out.flush()

            val input = socket.getInputStream()
            val headerBytes = ByteArrayOutputStream()
            var matched = 0
            while (matched < 4) {
                val b = input.read()
                if (b < 0) throw IOException("connection closed inside the response headers")
                headerBytes.write(b)
                matched = when {
                    b == 13 && (matched == 0 || matched == 2) -> matched + 1
                    b == 10 && (matched == 1 || matched == 3) -> matched + 1
                    else -> 0
                }
            }
            val headText = headerBytes.toString("ISO-8859-1")
            val lines = headText.split("\r\n")
            val status = lines.first().split(" ").getOrNull(1)?.toIntOrNull()
                ?: throw IOException("unparsable status line: ${lines.first()}")
            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                if (line.isBlank()) continue
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val declared = headers["content-length"]?.toLongOrNull() ?: 0L
            val payload = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var remaining = declared
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) break
                payload.write(buffer, 0, n)
                remaining -= n
            }
            assertEquals("server closed before sending the bytes it promised", 0L, remaining)
            return Response(status, headers, payload.toByteArray(), headText)
        }
    }

    @Test
    fun streamedZipDeclaresItsExactLengthAndExtracts() {
        val ascii = File(root, "alpha.txt").apply { writeText("hello loopjet", Charsets.UTF_8) }
        val unicode = File(root, "héllo-🚀.txt").apply { writeText("unicode name survives", Charsets.UTF_8) }
        val server = start(
            listOf(
                SharedFile(1, ascii.name, ascii.length(), "text/plain", ascii),
                SharedFile(2, unicode.name, unicode.length(), "text/plain", unicode)
            )
        )

        val response = request(server, "GET", "/api/zip")
        assertEquals(200, response.status)
        assertEquals("application/zip", response.headers["content-type"])
        val declared = response.headers["content-length"]?.toLongOrNull()
        assertNotNull("the ZIP response must declare its length so a browser can show progress", declared)
        assertEquals("declared length must equal the bytes actually sent", declared!!, response.body.size.toLong())

        val entries = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(response.body)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals("both files must be in the archive", 2, entries.size)
        assertEquals("hello loopjet", entries["alpha.txt"])
        assertEquals("unicode name survives", entries[unicode.name])
    }

    @Test
    fun zipEntryNamesCannotEscapeTheExtractionFolder() {
        val odd = File(root, "payload.bin").apply { writeBytes(ByteArray(2048) { (it % 97).toByte() }) }
        // A display name that would be a traversal if it were handed to an extractor unchanged.
        val server = start(listOf(SharedFile(1, "../../etc/passwd", odd.length(), "application/octet-stream", odd)))

        val response = request(server, "GET", "/api/zip")
        assertEquals(200, response.status)
        ZipInputStream(ByteArrayInputStream(response.body)).use { zip ->
            val name = zip.nextEntry?.name
            assertNotNull(name)
            assertFalse("entry name must not contain a parent reference", name!!.contains(".."))
            assertFalse("entry name must not start at the filesystem root", name.startsWith("/"))
        }
    }

    @Test
    fun rangeRequestResumesAtTheRequestedOffset() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val file = File(root, "big.bin").apply { writeBytes(payload) }
        val server = start(listOf(SharedFile(1, file.name, file.length(), "application/octet-stream", file)))

        val response = request(server, "GET", "/files/1", extraHeaders = listOf("Range: bytes=1000-1999"))
        assertEquals(206, response.status)
        assertEquals("bytes 1000-1999/4096", response.headers["content-range"])
        assertEquals("bytes", response.headers["accept-ranges"])
        assertEquals(1000, response.body.size)
        assertArrayEquals(payload.copyOfRange(1000, 2000), response.body)
    }

    @Test
    fun uploadStreamsToDiskAndCannotWriteOutsideItsFolder() {
        val uploads = File(root, "uploads").apply { mkdirs() }
        val server = start(emptyList(), uploadDir = uploads, allowUpload = true)

        val payload = ByteArray(300_000) { (it * 7 % 251).toByte() }
        val traversal = URLEncoder.encode("../../escape.bin", "UTF-8")
        val response = request(server, "POST", "/api/upload?name=$traversal", body = payload)
        assertEquals(200, response.status)
        assertEquals(1, uploads.listFiles()!!.count { it.isFile })
        assertEquals(payload.size.toLong(), uploads.listFiles()!!.first { it.isFile }.length())
        assertFalse("upload escaped to the parent of its folder", File(root, "escape.bin").exists())
        assertFalse("upload escaped to the parent of its folder", File(root.parentFile, "escape.bin").exists())
        assertTrue(
            "no .part file may be left behind",
            uploads.listFiles()!!.none { it.name.endsWith(".part") }
        )

        val named = URLEncoder.encode("photo.jpg", "UTF-8")
        val second = request(server, "POST", "/api/upload?name=$named", body = ByteArray(4096) { 7 })
        assertEquals(200, second.status)
        val photo = File(uploads, "photo.jpg")
        assertTrue(photo.isFile)
        assertEquals(4096L, photo.length())
    }

    @Test
    fun pinIsRequiredOnEveryRequest() {
        val file = File(root, "a.txt").apply { writeText("x") }
        val server = start(listOf(SharedFile(1, file.name, file.length(), "text/plain", file)), pin = "1234")

        assertEquals(401, request(server, "GET", "/api/list").status)
        assertEquals(200, request(server, "GET", "/api/list?pin=1234").status)
    }

    @Test
    fun sharePageNeedsNoNetworkResourcesBeyondThePhone() {
        val file = File(root, "a.txt").apply { writeText("x") }
        val server = start(listOf(SharedFile(1, file.name, file.length(), "text/plain", file)))

        val response = request(server, "GET", "/")
        assertEquals(200, response.status)
        val html = response.body.toString(Charsets.UTF_8)
        assertTrue("the page must carry the app name", html.contains("LoopJet"))
        assertTrue("the page must link the shared file", html.contains("/files/1"))
        // The only URL in the markup is the SVG namespace, which is an identifier, not a fetch.
        val withoutNamespaces = html.replace("http://www.w3.org/2000/svg", "")
        for (forbidden in listOf("https://", "http://", "googleapis", "cdn.", "integrity=", "crossorigin")) {
            assertFalse("the page must not reach for $forbidden", withoutNamespaces.contains(forbidden, ignoreCase = true))
        }
    }
}
