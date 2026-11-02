package io.github.aedev.flow.sync2.store

import android.util.Xml
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync2.crypto.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private val OCTETS = "application/octet-stream".toMediaType()
private val XML = "application/xml; charset=utf-8".toMediaType()

/** A `PROPFIND` body asking for one DAV property. */
private fun propfind(property: String) =
    """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:$property/></d:prop></d:propfind>"""

/** Servers whose folder ETags change with anything inside: Nextcloud and ownCloud. */
private val PROPAGATING = setOf("http://nextcloud.org/ns", "http://owncloud.org/ns")

/**
 * A WebDAV folder as a file store (spec/store.md, WebDAV): `PUT`, `GET`, `MKCOL` and `PROPFIND` with
 * `Depth` always explicit. A 401 stops every later request with the same credentials, because
 * Nextcloud blocks an address after repeated failures. With [conditional], writes carry
 * `If-None-Match: *` as an extra guard, for servers the [probe] found honouring it.
 */
class WebDavStore private constructor(
    private val base: HttpUrl,
    private val client: OkHttpClient,
    private val authorization: String,
    private val conditional: Boolean,
) : FileStore {
    override val location: String = base.toString()

    /** Identifies these credentials at this location without keeping them. */
    private val account = SyncBytes.toHex(sha256("$location\n$authorization".toByteArray()))

    /** Folders known to exist, so a write doesn't make them again. */
    private val folders = ConcurrentHashMap.newKeySet<String>()

    companion object {
        /** Credentials a server refused, while the app runs; new credentials are tried again. */
        private val refused = ConcurrentHashMap.newKeySet<String>()

        /** [url] is the folder that holds the store; it may be made by the [probe]. */
        fun connect(
            url: String,
            user: String,
            password: String,
            conditional: Boolean = false,
        ): WebDavStore {
            val base = url.trimEnd('/').plus('/').toHttpUrlOrNull() ?: throw StoreException.Insecure()
            return WebDavStore(base, storeClient(base), Credentials.basic(user, password), conditional)
        }
    }

    private fun url(path: String): HttpUrl = base.resolve(path) ?: throw StoreException.Malformed()

    private suspend fun send(
        method: String,
        path: String,
        body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
        limit: Long = MAX_RESPONSE,
    ): Pair<Int, ByteArray> {
        if (account in refused) throw StoreException.LoginRefused()
        val request =
            Request
                .Builder()
                .url(url(path))
                .method(method, body)
                .header("Authorization", authorization)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
        val (code, bytes) =
            withContext(Dispatchers.IO) {
                try {
                    client.newCall(request).execute().use { it.code to it.body.bytesUpTo(limit) }
                } catch (e: IOException) {
                    throw StoreException.Network(e)
                }
            }
        if (code == 401) {
            refused += account
            throw StoreException.LoginRefused()
        }
        return code to bytes
    }

    private fun refusal(code: Int): Nothing = throw StoreException.Refused(code, "webdav")

    override suspend fun get(path: String): ByteArray? {
        val (code, bytes) = send("GET", path, limit = MAX_OBJECT_SIZE.toLong())
        return when (code) {
            in 200..299 -> bytes
            404 -> null
            else -> refusal(code)
        }
    }

    override suspend fun put(
        path: String,
        bytes: ByteArray,
    ) {
        val guard = if (conditional) mapOf("If-None-Match" to "*") else emptyMap()
        var code = send("PUT", path, bytes.toRequestBody(OCTETS), guard).first
        // A missing folder: 409 by the standard, 404 on some servers.
        if (code == 409 || code == 404) {
            makeFolders(path)
            code = send("PUT", path, bytes.toRequestBody(OCTETS), guard).first
        }
        // 412: the file is there already, and nothing is overwritten.
        if (code !in 200..299 && code != 412) refusal(code)
    }

    /** Makes every folder above [path], from the store's own folder down. */
    private suspend fun makeFolders(path: String) {
        val segments = path.split('/').dropLast(1)
        for (depth in 0..segments.size) {
            val folder = segments.take(depth).joinToString("") { "$it/" }
            if (folder in folders) continue
            val code = send("MKCOL", folder).first
            // 405: it exists already.
            if (code !in 200..299 && code != 405) refusal(code)
            folders += folder
        }
    }

    override suspend fun delete(path: String) {
        val code = send("DELETE", path).first
        if (code !in 200..299 && code != 404) refusal(code)
    }

    override suspend fun list(folder: String): List<String> {
        val path = folder.trimEnd('/') + "/"
        val (code, bytes) = send("PROPFIND", path, propfind("resourcetype").toRequestBody(XML), mapOf("Depth" to "1"))
        if (code == 404) return emptyList()
        if (code != 207) refusal(code)
        val self = url(path).encodedPath.trimEnd('/')
        return multistatus(bytes).hrefs.mapNotNull { href ->
            val item = url(href)
            item.pathSegments.lastOrNull { it.isNotEmpty() }.takeIf { item.encodedPath.trimEnd('/') != self }
        }
    }

    override suspend fun tag(folder: String): String? {
        val (code, bytes) = send("PROPFIND", folder.trimEnd('/') + "/", propfind("getetag").toRequestBody(XML), mapOf("Depth" to "0"))
        if (code == 404) return null
        if (code != 207) refusal(code)
        return multistatus(bytes).takeIf { it.propagates }?.etags?.firstOrNull()
    }

    /**
     * Makes the store's folder, then writes, reads back and deletes a probe file, so wrong
     * credentials or a read-only folder show up before anything else is written (spec/store.md,
     * Setup probe). Returns whether a second guarded write was refused, the only case where later
     * writes send `If-None-Match: *`; correctness never depends on it.
     */
    suspend fun probe(): Boolean {
        val code = send("MKCOL", "").first
        if (code !in 200..299 && code != 405) refusal(code)
        val path = "probe/" + SyncBytes.toHex(SyncCrypto.randomBytes(16))
        val content = SyncCrypto.randomBytes(16)
        val guard = mapOf("If-None-Match" to "*")
        makeFolders(path)
        val first = send("PUT", path, content.toRequestBody(OCTETS), guard).first
        if (first !in 200..299) refusal(first)
        if (!get(path).contentEquals(content)) throw StoreException.Malformed()
        val second = send("PUT", path, content.toRequestBody(OCTETS), guard).first
        delete("probe/")
        return second == 412
    }

    private class Multistatus(
        val hrefs: List<String>,
        val etags: List<String>,
        val propagates: Boolean,
    )

    private fun multistatus(bytes: ByteArray): Multistatus {
        val hrefs = ArrayList<String>()
        val etags = ArrayList<String>()
        var propagates = false
        try {
            val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
            parser.setInput(bytes.inputStream(), null)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                for (i in parser.getNamespaceCount(parser.depth - 1) until parser.getNamespaceCount(parser.depth)) {
                    if (parser.getNamespaceUri(i) in PROPAGATING) propagates = true
                }
                if (parser.namespace != "DAV:") continue
                when (parser.name) {
                    "href" -> hrefs += parser.nextText().trim()
                    "getetag" -> etags += parser.nextText().trim()
                }
            }
        } catch (_: Exception) {
            throw StoreException.Malformed()
        }
        return Multistatus(hrefs, etags, propagates)
    }
}
