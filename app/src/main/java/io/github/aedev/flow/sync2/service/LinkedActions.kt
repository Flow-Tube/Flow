package io.github.aedev.flow.sync2.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.profile.ActiveProfile
import io.github.aedev.flow.di.ApplicationScope
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.identity.DeviceIdentity
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.data.GroupRecord
import io.github.aedev.flow.sync2.data.LAN_STORE
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.webDavUrl
import io.github.aedev.flow.sync2.device.DeviceKeys
import io.github.aedev.flow.sync2.device.StoreCredentials
import io.github.aedev.flow.sync2.device.WebDavLogin
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.lan.LanSync
import io.github.aedev.flow.sync2.lan.lanPort
import io.github.aedev.flow.sync2.linking.Code
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.StoreLocation
import io.github.aedev.flow.sync2.linking.addDevice
import io.github.aedev.flow.sync2.linking.addScanned
import io.github.aedev.flow.sync2.linking.answerShown
import io.github.aedev.flow.sync2.linking.askToJoin
import io.github.aedev.flow.sync2.linking.createGroup
import io.github.aedev.flow.sync2.linking.createLanGroup
import io.github.aedev.flow.sync2.linking.createWebDavGroup
import io.github.aedev.flow.sync2.linking.deviceNames
import io.github.aedev.flow.sync2.linking.join
import io.github.aedev.flow.sync2.linking.listen
import io.github.aedev.flow.sync2.linking.newOffer
import io.github.aedev.flow.sync2.linking.readCode
import io.github.aedev.flow.sync2.linking.removeDevice
import io.github.aedev.flow.sync2.linking.renameDevice
import io.github.aedev.flow.sync2.linking.restoreGroup
import io.github.aedev.flow.sync2.linking.restoreWebDavGroup
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.store.FileLayout
import io.github.aedev.flow.sync2.store.JoinRequest
import io.github.aedev.flow.sync2.store.LocalStore
import io.github.aedev.flow.sync2.store.ServerStore
import io.github.aedev.flow.sync2.store.StoreException
import io.github.aedev.flow.sync2.store.WebDavStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val NAME_LIMIT = 64
private const val PLATFORM = "android"

/** Where adding or joining a device is: the code to show, the six digits to compare, the outcome. */
sealed interface LinkPhase {
    data object Idle : LinkPhase

    /** [code] is the QR this device shows; null while it answers another device's code. */
    data class Waiting(
        val code: String?,
    ) : LinkPhase

    data class Confirm(
        val sas: String,
    ) : LinkPhase

    /** This device asked to join with the admin token and waits for a linked device to accept. */
    data object Asked : LinkPhase

    data object Linking : LinkPhase

    data object Done : LinkPhase

    data class Failed(
        val error: LinkedError,
    ) : LinkPhase
}

data class LinkedDevice(
    val id: String,
    val name: String,
    val platform: String,
    val thisDevice: Boolean,
    /** When the server last heard from it; null in a group without a server. */
    val seen: Long? = null,
)

