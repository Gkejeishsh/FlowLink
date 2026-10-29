package com.loopjet.app.platform.net

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.loopjet.app.core.BeaconClient
import com.loopjet.app.core.BeaconInfo
import com.loopjet.app.core.Bytes
import com.loopjet.app.core.Json
import com.loopjet.app.core.MulticastBeacon
import com.loopjet.app.core.ProtocolLimits
import com.loopjet.app.core.int
import com.loopjet.app.core.str
import com.loopjet.app.platform.Prefs
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class PeerKind { LAN, DIRECT, HOTSPOT, BLE }

data class DiscoveredPeer(
    val id: String,
    val name: String,
    val fingerprint: String,
    val address: String,
    val controlPort: Int,
    val beaconPort: Int,
    val rttMicros: Long,
    val kind: PeerKind,
    val sdk: Int,
    val capabilities: List<String>,
    val p2pDeviceAddress: String? = null,
    val lastSeen: Long = System.currentTimeMillis()
) {
    val rttMillis: Double get() = rttMicros / 1000.0
}

interface DiscoveryListener {
    fun onPeersChanged(peers: List<DiscoveredPeer>)
    fun onDiscoveryLog(message: String)
}

/**
 * Finds other FlowLink phones. Four independent channels, because any single one fails often:
 * mDNS (NsdManager), UDP multicast, a parallel subnet scan that identifies peers through the beacon
 * port, Wi-Fi Direct peer discovery and a BLE advert for the no-network case.
 */
