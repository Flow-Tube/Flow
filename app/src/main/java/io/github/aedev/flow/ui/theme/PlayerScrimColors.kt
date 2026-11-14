package io.github.aedev.flow.ui.theme

import androidx.compose.ui.graphics.Color

val PlayerScrim = Color.Black

/** Letterbox and backdrop drawn behind the video surface itself. */
val PlayerGround = Color.Black

/** Pills, icon buttons and chips resting directly on video. */
val PlayerScrimAffordance = PlayerScrim.copy(alpha = 0.4f)

/** Round icon buttons on the floating mini player; the play/close pair sits a little lighter. */
val PlayerScrimMiniButton = PlayerScrim.copy(alpha = 0.36f)
val PlayerScrimMiniTopButton = PlayerScrim.copy(alpha = 0.28f)

/** Tint over the blurred thumbnail behind an immersive fullscreen player. */
val PlayerScrimImmersiveBackdrop = PlayerScrim.copy(alpha = 0.45f)

/** Brightness and volume readouts shown mid-gesture. */
val PlayerScrimGestureHud = PlayerScrim.copy(alpha = 0.54f)

/**
 * Panels that carry a block of content over video — the seek read-out, the speed-boost badge, the
 * SponsorBlock skip button. One step for all of them: each had picked its own alpha between .50 and
 * .60, which read as drift rather than as one family.
 */
val PlayerScrimPanel = PlayerScrim.copy(alpha = 0.56f)

/** Top and bottom edge gradients behind the portrait-fullscreen controls. */
val PlayerScrimEdgeGradient = PlayerScrim.copy(alpha = 0.72f)

val PlayerScrimContent = Color.White

/** Supporting text over video: a channel name, a total duration, a separator. */
val PlayerScrimContentSecondary = PlayerScrimContent.copy(alpha = 0.7f)

val PlayerScrimContentDisabled = PlayerScrimContent.copy(alpha = 0.3f)

val PlayerLiveIndicator = Color.Red

/** The rewatch curve behind the seek bar, and the brighter fill over its most-replayed stretch. */
val PlayerHeatmapCurve = PlayerScrimContent.copy(alpha = 0.22f)
val PlayerHeatmapCurvePeak = PlayerScrimContent.copy(alpha = 0.70f)

/** The line along the top of the curve; the fill alone washes out over bright video. */
val PlayerHeatmapCurveEdge = PlayerScrimContent.copy(alpha = 0.55f)

/** The music player's and mini player's true-black look, for OLED screens. */
val MusicPlayerTrueBlack = Color.Black
