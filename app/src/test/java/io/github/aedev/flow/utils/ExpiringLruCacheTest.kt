package io.github.aedev.flow.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExpiringLruCacheTest {
    private var nowMs = 1_000L
    private val cache = ExpiringLruCache<String, String>(capacity = 3) { nowMs }

    @Test
    fun `an entry is fresh until its lifetime runs out`() {
        cache.put("a", "song a", ttlMs = 600_000L)

        nowMs += 599_999L
        assertThat(cache.fresh("a")).isEqualTo("song a")

        nowMs += 1L
        assertThat(cache.fresh("a")).isNull()
    }

    @Test
    fun `an expired entry no longer counts as warm, so it is fetched again`() {
        cache.put("a", "song a", ttlMs = 600_000L)
        nowMs += 3_600_000L

        assertThat(cache.fresh("a")).isNull()
        assertThat(cache.size()).isEqualTo(0)

        cache.put("a", "song a again", ttlMs = 600_000L)
        assertThat(cache.fresh("a")).isEqualTo("song a again")
    }

    @Test
    fun `stored reads an entry at any age until fresh drops it`() {
        cache.put("a", "song a", ttlMs = 1_000L)
        nowMs += 5_000L

        assertThat(cache.stored("a")).isEqualTo("song a")
        cache.fresh("a")
        assertThat(cache.stored("a")).isNull()
    }

    @Test
    fun `the least recently used entry goes first once full`() {
        cache.put("a", "1", ttlMs = 60_000L)
        cache.put("b", "2", ttlMs = 60_000L)
        cache.put("c", "3", ttlMs = 60_000L)
        cache.fresh("a")

        cache.put("d", "4", ttlMs = 60_000L)

        assertThat(cache.size()).isEqualTo(3)
        assertThat(cache.fresh("b")).isNull()
        assertThat(cache.fresh("a")).isEqualTo("1")
        assertThat(cache.fresh("d")).isEqualTo("4")
    }

    @Test
    fun `each entry keeps its own lifetime`() {
        cache.put("short", "1", ttlMs = 30_000L)
        cache.put("long", "2", ttlMs = 300_000L)
        nowMs += 60_000L

        assertThat(cache.fresh("short")).isNull()
        assertThat(cache.fresh("long")).isEqualTo("2")
    }

    @Test
    fun `remove and clear drop entries`() {
        cache.put("a", "1", ttlMs = 60_000L)
        cache.put("b", "2", ttlMs = 60_000L)

        cache.remove("a")
        assertThat(cache.fresh("a")).isNull()

        cache.clear()
        assertThat(cache.size()).isEqualTo(0)
    }
}
