package io.github.aedev.flow.sync2.service

import io.github.aedev.flow.data.profile.ProfileRepository
import io.github.aedev.flow.sync2.data.DEFAULT_PROFILE
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.ProfileLinkRow
import io.github.aedev.flow.sync2.engine.Session
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Which of this device's profiles sync, and under which links (spec/protocol.md, Profiles). */
@Singleton
class LinkedProfiles
    @Inject
    constructor(
        private val db: LinkedDatabase,
        private val profiles: ProfileRepository,
    ) {
        suspend fun defaultId(): String = checkNotNull(profiles.state.first().defaultId)

        /** The link the running profile syncs under, or null when it doesn't sync. */
        suspend fun activeLink(): String? = db.profiles().linkOf(profiles.running().id)

        /**
         * This device's links, or null while it can't tell yet. A restored device, whose group record names no
         * link, links Default once the logs name one link or none (spec/protocol.md, Recovery), and waits for the
         * user when they name several.
         */
        suspend fun links(session: Session): List<ProfileLinkRow>? {
            val dao = db.profiles()
            dao.moveProfile(DEFAULT_PROFILE, defaultId())
            dao.links().takeIf { it.isNotEmpty() || session.record.link.isNotEmpty() }?.let { return it }
            val named = session.namedLinks() ?: return null
            if (named.size > 1) return null
            val link = named.singleOrNull() ?: defaultId()
            dao.adopt(defaultId(), link)
            return dao.links()
        }
    }
