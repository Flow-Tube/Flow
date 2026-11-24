package io.github.aedev.flow.sync2

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.sync2.data.LanAddressRow
import io.github.aedev.flow.sync2.lan.removalTargets
import org.junit.Test

/** #2184: the removed devices are told where they last listened, before the removal forgets them. */
class RemovalTargetsTest {
    @Test
    fun only_the_removed_devices_are_dialed_each_address_once() {
        val rows =
            listOf(
                LanAddressRow("removed", "192.168.1.14", 4100),
                LanAddressRow("kept", "192.168.1.15", 4100),
                LanAddressRow("removed-too", "192.168.1.16", 4100),
                LanAddressRow("removed-too", "192.168.1.16", 4100),
            )

        val targets = removalTargets(rows, listOf("removed", "removed-too"))

        assertThat(targets.map { it.did }).containsExactly("removed", "removed-too").inOrder()
    }
}
