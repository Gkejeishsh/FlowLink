package com.loopjet.app.platform.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Local-only hotspot.
 *
 * Android's public API cannot pin the band: `startLocalOnlyHotspot(SoftApConfiguration, …)` is a
 * `@SystemApi`, and calling it through reflection is blocked by the hidden-API policy. So we use the
 * public call, report the *actual* band we were given, and offer the user's own 5 GHz hotspot as the
 * explicitly-manual alternative (the receiver joins it with one system tap).
 */
class HotspotController(private val context: Context) {
    private val wifi = context.getSystemService(WifiManager::class.java)
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    data class HotspotInfo(val ssid: String, val passphrase: String?, val address: String?, val band: String)

    companion object {
        /** requestNetwork's own timeout; the system sheet stays up for this long at most. */
        const val JOIN_TIMEOUT_MS = 60_000L

        /**
         * One instance for the process. A joined network is released through the *same* object that
         * requested it — creating a fresh controller to "release" leaves the callback registered and
         * the network up (or tears down the wrong one), so join and release share this instance.
         */
        @Volatile
        private var shared: HotspotController? = null

        fun of(context: Context): HotspotController =
            shared ?: synchronized(this) {
                shared ?: HotspotController(context.applicationContext).also { shared = it }
            }
    }

    fun start(onResult: (HotspotInfo?, String) -> Unit) {
        val manager = wifi ?: return onResult(null, "Wi-Fi is not available")
        // startLocalOnlyHotspot throws SecurityException without the Wi-Fi runtime permission. That
        // exception used to reach the service's main thread and close the app the moment Receive was
        // tapped on a phone that had not granted it yet.
        if (!hasHotspotPermission(context)) {
            return onResult(null, "Allow \"Nearby Wi-Fi devices\" to share a hotspot")
        }
        if (reservation != null) {
            return onResult(readInfo(), "already running")
        }
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                reservation = res
                // The AP interface takes a moment to appear; poll briefly for its IPv4 address.
                var attempts = 0
                val handler = Handler(Looper.getMainLooper())
                val poll = object : Runnable {
                    override fun run() {
                        val info = readInfo()
                        if (info?.address != null || attempts >= 10) {
                            onResult(info, "hotspot started")
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
                onResult(null, "hotspot stopped")
            }

            override fun onFailed(reason: Int) {
                reservation = null
                onResult(null, failureText(reason))
            }
        }
        runCatching {
            manager.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
        }.onFailure { onResult(null, it.message ?: "hotspot failed") }
    }

    private fun failureText(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "No Wi-Fi channel available for a hotspot"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "Wi-Fi is busy (turn off Wi-Fi hotspot or p2p)"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "Hotspot is disabled by the device policy"
        else -> "Could not start the hotspot (code $reason)"
    }

    @SuppressLint("MissingPermission")
    private fun readInfo(): HotspotInfo? {
        val res = reservation ?: return null
        val config = runCatching { res.softApConfiguration }.getOrNull()
        var ssid: String? = null
        var passphrase: String? = null
        if (config != null) {
            ssid = runCatching { config.wifiSsid?.toString() }.getOrNull()
            if (ssid == null) {
                @Suppress("DEPRECATION")
                ssid = runCatching { config.ssid }.getOrNull()
            }
            passphrase = runCatching { config.passphrase }.getOrNull()
        }
        if (ssid == null) {
            @Suppress("DEPRECATION")
            @SuppressLint("MissingPermission")
            val legacy = runCatching { res.wifiConfiguration }.getOrNull()
            ssid = legacy?.SSID?.trim('"')
            passphrase = passphrase ?: legacy?.preSharedKey?.trim('"')
        }
        val address = apAddress()
        val band = when {
            address == null -> "unknown band"
            else -> {
                val freq = runCatching { wifi?.connectionInfo?.frequency ?: 0 }.getOrDefault(0)
                when (freq) {
                    in 4900..5900 -> "5 GHz"
                    in 2400..2500 -> "2.4 GHz"
                    else -> "hotspot band"
                }
            }
        }
        return HotspotInfo(ssid ?: "LoopJet hotspot", passphrase, address, band)
    }

    /** Finds the hotspot AP interface address (never cellular). */
    private fun apAddress(): String? {
        for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name ?: continue
            val looksLikeAp = name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("wlan1") ||
                name.startsWith("softap")
            if (!looksLikeAp) continue
            for (address in Collections.list(iface.inetAddresses)) {
                if (address is Inet4Address && !address.isLoopbackAddress) return address.hostAddress
            }
        }
        return null
    }

