package com.loopjet.app

import com.loopjet.app.platform.net.ConnectCode
import com.loopjet.app.platform.net.ConnectCodes
import com.loopjet.app.platform.net.ConnectNetwork
import com.loopjet.app.platform.net.ScannedCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The code is the entire pairing story, and it is the only part of P3/P4 that can be exercised
 * without two phones. Everything here is a case a real user produced in testing: a hotspot code, a
 * plain LAN code, a code photographed off a wrapped screen, and the Wi-Fi QR from Android's own
 * share sheet.
 */
class ConnectCodeTest {

    @Test
    fun lanCodeSurvivesRoundTrip() {
        val code = ConnectCode("dev-1", "abc123", "Pixel", "192.168.1.7", 45999)
        val back = ConnectCode.decode(code.encode())
        assertEquals(code, back)
        assertEquals("Same Wi-Fi · 192.168.1.7", back!!.describe())
    }

    @Test
    fun hotspotCodeCarriesNetworkAndEndsUpAsJoinThenConnect() {
        val code = ConnectCode(
            "dev-2", "ffee", "Samsung", lanHost = "", lanPort = 0,
            network = ConnectNetwork("ap", "Direct-xy", "pass1234", "192.168.49.1", 45999)
        )
        val parsed = ConnectCodes.parse(code.encode())
        assertTrue(parsed is ScannedCode.JoinThenConnect)
        val join = parsed as ScannedCode.JoinThenConnect
        assertEquals("Direct-xy", join.network.ssid)
        assertEquals("pass1234", join.network.psk)
        assertEquals("192.168.49.1", join.network.host)
        assertTrue(code.describe().startsWith("No shared Wi-Fi needed"))
    }

    @Test
    fun v1LinkStillParses() {
        val parsed = ConnectCodes.parse("loopjet://10.0.0.5:45999#finger#print#device-9")
        assertTrue(parsed is ScannedCode.Direct)
        val c = (parsed as ScannedCode.Direct).code
        assertEquals("10.0.0.5", c.lanHost)
        assertEquals(45999, c.lanPort)
        assertEquals("finger", c.fingerprint)
        assertEquals("device-9", c.deviceId)
    }

    @Test
    fun cameraSplittingThePayloadAcrossLinesIsRecovered() {
        val code = ConnectCode("dev-3", "ff", "Phone", "10.1.1.1", 45999)
        val encoded = code.encode()
        val mangled = encoded.take(12) + "\n" + encoded.drop(12)
        assertEquals(code, ConnectCode.decode(mangled))
        // Upper case is a real scanner behaviour (some apps normalise text). Only the prefix may be
        // compared case-insensitively; the payload itself is case-sensitive base64.
        val upperPrefix = "LOOPJET2:" + encoded.removePrefix("loopjet2:")
        assertTrue(ConnectCodes.parse(upperPrefix) is ScannedCode.Direct)
    }

    @Test
    fun wifiShareSheetCodeIsReadWithEscapes() {
        val parsed = ConnectCodes.parse("WIFI:T:WPA;S:Cafe\\;Guest;P:p\\,w1;;")
        assertTrue(parsed is ScannedCode.WifiOnly)
        val wifi = parsed as ScannedCode.WifiOnly
        assertEquals("Cafe;Guest", wifi.ssid)
        assertEquals("p,w1", wifi.passphrase)
    }

    @Test
    fun junkNeverThrowsAndNeverClaimsToBeUsable() {
        listOf("", "   ", "hello", "loopjet2:", "loopjet2:!!!!", "http://x", "1234567")
            .forEach { input ->
                val parsed = ConnectCodes.parse(input)
                // NeedsUpdate is allowed: a `loopjet2:` prefix with an unreadable payload is most
                // likely a code from a newer build (or a truncated photo), and both messages are
                // honest. What must never happen is a Direct/Join result the user cannot act on.
                assertTrue(
                    "unexpected $parsed for '$input'",
                    parsed is ScannedCode.Unknown ||
                        parsed is ScannedCode.WebLink ||
                        parsed is ScannedCode.NeedsUpdate
                )
            }
        assertNull(ConnectCode.decode("loopjet://1.2.3.4:1#f#d"))
    }

    @Test
    fun sixDigitsIsAPairingCodeAndHttpIsALink() {
        assertTrue(ConnectCodes.parse("123456") is ScannedCode.PairingCode)
        assertTrue(ConnectCodes.parse("https://loopjet.example/x") is ScannedCode.WebLink)
    }
}
