package online.mmpg.remote.tv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobertura pura (sem Android) do roteamento de [TvManager] entre múltiplos
 * [TvProvider]s - o mesmo espírito de [online.mmpg.remote.discovery.TvDeviceRegistryTest]:
 * a lógica de bookkeeping fica isolada da camada Android para poder ser
 * testada com JUnit puro.
 */
class TvManagerTest {

    private class FakeTvProvider(override val id: String) : TvProvider {
        override val name: String = "Fake $id"
        override val platform: TvPlatform = TvPlatform.GENERIC_NETWORK
        override val pairingMethod: PairingMethod = PairingMethod.NONE

        val hosts = mutableSetOf<String>()
        var cancelPairingCalls = 0
        var lastSendKeyHost: String? = null

        override fun startDiscovery(onUpdate: (List<TvDevice>) -> Unit) {
            onUpdate(
                hosts.map { host ->
                    TvDevice(
                        id = "$id:$host",
                        name = "Device $host",
                        host = host,
                        port = 1234,
                        manufacturer = null,
                        model = null,
                        platform = platform,
                        providerId = id,
                        services = emptyList(),
                        capabilities = emptySet()
                    )
                }
            )
        }

        override fun stopDiscovery() {}

        override suspend fun beginPairing(host: String, port: Int): PairingResult =
            PairingResult(true, "OK", "begin:$id")

        override suspend fun submitPairingCredential(host: String, credential: String): PairingResult =
            PairingResult(true, "OK", "submit:$id")

        override fun cancelPairing() { cancelPairingCalls++ }

        override suspend fun connect(host: String): ConnectionResult =
            ConnectionResult(true, "OK", "connect:$id")

        override suspend fun sendKey(host: String, key: RemoteKey): CommandResult {
            lastSendKeyHost = host
            return CommandResult(true, "OK", "key:$id")
        }

        override suspend fun launchApp(host: String, appId: String): CommandResult =
            CommandResult(false, "NOT_IMPLEMENTED", "n/a")

        override suspend fun disconnect(host: String) {}

        override suspend fun forget(host: String): CommandResult =
            CommandResult(true, "OK", "forget:$id")

        override suspend fun pairedDevices(): List<TvDevice> = emptyList()

        override fun ownsHost(host: String): Boolean = host in hosts

        override fun supports(capability: TvCapability): Boolean = false
    }

    @Test
    fun `startDiscovery aggregates devices from every provider sorted by name`() {
        val a = FakeTvProvider("a").apply { hosts += "10.0.0.2" }
        val b = FakeTvProvider("b").apply { hosts += "10.0.0.1" }
        val manager = TvManager(listOf(a, b))

        var last: List<TvDevice> = emptyList()
        manager.startDiscovery { last = it }

        assertEquals(2, last.size)
        assertEquals("Device 10.0.0.1", last[0].name)
        assertEquals("Device 10.0.0.2", last[1].name)
    }

    @Test
    fun `sendKey routes to the provider that owns the host`() = runBlocking {
        val a = FakeTvProvider("a").apply { hosts += "10.0.0.2" }
        val b = FakeTvProvider("b").apply { hosts += "10.0.0.1" }
        val manager = TvManager(listOf(a, b))

        val result = manager.sendKey("10.0.0.1", RemoteKey.UP)

        assertTrue(result.ok)
        assertEquals("key:b", result.message)
        assertEquals("10.0.0.1", b.lastSendKeyHost)
        assertEquals(null, a.lastSendKeyHost)
    }

    @Test
    fun `sendKey with two providers and an unknown host reports PROVIDER_NOT_FOUND`() = runBlocking {
        val a = FakeTvProvider("a").apply { hosts += "10.0.0.2" }
        val b = FakeTvProvider("b").apply { hosts += "10.0.0.1" }
        val manager = TvManager(listOf(a, b))

        val result = manager.sendKey("10.0.0.99", RemoteKey.UP)

        assertFalse(result.ok)
        assertEquals("PROVIDER_NOT_FOUND", result.code)
    }

    @Test
    fun `sendKey with a single registered provider routes even for an unknown host`() = runBlocking {
        val only = FakeTvProvider("only")
        val manager = TvManager(listOf(only))

        val result = manager.sendKey("10.0.0.55", RemoteKey.UP)

        assertTrue(result.ok)
        assertEquals("10.0.0.55", only.lastSendKeyHost)
    }

    @Test
    fun `cancelPairing broadcasts to every registered provider`() {
        val a = FakeTvProvider("a")
        val b = FakeTvProvider("b")
        val manager = TvManager(listOf(a, b))

        manager.cancelPairing()

        assertEquals(1, a.cancelPairingCalls)
        assertEquals(1, b.cancelPairingCalls)
    }

    @Test
    fun `connect on an unrecognized host with multiple providers reports PROVIDER_NOT_FOUND`() = runBlocking {
        val a = FakeTvProvider("a").apply { hosts += "10.0.0.2" }
        val b = FakeTvProvider("b").apply { hosts += "10.0.0.1" }
        val manager = TvManager(listOf(a, b))

        val result = manager.connect("192.168.50.50")

        assertFalse(result.ok)
        assertEquals("PROVIDER_NOT_FOUND", result.code)
    }
}