    private fun hasHotspotPermission(ctx: Context): Boolean {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        return androidx.core.content.ContextCompat.checkSelfPermission(ctx, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
        cancelJoin()
    }

    fun cancelJoin() {
        val callback = networkCallback ?: return
        runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        networkCallback = null
        joinedNetwork = null
    }

    /** The network produced by the last successful [joinAp]; null when not joined. */
    @Volatile
    var joinedNetwork: Network? = null
        private set

    /**
     * Joins a network and **hands back the [Network] object**, which is the whole point.
     *
     * `NetworkTools.bind()` needs that object: after joining the other phone's hotspot this phone is
     * still associated with its own Wi-Fi or mobile network, and `activeWifiNetwork()` would return the
     * wrong interface. Sockets bound through this Network go out of the right one.
     *
     * The callback stays registered until [releaseJoin] — unregistering it early makes Android drop the
     * network, which is exactly the kind of "it connected and then died" behaviour this fixes. Android
     * shows its own consent sheet for this request and no app can suppress it, so callers must say
     * "one tap" rather than pretending otherwise.
     */
    fun joinAp(ssid: String, passphrase: String?, onResult: (Network?, String) -> Unit) {
        // ConnectivityManager answers on its own binder thread. Every caller here mutates UI state
        // (notice text, screen, PendingPeer), so hand results back on the main thread or the
        // notification can land with no recomposition and look like a dead button.
        val deliver: (Network?, String) -> Unit = { n, m ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(n, m) }
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (connectivity == null) {
            deliver(null, "Connectivity service unavailable")
            return
        }
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            if (!passphrase.isNullOrBlank() && passphrase.length >= 8) setWpa2Passphrase(passphrase)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        cancelJoin()
        var answered = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                joinedNetwork = network
                if (!answered) {
                    answered = true
                    deliver(network, "joined $ssid")
                }
            }

            override fun onUnavailable() {
                if (!answered) {
                    answered = true
                    joinedNetwork = null
                    deliver(null, "Could not join \"$ssid\" — the other phone may have stopped sharing")
                }
            }

            override fun onLost(network: Network) {
                if (joinedNetwork == network) joinedNetwork = null
            }
        }
        networkCallback = callback
        // The timeout overload takes an Int (ms); passing a Long matches no overload.
        runCatching { connectivity.requestNetwork(request, callback, JOIN_TIMEOUT_MS.toInt()) }
            .onFailure {
                if (!answered) {
                    answered = true
                    deliver(null, it.message ?: "join failed")
                }
            }
    }

    /** Releases a joined network. Call at session end, never when "connect" merely finished. */
    fun releaseJoin() {
        cancelJoin()
    }

}

/** Builds the Wi-Fi QR payload the other phone can scan with its system camera. */
object WifiQr {
    private fun escapeWifi(value: String): String = value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace(":", "\\:")

    fun payload(ssid: String, passphrase: String?, hidden: Boolean = false): String {
        val type = if (passphrase.isNullOrBlank()) "nopass" else "WPA"
        val escaped = escapeWifi(ssid)
        val pass = passphrase?.let { escapeWifi(it) } ?: ""
        val hiddenPart = if (hidden) "H:true;" else ""
        return if (type == "nopass") {
            "WIFI:T:nopass;S:$escaped;$hiddenPart;"
        } else {
            "WIFI:T:WPA;S:$escaped;P:$pass;$hiddenPart;"
        }
    }

    fun log(message: String) = Log.d("LoopJetHotspot", message)
}
