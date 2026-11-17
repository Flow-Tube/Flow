package io.github.aedev.flow.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import io.github.aedev.flow.player.config.VideoSizeCap
import io.github.aedev.flow.player.media.MediaLoader
import io.github.aedev.flow.player.preload.GaplessPreloadController
import io.github.aedev.flow.player.preload.PreloadedNext
import io.github.aedev.flow.player.quality.QualityManager
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.stream.ResolvedStreamData
import io.github.aedev.flow.player.stream.TestAudioStream
import io.github.aedev.flow.player.stream.TestVideoStream
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A fresh [EnhancedPlayerManager] wired to a mocked ExoPlayer and the real [MediaLoader] and
 * [QualityManager], without the Android-only setup `initialize` does (cache, surface, sessions).
 *
 * The manager is a singleton with a private constructor and private collaborators, so this reaches
 * them by reflection; a field rename fails here with the field's name, never silently. The
 * quality-switch callback repeats the one `initializeComponents` installs.
 */
@UnstableApi
internal class PlayerManagerHarness {
    val manager: EnhancedPlayerManager =
        EnhancedPlayerManager::class.java
            .getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()
    val loadedSources = mutableListOf<MediaSource>()
    val player: ExoPlayer =
        mockk(relaxed = true) {
            every { setMediaSource(any()) } answers { loadedSources += firstArg<MediaSource>() }
        }
    val context: Context = mockk(relaxed = true)

    @Suppress("UNCHECKED_CAST")
    val state: MutableStateFlow<EnhancedPlayerState> = get("_playerState") as MutableStateFlow<EnhancedPlayerState>
    val quality: QualityManager

    init {
        set("player", player)
        set("appContext", context)
        set("mediaLoader", MediaLoader(context, state, null, null))
        quality =
            QualityManager(null, null, VideoSizeCap.UHD, state) { stream, position ->
                set("currentVideoStream", stream)
                loadMediaInternal(stream, get("currentAudioStream") as TestAudioStream?, position)
            }
        set("qualityManager", quality)
    }

    val availableVideoStreams: List<TestVideoStream> get() = list("availableVideoStreams")
    val availableAudioStreams: List<TestAudioStream> get() = list("availableAudioStreams")
    val currentVideoStream: TestVideoStream? get() = get("currentVideoStream") as TestVideoStream?
    val currentAudioStream: TestAudioStream? get() = get("currentAudioStream") as TestAudioStream?
    val currentVideoId: String? get() = get("currentVideoId") as String?
    val dashManifestUrl: String? get() = get("currentDashManifestUrl") as String?

    fun preload(
        data: ResolvedStreamData,
        fromQueue: Boolean = false,
    ) {
        val controller = get("preload") as GaplessPreloadController
        GaplessPreloadController::class.java
            .getDeclaredField("preloaded")
            .apply {
                isAccessible = true
            }.set(controller, PreloadedNext(data, fromQueue))
    }

    fun promotePreloadedItem() = call("promotePreloadedItem")

    fun reloadCurrentStream(
        position: Long?,
        reason: String,
    ) = call("reloadCurrentStream", position to java.lang.Long::class.java, reason to String::class.java)

    fun reloadPlaybackManager() = call("reloadPlaybackManager")

    private fun loadMediaInternal(
        video: TestVideoStream?,
        audio: TestAudioStream?,
        position: Long,
    ) = call(
        "loadMediaInternal",
        video to TestVideoStream::class.java,
        audio to TestAudioStream::class.java,
        position to java.lang.Long::class.java,
        null to String::class.java,
        false to Boolean::class.javaPrimitiveType!!,
        true to Boolean::class.javaPrimitiveType!!,
    )

    private fun field(name: String) = EnhancedPlayerManager::class.java.getDeclaredField(name).apply { isAccessible = true }

    fun get(name: String): Any? = field(name).get(manager)

    fun set(
        name: String,
        value: Any?,
    ) = field(name).set(manager, value)

    @Suppress("UNCHECKED_CAST")
    private fun <T> list(name: String) = get(name) as List<T>

    private fun call(
        name: String,
        vararg args: Pair<Any?, Class<*>>,
    ): Any? =
        EnhancedPlayerManager::class.java
            .getDeclaredMethod(name, *args.map { it.second }.toTypedArray())
            .apply { isAccessible = true }
            .invoke(manager, *args.map { it.first }.toTypedArray())
}
