package io.github.aedev.flow.ui.screens.settings.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.recommendation.FlowPersona
import io.github.aedev.flow.ui.components.shared.FlowSpinner
import io.github.aedev.flow.ui.screens.settings.taste.PersonaEmblem

private val CardPadding = 16.dp
private val CardSpacing = 16.dp
private val EmblemSize = 56.dp

/** The way into Your taste: the persona the engine has settled on, one tap from the full picture. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PersonaEntryCard(
    persona: FlowPersona?,
    selected: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onOpen,
        selected = selected,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(CardPadding),
            horizontalArrangement = Arrangement.spacedBy(CardSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (persona != null) {
                PersonaEmblem(persona, Modifier.size(EmblemSize))
            } else {
                FlowSpinner(Modifier.size(EmblemSize))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.taste_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = persona?.let { stringResource(it.titleRes) } ?: stringResource(R.string.taste_card_loading),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = persona?.let { stringResource(it.descriptionRes) } ?: stringResource(R.string.taste_card_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
        }
    }
}
