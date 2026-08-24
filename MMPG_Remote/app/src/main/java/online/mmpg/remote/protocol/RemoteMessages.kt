package online.mmpg.remote.protocol

/**
 * Android TV Remote Protocol v2 remote-control channel messages
 * (`RemoteMessage` in `remotemessage.proto`), TCP/TLS port 6466, encoded
 * using [ProtoWire]'s varint/length-delimited wire-format helpers and
 * "delimited" framing (same framing as the pairing channel).
 *
 * Field numbers/shapes and the `RemoteKeyCode`/`RemoteDirection` enum values
 * were cross-checked against two independently maintained open-source
 * Android TV Remote Protocol v2 clients - tronikos/androidtvremote2
 * (Apache-2.0) and louis49/androidtv-remote (MIT) - and against the protocol
 * notes at Aymkdn/assistant-freebox-cloud's wiki (cited by both reference
 * clients as the original documentation of this wire format). See
 * THIRD_PARTY_NOTICES.md for exactly which files were consulted. Message
 * definitions (field numbers/types, enum values) are protocol facts, not
 * copyrightable expression - this is an original Kotlin implementation, no
 * source code was copied from any reference.
 *
 * RemoteMessage field numbers relevant to the remote-control channel (all 14
 * [RemoteProtocol.supportedKeys] - D-pad/ENTER plus HOME/BACK/POWER/VOLUME_UP/
 * VOLUME_DOWN/MUTE/CHANNEL_UP/CHANNEL_DOWN/PLAY_PAUSE - are wired up
 * end-to-end via [keyCodeForCommand], see [RemoteClient]):
 *   1  remote_configure     (RemoteConfigure)   - bidirectional, handshake
 *   2  remote_set_active    (RemoteSetActive)   - bidirectional, handshake
 *   8  remote_ping_request  (RemotePingRequest) - server -> client, heartbeat
 *   9  remote_ping_response (RemotePingResponse)- client -> server, heartbeat
 *   10 remote_key_inject    (RemoteKeyInject)   - client -> server, commands
 *   40 remote_start         (RemoteStart)       - server -> client, informational
 *
 * RemoteConfigure:     1=code1(varint, feature bitmask) 2=device_info(RemoteDeviceInfo)
 * RemoteDeviceInfo:    1=model(string) 2=vendor(string) 3=unknown1(varint) 4=unknown2(string)
 *                      5=package_name(string) 6=app_version(string)
 * RemoteSetActive:     1=active(varint, feature bitmask)
 * RemotePingRequest:   1=val1(varint) 2=val2(varint)
 * RemotePingResponse:  1=val1(varint)
 * RemoteKeyInject:     1=key_code(varint enum RemoteKeyCode) 2=direction(varint enum RemoteDirection)
 * RemoteStart:         1=started(varint bool)
 */
internal object RemoteMessages {
    private const val FIELD_REMOTE_CONFIGURE = 1
    private const val FIELD_REMOTE_SET_ACTIVE = 2
    private const val FIELD_REMOTE_PING_REQUEST = 8
    private const val FIELD_REMOTE_PING_RESPONSE = 9
    private const val FIELD_REMOTE_KEY_INJECT = 10
    private const val FIELD_REMOTE_START = 40

    /**
     * `RemoteMessage.Feature`-style bitmask bits this client declares/
     * negotiates during the `RemoteConfigure`/`RemoteSetActive` handshake.
     * Only PING (required for the heartbeat) and KEY (required for every key
     * press, including the 9 non-D-pad keys added in Prompt 5) are declared.
     *
     * IME/VOICE/APP_LINK bits are intentionally left out because those
     * features are not implemented at all on this channel. POWER (2**5) and
     * VOLUME (2**6) also exist in tronikos/androidtvremote2's `Feature`
     * IntFlag (`remote.py`) and are deliberately NOT added here either, even
     * though POWER/VOLUME_UP/VOLUME_DOWN/MUTE key presses are now
     * implemented: in that reference client, `send_key_command()` (the
     * method that actually emits `RemoteKeyInject`) never checks any
     * per-key feature bit before sending - `RemoteKeyInject` for ANY
     * `RemoteKeyCode` is gated only by the generic KEY bit. The POWER/VOLUME
     * bits there instead gate a separate, unrelated feature this app does
     * not implement: receiving/setting `RemoteMessage.remote_set_volume_level`
     * / volume-level sync state. So declaring them would misrepresent a
     * capability this client doesn't have, and omitting them does not
     * disable POWER/VOLUME_UP/VOLUME_DOWN/MUTE key-inject, which only needs
     * KEY. This is the "minimal necessary adjustment" the mapping work in
     * Prompt 5 required to this already-validated handshake constant - no
     * other handshake/heartbeat logic was touched.
     */
    object Feature {
        const val PING = 1
        const val KEY = 2
        const val SUPPORTED = PING or KEY
    }

