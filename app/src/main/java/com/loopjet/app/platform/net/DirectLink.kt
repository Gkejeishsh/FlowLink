package com.loopjet.app.platform.net

import android.util.Log
import com.loopjet.app.platform.LoopJetApp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Wi-Fi Direct plumbing that turns "a p2p device was discovered" into "there is an IP address to
 * connect to".
 *
 * The negotiation is deliberately one-directional: the *receiving* phone creates the group (it is
 * already showing "Receive"), so the sending phone always joins as a client and receives the group
 * owner's address through [P2pController.requestConnectionInfo]. If the roles end up reversed the join
 * is not silently retried — it is reported, because guessing an address would mean failing later with
 * a mystery timeout.
 */
object DirectLink {
    private const val TAG = "LoopJetDirect"
    const val JOIN_TIMEOUT_MS = 22_000L

    data class Result(
        val address: String?,
        val reason: String,
        val becameGroupOwner: Boolean = false
    ) {
        val ok: Boolean get() = address != null
    }

    /**
     * Joins the group hosted by [deviceAddress]. Runs on its own thread and reports on it, so it can be
     * called from a service worker without ever touching the main thread's looper.
     */
    fun join(deviceAddress: String, timeoutMs: Long = JOIN_TIMEOUT_MS, onResult: (Result) -> Unit) {
        Thread({
            val controller = P2pController(LoopJetApp.context)
            try {
                controller.initialize()
                if (!controller.isSupported) {
                    onResult(Result(null, "Wi-Fi Direct is not available on this phone"))
                    return@Thread
                }
                val finished = AtomicBoolean(false)
                val failure = AtomicReference<String?>(null)
                controller.connect(deviceAddress, P2pController.BAND_5) { ok, message ->
                    if (!ok) failure.set(message)
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline && !finished.get()) {
                    failure.get()?.let {
                        onResult(Result(null, it))
                        finished.set(true)
                        return@Thread
                    }
                    val latch = CountDownLatch(1)
                    val address = AtomicReference<String?>(null)
                    val owner = AtomicBoolean(false)
                    controller.requestConnectionInfo { ip, isGroupOwner ->
                        address.set(ip)
                        owner.set(isGroupOwner)
                        latch.countDown()
                    }
                    runCatching { latch.await(1200, TimeUnit.MILLISECONDS) }
                    val ip = address.get()
                    if (!ip.isNullOrBlank()) {
                        if (owner.get()) {
                            onResult(
                                Result(
                                    null,
                                    "This phone became the Wi-Fi Direct host. Open LoopJet → Receive on " +
                                        "the other phone first, then try again.",
                                    becameGroupOwner = true
                                )
                            )
                        } else {
                            Log.d(TAG, "joined group, owner address $ip")
                            onResult(Result(ip, "Wi-Fi Direct group ready"))
                        }
                        finished.set(true)
                        return@Thread
                    }
                    Thread.sleep(350)
                }
                onResult(Result(null, "Wi-Fi Direct did not connect in time"))
            } catch (t: Throwable) {
                onResult(Result(null, t.message ?: "Wi-Fi Direct failed"))
            } finally {
                runCatching { controller.stop() }
            }
        }, "lj-p2p-join").apply { isDaemon = true }.start()
    }

    /** Creates the group on the receiving phone, preferring 5 GHz and reporting the band it got. */
    fun host(onResult: (Boolean, String) -> Unit) {
        val controller = P2pController(LoopJetApp.context)
        controller.initialize()
        if (!controller.isSupported) {
            onResult(false, "Wi-Fi Direct is not available")
            return
        }
        controller.createGroup(P2pController.BAND_5) { ok, message ->
            if (!ok) {
                onResult(false, message)
                return@createGroup
            }
            val latch = CountDownLatch(1)
            val label = AtomicReference("unknown band")
            controller.requestGroupInfo { state ->
                if (state != null) label.set(state.bandLabel)
                latch.countDown()
            }
            Thread({
                runCatching { latch.await(3, TimeUnit.SECONDS) }
                onResult(true, label.get())
                runCatching { controller.stop() }
            }, "lj-p2p-band").apply { isDaemon = true }.start()
        }
    }

    /** Removes the group so no hotspot, interface or power draw is left behind. */
    fun teardown(onDone: () -> Unit = {}) {
        val controller = P2pController(LoopJetApp.context)
        controller.initialize()
        controller.removeGroup {
            runCatching { controller.stop() }
            onDone()
        }
    }
}
