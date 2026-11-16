package io.github.aedev.flow.sync2.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/** An object or blob this device holds, in the order it arrived; [cursor] is what `changes` pages by. */
@Entity(tableName = "held", indices = [Index(value = ["path"], unique = true)])
data class HeldRow(
    @PrimaryKey(autoGenerate = true) val cursor: Long = 0,
    val path: String,
    val size: Long,
)

@Dao
interface HeldDao {
    @Query("SELECT entry FROM roster WHERE seq = 0")
    suspend fun genesis(): String?

    @Query("SELECT hash FROM roster_pin")
    suspend fun rosterPins(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(row: HeldRow)

    @Query("SELECT * FROM held WHERE cursor > :after ORDER BY cursor LIMIT :limit")
    suspend fun after(
        after: Long,
        limit: Int,
    ): List<HeldRow>

    @Query("SELECT path FROM held")
    suspend fun paths(): List<String>

    @Query("DELETE FROM held WHERE path = :path")
    suspend fun remove(path: String)
}

/**
 * Sessions now read the objects this device holds, so their cursor starts over on that index; the
 * server's cursor moves to its own column.
 */
class HoldObjects : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE group_record SET serverCursor = cursor, cursor = 0")
    }
}
