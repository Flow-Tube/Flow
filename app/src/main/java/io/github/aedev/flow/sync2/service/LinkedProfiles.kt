package io.github.aedev.flow.sync2.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.profile.CreateResult
import io.github.aedev.flow.data.profile.ProfileRegistry
import io.github.aedev.flow.data.profile.ProfileRepository
import io.github.aedev.flow.data.profile.displayName
import io.github.aedev.flow.sync.identity.DeviceIdentity
import io.github.aedev.flow.sync.identity.Hlc
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncSerialization
import io.github.aedev.flow.sync2.changes.IncomingBatch
import io.github.aedev.flow.sync2.changes.PROFILES
import io.github.aedev.flow.sync2.data.DEFAULT_PROFILE
import io.github.aedev.flow.sync2.data.KeptBatches
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.ProfileLinkRow
import io.github.aedev.flow.sync2.data.ProfileNameRow
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A profile on this device, and the link it syncs under when it does. */
data class LocalProfile(
    val id: String,
    val name: String,
    val link: String?,
)

/** A synced profile no profile here syncs; [name] is null when no device named it, and blank for a default profile never renamed. */
data class OtherProfile(
    val link: String,
    val name: String?,
)

/** What Linked devices offers about profiles: this device's, the others synced, and room for a new one. */
data class ProfileChoices(
    val local: List<LocalProfile> = emptyList(),
    val others: List<OtherProfile> = emptyList(),
    val canCreate: Boolean = false,
)

/** A record of the `profiles` collection (spec/protocol.md, Profiles). */
@Serializable
private data class ProfileRecord(
    val deleted: Boolean,
    val hlc: String,
    val link: String,
    val name: String,
)

private val records = Json { ignoreUnknownKeys = true }

