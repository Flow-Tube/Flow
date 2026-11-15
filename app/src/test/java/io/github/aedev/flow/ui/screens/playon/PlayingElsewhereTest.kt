package io.github.aedev.flow.ui.screens.playon

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.sync2.live.LiveMessage
import io.github.aedev.flow.sync2.live.LivePeer
import org.junit.Test

class PlayingElsewhereTest {
    private val tv = "bb".repeat(16)
    private val laptop = "cc".repeat(16)
    private val song = LiveMessage.Now(video = "aaaaaaaaaaa", title = "Song", music = true, state = LiveMessage.PLAYING)

    private fun peer(
        did: String,
        nowAt: Long,
        now: LiveMessage.Now? = song,
    ) = LivePeer(did, kind = "tv", now = now, nowAt = nowAt, heardAt = nowAt)

    @Test
    fun `a device playing lately is offered until its now is 75 seconds old`() {
        val peers = mapOf(tv to peer(tv, nowAt = 1_000))
        val found = findPlayingElsewhere(peers, chosen = null, closed = emptyMap(), at = 2_000)
        assertThat(found?.peer?.did).isEqualTo(tv)
        assertThat(found?.expiresAt).isEqualTo(1_000 + PLAYING_ELSEWHERE_FRESH_MS)
        assertThat(findPlayingElsewhere(peers, null, emptyMap(), at = 1_000 + PLAYING_ELSEWHERE_FRESH_MS - 1)).isNotNull()
        assertThat(findPlayingElsewhere(peers, null, emptyMap(), at = 1_000 + PLAYING_ELSEWHERE_FRESH_MS)).isNull()
    }

    @Test
    fun `a paused or empty device is never offered`() {
        val peers =
            mapOf(
                tv to peer(tv, nowAt = 1_000, now = song.copy(state = LiveMessage.PAUSED)),
                laptop to peer(laptop, nowAt = 1_000, now = LiveMessage.Now(video = null, state = LiveMessage.PLAYING)),
            )
        assertThat(findPlayingElsewhere(peers, null, emptyMap(), at = 2_000)).isNull()
    }

    @Test
    fun `nothing is offered while the user drives a device`() {
        val peers = mapOf(tv to peer(tv, nowAt = 1_000))
        assertThat(findPlayingElsewhere(peers, chosen = laptop, closed = emptyMap(), at = 2_000)).isNull()
    }

    @Test
    fun `the device whose now came last is offered`() {
        val peers = mapOf(tv to peer(tv, nowAt = 1_000), laptop to peer(laptop, nowAt = 5_000))
        assertThat(findPlayingElsewhere(peers, null, emptyMap(), at = 6_000)?.peer?.did).isEqualTo(laptop)
    }

    @Test
    fun `closing hides the track until the device plays another one`() {
        val closed = withClosedHint(emptyMap(), tv, song.video)
        val peers = mapOf(tv to peer(tv, nowAt = 1_000))
        assertThat(findPlayingElsewhere(peers, null, closed, at = 2_000)).isNull()
        val next = mapOf(tv to peer(tv, nowAt = 3_000, now = song.copy(video = "bbbbbbbbbbb")))
        assertThat(findPlayingElsewhere(next, null, closed, at = 4_000)?.peer?.did).isEqualTo(tv)
    }

    @Test
    fun `a closed device leaves the offer to another one playing`() {
        val closed = withClosedHint(emptyMap(), laptop, song.video)
        val peers = mapOf(tv to peer(tv, nowAt = 1_000), laptop to peer(laptop, nowAt = 5_000))
        val found = findPlayingElsewhere(peers, null, closed, at = 6_000)
        assertThat(found?.peer?.did).isEqualTo(tv)
        assertThat(found?.expiresAt).isEqualTo(1_000 + PLAYING_ELSEWHERE_FRESH_MS)
    }

    @Test
    fun `closing again on a device replaces the track closed there`() {
        val closed = withClosedHint(withClosedHint(emptyMap(), tv, "aaaaaaaaaaa"), tv, "bbbbbbbbbbb")
        assertThat(closed).containsExactly(tv, "bbbbbbbbbbb")
    }
}
