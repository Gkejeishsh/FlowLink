package com.loopjet.app.platform

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.loopjet.app.MainActivity
import com.loopjet.app.R
import com.loopjet.app.core.BeaconInfo
import com.loopjet.app.core.BeaconServer
import com.loopjet.app.core.Direction
import com.loopjet.app.core.FileKind
import com.loopjet.app.core.FileResult
import com.loopjet.app.core.FileSink
import com.loopjet.app.core.FileSinkFactory
import com.loopjet.app.core.FileSource
import com.loopjet.app.core.Fmt
import com.loopjet.app.core.LocalFileSinkFactory
import com.loopjet.app.core.OfferDecision
import com.loopjet.app.core.ProtocolLimits
import com.loopjet.app.core.RandomAccessSource
import com.loopjet.app.core.ReceiverEngine
import com.loopjet.app.core.SenderEngine
import com.loopjet.app.core.SocketConnector
import com.loopjet.app.core.SpaceGuard
import com.loopjet.app.core.SafePath
import com.loopjet.app.core.TransferFile
import com.loopjet.app.core.TransferListener
import com.loopjet.app.core.TransferManifest
import com.loopjet.app.core.TransferOffer
import com.loopjet.app.core.TransferProgress
import com.loopjet.app.core.TransferResult
import com.loopjet.app.core.TransferState
import com.loopjet.app.platform.net.DirectLink
import com.loopjet.app.platform.net.DiscoveredPeer
import com.loopjet.app.platform.net.NetworkTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class SessionMode { IDLE, SEND, RECEIVE }

data class IncomingOfferUi(
    val peerName: String,
    val sas: String,
    val files: Int,
    val bytes: Long,
    val trusted: Boolean
)

/** An app whose base APK and every split arrived, ready for one PackageInstaller session. */
data class ReceivedAppUi(
    val packageName: String,
    val label: String,
    val versionName: String,
    val files: List<String>,
    val installed: Boolean = false,
    val message: String = ""
)

data class SessionUi(
    val mode: SessionMode = SessionMode.IDLE,
    val state: TransferState = TransferState.IDLE,
    val peerName: String = "",
    val sas: String = "",
    val totalBytes: Long = 0,
    val doneBytes: Long = 0,
    val instantBps: Long = 0,
    val averageBps: Long = 0,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val etaMs: Long = 0,
    val pathLabel: String = "",
    val message: String = "",
    val speedHistory: List<Long> = emptyList(),
    val fileResults: List<FileResult> = emptyList(),
    val linkLabel: String = "",
    val receiveReady: Boolean = false,
    val receiveQr: String = "",
    val offer: IncomingOfferUi? = null,
    val deviceName: String = "",
    val receivedApps: List<ReceivedAppUi> = emptyList(),
    /** Six digits shown on the Receive screen for manual pairing. */
    val receiveCode: String = ""
) {
    val active: Boolean get() = mode != SessionMode.IDLE
}

/** Single source of truth for the UI; written by [TransferService], read by Compose. */
object TransferHub {
    private val _session = MutableStateFlow(SessionUi())
    val session: StateFlow<SessionUi> = _session

    private val pending = AtomicReference<CompletableDeferred<OfferDecision>?>(null)
    private val earlyAnswer = AtomicReference<OfferDecision?>(null)

    fun update(block: (SessionUi) -> SessionUi) {
        _session.value = block(_session.value)
    }

    fun reset() {
        _session.value = SessionUi()
    }

    fun offer(offer: IncomingOfferUi) {
        update { it.copy(offer = offer) }
    }

    fun answerOffer(accepted: Boolean, trust: Boolean) {
        update { it.copy(offer = null) }
        val decision = OfferDecision(accepted, trust)
        val waiter = pending.getAndSet(null)
        if (waiter != null) {
            waiter.complete(decision)
        } else {
            // The dialog can be answered a frame before the engine starts waiting; keep it.
            earlyAnswer.set(decision)
        }
    }

    fun awaitDecision(timeoutMs: Long): OfferDecision {
        earlyAnswer.getAndSet(null)?.let { return it }
        val deferred = CompletableDeferred<OfferDecision>()
        pending.set(deferred)
        earlyAnswer.getAndSet(null)?.let {
            pending.compareAndSet(deferred, null)
            return it
        }
        val result = runBlocking { withTimeoutOrNull(timeoutMs) { deferred.await() } }
        pending.compareAndSet(deferred, null)
        return result ?: OfferDecision(accepted = false, reason = "No answer")
    }
}

