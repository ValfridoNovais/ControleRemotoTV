# Third-party references

Este scaffold não incorpora diretamente o código-fonte das implementações de protocolo abaixo, mas foi arquitetado para permitir que um agente as utilize como referência técnica durante a implementação.

- tronikos/androidtvremote2 — Apache License 2.0
- louis49/androidtv-remote — MIT License
- kud/androidtv-remote — MIT License

Ao incorporar ou adaptar qualquer trecho de código, o agente deve revisar a licença específica da versão utilizada e preservar os avisos exigidos.

## Pareamento (Android TV Remote Protocol v2) — arquivos consultados

Ao implementar `PairingClient.kt`, `PairingMessages.kt`, `ProtoWire.kt`,
`AcceptAnyServerTrustManager.kt` e a geração de certificado em
`CertificateStore.kt`, os seguintes arquivos de referência foram consultados
(lidos via WebFetch, nunca copiados literalmente — apenas a lógica
algorítmica e os números/nomes de campo das mensagens, que são fatos de
protocolo, foram reproduzidos em Kotlin idiomático original):

- **tronikos/androidtvremote2** (Apache License 2.0)
  — https://github.com/tronikos/androidtvremote2
  - `src/androidtvremote2/pairing.py` — sequência de mensagens do handshake
    de pareamento (`PairingRequest` → `PairingRequestAck` → `Options` →
    `Options` → `Configuration` → `ConfigurationAck` → `Secret` →
    `SecretAck`) e o algoritmo do desafio de PIN (SHA-256 sobre módulo/
    expoente RSA do cliente e do servidor + nonce derivado do PIN).
  - `src/androidtvremote2/polo.proto` — formato e números de campo da
    `OuterMessage` e das submensagens de pareamento.
  - `src/androidtvremote2/base.py` — framing varint "delimited" (tamanho da
    mensagem como varint antes de cada mensagem protobuf serializada).
  - `src/androidtvremote2/certificate_generator.py` — características do
    certificado autoassinado (RSA 2048 bits, validade ~10 anos, subject
    autoassinado) usadas como referência para os parâmetros do
    `KeyGenParameterSpec` do Android Keystore.
- **louis49/androidtv-remote** (MIT License)
  — https://github.com/louis49/androidtv-remote
  - `src/pairing/pairingmessage.proto` — usado para checagem cruzada dos
    números de campo e nomes de enum da `PairingMessage`.
  - `src/pairing/PairingMessageManager.js` e `src/pairing/PairingManager.js`
    — checagem cruzada da sequência de mensagens e da fórmula do hash do
    desafio de PIN (mesma fórmula de tronikos, com resultado idêntico).
- **google-tv-pairing-protocol** (AOSP, Apache License 2.0) — referência
  original citada pelos dois projetos acima
  — https://android.googlesource.com/platform/external/google-tv-pairing-protocol
  - `java/src/com/google/polo/pairing/PoloChallengeResponse.java` — origem
    canônica do algoritmo de hash (`getAlpha`), incluindo a regra de
    remover bytes nulos à esquerda de `BigInteger.toByteArray()` antes do
    hash — usada para confirmar que a extração de módulo/expoente em
    `PairingMessages.computePinSecret` reproduz corretamente o layout de
    bytes esperado pelo protocolo real (e não apenas o comportamento
    específico de outra linguagem).

Nenhum trecho de código-fonte desses repositórios foi copiado para este
projeto. O que foi reproduzido são fatos de protocolo (números de campo de
mensagens protobuf, sequência de handshake, fórmula de hash), implementados
aqui como código Kotlin original (`PairingMessages.kt`, `ProtoWire.kt`,
`PairingClient.kt`). O algoritmo do desafio de PIN foi validado com um vetor
de teste calculado de forma independente em Python (`PairingMessagesTest.kt`,
teste `computePinSecret_matchesKnownVector`), não apenas contra a leitura do
código de referência.

## Canal remoto / D-pad (Android TV Remote Protocol v2, porta 6466) — arquivos consultados

Ao implementar `RemoteMessages.kt` e `RemoteClient.kt`, os seguintes arquivos
de referência foram consultados (lidos via WebFetch/curl, nunca copiados
literalmente — apenas números/nomes de campo de mensagens protobuf, valores
de enum e a sequência de handshake/heartbeat, que são fatos de protocolo,
foram reproduzidos em Kotlin idiomático original):

