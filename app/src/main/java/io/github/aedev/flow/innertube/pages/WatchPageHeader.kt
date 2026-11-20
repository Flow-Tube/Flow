package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.RichText
import io.github.aedev.flow.data.model.RichTextSpan
import io.github.aedev.flow.data.model.RichTextTarget
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What the watch page prints around the title and the channel name, beyond the text itself. */
data class WatchPageHeader(
    /** The hashtags or the place YouTube prints above the title. */
    val superTitle: RichText? = null,
)

internal fun JsonElement.toWatchPageHeader(ownVideoId: String?): WatchPageHeader {
    val primary = watchInfoRenderer("videoPrimaryInfoRenderer")
    // Flow has a page for a hashtag but none for a place, so a location run stays plain text.
    val superTitle =
        primary?.get("superTitleLink").runsToRichText(ownVideoId)?.let { line ->
            line.copy(spans = line.spans.filter { it.target is RichTextTarget.Hashtag })
        }
    return WatchPageHeader(superTitle = superTitle)
}

/** One of the info renderers the web watch response stacks under the player. */
internal fun JsonElement.watchInfoRenderer(name: String): JsonObject? =
    objectOrNull()
        ?.get("contents")
        .objectOrNull()
        ?.get("twoColumnWatchNextResults")
        .objectOrNull()
        ?.get("results")
        .objectOrNull()
        ?.get("results")
        .objectOrNull()
        ?.get("contents")
        .arrayOrNull()
        .orEmpty()
        .firstNotNullOfOrNull { it.objectOrNull()?.get(name).objectOrNull() }

/** Runs joined into one text, each run that carries an endpoint Flow can follow kept as a span. */
internal fun JsonElement?.runsToRichText(ownVideoId: String?): RichText? {
    val runs = objectOrNull()?.get("runs").arrayOrNull() ?: return null
    val text = StringBuilder()
    val spans = mutableListOf<RichTextSpan>()
    runs.forEach { element ->
        val run = element.objectOrNull() ?: return@forEach
        val runText = run["text"].stringOrNull().orEmpty()
        if (runText.isEmpty()) return@forEach
        run["navigationEndpoint"].objectOrNull()?.toRichTextTarget(ownVideoId)?.let { target ->
            spans += RichTextSpan(start = text.length, length = runText.length, target = target)
        }
        text.append(runText)
    }
    return RichText(text = text.toString(), spans = spans).takeIf { it.text.isNotBlank() }
}
