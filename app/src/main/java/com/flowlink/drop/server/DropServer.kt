package com.flowlink.drop.server

import com.flowlink.drop.core.Names
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The HTTP/1.1 server the guest's browser talks to. No Android types: the whole thing is exercised
 * by JVM tests against a loopback socket, which is the only way to prove upload behaviour without a
 * phone in hand — and uploads that corrupt a 2 GB video are exactly the bug worth proving absent.
 *
 * Design decisions that are not obvious:
 *  * `Expect: 100-continue` is answered before the body is read. Chrome sends it for large bodies; a
 *    server that ignores it makes the browser wait, then time out.
 *  * Bodies are streamed to the sink. Nothing is buffered whole, ever.
 *  * `Transfer-Encoding: chunked` is decoded, because a browser with an unknown file size uses it.
 *  * multipart/form-data is parsed so the page still works with JavaScript disabled.
 */
class DropServer(
    private val sinkFactory: SinkFactory,
    private val pageProvider: () -> String,
    private val listener: Listener,
    private val maxFileBytes: Long = 4L * 1024L * 1024L * 1024L
) {

    interface Listener {
        fun onClientSeen(remote: String, userAgent: String)
        fun onUploadStarted(name: String, remote: String)
        fun onUploadProgress(name: String, bytes: Long, total: Long)
        fun onUploadFinished(name: String, path: String, bytes: Long)
        fun onUploadFailed(name: String, reason: String)
        fun onLog(message: String)
    }

    val requestLog = RequestLog()

    @Volatile
    var phoneName: String = "this phone"

    @Volatile
    var boundPort: Int = 0
        private set

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = ThreadPoolExecutor(
        4, 12, 30, TimeUnit.SECONDS, ArrayBlockingQueue(24),
        { runnable ->
            Thread(runnable, "drop-http-${counter.incrementAndGet()}").apply { isDaemon = true }
        },
        ThreadPoolExecutor.DiscardPolicy()
    )

    private companion object {
        val counter = AtomicInteger(0)
        const val IDLE_TIMEOUT_MS = 5_000
        const val MAX_HEADER_BYTES = 8 * 1024
        const val MAX_HEADERS = 64
        const val COPY_BUFFER = 256 * 1024
        const val PROGRESS_STEP = 512L * 1024L
    }

    // ---------------------------------------------------------------- lifecycle

    fun start(ports: List<Int>): Int {
        if (running) return boundPort
        for (port in ports) {
            val socket = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port), 64)
                }
            } catch (t: Throwable) {
                listener.onLog("port $port unavailable: ${t.message}")
                continue
            }
            serverSocket = socket
            boundPort = socket.localPort
            running = true
            acceptThread = Thread({ acceptLoop(socket) }, "drop-accept").apply {
                isDaemon = true
                start()
            }
            listener.onLog("listening on 0.0.0.0:${socket.localPort}")
            return socket.localPort
        }
        throw IOException("no free port between ${ports.first()} and ${ports.last()}")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                if (running) listener.onLog("accept failed: ${t.message}")
                break
            }
            runCatching { workers.execute { serveClient(client) } }
                .onFailure { runCatching { client.close() } }
        }
    }

    // ---------------------------------------------------------------- request loop

    private fun serveClient(socket: Socket) {
        try {
            socket.use { client ->
                client.soTimeout = IDLE_TIMEOUT_MS
                client.tcpNoDelay = true
                val input = BufferedInputStream(client.getInputStream(), 64 * 1024)
                val output = BufferedOutputStream(client.getOutputStream(), 64 * 1024)
                var keepAlive = true
                while (keepAlive && running) {
                    val request = try {
                        readRequest(input)
                    } catch (t: Throwable) {
                        null
                    } ?: break
                    val started = System.currentTimeMillis()
                    val remote = client.inetAddress?.hostAddress ?: "unknown"
                    listener.onClientSeen(remote, request.headers["user-agent"].orEmpty())
                    if (request.wantsContinue) {
                        output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                        output.flush()
                    }
                    val response = try {
                        route(request, input, remote)
                    } catch (t: Throwable) {
                        listener.onLog("router failed: ${t.message}")
                        Response.text(500, "server error: ${t.message}")
                    }
                    writeResponse(output, response)
                    requestLog.add(
                        RequestLog.Entry(
                            time = started,
                            remote = remote,
                            method = request.method,
                            path = if (request.query.isEmpty()) request.path
                            else request.path + "?" + request.query.entries.joinToString("&") { "${it.key}=…" },
                            bytes = response.bytesOut,
                            status = response.status,
                            durationMs = System.currentTimeMillis() - started
                        )
                    )
                    keepAlive = response.keepAlive && !request.wantsClose
                }
            }
        } catch (t: Throwable) {
            // A client that walks away mid-upload is normal, not an error worth shouting about.
            listener.onLog("client closed: ${t.message}")
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        if (line.isBlank()) return readRequest(input)
        val parts = line.trim().split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val version = parts.getOrElse(2) { "HTTP/1.1" }
        val headers = LinkedHashMap<String, String>()
        var total = 0
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
            total += header.length
            if (total > MAX_HEADER_BYTES || headers.size >= MAX_HEADERS) {
                throw IOException("headers too large")
            }
            val colon = header.indexOf(':')
            if (colon <= 0) continue
            headers[header.substring(0, colon).trim().lowercase()] =
                header.substring(colon + 1).trim()
        }
        val path = target.substringBefore('?')
        val query = parseQuery(if (target.contains('?')) target.substringAfter('?') else "")
        return Request(method, path, query, headers, version)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val key = Names.urlDecode(pair.substringBefore('='))
            val value = Names.urlDecode(pair.substringAfter('=', ""))
            out[key] = value
        }
        return out
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        var read = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (read == 0) null else buffer.toString("ISO-8859-1")
            read++
            if (read > 8 * 1024) throw IOException("line too long")
            if (byte == '\n'.code) break
            if (byte != '\r'.code) buffer.write(byte)
        }
        return buffer.toString("ISO-8859-1")
    }

    // ---------------------------------------------------------------- routing

    private fun route(request: Request, input: InputStream, remote: String): Response = when {
        request.method == "GET" || request.method == "HEAD" -> when (request.path) {
            "/", "/index.html" -> Response.html(pageProvider(), head = request.method == "HEAD")
            "/favicon.ico" -> Response.bytes(200, "image/svg+xml", ICON.toByteArray())
            "/api/ping", "/api/info" -> Response.json(infoJson())
            else -> Response.html(404, simplePage("Not found", "This phone only serves the drop page."))
        }
        request.method == "POST" -> when (request.path) {
            "/api/upload", "/upload" -> handleUpload(request, input, remote)
            else -> Response.text(404, "not found")
        }
        request.method == "OPTIONS" -> Response(204, "text/plain", ByteArray(0))
        else -> Response.text(405, "method not allowed").closing()
    }

    private fun infoJson(): String {
        val free = freeSpaceText
        return """{"ok":true,"name":${json(phoneName)},"free":$free,"maxFile":$maxFileBytes,"version":1}"""
    }

    @Volatile
    var freeSpaceText: Long = -1

    private fun handleUpload(request: Request, input: InputStream, remote: String): Response {
        val contentType = request.headers["content-type"].orEmpty()
        return if (contentType.startsWith("multipart/form-data", ignoreCase = true)) {
            handleMultipart(request, input, remote, contentType)
        } else {
            handleRaw(request, input, remote)
        }
    }

    private fun bodyStream(request: Request, input: InputStream): InputStream {
        val chunked = request.headers["transfer-encoding"]?.contains("chunked", true) == true
        return if (chunked) ChunkedInputStream(input) else input
    }

    private fun contentLength(request: Request): Long =
        request.headers["content-length"]?.toLongOrNull() ?: -1L

    private fun handleRaw(request: Request, input: InputStream, remote: String): Response {
        val length = contentLength(request)
        if (length > maxFileBytes) {
            return Response.text(413, "too large").closing()
        }
        val rawName = request.query["name"] ?: "file"
        val name = Names.sanitize(rawName)
        val mime = Names.mimeOf(name)
        val sink = try {
            sinkFactory.create(name, mime)
        } catch (t: Throwable) {
            listener.onUploadFailed(name, t.message ?: "cannot create file")
            return Response.text(507, "not enough space on the phone")
        }
        listener.onUploadStarted(sink.displayName, remote)
        var received = 0L
        var lastTick = 0L
        try {
            val buffer = ByteArray(COPY_BUFFER)
            val body = bodyStream(request, input)
            while (true) {
                // length < 0 means chunked: read until the stream ends, not until a counter does.
                val max = if (length >= 0) minOf(buffer.size.toLong(), length - received)
                else buffer.size.toLong()
                if (max <= 0) break
                val read = body.read(buffer, 0, max.toInt())
                if (read < 0) break
                sink.write(buffer, 0, read)
                received += read
                if (received > maxFileBytes) throw IOException("too large")
                if (received - lastTick >= PROGRESS_STEP) {
                    lastTick = received
                    listener.onUploadProgress(sink.displayName, received, maxOf(length, received))
                }
            }
            if (length >= 0 && received != length) throw IOException("upload incomplete")
            val where = sink.commit()
            listener.onUploadFinished(sink.displayName, where, received)
            return Response.json(
                """{"ok":true,"name":${json(sink.displayName)},"size":$received,"where":${json(where)}}"""
            )
        } catch (t: Throwable) {
            runCatching { sink.abort() }
            listener.onUploadFailed(sink.displayName, t.message ?: "upload failed")
            return Response.text(if (t.message?.contains("space") == true) 507 else 500, "upload failed")
                .closing()
        }
    }

    private fun handleMultipart(
        request: Request,
        input: InputStream,
        remote: String,
        contentType: String
    ): Response {
        val boundary = boundaryOf(contentType)
            ?: return Response.text(400, "missing multipart boundary").closing()
        val length = contentLength(request)
        if (length > maxFileBytes) return Response.text(413, "too large").closing()
        val body = bodyStream(request, input)
        val reader = BoundaryReader(body, boundary)
        val accepted = ArrayList<String>()
        try {
            if (!reader.skipToFirstBoundary()) {
                return Response.text(400, "malformed multipart").closing()
            }
            while (true) {
                val headerLine = reader.readLine()
                if (headerLine == null || headerLine.startsWith("--")) break
                var disposition = ""
                var total = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    total += line.length
                    if (total > MAX_HEADER_BYTES) break
                    if (line.lowercase().startsWith("content-disposition:")) disposition = line
                }
                val rawName = disposition
                    .substringAfter("filename=\"", "")
                    .substringBefore("\"")
                    .ifBlank { disposition.substringAfter("filename=", "").trim() }
                if (rawName.isBlank()) {
                    // A plain form field, not a file: consume it so the parse stays aligned.
                    reader.readPart { _, _, _ -> }
                } else {
                    val name = Names.sanitize(rawName)
                    val sink = try {
                        sinkFactory.create(name, Names.mimeOf(name))
                    } catch (t: Throwable) {
                         return Response.text(507, "not enough space on the phone")
                    }
                    listener.onUploadStarted(sink.displayName, remote)
                    var received = 0L
                    var tick = 0L
                    try {
                        val complete = reader.readPart { buffer, offset, count ->
                            if (count > 0) {
                                sink.write(buffer, offset, count)
                                received += count
                                if (received - tick >= PROGRESS_STEP) {
                                    tick = received
                                    listener.onUploadProgress(sink.displayName, received, received)
                                }
                            }
                        }
                        if (!complete) throw IOException("multipart truncated")
                        val where = sink.commit()
                        accepted.add(sink.displayName)
                        listener.onUploadFinished(sink.displayName, where, received)
                    } catch (t: Throwable) {
                        runCatching { sink.abort() }
                        listener.onUploadFailed(name, t.message ?: "upload failed")
                        throw t
                    }
                }
            }
            return Response.html(200, sentPage(accepted))
        } catch (t: Throwable) {
            listener.onLog("multipart failed: ${t.message}")
            return Response.text(500, "upload failed").closing()
        }
    }

    private fun drain(input: InputStream, request: Request) {
        val length = contentLength(request)
        if (length in 1..(1024 * 1024)) {
            runCatching { input.skipNBytes(length) }
        }
    }

    private fun boundaryOf(contentType: String): String? {
        val marker = "boundary="
        val index = contentType.indexOf(marker, ignoreCase = true)
        if (index < 0) return null
        var value = contentType.substring(index + marker.length).trim()
        if (value.startsWith("\"")) value = value.substring(1).substringBefore('"')
        return value.ifBlank { null }
    }

    // ---------------------------------------------------------------- responses

    private fun writeResponse(output: OutputStream, response: Response) {
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(statusText(response.status))
                .append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Connection: ").append(if (response.keepAlive) "keep-alive" else "close").append("\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray())
        if (response.body.isNotEmpty()) output.write(response.body)
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        204 -> "No Content"
        303 -> "See Other"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        507 -> "Insufficient Storage"
        else -> "OK"
    }

    private fun json(value: String): String {
        val out = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private fun sentPage(names: List<String>): String = simplePage(
        "Sent",
        if (names.isEmpty()) "Nothing arrived." else
            names.joinToString("<br>") { "$it — on the phone now." } +
                "<p><a href=\"/\">Send more</a></p>"
    )

    private fun simplePage(title: String, body: String): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>$title</title>
        <style>body{background:#0b0f17;color:#f2f5ff;font:16px/1.5 system-ui,sans-serif;
        display:grid;place-items:center;min-height:100vh;margin:0;padding:24px;text-align:center}
        a{color:#7aa2ff}</style></head>
        <body><main><h1>$title</h1><p>$body</p></main></body></html>
    """.trimIndent()

    // ---------------------------------------------------------------- types

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val version: String
    ) {
        val wantsContinue: Boolean
            get() = headers["expect"]?.contains("100-continue", true) == true
        val wantsClose: Boolean
            get() = headers["connection"]?.contains("close", true) == true ||
                version == "HTTP/1.0"
    }

    private class Response(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        var keepAlive: Boolean = true,
        var bytesOut: Long = 0
    ) {
        /** Some answers leave unread bytes on the socket, so the connection must end with them. */
        fun closing(): Response = also { keepAlive = false }

        companion object {
            fun text(status: Int, text: String) =
                Response(status, "text/plain; charset=utf-8", text.toByteArray())

            fun html(status: Int, html: String) =
                Response(status, "text/html; charset=utf-8", html.toByteArray())

            fun html(html: String, head: Boolean) = html(200, html).let {
                if (head) Response(it.status, it.contentType, ByteArray(0)) else it
            }

            fun json(json: String) = Response(200, "application/json", json.toByteArray())

            fun bytes(status: Int, type: String, bytes: ByteArray) = Response(status, type, bytes)
        }
    }

    /** Decodes `Transfer-Encoding: chunked`. */
    private class ChunkedInputStream(private val input: InputStream) : InputStream() {
        private var remaining = 0L
        private var finished = false

        private fun nextChunk() {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) { finished = true; return }
                if (b == '\n'.code) break
                if (b != '\r'.code) line.append(b.toChar())
            }
            val size = line.toString().trim().substringBefore(';').toLongOrNull(16) ?: 0L
            if (size == 0L) {
                // Consume the trailer.
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    if (b == '\n'.code) break
                }
                finished = true
                remaining = 0
            } else {
                remaining = size
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (finished) return -1
            if (remaining == 0L) {
                nextChunk()
                if (finished) return -1
            }
            val wanted = minOf(len.toLong(), remaining).toInt()
            val read = input.read(b, off, wanted)
            if (read > 0) remaining -= read
            if (remaining == 0L) {
                // Swallow the CRLF that follows each chunk.
                input.read(); input.read()
            }
            return read
        }
    }

    /**
     * Streams a multipart body without buffering a file: it hands out everything up to the next
     * boundary and never holds more than one buffer.
     */
    private class BoundaryReader(private val input: InputStream, boundary: String) {
        private val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        private val inner = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
        private val buffer = ByteArray(128 * 1024)
        private var position = 0
        private var limit = 0
        private var eof = false
        private var firstChunk = true

        private fun fill(): Boolean {
            if (eof) return false
            if (position < limit) {
                System.arraycopy(buffer, position, buffer, 0, limit - position)
                limit -= position
                position = 0
            } else {
                position = 0
                limit = 0
            }
            val read = input.read(buffer, limit, buffer.size - limit)
            if (read < 0) {
                eof = true
                return false
            }
            limit += read
            return true
        }

        fun skipToFirstBoundary(): Boolean {
            // The body starts with the bare boundary and no preceding CRLF.
            while (true) {
                val index = indexOf(delimiter, position, limit)
                if (index >= 0) {
                    position = index + delimiter.size
                    firstChunk = false
                    return true
                }
                if (!fill()) return false
                if (limit < delimiter.size) return false
            }
        }

        fun readLine(): String? {
            val out = ByteArrayOutputStream(64)
            while (true) {
                if (position >= limit && !fill()) return null
                val b = buffer[position++]
                if (b == '\n'.code.toByte()) return out.toString("ISO-8859-1").trimEnd('\r')
                out.write(b.toInt())
                if (out.size() > 8 * 1024) return null
            }
        }

        /** Returns true when the closing boundary was reached (the part was complete). */
        fun readPart(sink: (ByteArray, Int, Int) -> Unit): Boolean {
            if (firstChunk) {
                firstChunk = false
            }
            while (true) {
                if (limit - position < inner.size && !fill()) {
                    if (limit > position) sink(buffer, position, limit - position)
                    position = limit
                    return false
                }
                val index = indexOf(inner, position, limit)
                if (index >= 0) {
                    if (index > position) sink(buffer, position, index - position)
                    position = index + inner.size
                    return true
                }
                val keep = inner.size - 1
                val flushEnd = limit - keep
                if (flushEnd > position) {
                    sink(buffer, position, flushEnd - position)
                    position = flushEnd
                }
                if (!fill()) return false
            }
        }

        private fun indexOf(needle: ByteArray, from: Int, until: Int): Int {
            val last = until - needle.size
            var start = from
            while (start <= last) {
                var matched = true
                var offset = 0
                while (offset < needle.size) {
                    if (buffer[start + offset] != needle[offset]) {
                        matched = false
                        break
                    }
                    offset++
                }
                if (matched) return start
                start++
            }
            return -1
        }
    }

    private val ICON = """
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
        <rect width="64" height="64" rx="16" fill="#255CE8"/>
        <path d="M32 16v24m0 0l-9-9m9 9l9-9" stroke="#fff" stroke-width="6" stroke-linecap="round"/>
        <rect x="16" y="44" width="32" height="5" rx="2.5" fill="#fff" opacity=".85"/></svg>
    """.trimIndent()
}