/** Items queued on the Send screen. */
data class SendItem(
    val key: String,
    val displayName: String,
    val relativePath: String,
    val size: Long,
    val mtime: Long,
    val mime: String,
    val uri: String? = null,
    val filePath: String? = null,
    val appPackage: String? = null,
    val appLabel: String? = null,
    val appVersionName: String? = null,
    val appVersionCode: Long = 0,
    val splitName: String? = null,
    val isBaseApk: Boolean = false,
    val kind: Int = FileKind.FILE,
    val appMinSdk: Int = 0,
    val appTargetSdk: Int = 0,
    val appAbis: List<String> = emptyList()
)

object SendQueue {
    private val _items = MutableStateFlow<List<SendItem>>(emptyList())
    val items: StateFlow<List<SendItem>> = _items

    val snapshot: List<SendItem> get() = _items.value
    val totalBytes: Long get() = _items.value.sumOf { it.size }

    fun add(items: List<SendItem>) {
        val existing = _items.value.toMutableList()
        val keys = existing.map { it.key }.toMutableSet()
        for (item in items) if (keys.add(item.key)) existing.add(item)
        _items.value = existing
    }

    fun remove(key: String) {
        _items.value = _items.value.filterNot { it.key == key }
    }

    fun clear() {
        _items.value = emptyList()
    }
}

/**
 * Foreground service that owns every long-running operation: sending, receiving, and the invitation
 * / web-share servers. It keeps a wake lock and a low-latency Wi-Fi lock, survives screen-off and
 * swipe-away, and implements the Android 15 timeout contract (finish the chunk, persist, stop).
 */
class TransferService : Service() {

