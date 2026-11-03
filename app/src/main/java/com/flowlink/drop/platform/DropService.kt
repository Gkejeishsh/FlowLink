package com.flowlink.drop.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.flowlink.drop.BuildConfig
import com.flowlink.drop.ClientInfo
import com.flowlink.drop.DropApp
import com.flowlink.drop.DropHub
import com.flowlink.drop.Incoming
import com.flowlink.drop.MainActivity
import com.flowlink.drop.ReceivedItem
import com.flowlink.drop.server.DropServer
import com.flowlink.drop.web.Page
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Owns the three things that must outlive the screen: the hotspot reservation, the HTTP server and
 * the join watcher.
 *
 * A foreground service rather than an activity-bound object because Android tears down the hotspot
 * the moment the process that requested it stops being important. `specialUse` rather than
 * `dataSync` because the platform time-limits data-sync services, and a session that waits for
 * files overnight is not a data sync.
 */
class DropService : Service() {

    private lateinit var prefs: com.flowlink.drop.Prefs
    private val host by lazy { HotspotHost(this) }
    private var server: DropServer? = null
    private var watcher: JoinWatcher? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var phoneName: String = "this phone"

    companion object {
        const val ACTION_START = "com.flowlink.drop.START"
        const val ACTION_STOP = "com.flowlink.drop.STOP"
        const val ACTION_RESTART = "com.flowlink.drop.RESTART"
        private const val CHANNEL = "drop.host"
        private const val NOTIFICATION_ID = 4711
        private const val TAG = "DropService"

        fun start(context: Context) {
            val intent = Intent(context, DropService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun restart(context: Context) {
            val intent = Intent(context, DropService::class.java).setAction(ACTION_RESTART)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DropService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { context.stopService(Intent(context, DropService::class.java)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = (application as DropApp).prefs
        phoneName = prefs.phoneName
        DropHub.update { it.copy(phoneName = phoneName) }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            shutDown("stopped")
            return START_NOT_STICKY
        }
        // startForeground must happen within seconds of the start request, before any network work.
        promoteToForeground(if (host.running) currentUrl() else "Starting the hotspot…")
        if (action == ACTION_RESTART) shutDownResources()
        try {
            begin()
        } catch (t: Throwable) {
            // A service that dies silently is worse than one that fails loudly: the app used to
            // vanish here with nothing on screen to explain it.
            Log.e(TAG, "hosting failed", t)
            DropHub.update {
                it.copy(phase = "failed", error = t.message ?: t.javaClass.simpleName)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutDownResources()
        // The service can be killed without ACTION_STOP; without this the screen keeps showing a
        // hotspot and an address that no longer exist.
        DropHub.update {
            it.copy(
                phase = "idle",
                ssid = "",
                password = "",
                address = "",
                port = 0,
                reachable = null,
                clients = emptyList()
            )
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ startup

    private fun begin() {
        if (host.running && server != null) {
            // Already hosting. Re-entering here used to bind a second server on the next free port,
            // which silently changed the address on screen every time the activity resumed.
            updateNotification("Waiting for someone to join", currentUrl())
            return
        }
        // Check the preconditions that make the platform throw, before it gets the chance to.
        val blocker = host.blocker()
        if (blocker != null) {
            DropHub.update {
                it.copy(
                    phase = "blocked",
                    blocker = when (blocker) {
                        Blocker.LocationPermission -> "location-permission"
                        Blocker.LocationOff -> "location-off"
                        Blocker.WifiOff -> "wifi-off"
                    },
                    error = "",
                    message = LocationGate.detail(blocker),
                    ssid = "",
                    password = "",
                    address = "",
                    port = 0
                )
            }
            updateNotification(LocationGate.title(blocker), LocationGate.detail(blocker))
            return
        }
        host.onPlatformFailure = { message ->
            DropHub.update { it.copy(phase = "failed", error = message) }
            updateNotification("Hotspot failed", message)
        }
        DropHub.update {
            it.copy(
                phase = "starting",
                message = "Starting the hotspot…",
                error = "",
                blocker = "",
                reachable = null,
                clients = emptyList()
            )
        }
        val fixedName = if (prefs.useFixedGroup) prefs.fixedName.ifBlank { defaultFixedName() } else null
        val fixedPass = if (prefs.useFixedGroup) prefs.fixedPass.ifBlank { fixedPassphrase() } else null
        host.start(fixedName, fixedPass) { info, message ->
            if (info == null) {
                DropHub.update { it.copy(phase = "failed", error = message, message = message) }
                updateNotification("Hotspot unavailable", message)
                Log.d(TAG, "hotspot failed: $message")
                return@start
            }
            startServer(info)
        }
    }

    private fun startServer(info: HotspotHost.Info) {
        val address = info.address.orEmpty()
        // 8080 upward only: Android does not let an app bind a privileged port, so "try 80 first"
        // was a promise the platform refuses to keep. The port is always shown in the URL.
        val ports = (8080..8095).toList()
        val factory = AndroidSinkFactory(this, SAVE_FOLDER)
        val dropServer = DropServer(
            sinkFactory = factory,
            pageProvider = {
                Page.render(phoneName, MAX_FILE_BYTES, BuildConfig.VERSION_NAME)
            },
            listener = listener,
            maxFileBytes = MAX_FILE_BYTES
        )
        dropServer.phoneName = phoneName
        dropServer.freeSpace = { freeSpace() }
        val bound = try {
            dropServer.start(ports, address.ifBlank { null })
        } catch (t: Throwable) {
            DropHub.update {
                it.copy(phase = "failed", error = "the page could not start: ${t.message}", message = "")
            }
            return
        }
        server?.stop()
        server = dropServer
        DropHub.update {
            it.copy(
                phase = "ready",
                ssid = info.ssid,
                password = info.passphrase,
                address = address,
                port = bound,
                band = info.band,
                route = info.route,
                message = if (address.isBlank()) {
                    "The hotspot is up but its address could not be read — tap Restart."
                } else "",
                reachable = null
            )
        }
        probe(address, bound)
        watcher?.stop()
        watcher = JoinWatcher(this) { remote, source -> onJoined(remote, source) }.also {
            it.start(address)
        }
        acquireWakeLock()
        updateNotification("Waiting for someone to join", "$address:$bound")
    }

    /**
     * Proves the address before showing it. An unproven address on screen is worse than none: the
     * guest types it, nothing happens, and neither side knows why.
     */
    private fun probe(address: String, port: Int) {
        if (address.isBlank()) return
        Thread({
            val ok = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), 900)
                    val request = "GET /api/ping HTTP/1.1\r\nHost: $address\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(request.toByteArray())
                    socket.getOutputStream().flush()
                    val line = socket.getInputStream().bufferedReader().readLine()
                    line?.contains("200") == true
                }
            }.getOrDefault(false)
            DropHub.update {
                it.copy(
                    reachable = ok,
                    message = if (ok) it.message
                    else "The page is not answering on $address — tap Restart."
                )
            }
            Log.d(TAG, "reachability on $address:$port = $ok")
        }, "drop-probe").start()
    }

    // ------------------------------------------------------------------ server events

    private val listener = object : DropServer.Listener {
        override fun onClientSeen(remote: String, userAgent: String) {
            DropHub.update { state ->
                val existing = state.clients.firstOrNull { it.address == remote }
                val clients = if (existing == null) {
                    state.clients + ClientInfo(remote, userAgent, System.currentTimeMillis())
                } else if (existing.agent.isBlank() && userAgent.isNotBlank()) {
                    state.clients.map { if (it.address == remote) it.copy(agent = userAgent) else it }
                } else {
                    state.clients
                }
                state.copy(clients = clients, requests = state.requests + 1)
            }
        }

        override fun onUploadStarted(name: String, remote: String) {
            DropHub.update { state ->
                val existing = state.incoming.firstOrNull { it.name == name }
                val list = if (existing == null) {
                    state.incoming + Incoming(name, 0, 0, "receiving")
                } else {
                    state.incoming.map { if (it.name == name) it.copy(state = "receiving") else it }
                }
                state.copy(incoming = list)
            }
            updateNotification("Receiving $name", remote)
        }

        override fun onUploadProgress(name: String, bytes: Long, total: Long) {
            DropHub.update { state ->
                state.copy(
                    incoming = state.incoming.map {
                        if (it.name == name) it.copy(bytes = bytes, total = maxOf(total, bytes)) else it
                    }
                )
            }
        }

        override fun onUploadFinished(name: String, path: String, bytes: Long) {
            DropHub.update { state ->
                val item = ReceivedItem(name, bytes, System.currentTimeMillis(), path)
                state.copy(
                    incoming = state.incoming.map {
                        if (it.name == name) it.copy(bytes = bytes, total = bytes, state = "done") else it
                    },
                    history = (state.history + item).takeLast(200)
                )
            }
            runCatching { prefs.remember(ReceivedItem(name, bytes, System.currentTimeMillis(), path)) }
            updateNotification("Received $name", "saved to $path")
        }

        override fun onUploadFailed(name: String, reason: String) {
            DropHub.update { state ->
                state.copy(
                    incoming = state.incoming.map {
                        if (it.name == name) it.copy(state = "failed") else it
                    },
                    message = reason
                )
            }
            Log.d(TAG, "upload failed: $name — $reason")
        }

        override fun onLog(message: String) {
            Log.d(TAG, message)
        }
    }

    private fun onJoined(remote: String, source: String) {
        DropHub.update { state ->
            if (state.clients.any { it.address == remote }) state
            else state.copy(
                clients = state.clients + ClientInfo(remote, "", System.currentTimeMillis())
            )
        }
        updateNotification("A device joined", "$remote via $source")
    }

    // ------------------------------------------------------------------ lifecycle

    private fun shutDown(reason: String) {
        shutDownResources()
        DropHub.update {
            it.copy(
                phase = "idle",
                message = reason,
                reachable = null,
                ssid = "",
                password = "",
                address = "",
                port = 0,
                clients = emptyList(),
                incoming = emptyList()
            )
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutDownResources() {
        watcher?.stop()
        watcher = null
        server?.stop()
        server = null
        host.stop()
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    private fun acquireWakeLock() {
        if (!prefs.keepAwake) return
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flowlink:host").apply {
            setReferenceCounted(false)
            runCatching { acquire(6 * 60 * 60 * 1000L) }
        }
    }

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL,
            "Hosting",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while this phone is receiving files"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun promoteToForeground(text: String) {
        val notification = buildNotification("FlowLink Drop", text)
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.isSuccess || runCatching {
            // Some OEM builds reject the special-use type outright. Falling back to "no type" keeps
            // the hotspot alive; staying silent would let the system kill the app ~5 s later.
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }.isSuccess
        if (!started) {
            DropHub.update {
                it.copy(phase = "failed", error = "This phone refused to keep the service running.")
            }
            stopSelf()
        }
    }

    private fun updateNotification(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(title, text))
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DropService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stop hosting", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun currentUrl(): String {
        val state = DropHub.state.value
        return if (state.url.isBlank()) "the hotspot is running" else state.url
    }

    private fun freeSpace(): Long = runCatching {
        val stat = StatFs(filesDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(-1L)

    private fun defaultFixedName(): String {
        val tail = (System.currentTimeMillis() % 1000).toString().padStart(3, '0')
        return "FlowLink-$tail"
    }

    /** WPA2 requires 8–63 printable characters; this stays inside that window. */
    private fun fixedPassphrase(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        return (1..10).map { alphabet.random() }.joinToString("")
    }
}

private const val SAVE_FOLDER = "FlowLink"
private const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024
