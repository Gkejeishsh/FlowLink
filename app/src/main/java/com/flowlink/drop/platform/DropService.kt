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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * Owns the three things that must outlive the screen: the access point, the HTTP server and the
 * join watcher.
 *
 * The rule this file exists to enforce: **the UI describes reality, not intentions.** A device
 * report ("I could see the hotspot but the app said it failed") came from three separate ways the
 * old code could lose track — a restart that tore down a working hotspot, a stale `onStopped`, and a
 * failure path that fired when the reservation was alive but unreadable. All three are closed here,
 * and a watchdog re-syncs the screen with the access point every two seconds regardless.
 */
class DropService : Service() {

    private lateinit var prefs: com.flowlink.drop.Prefs
    private val host by lazy { HotspotHost(this) }
    private var server: DropServer? = null
    private var watcher: JoinWatcher? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var phoneName: String = "this phone"
    private var hosting = false

    private val main = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            runCatching { reconcile() }
            main.postDelayed(this, 2_000)
        }
    }

    companion object {
        const val ACTION_START = "com.flowlink.drop.START"
        const val ACTION_STOP = "com.flowlink.drop.STOP"
        const val ACTION_RESTART = "com.flowlink.drop.RESTART"
        private const val CHANNEL = "drop.host"
        private const val NOTIFICATION_ID = 4711
        private const val TAG = "DropService"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, DropService::class.java).setAction(ACTION_START)
                )
            }
        }

        fun restart(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, DropService::class.java).setAction(ACTION_RESTART)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, DropService::class.java).setAction(ACTION_STOP)) }
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
            shutDown("stopped by you")
            return START_NOT_STICKY
        }
        promoteToForeground(if (hosting) currentUrl() else "Starting the hotspot…")
        main.removeCallbacks(watchdog)
        main.post(watchdog)
        try {
            ensureHosting(force = action == ACTION_RESTART)
        } catch (t: Throwable) {
            Log.e(TAG, "hosting failed", t)
            DropHub.update { it.copy(phase = "failed", error = t.message ?: t.javaClass.simpleName) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        main.removeCallbacks(watchdog)
        release()
        DropHub.update {
            it.copy(
                phase = "idle",
                ssid = "",
                password = "",
                address = "",
                port = 0,
                reachable = null,
                clients = emptyList(),
                route = ""
            )
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ hosting lifecycle

    private fun ensureHosting(force: Boolean) {
        if (force) {
            // A deliberate restart: tear down first, so the platform is not asked for a second AP
            // while ours is alive (that is what produced ERROR_INCOMPATIBLE_MODE for ever).
            release()
        } else if (hosting && server != null) {
            // Already up. Re-entering here used to bind a second server on the next free port.
            updateNotification("Waiting for someone to join", currentUrl())
            return
        }

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
                    port = 0,
                    route = ""
                )
            }
            updateNotification(LocationGate.title(blocker), LocationGate.detail(blocker))
            return
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

        val useDirect = prefs.useFixedGroup
        host.start(
            preferDirectGroup = useDirect,
            name = prefs.fixedName.ifBlank { "FlowLink-" + (100..999).random() },
            passphrase = prefs.fixedPass.ifBlank { null },
            listener = object : HotspotHost.Listener {
                override fun onHosting(info: HotspotHost.Info) {
                    hosting = true
                    startServer(info)
                }

                override fun onRouteFailed(route: String, reason: String) {
                    // Not a failure of the app, just this route: keep the screen honest and quiet.
                    if (!hosting) {
                        DropHub.update {
                            it.copy(
                                phase = "starting",
                                message = when (route) {
                                    "direct" -> "Trying Wi-Fi Direct…"
                                    "os" -> "Trying the phone's own hotspot…"
                                    else -> "Setting up…"
                                },
                                route = route
                            )
                        }
                    }
                    Log.d(TAG, "route $route: $reason")
                }

                override fun onAllRoutesFailed(reason: String) {
                    hosting = false
                    DropHub.update {
                        it.copy(
                            phase = "failed",
                            error = reason,
                            message = "",
                            route = ""
                        )
                    }
                    updateNotification("Hotspot unavailable", reason)
                }
            }
        )
    }

    /**
     * Re-syncs the screen with the access point. This is the safety net for every case where a
     * callback is late, dropped, or delivered on a thread that is already gone: if the host says it
     * is hosting, the screen says so too, within two seconds.
     */
    private fun reconcile() {
        if (!host.hosting) return
        hosting = true
        val info = host.currentInfo() ?: return
        if (server == null || DropHub.state.value.phase != "ready") startServer(info)
    }

    private fun startServer(info: HotspotHost.Info) {
        val existing = server
        if (existing != null && existing.boundPort != 0) {
            publish(info, existing.boundPort)
            return
        }
        val ports = (8080..8095).toList()
        val factory = AndroidSinkFactory(this, SAVE_FOLDER)
        val dropServer = DropServer(
            sinkFactory = factory,
            pageProvider = { Page.render(phoneName, MAX_FILE_BYTES, BuildConfig.VERSION_NAME) },
            listener = listener,
            maxFileBytes = MAX_FILE_BYTES
        )
        dropServer.phoneName = phoneName
        dropServer.freeSpace = { freeSpace() }
        val bound = try {
            dropServer.start(ports, info.address)
        } catch (t: Throwable) {
            // The access point is up but the page is not. Say which half failed.
            DropHub.update {
                it.copy(
                    phase = "failed",
                    error = "The hotspot is up, but the page could not start: ${t.message}"
                )
            }
            return
        }
        server = dropServer
        publish(info, bound)
        watcher?.stop()
        watcher = JoinWatcher(this) { remote, source -> onJoined(remote, source) }
            .also { it.start(info.address) }
        acquireWakeLock()
    }

    private fun publish(info: HotspotHost.Info, port: Int) {
        val address = info.address.orEmpty()
        val manual = info.route == "manual"
        DropHub.update {
            it.copy(
                phase = "ready",
                ssid = info.ssid,
                password = info.passphrase,
                address = address,
                port = port,
                band = info.band,
                route = info.route,
                message = when {
                    info.ssid.isBlank() && manual ->
                        "Turn on this phone's own hotspot in Settings; the page is already running."
                    address.isBlank() ->
                        "The hotspot is up, but its address could not be read — tap Restart."
                    else -> ""
                },
                reachable = null
            )
        }
        if (address.isNotBlank()) probe(address, port)
        updateNotification(
            when {
                info.ssid.isBlank() -> "Page running — turn on your hotspot"
                else -> "Waiting for someone to join"
            },
            if (address.isBlank()) ":$port" else "$address:$port"
        )
    }

    /**
     * Proves the address before showing it. An unproven address on screen is worse than none: the
     * guest types it, nothing happens, and neither side knows why.
     */
    private fun probe(address: String, port: Int) {
        Thread({
            val ok = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), 900)
                    val request = "GET /api/ping HTTP/1.1\r\nHost: $address\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(request.toByteArray())
                    socket.getOutputStream().flush()
                    socket.getInputStream().bufferedReader().readLine()?.contains("200") == true
                }
            }.getOrDefault(false)
            DropHub.update { state ->
                state.copy(
                    reachable = ok,
                    message = when {
                        ok -> state.message
                        state.address.isBlank() -> state.message
                        else -> "The page is not answering on $address — tap Restart."
                    }
                )
            }
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
            val item = ReceivedItem(name, bytes, System.currentTimeMillis(), path)
            DropHub.update { state ->
                state.copy(
                    incoming = state.incoming.map {
                        if (it.name == name) it.copy(bytes = bytes, total = bytes, state = "done") else it
                    },
                    history = (state.history + item).takeLast(200)
                )
            }
            runCatching { prefs.remember(item) }
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
            else state.copy(clients = state.clients + ClientInfo(remote, "", System.currentTimeMillis()))
        }
        updateNotification("A device joined", "$remote via $source")
    }

    // ------------------------------------------------------------------ teardown

    private fun shutDown(reason: String) {
        release()
        DropHub.update {
            it.copy(
                phase = "idle",
                message = reason,
                reachable = null,
                ssid = "",
                password = "",
                address = "",
                port = 0,
                route = "",
                clients = emptyList(),
                incoming = emptyList()
            )
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun release() {
        main.removeCallbacks(watchdog)
        watcher?.stop()
        watcher = null
        server?.stop()
        server = null
        hosting = false
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
        val channel = NotificationChannel(CHANNEL, "Hosting", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while this phone is receiving files"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun promoteToForeground(text: String) {
        val notification = buildNotification("FlowLink Drop", text)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        }.isSuccess || runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }.isSuccess
        if (!started) {
            // Do not stopSelf: that would tear down an access point that may already be running.
            Log.e(TAG, "startForeground refused")
            DropHub.update {
                it.copy(phase = "failed", error = "This phone refused to keep the service running.")
            }
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
}

private const val SAVE_FOLDER = "FlowLink"
private const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024
