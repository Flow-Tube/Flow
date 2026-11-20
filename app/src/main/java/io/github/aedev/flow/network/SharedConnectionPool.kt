package io.github.aedev.flow.network

import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit

/**
 * The connection pool every app client shares, so a connection one of them opened serves the
 * next request to the same host from any of them. OkHttp only hands a pooled connection to a
 * client whose proxy, DNS and TLS settings match, so clients with different routes stay apart.
 */
object SharedConnectionPool {
    private const val MAX_IDLE_CONNECTIONS = 15
    private const val KEEP_ALIVE_MINUTES = 5L

    val pool = ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES)
}
