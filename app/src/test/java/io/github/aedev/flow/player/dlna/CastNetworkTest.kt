package io.github.aedev.flow.player.dlna

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CastNetworkTest {
    private val hotspotHost =
        listOf(
            LocalIpv4("lo", "127.0.0.1", 8, loopback = true),
            LocalIpv4("rmnet_data0", "10.142.7.21", 30),
            LocalIpv4("v4-rmnet_data0", "192.0.0.4", 32),
            LocalIpv4("ap0", "192.168.43.1", 24),
        )

    private val wifiClient =
        listOf(
            LocalIpv4("lo", "127.0.0.1", 8, loopback = true),
            LocalIpv4("rmnet_data0", "10.142.7.21", 30),
            LocalIpv4("wlan0", "192.168.1.37", 24),
        )

    @Test
    fun `a phone sharing its hotspot searches on the hotspot, not mobile data`() {
        assertThat(CastNetwork.discoveryInterfaces(hotspotHost).map { it.interfaceName }).containsExactly("ap0")
    }

    @Test
    fun `a tv on the hotspot gets the hotspot address`() {
        assertThat(CastNetwork.advertisedHostFor("192.168.43.57", hotspotHost)).isEqualTo("192.168.43.1")
    }

    @Test
    fun `a wifi client searches and streams on wifi`() {
        assertThat(CastNetwork.discoveryInterfaces(wifiClient).map { it.interfaceName }).containsExactly("wlan0")
        assertThat(CastNetwork.advertisedHostFor("192.168.1.20", wifiClient)).isEqualTo("192.168.1.37")
    }

    @Test
    fun `wifi and hotspot at once search both and stream on the tv's subnet`() {
        val both = wifiClient + LocalIpv4("wlan1", "192.168.43.1", 24)

        assertThat(CastNetwork.discoveryInterfaces(both).map { it.interfaceName }).containsExactly("wlan0", "wlan1")
        assertThat(CastNetwork.advertisedHostFor("192.168.43.57", both)).isEqualTo("192.168.43.1")
        assertThat(CastNetwork.advertisedHostFor("192.168.1.20", both)).isEqualTo("192.168.1.37")
    }

    @Test
    fun `a vpn is never searched and never advertised`() {
        val withVpn = listOf(LocalIpv4("tun0", "10.64.0.1", 32)) + wifiClient

        assertThat(CastNetwork.discoveryInterfaces(withVpn).map { it.interfaceName }).containsExactly("wlan0")
        assertThat(CastNetwork.advertisedHostFor("192.168.1.20", withVpn)).isEqualTo("192.168.1.37")
    }

    @Test
    fun `a renderer off every subnet gets the address its answer arrived on`() {
        assertThat(CastNetwork.advertisedHostFor("172.16.0.9", hotspotHost, answeredOn = "192.168.43.1")).isEqualTo("192.168.43.1")
    }

    @Test
    fun `interfaces that are down or cannot multicast are skipped`() {
        val addresses =
            listOf(
                LocalIpv4("wlan0", "192.168.1.37", 24, up = false),
                LocalIpv4("ap0", "192.168.43.1", 24, multicast = false),
            )

        assertThat(CastNetwork.discoveryInterfaces(addresses)).isEmpty()
    }

    @Test
    fun `no usable address gives no host`() {
        val onlyLoopback = listOf(LocalIpv4("lo", "127.0.0.1", 8, loopback = true))

        assertThat(CastNetwork.discoveryInterfaces(onlyLoopback)).isEmpty()
        assertThat(CastNetwork.advertisedHostFor("192.168.1.20", onlyLoopback)).isNull()
        assertThat(CastNetwork.advertisedHostFor(null, emptyList())).isNull()
    }
}