- **tronikos/androidtvremote2** (Apache License 2.0)
  — https://github.com/tronikos/androidtvremote2
  - `src/androidtvremote2/remotemessage.proto` — formato e números de campo
    da `RemoteMessage` e das submensagens do canal remoto
    (`RemoteConfigure`, `RemoteDeviceInfo`, `RemoteSetActive`,
    `RemotePingRequest`/`RemotePingResponse`, `RemoteKeyInject`,
    `RemoteStart`), e os valores do enum `RemoteKeyCode`
    (`KEYCODE_DPAD_UP`=19, `KEYCODE_DPAD_DOWN`=20, `KEYCODE_DPAD_LEFT`=21,
    `KEYCODE_DPAD_RIGHT`=22, `KEYCODE_ENTER`=66) e do enum `RemoteDirection`
    (`START_LONG`=1, `END_LONG`=2, `SHORT`=3). Este arquivo em si é uma cópia
    do `remotemessage.proto` de louis49/androidtv-remote (citado no
    cabeçalho do próprio arquivo), com comentários adicionais extraídos do
    `android/keycodes.h` do AOSP (Apache License 2.0).
  - **Prompt 5 (HOME/BACK/POWER/VOLUME_UP/VOLUME_DOWN/MUTE/CHANNEL_UP/
    CHANNEL_DOWN/PLAY_PAUSE)** — valores adicionais do mesmo enum
    `RemoteKeyCode`, lidos via WebFetch em
    `https://raw.githubusercontent.com/tronikos/androidtvremote2/main/src/androidtvremote2/remotemessage.proto`
    (linhas exatas confirmadas por download/grep local do mesmo arquivo):
    `KEYCODE_HOME`=3 (linha 117), `KEYCODE_BACK`=4 (linha 119),
    `KEYCODE_VOLUME_UP`=24 (linha 165), `KEYCODE_VOLUME_DOWN`=25
    (linha 168), `KEYCODE_POWER`=26 (linha 170),
    `KEYCODE_MEDIA_PLAY_PAUSE`=85 (linha 297), `KEYCODE_MUTE`=91
    (linha 310, comentário "Mutes the microphone, unlike
    KEYCODE_VOLUME_MUTE" — **não** usado para o botão de mudo da TV),
    `KEYCODE_MEDIA_PLAY`=126 / `KEYCODE_MEDIA_PAUSE`=127 (linhas 416/418,
    não usados: existe um `KEYCODE_MEDIA_PLAY_PAUSE` combinado, então não
    foi necessária uma estratégia de alternância entre dois keycodes),
    `KEYCODE_VOLUME_MUTE`=164 (linha 499, comentário "Mutes the speaker,
    unlike KEYCODE_MUTE" — este é o keycode correto para o botão MUTE do
    controle remoto de TV), `KEYCODE_CHANNEL_UP`=166 (linha 506),
    `KEYCODE_CHANNEL_DOWN`=167 (linha 509). Também usado para consultar o
    bitmask `Feature` (IntFlag) em `src/androidtvremote2/remote.py`:
    `PING`=2**0, `KEY`=2**1, `IME`=2**2, `VOICE`=2**3, `POWER`=2**5,
    `VOLUME`=2**6, `APP_LINK`=2**9 — confirmando que `send_key_command()`
    (o método que emite `RemoteKeyInject`) não checa nenhum bit de feature
    por tecla, apenas a negociação genérica `KEY`; os bits `POWER`/`VOLUME`
    daquele cliente de referência gate uma funcionalidade não relacionada
    (sincronização de nível de volume via `remote_set_volume_level`, não
    implementada neste app), por isso `RemoteMessages.Feature.SUPPORTED`
    permanece `PING or KEY` mesmo após adicionar POWER/VOLUME_UP/
    VOLUME_DOWN/MUTE como comandos de tecla.
  - `src/androidtvremote2/remote.py` (classe `RemoteProtocol`) — sequência de
    handshake do canal remoto (o servidor envia `remote_configure` primeiro;
    o cliente responde com seu próprio `remote_configure` contendo o bitmask
    de features negociado e informações do dispositivo; o servidor envia
    `remote_set_active`, o cliente ecoa o bitmask negociado), o tratamento de
    `remote_ping_request` respondendo imediatamente com
    `remote_ping_response` ecoando `val1`, e a lógica de desconexão por
    inatividade (`_async_idle_disconnect`, 16s sem nenhuma mensagem do
    servidor) usada como referência para o timeout de heartbeat de
    `RemoteClient.kt` (`IDLE_TIMEOUT_MS` = 20s).
  - `src/androidtvremote2/const.py` — valores do bitmask `Feature`
    (`PING`=1, `KEY`=2, ...) usados para negociar `code1`/`active`.
- **louis49/androidtv-remote** (MIT License)
  — https://github.com/louis49/androidtv-remote
  - `src/remote/remotemessage.proto` — usado para checagem cruzada dos
    números de campo e nomes de enum do canal remoto (fonte original do
    arquivo equivalente em tronikos). Para o Prompt 5, baixado via
    `https://raw.githubusercontent.com/louis49/androidtv-remote/main/src/remote/remotemessage.proto`
    e conferido campo a campo contra o arquivo de tronikos: todos os 9
    keycodes novos (HOME=3, BACK=4, VOLUME_UP=24, VOLUME_DOWN=25, POWER=26,
    MEDIA_PLAY_PAUSE=85, MUTE=91, MEDIA_PLAY=126, MEDIA_PAUSE=127,
    VOLUME_MUTE=164, CHANNEL_UP=166, CHANNEL_DOWN=167) batem exatamente
    entre os dois repositórios (linhas 92-256 do arquivo de louis49).
  - `src/remote/RemoteManager.js` e `src/remote/RemoteMessageManager.js` —
    checagem cruzada da sequência de handshake (client responde
    `remote_configure` ao receber `remote_configure` do servidor, idem para
    `remote_set_active`, idem para `remote_ping_request`/
    `remote_ping_response`) e confirmação de que `code1`/`active` carregam
    um bitmask de features, não um valor arbitrário.
- **Aymkdn/assistant-freebox-cloud** (wiki, citado no docstring de
  `remote.py` de tronikos como a documentação original deste protocolo)
  — https://github.com/Aymkdn/assistant-freebox-cloud/wiki/Google-TV-(aka-Android-TV)-Remote-Control-(v2)
  - A wiki mostra o traço de bytes de VOLUME como um par
    press/release (`16, 1` seguido de `16, 2`), mas descreve explicitamente
    que `CHANNEL_UP`/`CHANNEL_DOWN` "will need only one message" com
    `direction=3` (`SHORT`) — ou seja, ela **não** afirma que todo toque de
    tecla é um par `START_LONG`/`END_LONG`; isso é específico de comandos do
    tipo volume. Combinado com `remote.py` (`send_key_command` de tronikos
    tem `direction: int | str = RemoteDirection.SHORT` como padrão) e com
    `RemoteManager.js` de louis49 (`sendPower()`/`sendKey()` passam `SHORT`
    para um toque comum), e com a integração oficial `androidtv_remote` do
    Home Assistant (que só usa `START_LONG` seguido de `END_LONG` quando o
    usuário pede um "hold" explícito, com um `asyncio.sleep(hold_secs)` real
    entre as duas mensagens — um toque normal cai em `direction or "SHORT"`),
    a conclusão correta é que um toque comum de UP/DOWN/LEFT/RIGHT/ENTER é
    **uma única mensagem** `RemoteKeyInject{direction=SHORT}`.
    `RemoteClient.sendKey()` foi corrigido para refletir isso; os valores
    `START_LONG`/`END_LONG` continuam definidos em `RemoteMessages.Direction`
    apenas para uma eventual funcionalidade futura de pressionar-e-segurar
    com um intervalo real entre as duas mensagens, não para o toque comum
    implementado neste incremento.

Nenhum trecho de código-fonte desses repositórios foi copiado para este
projeto. `RemoteMessages.kt` reimplementa apenas os fatos de protocolo acima
(números de campo, valores de enum, sequência de mensagens) como código
Kotlin original, reaproveitando unicamente o framing varint já existente em
`ProtoWire.kt`. A cobertura de teste (`RemoteMessagesTest.kt`) valida o
round-trip de encoding/decoding dessas mensagens, que um toque normal produz
uma única mensagem `RemoteKeyInject{direction=SHORT}`, e o mapeamento das 14
teclas de `RemoteProtocol.supportedKeys` (UP/DOWN/LEFT/RIGHT/ENTER/HOME/
BACK/POWER/VOLUME_UP/VOLUME_DOWN/MUTE/CHANNEL_UP/CHANNEL_DOWN/PLAY_PAUSE)
para os keycodes oficiais, sem depender de um `Socket`/`SSLSocket` real.
