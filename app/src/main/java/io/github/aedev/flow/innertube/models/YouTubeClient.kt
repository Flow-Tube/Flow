package io.github.aedev.flow.innertube.models

import kotlinx.serialization.Serializable

/**
 * Which attestation runtime mints a PO Token this client's requests will be accepted with.
 *
 * A PO Token is produced by BotGuard (Web), DroidGuard (Android) or iOSGuard (iOS), and one
 * platform's token is never valid on another's URL. Flow only owns a BotGuard WebView, so [WEB]
 * is the only family it can attest; the rest are usable exactly as long as YouTube keeps serving
 * them unattested. Encoding that here is what stops a WEB token being stamped onto an
 * ANDROID_VR/IOS URL, which is a claim GVS checks and rejects.
 */
enum class AttestationPlatform {
    /** BotGuard — Flow mints these in [io.github.aedev.flow.utils.potoken.PoTokenWebView]. */
    WEB,

    /** DroidGuard, inside Google Play Services. Not reachable from a third-party app. */
    DROIDGUARD,

    /** iOSGuard. Not reachable at all from Android. */
    IOSGUARD,
}

@Serializable
data class YouTubeClient(
    val clientName: String,
    val clientVersion: String,
    val clientId: String,
    val userAgent: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: String? = null,
    val originalUrl: String? = null,
    val platform: String? = null,
    val utcOffsetMinutes: Int? = null,
    val buildId: String? = null,
    val cronetVersion: String? = null,
    val packageName: String? = null,
    val friendlyName: String? = null,
    val loginSupported: Boolean = false,
    val loginRequired: Boolean = false,
    val useSignatureTimestamp: Boolean = false,
    val isEmbedded: Boolean = false,
    val useWebPoTokens: Boolean = false,
    /**
     * The runtime that mints tokens this client's URLs will accept. Defaults to [AttestationPlatform.WEB]
     * because every client Flow can actually attest is web-family; the non-web clients set it
     * explicitly so token attachment can be gated on it.
     */
    val attestation: AttestationPlatform = AttestationPlatform.WEB,
    /**
     * Whether [userAgent] is repeated inside `context.client` as well as sent as the HTTP header.
     * Only MWEB expects this; sending it for other clients is a fingerprint inconsistency. Clients
     * that leave it false serialise no `userAgent` key at all — the InnerTube Json sets
     * `explicitNulls = false` — so their request bodies are byte-identical to before this existed.
     */
    val sendUserAgentInContext: Boolean = false,
) {
    fun toContext(
        locale: YouTubeLocale,
        visitorData: String?,
        dataSyncId: String?,
    ) = Context(
        client =
            Context.Client(
                clientName = clientName,
                clientVersion = clientVersion,
                osName = osName,
                osVersion = osVersion,
                deviceMake = deviceMake,
                deviceModel = deviceModel,
                androidSdkVersion = androidSdkVersion,
                originalUrl = originalUrl,
                platform = platform,
                utcOffsetMinutes = utcOffsetMinutes,
                gl = locale.gl,
                hl = locale.hl,
                visitorData = visitorData,
                userAgent = userAgent.takeIf { sendUserAgentInContext },
            ),
        user =
            Context.User(
                onBehalfOfUser = if (loginSupported) dataSyncId else null,
            ),
    )

    companion object {
        const val USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        const val USER_AGENT_MWEB =
            "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"

        const val ORIGIN_YOUTUBE_MUSIC = "https://music.youtube.com"
        const val REFERER_YOUTUBE_MUSIC = "$ORIGIN_YOUTUBE_MUSIC/"
        const val API_URL_YOUTUBE_MUSIC = "$ORIGIN_YOUTUBE_MUSIC/youtubei/v1/"

        // Main-site host for stream extraction, video and music alike (InnerTubeVideoStreamExtractor
        // passes it to player()). YouTube serves usable ANDROID_VR direct adaptive formats from the
        // main site, whereas the music endpoint returns SABR-only for those clients. Music metadata
        // still comes from music.youtube.com.
        const val ORIGIN_YOUTUBE = "https://www.youtube.com"
        const val REFERER_YOUTUBE = "$ORIGIN_YOUTUBE/"
        const val API_URL_YOUTUBE = "$ORIGIN_YOUTUBE/youtubei/v1/"

        val WEB =
            YouTubeClient(
                clientName = "WEB",
                clientVersion = "2.20260710.06.00",
                clientId = "1",
                userAgent = USER_AGENT_WEB,
                originalUrl = ORIGIN_YOUTUBE,
                platform = "DESKTOP",
                utcOffsetMinutes = 0,
            )

        val MWEB =
            YouTubeClient(
                clientName = "MWEB",
                clientVersion = "2.20250122.04.00",
                clientId = "2",
                userAgent = USER_AGENT_MWEB,
                platform = "MOBILE",
                utcOffsetMinutes = 0,
                useSignatureTimestamp = true,
                useWebPoTokens = true,
                sendUserAgentInContext = true,
            )

        val ANDROID =
            YouTubeClient(
                clientName = "ANDROID",
                clientVersion = "21.03.38",
                clientId = "3",
                userAgent = "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip",
                osName = "Android",
                osVersion = "14",
                deviceMake = "Google",
                deviceModel = "Pixel 6 Pro",
                androidSdkVersion = "34",
                buildId = "TQ2A.230505.002",
                packageName = "com.google.android.youtube",
                friendlyName = "Android",
                loginSupported = true,
                useSignatureTimestamp = true,
                attestation = AttestationPlatform.DROIDGUARD,
            )

        /** Only this client gets a song's timed lyrics; WEB_REMIX gets plain text for the same browse id. */
        val ANDROID_MUSIC =
            YouTubeClient(
                clientName = "ANDROID_MUSIC",
                clientVersion = "7.21.50",
                clientId = "21",
                userAgent = "com.google.android.apps.youtube.music/7.21.50 (Linux; U; Android 14) gzip",
                osName = "Android",
                osVersion = "14",
                androidSdkVersion = "34",
                attestation = AttestationPlatform.DROIDGUARD,
            )

        val WEB_REMIX =
            YouTubeClient(
                clientName = "WEB_REMIX",
                clientVersion = "1.20260213.01.00",
                clientId = "67",
                userAgent = USER_AGENT_WEB,
                loginSupported = true,
                useSignatureTimestamp = true,
                useWebPoTokens = true,
            )

        val WEB_CREATOR =
            YouTubeClient(
                clientName = "WEB_CREATOR",
                clientVersion = "1.20260213.00.00",
                clientId = "62",
                userAgent = USER_AGENT_WEB,
                loginSupported = true,
                loginRequired = true,
                useSignatureTimestamp = true,
            )

        val TVHTML5 =
            YouTubeClient(
                clientName = "TVHTML5",
                clientVersion = "7.20260213.00.00",
                clientId = "7",
                userAgent =
                    "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 " +
                        "(KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15",
                loginSupported = true,
                loginRequired = true,
                useSignatureTimestamp = true,
                useWebPoTokens = true,
            )

        /**
         * The Samsung Tizen TV app, asked anonymously. Measured by NewTube on visitors GVS had
         * walled on the app clients (#921): it kept serving past the one-minute wall. Its formats
         * are all signed, so it needs the remote decoder's signature timestamp and solve, and the
         * Tizen device fields: without them /player answers "The page needs to be reloaded".
         */
        val TV_TIZEN =
            YouTubeClient(
                clientName = "TVHTML5",
                clientVersion = "5.20260707",
                clientId = "7",
                userAgent =
                    "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 " +
                        "(KHTML, like Gecko) Version/2.4.0 TV Safari/538.1",
                osName = "Tizen",
                osVersion = "2.4.0",
                deviceMake = "Samsung",
                deviceModel = "SmartTV",
                useSignatureTimestamp = true,
            )

        val TVHTML5_SIMPLY_EMBEDDED_PLAYER =
            YouTubeClient(
                clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                clientVersion = "2.0",
                clientId = "85",
                userAgent =
                    "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 " +
                        "(KHTML, like Gecko) Version/15.4 Safari/605.1.15",
                loginSupported = true,
                loginRequired = false,
                useSignatureTimestamp = true,
                isEmbedded = true,
            )

        val IOS =
            YouTubeClient(
                clientName = "IOS",
                clientVersion = "21.03.1",
                clientId = "5",
                userAgent = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)",
                osVersion = "18.2.22C152",
                attestation = AttestationPlatform.IOSGUARD,
            )

        val MOBILE =
            YouTubeClient(
                clientName = "ANDROID",
                clientVersion = "21.03.38",
                clientId = "3",
                userAgent = "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip",
                loginSupported = true,
                useSignatureTimestamp = true,
                attestation = AttestationPlatform.DROIDGUARD,
            )

        val ANDROID_VR_NO_AUTH =
            YouTubeClient(
                clientName = "ANDROID_VR",
                clientVersion = "1.61.48",
                clientId = "28",
                userAgent =
                    "com.google.android.apps.youtube.vr.oculus/1.61.48 " +
                        "(Linux; U; Android 12; en_US; Oculus Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
                loginSupported = false,
                useSignatureTimestamp = false,
                attestation = AttestationPlatform.DROIDGUARD,
            )

        val ANDROID_VR_1_61_48 =
            YouTubeClient(
                clientName = "ANDROID_VR",
                clientVersion = "1.61.48",
                clientId = "28",
                userAgent =
                    "com.google.android.apps.youtube.vr.oculus/1.61.48 " +
                        "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
                osName = "Android",
                osVersion = "12",
                deviceMake = "Oculus",
                deviceModel = "Quest 3",
                androidSdkVersion = "32",
                buildId = "SQ3A.220605.009.A1",
                cronetVersion = "132.0.6808.3",
                packageName = "com.google.android.apps.youtube.vr.oculus",
                friendlyName = "Android VR 1.61",
                loginSupported = false,
                useSignatureTimestamp = false,
                attestation = AttestationPlatform.DROIDGUARD,
            )

        val ANDROID_VR_1_65_10 =
            YouTubeClient(
                clientName = "ANDROID_VR",
                clientVersion = "1.65.10",
                clientId = "28",
                userAgent =
                    "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
                        "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                osName = "Android",
                osVersion = "12L",
                deviceMake = "Oculus",
                deviceModel = "Quest 3",
                androidSdkVersion = "32",
                buildId = "SQ3A.220605.009.A1",
                packageName = "com.google.android.apps.youtube.vr.oculus",
                friendlyName = "Android VR 1.65",
                loginSupported = false,
                useSignatureTimestamp = false,
                attestation = AttestationPlatform.DROIDGUARD,
            )

        val ANDROID_VR_1_43_32 =
            YouTubeClient(
                clientName = "ANDROID_VR",
                clientVersion = "1.43.32",
                clientId = "28",
                userAgent =
                    "com.google.android.apps.youtube.vr.oculus/1.43.32 " +
                        "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
                osName = "Android",
                osVersion = "12",
                deviceMake = "Oculus",
                deviceModel = "Quest 3",
                androidSdkVersion = "32",
                buildId = "SQ3A.220605.009.A1",
                cronetVersion = "107.0.5284.2",
                packageName = "com.google.android.apps.youtube.vr.oculus",
                friendlyName = "Android VR 1.43",
                loginSupported = false,
                useSignatureTimestamp = false,
                attestation = AttestationPlatform.DROIDGUARD,
            )

        val ANDROID_CREATOR =
            YouTubeClient(
                clientName = "ANDROID_CREATOR",
                clientVersion = "25.03.101",
                clientId = "14",
                userAgent =
                    "com.google.android.apps.youtube.creator/25.03.101 " +
                        "(Linux; U; Android 15; en_US; Pixel 9 Pro Fold; Build/AP3A.241005.015.A2; Cronet/132.0.6779.0)",
                osName = "Android",
                osVersion = "15",
                deviceMake = "Google",
                deviceModel = "Pixel 9 Pro Fold",
                androidSdkVersion = "35",
                buildId = "AP3A.241005.015.A2",
                cronetVersion = "132.0.6779.0",
                packageName = "com.google.android.apps.youtube.creator",
                friendlyName = "Android Studio",
                loginSupported = true,
                useSignatureTimestamp = true,
                attestation = AttestationPlatform.DROIDGUARD,
            )

        /**
         * The primary direct-URL client. GVS serves its formats without a PO Token and without an
         * `n` parameter, so it reaches first frame with no attestation and no nsig decode — the two
         * properties ANDROID_VR was chosen for, which ANDROID_VR lost when YouTube began requiring
         * a GVS PO Token for it (yt-dlp #17261, Aug 2026).
         *
         * Version-sensitive: the previous `0.1`/`RealityDevice14,1` build still answers, but serves
         * 17 formats and a single audio track. This build serves 98 formats and every dubbed track,
         * so do not "simplify" these values — they are the difference between a full ladder and a
         * stub. Kept in step with yt-dlp's `visionos` entry in `youtube/_base.py`.
         */
        val VISIONOS =
            YouTubeClient(
                clientName = "VISIONOS",
                clientVersion = "1.02",
                clientId = "101",
                userAgent =
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
                        "(KHTML, like Gecko) Version/26.0 Safari/605.1.15",
                osName = "visionOS",
                osVersion = "26.5.23O471",
                deviceMake = "Apple",
                deviceModel = "RealityDevice17,1",
                friendlyName = "visionOS",
                loginSupported = false,
                useSignatureTimestamp = false,
                attestation = AttestationPlatform.IOSGUARD,
            )

        val IPADOS =
            YouTubeClient(
                clientName = "IOS",
                clientVersion = "21.03.3",
                clientId = "5",
                userAgent = "com.google.ios.youtube/21.03.3 (iPad7,6; U; CPU iPadOS 17_7_10 like Mac OS X; en-US)",
                osName = "iPadOS",
                osVersion = "17.7.10.21H450",
                deviceMake = "Apple",
                deviceModel = "iPad7,6",
                friendlyName = "iPadOS",
                loginSupported = false,
                useSignatureTimestamp = false,
                packageName = "com.google.ios.youtube",
                attestation = AttestationPlatform.IOSGUARD,
            )
    }
}
