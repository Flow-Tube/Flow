package io.github.aedev.flow.ui.screens.equalizer

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.audio.eq.AutoEqFormat
import io.github.aedev.flow.data.audio.eq.AutoEqImport
import io.github.aedev.flow.data.audio.eq.BuiltInEqPresets
import io.github.aedev.flow.data.audio.eq.DeletedEqPreset
import io.github.aedev.flow.data.audio.eq.EqBand
import io.github.aedev.flow.data.audio.eq.EqLimits
import io.github.aedev.flow.data.audio.eq.EqMode
import io.github.aedev.flow.data.audio.eq.EqSpatialSound
import io.github.aedev.flow.data.audio.eq.EqState
import io.github.aedev.flow.data.audio.eq.EqStateJson
import io.github.aedev.flow.data.audio.eq.EqualizerRepository
import io.github.aedev.flow.data.audio.eq.GraphicEq
import io.github.aedev.flow.data.audio.eq.addBand
import io.github.aedev.flow.data.audio.eq.deletePreset
import io.github.aedev.flow.data.audio.eq.duplicatePreset
import io.github.aedev.flow.data.audio.eq.importPreset
import io.github.aedev.flow.data.audio.eq.isNameAvailable
import io.github.aedev.flow.data.audio.eq.normalizedPresetName
import io.github.aedev.flow.data.audio.eq.presetCurve
import io.github.aedev.flow.data.audio.eq.removeBand
import io.github.aedev.flow.data.audio.eq.renamePreset
import io.github.aedev.flow.data.audio.eq.restorePreset
import io.github.aedev.flow.data.audio.eq.revert
import io.github.aedev.flow.data.audio.eq.roundToDecimals
import io.github.aedev.flow.data.audio.eq.saveActive
import io.github.aedev.flow.data.audio.eq.saveActiveAs
import io.github.aedev.flow.data.audio.eq.selectPreset
import io.github.aedev.flow.data.audio.eq.withActiveCurve
import io.github.aedev.flow.data.audio.eq.withBand
import io.github.aedev.flow.data.audio.eq.withBassBoost
import io.github.aedev.flow.data.audio.eq.withManualPreamp
import io.github.aedev.flow.player.audio.AudioSessionRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A one-off message for the page's snackbar; [undo] restores what the action changed. */
data class EqMessage(
    @StringRes val text: Int,
    val undo: (() -> Unit)? = null,
)

/** A parsed file or paste waiting for the user to name and confirm it. */
data class EqImportPreview(
    val suggestedName: String,
    val result: AutoEqImport,
)

