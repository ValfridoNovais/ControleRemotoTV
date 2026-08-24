package online.mmpg.remote.protocol

import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/**
 * Encodes/decodes the Android TV Remote Protocol v2 pairing messages
 * (`OuterMessage` in the tronikos `polo.proto`, `PairingMessage` in the
 * louis49 `pairingmessage.proto` - same wire shape, different generated
 * names) using [ProtoWire], and computes the PIN challenge secret.
 *
 * Message field numbers/shapes and the PIN-secret algorithm were cross-
 * checked against two independently maintained open-source clients -
 * tronikos/androidtvremote2 (Apache-2.0) and louis49/androidtv-remote (MIT) -
 * and against the original Apache-2.0 `google-tv-pairing-protocol` AOSP
 * reference. See THIRD_PARTY_NOTICES.md for exactly which files were
 * consulted. Message definitions (field numbers/types) are protocol facts,
 * not copyrightable expression; no source code was copied from any
 * reference - this is an original Kotlin implementation.
 *
 * OuterMessage wire layout (field number -> meaning):
 *   1  protocol_version (varint)
 *   2  status           (varint enum, see [Status])
 *   10 pairing_request      (embedded PairingRequest)
 *   11 pairing_request_ack  (embedded PairingRequestAck)
 *   20 options              (embedded Options)
 *   30 configuration        (embedded Configuration)
 *   31 configuration_ack    (embedded, empty)
 *   40 secret               (embedded Secret)
 *   41 secret_ack           (embedded SecretAck)
 *
 * PairingRequest: 1=service_name(string), 2=client_name(string)
 * PairingRequestAck: 1=server_name(string)
 * Options: 1=input_encodings(repeated Encoding), 2=output_encodings(repeated Encoding), 3=preferred_role(varint enum)
 * Encoding: 1=type(varint enum), 2=symbol_length(varint)
 * Configuration: 1=encoding(Encoding), 2=client_role(varint enum)
 * Secret / SecretAck: 1=secret(bytes)
 */
internal object PairingMessages {
    // OuterMessage field numbers.
    private const val FIELD_PROTOCOL_VERSION = 1
    private const val FIELD_STATUS = 2
    private const val FIELD_PAIRING_REQUEST = 10
    private const val FIELD_PAIRING_REQUEST_ACK = 11
    private const val FIELD_OPTIONS = 20
    private const val FIELD_CONFIGURATION = 30
    private const val FIELD_CONFIGURATION_ACK = 31
    private const val FIELD_SECRET = 40
    private const val FIELD_SECRET_ACK = 41

    private const val PROTOCOL_VERSION = 2L

    /** OuterMessage.Status enum values (explicit, non-contiguous - matches both references). */
    object Status {
        const val OK = 200L
        const val ERROR = 400L
        const val BAD_CONFIGURATION = 401L
        const val BAD_SECRET = 402L
    }

    private const val ROLE_TYPE_INPUT = 1L
    private const val ENCODING_TYPE_HEXADECIMAL = 3L
    private const val PIN_SYMBOL_LENGTH = 6L

    private fun outerMessage(fieldNumber: Int, payload: ByteArray): ByteArray =
        ProtoWire.encode { out ->
            ProtoWire.writeVarintField(out, FIELD_PROTOCOL_VERSION, PROTOCOL_VERSION)
            ProtoWire.writeVarintField(out, FIELD_STATUS, Status.OK)
            ProtoWire.writeMessageField(out, fieldNumber, payload)
        }

    private fun encoding(): ByteArray = ProtoWire.encode { out ->
        ProtoWire.writeVarintField(out, 1, ENCODING_TYPE_HEXADECIMAL)
        ProtoWire.writeVarintField(out, 2, PIN_SYMBOL_LENGTH)
    }

    /** Step 1: client -> TV. Announces this app and asks to start pairing. */
    fun buildPairingRequest(clientName: String, serviceName: String): ByteArray {
        val request = ProtoWire.encode { out ->
            ProtoWire.writeStringField(out, 1, serviceName)
            ProtoWire.writeStringField(out, 2, clientName)
        }
        return outerMessage(FIELD_PAIRING_REQUEST, request)
    }

    /** Step 3: client -> TV. Declares the PIN encoding we want (6-digit hex), acting as input. */
    fun buildOptions(): ByteArray {
        val options = ProtoWire.encode { out ->
            ProtoWire.writeMessageField(out, 1, encoding()) // input_encodings
            ProtoWire.writeVarintField(out, 3, ROLE_TYPE_INPUT) // preferred_role
        }
        return outerMessage(FIELD_OPTIONS, options)
    }

