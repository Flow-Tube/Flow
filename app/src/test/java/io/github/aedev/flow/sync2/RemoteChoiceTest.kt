package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync2.live.QUIET_CHECK_MS
import io.github.aedev.flow.sync2.live.RemoteChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The remote player follows the user's choice only, and drops it when it is cleared or the device leaves. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteChoiceTest {
    private val tv = "bb".repeat(16)

    @Test
    fun a_choice_lasts_until_it_is_cleared() =
        runTest(UnconfinedTestDispatcher()) {
            val choice = RemoteChoice()
            val watching = launch { choice.watch(MutableStateFlow(Unit)) { true } }
            assertNull("nothing is chosen until the user picks a device", choice.did.value)

            choice.choose(tv)
            advanceTimeBy(QUIET_CHECK_MS * 3)
            assertEquals(tv, choice.did.value)

            choice.clear()
            assertNull(choice.did.value)
            watching.cancel()
        }

    @Test
    fun a_choice_ends_when_the_device_goes_offline() =
        runTest(UnconfinedTestDispatcher()) {
            val choice = RemoteChoice()
            val online = MutableStateFlow(setOf(tv))
            val watching = launch { choice.watch(online) { it in online.value } }
            choice.choose(tv)
            assertEquals(tv, choice.did.value)

            online.value = emptySet()
            assertNull(choice.did.value)
            watching.cancel()
        }

    @Test
    fun a_quiet_device_drops_without_another_message() =
        runTest(UnconfinedTestDispatcher()) {
            val choice = RemoteChoice()
            var reachable = true
            val watching = launch { choice.watch(MutableStateFlow(Unit)) { reachable } }
            choice.choose(tv)
            reachable = false
            assertEquals("nothing has said so yet", tv, choice.did.value)

            advanceTimeBy(QUIET_CHECK_MS + 1)
            assertNull(choice.did.value)
            watching.cancel()
        }
}
