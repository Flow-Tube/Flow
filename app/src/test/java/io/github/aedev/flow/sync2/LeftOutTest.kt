package io.github.aedev.flow.sync2

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.service.catchingUpAfter
import io.github.aedev.flow.sync2.service.sendable
import org.junit.Test

class LeftOutTest {
    @Test
    fun `a device that leaves nothing out sends every collection`() {
        assertThat(sendable(emptySet())).isEqualTo(SyncCollection.ANDROID_SYNCABLE)
    }

    @Test
    fun `a collection left out is never sent`() {
        val sent = sendable(setOf(SyncCollection.SETTINGS, SyncCollection.NOTES))
        assertThat(sent).containsNoneOf(SyncCollection.SETTINGS, SyncCollection.NOTES)
        assertThat(sent).hasSize(SyncCollection.ANDROID_SYNCABLE.size - 2)
    }

    @Test
    fun `a collection taken back catches up until it is read, unless it is left out again`() {
        val settings = setOf(SyncCollection.SETTINGS)
        val notes = setOf(SyncCollection.NOTES)
        assertThat(catchingUpAfter(pending = emptySet(), before = settings, after = emptySet())).isEqualTo(settings)
        assertThat(catchingUpAfter(pending = emptySet(), before = emptySet(), after = settings)).isEmpty()
        assertThat(catchingUpAfter(pending = settings, before = notes, after = emptySet())).isEqualTo(settings + notes)
        assertThat(catchingUpAfter(pending = settings, before = emptySet(), after = settings)).isEmpty()
    }
}
