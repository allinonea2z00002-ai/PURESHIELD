package com.pureshield.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LockStateDao {
    @Query("SELECT * FROM lock_state WHERE id = 1")
    suspend fun get(): LockState?

    @Query("SELECT * FROM lock_state WHERE id = 1")
    fun observe(): Flow<LockState?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: LockState)
}

@Dao
interface BlockedAppDao {
    @Query("SELECT * FROM blocked_app ORDER BY label COLLATE NOCASE")
    fun observeAll(): Flow<List<BlockedApp>>

    @Query("SELECT * FROM blocked_app")
    suspend fun getAll(): List<BlockedApp>

    @Query("SELECT * FROM blocked_app WHERE packageName = :pkg")
    suspend fun get(pkg: String): BlockedApp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: BlockedApp)

    @Query("UPDATE blocked_app SET blocked = :blocked WHERE packageName = :pkg")
    suspend fun setBlocked(pkg: String, blocked: Boolean)

    @Query("UPDATE blocked_app SET blockCountToday = 0")
    suspend fun resetDailyCounts()

    @Query("DELETE FROM blocked_app WHERE packageName = :pkg")
    suspend fun delete(pkg: String)
}

@Dao
interface BlockedSiteDao {
    @Query("SELECT * FROM blocked_site ORDER BY host")
    fun observeAll(): Flow<List<BlockedSite>>

    @Query("SELECT * FROM blocked_site")
    suspend fun getAll(): List<BlockedSite>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(site: BlockedSite)

    @Query("DELETE FROM blocked_site WHERE host = :host")
    suspend fun delete(host: String)
}

@Dao
interface KeywordDao {
    @Query("SELECT * FROM keyword ORDER BY word COLLATE NOCASE")
    fun observeAll(): Flow<List<Keyword>>

    @Query("SELECT * FROM keyword")
    suspend fun getAll(): List<Keyword>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(keyword: Keyword)

    @Query("DELETE FROM keyword WHERE word = :word")
    suspend fun delete(word: String)
}

@Dao
interface SafeZoneDao {
    @Query("SELECT * FROM safe_zone ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SafeZone>>

    @Query("SELECT * FROM safe_zone")
    suspend fun getAll(): List<SafeZone>

    @Query("SELECT * FROM safe_zone WHERE id = :id")
    suspend fun get(id: Long): SafeZone?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(zone: SafeZone): Long

    @Query("DELETE FROM safe_zone WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface BlockEventDao {
    @Insert
    suspend fun insert(event: BlockEvent)

    @Query("SELECT * FROM block_event ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BlockEvent>>

    @Query("SELECT COUNT(*) FROM block_event WHERE date(at / 1000, 'unixepoch', 'localtime') = :date")
    suspend fun countOnDate(date: String): Int

    @Query("DELETE FROM block_event WHERE at < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}

@Dao
interface FocusDayDao {
    @Query("SELECT * FROM focus_day ORDER BY date")
    fun observeAll(): Flow<List<FocusDay>>

    @Query("SELECT * FROM focus_day")
    suspend fun getAll(): List<FocusDay>

    @Query("SELECT * FROM focus_day WHERE date = :date")
    suspend fun get(date: String): FocusDay?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureRow(row: FocusDay)

    @Query("UPDATE focus_day SET focusMinutes = focusMinutes + :minutes WHERE date = :date")
    suspend fun addFocusMinutes(date: String, minutes: Long)

    @Query("UPDATE focus_day SET blocks = blocks + :n WHERE date = :date")
    suspend fun addBlocks(date: String, n: Int)

    @Query("UPDATE focus_day SET goalMet = :met WHERE date = :date")
    suspend fun setGoalMet(date: String, met: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FocusDay)

    @Query("DELETE FROM focus_day WHERE date < :olderThan")
    suspend fun deleteOlderThan(olderThan: String)
}

@Dao
interface ZoneEventDao {
    @Insert
    suspend fun insert(event: ZoneEvent)

    @Query("SELECT * FROM zone_event ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ZoneEvent>>

    @Query("SELECT * FROM zone_event WHERE at >= :sinceEpoch ORDER BY at DESC")
    suspend fun getSince(sinceEpoch: Long): List<ZoneEvent>

    @Query("DELETE FROM zone_event WHERE at < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}
