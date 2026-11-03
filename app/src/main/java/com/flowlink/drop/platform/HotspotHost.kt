package com.flowlink.drop.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * The access point. Two ways to get one, in priority order:
 *
 *  1. **Fixed group** (Wi-Fi Direct, API 29+): a name and passphrase chosen once, so the SSID is
 *     stable between sessions — the only route where "the link stays the same" is true.
 *  2. **Local-only hotspot**: works almost everywhere, but Android invents a new SSID and password
 *     every single time. That is the API, not a shortcut taken here.
 *
 * Both are wrapped in `runCatching`: hotspot creation is the single most OEM-dependent thing an app
 * can do, and a failure must become a sentence on screen, never a crash.
 */
class HotspotHost(private val context: Context) {

    data class Info(
        val ssid: String,
        val passphrase: String,
        val address: String?,
        val band: String,
        val route: String
    )

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var p2pGroup: WifiP2pGroup? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pManager: WifiP2pManager? = null

    @Volatile
    var running: Boolean = false
        private set

    fun start(fixedName: String?, fixedPass: String?, onResult: (Info?, String) -> Unit) {
        if (running) {
            onResult(readInfo(), "already running")
            return
        }
        if (!hasPermission()) {
            onResult(null, "Allow \"Nearby devices\" so this phone can host a hotspot")
            return
        }
        // Refuse up front when the platform is certain to throw: on several ROMs a hotspot request
        // with location services off raises from inside the system's own handler, which takes the
        // whole app down rather than returning a failure.
        LocationGate.blocker(
            hasLocationPermission = true,
            locationEnabled = isLocationEnabled(),
            wifiEnabled = isWifiEnabled()
        )?.let { blocker ->
            onResult(null, LocationGate.detail(blocker))
            return
        }
        if (Build.VERSION.SDK_INT >= 29 && fixedName != null && fixedPass != null) {
            if (startFixedGroup(fixedName, fixedPass, onResult)) return
        }
        startLocalOnly(onResult)
    }

    // ------------------------------------------------------------------ route 1: fixed group

