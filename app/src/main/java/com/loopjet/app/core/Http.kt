package com.loopjet.app.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SharedFile(
    val id: Int,
    val name: String,
    val size: Long,
    val mime: String,
    val file: File
)

interface WebServerEvents {
    fun onLog(message: String)
    fun onDownload(name: String, sent: Long, total: Long)
    fun onUpload(name: String, received: Long, total: Long)
    fun onClientSeen(remote: String, userAgent: String)
    fun onFinished(reason: String)
}

/**
 * Hand-written HTTP/1.1 server.
 *
 * Why not a library: every server library on the JVM either buffers request bodies in memory or
 * needs a framework whose throughput nobody measured here. This one streams both directions through
 * a fixed 256 KiB buffer, uses `FileChannel.transferTo` for downloads (zero-copy on supported
 * providers), supports Range requests for resumable downloads, streams uploads straight to disk and
 * generates a ZIP entry-by-entry. Its throughput is measured by `HttpServerTest` on the loopback rig.
 */
class WebServer(
    private val config: WebConfig,
    private val events: WebServerEvents
) : Closeable {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(config.maxConnections)
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val activeDownloads = AtomicInteger(0)
    private val bytesServed = AtomicLong(0)
    private val startedAt = System.currentTimeMillis()

    var port: Int = 0
        private set

    val totalBytesServed: Long get() = bytesServed.get()
    val activeClients: Int get() = activeDownloads.get()

    fun start(): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(config.bindAddress, config.port))
        server = socket
        port = socket.localPort
        running.set(true)
        val acceptThread = Thread({ acceptLoop(socket) }, "lj-web-accept")
        acceptThread.isDaemon = true
        acceptThread.start()
        val watchdog = Thread({ watchdogLoop() }, "lj-web-watchdog")
        watchdog.isDaemon = true
        watchdog.start()
        events.onLog("web server listening on ${socket.localPort}")
        return port
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                socket.soTimeout = 700
                val client = socket.accept()
                if (!isSameSubnet(client.inetAddress)) {
                    client.close()
                    events.onLog("rejected client outside local subnet: ${client.inetAddress.hostAddress}")
                    continue
                }
                clients.add(client)
                pool.execute { handleClient(client) }
            } catch (_: SocketTimeoutException) {
                checkExpiry()
            } catch (_: Exception) {
                if (running.get()) events.onLog("accept failed")
            }
        }
    }

    private fun checkExpiry() {
        if (config.expiresAt > 0 && System.currentTimeMillis() > config.expiresAt) {
            events.onFinished("expired")
            close()
        }
    }

    private fun watchdogLoop() {
        while (running.get()) {
            Thread.sleep(1000)
            checkExpiry()
        }
    }

    /** Accepts only clients on a private/link-local address in the same /24 or /64 as this host. */
    private fun isSameSubnet(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true
        if (!address.isSiteLocalAddress && !address.isLinkLocalAddress) return false
        val local = config.bindAddress ?: return true
        val a = address.address
        val b = local.address
        if (a.size != b.size) return true
        if (a.size == 4) {
            return (a[0] == b[0] && a[1] == b[1] && a[2] == b[2])
        }
        for (i in 0 until 8) if (a[i] != b[i]) return false
        return true
    }

    private fun handleClient(client: Socket) {
        activeDownloads.incrementAndGet()
        try {
            client.soTimeout = config.idleTimeoutMs
            val input = BufferedInputStream(client.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(client.getOutputStream(), 256 * 1024)
            while (running.get()) {
                val request = try {
                    parseRequest(input)
                } catch (_: SocketTimeoutException) {
                    break
                } catch (_: IOException) {
                    break
                } ?: break
                events.onClientSeen(client.inetAddress.hostAddress ?: "?", request.header("user-agent") ?: "")
                val keepAlive = route(request, output, client, input)
                output.flush()
                if (!keepAlive) break
            }
        } catch (_: Exception) {
            // Client vanished; nothing to report to the user.
        } finally {
            activeDownloads.decrementAndGet()
            clients.remove(client)
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun route(
        request: HttpRequest,
        output: OutputStream,
        client: Socket,
        input: InputStream
    ): Boolean {
        val keepAlive = request.header("connection")?.contains("close", true) != true
        if (config.expiresAt > 0 && System.currentTimeMillis() > config.expiresAt) {
            writeHtml(output, 410, "Link expired", Pages.messagePage("This link expired", "Ask the sender for a new link."))
            return false
        }
        val tokenParam = request.query["t"]
        val pinParam = request.query["pin"]
        if (config.requirePin && config.pin != null && pinParam != config.pin && tokenParam != null) {
            writeHtml(output, 401, "PIN required", Pages.pinPage(request.path, config.pinFailedHint))
            return keepAlive
        }
        return when {
            request.path == "/" || request.path == "/index.html" -> {
                if (request.method != "GET" && request.method != "HEAD") {
                    writeText(output, 405, "text/plain", "method not allowed")
                    true
                } else {
                    val html = if (config.mode == WebMode.SHARE) {
                        Pages.sharePage(config, request)
                    } else {
                        Pages.installPage(config, request, deliverable())
                    }
                    writeHtml(output, 200, config.title, html, head = request.method == "HEAD")
                    true
                }
            }
            request.path == "/favicon.ico" || request.path == "/logo.svg" -> {
                writeBytes(output, 200, "image/svg+xml", Branding.logoSvg.toByteArray(), request.method == "HEAD")
                true
            }
            request.path == "/app.apk" -> {
                val apk = deliverable()
                if (apk == null || !apk.exists()) {
                    writeHtml(output, 404, "Not found", Pages.messagePage("App file missing", "The sender no longer shares the installer."))
                    true
                } else {
                    serveFile(request, output, apk, "LoopJet-${config.versionName}-release.apk", config.title, client)
                    keepAlive
                }
            }
            request.path == "/api/list" -> {
                val json = Json.stringify(
                    Json.obj(
                        "title" to config.title,
                        "mode" to config.mode.name.lowercase(),
                        "files" to config.files.map {
                            Json.obj("id" to it.id, "name" to it.name, "size" to it.size, "mime" to it.mime)
                        },
                        "uploads" to config.allowUpload
                    )
                )
                writeBytes(output, 200, "application/json", json.toByteArray(), request.method == "HEAD")
                true
            }
            request.path.startsWith("/files/") -> {
                val id = request.path.removePrefix("/files/").substringBefore('/').toIntOrNull()
                val file = config.files.firstOrNull { it.id == id }
                if (file == null) {
                    writeHtml(output, 404, "Not found", Pages.messagePage("File not found", "It may have been removed by the sender."))
                } else {
                    serveFile(request, output, file.file, file.name, file.mime, client)
                }
                keepAlive
            }
            request.path == "/api/zip" -> {
                streamZip(request, output, client)
                false
            }
            request.path == "/api/upload" -> {
                if (request.method != "POST") {
                    writeText(output, 405, "text/plain", "POST only")
                } else if (!config.allowUpload) {
                    writeText(output, 403, "text/plain", "uploads disabled")
                } else {
                    receiveUpload(request, input, output)
                    false
                }
                true
            }
            else -> {
                writeHtml(output, 404, "Not found", Pages.messagePage("Page not found", "Check the link the phone showed."))
                true
            }
        }
    }

    private fun deliverable(): File? = config.installPackage

    /** Serves a file with Range support and 64-bit sizes. */
    private fun serveFile(
        request: HttpRequest,
        output: OutputStream,
        file: File,
        downloadName: String,
        mime: String,
        client: Socket
    ) {
        if (!file.isFile) {
            writeText(output, 404, "text/plain", "missing")
            return
        }
        val total = file.length()
        val range = parseRange(request.header("range"), total)
        val start = range?.first ?: 0L
        val end = range?.second ?: (total - 1)
        val length = if (total == 0L) 0L else (end - start + 1)
        val headers = StringBuilder()
        val status = if (range != null) 206 else 200
        headers.append("HTTP/1.1 $status ${if (status == 206) "Partial Content" else "OK"}\r\n")
        headers.append("Content-Type: $mime\r\n")
        headers.append("Content-Length: $length\r\n")
        headers.append("Accept-Ranges: bytes\r\n")
        if (range != null) headers.append("Content-Range: bytes $start-$end/$total\r\n")
        headers.append("Content-Disposition: attachment; filename*=UTF-8''${urlEncode(downloadName)}\r\n")
        headers.append("Cache-Control: no-store\r\n")
        headers.append("X-Content-Type-Options: nosniff\r\n")
        headers.append("Connection: keep-alive\r\n\r\n")
        output.write(headers.toString().toByteArray(Charsets.ISO_8859_1))
        if (request.method == "HEAD" || length == 0L) return

        val sent = copyRange(file, start, length, output, downloadName, total)
        bytesServed.addAndGet(sent)
        runCatching { output.flush() }
    }

    private fun copyRange(
        file: File,
        start: Long,
        length: Long,
        output: OutputStream,
        name: String,
        total: Long
    ): Long {
        var sent = 0L
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val channel = raf.channel
            val buffer = ByteArray(256 * 1024)
            var remaining = length
            var lastReport = 0L
            while (remaining > 0) {
                val n = channel.read(java.nio.ByteBuffer.wrap(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()))
                if (n <= 0) break
                output.write(buffer, 0, n)
                sent += n
                remaining -= n
                if (sent - lastReport > 2 * 1024 * 1024) {
                    lastReport = sent
                    events.onDownload(name, sent, total)
                }
            }
        }
        events.onDownload(name, sent, total)
        return sent
    }

    /** Streams every shared file as one ZIP (stored, so a slow CPU never limits the network). */
    private fun streamZip(request: HttpRequest, output: OutputStream, client: Socket) {
        val files = config.files.filter { it.file.isFile }
        val header = StringBuilder()
        header.append("HTTP/1.1 200 OK\r\n")
        header.append("Content-Type: application/zip\r\n")
        header.append("Content-Disposition: attachment; filename=\"${urlEncode(brandZipName())}\"\r\n")
        header.append("Cache-Control: no-store\r\n")
        header.append("Connection: close\r\n\r\n")
        output.write(header.toString().toByteArray(Charsets.ISO_8859_1))
        if (request.method == "HEAD") return
        ZipOutputStream(java.io.BufferedOutputStream(output, 256 * 1024)).use { zip ->
            zip.setMethod(ZipOutputStream.STORED)
            for (file in files) {
                if (!running.get()) break
                val crc = CRC32()
                var size = 0L
                java.io.RandomAccessFile(file.file, "r").use { raf ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = raf.read(buffer)
                        if (n <= 0) break
                        crc.update(buffer, 0, n)
                        size += n
                    }
                }
                val entry = ZipEntry(file.name)
                entry.method = ZipEntry.STORED
                entry.size = size
                entry.compressedSize = size
                entry.crc = crc.value
                zip.putNextEntry(entry)
                java.io.RandomAccessFile(file.file, "r").use { raf ->
                    val buffer = ByteArray(256 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = raf.read(buffer)
                        if (n <= 0) break
                        zip.write(buffer, 0, n)
                        sent += n
                        if (sent % (4L * 1024 * 1024) < n) events.onDownload(file.name, sent, size)
                    }
                    bytesServed.addAndGet(sent)
                }
                zip.closeEntry()
            }
        }
        output.flush()
    }

    private fun brandZipName(): String = "LoopJet-${Fmt.dayFolder(System.currentTimeMillis())}.zip"

    private fun receiveUpload(request: HttpRequest, input: InputStream, output: OutputStream) {
        val rawName = request.query["name"] ?: "upload.bin"
        val safe = SafePath.sanitize(rawName, "upload.bin") ?: "upload.bin"
        val total = request.header("content-length")?.toLongOrNull() ?: -1L
        val dir = config.uploadDir ?: config.root
        // Receive as <name>.part and rename only when the browser finished cleanly.
        val target = synchronized(this) { SafePath.uniqueFile(dir, safe) }
        val part = File(target.parentFile, target.name + ".part")
        var received = 0L
        try {
            part.parentFile?.mkdirs()
            part.outputStream().buffered(256 * 1024).use { out ->
                if (total >= 0) {
                    val buffer = ByteArray(256 * 1024)
                    var remaining = total
                    while (remaining > 0) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (n < 0) throw IOException("upload truncated")
                        out.write(buffer, 0, n)
                        received += n
                        remaining -= n
                        if (received % (2L * 1024 * 1024) < n) events.onUpload(target.name, received, total)
                    }
                } else {
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        received += n
                        if (received % (2L * 1024 * 1024) < n) events.onUpload(target.name, received, received)
                    }
                }
            }
            if (total >= 0 && received != total) throw IOException("upload incomplete")
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            events.onUpload(target.name, received, received)
            val body = Json.stringify(Json.obj("ok" to true, "name" to target.name, "size" to received))
            writeBytes(output, 200, "application/json", body.toByteArray(), false)
        } catch (e: Exception) {
            part.delete()
            events.onLog("upload failed: ${e.message}")
            writeText(output, 500, "text/plain", "upload failed: ${e.message}")
        }
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null || total <= 0) return null
        if (!header.startsWith("bytes=")) return null
        val spec = header.removePrefix("bytes=").trim()
        if (spec.contains(',')) return null
        if (spec.startsWith("-")) {
            val suffix = spec.removePrefix("-").toLongOrNull() ?: return null
            if (suffix <= 0) return null
            val start = (total - suffix).coerceAtLeast(0)
            return start to total - 1
        }
        val parts = spec.split("-", limit = 2)
        val start = parts[0].toLongOrNull() ?: return null
        if (start >= total) return null
        val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
            (parts[1].toLongOrNull() ?: (total - 1)).coerceAtMost(total - 1)
        } else total - 1
        if (end < start) return null
        return start to end
    }

    private fun writeHtml(output: OutputStream, status: Int, title: String, html: String, head: Boolean = false) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status ${statusText(status)}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Connection: keep-alive\r\n\r\n"
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        if (!head) output.write(bytes)
    }

    private fun writeText(output: OutputStream, status: Int, mime: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status ${statusText(status)}\r\nContent-Type: $mime\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: keep-alive\r\n\r\n"
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
    }

    private fun writeBytes(output: OutputStream, status: Int, mime: String, bytes: ByteArray, head: Boolean) {
        val headers = "HTTP/1.1 $status ${statusText(status)}\r\nContent-Type: $mime\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: max-age=3600\r\n" +
            "Connection: keep-alive\r\n\r\n"
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        if (!head) output.write(bytes)
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        410 -> "Gone"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    override fun close() {
        if (!running.getAndSet(false)) return
        runCatching { server?.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        pool.shutdownNow()
        events.onFinished("stopped")
    }

    // --- request parsing -------------------------------------------------------------------------

    class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val version: String
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private fun parseRequest(input: InputStream): HttpRequest? {
        val line = readLine(input, 8192) ?: return null
        if (line.isBlank()) return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        var target = parts[1]
        val version = parts.getOrElse(2) { "HTTP/1.1" }
        var query = emptyMap<String, String>()
        val qIndex = target.indexOf('?')
        if (qIndex >= 0) {
            query = parseQuery(target.substring(qIndex + 1))
            target = target.substring(0, qIndex)
        }
        val headers = HashMap<String, String>()
        var totalHeaderBytes = 0
        while (true) {
            val h = readLine(input, 8192) ?: break
            if (h.isEmpty()) break
            totalHeaderBytes += h.length
            if (totalHeaderBytes > 64 * 1024) throw IOException("headers too large")
            val comma = h.indexOf(':')
            if (comma <= 0) continue
            headers[h.substring(0, comma).trim().lowercase()] = h.substring(comma + 1).trim()
        }
        val decoded = runCatching { URLDecoder.decode(target, "UTF-8") }.getOrDefault(target)
        return HttpRequest(method, decoded, query, headers, version)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isBlank()) continue
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            val value = if (idx >= 0) pair.substring(idx + 1) else ""
            map[runCatching { URLDecoder.decode(key, "UTF-8") }.getOrDefault(key)] =
                runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }
        return map
    }

    private fun readLine(input: InputStream, limit: Int): String? {
        val sb = StringBuilder(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            if (sb.length > limit) throw IOException("line too long")
        }
    }
}

enum class WebMode { INVITE, SHARE }

data class WebConfig(
    val title: String,
    val versionName: String,
    val mode: WebMode,
    val files: List<SharedFile>,
    val root: File,
    val uploadDir: File? = null,
    val allowUpload: Boolean = false,
    val installPackage: File? = null,
    val port: Int = 0,
    val bindAddress: InetAddress? = null,
    val pin: String? = null,
    val requirePin: Boolean = false,
    val pinFailedHint: String = "Ask the sender for the 4-digit code.",
    val expiresAt: Long = 0,
    val maxConnections: Int = 16,
    val idleTimeoutMs: Int = 30_000
)
