package io.github.aedev.flow.sync2.data

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/** The hash of a link's record as last uploaded or received, so a device sends only what changed. */
@Entity(tableName = "sent", primaryKeys = ["link", "collection", "recordKey"])
data class SentRecord(
    @ColumnInfo(defaultValue = "") val link: String,
    val collection: String,
    val recordKey: String,
    val hash: String,
    val sentAt: Long,
)

/** A collection's latest upload in the ledger. */
data class LastSent(
    val collection: String,
    val sentAt: Long,
)

@Dao
interface SentDao {
    @Query("SELECT * FROM sent WHERE link = :link AND collection = :collection")
    suspend fun forCollection(
        link: String,
        collection: String,
    ): List<SentRecord>

    @Query("SELECT collection, MAX(sentAt) AS sentAt FROM sent WHERE link = :link GROUP BY collection")
    suspend fun lastSent(link: String): List<LastSent>

    @Upsert
    suspend fun upsert(records: List<SentRecord>)
}

/** Linked-devices state, kept apart from the app's database, one copy per device. */
@Database(
    entities = [
        SentRecord::class,
        GroupRecord::class,
        RosterRow::class,
        StreamRow::class,
        SnapshotRow::class,
        AckRow::class,
        ManifestRow::class,
        LinkRow::class,
        CollectedRow::class,
        HeldRow::class,
        LiveCounterRow::class,
        ConsentRow::class,
        LanAddressRow::class,
        ProfileLinkRow::class,
        KeptBatchRow::class,
        ProfileNameRow::class,
        LinkReadRow::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3, spec = HoldObjects::class),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6, spec = SentPerLink::class),
    ],
)
abstract class LinkedDatabase : RoomDatabase() {
    abstract fun sent(): SentDao

    abstract fun groups(): GroupDao

    abstract fun snapshots(): SnapshotDao

    abstract fun collection(): CollectionDao

    abstract fun held(): HeldDao

    abstract fun live(): LiveDao

    abstract fun lan(): LanDao

    abstract fun profiles(): LinkedProfileDao
}
