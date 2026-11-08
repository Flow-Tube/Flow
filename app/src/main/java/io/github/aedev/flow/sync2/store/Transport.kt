package io.github.aedev.flow.sync2.store

import io.github.aedev.flow.sync.qr.LocalNetworkAddress
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** The most a response other than an object or a blob may hold (spec/store.md, Untrusted input). */
internal const val MAX_RESPONSE = 16L * 1024 * 1024

/** Reads a body of at most [limit] bytes, failing as soon as more arrive. */
internal fun ResponseBody.bytesUpTo(limit: Long): ByteArray {
    val source = source()
    if (source.request(limit + 1)) throw StoreException.TooLarge()
    return source.readByteArray()
}

/** A `.local` name may resolve only to local addresses, checked on every connection. */
private val LOCAL_ONLY_DNS =
    Dns { host ->
        Dns.SYSTEM.lookup(host).takeIf { addresses -> addresses.all { LocalNetworkAddress.isLocal(it.hostAddress.orEmpty()) } }
            ?: throw UnknownHostException("$host is not on the local network")
    }

/**
 * The client for a store at [base] (spec/store.md, Transport security): HTTPS anywhere, plain HTTP
 * only to a private address or a `.local` name and never through a proxy, and redirects refused.
 */
internal fun storeClient(base: HttpUrl): OkHttpClient {
    val builder =
        OkHttpClient
            .Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
    when {
        base.isHttps -> Unit
        LocalNetworkAddress.isLocal(base.host) -> Unit
        base.host.endsWith(".local", ignoreCase = true) -> builder.dns(LOCAL_ONLY_DNS)
        else -> throw StoreException.Insecure()
    }
    // A system proxy would see plain traffic, login included, and the checks above wouldn't reach where it connects.
    if (!base.isHttps) builder.proxy(Proxy.NO_PROXY)
    return builder.build()
}
