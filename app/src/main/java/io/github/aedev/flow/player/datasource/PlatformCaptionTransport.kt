package io.github.aedev.flow.player.datasource

import android.content.Context
import android.net.Uri
import android.net.http.HttpEngine
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpEngineDataSource
import io.github.aedev.flow.network.AppProxyManager
import java.util.concurrent.Executors

/**
 * Fetches YouTube captions over Android's own Cronet, which YouTube serves a translated caption to
 * where it refuses the same URL over OkHttp with 429 (#1369). The engine is built on the first
 * caption, on the loader's thread, and kept for the process.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@UnstableApi
internal class PlatformCaptionTransport(
    private val context: Context,
) : DataSource.Factory {
    private val sources by lazy {
        HttpEngineDataSource
            .Factory(HttpEngine.Builder(context).build(), Executors.newSingleThreadExecutor())
            .setUserAgent(YouTubeHttpDataSource.DEFAULT_USER_AGENT)
            .setDefaultRequestProperties(GoogleVideoRequestPolicy.captionHeaders())
    }

    fun handles(uri: Uri): Boolean =
        GoogleVideoRequestPolicy.captionsOverPlatformCronet(uri.path, proxied = AppProxyManager.currentProxy() != null)

    override fun createDataSource(): DataSource = sources.createDataSource()
}
