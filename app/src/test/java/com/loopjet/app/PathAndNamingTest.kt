package com.loopjet.app

import com.loopjet.app.core.LocalFileSinkFactory
import com.loopjet.app.platform.net.ConnectCode
import com.loopjet.app.platform.net.ConnectCodes
import com.loopjet.app.platform.net.ConnectNetwork
import com.loopjet.app.platform.net.ConnectPlanner
import com.loopjet.app.platform.net.ScannedCode
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two reports in one file:
 *
 *  * "the receiving phone had IMG-1234.jpg.part and other stray files" -- partials must live in a
 *    hidden folder and only complete files may appear with their normal name;
 *  * "hotspot mode still cannot connect" -- two phones on two different networks each have a valid
 *    address, so the LAN path can never work; the code must carry a hotspot too and the sender must
 *    choose by subnet instead of guessing.
 */
class PathAndNamingTest {

    private fun code(lan: String, port: Int = 43130, net: ConnectNetwork? = null) =
        ConnectCode("dev", "fp", "Phone", lan, port, net)

    @Test
    fun sameSubnetTakesTheLanPath() {
        val plan = ConnectPlanner.plan(code("192.168.10.6"), listOf("192.168.10.9", "10.0.0.2"))
        assertEquals(ConnectPlanner.Route.LAN, plan.route)
    }

    @Test
    fun differentNetworkUsesTheHotspotTheSameCodeAdvertises() {
        val hotspot = ConnectNetwork("ap", "Direct-xy", "pw123456", "192.168.49.1", 43130)
        val plan = ConnectPlanner.plan(code("192.168.10.6", net = hotspot), listOf("192.168.1.20"))
        assertEquals(ConnectPlanner.Route.HOTSPOT, plan.route)
        // and the reason says what happened instead of "failed to connect"
        assertTrue(plan.reason, plan.reason.contains("Different Wi-Fi"))
    }

    @Test
    fun lanCodeWithHotspotStillParsesAsDirectSoNothingIsJoinedEarly() {
        val hotspot = ConnectNetwork("ap", "Direct-xy", "pw123456", "192.168.49.1", 43130)
        val parsed = ConnectCodes.parse(code("192.168.10.6", net = hotspot).encode())
        assertTrue(parsed is ScannedCode.Direct)
        val direct = parsed as ScannedCode.Direct
        assertEquals("192.168.10.6", direct.code.lanHost)
        assertEquals("Direct-xy", direct.code.network?.ssid)
    }

    @Test
    fun hotspotOnlyCodeAsksToJoinFirst() {
        val hotspot = ConnectNetwork("ap", "Direct-xy", "pw123456", "192.168.49.1", 43130)
        val parsed = ConnectCodes.parse(code("", port = 0, net = hotspot).encode())
        assertTrue(parsed is ScannedCode.JoinThenConnect)
    }

    @Test
    fun incompleteFilesStayHiddenAndCompleteOnesHaveTheirRealName() {
        val root = Files.createTempDirectory("loopjet-naming").toFile()
        try {
            val factory = LocalFileSinkFactory(root)
            val sink = factory.prepare("Pictures/IMG_1234.jpg", 10, 0L, "image/jpeg")
            sink.writeAt(ByteArray(10) { it.toByte() }, 0, 10, 0)
            // Nothing visible yet, and definitely no "IMG_1234.jpg.part" next to the photo folder.
            assertFalse(File(root, "Pictures/IMG_1234.jpg").exists())
            assertFalse(File(root, "Pictures/IMG_1234.jpg.part").exists())

            val committed = sink.commit(null)
            assertEquals("IMG_1234.jpg", committed.name)
            assertTrue(committed.isFile)
            assertEquals(10L, committed.length())
            assertFalse(File(root, "Pictures/IMG_1234.jpg.part").exists())
            factory.cleanupStaging()
            assertFalse(File(root, LocalFileSinkFactory.STAGING_DIR).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aResumedFileKeepsItsNameInsteadOfBecomingANumberedCopy() {
        val root = Files.createTempDirectory("loopjet-resume").toFile()
        try {
            val first = LocalFileSinkFactory(root)
            val sink = first.prepare("a/big.bin", 8, 0L, "application/octet-stream")
            sink.writeAt(ByteArray(8), 0, 8, 0)
            sink.close() // abandoned mid-transfer: staged, not committed

            val second = LocalFileSinkFactory(root)
            assertTrue(second.hasPartial("a/big.bin"))
            val resumed = second.prepare("a/big.bin", 8, 0L, "application/octet-stream")
            assertEquals("big.bin", resumed.target.name)
        } finally {
            root.deleteRecursively()
        }
    }
}
