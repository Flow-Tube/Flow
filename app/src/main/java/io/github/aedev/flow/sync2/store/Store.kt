package io.github.aedev.flow.sync2.store

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.EpochField
import kotlinx.serialization.Serializable
import java.io.IOException

/** What the sync engine needs from a store: the Flow server, plain file stores through [FileLayout], and what a device holds. */
interface Store {
    suspend fun rosterAfter(after: Long): List<Entry>

    /** Every roster entry the store shows, fork candidates included. */
    suspend fun rosterView(): List<Entry> = rosterAfter(-1)

    /** Whether the store orders appends, so it has no honest way to change an entry it answered with. */
    val linear: Boolean get() = true

    /** Whether this is what the device itself holds, rather than a store's answer. */
    val heldHere: Boolean get() = false

    /** The entries this device chose between sides of a contested roster, for a store that picks a winner itself. */
    fun usePins(pins: Set<String>) = Unit

    suspend fun appendRoster(
        head: String,
        entry: Entry,
        wraps: Map<String, String>,
        chain: String?,
    ): Appended

    /** A wrap or chain box: [kc] is the `<kc:16>` of its path. */
    suspend fun key(
        epoch: Int,
        kc: String,
        name: String,
    ): ByteArray?

    suspend fun putObject(
        path: String,
        bytes: ByteArray,
    )

    suspend fun getObject(path: String): ByteArray?

    /** Deletes an object; one that is already gone counts as deleted. */
    suspend fun deleteObject(path: String)

    /** Lets go of a held wrap or chain box that failed its checks, so the next sync asks for it again. */
    suspend fun forgetKey(
        epoch: Int,
        kc: String,
        name: String,
    ) = Unit
}

/** A group's shared store: a Flow server, or files through [FileLayout]. Both carry the pairing mailbox. */
interface Remote :
    Store,
    Mailbox

/** A store that lists what arrived after a cursor: what a device holds, and the Flow server. File stores keep no such list. */
interface ChangeList : Store {
    /** Objects that arrived after [cursor], and the cursor to pass next time. */
    suspend fun changes(cursor: Long): Pair<List<ChangedObject>, Long>
}

/** Where a wrap or chain box lives under `keys/`: its epoch, its `<kc:16>`, and a member's id or `chain`. */
data class KeyName(
    val epoch: Int,
    val kc: String,
    val name: String,
)

/** An epoch's `<kc:16>`, from the `kc` an entry names. */
internal fun kcNameOf(field: EpochField): String = kcName(wrapBytes(field.kc))

/** The epoch and `<kc:16>` an appended [entry]'s wraps belong to: the epoch it starts, or the one in force on [chain]. */
internal fun wrapsEpoch(
    entry: Entry,
    chain: List<Entry>,
): Pair<Int, String> {
    val field = entry.epoch ?: chain.lastOrNull { it.epoch != null }?.epoch ?: reject(ProtocolError.ROSTER_CHAIN)
    return field.n to kcNameOf(field)
}

/**
 * The wraps and chain boxes a store holds for [me] along [chain] and for the [standing] entries that lost: a chain box for
 * every epoch after genesis, and [me]'s wrap only for the epochs since it joined.
 */
internal fun keyNames(
    chain: List<Entry>,
    standing: List<Entry>,
    me: String,
): List<KeyName> {
    val names = ArrayList<KeyName>()
    for ((index, entry) in (chain + standing).withIndex()) {
        val started = entry.epoch
        if (started != null) {
            val kc = kcNameOf(started)
            if (started.n > 0) names += KeyName(started.n, kc, "chain")
            if (me in started.to) names += KeyName(started.n, kc, me)
        } else if (entry.add.orEmpty().any { it.did == me }) {
            val (epoch, kc) = wrapsEpoch(entry, chain.take(index))
            names += KeyName(epoch, kc, me)
        }
    }
    return names
}

/** A wrap or chain box as an append carries it, in base64url. */
internal fun wrapBytes(b64: String): ByteArray =
    runCatching { SyncBytes.b64urlDecode(b64) }.getOrNull() ?: reject(ProtocolError.ROSTER_FORMAT)

/** Whether an append landed, or lost to another device's append at the same head. */
sealed interface Appended {
    data object Done : Appended

    data class Conflict(
        val head: String,
    ) : Appended
}

/** A device asking to join with the admin token, by the name it gave. */
@Serializable
data class JoinRequest(
    val id: String,
    val name: String,
    val expires: Long,
)

@Serializable
data class ChangedObject(
    val path: String,
    val size: Long,
)

sealed class StoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class Insecure : StoreException("The server must use HTTPS unless it is on a private network")

    class Network(
        cause: IOException,
    ) : StoreException("Could not reach the server", cause)

    class Refused(
        val status: Int,
        val code: String,
    ) : StoreException("The server refused the request: $code ($status)") {
        val rateLimited: Boolean get() = status == 429
    }

    /** The Flow server refused the admin token. */
    class AdminRefused : StoreException("The server refused the admin token")

    /** The Flow server blocks admin requests from this address for a while after repeated wrong tokens. */
    class AdminBlocked : StoreException("The server is blocking admin requests for now")

    /** The Flow server issues this device no token: it is no longer in the group, and can't read the roster to see that. */
    class SignInRefused : StoreException("The server refused this device's sign-in")

    /** A file store refused the user name or password; nothing more is sent with them. */
    class LoginRefused : StoreException("The store refused the user name or password")

    class InstanceChanged : StoreException("This is not the server instance this device paired with")

    class Malformed : StoreException("The server sent an invalid response")

    /** An answer past the size it may have (spec/store.md, Untrusted input). */
    class TooLarge : StoreException("The server sent more than an answer may hold")

    /** Whether this read failed for the one item asked for, not because the store is out of reach, wants a login or asks for time (spec/store.md, Untrusted input). */
    val stopsOnlyItsItem: Boolean
        get() =
            when (this) {
                is TooLarge -> true
                is Refused -> status in 400..499 && status !in NOT_ABOUT_THE_ITEM
                else -> false
            }
}

/** 4xx answers that say something about the store or this device rather than the item asked for. */
private val NOT_ABOUT_THE_ITEM = setOf(401, 407, 408, 429)

/** What [read] answers, or null when it failed for that one item. */
internal suspend fun <T> oneItem(read: suspend () -> T?): T? =
    try {
        read()
    } catch (e: StoreException) {
        if (e.stopsOnlyItsItem) null else throw e
    }