    private val running = AtomicBoolean(false)
    private val worker = AtomicReference<Thread?>(null)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var receiver: ReceiverEngine? = null
    private var beacon: BeaconServer? = null
    private var sender: SenderEngine? = null
    private var paused = false
    @Volatile private var receiveCode: String = ""
    @Volatile private var p2pGroupHosted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        when (action) {
            ACTION_SEND -> {
                promote("Sending", "Preparing")
                val peer = PendingPeer.peer
                if (peer == null) {
                    stopSelfSafely("No device selected")
                    return START_NOT_STICKY
                }
                startWorker { runSend(peer) }
            }
            ACTION_RECEIVE -> {
                promote("Ready to receive", "Waiting for a sender")
                startReceiver()
            }
            ACTION_PAUSE -> {
                val engine = sender
                if (engine == null) {
                    stopSelfSafely("Nothing to pause")
                    return START_NOT_STICKY
                }
                paused = true
                engine.requestPause()
                updateNotification()
            }
            ACTION_RESUME -> {
                val engine = sender
                if (engine == null) {
                    stopSelfSafely("Nothing to resume")
                    return START_NOT_STICKY
                }
                paused = false
                engine.requestResume()
                updateNotification()
            }
            ACTION_STOP -> {
                cancelSession()
                stopSelfSafely("Stopped")
            }
            else -> {
                stopSelfSafely("Unknown action")
            }
        }
        return START_STICKY
    }

    private fun startWorker(block: () -> Unit) {
        if (!running.compareAndSet(false, true)) {
            // A session is already running (send while receiving, or a double tap). Say so instead of
            // silently dropping the request, which used to look like a dead button.
            TransferHub.update {
                it.copy(message = "Another transfer is already running — finish or cancel it first")
            }
            MediaIndex.notifyFinished(this, false, "Another transfer is already running")
            stopSelfSafely("Already running")
            return
        }
        acquireLocks()
        val thread = Thread({
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "transfer failed", t)
                TransferHub.update { it.copy(state = TransferState.FAILED, message = t.message ?: "Failed") }
            } finally {
                running.set(false)
                releaseLocks()
            }
        }, "loopjet-session")
        thread.isDaemon = true
        worker.set(thread)
        thread.start()
    }

    // --- sending ---------------------------------------------------------------------------------

    private fun runSend(discovered: DiscoveredPeer) {
        val items = SendQueue.snapshot
        if (items.isEmpty()) {
            stopSelfSafely("Nothing selected")
            return
        }
        val app = LoopJetApp.instance
        val tools = NetworkTools(this)
        val startedAt = System.currentTimeMillis()
        // A Wi-Fi Direct peer is a MAC address, not something a socket can connect to. Join the group
        // first, learn the owner's IP from the platform, then ask its beacon where the server really is.
        var directUsed = false
        var peer = discovered
        if (discovered.kind == com.loopjet.app.platform.net.PeerKind.DIRECT ||
            discovered.p2pDeviceAddress != null
        ) {
            val mac = discovered.p2pDeviceAddress ?: discovered.address
            TransferHub.update {
                it.copy(
                    mode = SessionMode.SEND,
                    state = TransferState.CONNECTING,
                    peerName = discovered.name,
                    message = "Joining Wi-Fi Direct…"
                )
            }
            updateNotification()
            val joined = joinDirect(mac)
            if (!joined.ok) {
                val result = TransferResult(
                    ok = false,
                    message = joined.reason,
                    files = emptyList(),
                    bytesTransferred = 0,
                    durationMs = System.currentTimeMillis() - startedAt,
                    chunksTotal = 0,
                    chunksDone = 0,
                    pathLabel = ""
                )
                finishSession(result, Direction.SEND, discovered.name, "", startedAt, items)
                return
            }
            val address = joined.address ?: discovered.address
            directUsed = true
            val info = com.loopjet.app.core.BeaconClient().probe(
                address, ProtocolLimits.BEACON_PORT, 900
            )
            peer = DiscoveredPeer(
                id = info?.deviceId?.ifBlank { discovered.id } ?: discovered.id,
                name = info?.name ?: discovered.name,
                fingerprint = info?.fingerprint ?: discovered.fingerprint,
                address = address,
                controlPort = info?.controlPort ?: ProtocolLimits.DEFAULT_PORT,
                beaconPort = ProtocolLimits.BEACON_PORT,
                rttMicros = info?.rttMicros ?: 0,
                kind = com.loopjet.app.platform.net.PeerKind.DIRECT,
                sdk = info?.sdk ?: discovered.sdk,
                capabilities = info?.capabilities ?: discovered.capabilities,
                p2pDeviceAddress = mac
            )
            if (info == null) {
                TransferHub.update {
                    it.copy(message = "Wi-Fi Direct is up, but the other phone is not receiving")
                }
            }
        }
        val built = buildManifest(items, app.prefs.deviceName, startedAt)
        val manifest = built.first
        val itemById = built.second
        TransferHub.update {
            it.copy(
                mode = SessionMode.SEND,
                state = TransferState.CONNECTING,
                peerName = peer.name,
                filesTotal = items.size,
                totalBytes = manifest.totalBytes,
                linkLabel = pathLabelFor(peer)
            )
        }
        val network = tools.activeWifiNetwork()
        val connector = SocketConnector { port, timeout ->
            val socket = Socket()
            tools.bind(socket, network)
            socket.connect(InetSocketAddress(peer.address, port), timeout)
            socket
        }
        val listener = object : TransferListener {
            override fun onState(state: TransferState, detail: String) {
                TransferHub.update { it.copy(state = state, message = detail) }
                updateNotification()
            }

            override fun onProgress(progress: TransferProgress) {
                TransferHub.update { current ->
                    current.copy(
                        doneBytes = progress.transferredBytes,
                        instantBps = progress.instantBytesPerSecond,
                        averageBps = progress.averageBytesPerSecond,
                        filesDone = progress.filesDone,
                        filesTotal = maxOf(progress.filesTotal, current.filesTotal),
                        etaMs = progress.etaMs,
                        pathLabel = progress.pathLabel.ifBlank { current.pathLabel },
                        state = progress.state,
                        speedHistory = (current.speedHistory + progress.instantBytesPerSecond).takeLast(120)
                    )
                }
            }

            override fun onFileDone(result: FileResult) {
                TransferHub.update { it.copy(fileResults = it.fileResults + result) }
            }

            override fun onLog(message: String) {
                Log.d(TAG, message)
            }
        }
        val engine = SenderEngine(
            identity = app.identity,
            manifest = manifest,
            sourceFor = { file -> openSource(file, itemById) },
            connector = connector,
            controlPort = peer.controlPort,
            listener = listener,
            maxStreams = streamCount(app.prefs.speedMode),
            expectedPeerFingerprint = peer.fingerprint.takeIf { it.isNotBlank() },
            sessionLog = { Log.d(TAG, it) }
        )
        sender = engine
        TransferHub.update { it.copy(sas = engine.confirmationCode) }
        // Auto-resume: a dropped link restarts the same session id, so the receiver's chunk map
        // skips everything already on disk. Bounded, with backoff, and never after a cancel.
        var result = engine.run()
        var attempt = 1
        while (!result.ok && !paused && attempt <= 2 &&
            result.message !in setOf("Cancelled", "Declined", "Everything was already on the receiver")
        ) {
            val retryable = result.message.contains("closed", true) ||
                result.message.contains("reset", true) ||
                result.message.contains("refused", true) ||
                result.message.contains("timed out", true) ||
                result.message.contains("stall", true) ||
                result.message.contains("resume", true) ||
                result.message.contains("Interrupted", true)
            if (!retryable) break
            TransferHub.update {
                it.copy(state = TransferState.CONNECTING, message = "Link dropped — resuming (attempt ${attempt + 1})")
            }
            updateNotification()
            Thread.sleep(1200L * attempt)
            val resumed = SenderEngine(
                identity = app.identity,
                manifest = manifest,
                sourceFor = { file -> openSource(file, itemById) },
                connector = connector,
                controlPort = peer.controlPort,
                listener = listener,
                maxStreams = streamCount(app.prefs.speedMode),
                expectedPeerFingerprint = peer.fingerprint.takeIf { it.isNotBlank() },
                sessionLog = { Log.d(TAG, it) }
            )
            sender = resumed
            result = resumed.run()
            attempt++
        }
        // Sending to a device never grants it trust: trust is something the *receiver* decides when it
        // accepts an offer (and can be revoked in Settings).
        finishSession(result, Direction.SEND, peer.name, peer.fingerprint, startedAt, items)
        if (directUsed) {
            // Leaving a group behind drains the battery and blocks the next session.
            DirectLink.teardown()
        }
    }

    /** Blocks this service worker (never the main thread) until the group is joined or fails. */
    private fun joinDirect(mac: String): DirectLink.Result {
        val latch = java.util.concurrent.CountDownLatch(1)
        val ref = AtomicReference(DirectLink.Result(null, "Wi-Fi Direct did not start"))
        DirectLink.join(mac) { outcome ->
            ref.set(outcome)
            latch.countDown()
        }
        runCatching { latch.await(DirectLink.JOIN_TIMEOUT_MS + 8000, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return ref.get()
    }

    private fun pathLabelFor(peer: DiscoveredPeer): String = when (peer.kind) {
        com.loopjet.app.platform.net.PeerKind.DIRECT -> "Wi-Fi Direct"
        com.loopjet.app.platform.net.PeerKind.HOTSPOT -> "Hotspot"
        com.loopjet.app.platform.net.PeerKind.BLE -> "Bluetooth LE discovery"
        else -> "Same Wi-Fi"
    }

    private fun streamCount(speedMode: String): Int = when (speedMode) {
        "balanced" -> 2
        "max" -> 6
        else -> 4
    }

    private fun buildManifest(
        items: List<SendItem>,
        deviceName: String,
        startedAt: Long
    ): Pair<TransferManifest, Map<Int, SendItem>> {
        val files = ArrayList<TransferFile>()
        val idToItem = LinkedHashMap<Int, SendItem>(items.size * 2)
        val apps = ArrayList<com.loopjet.app.core.TransferApp>()
        val appGroups = LinkedHashMap<String, MutableList<Int>>()
        items.forEachIndexed { index, item ->
            val id = index + 1
            idToItem[id] = item
            files.add(
                TransferFile(
                    id = id,
                    relPath = item.relativePath,
                    size = item.size,
                    mtime = item.mtime,
                    kind = item.kind,
                    sha256 = null,
                    mime = item.mime
                )
            )
            if (item.appPackage != null) {
                appGroups.getOrPut(item.appPackage) { mutableListOf() }.add(id)
            }
        }
        for ((pkg, ids) in appGroups) {
            val sample = items.first { it.appPackage == pkg }
            apps.add(
                com.loopjet.app.core.TransferApp(
                    packageName = pkg,
                    label = sample.appLabel ?: pkg,
                    versionCode = sample.appVersionCode,
                    versionName = sample.appVersionName ?: "",
                    baseFileId = ids.first(),
                    splitFileIds = ids.drop(1),
                    minSdk = sample.appMinSdk,
                    targetSdk = sample.appTargetSdk,
                    abis = sample.appAbis
                )
            )
        }
        val manifest = TransferManifest(
            sessionId = java.util.UUID.randomUUID().toString().replace("-", "").take(16),
            files = files,
            dirs = items.map { it.relativePath.substringBeforeLast('/', "") }.filter { it.isNotBlank() }.distinct(),
            apps = apps,
            totalBytes = items.sumOf { it.size },
            chunkSize = ProtocolLimits.CHUNK_SIZE,
            senderName = deviceName,
            startedAt = startedAt
        )
        return manifest to idToItem
    }

    /**
     * Resolves the source by file id, not by display path: two picked files can legitimately share a
     * relative name, and matching by name used to send the wrong bytes (then fail the checksum).
     */
    private fun openSource(file: TransferFile, itemById: Map<Int, SendItem>): RandomAccessSource {
        val item = itemById[file.id]
            ?: throw IOException("source disappeared: ${file.relPath}")
        return openSourceForItem(item)
    }

    // --- receiving -------------------------------------------------------------------------------

    private fun startReceiver() {
        val app = LoopJetApp.instance
        val tools = NetworkTools(this)
        val binding = tools.sessionBinding()
        val saveRoot = resolveSaveRoot()
        // Media goes straight into the gallery through a pending MediaStore item (API 29+), so the
        // photo appears in Photos the moment it lands and no second copy is written.
        val factory = GallerySinkFactory(
            this,
            LocalFileSinkFactory(saveRoot) { SpaceGuard.usable(saveRoot) },
            app.prefs.saveFolderName.ifBlank { "LoopJet" }
        )
        val engine = ReceiverEngine(
            identity = app.identity,
            sinkFactory = factory,
            listener = object : TransferListener {
                override fun onState(state: TransferState, detail: String) {
                    TransferHub.update { it.copy(state = state, message = detail) }
                    updateNotification()
                }

                override fun onProgress(progress: TransferProgress) {
                    TransferHub.update { current ->
                        current.copy(
                            doneBytes = progress.transferredBytes,
                            instantBps = progress.instantBytesPerSecond,
                            averageBps = progress.averageBytesPerSecond,
                            filesDone = progress.filesDone,
                            filesTotal = maxOf(progress.filesTotal, current.filesTotal),
                            etaMs = progress.etaMs,
                            speedHistory = (current.speedHistory + progress.instantBytesPerSecond).takeLast(120)
                        )
                    }
                }

                override fun onFileDone(result: FileResult) {
                    TransferHub.update { it.copy(fileResults = it.fileResults + result) }
                }

                override fun onLog(message: String) {
                    Log.d(TAG, message)
                }

                override fun onOffer(offer: TransferOffer): OfferDecision {
                    val trusted = app.prefs.trustedDevices.contains(offer.peerFingerprint)
                    if (trusted && app.prefs.autoAcceptTrusted) {
                        Log.d(TAG, "auto-accepted offer from trusted device ${offer.peerName}")
                        return OfferDecision(accepted = true)
                    }
                    TransferHub.offer(
                        IncomingOfferUi(
                            peerName = offer.peerName,
                            sas = offer.sas,
                            files = offer.manifest.files.size,
                            bytes = offer.manifest.totalBytes,
                            trusted = trusted
                        )
                    )
                    updateNotification()
                    val decision = TransferHub.awaitDecision(180_000)
                    // "Remember this device" is only meaningful if it is actually stored.
                    if (decision.accepted && decision.trustDevice && offer.peerFingerprint.isNotBlank()) {
                        app.prefs.trust(offer.peerFingerprint, offer.peerName)
                        Log.d(TAG, "trusted ${offer.peerName} on request")
                    }
                    return decision
                }

                override fun onAppReady(
                    transferApp: com.loopjet.app.core.TransferApp,
                    apks: List<java.io.File>
                ) {
                    val warning = when {
                        transferApp.minSdk > Build.VERSION.SDK_INT ->
                            "Needs Android SDK ${transferApp.minSdk}; this phone runs ${Build.VERSION.SDK_INT}"
                        apks.none { it.length() > 0 } -> "The APK files are empty"
                        else -> ""
                    }
                    TransferHub.update { current ->
                        current.copy(
                            receivedApps = current.receivedApps + ReceivedAppUi(
                                packageName = transferApp.packageName,
                                label = transferApp.label,
                                versionName = transferApp.versionName,
                                files = apks.map { it.absolutePath },
                                message = warning
                            )
                        )
                    }
                }
            },
            resumeStore = ResumeRepo(this),
            requestedPort = ProtocolLimits.DEFAULT_PORT,
            stallTimeoutMs = 45_000,
            sessionLog = { Log.d(TAG, it) },
            onCommitted = { file, committed -> MediaIndex.index(this, committed, file.mime) }
        )
        engine.bind()
        receiver = engine
        val localAddress = binding?.address?.hostAddress ?: "0.0.0.0"
        val info = BeaconInfo(
            name = app.prefs.deviceName,
            fingerprint = app.identity.fingerprintHex,
            controlPort = engine.controlPort,
            sdk = Build.VERSION.SDK_INT,
            deviceId = app.prefs.deviceId(),
            capabilities = listOf("resume", "aes-gcm", "sdk${Build.VERSION.SDK_INT}"),
            address = localAddress
        )
        // Six digits for manual pairing: a sender can find this phone by code when multicast, mDNS
        // and subnet scans are all blocked. It is a convenience channel, not a secret: the real
        // authentication is the key exchange plus the accept prompt.
        val code = (100_000 + java.security.SecureRandom().nextInt(900_000)).toString()
        receiveCode = code
        val beaconServer = BeaconServer({ info }, { receiveCode })
        runCatching { beaconServer.bind(ProtocolLimits.BEACON_PORT) }
        beacon = beaconServer
        val qr = "loopjet://${localAddress}:${engine.controlPort}#${app.identity.fingerprintHex}#${app.prefs.deviceId()}"
        TransferHub.update {
            it.copy(
                mode = SessionMode.RECEIVE,
                state = TransferState.IDLE,
                receiveReady = true,
                receiveQr = qr,
                receiveCode = code,
                deviceName = app.prefs.deviceName,
                pathLabel = factory.rootLabel(),
                linkLabel = localAddress
            )
        }
        // Host a Wi-Fi Direct group so a sender that cannot see this phone on the Wi-Fi can still join
        // one. Best effort: on phones/ROMs without support the LAN and hotspot paths stay available.
        if (tools.supportsWifiDirect()) {
            DirectLink.host { ok, detail ->
                Log.d(TAG, "wi-fi direct host: $ok $detail")
                if (ok) {
                    p2pGroupHosted = true
                    TransferHub.update {
                        it.copy(message = "Wi-Fi Direct ready ($detail)")
                    }
                }
            }
        }
        updateNotification()
        startWorker {
            while (running.get()) {
                TransferHub.update {
                    it.copy(state = TransferState.IDLE, message = "Waiting for a sender", receiveReady = true)
                }
                val result = engine.runOnce()
                if (result.message == "No sender connected") continue
                finishSession(result, Direction.RECEIVE, result.files.firstOrNull()?.let { "Sender" } ?: "Sender", "", System.currentTimeMillis(), emptyList(), keepRunning = true)
                if (!running.get()) break
                try {
                    engine.bind()
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private fun resolveSaveRoot(): File {
        val prefs = LoopJetApp.instance.prefs
        // The folder name comes from a text field: keep it a single safe component so it can never
        // escape the directory it is joined to.
        val folder = SafePath.sanitizeComponent(prefs.saveFolderName) ?: "LoopJet"
        val external = getExternalFilesDir(null) ?: filesDir
        val canWrite = if (Build.VERSION.SDK_INT >= 30) {
            android.os.Environment.isExternalStorageManager()
        } else {
            true
        }
        val publicDownloads = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            folder
        )
        val base = if (canWrite) {
            publicDownloads.takeIf { it.exists() || it.mkdirs() } ?: File(external, folder)
        } else {
            File(external, folder)
        }
        val day = File(base, Fmt.dayFolder(System.currentTimeMillis()))
        if (!day.exists() && !day.mkdirs()) {
            val fallback = File(filesDir, "received/${Fmt.dayFolder(System.currentTimeMillis())}")
            fallback.mkdirs()
            return fallback
        }
        return day
    }

    // --- session bookkeeping ---------------------------------------------------------------------

    private fun finishSession(
        result: TransferResult,
        direction: Direction,
        peerName: String,
        peerFingerprint: String,
        startedAt: Long,
        items: List<SendItem>,
        keepRunning: Boolean = false
    ) {
        val app = LoopJetApp.instance
        val names = if (direction == Direction.SEND) items.map { it.displayName }
        else result.files.map { it.relPath }
        app.history.add(
            HistoryEntry(
                id = java.util.UUID.randomUUID().toString(),
                direction = if (direction == Direction.SEND) "send" else "receive",
                peerName = peerName,
                peerFingerprint = peerFingerprint,
                files = if (direction == Direction.SEND) items.size else result.files.size,
                bytes = result.bytesTransferred,
                startedAt = startedAt,
                durationMs = result.durationMs,
                ok = result.ok,
                path = result.pathLabel,
                fileNames = names.take(40)
            )
        )
        TransferHub.update {
            it.copy(
                state = if (result.ok) TransferState.DONE else if (it.state == TransferState.CANCELLED) TransferState.CANCELLED else TransferState.FAILED,
                message = result.message,
                doneBytes = result.bytesTransferred,
                totalBytes = if (result.bytesTransferred > it.totalBytes) result.bytesTransferred else it.totalBytes,
                fileResults = if (result.files.isNotEmpty()) result.files else it.fileResults,
                offer = null
            )
        }
        MediaIndex.notifyFinished(this, result.ok, result.message)
        if (!keepRunning) {
            stopSelfSafely(result.message)
        }
    }

    private fun cancelSession() {
        TransferHub.update { it.copy(state = TransferState.CANCELLED, message = "Cancelled") }
        TransferHub.answerOffer(accepted = false, trust = false)
        sender?.requestCancel()
        receiver?.close()
        beacon?.close()
    }

    private fun stopSelfSafely(reason: String) {
        running.set(false)
        beacon?.close()
        beacon = null
        receiver?.close()
        receiver = null
        // Without this the engine kept transferring after the service was told to stop: no foreground
        // notification, no wake lock, and the OS free to kill the process mid-file.
        runCatching { sender?.requestCancel() }
        sender = null
        worker.get()?.let { thread ->
            if (thread !== Thread.currentThread()) {
                runCatching { thread.interrupt() }
                runCatching { thread.join(1500) }
            }
        }
        if (p2pGroupHosted) {
            p2pGroupHosted = false
            DirectLink.teardown()
        }
        receiveCode = ""
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        TransferHub.update { it.copy(mode = SessionMode.IDLE, receiveReady = false, offer = null, message = reason) }
        stopSelf()
    }

    override fun onDestroy() {
        running.set(false)
        beacon?.close()
        receiver?.close()
        runCatching { sender?.requestCancel() }
        if (p2pGroupHosted) {
            p2pGroupHosted = false
            DirectLink.teardown()
        }
        releaseLocks()
        super.onDestroy()
    }

    /**
     * Android 15 contract: a `dataSync` foreground service is limited to 6 h per 24 h. When the quota
     * runs out we stop quickly — the chunk map is already persisted every couple of seconds, so the
     * user can resume from where it stopped.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service timeout for type $fgsType")
        sender?.requestPause()
        TransferHub.update {
            it.copy(
                state = TransferState.PAUSED,
                message = "Android stopped the background transfer (6 h limit). Open LoopJet to resume."
            )
        }
        stopSelfSafely("Paused by the system")
    }

    override fun onTimeout(startId: Int) {
        onTimeout(startId, 0)
    }

    // --- foreground plumbing ---------------------------------------------------------------------

    private fun promote(title: String, text: String) {
        val notification = buildNotification(title, text)
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun updateNotification() {
        val ui = TransferHub.session.value
        val title = when (ui.mode) {
            SessionMode.SEND -> "Sending to ${ui.peerName.ifBlank { "device" }}"
            SessionMode.RECEIVE -> if (ui.state == TransferState.TRANSFERRING) "Receiving from ${ui.peerName}" else "Ready to receive"
            SessionMode.IDLE -> "LoopJet"
        }
        val text = when (ui.state) {
            TransferState.TRANSFERRING -> "${Fmt.rate(ui.instantBps.toDouble())} · ${Fmt.bytes(ui.doneBytes)} / ${Fmt.bytes(ui.totalBytes)}"
            TransferState.PAUSED -> "Paused · ${Fmt.bytes(ui.doneBytes)}"
            TransferState.DONE -> "Complete · ${Fmt.bytes(ui.doneBytes)}"
            TransferState.FAILED -> "Failed · ${ui.message}"
            else -> ui.message.ifBlank { ui.pathLabel }
        }
        val manager = getSystemService(android.app.NotificationManager::class.java)
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(title, text)) }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pause = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this, 2, Intent(this, TransferService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val ui = TransferHub.session.value
        val builder = NotificationCompat.Builder(this, LoopJetApp.CHANNEL_TRANSFER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ui.state == TransferState.TRANSFERRING || ui.state == TransferState.CONNECTING)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, if (paused) "Resume" else "Pause", pause)
            .addAction(0, "Cancel", cancel)
        if (ui.totalBytes > 0 && ui.mode == SessionMode.SEND) {
            builder.setProgress(1000, ((ui.doneBytes * 1000) / ui.totalBytes).toInt().coerceIn(0, 1000), false)
        }
        return builder.build()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "loopjet:transfer").apply {
                setReferenceCounted(false)
                acquire(6L * 60 * 60 * 1000)
            }
        }
        if (wifiLock == null) {
            wifiLock = NetworkTools(this).wifiLock()?.apply { runCatching { acquire() } }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    // --- sources ---------------------------------------------------------------------------------

    companion object {
        private const val TAG = "LoopJetTransfer"
        const val NOTIFICATION_ID = 4101
        const val ACTION_SEND = "com.loopjet.app.SEND"
        const val ACTION_RECEIVE = "com.loopjet.app.RECEIVE"
        const val ACTION_PAUSE = "com.loopjet.app.PAUSE"
        const val ACTION_RESUME = "com.loopjet.app.RESUME"
        const val ACTION_STOP = "com.loopjet.app.STOP"

        fun startSend(context: Context, peer: DiscoveredPeer) {
            PendingPeer.peer = peer
            val intent = Intent(context, TransferService::class.java).setAction(ACTION_SEND)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun startReceive(context: Context) {
            val intent = Intent(context, TransferService::class.java).setAction(ACTION_RECEIVE)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        // startService throws on Android 8+ when the app is in the background; a notification action
        // or a UI tap is an exempt start, but a race must not crash the caller either.
        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, TransferService::class.java).setAction(ACTION_STOP))
            }
        }

        fun pause(context: Context) {
            runCatching {
                context.startService(Intent(context, TransferService::class.java).setAction(ACTION_PAUSE))
            }
        }

        fun resume(context: Context) {
            runCatching {
                context.startService(Intent(context, TransferService::class.java).setAction(ACTION_RESUME))
            }
        }

        fun openSourceForItem(item: SendItem): RandomAccessSource {
            item.filePath?.let { return FileSource(File(it)) }
            val uri = item.uri?.let { Uri.parse(it) } ?: throw IOException("no source for ${item.displayName}")
            return UriSource(LoopJetApp.context, uri)
        }
    }
}

object PendingPeer {
    @Volatile
    var peer: DiscoveredPeer? = null
}

/**
 * Random access over a SAF `content://` URI using the real file descriptor. This is the fast path —
 * no DocumentFile calls, no whole-file copies. If a provider hands us a non-seekable pipe we copy
 * once into app cache and say so in the log instead of failing.
 */
class UriSource(private val context: Context, private val uri: Uri) : RandomAccessSource {
    private var descriptor: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private val channel: java.nio.channels.FileChannel
    private val direct: Boolean
    override val size: Long

    init {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Cannot open $uri")
        descriptor = pfd
        val stream = FileInputStream(pfd.fileDescriptor)
        input = stream
        val ch = stream.channel
        val seekable = runCatching { ch.read(java.nio.ByteBuffer.allocate(0), 0) }.isSuccess
        val reported = pfd.statSize.takeIf { it > 0 } ?: runCatching { ch.size() }.getOrDefault(0L)
        if (seekable && reported > 0) {
            channel = ch
            direct = true
            size = reported
        } else {
            // Fall back to a cached copy (rare: pipes, cloud providers without seek support).
            val cached = File(context.cacheDir, "loopjet-cache-${uri.toString().hashCode()}.bin")
            if (!cached.exists()) {
                context.contentResolver.openInputStream(uri)?.use { streamIn ->
                    cached.outputStream().use { streamOut -> streamIn.copyTo(streamOut, 512 * 1024) }
                }
            }
            val raf = RandomAccessFile(cached, "r")
            channel = raf.channel
            direct = false
            size = cached.length()
            Log.w("LoopJetUriSource", "non-seekable source ${uri}, using cached copy")
        }
    }

    override fun readAt(buffer: ByteArray, offset: Int, length: Int, position: Long): Int {
        val bb = java.nio.ByteBuffer.wrap(buffer, offset, length)
        var pos = position
        var total = 0
        while (bb.hasRemaining()) {
            val n = if (direct) channel.read(bb, pos) else {
                synchronized(channel) {
                    channel.position(pos)
                    channel.read(bb)
                }
            }
            if (n <= 0) break
            total += n
            pos += n
        }
        return total
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { input?.close() }
        runCatching { descriptor?.close() }
    }
}

/** Chunk-map persistence in app-private storage (JSON per session). */
class ResumeRepo(context: Context) : com.loopjet.app.core.ResumeStore {
    private val dir = File(context.filesDir, "resume").apply { mkdirs() }

    init {
        // Chunk maps are small but they were never removed; keep a week's worth.
        runCatching {
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        }
    }

    /**
     * The session id comes from the other phone. It is used as a file name, so anything that is not a
     * plain identifier is dropped — otherwise `../../databases/x` would write outside this folder.
     */
    private fun fileFor(sessionId: String): File {
        val safe = sessionId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
        return File(dir, (safe.ifBlank { "session" }) + ".json")
    }

    override fun save(sessionId: String, chunkSize: Int, maps: Map<Int, com.loopjet.app.core.ChunkMap>, paths: Map<Int, String>) {
        if (maps.isEmpty()) {
            clear(sessionId)
            return
        }
        val payload = com.loopjet.app.core.Json.stringify(
            com.loopjet.app.core.Json.obj(
                "chunk" to chunkSize,
                "maps" to maps.entries.associate { it.key.toString() to it.value.toBase64() }
            )
        )
        runCatching { fileFor(sessionId).writeText(payload) }
    }

    override fun load(sessionId: String): Map<Int, com.loopjet.app.core.ChunkMap> {
        val file = fileFor(sessionId)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val map = com.loopjet.app.core.Json.parseObject(file.readText())
            val raw = map["maps"] as? Map<*, *> ?: return emptyMap()
            raw.entries.mapNotNull { (k, v) ->
                val id = (k as? String)?.toIntOrNull() ?: return@mapNotNull null
                val text = v as? String ?: return@mapNotNull null
                id to com.loopjet.app.core.ChunkMap.fromBase64(text)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    override fun clear(sessionId: String) {
        runCatching { fileFor(sessionId).delete() }
    }
}
