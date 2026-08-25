package online.mmpg.remote.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteKeyTest {
    @Test
    fun `fromWireName resolves every known command`() {
        assertEquals(RemoteKey.UP, RemoteKey.fromWireName("UP"))
        assertEquals(RemoteKey.PLAY_PAUSE, RemoteKey.fromWireName("PLAY_PAUSE"))
        assertEquals(RemoteKey.VOLUME_DOWN, RemoteKey.fromWireName("VOLUME_DOWN"))
    }

    @Test
    fun `fromWireName returns null for an unknown command`() {
        assertNull(RemoteKey.fromWireName("NOT_A_REAL_KEY"))
    }

    @Test
    fun `wireName round-trips through fromWireName for every entry`() {
        RemoteKey.entries.forEach { key ->
            assertEquals(key, RemoteKey.fromWireName(key.wireName))
        }
    }
}
