package online.mmpg.remote.discovery

import java.util.concurrent.ConcurrentHashMap

/**
 * Pure, framework-free bookkeeping for discovered TV devices.
 *
 * Extracted out of [TvDiscovery] so the dedupe/removal logic (add-or-replace on
 * resolve, remove on service-lost) can be exercised by plain JUnit tests without
 * touching `android.net.nsd.NsdManager`, which is unavailable to local unit tests.
 *
 * Devices are keyed by the caller-supplied [key] — [TvDiscovery] uses the NSD
 * service name, which is present on every callback (including `onServiceLost`,
 * where the resolved host/port are not guaranteed to be available).
 */
class TvDeviceRegistry {
    private val devicesByKey = ConcurrentHashMap<String, TvDevice>()

    /**
     * Adds a new device or replaces the existing one for [key] (e.g. the TV's IP
     * or port changed on a re-resolve). Returns a stable, name-sorted snapshot.
     */
    fun upsert(key: String, device: TvDevice): List<TvDevice> {
        devicesByKey[key] = device
        return snapshot()
    }

    /**
     * Removes the device registered under [key], if any. Safe to call for a key
     * that was never added (e.g. a service lost before it finished resolving).
     * Returns a stable, name-sorted snapshot.
     */
    fun remove(key: String): List<TvDevice> {
        devicesByKey.remove(key)
        return snapshot()
    }

    /** Drops every known device, e.g. when a fresh discovery scan begins. */
    fun clear() {
        devicesByKey.clear()
    }

    /** Current devices, sorted by display name (case-insensitive) for stable UI ordering. */
    fun snapshot(): List<TvDevice> = devicesByKey.values.sortedBy { it.name.lowercase() }
}
