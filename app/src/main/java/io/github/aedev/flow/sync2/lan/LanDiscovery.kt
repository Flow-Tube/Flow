package io.github.aedev.flow.sync2.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import io.github.aedev.flow.sync.crypto.SyncBytes
import kotlinx.coroutines.CompletableDeferred

private const val SERVICE_TYPE = "_flow._tcp"

/**
 * mDNS on Android (spec/live.md, Discovery): advertises `_flow._tcp` with `v=2` and this hour's tag, and
 * finds the other services. Only the tag tells members apart; the instance name is random per start.
 */
class LanDiscovery(
    context: Context,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    // Older Wi-Fi stacks drop multicast replies unless an app holds this lock (spec/live.md, Platform notes).
    private val multicast =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) {
            context.applicationContext
                .getSystemService(
                    WifiManager::class.java,
                ).createMulticastLock("flow-linked")
                .apply { setReferenceCounted(false) }
        } else {
            null
        }
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    /** Completes when advertising or finding couldn't start, so the caller can start over. */
    val failed = CompletableDeferred<Unit>()

    fun advertise(
        name: String,
        port: Int,
        tag: ByteArray,
    ) {
        val info =
            NsdServiceInfo().apply {
                serviceName = name
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("v", "2")
                setAttribute("t", SyncBytes.toHex(tag))
            }
        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = Unit

                override fun onRegistrationFailed(
                    info: NsdServiceInfo,
                    error: Int,
                ) {
                    failed.complete(Unit)
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

                override fun onUnregistrationFailed(
                    info: NsdServiceInfo,
                    error: Int,
                ) = Unit
            }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registration = listener
    }

    /** Reports each Flow service that answers, other than this device's [own] names: its address, port and tag. */
    fun discover(
        own: Set<String>,
        onFound: (host: String, port: Int, tag: String?) -> Unit,
    ) {
        multicast?.acquire()
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceName in own) return
                    @Suppress("DEPRECATION") // The replacement callback needs API 34.
                    nsd.resolveService(
                        info,
                        object : NsdManager.ResolveListener {
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                @Suppress("DEPRECATION")
                                val host = resolved.host?.hostAddress ?: return
                                onFound(host, resolved.port, resolved.attributes["t"]?.toString(Charsets.US_ASCII))
                            }

                            override fun onResolveFailed(
                                info: NsdServiceInfo,
                                error: Int,
                            ) = Unit
                        },
                    )
                }

                override fun onServiceLost(info: NsdServiceInfo) = Unit

                override fun onDiscoveryStarted(type: String) = Unit

                override fun onDiscoveryStopped(type: String) = Unit

                override fun onStartDiscoveryFailed(
                    type: String,
                    error: Int,
                ) {
                    failed.complete(Unit)
                }

                override fun onStopDiscoveryFailed(
                    type: String,
                    error: Int,
                ) = Unit
            }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        discovery = listener
    }

    fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        multicast?.release()
    }
}
