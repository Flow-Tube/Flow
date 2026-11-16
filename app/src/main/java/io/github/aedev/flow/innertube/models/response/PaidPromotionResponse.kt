package io.github.aedev.flow.innertube.models.response

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

private const val STATUS_OK = "OK"
private const val DEFAULT_DURATION_MS = 10_000L

/** YouTube's own wording and how long it shows the label, for a video that declared paid promotion. */
@Immutable
data class PaidPromotionLabel(
    val text: String?,
    val durationMs: Long,
)

/**
 * The fields of an IOS player response that carry the label. The playing client's response has no
 * such field, so this is a request of its own, masked down to these two fields.
 */
@Serializable
internal data class PaidPromotionResponse(
    val playabilityStatus: Status? = null,
    val miniPlayerByline: Byline? = null,
) {
    @Serializable
    data class Status(
        val status: String? = null,
    )

    @Serializable
    data class Byline(
        val miniPlayerBylineRenderer: Renderer? = null,
    )

    @Serializable
    data class Renderer(
        val text: Text? = null,
        val durationMs: String? = null,
    )

    @Serializable
    data class Text(
        val runs: List<Run>? = null,
    )

    @Serializable
    data class Run(
        val text: String? = null,
    )

    fun label(): PaidPromotionLabel? {
        if (playabilityStatus?.status != STATUS_OK) return null
        val renderer = miniPlayerByline?.miniPlayerBylineRenderer ?: return null
        return PaidPromotionLabel(
            text =
                renderer.text
                    ?.runs
                    ?.firstOrNull()
                    ?.text
                    ?.takeIf { it.isNotBlank() },
            durationMs = renderer.durationMs?.toLongOrNull()?.takeIf { it > 0L } ?: DEFAULT_DURATION_MS,
        )
    }
}
