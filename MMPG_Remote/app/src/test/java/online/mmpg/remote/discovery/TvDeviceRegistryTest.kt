package online.mmpg.remote.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JUnit coverage for the dedupe/removal bookkeeping used by TvDiscovery.
 *
 * NsdManager itself can't be exercised in a local unit test (it's part of the
 * Android framework and only available on-device/instrumented), which is why
 * this logic was pulled out into TvDeviceRegistry: everything about "does a
 * newly resolved device replace the old one" and "does onServiceLost actually
 * remove it" can be verified here without a real TV or an emulator.
 */
class TvDeviceRegistryTest {

    @Test
    fun `upsert adds a new device`() {
        val registry = TvDeviceRegistry()

        val snapshot = registry.upsert("tcl-service", TvDevice("TCL 32S615", "192.168.0.42", 6467))

        assertEquals(1, snapshot.size)
        assertEquals("TCL 32S615", snapshot[0].name)
        assertEquals("192.168.0.42", snapshot[0].host)
        assertEquals(6467, snapshot[0].port)
    }

    @Test
    fun `upsert with same key replaces host and port instead of duplicating`() {
        val registry = TvDeviceRegistry()
        registry.upsert("tcl-service", TvDevice("TCL 32S615", "192.168.0.42", 6467))

        // Simulates the TV re-appearing with a new DHCP lease / different port.
        val snapshot = registry.upsert("tcl-service", TvDevice("TCL 32S615", "192.168.0.99", 6467))

        assertEquals(1, snapshot.size)
        assertEquals("192.168.0.99", snapshot[0].host)
    }

    @Test
    fun `two different services are kept separately`() {
        val registry = TvDeviceRegistry()
        registry.upsert("tcl-service", TvDevice("TCL 32S615", "192.168.0.42", 6467))
        registry.upsert("shield-service", TvDevice("Shield TV", "192.168.0.55", 6467))

        val snapshot = registry.snapshot()

        assertEquals(2, snapshot.size)
        assertTrue(snapshot.any { it.host == "192.168.0.42" })
        assertTrue(snapshot.any { it.host == "192.168.0.55" })
    }

    @Test
    fun `remove drops the device on service lost`() {
        val registry = TvDeviceRegistry()
        registry.upsert("tcl-service", TvDevice("TCL 32S615", "192.168.0.42", 6467))

        val snapshot = registry.remove("tcl-service")

        assertTrue(snapshot.isEmpty())
    }

    @Test
    fun `remove for an unknown key is a harmless no-op`() {
        val registry = TvDeviceRegistry()
        registry.upsert("tcl-service", TvDevice("TCL 32S615", "192.168.0.42", 6467))

        // e.g. onServiceLost fires for a service that never finished resolving.
        val snapshot = registry.remove("never-resolved-service")

        assertEquals(1, snapshot.size)
    }

    @Test
    fun `clear wipes every known device`() {
        val registry = TvDeviceRegistry()
        registry.upsert("tcl-service", TvDevice("TCL 32S615", "192.168.0.42", 6467))
        registry.upsert("shield-service", TvDevice("Shield TV", "192.168.0.55", 6467))

        registry.clear()

        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `snapshot is sorted by name case-insensitively`() {
        val registry = TvDeviceRegistry()
        registry.upsert("b", TvDevice("zebra", "10.0.0.1", 6467))
        registry.upsert("a", TvDevice("Apple TV", "10.0.0.2", 6467))
        registry.upsert("c", TvDevice("mid", "10.0.0.3", 6467))

        val snapshot = registry.snapshot()

        assertEquals(listOf("Apple TV", "mid", "zebra"), snapshot.map { it.name })
    }
}
