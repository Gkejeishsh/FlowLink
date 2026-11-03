package com.flowlink.drop.platform

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Notices that a device has joined, so the `Next` button can say whose phone it is.
 *
 * Android exposes no "client joined my local-only hotspot" API, so this listens for the side effects
 * of a join instead, cheapest signal first:
 *  * mDNS traffic (most iOS and Android devices announce themselves within seconds)
 *  * the ARP table, where the ROM lets us read it
 *
 * Nothing here is load-bearing: the wizard moves on a button press, and a miss costs a name, not a
 * transfer. A *false* positive is worse than a miss, so ARP entries must be seen twice in a row.
 */
class JoinWatcher(
    private val context: Context,
    private val onJoined: (address: String, source: String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val seen = HashSet<String>()
    private val candidates = HashSet<String>()
    private var prefix = ""
    private var running = false
    private var mdnsSocket: DatagramSocket? = null
    private var mdnsThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val arpPoll = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { readArp() }
            handler.postDelayed(this, 1_500)
        }
    }

    fun start(apAddress: String?) {
        if (running) return
        running = true
        prefix = apAddress?.substringBeforeLast('.') ?: ""
        handler.postDelayed(arpPoll, 800)
        startMdns()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { mdnsSocket?.close() }
        mdnsSocket = null
        mdnsThread?.interrupt()
        mdnsThread = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        seen.clear()
        candidates.clear()
    }

    private fun emit(address: String, source: String) {
        if (!running) return
        if (seen.contains(address)) return
        seen.add(address)
        Log.d(TAG, "join detected from $address via $source")
        onJoined(address, source)
    }

    // ------------------------------------------------------------------ ARP

    private fun readArp() {
        val table = File("/proc/net/arp")
        if (!table.canRead()) return
        val found = HashSet<String>()
        table.forEachLine { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 4) return@forEachLine
            val ip = parts[0]
            val flags = parts[2]
            val mac = parts[3]
            if (!ip.contains('.')) return@forEachLine
            if (flags == "0x0") return@forEachLine
            if (mac == "00:00:00:00:00:00") return@forEachLine
            if (prefix.isNotEmpty() && !ip.startsWith("$prefix.")) return@forEachLine
            found.add(ip)
        }
        // Two consecutive sightings: a single stale row is not a join.
        val confirmed = found.intersect(candidates)
        candidates.retainAll(found)
        candidates.addAll(found)
        confirmed.forEach { emit(it, "arp") }
    }

    // ------------------------------------------------------------------ mDNS

    private fun startMdns() {
        val wifi = runCatching { context.getSystemService(WifiManager::class.java) }.getOrNull()
        runCatching {
            multicastLock = wifi?.createMulticastLock("flowlink-mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        val thread = Thread({
            val multicast = try {
                java.net.MulticastSocket(5353).apply {
                    reuseAddress = true
                    soTimeout = 2_000
                }
            } catch (t: Throwable) {
                Log.d(TAG, "mDNS unavailable: ${t.message}")
                return@Thread
            }
            mdnsSocket = multicast
            val group = runCatching { InetAddress.getByName("224.0.0.251") }.getOrNull() ?: return@Thread
            runCatching { multicast.joinGroup(group) }
            val buffer = ByteArray(2_048)
            while (running) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    multicast.receive(packet)
                } catch (t: Throwable) {
                    if (!running) break
                    continue
                }
                val from = packet.address?.hostAddress ?: continue
                if (from.contains(':')) continue
                if (prefix.isNotEmpty() && !from.startsWith("$prefix.")) continue
                emit(from, "mdns")
            }
            runCatching { multicast.close() }
        }, "drop-mdns").apply { isDaemon = true }
        mdnsThread = thread
        thread.start()
    }

    private companion object {
        const val TAG = "DropJoin"
    }
}
