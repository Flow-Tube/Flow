package io.github.aedev.flow.ui.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DocumentPickerTest {
    @Test
    fun `tv's stub alone is no picker`() {
        assertThat(hasRealDocumentPicker(listOf("com.android.tv.frameworkpackagestubs"))).isFalse()
        assertThat(hasRealDocumentPicker(emptyList())).isFalse()
    }

    @Test
    fun `a documents app is a picker, with or without the stub beside it`() {
        assertThat(hasRealDocumentPicker(listOf("com.google.android.documentsui"))).isTrue()
        assertThat(hasRealDocumentPicker(listOf("com.android.tv.frameworkpackagestubs", "com.android.documentsui"))).isTrue()
    }
}
