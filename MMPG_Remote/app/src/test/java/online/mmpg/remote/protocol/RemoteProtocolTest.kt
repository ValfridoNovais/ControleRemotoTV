package online.mmpg.remote.protocol

import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProtocolTest {
    @Test
    fun coreKeysExist() {
        assertTrue("UP" in RemoteProtocol.supportedKeys)
        assertTrue("ENTER" in RemoteProtocol.supportedKeys)
        assertTrue("POWER" in RemoteProtocol.supportedKeys)
    }
}
