package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.innertube.pages.VideoDescriptionDisclosure
import io.github.aedev.flow.utils.RichTextLinkActions
import io.github.aedev.flow.utils.toAnnotatedString

/** The "How this was made" disclosure: what was altered or generated, in the server's own words. */
@Composable
internal fun DescriptionDisclosureCard(
    disclosure: VideoDescriptionDisclosure,
    tint: MediaArtworkTint,
    actions: RichTextLinkActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(DescriptionChipSpacing)) {
        Text(
            text = disclosure.title ?: stringResource(R.string.description_how_this_was_made),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = tint.container,
            contentColor = tint.onContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(CardPadding),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                disclosure.header?.let {
                    Text(text = it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                disclosure.body?.let { body ->
                    val text =
                        remember(body, tint, actions) {
                            body.toAnnotatedString(linkColor = tint.accent, textColor = tint.onContainer, actions = actions)
                        }
                    Text(text = text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private val CardPadding = 16.dp
