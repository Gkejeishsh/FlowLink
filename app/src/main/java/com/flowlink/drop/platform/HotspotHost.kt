package com.flowlink.drop.platform

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.flowlink.drop.core.HostRoutes
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * The access point, as a state machine that never lies about what it has.
 *
 * Three routes, tried in order, each with its own failure message:
 *  1. **Wi-Fi Direct group** — a name and password you choose, so the SSID is stable between
 *     sessions, and the platform will tell us who joined (`requestGroupInfo`). This is what ShareIt
 *     class apps use, and it is the default here when the phone supports it.
 *  2. **Local-only hotspot** — works almost everywhere, but Android invents a new SSID and password
 *     every session.
 *  3. **Your own hotspot** — the app cannot create it, so it says so and serves on whatever access
 *     point the phone already has.
 *
 * Two rules that came out of a device report ("I could see the hotspot but the app said it failed"):
 *
 *  * A callback from a *previous* attempt is ignored. Android delivers `onStopped` for a reservation
 *    you have just closed, and it arrives *after* the new attempt has already started — that single
 *    race was enough to show "hotspot stopped" over a working hotspot forever.
 *  * `hosting` is set the moment the platform says it started, and only cleared by an explicit stop
 *    or by a failure of the *current* attempt. Nothing reports "failed" while an access point that
 *    this process created is up.
 */
class HotspotHost(private val context: Context) {

    data class Info(
        val ssid: String,
        val passphrase: String,
        val address: String?,
        val band: String,
        val route: String
    ) {
        val usable: Boolean get() = ssid.isNotBlank()
    }

    /** What the UI and the service listen to. All calls arrive on the main thread. */
    interface Listener {
        fun onHosting(info: Info)
        fun onRouteFailed(route: String, reason: String)
        fun onAllRoutesFailed(reason: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = HandlerThread("drop-hotspot").apply { start() }
    private val workerHandler = Handler(worker.looper ?: Looper.getMainLooper())

    @Volatile
    var hosting: Boolean = false
        private set

    @Volatile
    private var current: Info? = null

    private var generation = 0
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var group: WifiP2pGroup? = null
    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var listener: Listener? = null

    private var preferDirect = true
    private var directName: String? = null
    private var directPass: String? = null
    private var routeIndex = 0

    private val routes = listOf("direct", "os", "manual")

    /**
     * Starts hosting, or reports why it could not. Safe to call repeatedly: a second call while
     * hosting simply re-reports the current access point instead of creating another one.
     */
    fun start(
        preferDirectGroup: Boolean,
        name: String?,
        passphrase: String?,
        listener: Listener
    ) {
        this.listener = listener
        preferDirect = preferDirectGroup
        directName = name
        directPass = passphrase
        if (hosting) {
            val info = current ?: readInfo()
            if (info != null) {
                main.post { listener.onHosting(info) }
            } else {
                // Hosting, but the platform will not hand back the details yet. Keep the state
                // honest and let the watchdog fill them in rather than starting a second AP.
                main.post { listener.onRouteFailed("current", "reading details…") }
            }
            return
        }
        routeIndex = if (preferDirect && supportsDirect()) 0 else 1
        generation++
        attempt(generation)
    }

    /** Cheap liveness check for the service watchdog. */
    fun currentInfo(): Info? {
        if (!hosting) return null
        val info = current ?: readInfo()
        if (info != null) current = info
        return info
    }

    // ------------------------------------------------------------------ route machine

    private fun attempt(gen: Int) {
        if (gen != generation) return
        val route = routes.getOrNull(routeIndex)
        if (route == null) {
            hosting = false
            main.post { listener?.onAllRoutesFailed(lastFailure) }
            return
        }
        main.post { listener?.onRouteFailed(route, "starting") }
        when (route) {
            "direct" -> startDirect(gen)
            "os" -> startLocalOnly(gen)
            else -> hostManual(gen)
        }
    }

    private fun advance(gen: Int, reason: String) {
        if (gen != generation) return
        lastFailure = reason
        Log.d(TAG, "route ${routes.getOrNull(routeIndex)} failed: $reason")
        main.post { listener?.onRouteFailed(routes.getOrNull(routeIndex) ?: "", reason) }
        routeIndex++
        main.postDelayed({ attempt(gen) }, 350)
    }

    @Volatile
    private var lastFailure = "the hotspot could not start"

    // ------------------------------------------------------------------ route 1: Wi-Fi Direct

    private fun supportsDirect(): Boolean = runCatching {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) &&
            context.getSystemService(WifiP2pManager::class.java) != null
    }.getOrDefault(false)

    private fun startDirect(gen: Int) {
        val manager = runCatching { context.getSystemService(WifiP2pManager::class.java) }.getOrNull()
            ?: return advance(gen, "Wi-Fi Direct is not available on this phone")
        val ch = runCatching { manager.initialize(context, workerHandler.looper, null) }.getOrNull()
            ?: return advance(gen, "Wi-Fi Direct could not be initialised")
        p2p = manager
        channel = ch
        val config = runCatching {
            WifiP2pConfig.Builder()
                .setNetworkName(validName(directName))
                .setPassphrase(validPass(directPass))
                .build()
        }.getOrNull() ?: return advance(gen, "Wi-Fi Direct rejected the network name or password")

        val created = createGroup(manager, ch, config, gen, retried = false)
        if (!created) advance(gen, "Wi-Fi Direct group could not be created")
    }

