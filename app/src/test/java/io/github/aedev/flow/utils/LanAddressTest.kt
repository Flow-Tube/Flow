package io.github.aedev.flow.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The QR advertises exactly one address, so a wrong pick is a connection that never arrives and a
 * bug report with nothing in it. Ranking must match the desktop's `rank_lan_candidates`.
 */
class LanAddressTest {
    private fun best(vararg interfaces: Pair<String, String>) = LanAddress.rank(interfaces.toList()).firstOrNull()?.ip

    @Test
    fun private_ranges_are_preferred_in_order() {
        assertEquals("192.168.1.42", best("eth0" to "10.0.0.5", "wlan0" to "192.168.1.42"))
        assertEquals("10.0.0.5", best("eth0" to "10.0.0.5", "eth1" to "172.20.0.5"))
        assertEquals("172.20.0.5", best("eth1" to "172.20.0.5", "eth2" to "203.0.113.7"))
    }

    @Test
    fun a_vpn_tunnel_never_wins_over_a_physical_interface() {
        // Mullvad allocates from 10.64/10, which would otherwise outrank a physical 172.20 address.
        assertEquals("172.20.0.5", best("tun0" to "10.64.0.1", "wlan0" to "172.20.0.5"))
        assertEquals("192.168.1.42", best("wg0-mullvad" to "10.64.0.1", "wlan0" to "192.168.1.42"))
        assertEquals("192.168.1.42", best("tailscale0" to "100.100.1.1", "wlan0" to "192.168.1.42"))
    }

    @Test
    fun a_virtual_192_168_loses_to_a_physical_10() {
        assertEquals("10.0.0.5", best("vboxnet0" to "192.168.56.1", "wlan0" to "10.0.0.5"))
    }

    @Test
    fun unusable_addresses_are_rejected_outright() {
        assertTrue(LanAddress.rank(listOf("lo" to "127.0.0.1")).isEmpty())
        assertTrue(LanAddress.rank(listOf("wlan0" to "169.254.3.4")).isEmpty())
        assertTrue(LanAddress.rank(listOf("wlan0" to "0.0.0.0")).isEmpty())
        assertTrue(LanAddress.rank(listOf("wlan0" to "not-an-ip")).isEmpty())
    }

    @Test
    fun a_tunnel_is_still_offered_when_it_is_all_there_is() {
        val ranked = LanAddress.rank(listOf("tun0" to "10.64.0.1"))
        assertEquals("10.64.0.1", ranked.single().ip)
        assertTrue("the interface must be reported as virtual in the diagnostics", ranked.single().virtual)
    }

    @Test
    fun equally_ranked_addresses_keep_enumeration_order() {
        val ranked = LanAddress.rank(listOf("wlan0" to "192.168.1.42", "ap0" to "192.168.43.1"))
        assertEquals(listOf("192.168.1.42", "192.168.43.1"), ranked.map { it.ip })
    }
}
