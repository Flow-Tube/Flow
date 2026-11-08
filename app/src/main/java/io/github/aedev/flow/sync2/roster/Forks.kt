package io.github.aedev.flow.sync2.roster

import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.reject

const val MAX_ROSTER_ENTRIES = 4096

/** The chain the rule picks, or the entries it can't decide between. */
data class ForkResolution(
    val chain: List<Entry> = emptyList(),
    val contested: List<Entry> = emptyList(),
    /** The contested entries that revoke another contested entry: what the user chooses between. */
    val sides: List<Entry> = emptyList(),
    /** The entries off the chain that stand and start an epoch: their keys still open objects. */
    val standing: List<Entry> = emptyList(),
)

/** What a device does with a store's view of the roster. */
sealed interface Settlement {
    data class Apply(
        val chain: List<Entry>,
        val standing: List<Entry>,
        val extendsApplied: Boolean,
    ) : Settlement

    data class Contested(
        val contested: List<Entry>,
        val sides: List<Entry>,
    ) : Settlement

    data object Rollback : Settlement

    data object Rewritten : Settlement
}

private enum class Status { STANDS, FALLS }

private class Node(
    val entry: Entry,
    val hash: String,
    val state: RosterState,
    val parent: Node?,
) {
    val children = ArrayList<Node>()
    val revokes = ArrayList<Node>()
    var waiting = 0
    var enter = 0
    var exit = 0
    var status: Status? = null

    fun mayStand() = waiting == 0 && (parent == null || parent.status == Status.STANDS)
}

/**
 * The entries that count (spec/protocol.md, Forks): each valid against the chain from genesis to its own `prev`.
 * Entries go in with lower `seq` first. A null [group] takes any group, and [genesis] pins the only genesis that counts.
 */
internal class ValidEntries(
    private val group: String?,
    private val genesis: String?,
) {
    private val states = HashMap<String, RosterState>()

    /** The state after [entry], or null when it doesn't count or is already in. */
    fun admit(entry: Entry): RosterState? {
        val hash = entryHash(entry)
        if (hash in states || (group != null && entry.group != group)) return null
        if (entry.op == Op.GENESIS && genesis != null && hash != genesis) return null
        val parent = if (entry.op == Op.GENESIS) null else states[entry.prev] ?: return null
        val state =
            try {
                applyEntry(parent, entry)
            } catch (_: ProtocolException) {
                return null
            }
        states[hash] = state
        return state
    }
}

/** Every held entry that is valid against its parent, as a tree. */
private fun validTree(
    group: String,
    held: List<Entry>,
    genesis: String?,
): List<Node> {
    val valid = ValidEntries(group, genesis)
    val nodes = LinkedHashMap<String, Node>()
    for (entry in held.sortedBy { it.seq }) {
        val state = valid.admit(entry) ?: continue
        val hash = entryHash(entry)
        val parent = nodes[entry.prev].takeIf { entry.op != Op.GENESIS }
        val node = Node(entry, hash, state, parent)
        nodes[hash] = node
        parent?.children?.add(node)
        if (nodes.size > MAX_ROSTER_ENTRIES) reject(ProtocolError.ROSTER_CHAIN)
    }
    return nodes.values.toList()
}

/** Numbers the tree so that an ancestor check takes constant time. */
private fun numberTree(nodes: List<Node>) {
    var clock = 0
    for (root in nodes.filter { it.parent == null }) {
        root.enter = clock++
        val stack = ArrayDeque(listOf(root to root.children.iterator()))
        while (stack.isNotEmpty()) {
            val (node, children) = stack.last()
            if (children.hasNext()) {
                val child = children.next()
                child.enter = clock++
                stack.addLast(child to child.children.iterator())
            } else {
                node.exit = clock++
                stack.removeLast()
            }
        }
    }
}

private fun linkRevocations(
    nodes: List<Node>,
    pins: Set<String>,
) {
    val bySigner = nodes.groupBy { it.entry.signer }
    for (y in nodes) {
        for (did in y.entry.remove.orEmpty()) {
            for (x in bySigner[did].orEmpty()) {
                val isAncestor = x.enter <= y.enter && y.exit <= x.exit
                if (isAncestor || x.hash in pins) continue
                y.revokes += x
                x.waiting++
            }
        }
    }
}

