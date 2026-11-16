package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object MediaRowDefaults {
    val HorizontalPadding: Dp = 16.dp
    val VerticalPadding: Dp = 10.dp
    val Spacing: Dp = 14.dp
    val TextSpacing: Dp = 2.dp
    val ActionIconSize: Dp = 20.dp
    const val SELECTION_ALPHA = 0.24f
}

@Composable
fun MediaRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    supporting: String? = null,
    supportingColor: Color = MaterialTheme.colorScheme.primary,
    titleMaxLines: Int = 2,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    subtitleLeading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    thumbnail: @Composable () -> Unit,
) {
    val background =
        if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = MediaRowDefaults.SELECTION_ALPHA)
        } else {
            Color.Transparent
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(background)
                .then(
                    if (onClick != null || onLongClick != null) {
                        Modifier
                            .then(if (onLongClick != null) Modifier.onMenuKey(onLongClick) else Modifier)
                            .combinedClickable(
                                onClick = onClick ?: {},
                                onLongClick = onLongClick,
                                role = Role.Button,
                            )
                    } else {
                        Modifier
                    },
                ).padding(
                    horizontal = MediaRowDefaults.HorizontalPadding,
                    vertical = MediaRowDefaults.VerticalPadding,
                ),
        horizontalArrangement = Arrangement.spacedBy(MediaRowDefaults.Spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()

        thumbnail()

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MediaRowDefaults.TextSpacing),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )

            if (subtitle != null || subtitleLeading != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    subtitleLeading?.invoke()
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = supportingColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (actions != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }

        trailing?.invoke(this)
    }
}

@Composable
fun MediaRowAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(MediaRowDefaults.ActionIconSize),
        )
    }
}
