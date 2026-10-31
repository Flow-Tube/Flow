package io.github.aedev.flow.sync2.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Which local profile syncs under which link (spec/protocol.md, Profiles); a link has one profile at most. A link
 * linked after this device started reading the logs is [caughtUp] once it has been read from its snapshot.
 */
@Entity(tableName = "profile_links", indices = [Index(value = ["link"], unique = true)])
data class ProfileLinkRow(
    @PrimaryKey val profile: String,
    val link: String,
    val caughtUp: Boolean,
)

/**
 * A batch for a linked profile that isn't active, kept until that profile runs. [id] is the order it was read in
 * and names the file that holds it, as a batch can be larger than a database row can be read back.
 */
@Entity(tableName = "kept_batches", indices = [Index(value = ["link"])])
data class KeptBatchRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val link: String,
    val sender: String,
    val collection: String,
)

/** A record of the `profiles` collection: a link's name, merged last-writer-wins by [hlc]. */
@Entity(tableName = "profile_names")
data class ProfileNameRow(
    @PrimaryKey val link: String,
    val name: String,
    val hlc: String,
    val deleted: Boolean,
)

/**
 * Where reading [did]'s log stands for one link apart from the others: the end of the link's snapshot, or how far
 * catching it up got (spec/protocol.md, Reading a link from its snapshot).
 */
@Entity(tableName = "link_reads", primaryKeys = ["link", "did"])
data class LinkReadRow(
    val link: String,
    val did: String,
    val seq: Long,
    val hash: String,
)

/** Stands for Default's id in a link the upgrade moved, which the database alone can't know. */
const val DEFAULT_PROFILE = ""

/** The one link a group had, and what was sent under it, now belong to Default's link. */
class SentPerLink : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE sent SET link = COALESCE((SELECT link FROM group_record WHERE id = 1), '')")
        db.execSQL(
            "INSERT INTO profile_links (profile, link, caughtUp) SELECT '$DEFAULT_PROFILE', link, 1 FROM group_record WHERE id = 1 AND link != ''",
        )
    }
}

@Dao
interface LinkedProfileDao {
    @Query("SELECT * FROM profile_links")
    suspend fun links(): List<ProfileLinkRow>

    @Query("SELECT link FROM profile_links WHERE profile = :profile")
    suspend fun linkOf(profile: String): String?

    @Upsert
    suspend fun link(row: ProfileLinkRow)

    @Upsert
    suspend fun saveGroupRecord(record: GroupRecord)

    @Query("UPDATE group_record SET link = :link WHERE id = 1")
    suspend fun setGroupLink(link: String)

    /** Saves a group this device created or joined, with [profile] syncing under the group's link. */
    @Transaction
    suspend fun saveGroup(
        record: GroupRecord,
        profile: String,
    ) {
        saveGroupRecord(record)
        link(ProfileLinkRow(profile, record.link, caughtUp = true))
    }

    /** A restored device's [profile] takes the link it found (spec/protocol.md, Recovery). */
    @Transaction
    suspend fun adopt(
        profile: String,
        link: String,
    ) {
        link(ProfileLinkRow(profile, link, caughtUp = true))
        setGroupLink(link)
    }

    @Query("UPDATE profile_links SET profile = :profile WHERE profile = :from")
    suspend fun moveProfile(
        from: String,
        profile: String,
    )

    @Query("UPDATE profile_links SET caughtUp = 1 WHERE link = :link")
    suspend fun caughtUp(link: String)

    @Insert
    suspend fun keep(row: KeptBatchRow): Long

    @Query("SELECT * FROM kept_batches WHERE link = :link ORDER BY id LIMIT 1")
    suspend fun firstKept(link: String): KeptBatchRow?

    @Query("SELECT COUNT(*) FROM kept_batches WHERE link = :link")
    suspend fun keptCount(link: String): Int

    @Query("DELETE FROM kept_batches WHERE id = :id")
    suspend fun applied(id: Long)

    @Query("SELECT * FROM profile_names")
    suspend fun names(): List<ProfileNameRow>

    @Upsert
    suspend fun saveNames(rows: List<ProfileNameRow>)

    @Query("SELECT * FROM link_reads")
    suspend fun reads(): List<LinkReadRow>

    @Upsert
    suspend fun saveReads(rows: List<LinkReadRow>)

    @Query("DELETE FROM link_reads WHERE link = :link AND did = :did")
    suspend fun readDone(
        link: String,
        did: String,
    )

    /** Where a link's own reading got, and the reads that reached the shared position, in one transaction. */
    @Transaction
    suspend fun updateReads(
        rows: List<LinkReadRow>,
        done: List<LinkReadRow>,
    ) {
        saveReads(rows)
        done.forEach { readDone(it.link, it.did) }
    }
}
