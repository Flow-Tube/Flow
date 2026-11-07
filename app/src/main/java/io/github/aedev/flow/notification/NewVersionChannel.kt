package io.github.aedev.flow.notification

private const val UPDATE_CHANNEL_NIGHTLY = "nightly"

/**
 * Stable releases are rare enough to make a sound, on a channel of their own because a channel's
 * importance can't be raised once it exists. Nightly stays silent: it could sound twice a day. A
 * person who blocked the old channel stays on it, so their choice still holds.
 */
internal fun newVersionChannel(
    updateChannel: String,
    updatesChannelBlocked: Boolean,
): String =
    if (updateChannel == UPDATE_CHANNEL_NIGHTLY || updatesChannelBlocked) {
        NotificationHelper.CHANNEL_UPDATES
    } else {
        NotificationHelper.CHANNEL_NEW_VERSION
    }
