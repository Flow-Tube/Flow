package io.github.aedev.flow.sync2.linking

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.agreementPublicKey
import io.github.aedev.flow.sync2.crypto.decodePayload
import io.github.aedev.flow.sync2.crypto.encodePayload
import io.github.aedev.flow.sync2.crypto.memberId
import io.github.aedev.flow.sync2.crypto.open
import io.github.aedev.flow.sync2.crypto.pairingAad
import io.github.aedev.flow.sync2.crypto.pairingKeys
import io.github.aedev.flow.sync2.crypto.pairingSlot
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.crypto.seal
import io.github.aedev.flow.sync2.crypto.sign
import io.github.aedev.flow.sync2.crypto.wrapEpochKey
import io.github.aedev.flow.sync2.data.GroupRecord
import io.github.aedev.flow.sync2.data.LAN_STORE
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.WEBDAV_STORE
import io.github.aedev.flow.sync2.data.addEntries
import io.github.aedev.flow.sync2.data.webDavUrl
import io.github.aedev.flow.sync2.device.WebDavLogin
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.roster.Draft
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.Subject
import io.github.aedev.flow.sync2.roster.applyEntry
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.memberSubject
import io.github.aedev.flow.sync2.roster.replayRoster
import io.github.aedev.flow.sync2.roster.sealDeviceMeta
import io.github.aedev.flow.sync2.roster.signingBytes
import io.github.aedev.flow.sync2.store.Appended
import io.github.aedev.flow.sync2.store.FileLayout
import io.github.aedev.flow.sync2.store.LocalStore
import io.github.aedev.flow.sync2.store.Mailbox
import io.github.aedev.flow.sync2.store.Remote
import io.github.aedev.flow.sync2.store.ServerStore
import io.github.aedev.flow.sync2.store.Store
import io.github.aedev.flow.sync2.store.StoreException
import io.github.aedev.flow.sync2.store.WebDavStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.serialization.SerializationException

