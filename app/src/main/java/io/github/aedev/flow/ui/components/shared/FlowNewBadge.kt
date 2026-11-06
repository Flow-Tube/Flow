package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Badge
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.theme.newBadgeColor

private val BadgeSpacing = 6.dp

/** The small dot that marks something new, such as a setting added in an update. */
@Composable
fun FlowNewBadge(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.new_badge_label)
    Badge(
        modifier = modifier.semantics { contentDescription = label },
        containerColor = newBadgeColor(MaterialTheme.colorScheme.surface),
    )
}

/** A row title, followed by a [FlowNewBadge] when [badge] is set; without it, a plain [Text]. */
@Composable
fun FlowBadgedTitle(
    title: String,
    badge: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    color: Color = Color.Unspecified,
) {
    val textStyle = style ?: LocalTextStyle.current
    if (!badge) {
        Text(text = title, modifier = modifier, style = textStyle, color = color)
        return
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BadgeSpacing),
    ) {
        Text(text = title, modifier = Modifier.weight(1f, fill = false), style = textStyle, color = color)
        FlowNewBadge()
    }
}
