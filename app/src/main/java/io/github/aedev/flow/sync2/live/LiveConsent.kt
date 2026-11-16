package io.github.aedev.flow.sync2.live

import io.github.aedev.flow.sync2.data.ConsentRow
import io.github.aedev.flow.sync2.data.LinkedDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** An unanswered question is declined after this long, without being remembered. */
private const val ASK_MS = 60_000L

/** What the user answers when a device first asks to play here or control playback (spec/live.md, Consent). */
enum class ConsentAnswer { ALWAYS, ONCE, DECLINE }

/** A device asking, while the user decides. */
class ConsentRequest(
    val did: String,
    internal val answer: CompletableDeferred<ConsentAnswer>,
)

/** Whether each device may play here and control playback, remembered per device and changeable under Linked devices. */
@Singleton
class LiveConsent
    @Inject
    constructor(
        private val db: LinkedDatabase,
    ) {
        private val asking = MutableStateFlow<ConsentRequest?>(null)

        /** The question on screen, if any. */
        val request: StateFlow<ConsentRequest?> = asking.asStateFlow()

        /** The remembered answers: true allows always, false declines. */
        val remembered: Flow<Map<String, Boolean>> = db.live().observeConsent().map { rows -> rows.associate { it.did to it.allowed } }

        /** Whether [did] may go ahead, asking the user the first time; one question at a time. */
        suspend fun allows(did: String): Boolean {
            db.live().consent(did)?.let { return it }
            val request = ConsentRequest(did, CompletableDeferred())
            if (!asking.compareAndSet(null, request)) return false
            val answer =
                try {
                    withTimeoutOrNull(ASK_MS) { request.answer.await() }
                } finally {
                    asking.compareAndSet(request, null)
                }
            when (answer) {
                ConsentAnswer.ALWAYS -> remember(did, true)
                ConsentAnswer.DECLINE -> remember(did, false)
                else -> Unit
            }
            return answer == ConsentAnswer.ALWAYS || answer == ConsentAnswer.ONCE
        }

        fun answer(
            request: ConsentRequest,
            answer: ConsentAnswer,
        ) {
            request.answer.complete(answer)
        }

        suspend fun remember(
            did: String,
            allowed: Boolean,
        ) = db.live().setConsent(ConsentRow(did, allowed))

        /** Asks again next time. */
        suspend fun forget(did: String) = db.live().clearConsent(did)
    }
