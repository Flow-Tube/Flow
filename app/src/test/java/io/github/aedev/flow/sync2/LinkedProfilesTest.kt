package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.data.profile.ProfileRegistryState
import io.github.aedev.flow.data.profile.ProfileRepository
import io.github.aedev.flow.data.profile.RunningProfile
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.data.DEFAULT_PROFILE
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.ProfileLinkRow
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.service.LinkedProfiles
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Which profile syncs under which link, including groups from before profiles chose their links. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LinkedProfilesTest {
    private val default = "default-profile"

    private fun profiles(
        db: LinkedDatabase,
        running: String = default,
    ) = LinkedProfiles(
        db,
        mockk<ProfileRepository> {
            every { state } returns flowOf(ProfileRegistryState(defaultId = default, activeId = running))
            every { running() } returns RunningProfile(running, isDefault = running == default)
        },
    )

    private fun watched(id: String) =
        mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"$id"}"""), 1, ""))

    @Test
    fun a_group_from_before_keeps_its_link_for_default() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val db = g.deviceDb()
            db.profiles().link(ProfileLinkRow(DEFAULT_PROFILE, LINK, caughtUp = true))
            val links = profiles(db).links(Session.open(db, g.store, g.a))
            assertEquals(listOf(ProfileLinkRow(default, LINK, caughtUp = true)), links)
            assertEquals(LINK, profiles(db).activeLink())
            assertNull("another profile doesn't sync", profiles(db, running = "other-profile").activeLink())
        }

    @Test
    fun a_device_that_stopped_every_link_links_nothing_by_itself() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            Session.open(g.deviceDb(), g.store, g.a).push(watched("aaaaaaaaaaa"), LINK)
            val db = g.deviceDb()
            assertEquals(emptyList<ProfileLinkRow>(), profiles(db).links(Session.open(db, g.store, g.b)))
        }

    @Test
    fun a_restored_device_starts_its_own_link_when_nothing_was_synced() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val (device, db) = restoredDevice(g)
            assertEquals(default, profiles(db).links(Session.open(db, g.store, device))?.single()?.link)
        }

    @Test
    fun a_restored_device_links_default_to_the_one_link_synced() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            Session.open(g.deviceDb(), g.store, g.a).push(watched("aaaaaaaaaaa"), LINK)
            val (device, db) = restoredDevice(g)
            assertEquals(LINK, profiles(db).links(Session.open(db, g.store, device))?.single()?.link)
        }

    @Test
    fun a_restored_device_waits_when_the_logs_name_several_links() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            Session.open(g.deviceDb(), g.store, g.a).apply {
                push(watched("aaaaaaaaaaa"), LINK)
                push(watched("bbbbbbbbbbb"), "other-link")
            }
            val (device, db) = restoredDevice(g)
            assertNull(profiles(db).links(Session.open(db, g.store, device)))
            assertEquals(emptyList<ProfileLinkRow>(), db.profiles().links())
        }
}
