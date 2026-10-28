package com.loopjet.app.platform.net

import com.loopjet.app.core.Json
import com.loopjet.app.core.int
import com.loopjet.app.core.obj
import com.loopjet.app.core.str
import java.util.Base64

/**
 * The one code that carries everything needed to connect.
 *
 * Two formats exist and both stay readable:
 *
 *  * `loopjet://host:port#fingerprint#device` — v1, LAN only. Kept because it is what a phone that
 *    cannot make a network can offer, and because older builds understand it.
 *  * `loopjet2:<base64url json>` — v2, LAN **and** an optional network (hotspot or Wi-Fi Direct group)
 *    the receiver is hosting right now.
 *
 * A v1 phone that scans a v2 code says so explicitly instead of reporting "not a LoopJet code" — that
 * was the flaw in the first draft of this design (see CONNECTION-PLAN.md §0).
 *
 * Deliberately small: the JSON keys are two letters each so a code carrying a network stays around
 * 200 bytes, which keeps it scannable from a phone held at a normal distance.
 */
data class ConnectNetwork(
    /** "ap" for a local-only hotspot, "p2p" for a Wi-Fi Direct group, "wifi" for a user's own hotspot. */
    val kind: String,
    val ssid: String,
    val psk: String,
    val host: String,
    val port: Int
)

data class ConnectCode(
    val deviceId: String,
    val fingerprint: String,
    val name: String,
    val lanHost: String = "",
    val lanPort: Int = 0,
    val network: ConnectNetwork? = null
) {
    /** True when there is at least one address the other phone can actually dial. */
    val usable: Boolean get() = lanHost.isNotBlank() || network != null

    fun encode(): String {
        val map = LinkedHashMap<String, Any?>()
        map["v"] = 2
        map["id"] = deviceId
        map["fp"] = fingerprint
        map["n"] = name
        if (lanHost.isNotBlank() && lanPort > 0) {
            map["l"] = Json.obj("h" to lanHost, "p" to lanPort)
        }
        network?.let { net ->
            map["net"] = Json.obj(
                "k" to net.kind,
                "s" to net.ssid,
                "w" to net.psk,
                "h" to net.host,
                "p" to net.port
            )
        }
        // java.util.Base64 (API 26+, matching minSdk) rather than android.util.Base64: identical
        // output, but it also runs in plain JVM unit tests, so the code format is covered by CI.
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Json.stringify(map).toByteArray(Charsets.UTF_8))
        return PREFIX + payload
    }

    /** What the Receive screen says under the code, so the path is never a mystery. */
    fun describe(): String = when {
        network != null && network.kind == "ap" -> "No shared Wi-Fi needed · this phone made \"${network.ssid}\""
        network != null && network.kind == "p2p" -> "No shared Wi-Fi needed · Wi-Fi Direct \"${network.ssid}\""
        network != null -> "Joining your hotspot \"${network.ssid}\""
        lanHost.isNotBlank() -> "Same Wi-Fi · $lanHost"
        else -> "Preparing the connection…"
    }

    companion object {
        const val PREFIX = "loopjet2:"

        /**
         * Tolerates what a camera app may do to the payload: line breaks from a wrapped display,
         * missing padding, and the standard (+/) alphabet. Strict rejection here means "that is not
         * a LoopJet code", which sends the user hunting for a problem that does not exist.
         */
        private fun decodePayload(raw: String): ByteArray {
            val cleaned = raw.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/')
            val padded = when (cleaned.length % 4) {
                2 -> "$cleaned=="
                3 -> "$cleaned="
                else -> cleaned
            }
            return Base64.getMimeDecoder().decode(padded)
        }

        fun decode(text: String): ConnectCode? {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return null
            val raw = trimmed.substring(PREFIX.length)
            val json = runCatching { String(decodePayload(raw), Charsets.UTF_8) }.getOrNull()
                ?: return null
            val map = runCatching { Json.parseObject(json) }.getOrNull() ?: return null
            if ((map.int("v") ?: 0) < 2) return null
            val lan = map.obj("l")
            val net = map.obj("net")
            return ConnectCode(
                deviceId = map.str("id").orEmpty(),
                fingerprint = map.str("fp").orEmpty(),
                name = map.str("n").orEmpty().ifBlank { "LoopJet phone" },
                lanHost = lan?.str("h").orEmpty(),
                lanPort = lan?.int("p") ?: 0,
                network = net?.let {
                    val ssid = it.str("s").orEmpty()
                    if (ssid.isBlank()) return@let null
                    ConnectNetwork(
                        kind = it.str("k").orEmpty().ifBlank { "ap" },
                        ssid = ssid,
                        psk = it.str("w").orEmpty(),
                        host = it.str("h").orEmpty(),
                        port = it.int("p") ?: 0
                    )
                }
            )
        }
    }
}

/** Everything a scanned code can be. */
sealed class ScannedCode {
    /** v1 `loopjet://…` or the LAN half of a v2 code. */
    data class Direct(val code: ConnectCode) : ScannedCode()

    /** A v2 code that also carries a network to join first. */
    data class JoinThenConnect(val code: ConnectCode, val network: ConnectNetwork) : ScannedCode()

    /** A generic `WIFI:` code (system camera format) — we can join it, nothing more. */
    data class WifiOnly(val ssid: String, val passphrase: String) : ScannedCode()

    /** Six digits shown on the other phone. */
    data class PairingCode(val digits: String) : ScannedCode()

    /** `http(s)://` — the Web Share page. */
    data class WebLink(val url: String) : ScannedCode()

