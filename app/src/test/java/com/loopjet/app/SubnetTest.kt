package com.loopjet.app

import com.loopjet.app.platform.net.Subnet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verification behind the "Same Wi-Fi" mode: the green tick has to mean something.
 *
 * These cases come from the failures users actually hit — a guest network, a phone still holding a
 * hotspot address, an address that never was IPv4.
 */
class SubnetTest {

    private val phone = listOf("192.168.1.7", "10.0.0.12", "fe80::1")

    @Test
    fun theSameSlash24IsAVerifiedMatch() {
        assertTrue(Subnet.sharesNetwork(phone, "192.168.1.23"))
        assertTrue(Subnet.sharesNetwork(phone, "10.0.0.9"))
    }

    @Test
    fun aDifferentNetworkIsNotAMatch() {
        // Both phones are on Wi-Fi. That never meant they are on the same Wi-Fi.
        assertFalse(Subnet.sharesNetwork(phone, "192.168.43.1"))
        assertFalse(Subnet.sharesNetwork(phone, "192.168.2.23"))
        assertFalse(Subnet.sharesNetwork(listOf("192.168.1.7"), "192.168.10.7"))
    }

    @Test
    fun junkIsNeverVerified() {
        assertFalse(Subnet.sharesNetwork(phone, ""))
        assertFalse(Subnet.sharesNetwork(phone, "not-an-address"))
        assertFalse(Subnet.sharesNetwork(phone, "192.168.1"))
        assertFalse(Subnet.sharesNetwork(phone, "192.168.1.999"))
        assertFalse(Subnet.sharesNetwork(emptyList(), "192.168.1.23"))
    }

    @Test
    fun ipv6HasNoIpv4Prefix() {
        // The phone's interface list contains IPv6 addresses too; they must not match anything.
        assertEquals("", Subnet.prefixOf("fe80::1"))
        assertEquals("", Subnet.prefixOf("2001:db8::5"))
        assertEquals("192.168.43", Subnet.prefixOf("192.168.43.1"))
    }
}
