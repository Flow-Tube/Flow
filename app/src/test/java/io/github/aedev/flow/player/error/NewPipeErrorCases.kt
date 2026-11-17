package io.github.aedev.flow.player.error

import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import java.lang.reflect.InvocationTargetException

/**
 * Every NewPipe exception the error mapper knows, and NewPipe's own reading of a YouTube refusal.
 * The only file of the error-message tests that names NewPipe; it goes when the library does.
 */
internal object NewPipeErrorCases {
    val ageRestricted: Throwable = AgeRestrictedContentException("age")
    val privateVideo: Throwable = PrivateContentException("private")
    val geographic: Throwable = GeographicRestrictionException("geo")
    val notInCountry: Throwable = UnsupportedContentInCountryException("country")
    val paid: Throwable = PaidContentException("paid")
    val musicPremium: Throwable = YoutubeMusicPremiumContentException()
    val terminatedWithReason: Throwable = AccountTerminatedException("The account was closed")
    val terminatedWithoutReason: Throwable = AccountTerminatedException("")
    val reCaptcha: Throwable = ReCaptchaException("captcha", "https://www.youtube.com/")
    val notAvailable: Throwable = ContentNotAvailableException("gone")
    val notSupported: Throwable = ContentNotSupportedException("unsupported")
    val extraction: Throwable = ExtractionException("parse")

    /**
     * What NewPipe 0.26.5 threw for one `/player` playabilityStatus, read through its own
     * YoutubeStreamExtractor.checkPlayabilityStatus; null when it accepted the status.
     */
    fun newPipeVerdict(
        status: String,
        reason: String?,
    ): Throwable? {
        val jsonObject = Class.forName("com.grack.nanojson.JsonObject")
        val method =
            Class
                .forName("org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor")
                .getDeclaredMethod("checkPlayabilityStatus", jsonObject)
                .apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val playability = jsonObject.getDeclaredConstructor().newInstance() as MutableMap<String, Any?>
        playability["status"] = status
        reason?.let { playability["reason"] = it }
        return try {
            method.invoke(null, playability)
            null
        } catch (e: InvocationTargetException) {
            e.targetException
        }
    }
}
