package com.loopjet.app.platform

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.loopjet.app.MainActivity
import com.loopjet.app.R
import com.loopjet.app.core.Fmt
import com.loopjet.app.core.ProtocolLimits
import com.loopjet.app.core.SafePath
import com.loopjet.app.core.SharedFile
import com.loopjet.app.core.SpaceGuard
import com.loopjet.app.core.WebConfig
import com.loopjet.app.core.WebMode
import com.loopjet.app.core.WebServer
import com.loopjet.app.core.WebServerEvents
import com.loopjet.app.platform.net.HotspotController
import com.loopjet.app.platform.net.NetworkTools
import com.loopjet.app.platform.net.WifiQr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

data class WebUi(
    val active: Boolean = false,
    val mode: WebMode = WebMode.SHARE,
    val url: String = "",
    val qr: String = "",
    val wifiQr: String = "",
    val hotspotSsid: String = "",
    val clients: Int = 0,
    val bytesServed: Long = 0,
    val uploads: Int = 0,
    val pin: String = "",
    val expiresAt: Long = 0,
    val message: String = "",
    val addressLabel: String = "",
    /** Address of a browser waiting for the phone owner to allow it. */
    val pendingBrowser: String = ""
)

object WebHub {
    private val _state = MutableStateFlow(WebUi())
    val state: StateFlow<WebUi> = _state

    private val pendingApproval = AtomicReference<CompletableDeferred<Boolean>?>(null)

    fun update(block: (WebUi) -> WebUi) {
        _state.value = block(_state.value)
    }

    /** Called from the browser thread; blocks until the phone owner answers or the wait expires. */
    fun awaitBrowserApproval(remote: String, timeoutMs: Long = 90_000): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingApproval.set(deferred)
        update { it.copy(pendingBrowser = remote, message = "A browser is asking for permission") }
        val answer = runBlocking { withTimeoutOrNull(timeoutMs) { deferred.await() } }
        pendingApproval.compareAndSet(deferred, null)
        update { it.copy(pendingBrowser = "") }
        return answer == true
    }

    fun answerBrowser(accepted: Boolean) {
        update { it.copy(pendingBrowser = "") }
        pendingApproval.getAndSet(null)?.complete(accepted)
    }
}

object WebShareQueue {
    private val _files = MutableStateFlow<List<SendItem>>(emptyList())
    val files: StateFlow<List<SendItem>> = _files

    fun set(items: List<SendItem>) {
        _files.value = items
    }

    fun snapshot(): List<SendItem> = _files.value
}

/**
 * Serves the Invite page (this app's own universal APK) and the Web Share page from the phone, to any
 * browser on the same network. Runs as a foreground service so the server survives the screen going
 * off, and stops itself when the session expires.
 */
class WebService : Service() {
    private var server: WebServer? = null
    private var hotspot: HotspotController? = null
    private var hotspotInfo: HotspotController.HotspotInfo? = null
    private var stagingDir: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_INVITE -> startServer(WebMode.INVITE, intent.getBooleanExtra(EXTRA_HOTSPOT, false))
            ACTION_START_SHARE -> startServer(WebMode.SHARE, false)
            ACTION_STOP -> stopSelfSafely()
            else -> stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    private fun startServer(mode: WebMode, useHotspot: Boolean) {
        if (server != null) {
            stopServer()
        }
        promote()
        val app = LoopJetApp.instance
        val tools = NetworkTools(this)
        if (useHotspot) {
            val controller = HotspotController(this)
            hotspot = controller
            controller.start { info, _ ->
                hotspotInfo = info
                runCatching { launchServer(mode, tools, info) }
            }
        } else {
            launchServer(mode, tools, null)
        }
    }