    /** Step 5: client -> TV. Confirms the encoding/role chosen above. */
    fun buildConfiguration(): ByteArray {
        val configuration = ProtoWire.encode { out ->
            ProtoWire.writeMessageField(out, 1, encoding()) // encoding
            ProtoWire.writeVarintField(out, 2, ROLE_TYPE_INPUT) // client_role
        }
        return outerMessage(FIELD_CONFIGURATION, configuration)
    }

    /** Step 7: client -> TV. Sends the computed PIN-challenge secret. */
    fun buildSecret(secret: ByteArray): ByteArray {
        val secretMessage = ProtoWire.encode { out ->
            ProtoWire.writeBytesField(out, 1, secret)
        }
        return outerMessage(FIELD_SECRET, secretMessage)
    }

    /** Parsed view of an incoming OuterMessage relevant to the pairing state machine. */
    sealed class Incoming {
        data class Error(val status: Long) : Incoming()
        data object PairingRequestAck : Incoming()
        data object Options : Incoming()
        data object ConfigurationAck : Incoming()
        data object SecretAck : Incoming()
        data object Unknown : Incoming()
    }

    fun parse(raw: ByteArray): Incoming {
        val fields = ProtoWire.parseMessage(raw)
        val status = (fields[FIELD_STATUS]?.firstOrNull() as? ProtoWire.Field.Varint)?.value ?: Status.OK
        if (status != Status.OK) return Incoming.Error(status)
        return when {
            fields.containsKey(FIELD_PAIRING_REQUEST_ACK) -> Incoming.PairingRequestAck
            fields.containsKey(FIELD_OPTIONS) -> Incoming.Options
            fields.containsKey(FIELD_CONFIGURATION_ACK) -> Incoming.ConfigurationAck
            fields.containsKey(FIELD_SECRET_ACK) -> Incoming.SecretAck
            else -> Incoming.Unknown
        }
    }

    /** Thrown by [computePinSecret] when the PIN's checksum byte does not match. */
    class PinMismatchException : Exception("PIN checksum did not match")

    /**
     * Computes the Android TV Remote Protocol v2 pairing secret from the
     * client/server RSA public keys and the 6 hex-digit PIN shown on the TV.
     *
     * Algorithm (cross-checked against tronikos/androidtvremote2,
     * louis49/androidtv-remote, and the original AOSP
     * google-tv-pairing-protocol `PoloChallengeResponse` - see
     * THIRD_PARTY_NOTICES.md):
     *
     *   SHA-256(clientModulus || clientExponent || serverModulus || serverExponent || nonce)
     *
     * where modulus/exponent are each RSA public key's minimal big-endian
     * magnitude bytes (i.e. [BigInteger.toByteArray] with any leading 0x00
     * sign byte stripped), and `nonce` is the last 2 bytes (last 4 hex
     * digits) of the 6-digit PIN. The resulting digest's first byte must
     * equal the PIN's first byte (first 2 hex digits) - the TV encodes that
     * byte into the displayed PIN as a checksum so the client can detect a
     * mistyped/misread PIN locally, before spending a round trip on it.
     *
     * @throws IllegalArgumentException if [pin] is not exactly 6 hex digits.
     * @throws PinMismatchException if the checksum byte does not match.
     */
    fun computePinSecret(
        clientKey: RSAPublicKey,
        serverKey: RSAPublicKey,
        pin: String
    ): ByteArray {
        val pinBytes = try {
            require(pin.length == 6) { "PIN must be exactly 6 hex digits" }
            hexToBytes(pin)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("PIN must be hexadecimal", e)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsignedMagnitude(clientKey.modulus))
        digest.update(unsignedMagnitude(clientKey.publicExponent))
        digest.update(unsignedMagnitude(serverKey.modulus))
        digest.update(unsignedMagnitude(serverKey.publicExponent))
        digest.update(pinBytes, 1, pinBytes.size - 1) // nonce: last 2 bytes / 4 hex digits
        val result = digest.digest()

        if ((result[0].toInt() and 0xFF) != (pinBytes[0].toInt() and 0xFF)) {
            throw PinMismatchException()
        }
        return result
    }

    /** [BigInteger.toByteArray] big-endian magnitude, with a leading 0x00 sign byte stripped if present. */
    private fun unsignedMagnitude(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            if (high < 0 || low < 0) throw NumberFormatException("Invalid hex digit in PIN")
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }
}