/** Decides what it can: whatever is left has no status, and is contested. */
private fun decide(nodes: List<Node>) {
    val queue = ArrayDeque<Node>()

    fun settle(
        node: Node,
        status: Status,
    ) {
        if (node.status == null) {
            node.status = status
            queue.addLast(node)
        }
    }
    nodes.filter { it.mayStand() }.forEach { settle(it, Status.STANDS) }
    while (queue.isNotEmpty()) {
        val node = queue.removeLast()
        for (child in node.children) {
            if (node.status == Status.FALLS) {
                settle(child, Status.FALLS)
            } else if (child.mayStand()) {
                settle(child, Status.STANDS)
            }
        }
        for (revoked in node.revokes) {
            if (node.status == Status.STANDS) {
                settle(revoked, Status.FALLS)
            } else {
                revoked.waiting--
                if (revoked.mayStand()) settle(revoked, Status.STANDS)
            }
        }
    }
}

private fun lowestGenesisGroup(held: List<Entry>): String? =
    held
        .filter { it.op == Op.GENESIS && runCatching { applyEntry(null, it) }.isSuccess }
        .minByOrNull(::entryHash)
        ?.group

/**
 * The fork rule (spec/protocol.md, Forks): a function of the entries held, never of their order.
 * Without a known [group], the group of the lowest valid genesis held is taken: a device that just
 * needs to read a store it isn't yet in checks what it finds afterwards.
 */
fun resolveForks(
    group: String?,
    held: List<Entry>,
    pins: Set<String> = emptySet(),
    genesis: String? = null,
): ForkResolution {
    val id = group ?: lowestGenesisGroup(held) ?: return ForkResolution()
    val nodes = validTree(id, held, genesis)
    numberTree(nodes)
    linkRevocations(nodes, pins)
    decide(nodes)

    val open = nodes.filter { it.status == null }
    if (open.isNotEmpty()) {
        return ForkResolution(
            contested = open.map { it.entry },
            sides = open.filter { node -> node.revokes.any { it.status == null } }.map { it.entry },
        )
    }

    val chain = ArrayList<Entry>()
    var candidates = nodes.filter { it.parent == null && it.status == Status.STANDS }
    while (candidates.isNotEmpty()) {
        val winner = forkWinner(candidates.map { it.entry }) ?: break
        chain += winner
        candidates = candidates.first { it.entry === winner }.children.filter { it.status == Status.STANDS }
    }
    val onChain = chain.map(::entryHash).toSet()
    val standing = nodes.filter { it.status == Status.STANDS && it.entry.epoch != null && it.hash !in onChain }
    return ForkResolution(chain = chain, standing = standing.map { it.entry })
}

/**
 * What to do with a store's [view], given the chain this device has [applied]. [linear] is a Flow
 * server, which has no honest way to change an entry it already answered with.
 */
fun settleRoster(
    group: String,
    applied: List<Entry>,
    view: List<Entry>,
    pins: Set<String> = emptySet(),
    linear: Boolean = false,
): Settlement {
    val shown = view.map(::entryHash).toSet()
    if (applied.any { entryHash(it) !in shown }) return Settlement.Rollback
    val resolved = resolveForks(group, view, pins, applied.firstOrNull()?.let(::entryHash))
    if (resolved.contested.isNotEmpty()) return Settlement.Contested(resolved.contested, resolved.sides)
    if (resolved.chain.isEmpty()) reject(ProtocolError.ROSTER_CHAIN)
    val extendsApplied = applied.map(::entryHash) == resolved.chain.take(applied.size).map(::entryHash)
    if (!extendsApplied && linear) return Settlement.Rewritten
    return Settlement.Apply(resolved.chain, resolved.standing, extendsApplied)
}

/** The chain the rule picks from what a store shows, or nothing while the roster is contested. */
fun winningChain(
    held: List<Entry>,
    pins: Set<String> = emptySet(),
): List<Entry> = resolveForks(null, held, pins).chain
