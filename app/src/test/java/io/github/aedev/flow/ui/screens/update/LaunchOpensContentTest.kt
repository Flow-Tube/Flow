package io.github.aedev.flow.ui.screens.update

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LaunchOpensContentTest {
    @Test
    fun `a launcher open is not a content launch`() {
        assertThat(launchOpensContent(Intent.ACTION_MAIN, hasData = false)).isFalse()
        assertThat(launchOpensContent(null, hasData = false)).isFalse()
    }

    @Test
    fun `a link or share is a content launch`() {
        assertThat(launchOpensContent(Intent.ACTION_VIEW, hasData = true)).isTrue()
        assertThat(launchOpensContent(Intent.ACTION_SEND, hasData = false)).isTrue()
        assertThat(launchOpensContent(Intent.ACTION_SEND_MULTIPLE, hasData = false)).isTrue()
    }

    @Test
    fun `any launch carrying data is a content launch`() {
        assertThat(launchOpensContent(Intent.ACTION_MAIN, hasData = true)).isTrue()
    }
}
