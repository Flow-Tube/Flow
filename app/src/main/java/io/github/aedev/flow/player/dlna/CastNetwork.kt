package io.github.aedev.flow.player.dlna

import io.github.aedev.flow.utils.LanAddress
import java.net.Inet4Address
import java.net.NetworkInterface

/** One IPv4 address on a local interface. */
internal data class LocalIpv4(
    val interfaceName: String,
    val ip: String,
    val prefixLength: Int,
    val up: Boolean = true,
    val loopback: Boolean = false,
    val multicast: Boolean = true,
)

/**
 * Which local networks a cast searches and streams on. A phone sharing its hotspot is on mobile data
 * by default, so a socket left to the default route never reaches the TVs on its own hotspot (#2121).
 */
internal object CastNetwork {
    /** Mobile data and its 464xlat tunnel: no renderer lives behind them. */
    private val CELLULAR_PREFIXES = listOf("rmnet", "ccmni", "pdp", "seth_lte", "v4-")

    /** One address per interface a renderer could answer on, best first. */
    fun discoveryInterfaces(addresses: List<LocalIpv4>): List<LocalIpv4> {
        val usable =
            addresses.filter { address ->
                address.up &&
                    !address.loopback &&
                    address.multicast &&
                    address.prefixLength in 1..32 &&
                    !isClat(address.ip) &&
                    CELLULAR_PREFIXES.none { address.interfaceName.lowercase().startsWith(it) }
            }
        return LanAddress
            .rank(usable.map { it.interfaceName to it.ip })
            .filterNot { it.virtual }
            .mapNotNull { candidate -> usable.firstOrNull { it.interfaceName == candidate.interfaceName && it.ip == candidate.ip } }
            .distinctBy { it.interfaceName }
    }

    /**
     * The address a renderer at [rendererIp] can reach this phone on: the one on its subnet, else the
     * address its search answer arrived on ([answeredOn]), else the best one to search from.
     */
    fun advertisedHostFor(
        rendererIp: String?,
        addresses: List<LocalIpv4>,
        answeredOn: String? = null,
    ): String? {
        val renderer = rendererIp?.let(::ipv4ToInt)
        val sameSubnet =
            renderer?.let { target ->
                addresses
                    .filter { it.up && !it.loopback }
                    .firstOrNull { address -> ipv4ToInt(address.ip)?.let { sameSubnet(it, target, address.prefixLength) } == true }
            }
        return sameSubnet?.ip ?: answeredOn ?: discoveryInterfaces(addresses).firstOrNull()?.ip
    }

    /** Every IPv4 address on this device's interfaces, with its prefix length. */
    fun localAddresses(): List<LocalIpv4> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        return interfaces.flatMap { nif ->
            val up = runCatching { nif.isUp }.getOrDefault(false)
            val loopback = runCatching { nif.isLoopback }.getOrDefault(true)
            val multicast = runCatching { nif.supportsMulticast() }.getOrDefault(false)
            nif.interfaceAddresses.mapNotNull { address ->
                val ip = (address.address as? Inet4Address)?.hostAddress ?: return@mapNotNull null
                LocalIpv4(nif.name, ip, address.networkPrefixLength.toInt(), up, loopback, multicast)
            }
        }
    }

    private fun sameSubnet(
        a: Int,
        b: Int,
        prefixLength: Int,
    ): Boolean {
        if (prefixLength !in 1..32) return false
        val mask = -1 shl (32 - prefixLength)
        return (a and mask) == (b and mask)
    }

    /** 192.0.0.0/29, the address block of the 464xlat CLAT interface on IPv6-only mobile data. */
    private fun isClat(ip: String): Boolean = ipv4ToInt(ip)?.let { (it and -8) == ipv4ToInt("192.0.0.0") } == true

    private fun ipv4ToInt(ip: String): Int? {
        val octets = ip.split('.').map { it.toIntOrNull() ?: return null }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        return octets.fold(0) { acc, octet -> (acc shl 8) or octet }
    }
}
