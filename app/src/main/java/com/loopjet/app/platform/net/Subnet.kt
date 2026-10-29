package com.loopjet.app.platform.net

/**
 * "Are these two phones really on the same network?"
 *
 * Wi-Fi mode asks the user to confirm that both phones share a network, but a user cannot see a
 * guest SSID, client isolation, or the fact that one phone is still associated with a hotspot. What
 * can be checked is the thing that actually matters for a direct dial: whether the peer's IPv4
 * address shares this phone's /24 on some interface.
 *
 * Kept free of Android types so it can be unit-tested (see SubnetTest).
 */
object Subnet {
    /** The first three octets of a dotted IPv4 address, or "" when the input is not IPv4. */
    fun prefixOf(address: String): String {
        val parts = address.trim().split('.')
        if (parts.size != 4) return ""
        if (parts.any { it.isEmpty() || it.length > 3 || it.toIntOrNull()?.let { v -> v in 0..255 } != true }) {
            return ""
        }
        return parts.take(3).joinToString(".")
    }

    /**
     * True when [peerAddress] sits on the same /24 as any of [localAddresses].
     *
     * A blank or malformed peer address is never "verified": claiming a match we cannot compute would
     * put a green tick on a connection that is about to time out.
     */
    fun sharesNetwork(localAddresses: List<String>, peerAddress: String): Boolean {
        val peerPrefix = prefixOf(peerAddress)
        if (peerPrefix.isEmpty()) return false
        return localAddresses.any { prefixOf(it) == peerPrefix }
    }
}
