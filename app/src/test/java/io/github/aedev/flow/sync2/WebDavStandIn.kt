package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.sha256
import io.github.aedev.flow.sync2.store.WebDavStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import okhttp3.Credentials

/**
 * A WebDAV server in memory under `/dav/`: refuses other credentials, a `PROPFIND` without `Depth` and
 * a write into a missing folder, as real servers do. [ifNoneMatch] honours that header; [nextcloud]
 * gives folders ETags that change with anything inside.
 */
class WebDavStandIn(
    private val ifNoneMatch: Boolean = true,
    private val nextcloud: Boolean = false,
) : AutoCloseable {
    val files = sortedMapOf<String, ByteArray>()
    val folders = sortedSetOf("")
    val requests = ArrayList<String>()

    /** What the folder takes now. */
    @Volatile
    var password = PASSWORD

    private val server =
        StandInServer {
            val method = request.httpMethod.value
            val path = request.path().removePrefix("/dav/")
            requests += "$method $path"
            when {
                request.headers["Authorization"] != Credentials.basic(USER, password) -> {
                    respondText("", status = HttpStatusCode.Unauthorized)
                }

                method == "PROPFIND" -> {
                    propfind(
                        path,
                        request.headers["Depth"],
                    )?.let { respondText(it, ContentType.Application.Xml, HttpStatusCode.MultiStatus) }
                        ?: respondText("", status = HttpStatusCode.fromValue(if (request.headers["Depth"] == null) 400 else 404))
                }

                else -> {
                    val (code, body) =
                        change(
                            method,
                            path,
                            request.headers["If-None-Match"],
                            if (method ==
                                "PUT"
                            ) {
                                receive<ByteArray>()
                            } else {
                                ByteArray(0)
                            },
                        )
                    respondBytes(body, status = HttpStatusCode.fromValue(code))
                }
            }
        }

    val url = server.url + "dav/flow/"

    fun store(
        password: String = PASSWORD,
        conditional: Boolean = false,
    ) = WebDavStore.connect(url, USER, password, conditional)

    /** What a file or folder under the store's folder holds. */
    fun file(path: String) = files["flow/$path"]

    private fun parent(path: String) = path.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

    private fun change(
        method: String,
        path: String,
        condition: String?,
        body: ByteArray,
    ): Pair<Int, ByteArray> =
        when (method) {
            "GET" -> {
                files[path]?.let { 200 to it } ?: (404 to ByteArray(0))
            }

            "PUT" -> {
                when {
                    parent(path) !in folders -> 409
                    condition == "*" && ifNoneMatch && path in files -> 412
                    else -> 201.also { files[path] = body }
                } to ByteArray(0)
            }

            "MKCOL" -> {
                when {
                    path in folders -> 405
                    parent(path) !in folders -> 409
                    else -> 201.also { folders += path }
                } to ByteArray(0)
            }

            "DELETE" -> {
                if (path in files || path in folders) {
                    files.keys.removeAll { it == path || (it.startsWith(path) && path.endsWith("/")) }
                    folders.removeAll { it.startsWith(path) && path.endsWith("/") }
                    204 to ByteArray(0)
                } else {
                    404 to ByteArray(0)
                }
            }

            else -> {
                405 to ByteArray(0)
            }
        }

    private fun propfind(
        folder: String,
        depth: String?,
    ): String? {
        if (depth !in setOf("0", "1") || folder !in folders) return null
        val inside = (files.keys + folders).filter { it.startsWith(folder) && it != folder }
        val children = if (depth == "1") inside.filter { it.removePrefix(folder).trimEnd('/').none { c -> c == '/' } } else emptyList()
        val etag =
            SyncBytes.toHex(
                sha256(inside.joinToString { it + (files[it]?.let(::sha256)?.let(SyncBytes::toHex) ?: "") }.toByteArray()),
            )
        val namespaces = if (nextcloud) """ xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns"""" else ""
        val responses =
            (listOf(folder) + children).joinToString("") {
                """<d:response><d:href>/dav/$it</d:href><d:propstat><d:prop><d:getetag>"$etag"</d:getetag></d:prop></d:propstat></d:response>"""
            }
        return """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"$namespaces>$responses</d:multistatus>"""
    }

    override fun close() = server.close()

    companion object {
        const val USER = "flow"
        const val PASSWORD = "secret"
    }
}
