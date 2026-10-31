package io.github.aedev.flow.sync2.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
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