/** Sealed pairing messages over a pipe, numbered from 0 in each direction. */
internal class Channel(
    val pipe: Pipe,
    private val token: ByteArray,
    private val sealKey: ByteArray,
    private val openKey: ByteArray,
) {
    private var sent = 0L
    private var received = 0L

    suspend fun send(message: Message) {
        val content = canonical(Message.serializer(), message)
        pipe.send(seal(sealKey, pairingAad(token, sent), encodePayload(content.toByteArray())))
        sent++
    }

    suspend fun receive(): Message {
        val content = decodePayload(open(openKey, pairingAad(token, received), pipe.receive()))
        received++
        return try {
            pairingJson.decodeFromString(Message.serializer(), content.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            throw LinkedException.Unexpected()
        }
    }

    companion object {
        /** The side that showed [offer], once the scanner's key arrived. Returns the six digits too. */
        fun shower(
            pipe: Pipe,
            offer: Offer,
            scanner: ByteArray,
        ): Pair<Channel, String> {
            val keys = pairingKeys(offer.secret, scanner, offer.token, offer.public, scanner)
            return Channel(pipe, offer.token, keys.showerKey, keys.scannerKey) to keys.sas
        }

        /** The side that read [code]: sends its key first. */
        suspend fun scanner(
            pipe: Pipe,
            code: Code,
        ): Pair<Channel, String> {
            val secret = SyncCrypto.randomBytes(KEY_SIZE)
            val public = agreementPublicKey(secret)
            val keys = pairingKeys(secret, code.showerKey, code.token, code.showerKey, public)
            pipe.sendKey(public)
            return Channel(pipe, code.token, keys.scannerKey, keys.showerKey) to keys.sas
        }
    }
}

internal fun metaJson(meta: DeviceMeta): ByteArray = canonical(DeviceMeta.serializer(), meta).toByteArray()

/**
 * The member's side, on a code it shows: waits for the scanner on the mailbox and, when the code
 * lists LAN addresses, on the LAN too. Returns the new device's id.
 */
suspend fun addDevice(
    session: Session,
    mailbox: Mailbox?,
    offer: Offer,
    device: MemberSecrets,
    login: WebDavLogin? = null,
    port: Int? = null,
    confirm: suspend (String) -> Boolean,
): String {
    val slot = SyncBytes.toHex(pairingSlot(offer.token))
    mailbox?.openSlot(slot)
    try {
        val (pipe, key) =
            coroutineScope {
                val viaMailbox = mailbox?.let { MailboxPipe(it, slot, shower = true) }?.let { pipe -> async { pipe to pipe.receiveKey() } }
                val viaLan = offer.listening?.let { listening -> async { listening.accept() } }
                if (viaMailbox == null && viaLan == null) throw LinkedException.LanUnreachable()
                select<Pair<Pipe, ByteArray>> {
                    if (viaMailbox != null) viaMailbox.onAwait { it }
                    if (viaLan != null) viaLan.onAwait { it }
                }.also {
                    viaMailbox?.cancel()
                    viaLan?.cancel()
                }
            }
        val (channel, sas) = Channel.shower(pipe, offer, key)
        return memberSide(session, channel, sas, device, login, port, confirm)
    } finally {
        offer.listening?.stop()
        // The slot expires on its own if this fails.
        runCatching { mailbox?.closeSlot(slot) }
    }
}

/** The member's side, on a new device's code: reaches it over the LAN. */
suspend fun addScanned(
    session: Session,
    code: Code.New,
    device: MemberSecrets,
    login: WebDavLogin? = null,
    port: Int? = null,
    confirm: suspend (String) -> Boolean,
): String {
    val pipe = dial(code.lan) ?: throw LinkedException.LanUnreachable()
    try {
        val (channel, sas) = Channel.scanner(pipe, code)
        return memberSide(session, channel, sas, device, login, port, confirm)
    } finally {
        pipe.close()
    }
}

/**
 * [login]: the group's WebDAV login, which the welcome hands the new device. [port]: where this
 * device's LAN service listens, which a welcome over the LAN names.
 */
private suspend fun memberSide(
    session: Session,
    channel: Channel,
    sas: String,
    device: MemberSecrets,
    login: WebDavLogin?,
    port: Int?,
    confirm: suspend (String) -> Boolean,
): String {
    val join = channel.receive() as? Message.Join ?: throw LinkedException.Unexpected()
    if (!confirm(sas)) throw LinkedException.Declined()

    val sigPublic = key(join.sig)
    val kemPublic = key(join.kem)
    val did = memberId(sigPublic, kemPublic)
    val didHex = SyncBytes.toHex(did)
    val state = session.state
    val sealedMeta = sealDeviceMeta(session.currentKey(), session.group, state.epoch, state.seq + 1, did, metaJson(join.meta))
    val subject = memberSubject(sigPublic, kemPublic, Role.DEVICE)
    val draft =
        Draft(Op.ADD, SyncBytes.toHex(device.did), add = listOf(subject), meta = mapOf(didHex to SyncBytes.b64urlEncode(sealedMeta)))
    val unsigned = buildEntry(session.group, state, draft, listOf(device))
    channel.send(Message.EntryMessage(unsigned))

    val accept = channel.receive() as? Message.Accept ?: throw LinkedException.Unexpected()
    val entry = unsigned.copy(sigs = unsigned.sigs + (didHex to accept.sig))
    val after = applyEntry(state, entry)
    val wrap = wrapEpochKey(kemPublic, session.group, state.epoch, did, session.currentKey())
    if (session.store.appendRoster(state.head, entry, mapOf(didHex to SyncBytes.b64urlEncode(wrap)), null) != Appended.Done) {
        reject(ProtocolError.ROSTER_CHAIN)
    }

    val record = session.record
    val folder = record.webDavUrl
    val lan = channel.pipe as? LanPipe
    val p = port.takeIf { lan != null }
    val welcome =
        when {
            record.storeUrl == LAN_STORE -> {
                Message.Welcome(
                    record.groupId,
                    after.head,
                    after.seq,
                    record.link,
                    WelcomeStore("lan"),
                    session.store.rosterAfter(-1),
                    SyncBytes.b64urlEncode(wrap),
                    p,
                )
            }

            folder != null -> {
                val (user, password) = login ?: throw StoreException.LoginRefused()
                Message.Welcome(
                    record.groupId,
                    after.head,
                    after.seq,
                    record.link,
                    WelcomeStore("webdav", folder, n = user, w = password),
                    p = p,
                )
            }

            else -> {
                Message.Welcome(
                    record.groupId,
                    after.head,
                    after.seq,
                    record.link,
                    WelcomeStore("server", record.storeUrl, record.instance.orEmpty()),
                    p = p,
                )
            }
        }
    channel.send(welcome)
    if (channel.receive() != Message.Done) throw LinkedException.Unexpected()
    lan?.remember(session.db, didHex, join.p)
    return didHex
}

/** What a group's record keeps of where it lives (spec/live.md, LAN groups; WebDAV behind a prefix). */
private fun recordUrl(store: WelcomeStore): String =
    when (store.k) {
        "lan" -> LAN_STORE
        "webdav" -> WEBDAV_STORE + store.u.orEmpty()
        else -> store.u.orEmpty()
    }

/** A new device that was welcomed and has yet to check the roster and say it is done. */
class Welcomed internal constructor(
    private val channel: Channel,
    private val me: Subject,
    /** The member that welcomed this device. */
    private val member: String,
    internal val welcome: Message.Welcome,
    /** Closes the LAN connection or server the welcome came through. */
    private val close: suspend () -> Unit,
) {
    /** Where the group lives: a Flow server or WebDAV folder, or null for a LAN group, whose roster came with the welcome. */
    val storeUrl: String? get() = welcome.store.u
    val group: String get() = welcome.group

    /** The WebDAV login the member handed over, for the keystore. */
    val login: WebDavLogin?
        get() = welcome.store.takeIf { it.k == "webdav" }?.let { WebDavLogin(it.n.orEmpty(), it.w.orEmpty()) }

    /** The group's shared store, opened as [device]; null for a LAN group. */
    fun remote(device: MemberSecrets): Remote? {
        val store = welcome.store
        val url = store.u ?: return null
        return when (store.k) {
            "server" -> ServerStore.connect(url, device, store.i, group)
            "webdav" -> FileLayout(WebDavStore.connect(url, store.n.orEmpty(), store.w.orEmpty()), SyncBytes.toHex(device.did))
            else -> null
        }
    }

    /**
     * Saves the group once the roster verifies up to the head the member named: the one on [server],
     * or, for a LAN group, the one the welcome carried, which [local] then holds with this device's wrap.
     * [profile] joins the welcome's link.
     */
    suspend fun finish(
        db: LinkedDatabase,
        local: LocalStore,
        server: Store?,
        profile: String,
    ) {
        val roster = if (server != null) server.rosterAfter(-1) else welcome.entries ?: throw LinkedException.Unexpected()
        val end = roster.indexOfFirst { it.seq == welcome.seq }
        if (end < 0 || replayRoster(roster.take(end + 1)).head != welcome.head || me.did !in replayRoster(roster).members) {
            reject(ProtocolError.ROSTER_CHAIN)
        }
        try {
            if (server == null) {
                local.addEntries(roster)
                local.putKey(replayRoster(roster).epoch, me.did, SyncBytes.b64urlDecode(welcome.wrap ?: throw LinkedException.Unexpected()))
            }
            db.groups().addEntries(roster)
            // A joined device never had recovery words to confirm; the device that created or restored the group did.
            db.profiles().saveGroup(
                GroupRecord(welcome.group, recordUrl(welcome.store), welcome.store.i, welcome.link, recoverySaved = true),
                profile,
            )
            (channel.pipe as? LanPipe)?.remember(db, member, welcome.p)
            channel.send(Message.Done)
        } finally {
            close()
        }
    }
}

private suspend fun newSide(
    channel: Channel,
    sas: String,
    device: MemberSecrets,
    meta: DeviceMeta,
    port: Int?,
    confirm: suspend (String) -> Boolean,
    close: suspend () -> Unit,
): Welcomed {
    val p = port.takeIf { channel.pipe is LanPipe }
    channel.send(Message.Join(SyncBytes.b64urlEncode(device.sigPublic), SyncBytes.b64urlEncode(device.kemPublic), meta, p))
    if (!confirm(sas)) throw LinkedException.Declined()

    val entry = (channel.receive() as? Message.EntryMessage)?.entry ?: throw LinkedException.Unexpected()
    val me = memberSubject(device.sigPublic, device.kemPublic, Role.DEVICE)
    if (entry.op != Op.ADD || entry.add != listOf(me)) throw LinkedException.Unexpected()
    channel.send(Message.Accept(SyncBytes.b64urlEncode(sign(device.sigSeed, signingBytes(entry)))))

    val welcome = channel.receive() as? Message.Welcome ?: throw LinkedException.Unexpected()
    if (welcome.group != entry.group || welcome.store.k !in setOf("server", "webdav", "lan")) throw LinkedException.Unexpected()
    return Welcomed(channel, me, entry.signer, welcome, close)
}

/**
 * The new device's side, on a member's code: tries the LAN addresses it lists, then the store's
 * mailbox, and saves the group once accepted. [server] opens the store the welcome names, to verify
 * the roster on. [profile] joins the member's link.
 */
suspend fun join(
    db: LinkedDatabase,
    local: LocalStore,
    server: (Welcomed) -> Store?,
    mailbox: Mailbox?,
    code: Code.Member,
    device: MemberSecrets,
    meta: DeviceMeta,
    profile: String,
    port: Int? = null,
    confirm: suspend (String) -> Boolean,
): Welcomed {
    val pipe =
        dial(code.lan) ?: mailbox?.let { MailboxPipe(it, SyncBytes.toHex(pairingSlot(code.token)), shower = false) }
            ?: throw LinkedException.LanUnreachable()
    val close: suspend () -> Unit = { (pipe as? LanPipe)?.close() }
    val welcomed =
        try {
            val (channel, sas) = Channel.scanner(pipe, code)
            newSide(channel, sas, device, meta, port, confirm, close).takeIf { it.storeUrl == code.storeUrl }
                ?: throw LinkedException.Unexpected()
        } catch (e: Exception) {
            close()
            throw e
        }
    welcomed.finish(db, local, server(welcomed), profile)
    return welcomed
}

/** The new device's side, on the code it shows: waits on the LAN for a member to scan it. */
suspend fun answerShown(
    offer: Offer,
    device: MemberSecrets,
    meta: DeviceMeta,
    port: Int? = null,
    confirm: suspend (String) -> Boolean,
): Welcomed {
    val listening = offer.listening ?: throw LinkedException.LanUnreachable()
    try {
        val (pipe, key) = listening.accept()
        val (channel, sas) = Channel.shower(pipe, offer, key)
        return newSide(channel, sas, device, meta, port, confirm) {
            pipe.awaitClosed()
            listening.stop()
        }
    } catch (e: Exception) {
        listening.stop()
        throw e
    }
}