    private fun launchServer(
        mode: WebMode,
        tools: NetworkTools,
        hotspot: HotspotController.HotspotInfo?
    ) {
        val app = LoopJetApp.instance
        val binding = tools.sessionBinding()
        val address = hotspot?.address ?: binding?.address?.hostAddress ?: "127.0.0.1"
        val addressInet = runCatching { java.net.InetAddress.getByName(address) }.getOrNull()
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        // Files that only exist behind a content:// URI must be materialised. They go into a session
        // folder with a unique name (two picks can share a display name) and are deleted on stop; if
        // there is not enough space the file is skipped with a message instead of filling the disk.
        val staging = File(cacheDir, "web-$token").apply { mkdirs() }
        stagingDir = staging
        val files = if (mode == WebMode.SHARE) {
            val free = SpaceGuard.usable(staging)
            WebShareQueue.snapshot().mapIndexedNotNull { index, item ->
                val file = item.filePath?.let { File(it) } ?: runCatching {
                    val target = File(staging, "${index + 1}-" + (SafePath.sanitizeComponent(item.displayName) ?: "file"))
                    val size = item.size
                    if (size > 0 && free > 0 && size > free - 32L * 1024 * 1024) return@runCatching null
                    contentResolver.openInputStream(android.net.Uri.parse(item.uri))
                        ?.use { input -> target.outputStream().use { input.copyTo(it, 512 * 1024) } }
                    target
                }.getOrNull()
                if (file == null || !file.isFile) null
                else SharedFile(index + 1, item.displayName, file.length(), item.mime, file)
            }
        } else emptyList()
        val installApk = if (mode == WebMode.INVITE) {
            runCatching { File(applicationInfo.sourceDir) }.getOrNull()
        } else null
        val downloadFolder = uploadFolder()
        val pin = if (app.prefs.webRequirePin && app.prefs.webPin.isNotBlank()) app.prefs.webPin else null
        val expiresAt = System.currentTimeMillis() + 30 * 60 * 1000
        val config = WebConfig(
            title = app.prefs.deviceName.ifBlank { "LoopJet" },
            versionName = com.loopjet.app.BuildConfig.VERSION_NAME,
            mode = mode,
            files = files,
            root = downloadFolder,
            uploadDir = if (mode == WebMode.SHARE) downloadFolder else null,
            allowUpload = mode == WebMode.SHARE,
            installPackage = installApk,
            bindAddress = addressInet,
            pin = pin,
            requirePin = pin != null,
            // Web Share is an inbound door: the first request from each browser needs a tap on the
            // phone. Invite is outbound only, so it does not need the extra gate.
            token = token,
            requireApproval = mode == WebMode.SHARE,
            expiresAt = expiresAt
        )
        val webServer = WebServer(config, object : WebServerEvents {
            override fun onLog(message: String) { Log.d(TAG, message) }
            override fun onDownload(name: String, sent: Long, total: Long) = Unit

            override fun onApprovalNeeded(remote: String, userAgent: String): Boolean {
                Log.d(TAG, "browser $remote asking for approval")
                return WebHub.awaitBrowserApproval(remote)
            }

            override fun onUploadFinished(name: String, file: java.io.File) {
                // Published so a photo dropped from the browser shows up in the gallery at once.
                MediaIndex.index(this@WebService, file, mimeOf(name))
            }

            override fun onUpload(name: String, received: Long, total: Long) {
                if (received >= total && total > 0) {
                    WebHub.update { it.copy(uploads = it.uploads + 1) }
                }
            }

            override fun onClientSeen(remote: String, userAgent: String) {
                WebHub.update { it.copy(clients = server?.activeClients ?: it.clients) }
            }

            override fun onFinished(reason: String) {
                Log.d(TAG, "web server finished: $reason")
                if (reason == "expired") stopSelfSafely()
            }
        })
        val port = webServer.start()
        server = webServer
        val url = "http://$address:$port/?t=$token"
        val wifiQr = hotspot?.let { info ->
            if (info.passphrase.isNullOrBlank()) WifiQr.payload(info.ssid, null)
            else WifiQr.payload(info.ssid, info.passphrase)
        } ?: ""
        WebHub.update {
            WebUi(
                active = true,
                mode = mode,
                url = url,
                qr = url,
                wifiQr = wifiQr,
                hotspotSsid = hotspot?.ssid ?: "",
                pin = pin ?: "",
                expiresAt = expiresAt,
                addressLabel = hotspot?.band ?: tools.bandLabel(),
                message = when {
                    address == "127.0.0.1" ->
                        "No Wi-Fi network found — connect to Wi-Fi or use the hotspot option"
                    mode == WebMode.INVITE -> "Share this link to install LoopJet"
                    else -> "Open this link on any device on this Wi-Fi"
                }
            )
        }
        // Index uploaded media so it appears in the gallery immediately.
        Thread({
            while (server === webServer) {
                Thread.sleep(1000)
                val served = webServer.totalBytesServed
                WebHub.update { it.copy(bytesServed = served, clients = webServer.activeClients) }
            }
        }, "lj-web-stats").apply { isDaemon = true }.start()
    }

    private fun stopServer() {
        runCatching { server?.close() }
        server = null
        runCatching { hotspot?.stop() }
        hotspot = null
        // The staging copies existed only to serve this session.
        runCatching { stagingDir?.deleteRecursively() }
        stagingDir = null
        WebHub.update { WebUi() }
    }

    /**
     * Uploads land where the user can find them: public Downloads when all-files access is granted,
     * otherwise app storage with a MediaStore publish on completion.
     */
    private fun uploadFolder(): File {
        val name = LoopJetApp.instance.prefs.saveFolderName.ifBlank { "LoopJet" }.let {
            SafePath.sanitizeComponent(it) ?: "LoopJet"
        }
        val canWrite = if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.os.Environment.isExternalStorageManager()
        } else true
        val base = if (canWrite) {
            File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                name
            )
        } else {
            File(getExternalFilesDir(null) ?: filesDir, name)
        }
        val folder = File(base, "Web")
        if (!folder.exists() && !folder.mkdirs()) return File(filesDir, "web-uploads").apply { mkdirs() }
        return folder
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    private fun stopSelfSafely() {
        stopServer()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelfSafely()
    }

    override fun onTimeout(startId: Int) {
        stopSelfSafely()
    }

    private fun promote() {
        val notification: Notification = NotificationCompat.Builder(this, LoopJetApp.CHANNEL_SERVER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("LoopJet sharing")
            .setContentText("A local page is being served")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
        val type = if (android.os.Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "LoopJetWeb"
        private const val NOTIFICATION_ID = 4103
        const val ACTION_START_INVITE = "com.loopjet.app.WEB_INVITE"
        const val ACTION_START_SHARE = "com.loopjet.app.WEB_SHARE"
        const val ACTION_STOP = "com.loopjet.app.WEB_STOP"
        const val EXTRA_HOTSPOT = "hotspot"

        fun startInvite(context: Context, useHotspot: Boolean) {
            val intent = Intent(context, WebService::class.java)
                .setAction(ACTION_START_INVITE)
                .putExtra(EXTRA_HOTSPOT, useHotspot)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun startShare(context: Context) {
            val intent = Intent(context, WebService::class.java).setAction(ACTION_START_SHARE)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WebService::class.java).setAction(ACTION_STOP))
        }
    }
}
