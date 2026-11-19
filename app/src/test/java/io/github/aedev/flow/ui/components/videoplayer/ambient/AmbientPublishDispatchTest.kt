package io.github.aedev.flow.ui.components.videoplayer.ambient

import android.app.Application
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

/**
 * The smoothing loop runs on Main so its buffers need no locks, but the per-pixel encode inside
 * `publish` does not have to: on a phone it cost 97 ms of main thread in a 3.6 s collapse window.
 * This pins that it leaves the caller.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AmbientPublishDispatchTest {
    private class RecordingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        val threads = mutableListOf<Thread>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            delegate.dispatch(context) {
                synchronized(threads) { threads += Thread.currentThread() }
                block.run()
            }
        }
    }

    @Test
    fun `publish encodes off the calling thread`() {
        val recorder = RecordingDispatcher(Dispatchers.Default)
        val pipeline = AmbientPipeline(recorder)
        val caller = Thread.currentThread()

        val state = runBlocking { pipeline.publish() }

        assertThat(state.frame).isNotNull()
        assertThat(recorder.threads).isNotEmpty()
        assertThat(recorder.threads).doesNotContain(caller)
    }

    @Test
    fun `publish rotates buffers so a handed over frame is never overwritten`() {
        val pipeline = AmbientPipeline(Dispatchers.Default)

        val frames = List(OUTPUT_RING + 1) { runBlocking { pipeline.publish() }.frame?.asAndroidBitmap() }

        assertThat(frames.take(OUTPUT_RING).toSet()).hasSize(OUTPUT_RING)
        assertThat(frames.last()).isSameInstanceAs(frames.first())
    }
}
