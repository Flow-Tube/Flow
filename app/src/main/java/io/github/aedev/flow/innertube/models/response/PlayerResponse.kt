package io.github.aedev.flow.innertube.models.response

import io.github.aedev.flow.innertube.models.ResponseContext
import io.github.aedev.flow.innertube.models.Thumbnails
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

/**
 * PlayerResponse with [io.github.aedev.flow.innertube.models.YouTubeClient.WEB_REMIX] client
 */
@Serializable
data class PlayerResponse(
    val responseContext: ResponseContext,
    val playabilityStatus: PlayabilityStatus,
    val playerConfig: PlayerConfig?,
    val streamingData: StreamingData?,
    val videoDetails: VideoDetails?,
    val captions: Captions? = null,
    @SerialName("playbackTracking")
    val playbackTracking: PlaybackTracking?,
    val storyboards: Storyboards? = null,
    val microformat: Microformat? = null,
    val attestation: Attestation? = null,
    /** Kept raw: only the pre-roll's timing is read (see `PrerollWait`), and none of it may fail the parse. */
    val adPlacements: JsonArray? = null,
) {
    /**
     * The attestation demand YouTube attaches to a player response, as
     * `a=6&b=<nonce>&c=<issued>&d=<clientId>&e=<videoId>&hh=<binding>`.
     *
     * Not interchangeable with the BotGuard challenge the WebView fetches from `/api/jnn/v1/Create`
     * — that one descrambles to `[wrappedScript, program, globalName]`, the interpreter and program
     * the VM runs. This is a descriptor of *what* to attest, bound to one video and one client, and
     * reading it tells us whether YouTube asked this client to prove itself for this video at all.
     */
    @Serializable
    data class Attestation(
        val playerAttestationRenderer: PlayerAttestationRenderer? = null,
    ) {
        @Serializable
        data class PlayerAttestationRenderer(
            val challenge: String? = null,
            /** Set by ANDROID_VR: the same challenge may be reused across requests. */
            val useSharedChallenge: Boolean? = null,
        )
    }

    @Serializable
    data class Storyboards(
        val playerStoryboardSpecRenderer: PlayerStoryboardSpecRenderer? = null,
    ) {
        @Serializable
        data class PlayerStoryboardSpecRenderer(
            val spec: String? = null,
            val recommendedLevel: Int? = null,
        )
    }

    @Serializable
    data class Microformat(
        val playerMicroformatRenderer: PlayerMicroformatRenderer? = null,
    ) {
        @Serializable
        data class PlayerMicroformatRenderer(
            val category: String? = null,
            val likeCount: String? = null,
            val viewCount: String? = null,
            val publishDate: String? = null,
            val uploadDate: String? = null,
            val externalChannelId: String? = null,
            val canonicalUrl: String? = null,
        )
    }

    @Serializable
    data class Captions(
        val playerCaptionsTracklistRenderer: PlayerCaptionsTracklistRenderer? = null,
    ) {
        @Serializable
        data class PlayerCaptionsTracklistRenderer(
            val captionTracks: List<CaptionTrack>? = null,
            val translationLanguages: List<TranslationLanguage>? = null,
        )

        @Serializable
        data class CaptionTrack(
            val baseUrl: String? = null,
            val name: Text? = null,
            val languageCode: String? = null,
            val kind: String? = null, // "asr" = auto-generated
            val isTranslatable: Boolean? = null,
        )

        @Serializable
        data class TranslationLanguage(
            val languageCode: String? = null,
        )

        @Serializable
        data class Text(
            val simpleText: String? = null,
            val runs: List<Run>? = null,
        ) {
            @Serializable
            data class Run(
                val text: String? = null,
            )

            val text: String? get() = simpleText ?: runs?.joinToString("") { it.text.orEmpty() }
        }
    }

    @Serializable
    data class PlayabilityStatus(
        val status: String,
        val reason: String?,
        val liveStreamability: LiveStreamability? = null,
        val errorScreen: ErrorScreen? = null,
    ) {
        /** The error screen's second line, which says when the refusal is about the viewer's country. */
        val subreason: String? get() = errorScreen?.playerErrorMessageRenderer?.subreason?.text

        @Serializable
        data class ErrorScreen(
            val playerErrorMessageRenderer: PlayerErrorMessageRenderer? = null,
        ) {
            @Serializable
            data class PlayerErrorMessageRenderer(
                val subreason: Captions.Text? = null,
            )
        }

        @Serializable
        data class LiveStreamability(
            val liveStreamabilityRenderer: LiveStreamabilityRenderer? = null,
        ) {
            @Serializable
            data class LiveStreamabilityRenderer(
                val videoId: String? = null,
                val offlineSlate: OfflineSlate? = null,
            ) {
                @Serializable
                data class OfflineSlate(
                    val liveStreamOfflineSlateRenderer: LiveStreamOfflineSlateRenderer? = null,
                ) {
                    @Serializable
                    data class LiveStreamOfflineSlateRenderer(
                        val scheduledStartTime: String? = null,
                    )
                }
            }
        }
    }

    @Serializable
    data class PlayerConfig(
        val audioConfig: AudioConfig? = null,
        val mediaCommonConfig: MediaCommonConfig? = null,
    ) {
        @Serializable
        data class AudioConfig(
            val loudnessDb: Double?,
            val perceptualLoudnessDb: Double?,
            val loudnessTargetLkfs: Double? = null,
            val enablePerFormatLoudness: Boolean? = null,
            val trackAbsoluteLoudnessLkfs: Double? = null,
            val loudnessNormalizationConfig: LoudnessNormalizationConfig? = null,
        ) {
            @Serializable
            data class LoudnessNormalizationConfig(
                val applyStatefulNormalization: Boolean? = null,
                val preserveStatefulLoudnessTarget: Boolean? = null,
                val maxStatefulTimeThresholdSec: Int? = null,
                val minimumLoudnessTargetLkfs: Double? = null,
            )
        }

        @Serializable
        data class MediaCommonConfig(
            val mediaUstreamerRequestConfig: MediaUstreamerRequestConfig? = null,
        ) {
            @Serializable
            data class MediaUstreamerRequestConfig(
                val videoPlaybackUstreamerConfig: String? = null,
            )
        }
    }

    @Serializable
    data class StreamingData(
        val formats: List<Format>? = null,
        val adaptiveFormats: List<Format> = emptyList(),
        val expiresInSeconds: Int = 0,
        val serverAbrStreamingUrl: String? = null,
        val hlsManifestUrl: String? = null,
        val dashManifestUrl: String? = null,
    ) {
        @Serializable
        data class Format(
            val itag: Int,
            val url: String?,
            val mimeType: String,
            // Absent on the caption track a live stream with auto captions lists among its formats.
            val bitrate: Int = 0,
            val width: Int?,
            val height: Int?,
            val contentLength: Long?,
            val quality: String,
            val fps: Int?,
            val qualityLabel: String?,
            val averageBitrate: Int?,
            val audioQuality: String?,
            val approxDurationMs: String?,
            val audioSampleRate: Int?,
            val audioChannels: Int?,
            val loudnessDb: Double?,
            val lastModified: Long?,
            val signatureCipher: String?,
            val cipher: String? = null,
            val audioTrack: AudioTrack? = null,
            val isDrc: Boolean? = null,
            val xtags: String? = null,
            val trackAbsoluteLoudnessLkfs: Double? = null,
            val initRange: Range? = null,
            val indexRange: Range? = null,
            val colorInfo: ColorInfo? = null,
        ) {
            /** A live stream's auto-caption track (`text/mp4`), listed with the formats but neither audio nor video. */
            val isText: Boolean
                get() = mimeType.startsWith("text/")

            val isAudio: Boolean
                get() = width == null && !isText

            /**
             * Dynamic-range-compressed variant. YouTube ships DRC and non-DRC copies of the same
             * itag+track (identical bitrate to within a few bytes/s), so bitrate alone cannot tell
             * them apart — without this flag the DRC copy wins a bitrate sort and the user silently
             * gets loudness-flattened audio.
             */
            val isDynamicRangeCompressed: Boolean
                get() = isDrc == true || audioContentTags["drc"] == "1"

            /**
             * YouTube's authoritative audio role, decoded from `xtags` (e.g. acont=original vs
             * acont=dubbed). Preferred over the audioTrack id suffix, which is only a convention.
             */
            val isOriginal: Boolean
                get() {
                    audioContentTags["acont"]?.let { return it.equals("original", ignoreCase = true) }
                    val track = audioTrack ?: return true
                    if (track.isAutoDubbed == true) return false
                    track.audioIsDefault?.let { return it }
                    track.displayName?.let { if (it.contains("original", ignoreCase = true)) return true }
                    val id = track.id ?: return true
                    return id.substringAfterLast('.', missingDelimiterValue = "4") == "4"
                }

            val audioLanguageTag: String?
                get() =
                    audioTrack
                        ?.id
                        ?.substringBeforeLast('.', missingDelimiterValue = "")
                        ?.takeIf { it.isNotBlank() }
                        ?: audioContentTags["lang"]

            private val audioContentTags: Map<String, String>
                get() = xtags?.takeIf { it.isNotBlank() }?.let(AudioXTags::decode).orEmpty()

            @Serializable
            data class AudioTrack(
                val displayName: String?,
                val id: String?,
                val isAutoDubbed: Boolean? = null,
                val audioIsDefault: Boolean? = null,
            )

            @Serializable
            data class ColorInfo(
                val transferCharacteristics: String? = null,
            ) {
                /** HDR is carried by the transfer curve: PQ (HDR10) or HLG. */
                val isHdr: Boolean
                    get() =
                        transferCharacteristics == "COLOR_TRANSFER_CHARACTERISTICS_SMPTEST2084" ||
                            transferCharacteristics == "COLOR_TRANSFER_CHARACTERISTICS_ARIB_STD_B67"
            }

            @Serializable
            data class Range(
                val start: String? = null,
                val end: String? = null,
            )
        }
    }

    @Serializable
    data class VideoDetails(
        val videoId: String,
        val title: String?,
        val author: String?,
        val channelId: String,
        val shortDescription: String? = null,
        val lengthSeconds: String = "0",
        val musicVideoType: String? = null,
        val viewCount: String? = null,
        val thumbnail: Thumbnails? = null,
        val isLive: Boolean? = null,
        val isLiveContent: Boolean? = null,
        val isLiveDvrEnabled: Boolean? = null,
        val isPostLiveDvr: Boolean? = null,
        val keywords: List<String>? = null,
    )

    @Serializable
    data class PlaybackTracking(
        @SerialName("videostatsPlaybackUrl")
        val videostatsPlaybackUrl: VideostatsPlaybackUrl?,
        @SerialName("videostatsWatchtimeUrl")
        val videostatsWatchtimeUrl: VideostatsWatchtimeUrl?,
        @SerialName("atrUrl")
        val atrUrl: AtrUrl?,
    ) {
        @Serializable
        data class VideostatsPlaybackUrl(
            @SerialName("baseUrl")
            val baseUrl: String?,
        )

        @Serializable
        data class VideostatsWatchtimeUrl(
            @SerialName("baseUrl")
            val baseUrl: String?,
        )

        @Serializable
        data class AtrUrl(
            @SerialName("baseUrl")
            val baseUrl: String?,
        )
    }
}
