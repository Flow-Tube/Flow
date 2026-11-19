package io.github.aedev.flow.ui.components.videoplayer.ambient

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Only a permanent signal disables capture. `ERROR_SOURCE_INVALID` on a protected surface is a
 * platform guarantee that the readback will never succeed, so reissuing it every cadence for the
 * length of a video is pure waste.
 *
 * Everything else backs off but never latches. A surface can be legitimately unavailable for
 * seconds — during startup, after a fullscreen transition, or while the surface is re-created — and
 * a counter that disabled the effect after N of those would turn a transient hiccup into "ambient
 * is black for the rest of this video" with no way back.
 */
internal const val MAX_BACKOFF_MULTIPLIER = 8L

internal const val CAPTURE_OK = 0
internal const val CAPTURE_RETRY = 1
internal const val CAPTURE_UNSUPPORTED = 2

internal suspend fun captureSurface(
    surface: View,
    dst: Bitmap,
    handler: Handler,
): Int =
    suspendCancellableCoroutine { cont ->
        try {
            when {
                surface is TextureView -> {
                    if (surface.isAvailable) {
                        surface.getBitmap(dst)
                        cont.resume(CAPTURE_OK)
                    } else {
                        cont.resume(CAPTURE_RETRY)
                    }
                }

                surface is SurfaceView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                    val holderSurface = surface.holder?.surface
                    if (holderSurface != null && holderSurface.isValid) {
                        PixelCopy.request(
                            surface,
                            dst,
                            { result ->
                                cont.resume(
                                    when (result) {
                                        PixelCopy.SUCCESS -> CAPTURE_OK
                                        PixelCopy.ERROR_SOURCE_INVALID -> CAPTURE_UNSUPPORTED
                                        else -> CAPTURE_RETRY
                                    },
                                )
                            },
                            handler,
                        )
                    } else {
                        cont.resume(CAPTURE_RETRY)
                    }
                }

                else -> {
                    cont.resume(CAPTURE_RETRY)
                }
            }
        } catch (t: Throwable) {
            cont.resume(CAPTURE_RETRY)
        }
    }
