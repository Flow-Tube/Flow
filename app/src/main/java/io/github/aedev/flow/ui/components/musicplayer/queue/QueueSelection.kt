package io.github.aedev.flow.ui.components.musicplayer.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.shared.FlowSelectionAction

/** Which queue rows are picked, by their [queueKeys]; null while the queue isn't in select mode. */
@Stable
internal class QueueSelection {
    var keys by mutableStateOf<Set<String>?>(null)
        private set

    val active: Boolean get() = keys != null

    fun start(key: String? = null) {
        keys = setOfNotNull(key)
    }

    fun toggle(key: String) {
        keys = keys?.let { if (key in it) it - key else it + key }
    }

    /** Picks every row, or none when every row already is. */
    fun toggleAll(all: List<String>) {
        keys = if (keys.orEmpty().containsAll(all)) emptySet() else all.toSet()
    }

    /** Drops picks whose rows have left the queue. */
    fun retain(present: List<String>) {
        keys = keys?.intersect(present.toSet())
    }

    fun end() {
        keys = null
    }
}

/** A key per queued song that survives reordering: its id, told apart for duplicates. */
internal fun queueKeys(queue: List<MusicTrack>): List<String> {
    val seen = HashMap<String, Int>()
    return queue.map { track ->
        val occurrence = (seen[track.videoId] ?: 0) + 1
        seen[track.videoId] = occurrence
        "${track.videoId}#$occurrence"
    }
}

/**
 * The moves, each as (from, to) for `add(to, removeAt(from))`, that put the picked rows straight
 * after the playing one in their queue order. The playing row itself is never moved.
 */
internal fun playNextMoves(
    picked: List<Int>,
    current: Int,
    size: Int,
): List<Pair<Int, Int>> {
    val order = MutableList(size) { it }
    val moves = mutableListOf<Pair<Int, Int>>()
    picked
        .filter { it != current && it in 0 until size }
        .sorted()
        .forEachIndexed { placed, original ->
            val from = order.indexOf(original)
            val item = order.removeAt(from)
            val to = order.indexOf(current) + 1 + placed
            order.add(to, item)
            if (from != to) moves += from to to
        }
    return moves
}

/** The picked rows to remove, last first so each index still points at its row; never the playing one. */
internal fun removalOrder(
    picked: List<Int>,
    current: Int,
): List<Int> = picked.filter { it != current }.sortedDescending()

/** Count, Select all and Close, above the rows while the queue is in select mode. */
@Composable
internal fun QueueSelectionBar(
    count: Int,
    allPicked: Boolean,
    onToggleAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
        }
        Text(
            text = pluralStringResource(R.plurals.selected_count_template, count, count),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleAll) {
            Icon(
                imageVector = if (allPicked) Icons.Rounded.CheckBox else Icons.Rounded.SelectAll,
                contentDescription = stringResource(if (allPicked) R.string.deselect_all else R.string.select_all),
            )
        }
    }
}

/** What the queue can do with its picked songs. */
@Composable
internal fun queueSelectionActions(
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
): List<FlowSelectionAction> =
    listOf(
        FlowSelectionAction(Icons.Rounded.QueuePlayNext, stringResource(R.string.play_next), onClick = onPlayNext),
        FlowSelectionAction(Icons.AutoMirrored.Rounded.PlaylistAdd, stringResource(R.string.add_to_playlist), onClick = onAddToPlaylist),
        FlowSelectionAction(Icons.Rounded.Download, stringResource(R.string.download), onClick = onDownload),
        FlowSelectionAction(Icons.Rounded.RemoveCircleOutline, stringResource(R.string.remove_from_queue), onClick = onRemove),
    )
