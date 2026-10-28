package com.loopjet.app.core

import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class BeaconInfo(
    val name: String,
    val fingerprint: String,
    val controlPort: Int,
    val sdk: Int,
    val deviceId: String,
    val capabilities: List<String>,
    val address: String,
    val rttMicros: Long = 0
) {
    fun toJson(beaconPort: Int): Map<String, Any?> = Json.obj(
        "name" to name,
        "fp" to fingerprint,
        "port" to controlPort,
        "beacon" to beaconPort,
        "sdk" to sdk,
        "id" to deviceId,
        "caps" to capabilities
    )

    companion object {
        fun fromJson(map: Map<String, Any?>, address: String, rttMicros: Long): BeaconInfo = BeaconInfo(
            name = map.str("name") ?: "Phone",
            fingerprint = map.str("fp") ?: "",
            controlPort = map.int("port") ?: ProtocolLimits.DEFAULT_PORT,
            sdk = map.int("sdk") ?: 0,
            deviceId = map.str("id") ?: "",
            capabilities = map.arr("caps").orEmpty().mapNotNull { it as? String },
            address = address,
            rttMicros = rttMicros
        )
    }
}

/**
 * Tiny discovery beacon.
 *
 * One line request, one line JSON reply, on `controlPort + 2`. It is deliberately dumber than the
 * transfer protocol: a subnet scan has to be able to identify a LoopJet phone in a few milliseconds
 * without a session, a key exchange or a user prompt.
 */
class BeaconServer(private val infoProvider: () -> BeaconInfo?) : Closeable {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val handled = AtomicInteger(0)

    var port: Int = 0
        private set

    fun bind(requestedPort: Int = 0, bindAddress: InetAddress? = null): Int {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bindAddress, requestedPort))
        server = s
        port = s.localPort
        running.set(true)
        Thread({
            while (running.get()) {
                try {
                    s.soTimeout = 500
                    val client = s.accept()
                    Thread({ respond(client) }, "lj-beacon").apply { isDaemon = true }.start()
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                    if (!running.get()) return@Thread
                }
            }
        }, "lj-beacon-accept").apply { isDaemon = true }.start()
        return port
    }

    private fun respond(client: Socket) {
        try {
            client.soTimeout = 2000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val line = reader.readLine() ?: return
            if (!line.startsWith(BeaconClient.REQUEST)) return
            val info = infoProvider() ?: return
            val writer = OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8)
            writer.write(Json.stringify(info.toJson(port)))
            writer.write("\n")
            writer.flush()
            handled.incrementAndGet()
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { server?.close() }
    }
}

class BeaconClient {
    /** Returns the peer's beacon with the measured round-trip time, or null if nothing answered. */
    fun probe(address: String, port: Int, timeoutMs: Int = 400): BeaconInfo? {
        val started = System.nanoTime()
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            writer.write("$REQUEST loopjet\n")
            writer.flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val line = reader.readLine() ?: return null
            val rtt = (System.nanoTime() - started) / 1000
            BeaconInfo.fromJson(Json.parseObject(line), address, rtt)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** Scans a /24 around [localAddress] in parallel for beacons. */
    fun scanSubnet(localAddress: InetAddress, beaconPortBase: Int, timeoutMs: Int = 260): List<BeaconInfo> {
        val raw = localAddress.address
        if (raw.size != 4) return emptyList()
        val prefix = "${raw[0].toInt() and 0xFF}.${raw[1].toInt() and 0xFF}.${raw[2].toInt() and 0xFF}"
        val found = java.util.concurrent.ConcurrentLinkedQueue<BeaconInfo>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(48)
        val latch = java.util.concurrent.CountDownLatch(254)
        for (host in 1..254) {
            val address = "$prefix.$host"
            if (raw[3].toInt() and 0xFF == host) {
                latch.countDown()
                continue
            }
            pool.execute {
                try {
                    val info = probe(address, beaconPortBase, timeoutMs)
                    if (info != null) found.add(info)
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(timeoutMs.toLong() + 250, java.util.concurrent.TimeUnit.MILLISECONDS)
        pool.shutdownNow()
        return found.toList()
    }

    companion object {
        const val REQUEST = "LJP?"
    }
}

/** Best-effort UDP multicast announce/listen used when mDNS is blocked by the router. */
class MulticastBeacon(
    private val groupAddress: String = "239.255.72.200",
    private val port: Int = 43120
) : Closeable {
    private val running = AtomicBoolean(false)
    private var socket: java.net.MulticastSocket? = null
    private var listener: ((Map<String, Any?>, String) -> Unit)? = null

    fun start(bindAddress: InetAddress?, onAnnounce: (Map<String, Any?>, String) -> Unit) {
        listener = onAnnounce
        val group = InetAddress.getByName(groupAddress)
        val s = java.net.MulticastSocket(port)
        s.reuseAddress = true
        if (bindAddress != null) s.networkInterface = java.net.NetworkInterface.getByInetAddress(bindAddress)
        runCatching { s.joinGroup(group) }
        socket = s
        running.set(true)
        Thread({
            val buffer = ByteArray(4096)
            while (running.get()) {
                try {
                    val packet = java.net.DatagramPacket(buffer, buffer.size)
                    s.soTimeout = 700
                    s.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val map = runCatching { Json.parseObject(text) }.getOrNull() ?: continue
                    onAnnounce(map, packet.address.hostAddress ?: "")
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                    if (!running.get()) return@Thread
                }
            }
        }, "lj-multicast").apply { isDaemon = true }.start()
    }

    fun announce(payload: Map<String, Any?>) {
        val s = socket ?: return
        try {
            val group = InetAddress.getByName(groupAddress)
            val bytes = Json.stringify(payload).toByteArray(Charsets.UTF_8)
            s.send(java.net.DatagramPacket(bytes, bytes.size, group, port))
        } catch (_: Exception) {
        }
    }

    override fun close() {
        running.set(false)
        runCatching { socket?.close() }
    }
}
