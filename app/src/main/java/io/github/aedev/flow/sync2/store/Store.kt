package io.github.aedev.flow.sync2.store

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.RosterState
import io.github.aedev.flow.sync2.roster.applyEntry
import kotlinx.serialization.Serializable
import java.io.IOException

/** What the sync engine needs from a store: the Flow server, plain file stores through [FileLayout], and what a device holds. */
interface Store {
    suspend fun rosterAfter(after: Long): List<Entry>

    suspend fun appendRoster(
        head: String,
        entry: Entry,
        wraps: Map<String, String>,
        chain: String?,
    ): Appended

    suspend fun key(
        epoch: Int,
        name: String,
    ): ByteArray?

    suspend fun putObject(
        path: String,
        bytes: ByteArray,
    )

    suspend fun getObject(path: String): ByteArray?

    /** Deletes an object; one that is already gone counts as deleted. */
    suspend fun deleteObject(path: String)
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

/** The epoch an appended [entry]'s wraps belong to: the one it starts, or the current one on [chain]. */
internal fun epochOf(
    entry: Entry,
    chain: List<Entry>,
): Int = entry.epoch?.n ?: chain.fold(null as RosterState?) { state, e -> applyEntry(state, e) }?.epoch ?: 0

/**
 * The wraps and chain boxes a store holds for [me] along [chain]: a chain box for every epoch after
 * genesis, and [me]'s wrap only for the epochs since it joined.
 */
internal fun keyNames(
    chain: List<Entry>,
    me: String,
): List<Pair<Int, String>> {
    var epoch = 0
    val names = ArrayList<Pair<Int, String>>()
    for (entry in chain) {
        val started = entry.epoch
        if (started != null) {
            epoch = started.n
            if (epoch > 0) names += epoch to "chain"
            if (me in started.to) names += epoch to me
        } else if (entry.add.orEmpty().any { it.did == me }) {
            names += epoch to me
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
}
