package io.github.aedev.flow.ui.screens.sync.linked

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.sync2.service.LinkedDevice
import org.junit.Test

class LinkedDeviceNamesTest {
    private fun device(
        id: String,
        name: String,
    ) = LinkedDevice(id = id, name = name, platform = "Windows", thisDevice = false)

    @Test
    fun `two devices with one name get the start of their ids (2184)`() {
        val ids =
            shortIdsForSharedNames(
                listOf(
                    device("3fa1c0de", "Flow Desktop (Windows)"),
                    device("9c07beef", "flow desktop (windows) "),
                    device("aaaa0000", "Pixel 9"),
                ),
            )

        assertThat(ids).containsExactly("3fa1c0de", "3fa1", "9c07beef", "9c07", "aaaa0000", null)
    }

    @Test
    fun `ids that start alike grow until they differ`() {
        val ids = shortIdsForSharedNames(listOf(device("3fa1c0de", "Desktop"), device("3fa1beef", "Desktop")))

        assertThat(ids.values).containsExactly("3fa1c0de", "3fa1beef")
    }
}
