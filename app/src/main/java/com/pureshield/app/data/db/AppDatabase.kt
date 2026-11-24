package com.pureshield.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v2 adds the diagnostics columns to block_event (reason / pkg / detail). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE block_event ADD COLUMN reason TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE block_event ADD COLUMN pkg TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE block_event ADD COLUMN detail TEXT NOT NULL DEFAULT ''")
    }
}

@Database(
    entities = [
        BlockedApp::class,
        BlockedSite::class,
        Keyword::class,
        SafeZone::class,
        BlockEvent::class,
        FocusDay::class,
        ZoneEvent::class,
        LockState::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lockStateDao(): LockStateDao
    abstract fun blockedAppDao(): BlockedAppDao
    abstract fun blockedSiteDao(): BlockedSiteDao
    abstract fun keywordDao(): KeywordDao
    abstract fun safeZoneDao(): SafeZoneDao
    abstract fun blockEventDao(): BlockEventDao
    abstract fun focusDayDao(): FocusDayDao
    abstract fun zoneEventDao(): ZoneEventDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pureshield.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
