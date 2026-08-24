package online.mmpg.remote.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal, hand-written Protocol Buffers wire-format helpers.
 *
 * The Android TV Remote Protocol v2 pairing messages (and, later, the
 * remote-control channel messages on port 6466) are small and fixed in
 * shape, so this implements exactly the subset of the wire format needed to
 * encode/decode them - varints, length-delimited fields, and "delimited"
 * message framing (a varint length prefix before each serialized message,
 * matching Protocol Buffers' own `writeDelimitedTo`/`parseDelimitedFrom`
 * convention) - rather than pulling in the full protobuf runtime + protoc
 * toolchain for a handful of messages. It is intentionally reusable by the
 * future remote-control channel implementation (see RemoteClient.kt), which
 * uses the same framing.
 *
 * Wire format reference: https://protobuf.dev/programming-guides/encoding/
 * Message shapes were cross-checked against two independently maintained
 * open-source Android TV Remote Protocol v2 clients; see
 * THIRD_PARTY_NOTICES.md for exactly what was consulted. No source code was
 * copied from either reference - this is an original implementation of the
 * (unprotected) wire-format algorithm.
 */
internal object ProtoWire {
    const val WIRE_VARINT = 0
    const val WIRE_LENGTH_DELIMITED = 2

    fun writeVarint(out: OutputStream, valueIn: Long) {
        var value = valueIn
        while (true) {
            if (value and 0x7FL.inv() == 0L) {
                out.write(value.toInt())
                return
            } else {
                out.write(((value and 0x7F) or 0x80).toInt())
                value = value ushr 7
            }
        }
    }

    private fun tag(fieldNumber: Int, wireType: Int): Long =
        (fieldNumber.toLong() shl 3) or wireType.toLong()

    fun writeTag(out: OutputStream, fieldNumber: Int, wireType: Int) =
        writeVarint(out, tag(fieldNumber, wireType))

    fun writeVarintField(out: OutputStream, fieldNumber: Int, value: Long) {
        writeTag(out, fieldNumber, WIRE_VARINT)
        writeVarint(out, value)
    }

    fun writeBytesField(out: OutputStream, fieldNumber: Int, value: ByteArray) {
        writeTag(out, fieldNumber, WIRE_LENGTH_DELIMITED)
        writeVarint(out, value.size.toLong())
        out.write(value)
    }

    fun writeStringField(out: OutputStream, fieldNumber: Int, value: String) =
        writeBytesField(out, fieldNumber, value.toByteArray(Charsets.UTF_8))

    /** A nested/embedded message field is just a length-delimited field containing its bytes. */
    fun writeMessageField(out: OutputStream, fieldNumber: Int, message: ByteArray) =
        writeBytesField(out, fieldNumber, message)

    /** Convenience to build a message's serialized bytes with a lambda instead of a shared buffer. */
    fun encode(block: (OutputStream) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        block(buffer)
        return buffer.toByteArray()
    }

    fun readVarint(input: InputStream): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Unexpected end of stream while reading varint")
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift >= 64) throw IOException("Varint is too long (more than 64 bits)")
        }
    }

    /** A single decoded field occurrence: its wire type and raw payload. */
    sealed class Field {
        data class Varint(val value: Long) : Field()
        data class LengthDelimited(val bytes: ByteArray) : Field()
    }

    /**
     * Parses [data] into a map of field number -> every occurrence of that
     * field (protobuf allows a field, repeated or not, to appear more than
     * once on the wire). Only the varint and length-delimited wire types are
     * decoded, which is all that the pairing/remote messages use; any other
     * wire type throws, since it would indicate either corruption or a
     * message shape this client does not understand.
     */
    fun parseMessage(data: ByteArray): Map<Int, List<Field>> {
        val input = ByteArrayInputStream(data)
        val fields = mutableMapOf<Int, MutableList<Field>>()
        while (input.available() > 0) {
            val rawTag = readVarint(input)
            val fieldNumber = (rawTag ushr 3).toInt()
            val wireType = (rawTag and 0x7).toInt()
            val field = when (wireType) {
                WIRE_VARINT -> Field.Varint(readVarint(input))
                WIRE_LENGTH_DELIMITED -> Field.LengthDelimited(readLengthDelimitedBody(input))
                else -> throw IOException("Unsupported wire type $wireType for field $fieldNumber")
            }
            fields.getOrPut(fieldNumber) { mutableListOf() }.add(field)
        }
        return fields
    }

    private fun readLengthDelimitedBody(input: InputStream): ByteArray {
        val len = readVarint(input)
        if (len < 0 || len > Int.MAX_VALUE) throw IOException("Invalid length-delimited field size: $len")
        return readExactly(input, len.toInt())
    }

    private fun readExactly(input: InputStream, len: Int): ByteArray {
        val bytes = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(bytes, read, len - read)
            if (n < 0) throw EOFException("Unexpected end of stream (expected $len bytes, got $read)")
            read += n
        }
        return bytes
    }

    /** Writes [message] to [out] preceded by its length as a varint ("delimited" framing). */
    fun writeDelimited(out: OutputStream, message: ByteArray) {
        writeVarint(out, message.size.toLong())
        out.write(message)
        out.flush()
    }

    /**
     * Reads one varint-length-prefixed message from [input].
     *
     * @return the message bytes, or null if the stream was closed cleanly
     * before any data of a new message arrived (a graceful EOF). A partial
     * message (stream closed mid-length or mid-body) still throws
     * [EOFException], since that indicates an abnormal disconnect.
     */
    fun readDelimited(input: InputStream): ByteArray? {
        val first = input.read()
        if (first < 0) return null
        var result = first.toLong() and 0x7F
        var shift = 7
        var b = first
        while (b and 0x80 != 0) {
            b = input.read()
            if (b < 0) throw EOFException("Unexpected end of stream while reading message length")
            result = result or ((b.toLong() and 0x7F) shl shift)
            shift += 7
        }
        if (result < 0 || result > Int.MAX_VALUE) throw IOException("Invalid delimited message size: $result")
        return readExactly(input, result.toInt())
    }
}
