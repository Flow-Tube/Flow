package io.github.aedev.flow.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/** #2252: music search launched voice input with no catch and crashed where no app takes speech. */
class VoiceSearchTest {
    @Test
    fun `a device with no speech app swallows the launch`() {
        var launched = false

        launchVoiceSearch("Speak") {
            launched = true
            throw ActivityNotFoundException()
        }

        assertThat(launched).isTrue()
    }

    @Test
    fun `voice search is offered only when an app resolves the speech action`() {
        assertThat(canVoiceSearch(contextResolving(mockk<ResolveInfo>()))).isTrue()
        assertThat(canVoiceSearch(contextResolving(null))).isFalse()
    }

    private fun contextResolving(match: ResolveInfo?): Context {
        val packageManager = mockk<PackageManager> { every { resolveActivity(any<Intent>(), any<Int>()) } returns match }
        return mockk { every { this@mockk.packageManager } returns packageManager }
    }
}
