package org.freegram.app.store

import androidx.room.Dao
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

@Dao
interface BulletinDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertBulletin(row: BulletinRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRelay(row: RelayDeliveryRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setRelay(row: RelayDeliveryRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setDraft(row: DraftRow)
    @Query("SELECT wire FROM bulletins ORDER BY createdAt DESC, id DESC LIMIT 1") suspend fun latestWire(): String?
    @Query("SELECT COUNT(*) FROM bulletins") suspend fun bulletinCount(): Int
    @Query("SELECT EXISTS(SELECT 1 FROM bulletins WHERE id = :eventId)") suspend fun hasBulletin(eventId: String): Boolean
    @Query("SELECT content FROM drafts WHERE slot = 0") suspend fun draft(): String?
    @Query("SELECT state FROM relay_deliveries WHERE eventId = :eventId AND relay = :relay") suspend fun relayState(eventId: String, relay: String): String?
    @Query("SELECT relay FROM relay_deliveries WHERE eventId = :eventId AND state != 'Accepted' ORDER BY relay") suspend fun pendingRelays(eventId: String): List<String>
}

@Database(entities = [BulletinRow::class, RelayDeliveryRow::class, DraftRow::class], version = 1, exportSchema = true)
abstract class FreegramDatabase : RoomDatabase() {
    abstract fun bulletins(): BulletinDao
}
