package io.github.aedev.flow.data.contentfilter.aislist

import io.github.aedev.flow.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Plain GETs with no identifiers and no query parameters, as the list contract asks. The responses
 * stay out of the shared HTTP cache: the API marks them cacheable, and the snapshot would otherwise
 * sit there as a second megabyte-sized copy of what [AiListStore] already keeps.
 *
 * The shared client gives a whole call 60 s, which a slow link can spend on the snapshot alone; every
 * retry then dies at the same point. List calls get longer, and the read timeout still ends a stall.
 */
internal class OkHttpAiListFetcher(
    client: OkHttpClient,
    private val baseUrl: String,
) : AiListFetcher {
    private val client = client.newBuilder().callTimeout(CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES).build()

    override suspend fun get(
        path: String,
        etag: String?,
    ): AiListResponse =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(baseUrl + path)
                    .header("User-Agent", "Flow/${BuildConfig.VERSION_NAME}")
                    .cacheControl(NO_STORE)
                    .apply { etag?.let { header("If-None-Match", it) } }
                    .build()
            client.newCall(request).execute().use { response ->
                val body =
                    if (response.code == HTTP_OK) {
                        val source = response.body.source()
                        if (source.request(MAX_BYTES + 1)) throw IOException("$path is over $MAX_BYTES bytes")
                        source.readByteArray()
                    } else {
                        null
                    }
                AiListResponse(response.code, body, response.header("ETag"))
            }
        }

    private companion object {
        const val HTTP_OK = 200
        const val MAX_BYTES = 16L * 1024 * 1024
        const val CALL_TIMEOUT_MINUTES = 5L
        val NO_STORE: CacheControl = CacheControl.Builder().noStore().build()
    }
}
