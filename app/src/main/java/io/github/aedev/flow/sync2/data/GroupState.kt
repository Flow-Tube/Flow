package io.github.aedev.flow.sync2.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.canonicalEntry
import io.github.aedev.flow.sync2.roster.parseEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** The [GroupRecord.storeUrl] of a group that lives on its devices alone (spec/live.md, LAN groups). */
const val LAN_STORE = "lan"

/** What a [GroupRecord.storeUrl] of a group on WebDAV starts with; the folder's URL follows. */
const val WEBDAV_STORE = "webdav:"

/** The WebDAV folder the group lives in, or null when it lives elsewhere. */
val GroupRecord.webDavUrl: String? get() = storeUrl.takeIf { it.startsWith(WEBDAV_STORE) }?.removePrefix(WEBDAV_STORE)

/** The Flow server the group lives on, or null for a group on WebDAV or on its devices alone. */
val GroupRecord.serverUrl: String? get() = storeUrl.takeIf { it != LAN_STORE && webDavUrl == null }

/** The group this device belongs to, where its store is, and how far it has written and read. */
@Entity(tableName = "group_record")
data class GroupRecord(
    val groupId: String,
    val storeUrl: String,
    val instance: String?,
    val link: String,
    val ownSeq: Long = 0,
    val ownPrev: String = "00".repeat(32),
    val cursor: Long = 0,
    /** The user proved they wrote down the recovery words this device last showed. */
    @ColumnInfo(defaultValue = "0") val recoverySaved: Boolean = false,
    /** How far this device has read the server's change list; [cursor] pages what this device holds. */
    @ColumnInfo(defaultValue = "0") val serverCursor: Long = 0,
    /** This device's own log objects up to this `seq` are on the server. */
    @ColumnInfo(defaultValue = "0") val uploaded: Long = 0,
    @PrimaryKey val id: Int = 1,
)

/** The verified roster, entry by entry, as canonical JSON. */
@Entity(tableName = "roster")
data class RosterRow(
    @PrimaryKey val seq: Long,
    val entry: String,
)

/** How far this device has read each other device's log. */
@Entity(tableName = "streams")
data class StreamRow(
    @PrimaryKey val did: String,
    val seq: Long,
    val hash: String,
)

@Dao
interface GroupDao {
    @Query("SELECT * FROM group_record WHERE id = 1")
    suspend fun group(): GroupRecord?

    @Query("SELECT * FROM group_record WHERE id = 1")
    fun observeGroup(): Flow<GroupRecord?>

    @Query("UPDATE group_record SET ownSeq = :seq, ownPrev = :prev WHERE id = 1")
    suspend fun setOwn(
        seq: Long,
        prev: String,
    )

    @Query("UPDATE group_record SET serverCursor = :cursor WHERE id = 1")
    suspend fun setServerCursor(cursor: Long)

    @Query("UPDATE group_record SET uploaded = :heldCursor WHERE id = 1")
    suspend fun setUploaded(heldCursor: Long)

    @Query("UPDATE group_record SET recoverySaved = :saved WHERE id = 1")
    suspend fun setRecoverySaved(saved: Boolean)

    @Upsert
    suspend fun saveGroup(record: GroupRecord)

    @Query("SELECT * FROM roster ORDER BY seq")
    suspend fun roster(): List<RosterRow>

    @Query("SELECT * FROM roster ORDER BY seq")
    fun observeRoster(): Flow<List<RosterRow>>

    @Upsert
    suspend fun addRoster(rows: List<RosterRow>)

    @Query("DELETE FROM roster")
    suspend fun clearRoster()

    /** Takes the fork winner's chain in place of the one this device had. */
    @Transaction
    suspend fun replaceRoster(rows: List<RosterRow>) {
        clearRoster()
        addRoster(rows)
    }

    @Query("SELECT * FROM streams")
    suspend fun streams(): List<StreamRow>

    @Upsert
    suspend fun saveStreamRows(rows: List<StreamRow>)

    @Query("UPDATE group_record SET cursor = :cursor WHERE id = 1")
    suspend fun saveCursor(cursor: Long)

    /** How far reading got, in one transaction so streams and the cursor never disagree. */
    @Transaction
    suspend fun saveStreams(
        rows: List<StreamRow>,
        cursor: Long,
    ) {
        saveStreamRows(rows)
        saveCursor(cursor)
    }
}

private fun rows(entries: List<Entry>) = entries.map { RosterRow(it.seq, canonicalEntry(it)) }

fun List<RosterRow>.toEntries(): List<Entry> = map { parseEntry(Json.parseToJsonElement(it.entry)) }

/** The verified roster's current epoch, or null while this device holds no roster. */
fun GroupDao.observeEpoch(): Flow<Int?> =
    observeRoster().map { rows -> rows.toEntries().mapNotNull { it.epoch?.n }.lastOrNull() }.distinctUntilChanged()

suspend fun GroupDao.addEntries(entries: List<Entry>) = addRoster(rows(entries))

suspend fun GroupDao.replaceRoster(entries: List<Entry>) = replaceRoster(rows(entries))
