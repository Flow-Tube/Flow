package io.github.aedev.flow.player.shorts

import android.content.Context
import android.media.audiofx.AudioEffect
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.EntryPointAccessors
import io.github.aedev.flow.player.audio.AudioEffectsEntryPoint
import io.github.aedev.flow.player.audio.AudioSessionAnnouncer
import io.github.aedev.flow.player.audio.eq.EqualizerAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * One equalizer per pooled player, kept in step with the user's EQ, and each player's audio session
 * announced to the system effects. The players are all live at once, so each keeps its own filter history.
 */
@UnstableApi
internal class ShortsPoolAudioEffects(
    appContext: Context,
    size: Int,
) {
    private val effects = EntryPointAccessors.fromApplication(appContext, AudioEffectsEntryPoint::class.java)
    private val spec = effects.equalizerRepository().processingSpec
    private val equalizers = arrayOfNulls<EqualizerAudioProcessor>(size)
    private val announcers = arrayOfNulls<AudioSessionAnnouncer>(size)
    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope ->
            scope.launch { spec.collect { value -> equalizers.forEach { it?.setSpec(value) } } }
        }

    fun equalizerFor(slot: Int): EqualizerAudioProcessor =
        EqualizerAudioProcessor().also { it.setSpec(spec.value) }.also {
            equalizers[slot] =
                it
        }

    fun announce(
        slot: Int,
        player: ExoPlayer,
    ) {
        announcers[slot] = AudioSessionAnnouncer(effects.audioSessionRegistry(), AudioEffect.CONTENT_TYPE_MOVIE).apply { attach(player) }
    }

    fun release() {
        scope.cancel()
        announcers.forEach { it?.detach() }
        announcers.fill(null)
        equalizers.fill(null)
    }
}
