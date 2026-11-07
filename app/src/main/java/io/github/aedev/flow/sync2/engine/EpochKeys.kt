package io.github.aedev.flow.sync2.engine

import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.checkEpochKey
import io.github.aedev.flow.sync2.crypto.openChain
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.store.Store
import io.github.aedev.flow.sync2.store.kcName
import java.util.TreeMap

/** The epoch keys held so far, and older ones walked down the history chain from the oldest of them. */
internal class EpochKeys(
    private val store: Store,
    private val group: ByteArray,
    /** The `kc` of each epoch on the chain. */
    private val commitments: Map<Int, ByteArray>,
) {
    private val keys = TreeMap<Int, ByteArray>()

    operator fun set(
        epoch: Int,
        key: ByteArray,
    ) {
        keys[epoch] = key
    }

    fun held(epoch: Int): ByteArray = keys.getValue(epoch)

    suspend fun key(epoch: Int): ByteArray {
        while (epoch !in keys) {
            val (known, key) = keys.firstEntry() ?: reject(ProtocolError.KEY_COMMITMENT)
            if (known <= epoch) reject(ProtocolError.KEY_COMMITMENT)
            val knownCommitment = commitments[known] ?: reject(ProtocolError.KEY_COMMITMENT)
            val kc = kcName(knownCommitment)
            val chain = store.key(known, kc, "chain") ?: reject(ProtocolError.KEY_COMMITMENT)
            val below = commitments[known - 1] ?: reject(ProtocolError.KEY_COMMITMENT)
            keys[known - 1] =
                forgettingIfBad(known, kc, "chain") {
                    openChain(key, group, known, chain).also { checkEpochKey(it, group, known - 1, below) }
                }
        }
        return keys.getValue(epoch)
    }

    /** A wrap or chain box that fails its checks isn't kept: it may be fine next time the store is asked. */
    suspend fun <T> forgettingIfBad(
        epoch: Int,
        kc: String,
        name: String,
        open: () -> T,
    ): T =
        try {
            open()
        } catch (e: ProtocolException) {
            store.forgetKey(epoch, kc, name)
            throw e
        }
}
