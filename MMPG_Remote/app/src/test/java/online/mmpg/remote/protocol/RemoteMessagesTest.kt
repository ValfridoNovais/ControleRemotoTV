package online.mmpg.remote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMessagesTest {

    @Test
    fun keyCodeForCommand_mapsTheFiveDpadAndEnterKeysToOfficialKeycodes() {
        // Values are the real android.view.KeyEvent / RemoteKeyCode constants
        // (cross-checked against tronikos/androidtvremote2's remotemessage.proto).
        assertEquals(19, RemoteMessages.keyCodeForCommand("UP"))
        assertEquals(20, RemoteMessages.keyCodeForCommand("DOWN"))
        assertEquals(21, RemoteMessages.keyCodeForCommand("LEFT"))
        assertEquals(22, RemoteMessages.keyCodeForCommand("RIGHT"))
        assertEquals(66, RemoteMessages.keyCodeForCommand("ENTER"))
    }

    @Test
    fun keyCodeForCommand_mapsTheNineNonDpadKeysAddedInPrompt5ToOfficialKeycodes() {
        // RemoteKeyCode values cross-checked against BOTH
        // tronikos/androidtvremote2's remotemessage.proto and
        // louis49/androidtv-remote's remotemessage.proto (identical numeric
        // values in both) - see THIRD_PARTY_NOTICES.md for exact line numbers.
        assertEquals(3, RemoteMessages.keyCodeForCommand("HOME"))
        assertEquals(4, RemoteMessages.keyCodeForCommand("BACK"))
        assertEquals(26, RemoteMessages.keyCodeForCommand("POWER"))
        assertEquals(24, RemoteMessages.keyCodeForCommand("VOLUME_UP"))
        assertEquals(25, RemoteMessages.keyCodeForCommand("VOLUME_DOWN"))
        // MUTE maps to KEYCODE_VOLUME_MUTE=164 (mutes the speaker), NOT the
        // numerically different KEYCODE_MUTE=91 (microphone mute per the
        // proto's own comment) - a TV remote's mute button is speaker mute.
        assertEquals(164, RemoteMessages.keyCodeForCommand("MUTE"))
        assertEquals(166, RemoteMessages.keyCodeForCommand("CHANNEL_UP"))
        assertEquals(167, RemoteMessages.keyCodeForCommand("CHANNEL_DOWN"))
        // KEYCODE_MEDIA_PLAY_PAUSE=85 is a genuine combined toggle keycode in
        // the real enum (distinct from separate PLAY=126/PAUSE=127), so no
        // alternate-between-two-keycodes strategy was needed.
        assertEquals(85, RemoteMessages.keyCodeForCommand("PLAY_PAUSE"))
    }

    @Test
    fun keyCodeForCommand_allFourteenSupportedKeysAreMapped() {
        // No key in RemoteProtocol.supportedKeys should silently fall back to
        // NOT_IMPLEMENTED - Prompt 5 completed the mapping for all 14.
        for (key in RemoteProtocol.supportedKeys) {
            assertTrue(
                "expected a real keycode mapping for supported key $key",
                RemoteMessages.keyCodeForCommand(key) != null
            )
        }
    }

    @Test
    fun keyCodeForCommand_returnsNullForAnUnknownCommand() {
        // An unknown command string (typo, or a key never added to the JS UI)
        // must be treated as an explicit error by the caller
        // (RemoteClient.sendKey returns UNKNOWN_KEY/NOT_IMPLEMENTED), never
        // silently mapped to a guessed keycode.
        assertNull(RemoteMessages.keyCodeForCommand("NAO_EXISTE"))
        assertNull(RemoteMessages.keyCodeForCommand("NOT_A_REAL_KEY"))
        assertTrue("NAO_EXISTE" !in RemoteProtocol.supportedKeys)
    }

    @Test
    fun directionConstants_matchOfficialRemoteDirectionEnum() {
        assertEquals(1, RemoteMessages.Direction.START_LONG)
        assertEquals(2, RemoteMessages.Direction.END_LONG)
        assertEquals(3, RemoteMessages.Direction.SHORT)
    }

    @Test
    fun featureSupported_isPingAndKeyOnly() {
        assertEquals(1, RemoteMessages.Feature.PING)
        assertEquals(2, RemoteMessages.Feature.KEY)
        assertEquals(3, RemoteMessages.Feature.SUPPORTED)
    }

    @Test
    fun buildKeyInject_roundTripsKeyCodeAndDirection() {
        val encoded = RemoteMessages.buildKeyInject(RemoteMessages.KeyCode.DPAD_UP, RemoteMessages.Direction.START_LONG)
        val fields = ProtoWire.parseMessage(encoded)
        val keyInjectBytes = (fields[10]?.first() as ProtoWire.Field.LengthDelimited).bytes
        val keyInjectFields = ProtoWire.parseMessage(keyInjectBytes)

        val keyCode = (keyInjectFields[1]?.first() as ProtoWire.Field.Varint).value
        val direction = (keyInjectFields[2]?.first() as ProtoWire.Field.Varint).value

        assertEquals(19L, keyCode)
        assertEquals(1L, direction)
    }

    @Test
    fun sendKey_normalTap_producesASingleShortKeyInjectMessage() {
        // RemoteClient.sendKey() sends exactly one RemoteKeyInject message
        // per tap, with direction=SHORT - matching both reference clients'
        // default behavior for an ordinary press (tronikos' send_key_command
        // defaults to RemoteDirection.SHORT; louis49's RemoteManager.sendKey/
        // sendPower likewise pass SHORT). START_LONG+END_LONG back-to-back
        // with no real hold in between is a different gesture (instant
        // press-and-hold) and must NOT be what a normal tap sends.
        val tap = RemoteMessages.buildKeyInject(RemoteMessages.KeyCode.ENTER, RemoteMessages.Direction.SHORT)

        val keyInjectBytes = (ProtoWire.parseMessage(tap)[10]!!.first() as ProtoWire.Field.LengthDelimited).bytes
        val keyInjectFields = ProtoWire.parseMessage(keyInjectBytes)
        val keyCode = (keyInjectFields[1]!!.first() as ProtoWire.Field.Varint).value
        val direction = (keyInjectFields[2]!!.first() as ProtoWire.Field.Varint).value

        assertEquals(66L, keyCode)
        assertEquals(3L, direction) // SHORT
    }

    @Test
    fun startLongAndEndLong_remainAvailableForAFutureHoldFeature() {
        // These enum values exist for a possible future press-and-hold
        // feature (START_LONG when the key goes down, a real elapsed delay,
        // then END_LONG) but are not used by RemoteClient.sendKey() today -
        // just confirm they still encode distinctly, so a future caller can
        // rely on them.
        val down = RemoteMessages.buildKeyInject(RemoteMessages.KeyCode.DPAD_UP, RemoteMessages.Direction.START_LONG)
        val up = RemoteMessages.buildKeyInject(RemoteMessages.KeyCode.DPAD_UP, RemoteMessages.Direction.END_LONG)
        assertTrue(!down.contentEquals(up))
    }

    @Test
    fun buildPingResponse_roundTripsVal1() {
        val encoded = RemoteMessages.buildPingResponse(42L)
        val parsed = RemoteMessages.parse(
            // Re-wrap as if it were an incoming message isn't meaningful for a
            // response we send ourselves; instead verify the raw field layout.
            encoded
        )
        // buildPingResponse produces a remote_ping_response field (9), which
        // RemoteMessages.parse() does not decode (it's outgoing-only), so
        // assert directly on the wire fields instead.
        val fields = ProtoWire.parseMessage(encoded)
        val pingResponseBytes = (fields[9]?.first() as ProtoWire.Field.LengthDelimited).bytes
        val val1 = (ProtoWire.parseMessage(pingResponseBytes)[1]?.first() as ProtoWire.Field.Varint).value
        assertEquals(42L, val1)
        assertEquals(RemoteMessages.Incoming.Unknown, parsed) // not a message this client would ever receive
    }

    @Test
    fun buildConfigure_roundTripsThroughParse() {
        val encoded = RemoteMessages.buildConfigure(
            code1 = RemoteMessages.Feature.SUPPORTED,
            model = "Pixel Test",
            vendor = "Google",
            packageName = "online.mmpg.remote",
            appVersion = "1.0.0"
        )

        val parsed = RemoteMessages.parse(encoded)
        assertTrue(parsed is RemoteMessages.Incoming.Configure)
        assertEquals(3L, (parsed as RemoteMessages.Incoming.Configure).code1)

        val fields = ProtoWire.parseMessage(encoded)
        val configureBytes = (fields[1]?.first() as ProtoWire.Field.LengthDelimited).bytes
        val configureFields = ProtoWire.parseMessage(configureBytes)
        val deviceInfoBytes = (configureFields[2]?.first() as ProtoWire.Field.LengthDelimited).bytes
        val deviceInfoFields = ProtoWire.parseMessage(deviceInfoBytes)
        val model = String((deviceInfoFields[1]?.first() as ProtoWire.Field.LengthDelimited).bytes)
        val vendor = String((deviceInfoFields[2]?.first() as ProtoWire.Field.LengthDelimited).bytes)
        val packageName = String((deviceInfoFields[5]?.first() as ProtoWire.Field.LengthDelimited).bytes)
        val appVersion = String((deviceInfoFields[6]?.first() as ProtoWire.Field.LengthDelimited).bytes)

        assertEquals("Pixel Test", model)
        assertEquals("Google", vendor)
        assertEquals("online.mmpg.remote", packageName)
        assertEquals("1.0.0", appVersion)
    }

    @Test
    fun buildSetActive_isRecognizedByParse() {
        val encoded = RemoteMessages.buildSetActive(RemoteMessages.Feature.SUPPORTED)
        assertEquals(RemoteMessages.Incoming.SetActive, RemoteMessages.parse(encoded))
    }

    @Test
    fun parse_recognizesPingRequestWithVal1() {
        val encoded = ProtoWire.encode { out ->
            val ping = ProtoWire.encode { inner ->
                ProtoWire.writeVarintField(inner, 1, 7L)
                ProtoWire.writeVarintField(inner, 2, 0L)
            }
            ProtoWire.writeMessageField(out, 8, ping)
        }

        val parsed = RemoteMessages.parse(encoded)
        assertTrue(parsed is RemoteMessages.Incoming.PingRequest)
        assertEquals(7L, (parsed as RemoteMessages.Incoming.PingRequest).val1)
    }

    @Test
    fun parse_recognizesRemoteStartAndItsStartedFlag() {
        fun remoteStart(started: Boolean): ByteArray = ProtoWire.encode { out ->
            val start = ProtoWire.encode { inner ->
                ProtoWire.writeVarintField(inner, 1, if (started) 1L else 0L)
            }
            ProtoWire.writeMessageField(out, 40, start)
        }

        assertEquals(RemoteMessages.Incoming.Start(true), RemoteMessages.parse(remoteStart(true)))
        assertEquals(RemoteMessages.Incoming.Start(false), RemoteMessages.parse(remoteStart(false)))
    }

    @Test
    fun parse_unrecognizedMessage_isUnknown() {
        val encoded = ProtoWire.encode { out ->
            ProtoWire.writeVarintField(out, 90, 1L) // remote_app_link_launch_request-ish field, not decoded
        }
        assertEquals(RemoteMessages.Incoming.Unknown, RemoteMessages.parse(encoded))
    }
}
