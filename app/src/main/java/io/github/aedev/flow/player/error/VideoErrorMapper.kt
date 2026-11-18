package io.github.aedev.flow.player.error

import android.content.Context
import android.util.Log
import io.github.aedev.flow.R
import io.github.aedev.flow.network.appTrafficUsesVpn
import io.github.aedev.flow.player.stream.PlaybackBlock
import io.github.aedev.flow.player.stream.PlaybackBlockedException
import io.github.aedev.flow.player.stream.RefusalKind
import io.github.aedev.flow.player.stream.VideoUnavailableException
import io.github.aedev.flow.player.stream.YouTubeRefusal
import io.github.aedev.flow.utils.NetworkState
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Maps throwables (YouTube's refusals, ExoPlayer, the network layer, etc.) to
 * human-readable, actionable error messages shown in the video player. A refusal is
 * named the way YouTube names it: private, age-restricted, members-only, removed.
 */
object VideoErrorMapper {
    private const val TAG = "VideoErrorMapper"

    /**
     * Structured error result carrying a short display message and an optional
     * hint that gives the user more context or a suggested action.
     */
    data class VideoError(
        /** Short primary message — always present, suitable for a snackbar. */
        val message: String,
        /**
         * Optional secondary hint shown below the primary message in the error
         * panel (e.g. "Try a VPN or check your region settings.").
         */
        val hint: String? = null,
        /** Whether it is worth retrying (show a Retry button). */
        val isRetryable: Boolean = true,
        /** Whether this can be "fixed" by the user (vs. a server-side restriction). */
        val isUserActionable: Boolean = false,
        /** A fresh YouTube session may get past it, so the panel offers the reset. */
        val offersSessionReset: Boolean = false,
    )

    /**
     * Converts any [Throwable] thrown during stream resolution or playback to a
     * [VideoError] with a professional, non-technical message.
     *
     * @param context     Android context to resolve string resources.
     * @param throwable   The exception to map.
     * @param videoId     Optional video ID — used only for log output.
     */
    fun from(
        context: Context,
        throwable: Throwable?,
        videoId: String? = null,
    ): VideoError {
        Log.w(TAG, "Mapping error for video=$videoId: ${throwable?.javaClass?.simpleName}: ${throwable?.message}")

        return when {
            throwable != null && specificRefusal(throwable) != null -> {
                refusalError(context, specificRefusal(throwable)!!)
            }

            throwable is PlaybackBlockedException && throwable.block == PlaybackBlock.BOT_WALL -> {
                VideoError(
                    message = context.getString(R.string.error_bot_wall_session),
                    hint = context.getString(R.string.error_bot_wall_session_hint),
                    isRetryable = true,
                    isUserActionable = true,
                    offersSessionReset = true,
                )
            }

            // Only a wall that held for a fresh visitor names the network, and only a VPN that is
            // actually on is named: most walled users have none (#1354).
            throwable is PlaybackBlockedException && throwable.block == PlaybackBlock.NETWORK_WALL -> {
                VideoError(
                    message = context.getString(R.string.error_bot_wall),
                    hint =
                        context.getString(
                            networkWallHint(onVpn = context.appTrafficUsesVpn(), onWifi = NetworkState.isOnWifi(context)),
                        ),
                    isRetryable = true,
                    isUserActionable = true,
                    offersSessionReset = true,
                )
            }

            throwable is PlaybackBlockedException && throwable.block == PlaybackBlock.REGION -> {
                VideoError(
                    message = context.getString(R.string.error_geo_restricted),
                    hint = context.getString(R.string.error_geo_restricted_hint),
                    isRetryable = false,
                    isUserActionable = true,
                )
            }

            throwable is PlaybackBlockedException && throwable.block == PlaybackBlock.RATE_LIMITED -> {
                VideoError(
                    message = context.getString(R.string.error_rate_limited),
                    hint = context.getString(R.string.error_rate_limited_hint),
                    isRetryable = true,
                    isUserActionable = true,
                )
            }

            throwable is PlaybackBlockedException && throwable.block == PlaybackBlock.MEMBERS_OR_PAID -> {
                VideoError(
                    message = context.getString(R.string.error_members_or_paid),
                    hint = context.getString(R.string.error_members_or_paid_hint),
                    isRetryable = false,
                    isUserActionable = false,
                )
            }

            throwable is PlaybackBlockedException -> {
                VideoError(
                    message = context.getString(R.string.error_login_required),
                    hint = context.getString(R.string.error_login_required_hint),
                    isRetryable = true,
                    isUserActionable = false,
                )
            }

            throwable != null && unavailableCause(throwable) != null -> {
                refusalError(context, unavailableCause(throwable)?.refusal ?: YouTubeRefusal(RefusalKind.UNAVAILABLE))
            }

            // ── Network / connectivity errors ─────────────────────────────────

            throwable is UnknownHostException -> {
                VideoError(
                    message = context.getString(R.string.error_no_internet),
                    hint = context.getString(R.string.error_no_internet_hint),
                    isRetryable = true,
                    isUserActionable = true,
                )
            }

            throwable is SocketTimeoutException -> {
                VideoError(
                    message = context.getString(R.string.error_timeout),
                    hint = context.getString(R.string.error_timeout_hint),
                    isRetryable = true,
                    isUserActionable = true,
                )
            }

            throwable is kotlinx.coroutines.TimeoutCancellationException -> {
                VideoError(
                    message = context.getString(R.string.error_timeout), // Use common connection reset title
                    hint = context.getString(R.string.error_timeout_hint),
                    isRetryable = true,
                    isUserActionable = true,
                )
            }

            // ── ExoPlayer / media source errors ──────────────────────────────

            throwable != null && isExoHttpForbidden(throwable) -> {
                VideoError(
                    message = context.getString(R.string.error_http_forbidden),
                    hint = context.getString(R.string.error_http_forbidden_hint),
                    isRetryable = true,
                    isUserActionable = false,
                )
            }

            throwable != null && isExoHttpError(throwable) -> {
                val code = getExoHttpCode(throwable) ?: ""
                VideoError(
                    message = context.getString(R.string.error_http_general, code.toString()),
                    hint = context.getString(R.string.error_http_general_hint, code.toString()),
                    isRetryable = true,
                    isUserActionable = false,
                )
            }

            throwable != null && isExoSourceError(throwable) && !hasConnectivityCause(throwable) -> {
                VideoError(
                    message = context.getString(R.string.error_stream_failed),
                    hint = context.getString(R.string.error_stream_failed_hint),
                    isRetryable = true,
                    isUserActionable = false,
                )
            }

            throwable != null && isNetworkRelated(throwable) -> {
                VideoError(
                    message = context.getString(R.string.error_network),
                    hint = context.getString(R.string.error_network_hint),
                    isRetryable = true,
                    isUserActionable = true,
                )
            }

            throwable != null && isExoRendererError(throwable) -> {
                VideoError(
                    message = context.getString(R.string.error_codec_issue),
                    hint = context.getString(R.string.error_codec_issue_hint),
                    isRetryable = true,
                    isUserActionable = true,
                )
            }

            throwable != null && isExoError(throwable) -> {
                VideoError(
                    message = context.getString(R.string.error_playback_failed),
                    hint = context.getString(R.string.error_playback_failed_hint),
                    isRetryable = true,
                    isUserActionable = false,
                )
            }

            // ── Fallback ─────────────────────────────────────────────────────

            throwable != null -> {
                VideoError(
                    message = context.getString(R.string.error_generic),
                    hint = buildFallbackHint(throwable),
                    isRetryable = true,
                    isUserActionable = false,
                )
            }

            else -> {
                VideoError(
                    message = context.getString(R.string.error_generic),
                    hint = context.getString(R.string.error_generic_hint),
                    isRetryable = true,
                    isUserActionable = false,
                )
            }
        }
    }

    /**
     * Convenience overload for the timeout-only path where no exception is available.
     */
    fun fromTimeout(context: Context): VideoError =
        VideoError(
            message = context.getString(R.string.error_timeout),
            hint = context.getString(R.string.error_timeout_hint),
            isRetryable = true,
            isUserActionable = true,
        )

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun causes(t: Throwable): Sequence<Throwable> = generateSequence(t) { it.cause?.takeIf { cause -> cause !== it } }.take(8)

    // A source error over a dropped connection is the network's fault, not the stream's.
    private fun hasConnectivityCause(t: Throwable): Boolean =
        causes(t).any { it is java.net.UnknownHostException || it is java.net.SocketException || it is java.net.SocketTimeoutException }

    /** Where to send a walled user: off the VPN, or to the other kind of network than the one in use. */
    internal fun networkWallHint(
        onVpn: Boolean,
        onWifi: Boolean,
    ): Int =
        when {
            onVpn -> R.string.error_bot_wall_hint
            onWifi -> R.string.error_bot_wall_network_hint
            else -> R.string.error_bot_wall_mobile_data_hint
        }

    private fun unavailableCause(t: Throwable): VideoUnavailableException? =
        causes(t).filterIsInstance<VideoUnavailableException>().firstOrNull()

    /**
     * The refusal to name, when YouTube gave one more specific than a viewer block: a plain
     * "video unavailable" next to a region or membership block says less than the block does.
     */
    private fun specificRefusal(t: Throwable): YouTubeRefusal? =
        causes(t)
            .filterIsInstance<PlaybackBlockedException>()
            .firstOrNull()
            ?.refusal
            ?.takeIf { it.kind != RefusalKind.UNAVAILABLE }

    private fun refusalError(
        context: Context,
        refusal: YouTubeRefusal,
    ): VideoError {
        val (message, hint) =
            when (refusal.kind) {
                RefusalKind.PRIVATE -> {
                    R.string.error_private_video to context.getString(R.string.error_private_video_hint)
                }

                RefusalKind.AGE_RESTRICTED -> {
                    R.string.error_age_restricted to context.getString(R.string.error_age_restricted_hint)
                }

                RefusalKind.MEMBERS_ONLY -> {
                    R.string.error_members_only to context.getString(R.string.error_members_only_hint)
                }

                RefusalKind.PAID -> {
                    R.string.error_paid_content to context.getString(R.string.error_paid_content_hint)
                }

                RefusalKind.MUSIC_PREMIUM -> {
                    R.string.error_yt_music_premium to context.getString(R.string.error_yt_music_premium_hint)
                }

                RefusalKind.ACCOUNT_TERMINATED -> {
                    R.string.error_channel_terminated to context.getString(R.string.error_channel_terminated_hint)
                }

                RefusalKind.COPYRIGHT_REMOVED -> {
                    R.string.error_copyright_removed to
                        (
                            refusal.claimant?.let { context.getString(R.string.error_copyright_removed_hint, it) }
                                ?: context.getString(R.string.error_copyright_removed_hint_unnamed)
                        )
                }

                RefusalKind.UPLOADER_REMOVED -> {
                    R.string.error_removed_by_uploader to context.getString(R.string.error_removed_by_uploader_hint)
                }

                RefusalKind.POLICY_REMOVED -> {
                    R.string.error_policy_removed to context.getString(R.string.error_policy_removed_hint)
                }

                RefusalKind.RECORDING_UNAVAILABLE -> {
                    R.string.error_recording_unavailable to context.getString(R.string.error_recording_unavailable_hint)
                }

                RefusalKind.UNAVAILABLE -> {
                    R.string.error_not_available to context.getString(R.string.error_not_available_hint)
                }
            }
        return VideoError(message = context.getString(message), hint = hint, isRetryable = false, isUserActionable = false)
    }

    private fun isNetworkRelated(t: Throwable): Boolean =
        t is java.io.IOException ||
            t.cause?.let { isNetworkRelated(it) } == true

    /** Returns true when [t] is an ExoPlaybackException with an HTTP 403 cause. */
    private fun isExoHttpForbidden(t: Throwable): Boolean {
        return try {
            if (t.javaClass.simpleName != "ExoPlaybackException") return false
            val cause = t.cause ?: return false
            if (cause.javaClass.simpleName != "InvalidResponseCodeException") return false
            val codeField = cause.javaClass.getField("responseCode")
            (codeField.get(cause) as? Int) == 403
        } catch (_: Exception) {
            false
        }
    }

    /** Returns true when [t] is an ExoPlaybackException with any non-2xx HTTP cause. */
    private fun isExoHttpError(t: Throwable): Boolean {
        return try {
            if (t.javaClass.simpleName != "ExoPlaybackException") return false
            val cause = t.cause ?: return false
            cause.javaClass.simpleName == "InvalidResponseCodeException"
        } catch (_: Exception) {
            false
        }
    }

    private fun getExoHttpCode(t: Throwable): Int? {
        return try {
            val cause = t.cause ?: return null
            val field = cause.javaClass.getField("responseCode")
            field.get(cause) as? Int
        } catch (_: Exception) {
            null
        }
    }

    private fun isExoSourceError(t: Throwable): Boolean {
        return try {
            if (t.javaClass.simpleName != "ExoPlaybackException") return false
            val typeField = t.javaClass.getField("type")
            (typeField.get(t) as? Int) == 0 // TYPE_SOURCE = 0
        } catch (_: Exception) {
            false
        }
    }

    private fun isExoRendererError(t: Throwable): Boolean {
        return try {
            if (t.javaClass.simpleName != "ExoPlaybackException") return false
            val typeField = t.javaClass.getField("type")
            (typeField.get(t) as? Int) == 1 // TYPE_RENDERER = 1
        } catch (_: Exception) {
            false
        }
    }

    private fun isExoError(t: Throwable): Boolean = t.javaClass.simpleName == "ExoPlaybackException"

    private fun buildFallbackHint(t: Throwable): String {
        // Provide a minimal technical hint without leaking a raw stack trace.
        val type = t.javaClass.simpleName
        val msg = t.message?.take(120)?.let { if (it.isNotBlank()) it else null }
        return when {
            msg != null -> "$type: $msg"
            else -> "Error type: $type. Please try again or restart the app."
        }
    }
}
