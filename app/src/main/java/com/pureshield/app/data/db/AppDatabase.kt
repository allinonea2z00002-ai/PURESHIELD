package com.pureshield.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
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
                    .build()
                    .also { instance = it }
            }
    }
}
