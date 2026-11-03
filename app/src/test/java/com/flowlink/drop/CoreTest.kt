package com.flowlink.drop

import com.flowlink.drop.core.Fmt
import com.flowlink.drop.core.Names
import com.flowlink.drop.net.WifiQr
import com.flowlink.drop.web.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiQrTest {

    @Test
    fun plainPayloadRoundTrips() {
        val payload = WifiQr.joinPayload("AndroidShare_1234", "h7Kd2mQp")
        assertEquals("WIFI:T:WPA;S:AndroidShare_1234;P:h7Kd2mQp;;", payload)
        val parsed = WifiQr.parse(payload)!!
        assertEquals("AndroidShare_1234", parsed.first)
        assertEquals("h7Kd2mQp", parsed.second)
    }

    @Test
    fun reservedCharactersAreEscapedAndComeBack() {
        val ssid = "Cafe;Bar,Upstairs:2"
        val pass = "p;a,s:s\\word"
        val payload = WifiQr.joinPayload(ssid, pass)
        assertTrue(payload.contains("\\;"))
        val parsed = WifiQr.parse(payload)!!
        assertEquals(ssid, parsed.first)
        assertEquals(pass, parsed.second)
    }

    @Test
    fun openNetworkHasNoPasswordField() {
        val payload = WifiQr.joinPayload("FreeWiFi", null)
        assertTrue(payload.contains("T:nopass"))
        assertFalse(payload.contains("P:"))
    }

    @Test
    fun garbageIsNotParsed() {
        assertNull(WifiQr.parse("https://example.com"))
    }
}

class NamesTest {

    @Test
    fun traversalAndSeparatorsCannotEscape() {
        assertEquals("passwd", Names.sanitize("../../etc/passwd"))
        assertEquals("x", Names.sanitize("..\\..\\x"))
        assertEquals("file", Names.sanitize("   "))
        assertEquals("file", Names.sanitize(null))
    }

    @Test
    fun longNamesKeepTheirExtension() {
        val long = "x".repeat(300) + ".pdf"
        val safe = Names.sanitize(long)
        assertTrue(safe.length <= Names.MAX_LENGTH)
        assertTrue(safe.endsWith(".pdf"))
    }

    @Test
    fun duplicatesGetASuffixBeforeTheExtension() {
        val taken = setOf("report.pdf", "report (1).pdf")
        assertEquals("report (2).pdf", Names.unique("report.pdf", taken::contains))
        assertEquals("fresh.txt", Names.unique("fresh.txt", taken::contains))
    }

    @Test
    fun mimeComesFromTheExtensionNotFromTheClaim() {
        assertEquals("application/vnd.android.package-archive", Names.mimeOf("app.apk"))
        assertEquals("image/jpeg", Names.mimeOf("photo.JPG"))
        assertEquals("application/octet-stream", Names.mimeOf("mystery"))
    }

    @Test
    fun urlEncodingRoundTripsUnicode() {
        val name = "تقرير 2026 · résumé.pdf"
        assertEquals(name, Names.urlDecode(Names.urlEncode(name)))
        assertEquals("a b+c", Names.urlDecode("a+b%2Bc"))
    }

    @Test
    fun formattingStaysReadable() {
        assertEquals("512 B", Fmt.bytes(512))
        assertEquals("1.0 KB", Fmt.bytes(1024))
        assertEquals("50%", Fmt.percent(50, 100).toString() + "%")
        assertEquals("0%", Fmt.percent(0, 0).toString() + "%")
    }
}

class PageTest {

    @Test
    fun thePageAsksForNothingOutsideThePhone() {
        val html = Page.render("Talal's phone", 4L * 1024 * 1024 * 1024, "1.0.0")
        assertFalse("page must not reach the internet", html.contains("http://"))
        assertFalse("page must not reach the internet", html.contains("https://"))
        assertFalse(html.contains("cdn."))
        assertTrue(html.contains("Talal&#39;s phone".replace("&#39;", "'")) || html.contains("Talal's phone"))
    }

    @Test
    fun thePageWorksWithoutJavaScript() {
        val html = Page.render("Phone", 1024, "1")
        assertTrue(html.contains("multipart/form-data"))
        assertTrue(html.contains("action=\"/upload\""))
    }

    @Test
    fun phoneNameIsEscaped() {
        val html = Page.render("<script>x</script>", 1024, "1")
        assertFalse(html.contains("<script>x</script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }
}
