package io.github.aedev.flow.utils

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Picks this device's LAN IPv4 for the QR code, and can list every candidate it considered.
 *
 * The winner is what the QR tells the peer to dial, so a wrong pick is a connection that simply
 * never arrives. Ranking mirrors the desktop's `rank_lan_candidates` so both ends of a pair choose
 * comparably: private ranges first, and a **physical interface always beats a virtual one whatever
 * its range** — with WireGuard/Mullvad/Tailscale up, the tunnel address is frequently the one the
 * OS enumerates first even though it exists only inside the tunnel.
 */
object LanAddress {
    private const val TAG = "LanAddress"

    /** Interface-name prefixes that mean "virtual, container, or VPN adapter". */
    private val VIRTUAL_PREFIXES = listOf("tun", "tap", "utun", "wg", "ppp", "veth", "br-", "zt")

    /** Fragments meaning the same thing but which can appear anywhere in a verbose adapter name. */
    private val VIRTUAL_SUBSTRINGS =
        listOf("docker", "virbr", "vboxnet", "vmnet", "tailscale", "vethernet", "mullvad", "wsl")

    data class Candidate(
        val interfaceName: String,
        val ip: String,
        /** True when the interface looks virtual/VPN — such an address is only ever a last resort. */
        val virtual: Boolean,
    ) {
        override fun toString(): String = "$interfaceName=$ip${if (virtual) " (virtual)" else ""}"
    }

    /** The address the QR should advertise, or null when this device has no usable LAN address. */
    fun resolve(): String? = candidates().firstOrNull()?.ip

    /**
     * Every plausible LAN IPv4 on this device, best first. Logged when hosting so the next
     * "the phone shows a QR but nothing connects" report arrives with the ranking attached.
     */
    fun candidates(): List<Candidate> {
        val interfaces =
            runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull()
                ?: return emptyList()
        val pairs = ArrayList<Pair<String, String>>()
        for (nif in interfaces) {
            val up = runCatching { nif.isUp }.getOrDefault(false)
            val loopback = runCatching { nif.isLoopback }.getOrDefault(true)
            if (!up || loopback) continue
            val name = runCatching { nif.name }.getOrNull().orEmpty()
            for (addr in nif.inetAddresses) {
                if (addr !is Inet4Address) continue
                val ip = addr.hostAddress ?: continue
                pairs.add(name to ip)
            }
        }
        return rank(pairs)
    }

    fun logCandidates(chosen: String?) {
        val ranked = candidates()
        Log.i(TAG, "LAN candidates (best first): ${ranked.joinToString()} -> advertising ${chosen ?: "none"}")
    }

    /**
     * Rank `(interfaceName, ipv4)` pairs into QR candidates, best first. Split out from
     * [candidates] so the selection policy is unit-testable without real network interfaces.
     * The sort is stable, so equally-ranked addresses keep the OS enumeration order.
     */
    fun rank(interfaces: List<Pair<String, String>>): List<Candidate> =
        interfaces
            .mapNotNull { (name, ip) ->
                addressRank(name, ip)?.let { rank -> rank to Candidate(name, ip, isVirtual(name)) }
            }.sortedBy { it.first }
            .map { it.second }

    private fun isVirtual(name: String): Boolean {
        val n = name.lowercase()
        return VIRTUAL_PREFIXES.any { n.startsWith(it) } || VIRTUAL_SUBSTRINGS.any { n.contains(it) }
    }

    /**
     * How plausible this address is as "reachable from another device on the same LAN" — lower is
     * better. Null rejects the address outright.
     */
    private fun addressRank(
        name: String,
        ip: String,
    ): Int? {
        val o = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (o.size != 4 || o.any { it !in 0..255 }) return null
        val rangeRank =
            when {
                o[0] == 127 -> return null

                // loopback
                o[0] == 169 && o[1] == 254 -> return null

                // link-local
                o[0] == 0 -> return null

                // unspecified
                o[0] in 224..239 -> return null

                // multicast
                o[0] == 192 && o[1] == 168 -> 0

                o[0] == 10 -> 1

                // Docker's default bridge is demoted rather than rejected, so a device whose only
                // address really is in it still gets a QR.
                o[0] == 172 && o[1] == 17 -> 4

                o[0] == 172 && o[1] in 16..31 -> 2

                // CGNAT / Tailscale range: routable for that overlay, almost never the phone's LAN.
                o[0] == 100 && o[1] in 64..127 -> 5

                else -> 3
            }
        return if (isVirtual(name)) 8 + rangeRank else rangeRank
    }
}
