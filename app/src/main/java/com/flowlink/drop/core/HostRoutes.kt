package com.flowlink.drop.core

/**
 * The small, testable half of the hotspot decision: which route to try first and what makes a
 * network name and password legal.
 *
 * Kept free of Android types on purpose — "the password in Settings was six characters, so the group
 * was never created" is the kind of bug that should be caught by a test, not by a phone.
 */
object HostRoutes {

    const val DIRECT = "direct"
    const val OS = "os"
    const val MANUAL = "manual"

    /** Wi-Fi Direct first when it is available and wanted: only it gives a stable network name. */
    fun order(preferDirect: Boolean, supportsDirect: Boolean): List<String> =
        if (preferDirect && supportsDirect) listOf(DIRECT, OS, MANUAL) else listOf(OS, MANUAL)

    fun label(route: String): String = when (route) {
        DIRECT -> "Wi-Fi Direct"
        OS -> "Phone hotspot"
        MANUAL -> "Your own hotspot"
        else -> ""
    }

    /** Wi-Fi Direct accepts 1–32 printable ASCII characters; anything else is replaced. */
    fun name(seed: String?): String {
        val cleaned = (seed ?: "").filter { it.code in 32..126 }.trim()
        return if (cleaned.length in 1..32) cleaned else "FlowLink-${(100..999).random()}"
    }

    /** WPA2 needs 8–63 printable characters. A short password here used to mean no hotspot at all. */
    fun passphrase(seed: String?): String {
        val cleaned = (seed ?: "").filter { it.code in 32..126 }
        if (cleaned.length in 8..63) return cleaned
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        return (1..10).map { alphabet.random() }.joinToString("")
    }

    fun passphraseValid(candidate: String): Boolean = candidate.length in 8..63
}
