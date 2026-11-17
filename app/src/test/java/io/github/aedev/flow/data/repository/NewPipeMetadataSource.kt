package io.github.aedev.flow.data.repository

import io.github.aedev.flow.data.model.Video
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** An image as YouTube lists it. */
internal data class SourceImage(
    val url: String,
    val width: Int,
    val height: Int,
)

/** What YouTube says about one video's watch page. */
internal data class SourceWatchPage(
    val title: String?,
    val channelName: String?,
    val channelUrl: String?,
    val thumbnails: List<SourceImage>,
    val avatars: List<SourceImage>,
    val durationSeconds: Long,
    val viewCount: Long,
    val textualUploadDate: String?,
    val uploadedAtMillis: Long?,
)

internal enum class SourceKind { VIDEO, LIVE, UPCOMING }

/** One video as a list (related, search) carries it. */
internal data class SourceListedVideo(
    val url: String,
    val title: String?,
    val channelName: String?,
    val channelUrl: String?,
    val thumbnails: List<SourceImage>,
    val avatars: List<SourceImage>,
    val durationSeconds: Long,
    val viewCount: Long,
    val textualUploadDate: String?,
    val uploadedAtMillis: Long?,
    val kind: SourceKind = SourceKind.VIDEO,
    val shortForm: Boolean = false,
)

/** What YouTube says about one channel. */
internal data class SourceChannel(
    val id: String,
    val name: String,
    val avatars: List<SourceImage>,
    val tags: List<String>,
    val description: String?,
)

/**
 * Serves video and channel metadata to [YouTubeRepository] the way it is fetched today, through
 * NewPipe. The only file of the metadata tests that names NewPipe: when the repository reads the
 * same facts natively, this file serves them natively and the tests stay as they are.
 */
internal class NewPipeMetadataSource {
    private val watchPages = mutableMapOf<String, () -> SourceWatchPage>()
    private val channels = mutableMapOf<String, SourceChannel>()

    /** Every watch page request, by video id, in order. */
    val watchRequests = CopyOnWriteArrayList<String>()

    /** Every channel request, by the reference the repository asked with, in order. */
    val channelRequests = CopyOnWriteArrayList<String>()

    fun install() {
        mockkStatic(StreamInfo::class, ChannelInfo::class)
        every { StreamInfo.getInfo(any<StreamingService>(), any<String>()) } answers {
            val url = secondArg<String>()
            val id = videoIdOf(url)
            watchRequests += id
            val page = watchPages[id] ?: throw ContentNotAvailableException("missing $id")
            streamInfo(page())
        }
        every { ChannelInfo.getInfo(any<StreamingService>(), any<String>()) } answers {
            val url = secondArg<String>()
            channelRequests += url
            val channel =
                channels.values.firstOrNull { url.endsWith("/channel/${it.id}") || url.endsWith("/@${it.name}") }
                    ?: throw ContentNotAvailableException("missing $url")
            channelInfo(channel)
        }
    }

    fun uninstall() {
        unmockkStatic(StreamInfo::class, ChannelInfo::class)
    }

    fun serveWatchPage(
        videoId: String,
        page: SourceWatchPage,
    ) {
        watchPages[videoId] = { page }
    }

    /** The first request fails with YouTube's "page needs to be reloaded", the next one is served. */
    fun serveWatchPageAfterReload(
        videoId: String,
        page: SourceWatchPage,
    ) {
        var asked = 0
        watchPages[videoId] = {
            if (asked++ == 0) {
                throw ContentNotAvailableException("The page needs to be reloaded.")
            }
            page
        }
    }

    fun serveChannel(channel: SourceChannel) {
        channels[channel.id] = channel
    }

    /** The videos a related list of [items] becomes. */
    fun relatedVideos(
        repository: YouTubeRepository,
        items: List<SourceListedVideo>,
    ): List<Video> {
        val info = mockk<StreamInfo>()
        every { info.relatedItems } returns items.map(::streamInfoItem)
        return repository.getRelatedVideosFromStreamInfo(info)
    }

    private fun videoIdOf(url: String): String =
        url
            .substringAfter("watch?v=", missingDelimiterValue = url.substringAfterLast('/'))
            .substringBefore('&')

    private fun images(images: List<SourceImage>): List<Image> =
        images.map { Image(it.url, it.height, it.width, Image.ResolutionLevel.UNKNOWN) }

    private fun date(millis: Long?): DateWrapper? = millis?.let { DateWrapper(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC)) }

    private fun streamInfo(page: SourceWatchPage): StreamInfo {
        val info = mockk<StreamInfo>()
        every { info.name } returns page.title
        every { info.uploaderName } returns page.channelName
        every { info.uploaderUrl } returns page.channelUrl
        every { info.thumbnails } returns images(page.thumbnails)
        every { info.uploaderAvatars } returns images(page.avatars)
        every { info.duration } returns page.durationSeconds
        every { info.viewCount } returns page.viewCount
        every { info.textualUploadDate } returns page.textualUploadDate
        every { info.uploadDate } returns date(page.uploadedAtMillis)
        return info
    }

    private fun streamInfoItem(item: SourceListedVideo): InfoItem {
        val streamType =
            when (item.kind) {
                SourceKind.VIDEO -> StreamType.VIDEO_STREAM
                SourceKind.LIVE -> StreamType.LIVE_STREAM
                SourceKind.UPCOMING -> StreamType.NONE
            }
        return StreamInfoItem(0, item.url, item.title, streamType).apply {
            uploaderName = item.channelName
            uploaderUrl = item.channelUrl
            thumbnails = images(item.thumbnails)
            uploaderAvatars = images(item.avatars)
            duration = item.durationSeconds
            viewCount = item.viewCount
            textualUploadDate = item.textualUploadDate
            uploadDate = date(item.uploadedAtMillis)
            isShortFormContent = item.shortForm
        }
    }

    private fun channelInfo(channel: SourceChannel): ChannelInfo {
        val info = mockk<ChannelInfo>()
        every { info.id } returns channel.id
        every { info.name } returns channel.name
        every { info.avatars } returns images(channel.avatars)
        every { info.tags } returns channel.tags
        every { info.description } returns channel.description
        return info
    }
}