    /**
     * `RemoteKeyCode` enum values, cross-checked against
     * tronikos/androidtvremote2's `remotemessage.proto` (Apache-2.0) and
     * louis49/androidtv-remote's `remotemessage.proto` (MIT) - both list
     * identical numeric values for every constant below. See
     * THIRD_PARTY_NOTICES.md for exact file paths/line numbers consulted.
     *
     * MEDIA_PLAY_PAUSE=85 is a genuine combined play/pause toggle keycode in
     * the real enum (distinct from the separate KEYCODE_MEDIA_PLAY=126 /
     * KEYCODE_MEDIA_PAUSE=127), so PLAY_PAUSE maps directly to it - no
     * alternate-between-two-keycodes strategy was needed.
     *
     * VOLUME_MUTE=164 (mutes the speaker) is used for the MUTE command, not
     * the numerically different KEYCODE_MUTE=91 (which the proto documents
     * as muting the microphone, unrelated to a TV remote's mute button).
     */
    object KeyCode {
        const val HOME = 3
        const val BACK = 4
        const val DPAD_UP = 19
        const val DPAD_DOWN = 20
        const val DPAD_LEFT = 21
        const val DPAD_RIGHT = 22
        const val VOLUME_UP = 24
        const val VOLUME_DOWN = 25
        const val POWER = 26
        const val ENTER = 66
        const val MEDIA_PLAY_PAUSE = 85
        const val VOLUME_MUTE = 164
        const val CHANNEL_UP = 166
        const val CHANNEL_DOWN = 167
    }

    /**
     * `RemoteDirection` enum. A normal tap is sent as a single
     * `RemoteKeyInject{direction=SHORT}` message - this is the default in
     * both reference clients (tronikos' `send_key_command(key_code, direction
     * = RemoteDirection.SHORT)` and louis49's `sendPower()` /
     * `RemoteManager.sendKey()`, which pass `SHORT` for an ordinary press).
     * START_LONG/END_LONG instead model an explicit press-and-hold gesture:
     * START_LONG when the key goes down, END_LONG only after the hold
     * actually elapses (e.g. Home Assistant's `androidtv_remote` integration
     * sends START_LONG, then `asyncio.sleep(hold_secs)` for a *real* elapsed
     * hold duration, then END_LONG - not back-to-back with no delay). Sending
     * START_LONG immediately followed by END_LONG with no real hold in
     * between is a distinct gesture from a normal tap (some launchers treat
     * it as invoking a long-press/context action), so [RemoteClient.sendKey]
     * uses SHORT for the plain UP/DOWN/LEFT/RIGHT/ENTER taps implemented in
     * this increment. START_LONG/END_LONG are kept here for a future
     * press-and-hold feature, not used by any current caller.
     */
    object Direction {
        const val START_LONG = 1 // key down; only meaningful with a real hold before END_LONG
        const val END_LONG = 2 // key up, ending a hold started with START_LONG
        const val SHORT = 3 // a normal, instantaneous tap - used by RemoteClient.sendKey
    }

    /**
     * Maps a JS-side command name (see [RemoteProtocol.supportedKeys]) to the
     * `RemoteKeyCode` enum value. All 14 supported keys are mapped here.
     * Returns null for anything else (a key not in `supportedKeys`, e.g. a
     * typo or a future addition to the JS UI that hasn't been wired up on
     * this channel yet) - [RemoteClient.sendKey] treats a null result as
     * `NOT_IMPLEMENTED` rather than guessing a keycode.
     */
    fun keyCodeForCommand(command: String): Int? = when (command) {
        "UP" -> KeyCode.DPAD_UP
        "DOWN" -> KeyCode.DPAD_DOWN
        "LEFT" -> KeyCode.DPAD_LEFT
        "RIGHT" -> KeyCode.DPAD_RIGHT
        "ENTER" -> KeyCode.ENTER
        "HOME" -> KeyCode.HOME
        "BACK" -> KeyCode.BACK
        "POWER" -> KeyCode.POWER
        "VOLUME_UP" -> KeyCode.VOLUME_UP
        "VOLUME_DOWN" -> KeyCode.VOLUME_DOWN
        "MUTE" -> KeyCode.VOLUME_MUTE
        "CHANNEL_UP" -> KeyCode.CHANNEL_UP
        "CHANNEL_DOWN" -> KeyCode.CHANNEL_DOWN
        "PLAY_PAUSE" -> KeyCode.MEDIA_PLAY_PAUSE
        else -> null
    }