class DiscoveryEngine(
    private val context: Context,
    private val prefs: Prefs,
    private val tools: NetworkTools,
    private val listener: DiscoveryListener
) {
    private val peers = ConcurrentHashMap<String, DiscoveredPeer>()
    private val running = AtomicBoolean(false)
    @Volatile private var pool = newPool()
    private var multicast: MulticastBeacon? = null
    private var nsd: NsdHelper? = null
    private val beaconClient = BeaconClient()
    private val p2p = P2pController(context)
    private val ble = BleBeacon(context)
    private val fingerprintProvider: () -> String = { com.loopjet.app.platform.FlowLinkApp.instance.identity.fingerprintHex }

    private var advertise = false
    private var controlPort = ProtocolLimits.DEFAULT_PORT
    private var beaconPort = ProtocolLimits.BEACON_PORT

    private fun newPool(): java.util.concurrent.ScheduledExecutorService =
        Executors.newScheduledThreadPool(4)

    fun start(advertise: Boolean, controlPort: Int, beaconPort: Int) {
        if (!running.compareAndSet(false, true)) return
        // The pool is shut down by stop(); reusing it would throw RejectedExecutionException.
        if (pool.isShutdown) pool = newPool()
        this.advertise = advertise
        this.controlPort = controlPort
        this.beaconPort = beaconPort
        val binding = tools.sessionBinding()
        val localAddress = binding?.address
        if (advertise) {
            val info = BeaconInfo(
                name = prefs.deviceName,
                fingerprint = fingerprintProvider(),
                controlPort = controlPort,
                sdk = Build.VERSION.SDK_INT,
                deviceId = prefs.deviceId(),
                capabilities = capabilities(),
                address = localAddress?.hostAddress ?: ""
            )
            val mc = MulticastBeacon()
            runCatching { mc.start(localAddress) { map, from -> handleAnnounce(map, from) } }
            multicast = mc
            // 900 ms, not 1.5 s: a sender that opens the peer list was staring at "searching" for up
            // to two announce periods before the other phone even appeared.
            pool.scheduleAtFixedRate({
                if (!running.get()) return@scheduleAtFixedRate
                mc.announce(info.toJson(beaconPort))
            }, 0, 900, java.util.concurrent.TimeUnit.MILLISECONDS)
            val helper = NsdHelper(context, prefs.deviceName, controlPort, fingerprintProvider())
            runCatching { helper.register() }
            nsd = helper
            if (tools.supportsBle()) {
                runCatching { ble.startAdvertising(prefs.deviceName, localAddress?.hostAddress, controlPort) }
            }
        } else {
            val helper = NsdHelper(context, prefs.deviceName, controlPort, fingerprintProvider())
            runCatching { helper.discover { discovered -> onNsdPeer(discovered) } }
            nsd = helper
            val mc = MulticastBeacon()
            runCatching { mc.start(localAddress) { map, from -> handleAnnounce(map, from) } }
            multicast = mc
            mc.announce(Json.obj("probe" to true, "id" to prefs.deviceId()))
            if (tools.supportsBle()) {
                runCatching { ble.startScan { name, address, port -> onBlePeer(name, address, port) } }
            }
        }
        if (tools.supportsWifiDirect()) {
            p2p.startDiscovery { device -> onP2pPeer(device) }
        }
        pool.scheduleWithFixedDelay({ scan() }, 150, 2500, java.util.concurrent.TimeUnit.MILLISECONDS)
        pool.scheduleWithFixedDelay({ publish() }, 0, 400, java.util.concurrent.TimeUnit.MILLISECONDS)
        pool.scheduleWithFixedDelay({ sweep() }, 2, 2, java.util.concurrent.TimeUnit.SECONDS)
        listener.onDiscoveryLog("discovery started (advertise=$advertise) on ${localAddress?.hostAddress ?: "no address"}")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { multicast?.close() }
        runCatching { nsd?.stop() }
        runCatching { p2p.stop() }
        runCatching { ble.stop() }
        pool.shutdownNow()
        peers.clear()
        listener.onPeersChanged(emptyList())
    }

    fun caps(): List<String> = capabilities()

    private fun capabilities(): List<String> = buildList {
        add("resume")
        add("bundles")
        add("aes-gcm")
        if (tools.supportsWifiDirect()) add("p2p")
        if (tools.supports5Ghz()) add("5ghz")
        if (tools.supports6Ghz()) add("6ghz")
        if (tools.supportsBle()) add("ble")
        add("sdk" + Build.VERSION.SDK_INT)
    }

    fun peersSnapshot(): List<DiscoveredPeer> = peers.values.sortedBy { it.rttMicros }

    private fun publish() {
        listener.onPeersChanged(peersSnapshot())
    }

    private fun sweep() {
        val cutoff = System.currentTimeMillis() - 15_000
        peers.entries.removeIf { it.value.lastSeen < cutoff && it.value.kind != PeerKind.DIRECT }
    }

    private fun scan() {
        // A phone that is advertising for senders has nothing to scan for.
        if (!running.get() || advertise) return
        val address = tools.sessionBinding()?.address ?: return
        val executor = pool
        if (executor.isShutdown) return
        runCatching {
            executor.execute {
                val found = beaconClient.scanSubnet(address, ProtocolLimits.BEACON_PORT)
                for (info in found) merge(peerFrom(info, PeerKind.LAN))
            }
        }
    }

    private fun peerFrom(info: BeaconInfo, kind: PeerKind, p2pAddress: String? = null) = DiscoveredPeer(
        id = info.deviceId.ifBlank { info.fingerprint },
        name = info.name.ifBlank { "Phone" },
        fingerprint = info.fingerprint,
        address = info.address,
        controlPort = info.controlPort,
        beaconPort = ProtocolLimits.BEACON_PORT,
        rttMicros = info.rttMicros,
        kind = kind,
        sdk = info.sdk,
        capabilities = info.capabilities,
        p2pDeviceAddress = p2pAddress
    )

    /**
     * Manual pairing, part 1: the six digits on the receiving phone. A code probe is sent to every
     * address in the /24; only the phone displaying that code answers, so no multicast, mDNS or
     * firewall cooperation is required.
     */
    fun pairWithCode(code: String, onResult: (DiscoveredPeer?, String) -> Unit) {
        val executor = pool
        if (executor.isShutdown) {
            onResult(null, "Discovery is not running")
            return
        }
        runCatching {
            executor.execute {
                val address = tools.sessionBinding()?.address
                if (address == null) {
                    onResult(null, "Connect to Wi-Fi first, or use the hotspot option")
                    return@execute
                }
                val found = beaconClient.scanSubnet(address, ProtocolLimits.BEACON_PORT, 350, code)
                val first = found.firstOrNull()
                if (first == null) {
                    onResult(null, "No phone on this Wi-Fi is showing $code")
                } else {
                    val peer = peerFrom(first, PeerKind.LAN)
                    merge(peer)
                    onResult(peer, "Found ${first.name}")
                }
            }
        }.onFailure { onResult(null, "Discovery is not running") }
    }

    /**
     * Manual pairing, part 2: the link inside the receiver's QR code
     * (`loopjet://ip:port#fingerprint#device`). Pasting it works when a network scan is blocked.
     */
    fun pairWithLink(link: String, onResult: (DiscoveredPeer?, String) -> Unit) {
        val executor = pool
        if (executor.isShutdown) {
            onResult(null, "Discovery is not running")
            return
        }
        runCatching {
            executor.execute {
                val parsed = parseLink(link)
                if (parsed == null) {
                    onResult(null, "That does not look like a FlowLink link")
                    return@execute
                }
                val (host, port, fingerprint) = parsed
                // Ask the beacon for the live port and name; fall back to the pasted values so a
                // firewall that blocks new connections still gets a chance to fail loudly.
                val info = beaconClient.probe(host, ProtocolLimits.BEACON_PORT, 700)
                val peer = DiscoveredPeer(
                    id = info?.deviceId?.ifBlank { fingerprint } ?: fingerprint,
                    name = info?.name ?: "Phone at $host",
                    fingerprint = info?.fingerprint ?: fingerprint,
                    address = host,
                    controlPort = info?.controlPort ?: port,
                    beaconPort = ProtocolLimits.BEACON_PORT,
                    rttMicros = info?.rttMicros ?: 0,
                    kind = PeerKind.LAN,
                    sdk = info?.sdk ?: 0,
                    capabilities = info?.capabilities ?: emptyList()
                )
                merge(peer)
                onResult(peer, "Found ${peer.name}")
            }
        }.onFailure { onResult(null, "Discovery is not running") }
    }

    /** Accepts `loopjet://ip:port#fingerprint#device` with or without the device segment. */
    private fun parseLink(link: String): Triple<String, Int, String>? {
        val trimmed = link.trim()
        if (!trimmed.startsWith("loopjet://")) return null
        val body = trimmed.removePrefix("loopjet://")
        val hostPort = body.substringBefore('#')
        val rest = if (body.contains('#')) body.substringAfter('#') else ""
        if (!hostPort.contains(':')) return null
        val host = hostPort.substringBefore(':').trim()
        val port = hostPort.substringAfter(':').substringBefore('/').toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        val fingerprint = rest.substringBefore('#').trim()
        return Triple(host, port, fingerprint)
    }

    /**
     * Keeps one entry per phone, with the *reachable* address.
     *
     * A sighting can be probe-verified (a beacon answered at that address, so it is definitely
     * routable back) or merely claimed (BLE hands over whatever address the other phone put in its
     * advert, which is routinely its hotspot address or a stale Wi-Fi address). Last-writer-wins let
     * an unverified claim overwrite a verified address seconds before the user tapped the peer, and
     * the dial then went nowhere. A verified address is now only replaced by another verified one.
     */
    private fun merge(peer: DiscoveredPeer) {
        if (peer.fingerprint.isNotEmpty() && peer.fingerprint == fingerprintProvider()) return
        val existing = peers[peer.id]
        if (existing == null) {
            peers[peer.id] = peer.copy(lastSeen = System.currentTimeMillis())
            return
        }
        val keepAddress = existing.rttMicros > 0 && peer.rttMicros <= 0
        peers[peer.id] = when {
            // Keep the peer's real address, refresh everything that identifies it.
            keepAddress -> existing.copy(
                name = peer.name.ifBlank { existing.name },
                fingerprint = peer.fingerprint.ifBlank { existing.fingerprint },
                lastSeen = System.currentTimeMillis()
            )
            existing.kind == PeerKind.DIRECT && peer.kind == PeerKind.LAN ->
                peer.copy(p2pDeviceAddress = existing.p2pDeviceAddress, lastSeen = System.currentTimeMillis())
            else -> peer.copy(lastSeen = System.currentTimeMillis())
        }
    }

    private fun handleAnnounce(map: Map<String, Any?>, from: String) {
        val fingerprint = map.str("fp") ?: return
        if (fingerprint == fingerprintProvider()) return
        if (map["probe"] == true) return
        val info = BeaconInfo(
            name = map.str("name") ?: "Phone",
            fingerprint = fingerprint,
            controlPort = map.int("port") ?: ProtocolLimits.DEFAULT_PORT,
            sdk = map.int("sdk") ?: 0,
            deviceId = map.str("id") ?: fingerprint,
            capabilities = (map["caps"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            address = from
        )
        val beacon = map.int("beacon") ?: ProtocolLimits.BEACON_PORT
        val probed = beaconClient.probe(from, beacon, 300)
        merge(
            DiscoveredPeer(
                id = info.deviceId,
                name = info.name,
                fingerprint = info.fingerprint,
                address = from,
                controlPort = info.controlPort,
                beaconPort = beacon,
                rttMicros = probed?.rttMicros ?: 0,
                kind = PeerKind.LAN,
                sdk = info.sdk,
                capabilities = info.capabilities
            )
        )
    }

    private fun onNsdPeer(info: NsdHelper.PeerService) {
        val fingerprint = info.fingerprint ?: return
        if (fingerprint == fingerprintProvider()) return
        val host = info.host ?: return
        val probed = beaconClient.probe(host, ProtocolLimits.BEACON_PORT, 300)
        merge(
            DiscoveredPeer(
                id = fingerprint,
                name = info.name,
                fingerprint = fingerprint,
                address = host,
                controlPort = info.port,
                beaconPort = ProtocolLimits.BEACON_PORT,
                rttMicros = probed?.rttMicros ?: 0,
                kind = PeerKind.LAN,
                sdk = 0,
                capabilities = emptyList()
            )
        )
    }

    private fun onBlePeer(name: String, address: String?, port: Int) {
        val host = address ?: return
        val info = beaconClient.probe(host, ProtocolLimits.BEACON_PORT, 400)
        merge(
            DiscoveredPeer(
                id = info?.deviceId ?: "$host:$port",
                name = info?.name ?: name,
                fingerprint = info?.fingerprint ?: "",
                address = host,
                controlPort = info?.controlPort ?: port,
                beaconPort = ProtocolLimits.BEACON_PORT,
                rttMicros = info?.rttMicros ?: 0,
                kind = PeerKind.BLE,
                sdk = info?.sdk ?: 0,
                capabilities = info?.capabilities ?: emptyList()
            )
        )
    }

    private fun onP2pPeer(device: WifiP2pDevice) {
        merge(
            DiscoveredPeer(
                id = "p2p:${device.deviceAddress}",
                name = device.deviceName,
                fingerprint = "",
                address = device.deviceAddress ?: "",
                controlPort = ProtocolLimits.DEFAULT_PORT,
                beaconPort = ProtocolLimits.BEACON_PORT,
                rttMicros = Long.MAX_VALUE / 2,
                kind = PeerKind.DIRECT,
                sdk = 0,
                capabilities = listOf("p2p"),
                p2pDeviceAddress = device.deviceAddress
            )
        )
    }

    fun p2pController(): P2pController = p2p
}

/** mDNS registration + browsing through NsdManager (no third-party library). */
class NsdHelper(
    private val context: Context,
    private val name: String,
    private val port: Int,
    private val fingerprint: String
) {
    data class PeerService(val name: String, val host: String?, val port: Int, val fingerprint: String?)

    private var nsd: android.net.nsd.NsdManager? = context.getSystemService(android.net.nsd.NsdManager::class.java)
    private var registration: android.net.nsd.NsdManager.RegistrationListener? = null
    private var discovery: android.net.nsd.NsdManager.DiscoveryListener? = null

    fun register() {
        val manager = nsd ?: return
        val info = android.net.nsd.NsdServiceInfo().apply {
            serviceName = "FlowLink-$name"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("fp", fingerprint)
            setAttribute("name", name)
        }
        val listener = object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: android.net.nsd.NsdServiceInfo) {}
            override fun onRegistrationFailed(info: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: android.net.nsd.NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { manager.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener) }
        registration = listener
    }

    fun discover(onPeer: (PeerService) -> Unit) {
        val manager = nsd ?: return
        val listener = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(info: android.net.nsd.NsdServiceInfo) {
                if (info.serviceType != SERVICE_TYPE) return
                runCatching {
                    manager.resolveService(info, object : android.net.nsd.NsdManager.ResolveListener {
                        override fun onServiceResolved(resolved: android.net.nsd.NsdServiceInfo) {
                            val host = if (Build.VERSION.SDK_INT >= 34) {
                                resolved.hostAddresses.firstOrNull()?.hostAddress
                            } else {
                                @Suppress("DEPRECATION")
                                resolved.host?.hostAddress
                            }
                            val attributes = resolved.attributes ?: emptyMap()
                            val fp = attributes["fp"]?.let { String(it, Charsets.UTF_8) }
                            val peerName = attributes["name"]?.let { String(it, Charsets.UTF_8) }
                                ?: resolved.serviceName
                            onPeer(PeerService(peerName, host, resolved.port, fp))
                        }

                        override fun onResolveFailed(info: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
                    })
                }
            }

            override fun onServiceLost(info: android.net.nsd.NsdServiceInfo) {}
        }
        runCatching { manager.discoverServices(SERVICE_TYPE, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener) }
        discovery = listener
    }

    fun stop() {
        val manager = nsd
        registration?.let { runCatching { manager?.unregisterService(it) } }
        discovery?.let { runCatching { manager?.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
    }

    companion object {
        const val SERVICE_TYPE = "_loopjet._tcp."
    }
}

/**
 * Wi-Fi Direct controller.
 *
 * Band ladder: 6 GHz (API 36+) → 5 GHz (API 29+) → automatic. Every request is reported back with
 * the *actual* frequency the group got, because asking for 5 GHz is not the same as getting it.
 */
@SuppressLint("MissingPermission")
class P2pController(private val context: Context) {
    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: android.content.BroadcastReceiver? = null
    private var discoveryListener: ((WifiP2pDevice) -> Unit)? = null

    data class GroupState(
        val isGroupOwner: Boolean,
        val ownerAddress: String?,
        val frequency: Int,
        val ssid: String?,
        val passphrase: String?
    ) {
        val bandLabel: String
            get() = when (frequency) {
                in 2400..2500 -> "2.4 GHz"
                in 4900..5900 -> "5 GHz"
                in 5925..7125 -> "6 GHz"
                else -> "unknown band"
            }
    }

    val isSupported: Boolean get() = manager != null && channel != null

    fun initialize() {
        val m = manager ?: return
        if (channel != null) return
        channel = runCatching { m.initialize(context, Looper.getMainLooper(), null) }.getOrNull()
        if (channel == null) {
            Log.w("FlowLinkP2p", "WifiP2pManager.initialize failed")
            return
        }
        val filter = android.content.IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestGroupInfo { }
                }
            }
        }
        receiver = r
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun startDiscovery(onPeer: (WifiP2pDevice) -> Unit) {
        initialize()
        discoveryListener = onPeer
        val m = manager ?: return
        val c = channel ?: return
        runCatching { m.discoverPeers(c, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d("FlowLinkP2p", "peer discovery started") }
            override fun onFailure(reason: Int) { Log.d("FlowLinkP2p", "peer discovery failed: $reason") }
        }) }
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val m = manager ?: return
        val c = channel ?: return
        runCatching {
            m.requestPeers(c) { list ->
                list.deviceList.forEach { device -> discoveryListener?.invoke(device) }
            }
        }
    }

    fun requestGroupInfo(onResult: (GroupState?) -> Unit) {
        val m = manager ?: return
        val c = channel ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                m.requestGroupInfo(c) { group ->
                    if (group == null) {
                        onResult(null)
                    } else {
                        onResult(
                            GroupState(
                                isGroupOwner = group.isGroupOwner,
                                ownerAddress = group.owner?.deviceAddress,
                                frequency = if (Build.VERSION.SDK_INT >= 29) group.frequency else 0,
                                ssid = if (Build.VERSION.SDK_INT >= 30) runCatching { group.networkName }.getOrNull() else null,
                                passphrase = if (Build.VERSION.SDK_INT >= 30) runCatching { group.passphrase }.getOrNull() else null
                            )
                        )
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                m.requestGroupInfo(c) { group ->
                    if (group == null) {
                        onResult(null)
                    } else {
                        onResult(
                            GroupState(
                                isGroupOwner = group.isGroupOwner,
                                ownerAddress = group.owner?.deviceAddress,
                                // getFrequency() is API 29+, the name/passphrase getters API 30+.
                                frequency = if (Build.VERSION.SDK_INT >= 29) group.frequency else 0,
                                ssid = if (Build.VERSION.SDK_INT >= 30) runCatching { group.networkName }.getOrNull() else null,
                                passphrase = if (Build.VERSION.SDK_INT >= 30) runCatching { group.passphrase }.getOrNull() else null
                            )
                        )
                    }
                }
            }
        }.onFailure { onResult(null) }
    }

    /** Creates a group, asking for the fastest band the platform allows. */
    fun createGroup(preferBand: Int, onResult: (Boolean, String) -> Unit) {
        initialize()
        val m = manager ?: return onResult(false, "Wi-Fi Direct is not available")
        val c = channel ?: return onResult(false, "Wi-Fi Direct is not available")
        val bands = buildList {
            if (preferBand == BAND_AUTO) {
                if (Build.VERSION.SDK_INT >= 36) add(BAND_6)
                if (Build.VERSION.SDK_INT >= 29) add(BAND_5)
            } else {
                add(preferBand)
            }
            add(BAND_AUTO)
        }
        fun attempt(index: Int) {
            if (index >= bands.size) return onResult(false, "Could not create a Wi-Fi Direct group")
            val band = bands[index]
            val config = if (Build.VERSION.SDK_INT >= 29) {
                val builder = WifiP2pConfig.Builder()
                when (band) {
                    // 6 GHz needs both a supporting phone and API 36; otherwise ask for automatic.
                    BAND_6 -> if (Build.VERSION.SDK_INT >= 36) {
                        builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_6GHZ)
                    } else {
                        builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                    }
                    BAND_5 -> builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                    else -> builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                }
                builder.build()
            } else {
                WifiP2pConfig()
            }
            runCatching {
                m.createGroup(c, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = onResult(true, "Group created")
                    override fun onFailure(reason: Int) = attempt(index + 1)
                })
            }.onFailure { attempt(index + 1) }
        }
        attempt(0)
    }

    @SuppressLint("MissingPermission")
    fun connect(deviceAddress: String, preferBand: Int, onResult: (Boolean, String) -> Unit) {
        initialize()
        val m = manager ?: return onResult(false, "Wi-Fi Direct is not available")
        val c = channel ?: return onResult(false, "Wi-Fi Direct is not available")
        // A malformed address throws inside the Builder, so the legacy path stays available as a
        // fallback rather than turning a pairing attempt into a crash.
        val config = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val builder = WifiP2pConfig.Builder().setDeviceAddress(android.net.MacAddress.fromString(deviceAddress))
                when (preferBand) {
                    BAND_5 -> builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                    BAND_6 -> if (Build.VERSION.SDK_INT >= 36) builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_6GHZ) else builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                    else -> builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                }
                builder.build()
            } else {
                WifiP2pConfig().apply {
                    this.deviceAddress = deviceAddress
                }
            }
        }.getOrElse {
            @Suppress("DEPRECATION")
            WifiP2pConfig().apply { this.deviceAddress = deviceAddress }
        }
        runCatching {
            m.connect(c, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = onResult(true, "Joining group")
                override fun onFailure(reason: Int) = onResult(false, "Wi-Fi Direct connect failed ($reason)")
            })
        }.onFailure { onResult(false, it.message ?: "connect failed") }
    }

    @SuppressLint("MissingPermission")
    fun requestConnectionInfo(onResult: (String?, Boolean) -> Unit) {
        val m = manager ?: return onResult(null, false)
        val c = channel ?: return onResult(null, false)
        runCatching {
            m.requestConnectionInfo(c) { info ->
                onResult(info?.groupOwnerAddress?.hostAddress, info?.isGroupOwner == true)
            }
        }.onFailure { onResult(null, false) }
    }

    fun removeGroup(onDone: () -> Unit = {}) {
        val m = manager ?: return onDone()
        val c = channel ?: return onDone()
        runCatching {
            m.removeGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = onDone()
                override fun onFailure(reason: Int) = onDone()
            })
        }.getOrElse { onDone() }
    }

    fun stop() {
        runCatching { manager?.stopPeerDiscovery(channel, null) }
        runCatching { receiver?.let { context.unregisterReceiver(it) } }
        receiver = null
        channel = null
    }

    companion object {
        const val BAND_AUTO = 0
        const val BAND_5 = 1
        const val BAND_6 = 2
    }
}

