package io.github.aedev.flow.network

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * A client built inside a property getter is a new connection pool and thread pool on every read,
 * so each request pays a fresh TCP and TLS handshake. Hold the client, or a [ProxyAwareClient].
 */
class HttpClientConstructionTest {
    private val getterBuild = Regex("""get\(\)\s*=[^\n]*OkHttpClient(\.Builder)?\(\)""")

    @Test
    fun `no client is built on every property read`() {
        val root = File("src/main/java")
        assertWithMessage("source root").that(root.isDirectory).isTrue()

        val offenders =
            root
                .walkTopDown()
                .filter { it.extension == "kt" }
                .filter { getterBuild.containsMatchIn(it.readText()) }
                .map { it.relativeTo(root).path }
                .toList()

        assertWithMessage("files building a client in a getter").that(offenders).isEmpty()
    }
}
