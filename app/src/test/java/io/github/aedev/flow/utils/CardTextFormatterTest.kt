package io.github.aedev.flow.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Locale

class CardTextFormatterTest {
    private var now = 1_000_000L
    private val computed = mutableListOf<BylineKey>()

    private fun cache(
        capacity: Int = 8,
        freshMs: Long = 60_000L,
    ) = BylineCache(capacity, freshMs, { now }) { key, _ ->
        computed += key
        CardByline(date = "date of ${key.uploadDate}", views = "${key.viewCount} views")
    }

    private fun key(
        uploadDate: String = "3 days ago",
        viewCount: Long = 10L,
        locale: Locale = Locale.US,
        settings: DateDisplaySettings = DateDisplaySettings(),
    ) = BylineKey(
        uploadDate = uploadDate,
        timestamp = 0L,
        timestampIsExact = false,
        viewCount = viewCount,
        settings = settings,
        labels = TestDateLabels,
        viewsTemplate = "%1\$s views",
        locale = locale,
        hl = "en",
    )

    @Test
    fun `a video seen again within the minute is not formatted again`() {
        val cache = cache()

        val first = cache.get(key())
        now += 59_000L
        val second = cache.get(key())

        assertSame(first, second)
        assertEquals(1, computed.size)
    }

    @Test
    fun `an entry older than its freshness is formatted again`() {
        val cache = cache()

        cache.get(key())
        now += 60_000L
        cache.get(key())

        assertEquals(2, computed.size)
    }

    @Test
    fun `every input that changes the text is part of the key`() {
        val cache = cache()

        cache.get(key())
        cache.get(key(uploadDate = "4 days ago"))
        cache.get(key(viewCount = 11L))
        cache.get(key(settings = DateDisplaySettings(globalMode = DateDisplayMode.EXACT)))

        assertEquals(4, computed.size)
    }

    @Test
    fun `the cache stays bounded and drops the least recently used`() {
        val cache = cache(capacity = 2)

        cache.get(key(uploadDate = "a"))
        cache.get(key(uploadDate = "b"))
        cache.get(key(uploadDate = "a"))
        cache.get(key(uploadDate = "c"))
        cache.get(key(uploadDate = "a"))
        cache.get(key(uploadDate = "b"))

        assertEquals(2, cache.size())
        assertEquals(listOf("a", "b", "c", "b"), computed.map { it.uploadDate })
    }

    @Test
    fun `a locale change drops every entry`() {
        val cache = cache()

        cache.get(key(uploadDate = "a"))
        cache.get(key(uploadDate = "b"))
        cache.get(key(uploadDate = "a", locale = Locale.GERMANY))

        assertEquals(1, cache.size())
    }

    @Test
    fun `a locale slot builds one value per locale and rebuilds on a change`() {
        val built = mutableListOf<Locale>()
        val slot =
            LocaleSlot { locale: Locale ->
                built += locale
                "formatter for $locale"
            }

        assertEquals("formatter for en_US", slot.use(Locale.US) { it })
        slot.use(Locale.US) { it }
        slot.use(Locale.GERMANY) { it }
        slot.use(Locale.GERMANY) { it }

        assertEquals(listOf(Locale.US, Locale.GERMANY), built)
    }

    @Test
    fun `date labels are resolved once per locale`() {
        var resolved = 0
        val resolve = {
            resolved++
            TestDateLabels
        }
        val probe = Locale.forLanguageTag("x-card-test")

        CardTextFormatter.dateLabels(probe, resolve)
        CardTextFormatter.dateLabels(probe, resolve)

        assertEquals(1, resolved)
    }
}
