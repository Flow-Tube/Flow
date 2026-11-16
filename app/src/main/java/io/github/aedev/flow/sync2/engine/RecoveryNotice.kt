package io.github.aedev.flow.sync2.engine

import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.entryHash

private fun changedBy(entry: Entry): String? =
    when {
        entry.op == Op.RESTORE -> entry.add?.firstOrNull()?.did
        entry.op == Op.RECOVERY || (entry.op == Op.REMOVE && entry.add != null) -> entry.signer
        else -> null
    }

/**
 * The device behind the latest change of the recovery key in [chain] that [applied] didn't have, unless it was [me]
 * (spec/protocol.md, Recovery): the words the user holds may have just stopped working. For a restore, the device it
 * added. A device with nothing applied is only catching up.
 */
internal fun recoveryChange(
    me: String,
    applied: List<Entry>,
    chain: List<Entry>,
): String? {
    if (applied.isEmpty()) return null
    val known = applied.map(::entryHash).toSet()
    return chain
        .filter { entryHash(it) !in known }
        .mapNotNull(::changedBy)
        .lastOrNull()
        ?.takeIf { it != me }
}
