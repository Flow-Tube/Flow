/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

private const val MARQUEE_PASSES = 1
private const val MARQUEE_START_DELAY_MILLIS = 1200

/** The user's setting for scrolling text. With it off, no marquee in the app runs. */
val LocalMarqueeEnabled = staticCompositionLocalOf { true }

/**
 * The single marquee every scrolling title in a list uses. One pass after a short pause, then it rests:
 * a lane of long titles animates for a few seconds when it appears, not for as long as it is on
 * screen, and a title that scrolls away during the pause never starts at all.
 */
@Composable
fun Modifier.titleMarquee(): Modifier =
    if (LocalMarqueeEnabled.current) {
        basicMarquee(
            iterations = MARQUEE_PASSES,
            initialDelayMillis = MARQUEE_START_DELAY_MILLIS,
        )
    } else {
        this
    }

/** For a [titleMarquee] text: a marquee clips its own text, and without one a long title needs an ellipsis. */
@Composable
fun titleMarqueeOverflow(): TextOverflow = if (LocalMarqueeEnabled.current) TextOverflow.Clip else TextOverflow.Ellipsis
