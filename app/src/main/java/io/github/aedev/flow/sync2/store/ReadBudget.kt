package io.github.aedev.flow.sync2.store

private const val REQUESTS = 4096
private const val OBJECTS = 200_000

/**
 * How much one read may take from a store (spec/store.md, Make progress or stop). A cursor that keeps advancing
 * passes that rule, so a total has to end the read too. Both limits sit far above what a group holds, since garbage
 * collection keeps its logs short.
 */
class ReadBudget(
    private var requests: Int = REQUESTS,
    private var objects: Int = OBJECTS,
) {
    /** Counts one answer naming [named] objects; false once the read should end. */
    fun take(named: Int): Boolean {
        requests--
        objects -= named
        return requests > 0 && objects > 0
    }
}
