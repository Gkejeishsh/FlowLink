package com.flowlink.drop.platform

/**
 * Why the hotspot will not start.
 *
 * Android 8–12 require **both** the location permission *and* location services switched on before
 * `startLocalOnlyHotspot` will work, and the radio must be on. Checking this before calling the API
 * matters: on many ROMs the call does not fail politely, it throws from inside the platform's own
 * handler — which is a crash, not an error message.
 *
 * Pure Kotlin so the rule can be tested without a device.
 */
enum class Blocker { LocationPermission, LocationOff, WifiOff }

object LocationGate {

    /** Ordered by what the user has to fix first. */
    fun blocker(hasLocationPermission: Boolean, locationEnabled: Boolean, wifiEnabled: Boolean): Blocker? =
        when {
            !hasLocationPermission -> Blocker.LocationPermission
            !locationEnabled -> Blocker.LocationOff
            !wifiEnabled -> Blocker.WifiOff
            else -> null
        }

    fun title(blocker: Blocker): String = when (blocker) {
        Blocker.LocationPermission -> "Allow location"
        Blocker.LocationOff -> "Turn location on"
        Blocker.WifiOff -> "Turn Wi-Fi on"
    }

    fun detail(blocker: Blocker): String = when (blocker) {
        Blocker.LocationPermission ->
            "Android will not let an app create a hotspot without the location permission. It is used " +
                "for nothing else here — no location is read, stored or sent."
        Blocker.LocationOff ->
            "The permission is granted, but location services are switched off. Android still refuses " +
                "to create a hotspot in that state. Turn it on, then come back."
        Blocker.WifiOff ->
            "A hotspot is a Wi-Fi network, so the radio has to be on. Turn Wi-Fi on, then come back."
    }
}
