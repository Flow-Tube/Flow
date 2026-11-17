package io.github.aedev.flow.utils.potoken

import android.util.Log
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.player.error.PlayerDiagnostics
import io.github.aedev.flow.player.stream.InFlightRequestCoalescer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single source of truth for the WEB BotGuard PoToken session used by the native video
 * stream extractor.
 */
object WebPoTokenSession {
    private const val TAG = "WebPoTokenSession"

    /** Refusals tolerated before the identity is replaced; one can be a cold attestation. */
    private const val REFUSALS_BEFORE_ROTATION = 2

    /** A rotation rebuilds the BotGuard WebView on the main thread, so it is rate limited. */
    private const val ROTATION_COOLDOWN_MS = 5 * 60 * 1000L

    /**
     * A wall reported this soon after a re-roll comes from URLs the replaced visitor minted, which
     * are still playing out; it says nothing about the new one.
     */
    private const val WALL_REROLL_SETTLE_MS = 10 * 60 * 1000L

    private val generator = PoTokenGenerator
    private val rerollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var identityStore: VisitorIdentityStore? = null

    @Volatile
    private var lastWallRerollMs = 0L

    // The generation a /player wall re-roll produced, so its own wall is not answered with another.
    @Volatile
    private var playerWallRerollGeneration = -1
    private val visitorMutex = Mutex()
    private val rotationMutex = Mutex()

    @Volatile
    private var consecutiveLowTrustMints = 0

    // Never cleared by a good mint: a re-attestation after a refusal usually does return a healthy
    // token, so a shared counter let a refused-token loop reset itself every pass and rotate nothing.
    @Volatile
    private var tokenRejections = 0

    @Volatile
    private var forceReattestNextMint = false

    @Volatile
    private var lastRotationMs = 0L

    /** Only a server refusal justifies replacing the identity; token length is a diagnostic. */
    private val attestationStuck: Boolean
        get() = tokenRejections >= REFUSALS_BEFORE_ROTATION

    /** Bumped on every identity replacement, so callers can drop verdicts about the old one. */
    @Volatile
    var identityGeneration: Int = 0
        private set

    suspend fun sessionVisitorData(): String? {
        if (attestationStuck) rotateVisitorIdentity()
        YouTube.visitorData?.takeIf { it.isNotBlank() }?.let { return it }
        return visitorMutex.withLock {
            YouTube.visitorData?.takeIf { it.isNotBlank() }?.let { return it }
            val fetched = YouTube.visitorData().getOrNull()?.takeIf { it.isNotBlank() }
            if (fetched != null) {
                YouTube.visitorData = fetched
                Log.d(TAG, "Fetched session visitorData for WEB PoToken")
            } else {
                Log.w(TAG, "Could not obtain visitorData for WEB PoToken")
            }
            fetched
        }
    }

    /**
     * Mint the player + streaming PoToken pair for [videoId], bound to the session visitorData.
     * Returns null if a visitorData is unavailable or the WebView/BotGuard path is unusable

     */
    suspend fun mint(videoId: String): PoTokenResult? {
        val vd = sessionVisitorData() ?: return null
        return mintForVisitorData(videoId, vd)
    }

