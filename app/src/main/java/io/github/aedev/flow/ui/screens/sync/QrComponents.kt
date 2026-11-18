package io.github.aedev.flow.ui.screens.sync

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val QrMaxWidthWithRemote = 240.dp

/**
 * Caps a QR code's plate at phone-camera size where a remote drives the page. A full-width square is
 * taller than a TV screen, and TV scrolls a focused button a third of the way down, which pushed the
 * top of the code under the title bar (#2192). Full width elsewhere.
 *
 * With [bringIntoView], the plate also scrolls fully into view when it appears: nothing below the
 * code takes focus, so a remote has no other way to scroll to its bottom on a short TV screen.
 */
@Composable
internal fun Modifier.dpadQrPlate(bringIntoView: Boolean = false): Modifier {
    if (!LocalDpadNavigation.current) return this
    val capped = widthIn(max = QrMaxWidthWithRemote)
    if (!bringIntoView) return capped
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(requester) { requester.bringIntoView() }
    return capped.bringIntoViewRequester(requester)
}

/** Renders [text] as a QR code (ZXing, FOSS). */
@Composable
fun QrCodeImage(
    text: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(text) { generateQrBitmap(text, 640) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.sync_qr_content_description),
            modifier = modifier,
        )
    }
}

private fun generateQrBitmap(
    text: String,
    size: Int,
): Bitmap? =
    try {
        // The spec's minimum quiet zone is 4 modules; the code is shown on a dark container in dark
        // theme, so anything less leaves scanners hunting for the finder patterns.
        val hints = mapOf(EncodeHintType.MARGIN to 4)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val offset = y * size
            for (x in 0 until size) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    } catch (e: Exception) {
        null
    }

/**
 * CameraX preview that decodes a QR code from the live frames with ZXing (FOSS — no ML Kit) and
 * invokes [onQrScanned] once. The caller must already hold the CAMERA permission.
 */
@Composable
fun QrScannerView(
    onQrScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanned = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview =
                    Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                val analysis =
                    ImageAnalysis
                        .Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                analysis.setAnalyzer(
                    executor,
                    QrAnalyzer { result ->
                        if (scanned.compareAndSet(false, true)) onQrScanned(result)
                    },
                )
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/** Decodes the luminance plane of each frame with ZXing; ignores frames without a QR. */
private class QrAnalyzer(
    private val onResult: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val reader =
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val rowStride = plane.rowStride
            val source =
                PlanarYUVLuminanceSource(
                    data,
                    rowStride,
                    image.height,
                    0,
                    0,
                    image.width,
                    image.height,
                    false,
                )
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            onResult(result.text)
        } catch (e: NotFoundException) {
            // no QR in this frame — keep scanning
        } catch (e: Exception) {
            // ignore transient decode errors
        } finally {
            reader.reset()
            image.close()
        }
    }
}