/**
 * BLE presence. The advert carries just enough to bootstrap: name, IPv4 address and control port, so
 * a phone with no shared network can still be found and then paired over Wi-Fi Direct or a hotspot.
 */
class BleBeacon(private val context: Context) {
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun canUse(): Boolean {
        if (adapter == null || !adapter.isEnabled) return false
        val permission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_ADVERTISE else Manifest.permission.BLUETOOTH
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(name: String, address: String?, port: Int) {
        if (!canUse() || address == null) return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val payload = Json.stringify(Json.obj("n" to name.take(12), "h" to address, "p" to port)).toByteArray()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addManufacturerData(MANUFACTURER_ID, payload.copyOf(minOf(payload.size, 24)))
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) { Log.d("FlowLinkBle", "advertise failed $errorCode") }
        }
        runCatching { advertiser.startAdvertising(settings, data, callback) }
        advertiseCallback = callback
    }

    @SuppressLint("MissingPermission")
    fun startScan(onPeer: (String, String?, Int) -> Unit) {
        if (!canUse()) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val record = result?.scanRecord ?: return
                val raw = record.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
                val text = String(raw, Charsets.UTF_8)
                val map = runCatching { Json.parseObject(text) }.getOrNull() ?: return
                onPeer(map.str("n") ?: "Phone", map.str("h"), map.int("p") ?: ProtocolLimits.DEFAULT_PORT)
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(null, settings, callback) }
        scanCallback = callback
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { advertiseCallback?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        runCatching { scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) } }
        advertiseCallback = null
        scanCallback = null
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6c1f9a70-4d2a-4f2b-9b21-6c0f5f4a7d10")
        const val MANUFACTURER_ID = 0x0A11
    }
}
