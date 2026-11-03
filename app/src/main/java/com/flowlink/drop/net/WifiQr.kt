package com.flowlink.drop.net

/**
 * The two QR payloads this app shows.
 *
 * Stage 1 is a standard `WIFI:` code: Android 10+ and iOS 11+ both offer to join the network
 * straight from the camera. Stage 2 is a plain URL.
 */
object WifiQr {

    /** Backslash-escapes the four characters the WIFI: grammar reserves. */
    fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace(":", "\\:")

    fun joinPayload(ssid: String, passphrase: String?): String {
        val s = escape(ssid)
        return if (passphrase.isNullOrBlank()) {
            "WIFI:T:nopass;S:$s;;"
        } else {
            "WIFI:T:WPA;S:$s;P:${escape(passphrase)};;"
        }
    }

    /** Reads back an SSID/passphrase pair; used by tests and by the diagnostics screen. */
    fun parse(payload: String): Pair<String, String>? {
        if (!payload.startsWith("WIFI:", ignoreCase = true)) return null
        var ssid = ""
        var pass = ""
        val body = payload.substring(5)
        var index = 0
        while (index < body.length) {
            val key = body[index]
            if (key != 'S' && key != 'P') {
                // Skip an unknown field up to the next unescaped ';'.
                index = nextField(body, index)
                continue
            }
            if (index + 1 >= body.length || body[index + 1] != ':') {
                index = nextField(body, index)
                continue
            }
            val (value, next) = readValue(body, index + 2)
            if (key == 'S') ssid = value else pass = value
            index = next
        }
        return ssid to pass
    }

    private fun nextField(body: String, from: Int): Int {
        var i = from
        while (i < body.length && body[i] != ';') i++
        while (i < body.length && body[i] == ';') i++
        return if (i == from) from + 1 else i
    }

    private fun readValue(body: String, from: Int): Pair<String, Int> {
        val out = StringBuilder()
        var i = from
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> {
                    out.append(body[i + 1])
                    i += 2
                }
                c == ';' -> return out.toString() to i + 1
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString() to i
    }
}
