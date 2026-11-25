package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.data.images.PostImage
import io.github.aedev.flow.innertube.pages.renderer.PollChoice
import io.github.aedev.flow.innertube.pages.renderer.PostAttachment
import io.github.aedev.flow.ui.theme.extendedColors

private const val IMAGE_POLL_COLUMNS = 2

/** A post's poll: a list of choices, or a grid of pictures when the creator gave each choice one. */
@Composable
internal fun MediaPostPoll(
    poll: PostAttachment.Poll,
    modifier: Modifier = Modifier,
) {
    val hasImages = poll.choices.any { it.imageUrl != null }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = PostHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (hasImages) {
            poll.choices.chunked(IMAGE_POLL_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { choice -> ImagePollChoice(choice, Modifier.weight(1f)) }
                    repeat(IMAGE_POLL_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            poll.choices.forEach { choice -> TextPollChoice(choice) }
        }
        poll.totalVotesText?.takeIf { it.isNotBlank() }?.let { total ->
            Text(
                text = total,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.extendedColors.textSecondary,
            )
        }
    }
}

@Composable
private fun TextPollChoice(choice: PollChoice) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = choice.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            choice.voteCountText?.takeIf { it.isNotBlank() }?.let { votes ->
                Spacer(Modifier.width(12.dp))
                VoteCount(votes)
            }
        }
        ChoiceRatio(choice)
    }
}

@Composable
private fun ImagePollChoice(
    choice: PollChoice,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier, shape = MaterialTheme.shapes.medium) {
        AsyncImage(
            model = choice.imageUrl?.let(::PostImage),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = choice.text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            choice.voteCountText?.takeIf { it.isNotBlank() }?.let { VoteCount(it) }
            ChoiceRatio(choice)
        }
    }
}

@Composable
private fun VoteCount(votes: String) {
    Text(
        text = votes,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.extendedColors.textSecondary,
    )
}

@Composable
private fun ChoiceRatio(choice: PollChoice) {
    choice.voteRatio?.let { ratio ->
        LinearProgressIndicator(
            progress = { ratio.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
