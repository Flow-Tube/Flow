package io.github.aedev.flow.ui.components.channel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.innertube.pages.channel.ChannelHeader
import io.github.aedev.flow.ui.components.shared.FlowSelectionContainer
import io.github.aedev.flow.ui.theme.extendedColors

/**
 * Everything the About panel returned, and only that: each row is present exactly when its field is.
 */
@Composable
internal fun ChannelAboutSection(header: ChannelHeader) {
    val uriHandler = LocalUriHandler.current
    val moreInfo =
        remember(header) {
            listOfNotNull(
                header.canonicalUrl?.let { Icons.Outlined.Language to it.removePrefix("http://").removePrefix("https://") },
                header.handle?.let { Icons.Outlined.Person to it },
                header.subscriberCountText?.let { Icons.Outlined.Person to it },
                header.videoCountText?.let { Icons.Outlined.VideoLibrary to it },
                header.viewCountText?.let { Icons.Outlined.TrendingUp to it },
                header.countryText?.let { Icons.Outlined.Public to it },
                header.joinedDateText?.let { Icons.Outlined.CalendarMonth to it },
            )
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (!header.description.isNullOrBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChannelAboutHeading(stringResource(R.string.about))
                FlowSelectionContainer {
                    Text(
                        text = header.description,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (header.links.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ChannelAboutHeading(stringResource(R.string.channel_about_links))
                header.links.forEach { link ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { uriHandler.openUri(link.url) },
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (link.iconUrl.isNullOrBlank()) {
                            Icon(
                                imageVector = Icons.Outlined.Link,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.extendedColors.textSecondary,
                            )
                        } else {
                            AsyncImage(
                                model = link.iconUrl,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Column {
                            Text(
                                text = link.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = link.displayText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (moreInfo.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 0.5.dp)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ChannelAboutHeading(stringResource(R.string.channel_about_more_info))
                FlowSelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        moreInfo.forEach { (icon, value) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = MaterialTheme.extendedColors.textSecondary,
                                )
                                Text(text = value, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelAboutHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}