    private val mintCoalescer =
        InFlightRequestCoalescer<String, PoTokenResult?>(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

    // Mint with a bounded wait for the fast extraction path.
    suspend fun mintBounded(
        videoId: String,
        maxWaitMs: Long = 10_000L,
    ): PoTokenResult? {
        return withTimeoutOrNull(maxWaitMs) {
            mintCoalescer.run(videoId) {
                val vd = sessionVisitorData() ?: return@run null
                try {
                    generator
                        .getWebClientPoTokenSuspend(videoId, vd, consumeReattestationRequest())
                        .also { noteMintTrust(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Bounded PoToken mint failed for $videoId: ${e.message}")
                    null
                }
            }
        }
    }

    /** Mint against the exact visitor identity carried by the corresponding player response. */
    suspend fun mintForVisitorData(
        videoId: String,
        visitorData: String,
    ): PoTokenResult? = mintForVisitorData(videoId, visitorData, forceRefresh = false)

    /** Re-run attestation and replace the cached streaming token after a protection boundary. */
    suspend fun refreshForVisitorData(
        videoId: String,
        visitorData: String,
    ): PoTokenResult? = mintForVisitorData(videoId, visitorData, forceRefresh = true)

    private suspend fun mintForVisitorData(
        videoId: String,
        visitorData: String,
        forceRefresh: Boolean,
    ): PoTokenResult? {
        if (visitorData.isBlank()) return null
        return withTimeoutOrNull(90_000L) {
            try {
                generator
                    .getWebClientPoTokenSuspend(videoId, visitorData, forceRefresh || consumeReattestationRequest())
                    .also { noteMintTrust(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "PoToken mint failed for $videoId: ${e.message}")
                null
            }
        }
    }

    /**
     * GVS took the token and refused it. The next mint re-attests rather than handing back the one
     * the server has already rejected, and a second refusal retires the identity.
     */
    fun reportTokenRejected() {
        forceReattestNextMint = true
        tokenRejections++
        Log.w(TAG, "GVS refused the PO Token ($tokenRejections/$REFUSALS_BEFORE_ROTATION before rotating)")
    }

    private fun consumeReattestationRequest(): Boolean {
        if (!forceReattestNextMint) return false
        forceReattestNextMint = false
        return true
    }

    /**
     * Never rotates here: the caller sends this token alongside the visitorData it was minted for,
     * so swapping the identity underneath it would produce a mismatched pair.
     */
    private fun noteMintTrust(result: PoTokenResult?) {
        if (result == null) return
        consecutiveLowTrustMints =
            if (generator.lastStreamingTokenWasLowTrust) {
                val count = consecutiveLowTrustMints + 1
                Log.w(TAG, "Cold streaming token under the current visitor (mint #$count) — diagnostic only")
                count
            } else {
                0
            }
    }

    /**
     * Replaces the visitor identity the BotGuard session is graded against. Re-attesting alone
     * cannot lift a stuck verdict: the challenge re-runs under the same visitor ID, which is the
     * thing GVS has already judged.
     */
    suspend fun rotateVisitorIdentity() {
        rotationMutex.withLock {
            // Another caller may have rotated while this one waited for the lock.
            if (!attestationStuck) return@withLock
            val sinceLast = System.currentTimeMillis() - lastRotationMs
            if (lastRotationMs != 0L && sinceLast < ROTATION_COOLDOWN_MS) {
                Log.w(TAG, "Attestation still refused, but the identity was rotated ${sinceLast}ms ago — not rotating again")
                tokenRejections = 0
                return@withLock
            }
            Log.w(TAG, "Attestation refused $tokenRejections times (cold mints=$consecutiveLowTrustMints) — rotating the visitor identity")
            lastRotationMs = System.currentTimeMillis()
            consecutiveLowTrustMints = 0
            tokenRejections = 0
            generator.resetSession()
            YouTube.visitorData = null
            identityGeneration++
        }
    }

    fun bindIdentityStore(store: VisitorIdentityStore) {
        identityStore = store
    }

    /**
     * GVS walled this visitor on an app client: its media is refused a minute in on every video,
     * through any network, and a fresh visitor is served again (#921). The replacement is fetched
     * before the old one is dropped, so no request goes out without a visitor, and the store keeps
     * it across restarts. Budgeted by [VisitorIdentityStore.tryTakeReroll].
     */
    fun rerollAfterWall(client: String) {
        rerollScope.launch {
            rotationMutex.withLock {
                val now = System.currentTimeMillis()
                if (now - lastWallRerollMs < WALL_REROLL_SETTLE_MS) return@withLock
                val store = identityStore ?: return@withLock
                val takenAt =
                    store.tryTakeReroll(now) ?: run {
                        logWall("$client walled this visitor, but the re-roll budget is spent")
                        return@withLock
                    }
                val fresh = YouTube.visitorData().getOrNull()?.takeIf { it.isNotBlank() }
                if (fresh == null) {
                    store.refundReroll(takenAt)
                    logWall("$client walled this visitor; no fresh visitor could be fetched")
                    return@withLock
                }
                lastWallRerollMs = now
                generator.resetSession()
                consecutiveLowTrustMints = 0
                tokenRejections = 0
                YouTube.visitorData = fresh
                identityGeneration++
                logWall("$client walled this visitor; re-rolled the visitor identity")
            }
        }
    }

    /**
     * Every client was bot-walled at /player, which issues no stream URLs, so no refusal ever
     * reaches [rerollAfterWall] (#1354). Replaces the visitor that [walledGeneration] names, once:
     * a fresh visitor walled again points at the network, not the visitor. The first re-roll on a
     * network the visitor was not minted on skips the shared budget, since carrying a walled
     * visitor onto a new network is the case this exists for.
     */
    suspend fun rerollAfterPlayerWall(walledGeneration: Int): PlayerWallReroll {
        rotationMutex.withLock {
            if (identityGeneration != walledGeneration) return PlayerWallReroll.REROLLED
            if (playerWallRerollGeneration == identityGeneration) {
                logWall("every client walled a fresh visitor at /player too")
                return PlayerWallReroll.FRESH_VISITOR_WALLED
            }
            val store = identityStore ?: return PlayerWallReroll.UNAVAILABLE
            val now = System.currentTimeMillis()
            val newNetwork = store.mintedOnOtherNetwork()
            val takenAt =
                if (newNetwork) {
                    null
                } else {
                    store.tryTakeReroll(now) ?: run {
                        logWall("every client walled this visitor at /player, but the re-roll budget is spent")
                        return PlayerWallReroll.UNAVAILABLE
                    }
                }
            val fresh = YouTube.visitorData().getOrNull()?.takeIf { it.isNotBlank() }
            if (fresh == null) {
                takenAt?.let(store::refundReroll)
                logWall("every client walled this visitor at /player; no fresh visitor could be fetched")
                return PlayerWallReroll.UNAVAILABLE
            }
            lastWallRerollMs = now
            generator.resetSession()
            consecutiveLowTrustMints = 0
            tokenRejections = 0
            YouTube.visitorData = fresh
            identityGeneration++
            playerWallRerollGeneration = identityGeneration
            logWall("every client walled this visitor at /player; re-rolled it (new network=$newNetwork)")
            return PlayerWallReroll.REROLLED
        }
    }

    /**
     * The visitor's age, generation and whether it was minted on another network, so a wall report
     * can tell a stuck visitor from a flagged network. Never the visitor itself.
     */
    fun identitySummary(): String {
        val store = identityStore
        val ageMinutes = store?.visitorAgeMs()?.let { it / 60_000L }
        return "visitorAge=${ageMinutes?.let { "${it}m" } ?: "unknown"} generation=$identityGeneration " +
            "mintedOnOtherNetwork=${store?.mintedOnOtherNetwork()}"
    }

    private fun logWall(message: String) {
        Log.w(TAG, message)
        PlayerDiagnostics.logWarning(TAG, message)
    }

    /** Unconditional reset, for the user-facing "Reset YouTube session" action. */
    suspend fun resetIdentity() {
        rotationMutex.withLock {
            consecutiveLowTrustMints = 0
            tokenRejections = 0
            generator.resetSession()
            YouTube.visitorData = null
            identityGeneration++
        }
    }

    // Pre-warm the BotGuard session at app start so the first real extraction is fast.
    suspend fun prewarm() {
        try {
            val visitorData = sessionVisitorData() ?: return
            withContext(Dispatchers.IO) {
                generator.prewarmWebClient(visitorData)
            }
        } catch (e: Exception) {
            Log.w(TAG, "prewarm failed: ${e.message}")
        }
    }
}

/** What [WebPoTokenSession.rerollAfterPlayerWall] did about a wall on every client. */
enum class PlayerWallReroll {
    /** A fresh visitor is in place: walk the ladder again. */
    REROLLED,

    /** The visitor was already fresh from a wall like this one, so the network is the likelier cause. */
    FRESH_VISITOR_WALLED,

    /** No re-roll was possible: no store, the budget is spent, or no visitor could be fetched. */
    UNAVAILABLE,
}
