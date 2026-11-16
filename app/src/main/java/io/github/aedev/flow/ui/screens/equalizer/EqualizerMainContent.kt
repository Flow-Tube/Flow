package io.github.aedev.flow.ui.screens.equalizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.audio.eq.EqLimits
import io.github.aedev.flow.data.audio.eq.EqMode
import io.github.aedev.flow.data.audio.eq.EqSpatialSound
import io.github.aedev.flow.data.audio.eq.EqState
import io.github.aedev.flow.data.audio.eq.GraphicEq
import io.github.aedev.flow.data.audio.eq.canSaveActive
import io.github.aedev.flow.data.audio.eq.isEdited
import io.github.aedev.flow.data.audio.eq.processingSpec
import io.github.aedev.flow.ui.components.equalizer.EqBandEditor
import io.github.aedev.flow.ui.components.equalizer.EqBandRow
import io.github.aedev.flow.ui.components.equalizer.EqBassBoostSlider
import io.github.aedev.flow.ui.components.equalizer.EqGraphicBands
import io.github.aedev.flow.ui.components.equalizer.EqResponseGraph
import io.github.aedev.flow.ui.components.equalizer.PanelRow
import io.github.aedev.flow.ui.components.equalizer.activePresetName
import io.github.aedev.flow.ui.components.equalizer.formatGain
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.settings.LocalSettingsHighlight
import io.github.aedev.flow.ui.components.settings.SettingsHighlightFrame
import io.github.aedev.flow.ui.components.settings.isNew
import io.github.aedev.flow.ui.components.shared.FlowConnectedToggleGroup
import io.github.aedev.flow.ui.components.shared.FlowMaxContentWidth
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.FlowPaneState
import io.github.aedev.flow.ui.components.shared.FlowRowGroup
import io.github.aedev.flow.ui.components.shared.FlowSectionHeader
import io.github.aedev.flow.ui.components.shared.FlowSegmentedGap
import io.github.aedev.flow.ui.components.shared.FlowSidePanes
import io.github.aedev.flow.ui.components.shared.FlowSwitchRow
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.components.shared.flowRowGroupShape
import kotlinx.coroutines.flow.first

private val ItemInset = 12.dp
private val PhoneGraphHeight = 220.dp
private val PaneGraphHeight = 320.dp
private val SidePaneWidth = 440.dp
private val BottomSpacing = 32.dp
private val PanelPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

/**
 * The equalizer page body. On a phone it is one list and a band opens in a sheet; beside a second
 * pane the curve and levels sit on the leading side and the bands, with the chosen band's controls,
 * on the other.
 */
@Composable
internal fun EqualizerMainContent(
    state: EqState,
    viewModel: EqualizerViewModel,
    selectedBand: Int?,
    onSelectBand: (Int?) -> Unit,
    panes: FlowPaneState,
    twoPane: Boolean,
    onOpenPresets: () -> Unit,
    onSaveAs: () -> Unit,
    onSystemEqualizer: (() -> Unit)?,
    modifier: Modifier = Modifier,
    highlight: String? = null,
) {
    val bottom = flowBottomContentPadding(BottomSpacing)
    val soundListState = rememberLazyListState()
    LaunchedEffect(highlight) {
        if (highlight != EqualizerIndex.spatialSound.key) return@LaunchedEffect
        val total = snapshotFlow { soundListState.layoutInfo.totalItemsCount }.first { it > 0 }
        soundListState.animateScrollToItem(total - if (onSystemEqualizer != null) 2 else 1)
    }
    val list: @Composable (LazyListState, LazyListScope.() -> Unit) -> Unit = { state, content ->
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(FlowSegmentedGap),
            contentPadding = PaddingValues(bottom = bottom),
            content = content,
        )
    }
    val section = EqSections(state, viewModel, selectedBand, onSelectBand, onOpenPresets, onSaveAs, onSystemEqualizer, highlight)

    if (!twoPane) {
        Box(modifier) {
            list(soundListState) {
                with(section) {
                    header()
                    curve(PhoneGraphHeight)
                    mode()
                    if (state.mode == EqMode.PARAMETRIC) bands() else graphic()
                    sound()
                    system()
                }
            }
        }
        return
    }
    FlowSidePanes(
        panes = panes,
        sidePaneWidth = SidePaneWidth,
        modifier = modifier,
        sidePane = {
            list(soundListState) {
                with(section) {
                    header()
                    curve(PaneGraphHeight)
                    mode()
                    sound()
                    system()
                }
            }
        },
        mainPane = {
            list(rememberLazyListState()) {
                with(section) {
                    if (state.mode == EqMode.PARAMETRIC) {
                        selectedEditor()
                        bands()
                    } else {
                        graphic()
                    }
                }
            }
        },
    )
}

