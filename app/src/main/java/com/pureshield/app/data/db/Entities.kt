package com.pureshield.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One row per app the user chose to block. */
@Entity(tableName = "blocked_app")
data class BlockedApp(
    @PrimaryKey val packageName: String,
    val label: String,
    /** Block this app while protection is active. */
    val blocked: Boolean = true,
    /** How many times this app was blocked today (reset by the daily worker). */
    val blockCountToday: Int = 0,
    /** Epoch of the last block of this app, 0 if never blocked. */
    val lastBlockedAt: Long = 0
)

/** A site (host) the user added to the blocklist. */
@Entity(tableName = "blocked_site")
data class BlockedSite(
    @PrimaryKey val host: String,
    val addedAt: Long = 0
)

/** A keyword scanned for in on-screen text while protection is active. */
@Entity(tableName = "keyword")
data class Keyword(
    @PrimaryKey val word: String
)

/**
 * A saved place.
 *
 * lat/lng are null until the user saves the location (e.g. by tapping the place on the map):
 * a place without coordinates is a template and never drives the location lock.
 * [modeInside] = true means "lock while inside" (এখানে থাকলে), false means "lock while away"
 * (বাইরে গেলে). [isInside] is the last known geographic state (inside the radius or not),
 * null until the first fix; it is presence state, not the lock state.
 */
@Entity(tableName = "safe_zone")
data class SafeZone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val radiusM: Int = 100,
    val modeInside: Boolean = true,
    val enabled: Boolean = true,
    val isInside: Boolean? = null
)

/** One recorded block event (app launch, blocked site, keyword hit or feed). */
@Entity(tableName = "block_event")
data class BlockEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Package name for an app block, host for a site block, keyword/feed otherwise. */
    val packageNameOrHost: String,
    /** "APP", "SITE", "KEYWORD" or "FEED". */
    val kind: String,
    val at: Long = 0
)

/** Aggregated per local calendar day (date = yyyy-MM-dd). Only real, recorded events are counted. */
@Entity(tableName = "focus_day")
data class FocusDay(
    @PrimaryKey val date: String,
    /** Protected minutes credited to this day. */
    val focusMinutes: Long = 0,
    /** App + site blocks recorded this day. */
    val blocks: Int = 0,
    /** True when the day's focus goal was met (set by the UI layer). */
    val goalMet: Boolean = false
)

/** One zone entry/exit, kept for the location history (30 days). */
@Entity(tableName = "zone_event")
data class ZoneEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val zoneId: Long,
    val entered: Boolean,
    val at: Long = 0
)

/**
 * The single time-lock row (id is always 1).
 *
 * Time is tracked with a checkpoint: [remainingAtCheckpointMs] was left at the moment recorded by
 * [checkpointElapsed] (monotonic clock, survives clock changes) and [checkpointEpoch] (wall clock,
 * used only after a reboot when the monotonic clock restarted). [bootCount] tells which case applies.
 *
 * [locProtecting] is the location-lock flag: set from presence by PlaceWatcher, never a session.
 * Protection is active when the time session is active OR [locProtecting] is true.
 */
@Entity(tableName = "lock_state")
data class LockState(
    @PrimaryKey val id: Int = 1,
    val active: Boolean = false,
    val durationMs: Long = 0,
    val startEpoch: Long = 0,
    val remainingAtCheckpointMs: Long = 0,
    val checkpointElapsed: Long = 0,
    val checkpointEpoch: Long = 0,
    val bootCount: Int = -1,
    val timeSatisfied: Boolean = false,
    val distanceSatisfied: Boolean = false,
    val locProtecting: Boolean = false
)
