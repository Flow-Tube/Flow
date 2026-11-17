package io.github.aedev.flow.innertube

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Stands in for the WEB client version fetch, which today is NewPipe's. The only file of the
 * client version tests that names NewPipe; a native fetch is stubbed here instead.
 */
internal object NewPipeClientVersion {
    fun answer(version: String) {
        mockkStatic(YoutubeParsingHelper::class)
        every { YoutubeParsingHelper.getClientVersion() } returns version
    }

    fun fail() {
        mockkStatic(YoutubeParsingHelper::class)
        every { YoutubeParsingHelper.getClientVersion() } throws java.io.IOException("no network")
    }

    fun reset() {
        unmockkStatic(YoutubeParsingHelper::class)
    }
}