@HiltViewModel
class EqualizerViewModel
    @Inject
    constructor(
        private val repository: EqualizerRepository,
        private val sessions: AudioSessionRegistry,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        val state: StateFlow<EqState> = repository.state

        private val undoStack = ArrayDeque<EqState>()
        private val _canUndo = MutableStateFlow(false)
        val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

        private val _messages = MutableSharedFlow<EqMessage>(extraBufferCapacity = 4)
        val messages: SharedFlow<EqMessage> = _messages.asSharedFlow()

        private val _importPreview = MutableStateFlow<EqImportPreview?>(null)
        val importPreview: StateFlow<EqImportPreview?> = _importPreview.asStateFlow()

        val builtInNames: List<String> = BuiltInEqPresets.all.map { context.getString(it.nameRes) }

        /** Applies [transform] and remembers the state before it for Undo. */
        private fun edit(transform: (EqState) -> EqState) {
            val before = repository.state.value
            repository.update(transform)
            if (repository.state.value != before) {
                undoStack.addLast(before)
                if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
                _canUndo.value = true
            }
        }

        fun undo() {
            val previous = undoStack.removeLastOrNull() ?: return
            repository.restore(previous)
            _canUndo.value = undoStack.isNotEmpty()
        }

        fun setEnabled(enabled: Boolean) = edit { it.copy(enabled = enabled) }

        fun setMode(mode: EqMode) = edit { it.copy(mode = mode) }

        fun selectPreset(id: String) = edit { it.selectPreset(id) }

        fun revert() = edit { it.revert() }

        fun save() {
            edit { it.saveActive() }
            _messages.tryEmit(EqMessage(R.string.eq_preset_saved))
        }

        fun isNameAvailable(
            name: String,
            exceptId: String? = null,
        ): Boolean = repository.state.value.isNameAvailable(normalizedPresetName(name), builtInNames, exceptId)

        fun saveAs(name: String) {
            val clean = normalizedPresetName(name)
            if (!isNameAvailable(clean)) return
            edit { it.saveActiveAs(EqStateJson.newPresetId(), clean) }
            _messages.tryEmit(EqMessage(R.string.eq_preset_saved))
        }

        fun rename(
            id: String,
            name: String,
        ) {
            val clean = normalizedPresetName(name)
            if (!isNameAvailable(clean, exceptId = id)) return
            edit { it.renamePreset(id, clean) }
        }

        fun duplicate(
            id: String,
            name: String,
        ) {
            val clean = normalizedPresetName(name)
            if (!isNameAvailable(clean)) return
            edit { it.duplicatePreset(id, EqStateJson.newPresetId(), clean) }
        }

        fun deletePreset(id: String) {
            var deleted: DeletedEqPreset? = null
            edit { state -> state.deletePreset(id).also { deleted = it.second }.first }
            val removed = deleted ?: return
            _messages.tryEmit(EqMessage(R.string.eq_preset_deleted) { edit { it.restorePreset(removed) } })
        }

        fun updateBand(
            index: Int,
            band: EqBand,
        ) = edit { it.withBand(index, band) }

        fun toggleBand(index: Int) =
            edit { state ->
                val band =
                    state.active.curve.bands
                        .getOrNull(index) ?: return@edit state
                state.withBand(index, band.copy(enabled = !band.enabled))
            }

        /** Adds a peak at the tapped point and returns its index, or null at the band limit. */
        fun addBand(
            frequency: Double = DEFAULT_NEW_FREQUENCY,
            gain: Double = 0.0,
        ): Int? {
            val before = repository.state.value.active.curve.bands.size
            if (before >= EqLimits.MAX_BANDS) return null
            edit { it.addBand(EqBand(frequency = frequency.roundToDecimals(0), gain = gain.roundToDecimals(1))) }
            return before.takeIf { repository.state.value.active.curve.bands.size > before }
        }

        fun removeBand(index: Int) {
            val before = repository.state.value
            edit { it.removeBand(index) }
            if (repository.state.value != before) {
                _messages.tryEmit(EqMessage(R.string.eq_band_removed) { edit { before } })
            }
        }

        fun setGraphicGain(
            index: Int,
            gain: Double,
        ) = edit { state ->
            val gains = GraphicEq.gainsOf(state.graphic.curve).toMutableList()
            if (index !in gains.indices) return@edit state
            gains[index] = gain.coerceIn(-GraphicEq.MAX_GAIN, GraphicEq.MAX_GAIN)
            state.withActiveCurve(GraphicEq.curveOf(gains, state.graphic.curve.preamp))
        }

        fun setBassBoost(db: Double) = edit { it.withBassBoost(db.roundToDecimals(0)) }

        fun setSpatialSound(sound: EqSpatialSound) = edit { it.copy(spatialSound = sound) }

        fun setAutoPreamp(enabled: Boolean) = edit { it.copy(autoPreamp = enabled) }

        fun setPreamp(db: Double) = edit { it.withManualPreamp(db.roundToDecimals(1)) }

        fun resetCurve() {
            val before = repository.state.value
            edit { state ->
                if (state.mode == EqMode.PARAMETRIC) {
                    state.withActiveCurve(state.active.curve.copy(bands = emptyList()))
                } else {
                    state.withActiveCurve(GraphicEq.flatCurve())
                }
            }
            if (repository.state.value != before) _messages.tryEmit(EqMessage(R.string.eq_reset_done) { edit { before } })
        }

        /** The sound follows a dragged point; the saved state changes only when the drag ends. */
        fun previewBand(
            index: Int,
            band: EqBand,
        ) {
            val curve = repository.state.value.active.curve
            if (index !in curve.bands.indices) return
            repository.preview(curve.copy(bands = curve.bands.toMutableList().also { it[index] = band }))
        }

        fun setBypass(bypassed: Boolean) = repository.setBypass(bypassed)

        /** The APO text for preset [presetId], or for what is playing when it is null. */
        fun exportText(presetId: String?): String {
            val state = repository.state.value
            return AutoEqFormat.format(state.presetCurve(presetId) ?: state.active.curve)
        }

        fun importFromText(
            text: String,
            suggestedName: String? = null,
        ) {
            val parsed = AutoEqFormat.parse(text)
            if (parsed == null || parsed.curve.bands.isEmpty()) {
                _messages.tryEmit(EqMessage(R.string.eq_import_failed))
                return
            }
            _importPreview.value = EqImportPreview(uniqueName(suggestedName ?: context.getString(R.string.eq_imported_badge)), parsed)
        }

        fun importFromFile(uri: Uri) {
            viewModelScope.launch {
                val loaded =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val text =
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    stream.bufferedReader().readText().take(MAX_IMPORT_CHARS)
                                }
                            text to displayName(uri)
                        }.getOrNull()
                    }
                val text = loaded?.first
                if (text == null) {
                    _messages.tryEmit(EqMessage(R.string.eq_import_read_failed))
                } else {
                    importFromText(text, loaded.second)
                }
            }
        }

        fun confirmImport(name: String) {
            val preview = _importPreview.value ?: return
            val clean = normalizedPresetName(name)
            if (!isNameAvailable(clean)) return
            edit { it.importPreset(EqStateJson.newPresetId(), clean, preview.result.curve) }
            _importPreview.value = null
            _messages.tryEmit(EqMessage(R.string.eq_imported))
        }

        fun dismissImport() {
            _importPreview.value = null
        }

        /**
         * An installed equalizer panel for the session playing now, or null when none is installed.
         * Built at tap time: the active session changes as players start and stop.
         */
        fun systemEqualizerIntent(): Intent? {
            val session = sessions.activeSessionId.value
            val intent =
                Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    .putExtra(
                        AudioEffect.EXTRA_CONTENT_TYPE,
                        sessions.contentTypeOf(session) ?: AudioEffect.CONTENT_TYPE_MUSIC,
                    )
            if (session > 0) intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
            return intent.takeIf { it.resolveActivity(context.packageManager) != null }
        }

        private fun uniqueName(wanted: String): String {
            val base = normalizedPresetName(wanted).ifEmpty { context.getString(R.string.eq_imported_badge) }
            if (isNameAvailable(base)) return base
            var suffix = 2
            while (!isNameAvailable("$base $suffix")) suffix++
            return "$base $suffix"
        }

        private fun displayName(uri: Uri): String? =
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?.substringBeforeLast('.')

        override fun onCleared() {
            repository.setBypass(false)
            repository.preview(null)
        }

        private companion object {
            const val MAX_UNDO = 50
            const val MAX_IMPORT_CHARS = 64 * 1024
            const val DEFAULT_NEW_FREQUENCY = 1_000.0
        }
    }
