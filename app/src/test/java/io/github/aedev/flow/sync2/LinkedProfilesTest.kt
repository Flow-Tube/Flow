package io.github.aedev.flow.sync2

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.data.profile.CreateResult
import io.github.aedev.flow.data.profile.Profile
import io.github.aedev.flow.data.profile.ProfileRegistryState
import io.github.aedev.flow.data.profile.ProfileRepository
import io.github.aedev.flow.data.profile.RunningProfile
import io.github.aedev.flow.sync.identity.DeviceIdentity
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.changes.PROFILES
import io.github.aedev.flow.sync2.changes.batchContent
import io.github.aedev.flow.sync2.changes.parseBatch
import io.github.aedev.flow.sync2.data.DEFAULT_PROFILE
import io.github.aedev.flow.sync2.data.KeptBatchRow
import io.github.aedev.flow.sync2.data.KeptBatches
import io.github.aedev.flow.sync2.data.LinkReadRow
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.ProfileLinkRow
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.service.LinkedProfiles
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.io.path.createTempDirectory

/** Which profile syncs under which link, and the `profiles` collection that names the links (spec/protocol.md, Profiles). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LinkedProfilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val default = "default-profile"
    private val kids = "kids-profile"
    private val other = "other-link"
    private val registry =
        MutableStateFlow(ProfileRegistryState(listOf(Profile(default), Profile(kids, "Kids")), defaultId = default, activeId = default))
    private val repository =
        mockk<ProfileRepository>(relaxed = true) {
            every { state } returns registry
            every { running() } answers { RunningProfile(checkNotNull(registry.value.activeId), registry.value.activeId == default) }
        }

    private fun profiles(db: LinkedDatabase) =
        LinkedProfiles(context, db, repository, DeviceIdentity(context), KeptBatches(createTempDirectory("kept").toFile(), db.profiles()))

    /** Links [profile] the way the app does: with it running. */
    private suspend fun LinkedProfiles.linkRunning(
        profile: String,
        link: String,
    ) {
        val running = registry.value.activeId
        registry.value = registry.value.copy(activeId = profile)
        link(profile, link)
        registry.value = registry.value.copy(activeId = running)
    }

    private fun watched(id: String) =
        mapOf(SyncCollection.WATCH_HISTORY to CollectionWire(listOf("""{"title":"t","videoId":"$id"}"""), 1, ""))

    private fun names(vararg lines: String) = listOf(parseBatch(batchContent(PROFILES, "", null, lines.toList())))

    @Test
    fun a_group_from_before_keeps_its_link_for_default() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val db = g.deviceDb()
            db.profiles().link(ProfileLinkRow(DEFAULT_PROFILE, LINK, caughtUp = true))
            val linked = profiles(db)
            assertEquals(listOf(ProfileLinkRow(default, LINK, caughtUp = true)), linked.links(Session.open(db, g.store, g.a)))
            assertEquals(LINK, linked.activeLink())
            registry.value = registry.value.copy(activeId = kids)
            assertNull("another profile doesn't sync", linked.activeLink())
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
    fun a_restored_device_leaves_the_choice_to_the_user_when_the_logs_name_several_links() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            Session.open(g.deviceDb(), g.store, g.a).apply {
                push(watched("aaaaaaaaaaa"), LINK)
                push(watched("bbbbbbbbbbb"), other)
            }
            val (device, db) = restoredDevice(g)
            assertEquals(emptyList<ProfileLinkRow>(), profiles(db).links(Session.open(db, g.store, device)))
            assertTrue("it doesn't read every log again", checkNotNull(db.groups().group()).link.isNotEmpty())
        }

    @Test
    fun a_link_this_device_read_is_caught_up_and_a_new_one_is_not() =
        runTest {
            val db = emptyDb()
            val linked = profiles(db)
            linked.merge(names("""{"deleted":false,"hlc":"5:0:aaaaaaaa","link":"$other","name":"Kids"}"""))
            linked.linkRunning(kids, other)
            linked.link(default, default)
            assertEquals(
                setOf(ProfileLinkRow(kids, other, caughtUp = false), ProfileLinkRow(default, default, caughtUp = true)),
                db.profiles().links().toSet(),
            )
        }

    @Test
    fun each_linked_profile_is_named_and_a_rename_here_goes_out_again() =
        runTest {
            val db = emptyDb()
            val linked = profiles(db)
            linked.linkRunning(kids, kids)
            val first =
                linked
                    .export()
                    .getValue(PROFILES)
                    .lines
                    .single()
            assertTrue(first, first.startsWith("""{"deleted":false,"hlc":""") && first.endsWith(""","link":"$kids","name":"Kids"}"""))
            registry.value = registry.value.copy(profiles = listOf(Profile(default), Profile(kids, "Children")))
            assertTrue(
                linked
                    .export()
                    .getValue(PROFILES)
                    .lines
                    .single()
                    .endsWith(""""name":"Children"}"""),
            )
        }

    @Test
    fun the_newer_record_wins_and_renames_or_unlinks_the_profile_here() =
        runTest {
            val db = emptyDb()
            val linked = profiles(db)
            linked.link(default, LINK)
            linked.linkRunning(kids, other)
            linked.merge(names("""{"deleted":false,"hlc":"9:0:aaaaaaaa","link":"$other","name":"Children"}"""))
            linked.merge(names("""{"deleted":false,"hlc":"3:0:bbbbbbbb","link":"$other","name":"Older"}"""))
            coVerify(exactly = 1) { repository.rename(kids, "Children") }
            coVerify(exactly = 0) { repository.rename(kids, "Older") }

            linked.merge(names("""{"deleted":true,"hlc":"10:0:aaaaaaaa","link":"$other","name":"Children"}"""))
            assertEquals(listOf(ProfileLinkRow(default, LINK, caughtUp = true)), db.profiles().links())
            assertFalse(
                "a deleted link isn't offered",
                other in
                    linked.choices
                        .first()
                        .others
                        .map { it.link },
            )
        }

    @Test
    fun a_default_profile_never_renamed_goes_out_with_a_blank_name() =
        runTest {
            val db = emptyDb()
            val linked = profiles(db)
            linked.link(default, default)
            assertTrue(
                linked
                    .export()
                    .getValue(PROFILES)
                    .lines
                    .single()
                    .endsWith(""","link":"$default","name":""}"""),
            )
            linked.merge(names("""{"deleted":false,"hlc":"20:0:aaaaaaaa","link":"$default","name":""}"""))
            coVerify(exactly = 0) { repository.rename(any(), any()) }
        }

    @Test
    fun a_deleted_only_link_leaves_this_device_with_none_and_no_device_to_add() =
        runTest {
            val db = emptyDb()
            val linked = profiles(db)
            linked.link(default, LINK)
            linked.merge(names("""{"deleted":true,"hlc":"20:0:aaaaaaaa","link":"$LINK","name":""}"""))
            assertEquals(emptyList<ProfileLinkRow>(), db.profiles().links())
            assertTrue(runCatching { linked.welcomeLink() }.exceptionOrNull() is LinkedException.NoProfileSynced)
        }

    @Test
    fun one_profile_stays_linked_and_stopping_everywhere_deletes_the_link() =
        runTest {
            val db = emptyDb()
            val linked = profiles(db)
            linked.link(default, LINK)
            assertTrue(runCatching { linked.stopHere(default) }.isFailure)
            linked.linkRunning(kids, kids)
            linked.stopEverywhere(kids)
            assertEquals(listOf(default), db.profiles().links().map { it.profile })
            assertTrue(
                db
                    .profiles()
                    .names()
                    .single { it.link == kids }
                    .deleted,
            )
        }

    @Test
    fun a_new_device_joins_default_s_link_whichever_profile_runs_or_the_first_one_synced() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val linked = profiles(g.deviceDb())
            linked.linkRunning(kids, other)
            linked.link(default, LINK)
            registry.value = registry.value.copy(activeId = kids)
            assertEquals(LINK, linked.welcomeLink())
            linked.stopHere(default)
            assertEquals(other, linked.welcomeLink())
        }

    @Test
    fun only_the_running_profile_links() =
        runTest {
            val db = emptyDb()
            val linked = profiles(db)
            assertTrue(runCatching { linked.link(kids, kids) }.isFailure)
            assertEquals(emptyList<ProfileLinkRow>(), db.profiles().links())
        }

    @Test
    fun a_profile_that_is_gone_stops_syncing() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val db = g.deviceDb()
            val linked = profiles(db)
            linked.link(default, LINK)
            linked.linkRunning(kids, other)
            registry.value = registry.value.copy(profiles = listOf(Profile(default)))
            assertEquals(listOf(default), linked.links(Session.open(db, g.store, g.a))?.map { it.profile })
        }

    @Test
    fun the_running_profile_catches_up_while_its_link_is_unread_or_has_batches_or_reads_waiting() =
        runTest {
            val db = emptyDb()
            val linked = profiles(db)
            linked.merge(names("""{"deleted":false,"hlc":"5:0:aaaaaaaa","link":"$other","name":"Kids"}"""))
            linked.linkRunning(kids, other)
            assertFalse("a profile that doesn't run waits for its switch", linked.catchingUp())
            registry.value = registry.value.copy(activeId = kids)
            assertTrue("its link isn't read yet", linked.catchingUp())
            db.profiles().caughtUp(other)
            assertFalse(linked.catchingUp())
            val kept = db.profiles().keep(KeptBatchRow(link = other, sender = "sender", collection = "watch_history"))
            assertTrue("batches were kept for it", linked.catchingUp())
            db.profiles().applied(kept)
            db.profiles().saveReads(listOf(LinkReadRow(other, "sender", 1, "hash")))
            assertTrue("a read of its link is under way", linked.catchingUp())
        }

    @Test
    fun a_profile_is_created_for_a_synced_link() =
        runTest {
            val db = emptyDb()
            coEvery { repository.create("Kids") } returns CreateResult.Created(Profile("new-profile", "Kids"))
            assertTrue(profiles(db).createFor(other, "Kids"))
            assertEquals(other, db.profiles().linkOf("new-profile"))
        }
}