    /**
     * `createGroup` fails with BUSY when a group is already there — often one left behind by a
     * previous run of this app, or by the system's own Share sheet. Removing it and trying once more
     * is the difference between "works" and "works until the first restart".
     */
    private fun createGroup(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        config: WifiP2pConfig,
        gen: Int,
        retried: Boolean
    ): Boolean {
        var accepted = true
        runCatching {
            manager.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    workerHandler.postDelayed({ publishDirect(gen, 0) }, 700)
                }

                override fun onFailure(reason: Int) {
                    if (gen != generation) return
                    if (!retried && reason == WifiP2pManager.BUSY) {
                        runCatching { manager.removeGroup(ch, null) }
                        workerHandler.postDelayed({
                            if (gen == generation) createGroup(manager, ch, config, gen, retried = true)
                        }, 600)
                    } else {
                        advance(gen, directFailure(reason))
                    }
                }
            })
        }.onFailure { accepted = false }
        return accepted
    }

    private fun publishDirect(gen: Int, tries: Int) {
        if (gen != generation) return
        val manager = p2p
        val ch = channel
        if (manager == null || ch == null) return advance(gen, "Wi-Fi Direct was lost")
        var info: WifiP2pGroup? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        runCatching {
            manager.requestGroupInfo(ch) { found ->
                info = found
                latch.countDown()
            }
        }
        val settled = runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
            .getOrDefault(false)
        val found = info
        val ssid = runCatching { found?.networkName }.getOrNull().orEmpty()
        if (!settled || ssid.isBlank()) {
            if (tries < 4) {
                workerHandler.postDelayed({ publishDirect(gen, tries + 1) }, 500)
            } else {
                // The group exists but cannot be read back. Tear it down so the next route is not
                // blocked by a stray group, and move on.
                runCatching { manager.removeGroup(ch, null) }
                advance(gen, "the Wi-Fi Direct group could not be read back")
            }
            return
        }
        group = found
        val address = apAddress()
        val snapshot = Info(
            ssid = ssid,
            passphrase = runCatching { found?.passphrase }.getOrNull().orEmpty(),
            address = address,
            band = bandOf(address),
            route = "direct"
        )
        current = snapshot
        hosting = true
        main.post { listener?.onHosting(snapshot) }
    }

    private fun directFailure(reason: Int): String = when (reason) {
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy (another group or hotspot is running)"
        WifiP2pManager.ERROR -> "Wi-Fi Direct hit an internal error"
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this phone"
        else -> "Wi-Fi Direct failed (code $reason)"
    }

    // ------------------------------------------------------------------ route 2: local-only hotspot

    private fun startLocalOnly(gen: Int) {
        val wifi = runCatching { context.getSystemService(WifiManager::class.java) }.getOrNull()
            ?: return advance(gen, "Wi-Fi is not available on this device")
        if (reservation != null) {
            // A reservation from an earlier attempt is still alive: close it first, or the platform
            // answers ERROR_INCOMPATIBLE_MODE for ever.
            runCatching { reservation?.close() }
            reservation = null
        }
        var delivered = false
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                if (gen != generation) {
                    runCatching { res.close() }
                    return
                }
                reservation = res
                hosting = true
                // Wait for the AP interface, but never wait forever and never without an answer.
                publishLocal(gen, 0)
            }

            override fun onStopped() {
                // Ignored on purpose when it belongs to a superseded attempt: Android fires this for
                // the reservation we closed ourselves, *after* the replacement started.
                if (gen != generation) return
                reservation = null
                if (hosting) {
                    hosting = false
                    current = null
                    main.post { listener?.onRouteFailed("os", "the hotspot was stopped by the system") }
                }
            }

            override fun onFailed(reason: Int) {
                if (gen != generation) return
                reservation = null
                advance(gen, failureText(reason))
            }
        }
        runCatching {
            wifi.startLocalOnlyHotspot(callback, workerHandler)
        }.onFailure {
            if (gen == generation) advance(gen, it.message ?: "the hotspot could not start")
        }
    }

    private fun publishLocal(gen: Int, tries: Int) {
        if (gen != generation) return
        val info = readLocalInfo()
        if (info == null) {
            if (tries < 16) {
                workerHandler.postDelayed({ publishLocal(gen, tries + 1) }, 250)
            } else {
                // The reservation exists (onStarted fired) but carries no readable config. Report it
                // as hosting with blank credentials rather than as a failure: the phone *is* an AP.
                hosting = true
                val fallback = Info(
                    ssid = "",
                    passphrase = "",
                    address = apAddress(),
                    band = bandOf(apAddress()),
                    route = "os"
                )
                current = fallback
                main.post { listener?.onHosting(fallback) }
            }
            return
        }
        // The address can lag the credentials by a few hundred milliseconds.
        if (info.address.isNullOrBlank() && tries < 16) {
            workerHandler.postDelayed({ publishLocal(gen, tries + 1) }, 250)
            return
        }
        current = info
        hosting = true
        main.post { listener?.onHosting(info) }
    }

    private fun readLocalInfo(): Info? {
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
        if (ssid.isEmpty()) return null
        val address = apAddress()
        return Info(ssid, pass, address, bandOf(address), "os")
    }

    // ------------------------------------------------------------------ route 3: your own hotspot

    private fun hostManual(gen: Int) {
        if (gen != generation) return
        val address = apAddress()
        val info = Info(
            ssid = "",
            passphrase = "",
            address = address,
            band = bandOf(address),
            route = "manual"
        )
        // Nothing was created, so nothing can be "failed"; the service shows the instructions and
        // serves the page on whatever address the phone has.
        current = info
        hosting = true
        main.post { listener?.onHosting(info) }
    }

    private fun failureText(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
            "no Wi-Fi channel is free — turn off any other hotspot and try again"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "Wi-Fi is busy with another hotspot or Wi-Fi Direct group"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "this phone's policy forbids hotspots (carrier restriction or device policy)"
        else -> "the hotspot could not start (code $reason)"
    }

    // ------------------------------------------------------------------ queries

    fun readInfo(): Info? = current ?: group?.let {
        Info(
            ssid = runCatching { it.networkName }.getOrNull().orEmpty(),
            passphrase = runCatching { it.passphrase }.getOrNull().orEmpty(),
            address = apAddress(),
            band = bandOf(apAddress()),
            route = "direct"
        )
    } ?: readLocalInfo()

    /** Devices currently on a Wi-Fi Direct group, when this route is in use. Empty otherwise. */
    fun groupClients(): List<String> {
        val manager = p2p ?: return emptyList()
        val ch = channel ?: return emptyList()
        var clients: List<String> = emptyList()
        val latch = java.util.concurrent.CountDownLatch(1)
        runCatching {
            manager.requestGroupInfo(ch) { found ->
                clients = found?.clientList?.mapNotNull { it.deviceAddress } ?: emptyList()
                latch.countDown()
            }
        }
        latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        return clients
    }

    /**
     * The AP's own IPv4 address, ranked: known Android hotspot gateways first, then anything that is
     * not the Wi-Fi *client* interface and not cellular, then interface-name heuristics. Vendors
     * name the interface `ap0`, `swlan0`, `softap0`, `wlan1`… so the names cannot be the primary
     * signal — but the address range usually is.
     */
    fun apAddress(): String? {
        val candidates = ArrayList<Pair<Int, String>>()
        val known = arrayOf(
            "192.168.49.1", "192.168.43.1", "192.168.232.1", "192.168.50.1", "192.168.137.1",
            "192.168.42.1", "192.168.1.1"
        )
        val station = stationAddress()
        runCatching {
            for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.orEmpty()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")) continue
                for (address in Collections.list(iface.inetAddresses)) {
                    if (address !is Inet4Address || address.isLoopbackAddress) continue
                    val host = address.hostAddress ?: continue
                    if (host == station) continue
                    val priority = when {
                        known.contains(host) -> 0
                        name.startsWith("ap") || name.startsWith("swlan") ||
                            name.startsWith("softap") || name.startsWith("wlan1") ||
                            name.startsWith("p2p") -> 1
                        known.any { it.substringBeforeLast('.') == host.substringBeforeLast('.') } -> 2
                        else -> 3
                    }
                    candidates.add(priority to host)
                }
            }
        }
        return candidates.minByOrNull { it.first }?.second
    }

    /** Every address the phone could plausibly be reached at, for the manual route's instructions. */
    fun allAddresses(): List<String> {
        val out = ArrayList<String>()
        val station = stationAddress()
        runCatching {
            for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.orEmpty()
                if (name.startsWith("rmnet") || name.startsWith("ccmni")) continue
                for (address in Collections.list(iface.inetAddresses)) {
                    if (address !is Inet4Address || address.isLoopbackAddress) continue
                    val host = address.hostAddress ?: continue
                    if (host != station) out.add(host)
                }
            }
        }
        return out.distinct()
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

    // ------------------------------------------------------------------ preconditions

    fun blocker(): Blocker? = LocationGate.blocker(
        hasLocationPermission = hasPermission(),
        locationEnabled = isLocationEnabled(),
        wifiEnabled = isWifiEnabled()
    )

    fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
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

    private fun validName(seed: String?): String = HostRoutes.name(seed)

    private fun validPass(seed: String?): String = HostRoutes.passphrase(seed)

    // ------------------------------------------------------------------ teardown

    fun stop() {
        generation++
        hosting = false
        current = null
        runCatching { reservation?.close() }
        reservation = null
        val manager = p2p
        val ch = channel
        if (manager != null && ch != null) runCatching { manager.removeGroup(ch, null) }
        group = null
        p2p = null
        channel = null
        listener = null
        routeIndex = 0
    }

    private companion object {
        const val TAG = "DropHotspot"
    }
}
