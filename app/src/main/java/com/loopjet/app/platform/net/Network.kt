package com.loopjet.app.platform.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

/**
 * Everything that decides *which* address and *which* network a socket uses.
 *
 * Rules enforced here:
 * * never advertise or bind to the mobile-data address;
 * * prefer the Wi-Fi/p2p/hotspot interface that actually carries the session;
 * * bind sockets explicitly (`Network.bindSocket`) instead of trusting the default route, because
 *   Android happily routes "no internet" Wi-Fi traffic over cellular when it feels like it.
 */
class NetworkTools(private val context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    data class Binding(val network: Network?, val address: InetAddress?, val interfaceName: String, val transport: String)

    fun activeWifiNetwork(): Network? {
        val networks = connectivity.allNetworks
        for (network in networks) {
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                return network
            }
        }
        return null
    }

    fun wifiEnabled(): Boolean = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)

    fun ssid(): String? {
        val info = runCatching { wifiManager?.connectionInfo }.getOrNull() ?: return null
        val raw = info.ssid ?: return null
        return raw.trim('"').takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    fun linkSpeedMbps(): Int = runCatching { wifiManager?.connectionInfo?.linkSpeed ?: 0 }.getOrDefault(0)

    fun frequencyMhz(): Int = runCatching { wifiManager?.connectionInfo?.frequency ?: 0 }.getOrDefault(0)

    fun bandLabel(): String = when (val f = frequencyMhz()) {
        0 -> "unknown band"
        in 2400..2500 -> "2.4 GHz"
        in 4900..5900 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "$f MHz"
    }

    /** Address + network that should be advertised to a peer. Excludes cellular. */
    fun sessionBinding(): Binding? {
        val wifi = activeWifiNetwork()
        if (wifi != null) {
            val props = connectivity.getLinkProperties(wifi)
            val address = firstIpv4(props?.linkAddresses?.map { it.address })
            if (address != null) {
                return Binding(wifi, address, props?.interfaceName ?: "wlan0", "wifi")
            }
        }
        // p2p / hotspot interfaces are not always reported as a "Network" with Wi-Fi transport.
        // Order matters: p2p0 (192.168.49.1) is a dead end for a peer on the same Wi-Fi, so a real
        // wlan interface is always tried first.
        val ordered = wifiLikeInterfaces().sortedBy {
            when {
                it.name?.startsWith("p2p") == true -> 2
                it.name?.startsWith("ap") == true || it.name?.startsWith("swlan") == true -> 1
                else -> 0
            }
        }
        for (iface in ordered) {
            val address = firstIpv4(iface.inetAddresses.toList())
            if (address != null) {
                val name = iface.name ?: "wlan"
                return Binding(null, address, name, if (name.startsWith("p2p")) "p2p" else "wifi")
            }
        }
        return null
    }

    fun allLocalAddresses(): List<Pair<String, InetAddress>> {
        val out = ArrayList<Pair<String, InetAddress>>()
        for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!iface.isUp || iface.isLoopback) continue
            for (address in Collections.list(iface.inetAddresses)) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    out.add(iface.name to address)
                }
            }
        }
        return out
    }

    fun wifiLikeInterfaces(): List<NetworkInterface> {
        val list = ArrayList<NetworkInterface>()
        for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name ?: continue
            if (name.startsWith("wlan") || name.startsWith("p2p") || name.startsWith("ap") || name.startsWith("swlan")) {
                list.add(iface)
            }
        }
        return list
    }

    fun isCellularOnly(): Boolean {
        val wifi = activeWifiNetwork() ?: return true
        val caps = connectivity.getNetworkCapabilities(wifi)
        return caps == null
    }

    /** Binds a socket to the chosen network before connecting (no silent cellular fallback). */
    fun bind(socket: Socket, network: Network?): Boolean {
        if (network == null) return false
        return runCatching { network.bindSocket(socket); true }.getOrElse { false }
    }

    fun bindProcessTo(network: Network?) {
        runCatching { connectivity.bindProcessToNetwork(network) }
    }

    fun wifiLock(): WifiManager.WifiLock? {
        val manager = wifiManager ?: return null
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                manager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "loopjet:transfer")
            } else {
                @Suppress("DEPRECATION")
                manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "loopjet:transfer")
            }.apply { setReferenceCounted(false) }
        }.getOrNull()
    }

    fun supportsWifiDirect(): Boolean {
        return runCatching {
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT) &&
                context.getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java) != null
        }.getOrDefault(false)
    }

    /**
     * True when the device can stay on normal Wi-Fi while a p2p group is active. Android exposes no
     * direct query, so we report the Wi-Fi chipset capability set and let the UI phrase it carefully.
     */
    fun supportsConcurrentWifiAndP2p(): Boolean {
        val features = context.packageManager.systemAvailableFeatures
        return features.any { it.name == "android.hardware.wifi.direct" } &&
            runCatching { wifiManager?.is5GHzBandSupported == true }.getOrDefault(false)
    }

    fun supports5Ghz(): Boolean = runCatching { wifiManager?.is5GHzBandSupported == true }.getOrDefault(false)

    fun supports6Ghz(): Boolean = runCatching {
        android.os.Build.VERSION.SDK_INT >= 30 && wifiManager?.is6GHzBandSupported == true
    }.getOrDefault(false)

    fun supportsBle(): Boolean = context.packageManager.hasSystemFeature(
        android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE
    )

    fun gateways(): List<InetAddress> {
        val wifi = activeWifiNetwork() ?: return emptyList()
        val props: LinkProperties? = connectivity.getLinkProperties(wifi)
        return props?.routes?.mapNotNull { it.gateway } ?: emptyList()
    }

    private fun firstIpv4(addresses: List<InetAddress>?): InetAddress? =
        addresses?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }

    companion object {
        private const val TAG = "FlowLinkNet"
        fun log(message: String) = Log.d(TAG, message)
    }
}
