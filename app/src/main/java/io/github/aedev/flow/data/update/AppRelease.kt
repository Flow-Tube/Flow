package io.github.aedev.flow.data.update

import java.time.Instant

/** A published Flow release that is newer than the running build. */
data class AppRelease(
    val version: String,
    val tag: String,
    val notes: String,
    val publishedAt: Instant?,
    val pageUrl: String,
    val apk: ReleaseApk?,
)

/** The APK this device should install: the build for its ABI, with the size and digest GitHub reports. */
data class ReleaseApk(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String?,
)

/** Orders dotted version names, ignoring a leading `v` and anything after the first `-`. */
object AppVersions {
    fun normalize(version: String): String = version.trim().removePrefix("v").substringBefore('-')

    fun nightly(run: Int): String = "nightly.$run"

    /** The running build as the update page names it: a nightly by its run, so it reads like the release it is compared with. */
    fun installedLabel(
        versionName: String,
        nightlyRun: Int,
    ): String = if (nightlyRun > 0) nightly(nightlyRun) else normalize(versionName)

    fun isNewer(
        candidate: String,
        current: String,
    ): Boolean {
        val candidateParts = normalize(candidate).split('.').map { it.toIntOrNull() ?: 0 }
        val currentParts = normalize(current).split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(candidateParts.size, currentParts.size)) {
            val a = candidateParts.getOrElse(i) { 0 }
            val b = currentParts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
