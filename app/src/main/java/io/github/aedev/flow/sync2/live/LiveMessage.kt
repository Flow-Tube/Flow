package io.github.aedev.flow.sync2.live

import io.github.aedev.flow.sync.protocol.SyncSerialization
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A live message's content (spec/live.md, Live messages). */
@Serializable
sealed interface LiveMessage {
    /** The sender is online, what it accepts (`play`, `remote`), and which icon shows it. */
    @Serializable
    @SerialName("presence")
    data class Presence(
        val can: List<String>,
        val kind: String,
    ) : LiveMessage

    /** What the sender plays; [video] is null when nothing is loaded. */
    @Serializable
    @SerialName("now")
    data class Now(
        val video: String?,
        val title: String = "",
        val by: String = "",
        val music: Boolean = false,
        val link: String? = null,
        val pos: Long = 0,
        val dur: Long = 0,
        val state: String = PAUSED,
        val vol: Int = 100,
        val queue: List<String> = emptyList(),
        val index: Int = 0,
    ) : LiveMessage

    /** Hands the sender's whole session to device [to]. */
    @Serializable
    @SerialName("play")
    data class Play(
        val to: String,
        val video: String,
        val pos: Long,
        val music: Boolean,
        val link: String?,
        val queue: List<String>,
        val index: Int,
        val state: String,
    ) : LiveMessage

    /** Controls playback on device [to]. */
    @Serializable
    @SerialName("cmd")
    data class Cmd(
        val to: String,
        val cmd: String,
        val pos: Long? = null,
        val vol: Int? = null,
        val queue: List<String>? = null,
    ) : LiveMessage

    /** The outcome of the `play` or `cmd` whose counter is [ref]. */
    @Serializable
    @SerialName("result")
    data class Result(
        val to: String,
        val ref: Long,
        val ok: Boolean,
        val reason: String? = null,
    ) : LiveMessage

    companion object {
        const val PLAYING = "playing"
        const val PAUSED = "paused"

        /** At most this many videos travel in a queue. */
        const val QUEUE_LIMIT = 50

        // Fields a newer app adds are ignored; absent optional ones read as null. Every field at its
        // default still goes out, as spec/live.md lists them: Flow Desktop before 0.1.0-beta4 dropped a
        // `now` without `state`, so a paused phone stayed "playing" there.
        private val json =
            Json {
                classDiscriminator = "type"
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
            }

        fun encode(message: LiveMessage): ByteArray =
            SyncSerialization.canonicalJson(json.encodeToJsonElement(serializer(), message)).toByteArray()

        fun decode(content: ByteArray): LiveMessage = json.decodeFromString(serializer(), content.decodeToString())
    }
}