/** Which of this device's profiles sync, and under which links (spec/protocol.md, Profiles). */
@Singleton
class LinkedProfiles
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: LinkedDatabase,
        private val profiles: ProfileRepository,
        private val identity: DeviceIdentity,
        private val kept: KeptBatches,
    ) {
        private val dao get() = db.profiles()

        suspend fun defaultId(): String = checkNotNull(profiles.state.first().defaultId)

        /** The link the running profile syncs under, or null when it doesn't sync. */
        suspend fun activeLink(): String? = dao.linkOf(profiles.running().id)

        val choices: Flow<ProfileChoices> =
            combine(profiles.state, dao.observeLinks(), dao.observeNames(), dao.observeNamed()) { state, links, names, named ->
                val linked = links.associate { it.profile to it.link }
                val deleted = names.filter { it.deleted }.map { it.link }.toSet()
                val known = names.map { it.link } + named.mapNotNull { Json.decodeFromString(String.serializer().nullable, it) }
                ProfileChoices(
                    local = state.profiles.map { LocalProfile(it.id, it.displayName(context), linked[it.id]) },
                    others =
                        known
                            .distinct()
                            .filter { it !in deleted && it !in linked.values }
                            .map { link -> OtherProfile(link, names.firstOrNull { it.link == link }?.name) },
                    canCreate = state.profiles.size < ProfileRegistry.MAX_PROFILES,
                )
            }

        /**
         * This device's links, or null while it can't tell yet. A restored device, whose group record names no
         * link, links Default once the logs name one link or none (spec/protocol.md, Recovery); with several, or
         * once every link here stopped, the user chooses.
         */
        suspend fun links(session: Session): List<ProfileLinkRow>? {
            dao.moveProfile(DEFAULT_PROFILE, defaultId())
            val present =
                profiles.state
                    .first()
                    .profiles
                    .map { it.id }
            dao.links().filter { it.profile !in present }.forEach { unlink(it.profile, it.link) }
            dao.links().takeIf { it.isNotEmpty() || session.record.link.isNotEmpty() }?.let { return it }
            val named = session.namedLinks() ?: return null
            if (named.size <= 1) {
                dao.choose(defaultId(), named.singleOrNull() ?: defaultId(), caughtUp = true)
            } else {
                // Reading every log again on each sync would repeat that first read: the user chooses from here on.
                dao.setGroupLink(named.min())
            }
            return dao.links()
        }

        /** [profile] syncs under [link], its own id for a link of its own; its data merges with the link's. */
        suspend fun link(
            profile: String,
            link: String,
        ) {
            // A link this device never read needs no catching up.
            val read = link in dao.names().map { it.link } || link in choices.first().others.map { it.link }
            dao.choose(profile, link, caughtUp = !read)
        }

        /** Creates a profile here for [link], named as the link is, and links it; false at the profile limit. */
        suspend fun createFor(
            link: String,
            name: String,
        ): Boolean {
            val created = profiles.create(name) as? CreateResult.Created ?: return false
            link(created.profile.id, link)
            return true
        }

        /** Stops syncing [profile] here; its data stays. A device in a group keeps at least one profile linked. */
        suspend fun stopHere(profile: String) {
            val link = dao.linkOf(profile) ?: return
            check(dao.links().size > 1)
            unlink(profile, link)
        }

        /** Stops syncing [profile] on every device: its link is deleted, and each device keeps its data. */
        suspend fun stopEverywhere(profile: String) {
            val link = dao.linkOf(profile) ?: return
            check(dao.links().size > 1)
            record(link, dao.names().firstOrNull { it.link == link }?.name ?: nameOf(profile).orEmpty(), deleted = true)
            unlink(profile, link)
        }

        private suspend fun unlink(
            profile: String,
            link: String,
        ) {
            dao.unlink(profile)
            kept.drop(link)
            dao.dropReads(link)
        }

        /**
         * The link a new device's Default joins: the running profile's when it syncs, else the first profile's here
         * that does. A device whose every link was deleted adds no device until the user links a profile again.
         */
        suspend fun welcomeLink(): String {
            activeLink()?.let { return it }
            val linked = dao.links().associate { it.profile to it.link }
            return profiles.state
                .first()
                .profiles
                .firstNotNullOfOrNull { linked[it.id] } ?: throw LinkedException.NoProfileSynced()
        }

        /** This device's `profiles` records, after naming each link it syncs that has no record, or whose profile was renamed here. */
        suspend fun export(): Map<String, CollectionWire> {
            val names = dao.names().associateBy { it.link }
            for (row in dao.links()) {
                val name = nameOf(row.profile) ?: continue
                val current = names[row.link]
                if (current == null || (!current.deleted && current.name != name)) record(row.link, name, deleted = false)
            }
            val lines =
                dao.names().map {
                    SyncSerialization.canonicalJson(
                        records.encodeToJsonElement(ProfileRecord.serializer(), ProfileRecord(it.deleted, it.hlc, it.link, it.name)),
                    )
                }
            return mapOf(PROFILES to CollectionWire(lines, lines.size, ""))
        }

        /**
         * Merges other devices' `profiles` records, last writer wins: a deleted link stops syncing here, even the
         * only one, and a renamed one renames its profile. A blank name is a default profile never renamed, which no
         * profile here takes.
         */
        suspend fun merge(batches: List<IncomingBatch>) {
            for (line in batches.flatMap { it.lines }) {
                val record = runCatching { records.decodeFromString(ProfileRecord.serializer(), line) }.getOrNull() ?: continue
                val current = dao.names().firstOrNull { it.link == record.link }
                if (current != null && Hlc.compareEncoded(record.hlc, current.hlc) <= 0) continue
                identity.observe(Hlc.decode(record.hlc).physicalMs)
                dao.saveNames(listOf(ProfileNameRow(record.link, record.name, record.hlc, record.deleted)))
                val profile = dao.links().firstOrNull { it.link == record.link }?.profile ?: continue
                if (record.deleted) {
                    unlink(profile, record.link)
                } else if (record.name.isNotBlank()) {
                    profiles.rename(profile, record.name)
                }
            }
        }

        private suspend fun record(
            link: String,
            name: String,
            deleted: Boolean,
        ) = dao.saveNames(listOf(ProfileNameRow(link, name, Hlc(identity.editTime(), 0, identity.hlcNode()).encode(), deleted)))

        /** The name a `profiles` record carries: the registry's own, blank for a default profile never renamed. */
        private suspend fun nameOf(profile: String): String? =
            profiles.state
                .first()
                .profiles
                .firstOrNull { it.id == profile }
                ?.name
    }
