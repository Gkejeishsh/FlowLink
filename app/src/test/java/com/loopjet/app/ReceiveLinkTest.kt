package com.loopjet.app

import com.loopjet.app.core.FileSource
import com.loopjet.app.core.ReceiverEngine
import com.loopjet.app.core.SenderEngine
import com.loopjet.app.core.SoftwareIdentity
import com.loopjet.app.core.SocketConnector
import com.loopjet.app.core.TransferFile
import com.loopjet.app.core.TransferListener
import com.loopjet.app.core.TransferManifest
import com.loopjet.app.core.TransferOffer
import com.loopjet.app.core.ProtocolLimits
import com.loopjet.app.core.TransferProgress
import com.loopjet.app.core.TransferResult
import com.loopjet.app.core.TransferState
import com.loopjet.app.core.FileResult
import com.loopjet.app.core.LocalFileSinkFactory
import com.loopjet.app.core.OfferDecision
import com.loopjet.app.core.InMemoryResumeStore
import com.loopjet.app.platform.net.ConnectCode
import com.loopjet.app.platform.net.ConnectCodes
import com.loopjet.app.platform.net.ConnectDiagnosis
import com.loopjet.app.platform.net.ReceiveCodes
import com.loopjet.app.platform.net.ScannedCode
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract that was broken on real phones: what the receiver puts in its QR must be exactly
 * where it is listening, and dialing what the scanner parsed must reach that receiver.
 *
 * The service-level bug behind the "failed to connect" reports (a second receive session binding a
 * new port, republishing the code, then shutting the service down) cannot be reproduced without
 * Android, but this test fails the moment the address/port plumbing drifts again.
 */
class ReceiveLinkTest {

    private class SilentListener : TransferListener {
        override fun onState(state: TransferState, detail: String) {}
        override fun onProgress(progress: TransferProgress) {}
        override fun onFileDone(result: FileResult) {}
        override fun onLog(message: String) {}
        override fun onOffer(offer: TransferOffer) = OfferDecision(accepted = true)
    }

