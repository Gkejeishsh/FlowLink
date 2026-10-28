package com.loopjet.app.platform.net

/**
 * Turns "failed to connect to /192.168.1.5 (port 43130)" into something a person can act on.
 *
 * The raw socket message names an IP and a port; neither tells the user which of the two phones is
 * the problem or what to do next. This is only ever called *after* a session has already failed, so
 * it costs nothing on the transfer path.
 */
object ConnectDiagnosis {

    /** Socket-level failures worth explaining. Anything else is a real protocol answer. */
    fun isConnectFailure(cause: String): Boolean {
        val c = cause.lowercase()
        return listOf(
            "failed to connect", "econnrefused", "connection refused", "ehostunreach",
            "network is unreachable", "no route to host", "etimedout", "timed out",
            "connect timed out", "socket closed"
        ).any { c.contains(it) }
    }

    /**
     * @param beaconAnswered whether the peer's discovery beacon replied (it lives on its own port),
     *        which separates "the phone is gone" from "the phone is up but its receiver is not".
     */
    fun explain(
        peerName: String,
        peerAddress: String,
        peerPort: Int,
        cause: String,
        localAddresses: List<String>,
        beaconAnswered: Boolean
    ): String {
        val name = peerName.ifBlank { "The other phone" }
        if (!isConnectFailure(cause)) return cause
        if (beaconAnswered) {
            // The beacon answering means the phone is alive and reachable, so the receiver simply
            // is not listening on the port the code named.
            return "$name is running, but nothing is receiving on port $peerPort. " +
                "Tap \"Show my code\" there again and rescan."
        }
        val peerSubnet = subnet(peerAddress)
        val mySubnet = localAddresses.mapNotNull { subnet(it) }.firstOrNull()
        if (peerSubnet != null && mySubnet != null && peerSubnet != mySubnet) {
            return "Different networks: this phone is on $mySubnet.x and $name is on $peerSubnet.x. " +
                "Join the same Wi-Fi, or use \"Show my code\" there (it starts a hotspot when there is no shared Wi-Fi)."
        }
        return "No answer from $peerAddress:$peerPort. $name may have left the Receive screen — " +
            "open Receive there again and rescan, keeping both phones on the same Wi-Fi."
    }

    /** "192.168.1.7" -> "192.168.1". Null for anything that is not a plain IPv4 address. */
    fun subnet(address: String): String? {
        val parts = address.trim().split('.')
        if (parts.size != 4) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it < 0 || it > 255 }) return null
        return "${numbers[0]}.${numbers[1]}.${numbers[2]}"
    }
}