    private fun startFixedGroup(
        name: String,
        passphrase: String,
        onResult: (Info?, String) -> Unit
    ): Boolean {
        val manager = runCatching { context.getSystemService(WifiP2pManager::class.java) }.getOrNull()
            ?: return false
        val channel = runCatching {
            manager.initialize(context, callbackLooper(), null)
        }.getOrNull() ?: return false
        p2pManager = manager
        p2pChannel = channel
        val config = runCatching {
            WifiP2pConfig.Builder()
                .setNetworkName(name)
                .setPassphrase(passphrase)
                .build()
        }.getOrNull() ?: return false
        var answered = false
        val handler = Handler(callbackLooper())
        runCatching {
            manager.createGroup(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (answered) return
                    handler.postDelayed({
                        answered = true
                        val info = readFixedGroupInfo()
                        if (info != null) {
                            running = true
                            onResult(info, "fixed group up")
                        } else {
                            // The group exists but cannot be read back. Fall back rather than lie —
                            // and actually do it: this branch used to set `answered` first, so the
                            // guard below silently refused to start the local-only hotspot and the
                            // phone ended up hosting nothing at all.
                            stopFixedGroup()
                            startLocalOnly(onResult)
                        }
                    }, 900)
                }

                override fun onFailure(reason: Int) {
                    if (answered) return
                    answered = true
                    Log.d(TAG, "createGroup failed: $reason")
                    startLocalOnly(onResult)
                }
            })
        }.onFailure { return false }
        return true
    }

    private fun readFixedGroupInfo(): Info? {
        val manager = p2pManager ?: return null
        val channel = p2pChannel ?: return null
        var captured: Info? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        runCatching {
            manager.requestGroupInfo(channel) { group ->
                if (group != null) {
                    p2pGroup = group
                    val ssid = runCatching { group.networkName }.getOrNull().orEmpty()
                    val pass = runCatching { group.passphrase }.getOrNull().orEmpty()
                    val address = apAddress()
                    captured = Info(
                        ssid = ssid,
                        passphrase = pass,
                        address = address,
                        band = bandOf(address),
                        route = "fixed"
                    )
                }
                latch.countDown()
            }
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return captured
    }

    private fun stopFixedGroup() {
        val manager = p2pManager ?: return
        val channel = p2pChannel ?: return
        runCatching { manager.removeGroup(channel, null) }
        p2pGroup = null
    }

    // ------------------------------------------------------------------ route 2: local-only hotspot

    private fun startLocalOnly(onResult: (Info?, String) -> Unit) {
        val wifi = context.getSystemService(WifiManager::class.java)
            ?: return onResult(null, "Wi-Fi is not available on this device")
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                reservation = res
                running = true
                val handler = Handler(callbackLooper())
                var attempts = 0
                val poll = object : Runnable {
                    override fun run() {
                        val info = readInfo()
                        if (info != null && (!info.address.isNullOrBlank() || attempts >= 12)) {
                            onResult(info, "hotspot started")
                        } else if (attempts >= 12) {
                            onResult(info, "hotspot started without an address")
                        } else {
                            attempts++
                            handler.postDelayed(this, 250)
                        }
                    }
                }
                handler.postDelayed(poll, 250)
            }

            override fun onStopped() {
                reservation = null
                running = false
                onResult(null, "hotspot stopped")
            }

            override fun onFailed(reason: Int) {
                reservation = null
                running = false
                onResult(null, failureText(reason))
            }
        }
        runCatching {
            wifi.startLocalOnlyHotspot(callback, Handler(callbackLooper()))
        }.onFailure {
            running = false
            onResult(null, it.message ?: "the hotspot could not start")
        }
    }

    private fun failureText(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
            "No Wi-Fi channel available — turn the phone's own hotspot off and try again"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "Wi-Fi is busy — turn off any other hotspot or Wi-Fi Direct group"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "Hotspots are disabled by device policy on this phone"
        else -> "The hotspot could not start (code $reason)"
    }

    @SuppressLint("MissingPermission")
    fun readInfo(): Info? {
        if (!running) return null
        // Fixed group first: its credentials are the stable ones.
        val group = p2pGroup
        if (group != null) {
            val ssid = runCatching { group.networkName }.getOrNull().orEmpty()
            if (ssid.isNotEmpty()) {
                val address = apAddress()
                return Info(
                    ssid = ssid,
                    passphrase = runCatching { group.passphrase }.getOrNull().orEmpty(),
                    address = address,
                    band = bandOf(address),
                    route = "fixed"
                )
            }
        }
        val res = reservation ?: return null
        val config = runCatching { res.softApConfiguration }.getOrNull()
        var ssid = ""
        var pass = ""
        if (config != null) {
            ssid = runCatching { config.wifiSsid?.toString() }.getOrNull().orEmpty()
            if (ssid.isEmpty()) {
                @Suppress("DEPRECATION")
                ssid = runCatching { config.ssid }.getOrNull().orEmpty()
            }
            pass = runCatching { config.passphrase }.getOrNull().orEmpty()
        }
        if (ssid.isEmpty()) {
            @Suppress("DEPRECATION")
            val legacy = runCatching { res.wifiConfiguration }.getOrNull()
            ssid = legacy?.SSID?.trim('"').orEmpty()
            if (pass.isEmpty()) pass = legacy?.preSharedKey?.trim('"').orEmpty()
        }
        val address = apAddress()
        return Info(
            ssid = ssid.ifEmpty { "FlowLink hotspot" },
            passphrase = pass,
            address = address,
            band = bandOf(address),
            route = "os"
        )
    }

    /**
     * The AP's own IPv4 address.
     *
     * Ranking matters: interface names differ per OEM (`ap0`, `wlan1`, `swlan0`, `softap0`…), so the
     * known gateway addresses Android uses for a local-only hotspot are tried first, then any
     * non-cellular address that is not the Wi-Fi *client* interface, and only then the name
     * heuristics. The server binds 0.0.0.0 either way — this value is only what gets displayed, and
     * the service proves it by connecting to itself before showing it.
     */
    fun apAddress(): String? {
        val candidates = ArrayList<Pair<Int, String>>()
        val known = arrayOf(
            "192.168.49.1", "192.168.43.1", "192.168.232.1", "192.168.50.1", "192.168.137.1"
        )
        val wifiClient = stationAddress()
        runCatching {
            for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.orEmpty()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")) continue
                for (address in Collections.list(iface.inetAddresses)) {
                    if (address !is Inet4Address || address.isLoopbackAddress) continue
                    val host = address.hostAddress ?: continue
                    if (host == wifiClient) continue
                    val priority = when {
                        known.any { it.substringBeforeLast('.') == host.substringBeforeLast('.') } -> 0
                        name.startsWith("ap") || name.startsWith("swlan") ||
                            name.startsWith("softap") || name.startsWith("wlan1") -> 1
                        else -> 2
                    }
                    candidates.add(priority to host)
                }
            }
        }
        return candidates.sortedBy { it.first }.firstOrNull()?.second
    }

    private fun stationAddress(): String? = runCatching {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val ip = wifi.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }.getOrNull()

    private fun bandOf(address: String?): String {
        if (address == null) return "unknown band"
        val wifi = runCatching { context.getSystemService(WifiManager::class.java) }.getOrNull()
            ?: return "unknown band"
        val frequency = runCatching {
            @Suppress("DEPRECATION")
            wifi.connectionInfo?.frequency ?: 0
        }.getOrDefault(0)
        return when (frequency) {
            in 4900..5900 -> "5 GHz"
            in 2400..2500 -> "2.4 GHz"
            else -> "unknown band"
        }
    }

    fun stop() {
        running = false
        runCatching { reservation?.close() }
        reservation = null
        stopFixedGroup()
        runCatching { looperThread?.quitSafely() }
        looperThread = null
        p2pManager = null
        p2pChannel = null
    }

    /**
     * Callbacks run on a thread of our own so that an exception thrown by the platform while
     * dispatching them kills that thread — not the app. The uncaught handler turns it into a
     * sentence the user can act on, which is the difference between "it crashed" and "it said
     * location was off".
     */
    @Volatile
    private var looperThread: android.os.HandlerThread? = null

    @Volatile
    var onPlatformFailure: ((String) -> Unit)? = null

    private fun callbackLooper(): Looper {
        looperThread?.let { if (it.isAlive) return it.looper }
        val thread = android.os.HandlerThread("drop-hotspot").apply { start() }
        looperThread = thread
        // `Looper.setUncaughtExceptionHandler` does not exist: the handler belongs to the Thread,
        // and a crash inside a handler dispatch is routed to it.
        thread.setUncaughtExceptionHandler { _, error ->
            running = false
            Log.e(TAG, "hotspot callback failed", error)
            onPlatformFailure?.invoke(error.message ?: error.javaClass.simpleName)
        }
        return thread.looper!!
    }

    fun isWifiEnabled(): Boolean =
        runCatching { context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true }
            .getOrDefault(false)

    fun isLocationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            val manager = runCatching {
                context.getSystemService(android.location.LocationManager::class.java)
            }.getOrNull() ?: return true
            return runCatching { manager.isLocationEnabled }.getOrDefault(true)
        }
        @Suppress("DEPRECATION")
        return runCatching {
            android.provider.Settings.Secure.getInt(
                context.contentResolver,
                android.provider.Settings.Secure.LOCATION_MODE,
                android.provider.Settings.Secure.LOCATION_MODE_OFF
            ) != android.provider.Settings.Secure.LOCATION_MODE_OFF
        }.getOrDefault(true)
    }

    fun hasLocationPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun blocker(): Blocker? = LocationGate.blocker(
        hasLocationPermission = hasLocationPermission(),
        locationEnabled = isLocationEnabled(),
        wifiEnabled = isWifiEnabled()
    )

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "DropHotspot"
    }
}
