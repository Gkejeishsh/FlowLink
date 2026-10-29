package com.loopjet.app.platform

/**
 * The control port the receiving engine actually bound, or 0 when nothing is receiving.
 *
 * `ReceiverEngine.bind()` walks a range and takes the first free pair, so the port is not always the
 * default one -- after an interrupted session, or when another app holds it. Discovery used to
 * announce the constant, which meant a beacon could advertise a closed socket and the sender would
 * hang until its timeout. The beacon and mDNS record now carry this value instead.
 */
object ReceiverPort {
    @Volatile
    var current: Int = 0
        private set

    fun publish(port: Int) {
        current = port
    }

    fun clear() {
        current = 0
    }
}
