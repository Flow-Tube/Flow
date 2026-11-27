package io.github.aedev.flow.data.scrobble

import kotlinx.serialization.Serializable

/** The services Flow can scrobble to. The names are stored, so never rename them. */
enum class ScrobbleService {
    LASTFM,
    LIBREFM,
    LISTENBRAINZ,
}

/** One finished listen, as every service needs it. */
@Serializable
data class ScrobbleEntry(
    val artist: String,
    val title: String,
    val album: String = "",
    val durationSec: Int = 0,
    val timestampSec: Long,
    val fromYouTube: Boolean = true,
    val videoId: String = "",
    val thumbnailUrl: String = "",
)

/** A like or unlike to mirror as a love on Last.fm and Libre.fm. */
@Serializable
data class LoveEntry(
    val artist: String,
    val title: String,
    val loved: Boolean,
    val videoId: String = "",
)

/** An address of the viewer's own that gets every listen as a Web Scrobbler webhook event. */
@Serializable
data class ScrobbleWebhook(
    val id: String,
    val name: String,
    val url: String,
)

/** Where queued listens and loves go: a signed-in service or one of the viewer's webhooks. */
sealed interface ScrobbleTarget {
    data class Service(
        val service: ScrobbleService,
    ) : ScrobbleTarget

    data class Webhook(
        val id: String,
    ) : ScrobbleTarget
}

/** A signed-in account: the name to show and the key or token the service gave, kept sealed at rest. */
data class ScrobbleAccount(
    val userName: String,
    val secret: String,
)

/** What one attempt to send left behind. */
sealed interface SendOutcome {
    data object Sent : SendOutcome

    /** Network trouble or the service is down; keep the entries and try later. */
    data object Retry : SendOutcome

    /** The service no longer accepts the account; the viewer has to sign in again. */
    data object SignedOut : SendOutcome
}
