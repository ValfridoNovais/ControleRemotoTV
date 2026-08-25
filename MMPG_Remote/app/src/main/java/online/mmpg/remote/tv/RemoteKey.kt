package online.mmpg.remote.tv

/**
 * Comando abstrato de controle remoto que a UI web conhece - a WebView
 * nunca vê um keycode de protocolo específico (KEYCODE_DPAD_UP, KEY_VOLUP,
 * etc.); cada [TvProvider] traduz para o seu próprio protocolo dentro de
 * [TvProvider.sendKey] (ver PROMPT_NOVOS_RECURSO.md, seção 11).
 *
 * [wireName] é exatamente a mesma string que já circulava end-to-end entre
 * `app.js` e `RemoteProtocol.supportedKeys` antes desta refatoração -
 * preservada 1:1, inclusive `PLAY_PAUSE` como uma tecla só (é o que o
 * Android TV Remote Protocol v2 realmente usa, um único keycode de alternar
 * - ver [online.mmpg.remote.protocol.RemoteMessages]). Um provider futuro
 * com PLAY e PAUSE separados (ex.: Roku) pode mapear os dois a partir do
 * mesmo PLAY_PAUSE, ou ganhar entradas próprias aqui quando for implementado.
 */
enum class RemoteKey(val wireName: String) {
    UP("UP"),
    DOWN("DOWN"),
    LEFT("LEFT"),
    RIGHT("RIGHT"),
    ENTER("ENTER"),
    HOME("HOME"),
    BACK("BACK"),
    POWER("POWER"),
    VOLUME_UP("VOLUME_UP"),
    VOLUME_DOWN("VOLUME_DOWN"),
    MUTE("MUTE"),
    CHANNEL_UP("CHANNEL_UP"),
    CHANNEL_DOWN("CHANNEL_DOWN"),
    PLAY_PAUSE("PLAY_PAUSE");

    companion object {
        private val byWireName = entries.associateBy { it.wireName }

        /** `null` quando a UI manda uma string que nenhum RemoteKey conhece. */
        fun fromWireName(name: String): RemoteKey? = byWireName[name]
    }
}
