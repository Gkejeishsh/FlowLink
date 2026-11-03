package com.flowlink.drop.core

/**
 * File names that arrive from a browser, which is to say: hostile input.
 *
 * A name can be empty, 4 KB of emoji, `../../databases/x`, or `photo.jpg.exe`. Everything that
 * reaches the filesystem goes through [sanitize] first, and nothing ever keeps a path separator.
 */
object Names {

    const val MAX_LENGTH = 120

    private val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')

    /** A single safe path component, never empty, never a dot-file, never longer than [MAX_LENGTH]. */
    fun sanitize(raw: String?, fallback: String = "file"): String {
        val cleaned = (raw ?: "")
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filterNot { it in ILLEGAL }
            .filterNot { it < ' ' }
            .trim()
            .trim('.')
        val named = if (cleaned.isEmpty()) fallback else cleaned
        if (named.length <= MAX_LENGTH) return named
        // Keep the extension: "a very long name….pdf" must stay a PDF.
        val dot = named.lastIndexOf('.')
        if (dot <= 0 || dot < named.length - 12) return named.take(MAX_LENGTH)
        val ext = named.substring(dot)
        return named.take(MAX_LENGTH - ext.length) + ext
    }

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase()
    }

    /** Appends " (1)", " (2)" … before the extension until the predicate says the name is free. */
    fun unique(desired: String, taken: (String) -> Boolean): String {
        if (!taken(desired)) return desired
        val dot = desired.lastIndexOf('.')
        val stem = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var index = 1
        while (index < 10_000) {
            val candidate = "$stem ($index)$ext"
            if (!taken(candidate)) return candidate
            index++
        }
        return "$stem-${System.currentTimeMillis()}$ext"
    }

    private val MIME = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
        "svg" to "image/svg+xml", "avif" to "image/avif",
        "mp4" to "video/mp4", "mov" to "video/quicktime", "mkv" to "video/x-matroska",
        "webm" to "video/webm", "3gp" to "video/3gpp", "avi" to "video/x-msvideo",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "wav" to "audio/wav", "ogg" to "audio/ogg",
        "flac" to "audio/flac", "aac" to "audio/aac", "opus" to "audio/opus",
        "pdf" to "application/pdf", "zip" to "application/zip", "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed", "tar" to "application/x-tar",
        "gz" to "application/gzip", "apk" to "application/vnd.android.package-archive",
        "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv", "json" to "application/json",
        "xml" to "application/xml", "html" to "text/html", "htm" to "text/html",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "epub" to "application/epub+zip", "ttf" to "font/ttf", "otf" to "font/otf"
    )

    /**
     * The type is decided by extension, never by what the browser claims: a page can send any
     * `Content-Type` it likes, and routing a file into the gallery on that claim is how a
     * `.apk` ends up indexed as a photo.
     */
    fun mimeOf(name: String): String = MIME[extension(name)] ?: "application/octet-stream"

    fun isImage(mime: String): Boolean = mime.startsWith("image/")

    fun isVideo(mime: String): Boolean = mime.startsWith("video/")

    fun isAudio(mime: String): Boolean = mime.startsWith("audio/")

    /** Percent-decodes a query-string value; invalid escapes are kept verbatim rather than dropped. */
    fun urlDecode(value: String): String {
        if (!value.contains('%') && !value.contains('+')) return value
        val out = StringBuilder(value.length)
        val bytes = java.io.ByteArrayOutputStream()
        fun flush() {
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.reset()
            }
        }
        var index = 0
        while (index < value.length) {
            val c = value[index]
            when {
                c == '+' -> { flush(); out.append(' '); index++ }
                c == '%' && index + 2 < value.length -> {
                    val hex = value.substring(index + 1, index + 3)
                    val parsed = hex.toIntOrNull(16)
                    if (parsed == null) {
                        flush(); out.append(c); index++
                    } else {
                        bytes.write(parsed)
                        index += 3
                    }
                }
                else -> { flush(); out.append(c); index++ }
            }
        }
        flush()
        return out.toString()
    }

    fun urlEncode(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            val safe = (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) ||
                (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code || c == '.'.code ||
                c == '~'.code
            if (safe) out.append(c.toChar()) else out.append('%').append(String.format("%02X", c))
        }
        return out.toString()
    }
}
