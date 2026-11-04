package io.github.aedev.flow.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

private val ReleaseJson = Json { ignoreUnknownKeys = true }
private const val SHA256_PREFIX = "sha256:"
private const val FOSS_PREFIX = "flow-foss"
private const val UNIVERSAL_APK = "flow.apk"
private const val APK_SUFFIX = ".apk"
private val NightlyApk = Regex("""flow-nightly-(\d+)\.apk""", RegexOption.IGNORE_CASE)

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
internal data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
    val digest: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

internal fun parseGitHubRelease(json: String): GitHubRelease = ReleaseJson.decodeFromString(GitHubRelease.serializer(), json)

/** [release] as an [AppRelease] when it is newer than [currentVersion], otherwise null. */
internal fun GitHubRelease.toAppReleaseIfNewer(
    currentVersion: String,
    supportedAbis: List<String>,
): AppRelease? {
    if (tagName.isBlank() || !AppVersions.isNewer(tagName, currentVersion)) return null
    return AppRelease(
        version = AppVersions.normalize(tagName),
        tag = tagName,
        notes = body.orEmpty(),
        publishedAt = publishedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
        pageUrl = htmlUrl,
        apk = selectApk(assets, supportedAbis)?.toReleaseApk(),
    )
}

/**
 * The rolling nightly release as an [AppRelease] when its build is newer than [currentRun]. Nightly
 * builds are told apart by their CI run number, which the APK's name carries, so a local build
 * (run 0) never offers one.
 */
internal fun GitHubRelease.toNightlyReleaseIfNewer(currentRun: Int): AppRelease? {
    if (currentRun <= 0) return null
    val (apk, run) =
        assets
            .firstNotNullOfOrNull { asset -> NightlyApk.matchEntire(asset.name)?.let { asset to it.groupValues[1].toInt() } }
            ?: return null
    if (run <= currentRun) return null
    return AppRelease(
        version = AppVersions.nightly(run),
        tag = tagName,
        notes = body.orEmpty(),
        // The rolling release is edited in place, so its published_at never moves; the APK is new each run.
        publishedAt = (apk.updatedAt ?: publishedAt)?.let { runCatching { Instant.parse(it) }.getOrNull() },
        pageUrl = htmlUrl,
        apk = apk.toReleaseApk(),
    )
}

/**
 * The github-flavour APK for this device: the split build for its preferred ABI, else the universal
 * `flow.apk`, else any github APK. Foss builds are never picked; they are signed for other stores.
 */
internal fun selectApk(
    assets: List<GitHubAsset>,
    supportedAbis: List<String>,
): GitHubAsset? {
    val github =
        assets.filter {
            it.name.endsWith(APK_SUFFIX, ignoreCase = true) && !it.name.startsWith(FOSS_PREFIX, ignoreCase = true)
        }
    val splits = github.filter { asset -> supportedAbis.any { asset.name.equals("flow-$it.apk", ignoreCase = true) } }
    if (splits.isNotEmpty()) {
        return supportedAbis.firstNotNullOfOrNull { abi -> splits.firstOrNull { it.name.equals("flow-$abi.apk", ignoreCase = true) } }
    }
    val publishesSplits = github.any { it.name.startsWith("flow-", ignoreCase = true) }
    if (publishesSplits && github.none { it.name.equals(UNIVERSAL_APK, ignoreCase = true) }) return null
    return github.firstOrNull { it.name.equals(UNIVERSAL_APK, ignoreCase = true) } ?: github.firstOrNull()
}

private fun GitHubAsset.toReleaseApk(): ReleaseApk =
    ReleaseApk(
        name = name,
        url = downloadUrl,
        sizeBytes = size,
        sha256 = digest?.takeIf { it.startsWith(SHA256_PREFIX) }?.removePrefix(SHA256_PREFIX)?.lowercase(),
    )
