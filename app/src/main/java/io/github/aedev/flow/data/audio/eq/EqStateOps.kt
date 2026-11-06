package io.github.aedev.flow.data.audio.eq

/**
 * Every change the equalizer supports, as pure functions of [EqState]. The repository applies them
 * and persists the result; the tests exercise them without Android.
 */
fun EqState.userPreset(id: String?): EqPreset? = id?.let { wanted -> userPresets.firstOrNull { it.id == wanted } }

fun EqState.presetCurve(id: String?): EqCurve? = BuiltInEqPresets.byId(id)?.curve ?: userPreset(id)?.curve

private fun EqState.presetMode(id: String?): EqMode? =
    when {
        BuiltInEqPresets.isBuiltIn(id) -> EqMode.PARAMETRIC
        else -> userPreset(id)?.mode
    }

private fun defaultCurve(mode: EqMode): EqCurve = if (mode == EqMode.PARAMETRIC) EqCurve() else GraphicEq.flatCurve()

/**
 * Preset [id] as the given [mode] plays it: a graphic preset's ten bands load as parametric bands as
 * they are, and a parametric preset is fitted onto the ten graphic bands.
 */
fun EqState.presetCurveFor(
    mode: EqMode,
    id: String?,
): EqCurve? {
    val curve = presetCurve(id) ?: return null
    return when {
        mode == EqMode.PARAMETRIC -> curve.sanitized()
        presetMode(id) == EqMode.GRAPHIC -> sanitizedFor(EqMode.GRAPHIC, curve)
        else -> GraphicEq.fit(curve)
    }
}

/** The curve differs from the preset it came from, or there is no preset and it is not flat. */
val EqState.isEdited: Boolean
    get() = active.curve != (presetCurveFor(mode, active.presetId) ?: defaultCurve(mode))

/** The equalizer is on and something in it alters the signal. */
val EqState.changesSound: Boolean
    get() =
        enabled &&
            (
                bassBoost > 0.0 ||
                    spatialSound != EqSpatialSound.OFF ||
                    active.curve.bands.any { !EqFilterMath.isBypassed(it, EqFilterMath.REFERENCE_SAMPLE_RATE) } ||
                    (!autoPreamp && active.curve.preamp != 0.0)
            )

/** Save can overwrite the source preset only when it is one of the user's own. */
val EqState.canSaveActive: Boolean
    get() = isEdited && userPreset(active.presetId)?.mode == mode

private fun EqState.withActive(working: EqWorkingCopy): EqState =
    if (mode == EqMode.PARAMETRIC) copy(parametric = working) else copy(graphic = working)

fun EqState.withActiveCurve(curve: EqCurve): EqState = withActive(active.copy(curve = sanitizedFor(mode, curve)))

/** Plays preset [id] in the current mode; the mode itself never changes here. */
fun EqState.selectPreset(id: String): EqState {
    val curve = presetCurveFor(mode, id) ?: return this
    return withActive(EqWorkingCopy(id, curve))
}

fun EqState.revert(): EqState = withActiveCurve(presetCurveFor(mode, active.presetId) ?: defaultCurve(mode))

fun EqState.saveActive(): EqState {
    if (!canSaveActive) return this
    val id = active.presetId
    return copy(userPresets = userPresets.map { if (it.id == id) it.copy(curve = active.curve) else it })
}

fun EqState.saveActiveAs(
    id: String,
    name: String,
): EqState =
    copy(userPresets = userPresets + EqPreset(id = id, name = name, curve = active.curve, mode = mode))
        .withActive(EqWorkingCopy(id, active.curve))

fun EqState.renamePreset(
    id: String,
    name: String,
): EqState = copy(userPresets = userPresets.map { if (it.id == id) it.copy(name = name) else it })

fun EqState.duplicatePreset(
    sourceId: String,
    newId: String,
    name: String,
): EqState {
    val curve = presetCurve(sourceId) ?: return this
    val mode = presetMode(sourceId) ?: return this
    return copy(userPresets = userPresets + EqPreset(id = newId, name = name, curve = curve, mode = mode))
}

/** A deleted preset and where it was, so Undo can put it back in place. */
data class DeletedEqPreset(
    val preset: EqPreset,
    val index: Int,
    val selectedIn: Set<EqMode>,
)

fun EqState.deletePreset(id: String): Pair<EqState, DeletedEqPreset?> {
    val index = userPresets.indexOfFirst { it.id == id }
    if (index < 0) return this to null
    val selectedIn =
        buildSet {
            if (parametric.presetId == id) add(EqMode.PARAMETRIC)
            if (graphic.presetId == id) add(EqMode.GRAPHIC)
        }
    val next =
        copy(
            userPresets = userPresets.filterNot { it.id == id },
            parametric = if (parametric.presetId == id) parametric.copy(presetId = null) else parametric,
            graphic = if (graphic.presetId == id) graphic.copy(presetId = null) else graphic,
        )
    return next to DeletedEqPreset(userPresets[index], index, selectedIn)
}