/** Each part of the page as list items, shared by the one-pane and two-pane layouts. */
private class EqSections(
    val state: EqState,
    val viewModel: EqualizerViewModel,
    val selectedBand: Int?,
    val onSelectBand: (Int?) -> Unit,
    val onOpenPresets: () -> Unit,
    val onSaveAs: () -> Unit,
    val onSystemEqualizer: (() -> Unit)?,
    val highlight: String?,
) {
    private val bandList = state.active.curve.bands
    private val parametric = state.mode == EqMode.PARAMETRIC

    fun LazyListScope.header() {
        item("header") {
            EqFramed(Modifier.padding(top = 8.dp)) {
                FlowRowGroup {
                    FlowSwitchRow(
                        title = stringResource(R.string.equalizer),
                        checked = state.enabled,
                        onCheckedChange = viewModel::setEnabled,
                        leadingIcon = Icons.Rounded.GraphicEq,
                        shape = flowRowGroupShape(0, 2),
                    )
                    val edited = state.isEdited
                    FlowNavRow(
                        title = activePresetName(state),
                        supportingText = stringResource(if (edited) R.string.eq_edited else R.string.eq_presets),
                        onClick = onOpenPresets,
                        leadingIcon = Icons.Outlined.LibraryMusic,
                        shape = flowRowGroupShape(1, 2),
                        trailingContent =
                            if (edited) {
                                {
                                    Row {
                                        if (state.active.presetId != null) {
                                            TextButton(onClick = viewModel::revert) { Text(stringResource(R.string.eq_revert)) }
                                        }
                                        if (state.canSaveActive) {
                                            TextButton(onClick = viewModel::save) { Text(stringResource(R.string.save)) }
                                        } else {
                                            TextButton(onClick = onSaveAs) { Text(stringResource(R.string.eq_save_as)) }
                                        }
                                    }
                                }
                            } else {
                                null
                            },
                    )
                }
            }
        }
    }

    fun LazyListScope.curve(height: Dp) {
        item("curve") {
            EqFramed(Modifier.padding(horizontal = ItemInset, vertical = 8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EqResponseGraph(
                        bands = bandList,
                        bassBoost = state.bassBoost,
                        selectedIndex = selectedBand,
                        showPoints = parametric,
                        editable = parametric,
                        onBandClick = { onSelectBand(it) },
                        onAddBand = { frequency, gain -> viewModel.addBand(frequency, gain)?.let(onSelectBand) },
                        onBandPreview = viewModel::previewBand,
                        onBandCommit = viewModel::updateBand,
                        modifier = Modifier.fillMaxWidth().height(height),
                    )
                    if (parametric && bandList.isEmpty()) {
                        Text(
                            text = stringResource(R.string.eq_graph_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }

    fun LazyListScope.mode() {
        item("mode") {
            EqFramed(Modifier.padding(horizontal = ItemInset)) {
                FlowConnectedToggleGroup(
                    options =
                        listOf(
                            FlowToggleOption(EqMode.PARAMETRIC, stringResource(R.string.eq_mode_parametric), Icons.Rounded.Tune),
                            FlowToggleOption(EqMode.GRAPHIC, stringResource(R.string.eq_mode_graphic), Icons.Rounded.Equalizer),
                        ),
                    selected = state.mode,
                    onSelected = viewModel::setMode,
                )
            }
        }
    }

    fun LazyListScope.bands() {
        item("bands-header") {
            EqFramed {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlowSectionHeader(stringResource(R.string.eq_bands), Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.eq_band_count, bandList.size, EqLimits.MAX_BANDS),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 20.dp, top = 14.dp),
                    )
                }
            }
        }
        itemsIndexed(bandList, key = { index, _ -> "band-$index" }) { index, band ->
            EqFramed(Modifier.padding(horizontal = ItemInset)) {
                EqBandRow(
                    index = index,
                    band = band,
                    shape = flowRowGroupShape(index, bandList.size + 1),
                    selected = selectedBand == index,
                    onClick = { onSelectBand(index) },
                    onToggle = { viewModel.toggleBand(index) },
                )
            }
        }
        item("add-band") {
            EqFramed(Modifier.padding(horizontal = ItemInset)) {
                FlowNavRow(
                    title = stringResource(R.string.eq_add_band),
                    onClick = { viewModel.addBand()?.let(onSelectBand) },
                    leadingIcon = Icons.Rounded.Add,
                    showChevron = false,
                    enabled = bandList.size < EqLimits.MAX_BANDS,
                    shape = flowRowGroupShape(bandList.size, bandList.size + 1),
                )
            }
        }
    }

    fun LazyListScope.selectedEditor() {
        val index = selectedBand ?: return
        if (index !in bandList.indices) return
        item("editor") {
            EqFramed(Modifier.padding(horizontal = ItemInset, vertical = 8.dp)) {
                PanelRow(shape = MaterialTheme.shapes.large) {
                    EqBandEditor(
                        index = index,
                        bands = bandList,
                        bassBoost = state.bassBoost,
                        showGraph = false,
                        onChange = { viewModel.updateBand(index, it) },
                        onRemove = {
                            onSelectBand(null)
                            viewModel.removeBand(index)
                        },
                        modifier = Modifier.padding(PanelPadding),
                    )
                }
            }
        }
    }

    fun LazyListScope.graphic() {
        item("graphic") {
            EqFramed(Modifier.padding(horizontal = ItemInset, vertical = 8.dp)) {
                PanelRow(shape = MaterialTheme.shapes.large) {
                    EqGraphicBands(
                        gains = GraphicEq.gainsOf(state.graphic.curve),
                        onGainChange = viewModel::setGraphicGain,
                        modifier = Modifier.padding(PanelPadding),
                    )
                }
            }
        }
    }

    fun LazyListScope.sound() {
        item("sound") {
            EqFramed {
                Column {
                    FlowSectionHeader(stringResource(R.string.eq_settings_section))
                    val spatialOn = state.spatialSound != EqSpatialSound.OFF
                    val rows = (if (state.autoPreamp) 3 else 4) + (if (spatialOn) 1 else 0)
                    val bassRow = if (state.autoPreamp) 1 else 2
                    FlowRowGroup {
                        FlowSwitchRow(
                            title = stringResource(R.string.eq_auto_preamp),
                            supportingText = autoPreampSummary(state),
                            checked = state.autoPreamp,
                            onCheckedChange = viewModel::setAutoPreamp,
                            shape = flowRowGroupShape(0, rows),
                        )
                        if (!state.autoPreamp) {
                            PanelRow(shape = flowRowGroupShape(1, rows)) {
                                ManualPreamp(value = state.active.curve.preamp, onValueChange = viewModel::setPreamp)
                            }
                        }
                        PanelRow(shape = flowRowGroupShape(bassRow, rows)) {
                            EqBassBoostSlider(
                                value = state.bassBoost,
                                onValueChange = viewModel::setBassBoost,
                                enabled = state.enabled,
                                modifier = Modifier.padding(PanelPadding),
                            )
                        }
                        val spatialShape = flowRowGroupShape(bassRow + 1, rows)
                        CompositionLocalProvider(LocalSettingsHighlight provides highlight) {
                            SettingsHighlightFrame(EqualizerIndex.spatialSound.key, spatialShape) {
                                FlowSwitchRow(
                                    title = stringResource(R.string.eq_spatial_sound),
                                    supportingText = stringResource(R.string.eq_spatial_sound_summary),
                                    checked = spatialOn,
                                    onCheckedChange = { on ->
                                        viewModel.setSpatialSound(if (on) EqSpatialSound.MEDIUM else EqSpatialSound.OFF)
                                    },
                                    enabled = state.enabled,
                                    shape = spatialShape,
                                    titleBadge = EqualizerIndex.spatialSound.isNew(),
                                )
                            }
                        }
                        if (spatialOn) {
                            PanelRow(shape = flowRowGroupShape(rows - 1, rows)) {
                                FlowConnectedToggleGroup(
                                    options =
                                        listOf(
                                            FlowToggleOption(EqSpatialSound.LOW, stringResource(R.string.eq_spatial_low)),
                                            FlowToggleOption(EqSpatialSound.MEDIUM, stringResource(R.string.eq_spatial_medium)),
                                            FlowToggleOption(EqSpatialSound.HIGH, stringResource(R.string.eq_spatial_high)),
                                        ),
                                    selected = state.spatialSound,
                                    onSelected = viewModel::setSpatialSound,
                                    enabled = state.enabled,
                                    modifier = Modifier.padding(PanelPadding),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun LazyListScope.system() {
        val open = onSystemEqualizer ?: return
        item("system") {
            EqFramed(Modifier.padding(top = 16.dp)) {
                FlowRowGroup {
                    FlowNavRow(
                        title = stringResource(R.string.eq_system),
                        supportingText = stringResource(R.string.eq_system_desc),
                        onClick = open,
                        leadingIcon = Icons.AutoMirrored.Outlined.OpenInNew,
                        showChevron = false,
                        shape = flowRowGroupShape(0, 1),
                    )
                }
            }
        }
    }
}

@Composable
private fun autoPreampSummary(state: EqState): String {
    val preamp =
        remember(state.active.curve, state.bassBoost) {
            state.copy(enabled = true, autoPreamp = true).processingSpec(bypass = false).preampDb
        }
    return if (preamp < -AUDIBLE_DB) {
        stringResource(R.string.eq_auto_preamp_active, formatGain(preamp), formatGain(-preamp))
    } else {
        stringResource(R.string.eq_auto_preamp_idle)
    }
}

@Composable
private fun ManualPreamp(
    value: Double,
    onValueChange: (Double) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(PanelPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.eq_preamp), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(formatGain(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toDouble()) },
            valueRange = EqLimits.MIN_PREAMP.toFloat()..EqLimits.MAX_PREAMP.toFloat(),
        )
    }
}

/** Centres [content] and caps it at the app's reading width, as the settings pages do. */
@Composable
internal fun EqFramed(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = FlowMaxContentWidth).fillMaxWidth().then(modifier)) { content() }
    }
}

private const val AUDIBLE_DB = 0.05
