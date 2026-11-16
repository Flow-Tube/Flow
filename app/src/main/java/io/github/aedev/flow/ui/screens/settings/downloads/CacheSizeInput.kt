package io.github.aedev.flow.ui.screens.settings.downloads

import io.github.aedev.flow.data.local.MediaCacheSizes
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

internal enum class CacheSizeUnit(
    val megabytes: Int,
) {
    MB(1),
    GB(1024),
}

/** A cache size typed in by hand, as text in a unit, and the megabytes it is stored as. */
internal object CacheSizeInput {
    private const val MB_PER_GB = 1024

    /** The megabytes for [text] in [unit], or null when it is not a number within the custom range. */
    fun parse(
        text: String,
        unit: CacheSizeUnit,
    ): Int? {
        val number = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (number.isNaN() || number.isInfinite()) return null
        val megabytes = (number * unit.megabytes).roundToInt()
        return megabytes.takeIf { it in MediaCacheSizes.CUSTOM_MIN_MB..MediaCacheSizes.CUSTOM_MAX_MB }
    }

    /** The unit a stored size reads best in: whole and fractional gigabytes from 1 GB up. */
    fun unitFor(megabytes: Int): CacheSizeUnit = if (megabytes >= MB_PER_GB) CacheSizeUnit.GB else CacheSizeUnit.MB

    /** [megabytes] as a number in [unit], with at most one decimal, in [locale]. */
    fun format(
        megabytes: Int,
        unit: CacheSizeUnit,
        locale: Locale = Locale.getDefault(),
    ): String {
        val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
        return format.format(megabytes.toDouble() / unit.megabytes)
    }
}
