package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync2.live.LiveMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live messages carry every field the spec lists, defaults included, and still read the short form older phones send. */
class LiveMessageWireTest {
    private fun wire(message: LiveMessage) = LiveMessage.encode(message).decodeToString()

    @Test
    fun a_paused_now_says_it_is_paused_and_at_full_volume() {
        val json = wire(LiveMessage.Now(video = "v", title = "t"))
        assertTrue(json, "\"state\":\"paused\"" in json)
        assertTrue(json, "\"vol\":100" in json)
        assertTrue(json, "\"pos\":0" in json)
        assertTrue(json, "\"queue\":[]" in json)
    }

    @Test
    fun nothing_loaded_still_names_its_state_and_leaves_the_nulls_out() {
        val json = wire(LiveMessage.Now(video = null))
        assertTrue(json, "\"state\":\"paused\"" in json)
        assertFalse(json, "\"video\"" in json)
        assertFalse(json, "\"link\"" in json)
    }

    @Test
    fun a_command_leaves_out_the_values_it_does_not_carry() {
        assertEquals("""{"cmd":"pause","to":"ab","type":"cmd"}""", wire(LiveMessage.Cmd("ab", "pause")))
    }

    @Test
    fun the_short_form_of_older_phones_still_reads() {
        val now = LiveMessage.decode("""{"type":"now","video":"v"}""".toByteArray()) as LiveMessage.Now
        assertEquals(LiveMessage.PAUSED, now.state)
        assertEquals(100, now.vol)
    }
}