    @Test
    fun publishedCodePointsAtThePortTheReceiverIsListeningOn() {
        val dir = Files.createTempDirectory("loopjet-recv").toFile()
        val receiver = ReceiverEngine(
            identity = SoftwareIdentity.generate(),
            sinkFactory = LocalFileSinkFactory(dir),
            listener = SilentListener(),
            resumeStore = InMemoryResumeStore(),
            requestedPort = 45310,
            sessionLog = {}
        )
        try {
            receiver.bind()
            val link = ReceiveCodes.lanLink("127.0.0.1", receiver.controlPort, "fp123", "dev-1")
            val parsed = ConnectCodes.parse(link)
            assertTrue("the receiver's own code must parse: $link", parsed is ScannedCode.Direct)
            val direct = parsed as ScannedCode.Direct
            assertEquals("127.0.0.1", direct.code.lanHost)
            assertEquals(receiver.controlPort, direct.code.lanPort)
            assertEquals("fp123", direct.code.fingerprint)

            // The code says a port; something must actually accept there.
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", direct.code.lanPort), 2000) }
        } finally {
            receiver.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aSenderDialingTheScannedLinkTransfers() {
        val dir = Files.createTempDirectory("loopjet-e2e").toFile()
        val source = File(dir, "payload.bin").apply { writeBytes(ByteArray(512 * 1024) { (it % 251).toByte() }) }
        // Two different identities: a peer that presents our own fingerprint is not a peer.
        val identity = SoftwareIdentity.generate()
        val senderIdentity = SoftwareIdentity.generate()
        val done = CountDownLatch(1)
        val receiver = ReceiverEngine(
            identity = identity,
            sinkFactory = LocalFileSinkFactory(File(dir, "in")),
            listener = object : TransferListener by SilentListener() {
                override fun onState(state: TransferState, detail: String) {
                    if (state == TransferState.DONE) done.countDown()
                }
            },
            resumeStore = InMemoryResumeStore(),
            requestedPort = 45410,
            sessionLog = {}
        )
        receiver.bind()
        val link = ReceiveCodes.lanLink("127.0.0.1", receiver.controlPort, "fp", "dev")
        val code = (ConnectCodes.parse(link) as ScannedCode.Direct).code
        val accept = CountDownLatch(1)
        val receiverResult = java.util.concurrent.atomic.AtomicReference<TransferResult?>(null)
        val recvThread = Thread {
            receiverResult.set(receiver.runOnce())
            accept.countDown()
        }
        recvThread.start()

        val file = TransferFile(
            id = 1,
            relPath = "payload.bin",
            size = source.length(),
            mtime = source.lastModified(),
            mime = "application/octet-stream"
        )
        val manifest = TransferManifest(
            sessionId = "scan-link-session",
            files = listOf(file),
            dirs = emptyList(),
            apps = emptyList(),
            totalBytes = source.length(),
            chunkSize = ProtocolLimits.CHUNK_SIZE,
            senderName = "Test sender",
            startedAt = System.currentTimeMillis()
        )
        val connector = SocketConnector { port, timeout ->
            Socket().apply { connect(InetSocketAddress(code.lanHost, port), timeout) }
        }
        val sender = SenderEngine(
            identity = senderIdentity,
            manifest = manifest,
            sourceFor = { FileSource(source) },
            connector = connector,
            controlPort = code.lanPort,
            listener = SilentListener(),
            maxStreams = 2,
            sessionLog = {}
        )
        val result = sender.run()
        accept.await(10, TimeUnit.SECONDS)
        assertTrue("sender failed: ${result.message}", result.ok)
        val receiverOutcome = receiverResult.get()
        assertTrue("receiver failed: ${receiverOutcome?.message}", receiverOutcome?.ok == true)
        assertEquals(1, receiverOutcome?.files?.size)
        val landed = File(dir, "in/payload.bin")
        assertTrue("received file missing at ${landed.absolutePath}", landed.isFile)
        assertEquals(source.length(), landed.length())
        receiver.close()
        dir.deleteRecursively()
    }

    @Test
    fun emptyOrPortlessLinksAreNeverAdvertised() {
        assertEquals("", ReceiveCodes.lanLink("", 43130, "fp", "id"))
        assertEquals("", ReceiveCodes.lanLink("0.0.0.0", 43130, "fp", "id"))
        assertEquals("", ReceiveCodes.lanLink("10.0.0.2", 0, "fp", "id"))
    }

    @Test
    fun diagnosisNamesThePhoneToFixInsteadOfAPort() {
        val answered = ConnectDiagnosis.explain(
            peerName = "Pixel", peerAddress = "192.168.1.5", peerPort = 43130,
            cause = "failed to connect to /192.168.1.5 (port 43130) after 6000ms",
            localAddresses = listOf("192.168.1.9"), beaconAnswered = true
        )
        assertTrue(answered, answered.contains("Pixel") && answered.contains("Show my code"))

        val differentNetwork = ConnectDiagnosis.explain(
            peerName = "Samsung", peerAddress = "192.168.1.5", peerPort = 43130,
            cause = "failed to connect to /192.168.1.5 (port 43130) after 6000ms",
            localAddresses = listOf("10.0.4.7"), beaconAnswered = false
        )
        assertTrue(differentNetwork, differentNetwork.contains("Different networks"))

        val silent = ConnectDiagnosis.explain(
            peerName = "Samsung", peerAddress = "192.168.1.5", peerPort = 43130,
            cause = "failed to connect to /192.168.1.5 (port 43130) after 6000ms",
            localAddresses = listOf("192.168.1.9"), beaconAnswered = false
        )
        assertTrue(silent, silent.contains("192.168.1.5:43130"))

        // A protocol answer is not a connect failure and must be passed through untouched.
        assertEquals("Declined", ConnectDiagnosis.explain("Pixel", "192.168.1.5", 43130,
            "Declined", listOf("192.168.1.9"), false))
    }

    @Test
    fun subnetParsingIsStrict() {
        assertEquals("192.168.1", ConnectDiagnosis.subnet("192.168.1.7"))
        assertEquals(null, ConnectDiagnosis.subnet("fe80::1"))
        assertEquals(null, ConnectDiagnosis.subnet("192.168.1"))
        assertEquals(null, ConnectDiagnosis.subnet("192.168.1.999"))
    }
}
