package io.github.aedev.flow.sync2.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** Where a member listened at its last LAN session, to reach it where multicast doesn't (spec/live.md, Discovery). */
@Entity(tableName = "lan_address")
data class LanAddressRow(
    @PrimaryKey val did: String,
    val host: String,
    val port: Int,
)

@Dao
interface LanDao {
    @Query("SELECT * FROM lan_address")
    suspend fun addresses(): List<LanAddressRow>

    /** The members last seen listening at [host] and [port]. */
    @Query("SELECT did FROM lan_address WHERE host = :host AND port = :port")
    suspend fun at(
        host: String,
        port: Int,
    ): List<String>

    @Upsert
    suspend fun save(row: LanAddressRow)

    /** Forgets the members that left or were removed. */
    @Query("DELETE FROM lan_address WHERE did NOT IN (:members)")
    suspend fun keepOnly(members: List<String>)
}
