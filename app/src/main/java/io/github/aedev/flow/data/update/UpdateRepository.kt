package io.github.aedev.flow.data.update

import android.os.Build
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.data.local.LocalDataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val STABLE_RELEASE_API = "https://api.github.com/repos/Flow-Tube/Flow/releases/latest"
private const val NIGHTLY_RELEASE_API = "https://api.github.com/repos/Flow-Tube/Flow/releases/tags/nightly"
private const val NIGHTLY_CHANNEL = "nightly"
private const val GITHUB_JSON = "application/vnd.github+json"

/** How the automatic checks space themselves out and which releases they may show. */
internal object UpdateSchedule {
    val COOLDOWN_MS: Long = TimeUnit.HOURS.toMillis(12)

    fun isDue(
        lastCheckMs: Long,
        nowMs: Long,
    ): Boolean = nowMs < lastCheckMs || nowMs - lastCheckMs >= COOLDOWN_MS

    fun mayAnnounce(
        version: String,
        skippedVersion: String?,
        announcedVersion: String?,
    ): Boolean = version != skippedVersion && version != announcedVersion

    /** The update page opens on every launch until the user skips the version or its download starts. */
    fun shouldOpenOnLaunch(
        version: String,
        skippedVersion: String?,
        download: UpdateDownload,
    ): Boolean = version != skippedVersion && (download is UpdateDownload.Idle || download is UpdateDownload.Failed)

    /** A launch reuses what this process already learned from GitHub and fetches only when it has asked nothing yet. */
    suspend fun <T> releaseForLaunch(
        alreadyFetched: Boolean,
        known: T?,
        fetch: suspend () -> T?,
    ): T? = if (alreadyFetched) known else fetch()
}

/**
 * The one place Flow asks GitHub about releases. The launch check, the background worker and the
 * Settings button all come through here, so a cold start never fetches twice and a skipped version
 * stays skipped everywhere.
 */
@Singleton
class UpdateRepository
    @Inject
    constructor(
        private val client: OkHttpClient,
        private val dataManager: LocalDataManager,
    ) {
        private val mutex = Mutex()
        private val isNightly = BuildConfig.UPDATE_CHANNEL == NIGHTLY_CHANNEL
        private val _latest = MutableStateFlow<AppRelease?>(null)
        private var fetchedThisProcess = false

        /** The newest release found this process, or null when none is known or this build is current. */
        val latest: StateFlow<AppRelease?> = _latest.asStateFlow()

        /** Asks GitHub now. Throws when the request fails, so a check the user started can say so. */
        suspend fun fetch(): AppRelease? = mutex.withLock { fetchLocked() }

        /** For the launch page: the release this process already knows, or one fetch when it knows none. Null when it fails. */
        suspend fun releaseForLaunch(): AppRelease? =
            mutex.withLock {
                UpdateSchedule.releaseForLaunch(fetchedThisProcess, _latest.value) { runCatching { fetchLocked() }.getOrNull() }
            }

        /**
         * For the background notification: fetches only once the cooldown has passed, and returns a release only if
         * it was not announced before and the user has not skipped it.
         */
        suspend fun releaseToNotify(nowMs: Long = System.currentTimeMillis()): AppRelease? =
            mutex.withLock {
                if (!UpdateSchedule.isDue(dataManager.lastUpdateCheck.first(), nowMs)) return@withLock null
                val release = runCatching { fetchLocked() }.getOrNull() ?: return@withLock null
                if (!UpdateSchedule.mayAnnounce(
                        release.version,
                        dataManager.skippedUpdateVersion.first(),
                        dataManager.notifiedUpdateVersion.first(),
                    )
                ) {
                    return@withLock null
                }
                dataManager.setNotifiedUpdateVersion(release.version)
                release
            }

        suspend fun skip(version: String) = dataManager.setSkippedUpdateVersion(version)

        suspend fun skippedVersion(): String? = dataManager.skippedUpdateVersion.first()

        private suspend fun fetchLocked(): AppRelease? {
            val body =
                withContext(Dispatchers.IO) {
                    val request =
                        Request
                            .Builder()
                            .url(if (isNightly) NIGHTLY_RELEASE_API else STABLE_RELEASE_API)
                            .header("Accept", GITHUB_JSON)
                            .cacheControl(CacheControl.FORCE_NETWORK)
                            .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Update check failed: HTTP ${response.code}")
                        response.body.string()
                    }
                }
            val parsed = parseGitHubRelease(body)
            val release =
                if (isNightly) {
                    parsed.toNightlyReleaseIfNewer(BuildConfig.NIGHTLY_RUN)
                } else {
                    parsed.toAppReleaseIfNewer(BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.toList())
                }
            dataManager.setLastUpdateCheck(System.currentTimeMillis())
            _latest.value = release
            fetchedThisProcess = true
            return release
        }
    }
