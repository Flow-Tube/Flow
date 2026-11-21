package io.github.aedev.flow.data.images

/**
 * An image model whose URL depends on the size it is drawn at. [DrawnSizeImageInterceptor] asks for
 * the URLs when the load knows its size.
 */
interface DrawnSizeImage {
    /**
     * The URLs to try, best first, for an image drawn at [widthPx] by [heightPx]; zero or less means
     * that side is unknown. [crop] is true when the image fills its box and its overflow is cut off.
     */
    fun urls(
        widthPx: Int,
        heightPx: Int,
        crop: Boolean = true,
    ): List<String>
}
