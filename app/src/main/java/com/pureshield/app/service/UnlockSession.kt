package com.pureshield.app.service

/**
 * In-memory, process-local record of apps the user unlocked with App Lock
 * authentication (partner PIN or biometrics).
 *
 * Never persisted to disk: process death clears every session, so a killed
 * app locks again — the safe default. The accessibility service also drops
 * an unlock the moment a different app comes to the foreground (including
 * SystemUI when Recents opens), so returning from Recents re-locks.
 */
object UnlockSession {

    private val unlocked = HashSet<String>()

    @Synchronized
    fun unlock(pkg: String) {
        unlocked.add(pkg)
    }

    @Synchronized
    fun isUnlocked(pkg: String): Boolean = pkg in unlocked

    @Synchronized
    fun clear(pkg: String) {
        unlocked.remove(pkg)
    }

    /**
     * Keeps only [pkg] unlocked (null keeps none): the foreground changed,
     * so every other app's unlock is dropped.
     */
    @Synchronized
    fun retainOnly(pkg: String?) {
        if (pkg == null) {
            unlocked.clear()
            return
        }
        unlocked.retainAll(setOf(pkg))
    }

    @Synchronized
    fun clearAll() {
        unlocked.clear()
    }
}
