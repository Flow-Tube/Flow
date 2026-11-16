package io.github.aedev.flow.sync2.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Live counters (spec/live.md, Live messages): this device's own, which never repeats, and the
 * highest accepted from each other device, so a replayed message is refused even after a restart.
 */
@Entity(tableName = "live_counter")
data class LiveCounterRow(
    @PrimaryKey val did: String,
    val seq: Long,
)

/** What the user answered when a device first asked to play here or control playback (spec/live.md, Consent). */
@Entity(tableName = "consent")
data class ConsentRow(
    @PrimaryKey val did: String,
    val allowed: Boolean,
)

@Dao
interface LiveDao {
    @Query("SELECT seq FROM live_counter WHERE did = :did")
    suspend fun counter(did: String): Long?

    @Upsert
    suspend fun setCounter(row: LiveCounterRow)

    @Query("SELECT allowed FROM consent WHERE did = :did")
    suspend fun consent(did: String): Boolean?

    @Query("SELECT * FROM consent")
    fun observeConsent(): Flow<List<ConsentRow>>

    @Upsert
    suspend fun setConsent(row: ConsentRow)

    @Query("DELETE FROM consent WHERE did = :did")
    suspend fun clearConsent(did: String)
}