/** What the Linked devices screen can do. Pairing runs in the application scope, so it survives the screen. */
@Singleton
class LinkedActions
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: LinkedDatabase,
        private val keys: DeviceKeys,
        private val sync: LinkedSync,
        private val identity: DeviceIdentity,
        private val local: LocalStore,
        lan: LanSync,
        private val reset: LinkedReset,
        private val credentials: StoreCredentials,
        private val profiles: LinkedProfiles,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val phase = MutableStateFlow<LinkPhase>(LinkPhase.Idle)
        private var answer: CompletableDeferred<Boolean>? = null
        private var task: Job? = null

        val group: Flow<GroupRecord?> = db.groups().observeGroup()
        val lastSync: StateFlow<LastSync?> = sync.lastSync
        val link: StateFlow<LinkPhase> = phase.asStateFlow()
        val nearby: StateFlow<Map<String, Long>> = lan.nearby

        private suspend fun meta() = DeviceMeta(identity.deviceId(), identity.deviceName(), PLATFORM)

        private suspend fun linked(): GroupRecord = db.groups().group() ?: throw LinkedException.NotLinked()

        /** Fresh device keys for a group this device is about to create, join or restore (spec/crypto.md, Device keys). */
        private suspend fun newMembership(): MemberSecrets {
            if (db.groups().group() != null) throw LinkedException.AlreadyLinked()
            return keys.fresh()
        }

        private suspend fun session(): Session = sync.open(linked(), keys.load())

        private fun linkedNow() {
            LinkedSyncWorker.schedule(context)
            scope.launch { sync.syncNow() }
        }

        /** This group's devices, this one first. */
        suspend fun devices(): List<LinkedDevice> {
            val session = session()
            val names = deviceNames(session)
            val me = SyncBytes.toHex(session.device.did)
            // The status is extra; the devices still show without it.
            val seen =
                try {
                    sync.server(session.record, session.device)?.seen()
                } catch (_: StoreException) {
                    null
                }.orEmpty()
            return session.state.members
                .filterValues { it.role == Role.DEVICE }
                .keys
                .map { id -> LinkedDevice(id, names[id]?.name.orEmpty(), names[id]?.platform.orEmpty(), id == me, seen[id]) }
                .sortedByDescending { it.thisDevice }
        }

        /** Creates a group on a fresh server and returns the recovery words to show once. */
        suspend fun create(
            url: String,
            adminToken: String,
        ): String =
            createGroup(db, url.trim(), adminToken.trim(), newMembership(), meta(), ActiveProfile.get(context).id).also { linkedNow() }

        /**
         * Resets a server that already holds a group, deleting that group's objects for every device,
         * then creates a new group on it with the same admin token.
         */
        suspend fun resetAndCreate(
            url: String,
            adminToken: String,
        ): String {
            val device = newMembership()
            ServerStore.connect(url.trim(), device, null, null).resetServer(adminToken.trim())
            return createGroup(db, url.trim(), adminToken.trim(), device, meta(), ActiveProfile.get(context).id).also { linkedNow() }
        }

        /** Creates a group in an empty WebDAV folder and returns the recovery words. */
        suspend fun createOnWebDav(
            url: String,
            user: String,
            password: String,
        ): String {
            val (words, conditional) =
                createWebDavGroup(
                    db,
                    WebDavStore.connect(url.trim(), user, password),
                    newMembership(),
                    meta(),
                    ActiveProfile.get(context).id,
                )
            credentials.save(WebDavLogin(user, password, conditional))
            return words.also { linkedNow() }
        }

        /** Creates a group on this network alone, with no server, and returns the recovery words. */
        suspend fun createOnNetwork(): String =
            createLanGroup(db, local, newMembership(), meta(), ActiveProfile.get(context).id).also {
                linkedNow()
            }

        /** Restores the group with the recovery words and returns the new words to show once. */
        suspend fun restore(
            url: String,
            words: String,
        ): String = restoreGroup(db, url.trim(), words, newMembership(), meta()).also { linkedNow() }

        /** Restores the group from a WebDAV folder with the recovery words and returns the new words to show once. */
        suspend fun restoreFromWebDav(
            url: String,
            user: String,
            password: String,
            words: String,
        ): String {
            val restored = restoreWebDavGroup(db, WebDavStore.connect(url.trim(), user, password), words, newMembership(), meta())
            credentials.save(WebDavLogin(user, password))
            return restored.also { linkedNow() }
        }

        /** Replaces a WebDAV login the server refused, then syncs with it. */
        suspend fun updateLogin(
            user: String,
            password: String,
        ) {
            credentials.save(WebDavLogin(user, password))
            syncNow()
        }

        suspend fun confirmRecoverySaved() = db.groups().setRecoverySaved(true)

        suspend fun rename(
            id: String,
            name: String,
        ) {
            val session = session()
            val current = deviceNames(session)[id] ?: DeviceMeta("", "", "")
            renameDevice(session, keys.load(), id, current.copy(name = name.trim().take(NAME_LIMIT)))
        }

        suspend fun remove(id: String) {
            removeDevice(session(), keys.load(), id)
            db.snapshots().snapshotSoon()
        }

        /** Leaves the group, telling the others ([LinkedReset.leave]). */
        suspend fun leave() = reset.leave()

        /** Why the last leave couldn't tell the other devices, while the user decides. */
        val leaveFailed: StateFlow<LinkedError?> = reset.leaveFailed

        fun keepGroup() = reset.keepGroup()

        /** Forgets the group and this device's keys without telling the others, as after being removed. */
        suspend fun forget() = reset.forget()

        val syncing: StateFlow<Boolean> = sync.syncing

        fun syncNow() = LinkedSyncWorker.syncSoon(context, announce = true)

        /** Shows a member's code, answered through the server's mailbox or over the LAN. */
        fun addDevice() =
            start {
                val record = linked()
                val device = keys.load()
                val offer = newOffer(location(record), meta().name, listen())
                phase.value = LinkPhase.Waiting(offer.qr)
                addDevice(
                    sync.open(record, device),
                    sync.remote(record, device),
                    offer,
                    device,
                    profiles.welcomeLink(),
                    credentials.webDav(),
                    lanPort(context),
                    ::confirm,
                )
            }

        /** Where this device's group lives, as its codes name it: never with credentials. */
        private fun location(record: GroupRecord): StoreLocation =
            when {
                record.storeUrl == LAN_STORE -> StoreLocation("lan")
                record.webDavUrl != null -> StoreLocation("webdav", record.webDavUrl)
                else -> StoreLocation("server", record.storeUrl)
            }

        /**
         * Answers another device's code: a member's when this device isn't linked, a new device's when
         * it is. [login] is a WebDAV login the user entered before scanning, so pairing can go through
         * that folder when the devices aren't on the same network.
         */
        fun enterCode(
            text: String,
            login: WebDavLogin? = null,
        ) = start {
            when (val code = readCode(text)) {
                is Code.Member -> {
                    joinMember(code, newMembership(), login)
                }

                is Code.New -> {
                    if (db.groups().group() == null) throw LinkedException.NeedsMember()
                    addScanned(session(), code, keys.load(), profiles.welcomeLink(), credentials.webDav(), lanPort(context), ::confirm)
                }
            }
        }

        private suspend fun joinMember(
            code: Code.Member,
            device: MemberSecrets,
            login: WebDavLogin? = null,
        ) {
            val url = code.storeUrl
            val mailbox =
                when {
                    url == null -> {
                        null
                    }

                    code.store?.k == "webdav" -> {
                        login?.let {
                            FileLayout(
                                WebDavStore.connect(url, it.user, it.password),
                                SyncBytes.toHex(device.did),
                            )
                        }
                    }

                    else -> {
                        ServerStore.connect(url, device, null, null)
                    }
                }
            val welcomed = join(db, local, { it.remote(device) }, mailbox, code, device, meta(), joining(), lanPort(context), ::confirm)
            welcomed.login?.let { credentials.save(it) }
            linkedNow()
        }

        /** Asks to join a group on [url] with its admin token, then pairs with the member that accepts. */
        fun joinWithAdmin(
            url: String,
            adminToken: String,
        ) = start {
            val device = newMembership()
            phase.value = LinkPhase.Asked
            val code = askToJoin(ServerStore.connect(url.trim(), device, null, null), adminToken.trim(), meta().name)
            phase.value = LinkPhase.Waiting(null)
            joinMember(code, device)
        }

        /** Devices asking to join this device's server group. */
        suspend fun joinRequests(): List<JoinRequest> {
            val record = db.groups().group() ?: return emptyList()
            return sync.server(record, keys.load())?.joinRequests().orEmpty()
        }

        /** Answers a join request with this device's code, then pairs as if that device had scanned it. */
        fun accept(id: String) =
            start {
                val record = linked()
                val device = keys.load()
                val server = sync.server(record, device) ?: throw LinkedException.Unexpected()
                val offer = newOffer(StoreLocation("server", record.storeUrl), meta().name, null)
                server.answerJoin(id, offer.qr)
                addDevice(
                    sync.open(record, device),
                    server,
                    offer,
                    device,
                    profiles.welcomeLink(),
                    port = lanPort(context),
                    confirm = ::confirm,
                )
            }

        suspend fun decline(id: String) {
            val record = linked()
            sync.server(record, keys.load())?.declineJoin(id)
        }

        /** Shows a new device's code, for a member on the same network to scan: how a TV joins. */
        fun showCode() =
            start {
                val device = newMembership()
                val offer = newOffer(null, meta().name, listen() ?: throw LinkedException.LanUnreachable())
                phase.value = LinkPhase.Waiting(offer.qr)
                val welcomed = answerShown(offer, device, meta(), lanPort(context), ::confirm)
                welcomed.finish(db, local, welcomed.remote(device), joining())
                welcomed.login?.let { credentials.save(it) }
                linkedNow()
            }

        fun answer(accept: Boolean) {
            answer?.complete(accept)
        }

        fun cancel() {
            task?.cancel()
            answer?.complete(false)
            phase.value = LinkPhase.Idle
        }

        private suspend fun confirm(sas: String): Boolean {
            val decision = CompletableDeferred<Boolean>().also { answer = it }
            phase.value = LinkPhase.Confirm(sas)
            return decision.await().also { if (it) phase.value = LinkPhase.Linking }
        }

        private fun start(block: suspend () -> Unit) {
            if (task?.isActive == true) return
            phase.value = LinkPhase.Waiting(null)
            task =
                scope.launch {
                    phase.value =
                        try {
                            block()
                            LinkPhase.Done
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LinkPhase.Failed(e.linkedError())
                        }
                }
        }

        /** A joining device's Default profile joins the member's link (spec/protocol.md, Messages). */
        private suspend fun joining(): String = profiles.defaultId()
    }
