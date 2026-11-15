package io.github.aedev.flow.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NightlyNotesTest {
    private val body =
        """
        Built from abc1234 by run 2483. Installs next to the stable app as Flow Nightly.

        # Run 2483

        ## Fixed
        - Casting finds TVs on the phone's hotspot (#2121)

        # Run 2482

        ## New
        - Music AI filter banner (#865)

        ## Fixed
        - Chapter highlight no longer flashes (#2104)

        # Run 2480

        ## New
        - Linked devices pull-back (#1412)

        ## Improved
        - Channel headers in Spanish (#2058)
        """.trimIndent()

    @Test
    fun `keeps only runs newer than the installed one`() {
        assertThat(filterNightlyNotes(body, installedRun = 2482))
            .isEqualTo(
                """
                Built from abc1234 by run 2483. Installs next to the stable app as Flow Nightly.

                ## Fixed
                - Casting finds TVs on the phone's hotspot (#2121)
                """.trimIndent(),
            )
    }

    @Test
    fun `several runs behind sees all of them in section order`() {
        assertThat(filterNightlyNotes(body, installedRun = 2479))
            .isEqualTo(
                """
                Built from abc1234 by run 2483. Installs next to the stable app as Flow Nightly.

                ## New
                - Music AI filter banner (#865)
                - Linked devices pull-back (#1412)

                ## Improved
                - Channel headers in Spanish (#2058)

                ## Fixed
                - Casting finds TVs on the phone's hotspot (#2121)
                - Chapter highlight no longer flashes (#2104)
                """.trimIndent(),
            )
    }

    @Test
    fun `a run between published ones still counts as installed`() {
        assertThat(filterNightlyNotes(body, installedRun = 2481)).doesNotContain("Linked devices")
        assertThat(filterNightlyNotes(body, installedRun = 2481)).contains("Music AI filter banner")
    }

    @Test
    fun `a body without run groups is returned unchanged`() {
        val legacy = "Built from abc1234 by run 2480.\n\n## New\n- Something (#1)"

        assertThat(filterNightlyNotes(legacy, installedRun = 2479)).isEqualTo(legacy)
        assertThat(filterNightlyNotes(legacy, installedRun = 2490)).isEqualTo(legacy)
    }

    @Test
    fun `the intro is kept when no newer run has notes`() {
        assertThat(filterNightlyNotes(body, installedRun = 2483))
            .isEqualTo("Built from abc1234 by run 2483. Installs next to the stable app as Flow Nightly.")
    }

    @Test
    fun `filtered notes still parse into the update page sections`() {
        val sections = parseReleaseNotes(filterNightlyNotes(body, installedRun = 2479)).sections

        assertThat(sections.map { it.title }).containsExactly("New", "Improved", "Fixed").inOrder()
    }
}
