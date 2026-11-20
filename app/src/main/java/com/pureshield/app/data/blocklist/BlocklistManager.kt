package com.pureshield.app.data.blocklist

import android.content.Context
import com.pureshield.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Holds the adult-domain filter in memory for fast lookups from the accessibility URL-watch.
 *
 * Sources, all local: the bundled seed list (assets) and the user's own blocked sites
 * ([BlockedSite] rows). The allowlist always wins: a host (or any parent domain) on the
 * allowlist is never blocked, even if it is also in the seed list.
 * Lookups never leave the device.
 *
 * NOTE: the allowlist is kept in memory for this session (set via [setAllowedDomains]);
 * there is no allowlist table in the v1 schema. The UI should re-apply it after a restart
 * if it keeps its own allowlist.
 */
class BlocklistManager(private val context: Context, private val db: AppDatabase) {

    @Volatile private var listDomains: Set<String> = emptySet()
    @Volatile private var customBlocked: Set<String> = emptySet()
    @Volatile private var allowed: Set<String> = emptySet()

    fun isBlocked(host: String): Boolean {
        if (DomainMatcher.matches(host, allowed)) return false
        return DomainMatcher.matches(host, customBlocked) || DomainMatcher.matches(host, listDomains)
    }

    /** Number of domains in the bundled seed list (not counting the user's own entries). */
    fun listSize(): Int = listDomains.size

    /** Replaces the in-memory allowlist (allowlist wins over every other source). */
    fun setAllowedDomains(hosts: Set<String>) {
        allowed = hosts.map { DomainMatcher.normalize(it) }.filter { it.isNotEmpty() }.toHashSet()
    }

    fun allowedDomains(): Set<String> = allowed

    suspend fun reload() = withContext(Dispatchers.IO) {
        val merged = HashSet<String>(8_192)
        runCatching {
            context.assets.open(SEED_ASSET).bufferedReader().useLines { lines ->
                lines.forEach { l -> DomainMatcher.parseListLine(l)?.let(merged::add) }
            }
        }
        listDomains = merged
        customBlocked = db.blockedSiteDao().getAll().map { it.host }.toHashSet()
    }

    private companion object {
        const val SEED_ASSET = "adult_domains.txt"
    }
}