fun EqState.restorePreset(deleted: DeletedEqPreset): EqState {
    if (userPreset(deleted.preset.id) != null) return this
    val presets = userPresets.toMutableList().apply { add(deleted.index.coerceIn(0, size), deleted.preset) }
    val id = deleted.preset.id
    return copy(
        userPresets = presets,
        parametric =
            if (EqMode.PARAMETRIC in deleted.selectedIn && parametric.presetId == null) parametric.copy(presetId = id) else parametric,
        graphic = if (EqMode.GRAPHIC in deleted.selectedIn && graphic.presetId == null) graphic.copy(presetId = id) else graphic,
    )
}

fun EqState.importPreset(
    id: String,
    name: String,
    curve: EqCurve,
): EqState =
    copy(mode = EqMode.PARAMETRIC, userPresets = userPresets + EqPreset(id = id, name = name, curve = curve.sanitized(), imported = true))
        .selectPreset(id)

fun EqState.withBassBoost(db: Double): EqState = copy(bassBoost = db.coerceIn(0.0, EqLimits.MAX_BASS_BOOST))

fun EqState.withManualPreamp(db: Double): EqState = withActiveCurve(active.curve.copy(preamp = db))

fun EqState.withBand(
    index: Int,
    band: EqBand,
): EqState {
    val bands = active.curve.bands
    if (index !in bands.indices) return this
    return withActiveCurve(active.curve.copy(bands = bands.toMutableList().also { it[index] = band }))
}

fun EqState.addBand(band: EqBand): EqState {
    val bands = active.curve.bands
    if (mode != EqMode.PARAMETRIC || bands.size >= EqLimits.MAX_BANDS) return this
    return withActiveCurve(active.curve.copy(bands = bands + band))
}

fun EqState.removeBand(index: Int): EqState {
    val bands = active.curve.bands
    if (mode != EqMode.PARAMETRIC || index !in bands.indices) return this
    return withActiveCurve(active.curve.copy(bands = bands.filterIndexed { i, _ -> i != index }))
}

fun EqState.insertBand(
    index: Int,
    band: EqBand,
): EqState {
    val bands = active.curve.bands
    if (mode != EqMode.PARAMETRIC || bands.size >= EqLimits.MAX_BANDS) return this
    return withActiveCurve(active.curve.copy(bands = bands.toMutableList().apply { add(index.coerceIn(0, size), band) }))
}

/** True when [name] is free among the user's presets and the built-in names shown to them. */
fun EqState.isNameAvailable(
    name: String,
    builtInNames: Collection<String>,
    exceptId: String? = null,
): Boolean {
    val wanted = name.trim()
    if (wanted.isEmpty()) return false
    val taken = userPresets.filter { it.id != exceptId }.map { it.name } + builtInNames
    return taken.none { it.equals(wanted, ignoreCase = true) }
}

fun normalizedPresetName(raw: String): String = raw.trim().take(EqLimits.MAX_NAME_LENGTH)

/**
 * What the processors should run. [preview] replaces the active curve while a point is dragged, so
 * the sound follows the finger without the saved state or the UI changing every frame.
 */
fun EqState.processingSpec(
    bypass: Boolean,
    preview: EqCurve? = null,
): EqProcessingSpec {
    if (!enabled || bypass) return EqProcessingSpec.OFF
    val curve = preview ?: active.curve
    val bands =
        buildList {
            addAll(curve.bands.filter { it.enabled })
            if (bassBoost > 0.0) {
                add(EqBand(EqLimits.BASS_BOOST_FREQUENCY, bassBoost, EqLimits.DEFAULT_SHELF_Q, EqFilterType.LOW_SHELF))
            }
        }
    val preamp = if (autoPreamp) EqFilterMath.autoPreampDb(bands) else curve.preamp
    return EqProcessingSpec(enabled = true, preampDb = preamp, bands = bands, spatialSound = spatialSound)
}

/** Clamps every value and repairs anything a hand-edited backup or an old version could hold. */
fun EqState.sanitized(): EqState =
    copy(
        parametric = parametric.copy(curve = parametric.curve.sanitized()),
        graphic = graphic.copy(curve = sanitizedFor(EqMode.GRAPHIC, graphic.curve)),
        bassBoost = bassBoost.takeIf { it.isFinite() }?.coerceIn(0.0, EqLimits.MAX_BASS_BOOST) ?: 0.0,
        userPresets =
            userPresets
                .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                .distinctBy { it.id }
                .map { it.copy(name = normalizedPresetName(it.name), curve = sanitizedFor(it.mode, it.curve)) },
    )

private fun sanitizedFor(
    mode: EqMode,
    curve: EqCurve,
): EqCurve =
    if (mode == EqMode.GRAPHIC) {
        GraphicEq.curveOf(GraphicEq.gainsOf(curve), curve.sanitized().preamp)
    } else {
        curve.sanitized()
    }