    /** A LoopJet code from a newer build. */
    data object NeedsUpdate : ScannedCode()

    data object Unknown : ScannedCode()
}

/**
 * The one place a v1 receive link is built. The Receive screen's QR, the "copy link" action and the
 * tests all go through here so the advertised host and port cannot drift from the listening socket.
 */
object ReceiveCodes {
    fun lanLink(host: String, port: Int, fingerprint: String, deviceId: String): String =
        if (host.isBlank() || port <= 0) "" else "loopjet://$host:$port#$fingerprint#$deviceId"
}

object ConnectCodes {

    /** Parses anything the scanner can see. Never throws. */
    fun parse(text: String): ScannedCode {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ScannedCode.Unknown

        if (trimmed.startsWith("loopjet://", ignoreCase = true)) {
            val body = trimmed.removePrefix("loopjet://")
            val hostPort = body.substringBefore('#')
            if (!hostPort.contains(':')) return ScannedCode.Unknown
            val host = hostPort.substringBefore(':').trim()
            val port = hostPort.substringAfter(':').substringBefore('/').toIntOrNull() ?: return ScannedCode.Unknown
            if (host.isBlank() || port !in 1..65535) return ScannedCode.Unknown
            // v1 carries host, port and fingerprint only -- the same three fields the session parser
            // reads. Anything after a second '#' is a display tail, not an identity: treating it as
            // one handed the handshake a device id with the fingerprint glued on.
            val rest = if (body.contains('#')) body.substringAfter('#') else ""
            return ScannedCode.Direct(
                ConnectCode(
                    deviceId = "",
                    fingerprint = rest.substringBefore('#').trim(),
                    name = "LoopJet phone",
                    lanHost = host,
                    lanPort = port
                )
            )
        }

        if (trimmed.startsWith(ConnectCode.PREFIX, ignoreCase = true)) {
            val code = ConnectCode.decode(trimmed) ?: return ScannedCode.NeedsUpdate
            val net = code.network
            return when {
                net != null -> ScannedCode.JoinThenConnect(code, net)
                code.lanHost.isNotBlank() -> ScannedCode.Direct(code)
                else -> ScannedCode.Unknown
            }
        }

        if (trimmed.startsWith("WIFI:", ignoreCase = true)) {
            val fields = parseWifiPayload(trimmed)
            val ssid = fields["S"] ?: return ScannedCode.Unknown
            return ScannedCode.WifiOnly(ssid, fields["P"].orEmpty())
        }

        if (trimmed.length == 6 && trimmed.all { it.isDigit() }) return ScannedCode.PairingCode(trimmed)

        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            return ScannedCode.WebLink(trimmed)
        }
        return ScannedCode.Unknown
    }

    /**
     * Minimal `WIFI:T:WPA;S:name;P:pass;;` reader. Handles the escapes the generator writes
     * (`\\`, `\;`, `\,`, `\:`) so an SSID with a semicolon survives.
     */
    private fun parseWifiPayload(text: String): Map<String, String> {
        val body = text.removePrefix("WIFI:")
        val out = HashMap<String, String>()
        val key = StringBuilder()
        val value = StringBuilder()
        var inKey = true
        var escaped = false
        var index = 0
        while (index < body.length) {
            val c = body[index]
            when {
                escaped -> { value.append(c); escaped = false }
                c == '\\' -> escaped = true
                c == ':' && inKey -> inKey = false
                c == ';' -> {
                    if (key.isNotEmpty()) out[key.toString()] = value.toString()
                    key.clear(); value.clear(); inKey = true
                }
                inKey -> key.append(c)
                else -> value.append(c)
            }
            index++
        }
        if (key.isNotEmpty() && value.isNotEmpty()) out[key.toString()] = value.toString()
        return out
    }
}

/**
 * How good a connection would be, from facts we already have before connecting.
 *
 * This is deliberately a *prediction*: station Wi-Fi, a local-only hotspot and a p2p group share one
 * radio, so the candidates cannot be benchmarked against each other without tearing one down (see
 * CONNECTION-PLAN.md §5). The list is ordered by what is cheap and likely fastest first, and the
 * chosen path is verified with a single beacon round-trip before the session starts.
 */
object PathScores {
    const val LAN = "Same Wi-Fi"
    const val AP = "Local hotspot"
    const val P2P = "Wi-Fi Direct"
    const val UNKNOWN = "Unknown path"

    data class Candidate(val kind: String, val label: String, val score: Int, val reason: String)

    fun candidates(code: ConnectCode, supportsP2p: Boolean): List<Candidate> {
        val list = ArrayList<Candidate>(3)
        if (code.lanHost.isNotBlank()) {
            list.add(
                Candidate(
                    kind = "lan",
                    label = LAN,
                    // An existing network wins: no state change, no consent sheet, usually 5 or 6 GHz.
                    score = 100,
                    reason = "already on the same network"
                )
            )
        }
        code.network?.let { net ->
            val (score, label) = when (net.kind) {
                "p2p" -> 70 to P2P
                "wifi" -> 65 to "Your hotspot"
                else -> 75 to AP
            }
            list.add(
                Candidate(
                    kind = net.kind,
                    label = label,
                    score = if (net.kind == "p2p" && !supportsP2p) 0 else score,
                    reason = "the receiver is hosting \"${net.ssid}\""
                )
            )
        }
        return list.sortedByDescending { it.score }
    }

    fun labelFor(kind: String): String = when (kind) {
        "lan" -> LAN
        "p2p" -> P2P
        "ap", "wifi" -> AP
        else -> UNKNOWN
    }
}
