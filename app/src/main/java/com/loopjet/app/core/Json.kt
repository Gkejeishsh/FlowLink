package com.loopjet.app.core

/**
 * Small, dependency-free JSON reader/writer.
 *
 * The transfer engine lives outside the Android framework so that it can be exercised end to end on
 * the JVM (see `TransferLoopbackTest`). `org.json` is not available there, and pulling a JSON library
 * into the hot path would add a dependency nobody needs, so this file implements exactly what the
 * protocol uses: objects, arrays, strings, numbers, booleans and null.
 *
 * Numbers are parsed as [Long] when they are integral (so byte offsets keep full precision) and as
 * [Double] otherwise.
 */
object Json {
    fun parse(text: String): Any? = Parser(text).parseValue().also { _ -> }

    fun stringify(value: Any?): String = StringBuilder(256).also { write(it, value) }.toString()

    fun obj(vararg pairs: Pair<String, Any?>): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>(pairs.size * 2)
        for ((k, v) in pairs) m[k] = v
        return m
    }

    fun parseObject(text: String): Map<String, Any?> {
        val v = parse(text)
        @Suppress("UNCHECKED_CAST")
        return v as? Map<String, Any?> ?: throw JsonException("expected object")
    }

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Float -> sb.append(if (value.isFinite()) value.toString() else "0")
            is Double -> sb.append(if (value.isFinite()) value.toString() else "0")
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    write(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, v)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, value.toList())
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == '\u2028' || c == '\u2029' ->
                    sb.append("\\u").append(((c.code shr 12) and 0xF).toString(16))
                        .append(((c.code shr 8) and 0xF).toString(16))
                        .append(((c.code shr 4) and 0xF).toString(16))
                        .append((c.code and 0xF).toString(16))
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0
        private var depth = 0

        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) throw JsonException("unexpected end of input")
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    if (c == '-' || c.isDigit()) parseNumber()
                    else throw JsonException("unexpected character '$c' at $i")
                }
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonException("bad literal at $i")
            i += word.length
            return value
        }

        private fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++ else break
            }
        }

        private fun parseObject(): Map<String, Any?> {
            enter()
            i++ // {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; depth--; return m }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw JsonException("expected key at $i")
                val k = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw JsonException("expected ':' at $i")
                i++
                m[k] = parseValue()
                skipWs()
                if (i >= s.length) throw JsonException("unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; depth--; return m }
                    else -> throw JsonException("expected ',' or '}' at $i")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            enter()
            i++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; depth--; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (i >= s.length) throw JsonException("unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; depth--; return list }
                    else -> throw JsonException("expected ',' or ']' at $i")
                }
            }
        }

        private fun enter() {
            if (++depth > 64) throw JsonException("nesting too deep")
        }

        private fun parseString(): String {
            i++ // opening quote
            val sb = StringBuilder(32)
            while (true) {
                if (i >= s.length) throw JsonException("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw JsonException("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonException("bad \\u escape")
                                val hex = s.substring(i, i + 4)
                                i += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw JsonException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isDouble = false
            if (i < s.length && s[i] == '.') {
                isDouble = true
                i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isDouble = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val text = s.substring(start, i)
            if (!isDouble) {
                val asLong = text.toLongOrNull()
                if (asLong != null) return asLong
            }
            return text.toDoubleOrNull() ?: throw JsonException("bad number '$text'")
        }
    }
}

class JsonException(message: String) : Exception(message)

// --- typed accessors that keep protocol code readable -------------------------------------------

fun Map<String, Any?>.str(key: String): String? = this[key] as? String

fun Map<String, Any?>.requiredStr(key: String): String =
    str(key) ?: throw JsonException("missing string field '$key'")

fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
    is Long -> v
    is Int -> v.toLong()
    is Double -> v.toLong()
    else -> null
}

fun Map<String, Any?>.requiredLong(key: String): Long =
    long(key) ?: throw JsonException("missing number field '$key'")

fun Map<String, Any?>.int(key: String): Int? = long(key)?.toInt()

fun Map<String, Any?>.bool(key: String, default: Boolean = false): Boolean =
    (this[key] as? Boolean) ?: default

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.arr(key: String): List<Any?>? = this[key] as? List<Any?>

fun Map<String, Any?>.objList(key: String): List<Map<String, Any?>> =
    arr(key).orEmpty().mapNotNull { it as? Map<String, Any?> }

fun List<Any?>.longList(): List<Long> = mapNotNull { (it as? Number)?.toLong() }
