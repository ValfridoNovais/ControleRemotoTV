package online.mmpg.remote.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec

class PairingMessagesTest {

    // Fixed RSA public keys (not real device keys) reused across tests below,
    // so the golden hash/PIN pair stays consistent.
    private val clientModulus = BigInteger(
        "C1F7C8A1B2D3E4F506172839405162738495A6B7C8D9E0F1A2B3C4D5E6F7081" +
            "92A3B4C5D6E7F8091A2B3C4D5E6F7081920F1E2D3C4B5A69788796A5B4C3D2E1" +
            "F0091827364554637281900FFEE",
        16
    )
    private val serverModulus = BigInteger(
        "FEDCBA987654321000112233445566778899AABBCCDDEEFF0011223344556677" +
            "8899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF0011223344556677",
        16
    )
    private val exponent = BigInteger.valueOf(65537)

    private fun rsaKey(modulus: BigInteger, exp: BigInteger): RSAPublicKey =
        KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, exp)) as RSAPublicKey

    private val clientKey = rsaKey(clientModulus, exponent)
    private val serverKey = rsaKey(serverModulus, exponent)

    /**
     * Golden vector computed independently in Python using the same
     * SHA-256(clientMod|clientExp|serverMod|serverExp|nonce) algorithm
     * described in THIRD_PARTY_NOTICES.md, to catch any byte-layout mistake
     * (e.g. wrong stripping of BigInteger sign bytes) without needing a real
     * TV to talk to.
     */
    @Test
    fun computePinSecret_matchesKnownVector() {
        val pin = "8AABCD"
        val expectedDigestHex = "8acc7c79f53a6256b83f94f1e551d768123d7f4767c5962978af1c3e93bfeecb"

        val secret = PairingMessages.computePinSecret(clientKey, serverKey, pin)

        assertEquals(32, secret.size)
        assertArrayEquals(hexToBytes(expectedDigestHex), secret)
    }

    @Test
    fun computePinSecret_wrongChecksumByte_throwsPinMismatch() {
        // Same nonce (ABCD) but a checksum byte that does not match the digest.
        assertThrows(PairingMessages.PinMismatchException::class.java) {
            PairingMessages.computePinSecret(clientKey, serverKey, "00ABCD")
        }
    }

    @Test
    fun computePinSecret_nonHexPin_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingMessages.computePinSecret(clientKey, serverKey, "12GHIJ")
        }
    }

    @Test
    fun computePinSecret_wrongLengthPin_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingMessages.computePinSecret(clientKey, serverKey, "1234")
        }
    }

    @Test
    fun pairingRequest_roundTripsThroughOuterMessageFraming() {
        val encoded = PairingMessages.buildPairingRequest("MMPG Remote", "mmpgremote")
        val fields = ProtoWire.parseMessage(encoded)

        val protocolVersion = (fields[1]?.first() as ProtoWire.Field.Varint).value
        val status = (fields[2]?.first() as ProtoWire.Field.Varint).value
        val requestBytes = (fields[10]?.first() as ProtoWire.Field.LengthDelimited).bytes
        val requestFields = ProtoWire.parseMessage(requestBytes)
        val serviceName = String((requestFields[1]?.first() as ProtoWire.Field.LengthDelimited).bytes)
        val clientName = String((requestFields[2]?.first() as ProtoWire.Field.LengthDelimited).bytes)

        assertEquals(2L, protocolVersion)
        assertEquals(200L, status)
        assertEquals("mmpgremote", serviceName)
        assertEquals("MMPG Remote", clientName)
    }

    @Test
    fun parse_recognizesEachAckMessageType() {
        fun outer(fieldNumber: Int, payload: ByteArray = ByteArray(0)): ByteArray = ProtoWire.encode { out ->
            ProtoWire.writeVarintField(out, 1, 2L)
            ProtoWire.writeVarintField(out, 2, PairingMessages.Status.OK)
            ProtoWire.writeMessageField(out, fieldNumber, payload)
        }

        assertEquals(PairingMessages.Incoming.PairingRequestAck, PairingMessages.parse(outer(11)))
        assertEquals(PairingMessages.Incoming.Options, PairingMessages.parse(outer(20)))
        assertEquals(PairingMessages.Incoming.ConfigurationAck, PairingMessages.parse(outer(31)))
        assertEquals(PairingMessages.Incoming.SecretAck, PairingMessages.parse(outer(41)))
    }

    @Test
    fun parse_nonOkStatus_isReportedAsError() {
        val encoded = ProtoWire.encode { out ->
            ProtoWire.writeVarintField(out, 1, 2L)
            ProtoWire.writeVarintField(out, 2, PairingMessages.Status.BAD_SECRET)
        }

        val result = PairingMessages.parse(encoded) as PairingMessages.Incoming.Error
        assertEquals(PairingMessages.Status.BAD_SECRET, result.status)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
