package org.freegram.app.store

import androidx.room.Dao
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "bulletins")
data class BulletinRow(@PrimaryKey val id: String, val createdAt: Long, val wire: String)

@Entity(
    tableName = "relay_deliveries",
    primaryKeys = ["eventId", "relay"],
    foreignKeys = [ForeignKey(entity = BulletinRow::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("eventId")],
)
data class RelayDeliveryRow(val eventId: String, val relay: String, val state: String)

@Entity(tableName = "drafts")
data class DraftRow(@PrimaryKey val slot: Int = 0, val content: String)

@Entity(tableName = "author_policies")
data class AuthorPolicyRow(@PrimaryKey val pubkey: String, val state: String)

/** A stored bulletin with no unsettled relay delivery; `targets` is 0 for received posts. */
data class EvictionCandidate(val id: String, val createdAt: Long, val wire: String, val targets: Int)

@Dao
interface BulletinDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertBulletin(row: BulletinRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRelay(row: RelayDeliveryRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setRelay(row: RelayDeliveryRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setDraft(row: DraftRow)
    @Query("SELECT wire FROM bulletins ORDER BY createdAt DESC, id DESC LIMIT 100") suspend fun savedWires(): List<String>
    @Query("SELECT COUNT(*) FROM bulletins") suspend fun bulletinCount(): Int
    @Query("SELECT EXISTS(SELECT 1 FROM bulletins WHERE id = :eventId)") suspend fun hasBulletin(eventId: String): Boolean
    @Query("SELECT content FROM drafts WHERE slot = 0") suspend fun draft(): String?
    @Query("SELECT state FROM relay_deliveries WHERE eventId = :eventId AND relay = :relay") suspend fun relayState(eventId: String, relay: String): String?
    @Query("SELECT relay FROM relay_deliveries WHERE eventId = :eventId AND state != 'Accepted' ORDER BY relay") suspend fun pendingRelays(eventId: String): List<String>
    @Query("SELECT relay FROM relay_deliveries WHERE eventId = :eventId ORDER BY relay") suspend fun deliveryTargets(eventId: String): List<String>
    @Query("SELECT * FROM relay_deliveries WHERE state != 'Accepted' ORDER BY eventId, relay") suspend fun unsettledDeliveries(): List<RelayDeliveryRow>
    @Query("SELECT wire FROM bulletins WHERE id = :eventId") suspend fun wire(eventId: String): String?
    @Query(
        "SELECT b.id, b.createdAt, b.wire, (SELECT COUNT(*) FROM relay_deliveries r WHERE r.eventId = b.id) AS targets " +
            "FROM bulletins b WHERE NOT EXISTS (SELECT 1 FROM relay_deliveries r WHERE r.eventId = b.id AND r.state != 'Accepted') " +
            "ORDER BY b.createdAt, b.id"
    ) suspend fun evictionCandidates(): List<EvictionCandidate>
    @Query("DELETE FROM relay_deliveries WHERE eventId = :eventId") suspend fun deleteDeliveries(eventId: String)
    @Query("DELETE FROM bulletins WHERE id = :eventId") suspend fun deleteBulletin(eventId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setAuthorPolicy(row: AuthorPolicyRow)
    @Query("DELETE FROM author_policies WHERE pubkey = :pubkey") suspend fun removeAuthorPolicy(pubkey: String)
    @Query("SELECT * FROM author_policies ORDER BY pubkey") suspend fun authorPolicies(): List<AuthorPolicyRow>
    @Query("SELECT state FROM author_policies WHERE pubkey = :pubkey") suspend fun authorState(pubkey: String): String?
    @Query("SELECT COUNT(*) FROM author_policies") suspend fun authorCount(): Int
}

@Database(
    entities = [BulletinRow::class, RelayDeliveryRow::class, DraftRow::class, AuthorPolicyRow::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    exportSchema = true,
)
abstract class FreegramDatabase : RoomDatabase() {
    abstract fun bulletins(): BulletinDao
}
