package io.github.aedev.flow.ui.screens.home

/** Fewer cards than this wait for the run's next attempt instead of showing on their own (#1317). */
internal const val HOME_LOAD_MORE_MIN_CARDS = 4

internal const val HOME_LOAD_MORE_PAGE_SIZE = 8

/** How one load-more attempt ended. */
internal enum class LoadMoreOutcome {
    /** Cards were added to Home. */
    APPENDED,

    /** Cards were found but too few to show yet; the run's next attempt adds to them. */
    HELD,

    /** Every request was answered and nothing new and relevant was left. */
    EXHAUSTED,

    /** Nothing was found and at least one request failed. */
    FAILED,
}

internal enum class LoadMoreStep {
    DONE,
    RETRY,

    /** Home is out of videos on the viewer's subjects and ends with a refresh (#907). */
    END_FEED,

    /** The run stops and offers a retry; the feed stays open. */
    STOP_FAILED,
}

/**
 * What a load-more run does after [outcome] on its [attempt]th try (from 1). Only answered requests
 * that found nothing end the feed: ending it on a lost connection left Home ended after the
 * connection came back (#1317).
 */
internal fun nextLoadMoreStep(
    outcome: LoadMoreOutcome,
    attempt: Int,
    attempts: Int = HOME_PREFETCH_EMPTY_PAGE_RETRIES,
): LoadMoreStep =
    when (outcome) {
        LoadMoreOutcome.APPENDED -> LoadMoreStep.DONE

        LoadMoreOutcome.FAILED -> LoadMoreStep.STOP_FAILED

        // The run's last attempt shows whatever it holds, so held cards never outlive the run.
        LoadMoreOutcome.HELD -> LoadMoreStep.RETRY

        LoadMoreOutcome.EXHAUSTED -> if (attempt >= attempts) LoadMoreStep.END_FEED else LoadMoreStep.RETRY
    }

/** How an attempt that found [cards] cards ends, before showing them. */
internal fun loadMoreOutcome(
    cards: Int,
    lastAttempt: Boolean,
    requestFailed: Boolean,
): LoadMoreOutcome =
    when {
        cards >= HOME_LOAD_MORE_MIN_CARDS || (lastAttempt && cards > 0) -> LoadMoreOutcome.APPENDED
        cards > 0 -> LoadMoreOutcome.HELD
        requestFailed -> LoadMoreOutcome.FAILED
        else -> LoadMoreOutcome.EXHAUSTED
    }

/**
 * The [generated] queries not yet [queued] for this feed, in order and once each. The engine hands
 * most of the same strings back on every regeneration, and asking them again only returns cards
 * already on Home.
 */
internal fun unaskedQueries(
    generated: List<String>,
    queued: Collection<String>,
): List<String> {
    val seen = HashSet<String>(queued)
    return generated.filter { seen.add(it) }
}