    /** Client -> TV: injects one key event (down or up, per [Direction]). */
    fun buildKeyInject(keyCode: Int, direction: Int): ByteArray = ProtoWire.encode { out ->
        val keyInject = ProtoWire.encode { inner ->
            ProtoWire.writeVarintField(inner, 1, keyCode.toLong())
            ProtoWire.writeVarintField(inner, 2, direction.toLong())
        }
        ProtoWire.writeMessageField(out, FIELD_REMOTE_KEY_INJECT, keyInject)
    }

    /** Client -> TV: heartbeat reply, echoing the request's val1. */
    fun buildPingResponse(val1: Long): ByteArray = ProtoWire.encode { out ->
        val pingResponse = ProtoWire.encode { inner ->
            ProtoWire.writeVarintField(inner, 1, val1)
        }
        ProtoWire.writeMessageField(out, FIELD_REMOTE_PING_RESPONSE, pingResponse)
    }

    /**
     * Client -> TV: this app's half of the `RemoteConfigure` exchange, sent
     * in reply to the TV's own `RemoteConfigure`. [code1] should be the
     * negotiated feature bitmask ([Feature.SUPPORTED] AND the TV's own
     * advertised bitmask). unknown1=1 / unknown2="1" are constant, non-secret
     * values that both reference clients always send regardless of device -
     * their real meaning is undocumented upstream, but the wire shape is a
     * protocol fact confirmed by both.
     */
    fun buildConfigure(
        code1: Int,
        model: String,
        vendor: String,
        packageName: String,
        appVersion: String
    ): ByteArray = ProtoWire.encode { out ->
        val deviceInfo = ProtoWire.encode { inner ->
            ProtoWire.writeStringField(inner, 1, model)
            ProtoWire.writeStringField(inner, 2, vendor)
            ProtoWire.writeVarintField(inner, 3, 1L)
            ProtoWire.writeStringField(inner, 4, "1")
            ProtoWire.writeStringField(inner, 5, packageName)
            ProtoWire.writeStringField(inner, 6, appVersion)
        }
        val configure = ProtoWire.encode { inner ->
            ProtoWire.writeVarintField(inner, 1, code1.toLong())
            ProtoWire.writeMessageField(inner, 2, deviceInfo)
        }
        ProtoWire.writeMessageField(out, FIELD_REMOTE_CONFIGURE, configure)
    }

    /** Client -> TV: confirms which negotiated features are now active. */
    fun buildSetActive(active: Int): ByteArray = ProtoWire.encode { out ->
        val setActive = ProtoWire.encode { inner ->
            ProtoWire.writeVarintField(inner, 1, active.toLong())
        }
        ProtoWire.writeMessageField(out, FIELD_REMOTE_SET_ACTIVE, setActive)
    }

    /** Parsed view of an incoming RemoteMessage relevant to this client. */
    sealed class Incoming {
        data class Configure(val code1: Long) : Incoming()
        data object SetActive : Incoming()
        data class PingRequest(val val1: Long) : Incoming()
        data class Start(val started: Boolean) : Incoming()
        data object Unknown : Incoming()
    }

    fun parse(raw: ByteArray): Incoming {
        val fields = ProtoWire.parseMessage(raw)

        val configureBytes = (fields[FIELD_REMOTE_CONFIGURE]?.firstOrNull() as? ProtoWire.Field.LengthDelimited)?.bytes
        if (configureBytes != null) {
            val configureFields = ProtoWire.parseMessage(configureBytes)
            val code1 = (configureFields[1]?.firstOrNull() as? ProtoWire.Field.Varint)?.value ?: 0L
            return Incoming.Configure(code1)
        }

        if (fields.containsKey(FIELD_REMOTE_SET_ACTIVE)) return Incoming.SetActive

        val pingBytes = (fields[FIELD_REMOTE_PING_REQUEST]?.firstOrNull() as? ProtoWire.Field.LengthDelimited)?.bytes
        if (pingBytes != null) {
            val pingFields = ProtoWire.parseMessage(pingBytes)
            val val1 = (pingFields[1]?.firstOrNull() as? ProtoWire.Field.Varint)?.value ?: 0L
            return Incoming.PingRequest(val1)
        }

        val startBytes = (fields[FIELD_REMOTE_START]?.firstOrNull() as? ProtoWire.Field.LengthDelimited)?.bytes
        if (startBytes != null) {
            val startFields = ProtoWire.parseMessage(startBytes)
            val started = (startFields[1]?.firstOrNull() as? ProtoWire.Field.Varint)?.value == 1L
            return Incoming.Start(started)
        }

        return Incoming.Unknown
    }
}
