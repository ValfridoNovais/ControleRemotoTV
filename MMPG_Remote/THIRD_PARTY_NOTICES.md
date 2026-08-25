# Third-party references

Este scaffold não incorpora diretamente o código-fonte das implementações de protocolo abaixo, mas foi arquitetado para permitir que um agente as utilize como referência técnica durante a implementação.

- tronikos/androidtvremote2 — Apache License 2.0
- louis49/androidtv-remote — MIT License
- kud/androidtv-remote — MIT License
- hobbyquaker/lgtv2 — MIT License (referência para o `LgWebOsProvider` — ver
  seção "LG webOS" abaixo)
- xchwarze/samsung-tv-ws-api — LGPL-3.0 License (referência para o
  `SamsungTizenProvider` — ver seção "Samsung Tizen" abaixo; nenhum código
  incorporado, só fatos de protocolo consultados)

Dependências de código de terceiros de fato incorporadas (compiladas no APK, não apenas consultadas como referência):

- **Bouncy Castle** (`org.bouncycastle:bcpkix-jdk18on`) — MIT License —
  https://www.bouncycastle.org/ — usada em `CertificateStore.kt` só para
  construir o certificado X.509 autoassinado a partir do par de chaves RSA
  gerado em software (`JcaX509v3CertificateBuilder` +
  `JcaContentSignerBuilder`). Necessária porque o Android não expõe uma API
  pública para gerar um certificado a partir de um `KeyPair` arbitrário fora
  do fluxo de geração de chave do próprio `AndroidKeyStore` (ver seção
  "Identidade de cliente em software" abaixo para o porquê da mudança).
- **OkHttp** (`com.squareup.okhttp3:okhttp`) — Apache License 2.0 —
  https://square.github.io/okhttp/ — usada em `LgSsapClient.kt` como
  transporte WebSocket para o protocolo SSAP do LG webOS. Android não tem
  cliente WebSocket embutido; OkHttp é o padrão de fato para isso na
  plataforma.

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

## Identidade de cliente em software (diagnóstico em hardware real)

`CertificateStore.kt` originalmente gerava o par de chaves RSA diretamente no
`AndroidKeyStore` (hardware-backed). Testado ao vivo contra uma TV Android
real (firmware baseado em UnionTV, confirmado via `openssl s_client
-connect <ip>:6467 -showcerts`), o handshake TLS na porta 6467 falhava
consistentemente com `SSLHandshakeException` genérica do BoringSSL/Conscrypt
("RSA routines: internal error") no momento exato de assinar o
`CertificateVerify` — independente da versão de TLS negociada (padrão vs.
TLS 1.2 forçado, mesmo erro) e independente das capacidades declaradas na
chave (`PKCS#1` sozinho vs. `PKCS#1`+`PSS`, mesmo erro). A chave foi
confirmada hardware-backed (`KeyInfo.isInsideSecureHardware() == true`).
Essa combinação — negociação/capacidades corretas, falha só na operação de
assinatura real do hardware — indica um bug na implementação de assinatura
RSA do hardware seguro (TEE/Keymaster) deste aparelho específico, não
corrigível por ajuste de parâmetros na camada de API do Android.

A correção foi gerar o par de chaves RSA em software (JCA padrão, sem
`AndroidKeyStore` na operação de assinatura), protegendo a chave privada em
repouso por criptografia de envelope: uma chave AES-256-GCM gerada no
`AndroidKeyStore` (operação simples e confiável, não implicada na falha)
cifra os bytes PKCS#8 da chave RSA antes de persistir em
`SharedPreferences`. A chave privada só existe decifrada em memória durante
a construção do `KeyManager` de uma única sessão TLS.

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

## LG webOS — arquivos consultados

Ao implementar `LgWebOsProvider.kt`, `LgSsapClient.kt`,
`providers/ssdp/SsdpDiscovery.kt` e `LgManifest.kt`, os seguintes arquivos de
referência foram consultados (via WebSearch/WebFetch — não há um clone local
destes repositórios neste projeto):

- **hobbyquaker/lgtv2** (MIT License)
  — https://github.com/hobbyquaker/lgtv2
  - `index.js` — formato das mensagens SSAP (`{id, type, uri?, payload}`),
    estratégia de porta (`wss://host:3001` com fallback para
    `ws://host:3000`), e o protocolo de texto do socket especializado de
    ponteiro/botão (`SpecializedSocket.send`: linhas `chave:valor`
    terminadas por uma linha em branco).
  - `pairing.json` — o manifesto de pareamento (app de teste `com.lge.test`,
    assinado pela LG) reproduzido **verbatim** em `LgManifest.kt` — mudar
    qualquer campo do bloco `signed` invalidaria a assinatura; é o mesmo
    manifesto reutilizado sem alteração por praticamente todo cliente LG
    webOS de código aberto.
- Busca cruzada em código aberto (klattimer/LGWebOSRemote,
  home-assistant-libs/aiowebostv, LGTVCompanion, LG_Smart_TV_hubitat) para
  confirmar o vocabulário de nomes de botão do socket de ponteiro
  (`UP`/`DOWN`/`LEFT`/`RIGHT`/`HOME`/`BACK`/`ENTER`/`VOLUMEUP`/`VOLUMEDOWN`/
  `MUTE`/`CHANNELUP`/`CHANNELDOWN`) e o endpoint `ssap://system/turnOff`.
- **UDAP / SSDP** — `urn:lge-com:service:webos-second-screen:1` como Search
  Target do M-SEARCH, confirmado em múltiplas fontes (openHAB LG webOS
  binding, Home Assistant webostv integration, discussões de
  R0nd/LgSmartTvRemote).
- **Issues reais** de hobbyquaker/lgtv2 (nº 24: porta 3000 fechada em
  firmwares webOS 4) e de home-assistant/core (integração LG webOS: "nenhum
  pedido de pareamento aparece na TV", "connection reset by peer" mesmo com
  portas abertas) — consultadas depois que a descoberta SSDP se mostrou
  pouco confiável na prática (testando contra uma Samsung real), para
  levantar proativamente outros problemas já documentados pela comunidade
  em vez de esperar o próximo aparecer sozinho. Resultado em
  `LgWebOsProvider`: fallback de porta em `LgSsapClient`, varredura de
  sub-rede (`LgSubnetScan`) e mensagens de erro que citam a configuração
  "LG Connect Apps"/"Controle de IP em rede" da TV — ver
  docs/providers/lg-webos.md, seção 10.

Nenhum trecho de código-fonte do lgtv2 foi copiado, com uma única exceção
deliberada e explicitamente marcada: o conteúdo de `pairing.json` (o
manifesto assinado), reproduzido byte a byte em `LgManifest.kt` porque
qualquer edição invalidaria a assinatura criptográfica da LG. Todo o resto
(`LgSsapClient.kt`, `LgWebOsProvider.kt`, e `providers/ssdp/SsdpDiscovery.kt`,
depois generalizada para servir também ao provider Samsung abaixo) é Kotlin
original que reimplementa apenas os fatos de protocolo acima (formato de
mensagem, nomes de campo, nomes de botão), como já é a prática deste projeto
para o Android TV Remote Protocol v2.

**Este provider não foi validado contra uma TV LG real** (ao contrário do
Android TV, confirmado contra uma TCL 32S615 física) — ver o comentário de
classe em `LgWebOsProvider.kt` e `docs/providers/lg-webos.md`.

## Samsung Tizen — arquivos consultados

Ao implementar `SamsungTizenProvider.kt`, `SamsungWsClient.kt`,
`SamsungInfoClient.kt` e `WakeOnLan.kt`, os seguintes arquivos de referência
foram consultados (via WebSearch/WebFetch — não há um clone local destes
repositórios neste projeto):

- **xchwarze/samsung-tv-ws-api** (LGPL-3.0)
  — https://github.com/xchwarze/samsung-tv-ws-api
  - `samsungtvws/connection.py` — construção da URL do WebSocket
    (`wss://host:8002/api/v2/channels/samsung.remote.control?name=<base64>`,
    com `&token=` opcional), nomes de evento de pareamento
    (`MS_CHANNEL_CONNECT_EVENT`/`MS_CHANNEL_UNAUTHORIZED`) e onde o token
    aparece na resposta (`data.token`).
  - `samsungtvws/remote.py` — formato exato do comando de tecla
    (`{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":...,
    "Option":"false","TypeOfRemote":"SendRemoteKey"}}`).
  - `samsungtvws/rest.py` — endpoint HTTP não autenticado de informações do
    aparelho (`http://host:8001/api/v2/`).
- Busca cruzada em código aberto (samsungctl, samsung-tv-api, integrações
  Home Assistant/openHAB/Platypush) para confirmar o vocabulário de teclas
  (`KEY_UP`/`KEY_DOWN`/.../`KEY_POWER`/`KEY_VOLUP`/`KEY_CHUP`/etc.), o campo
  `wifiMac` na resposta do endpoint de informações, e o Search Target SSDP
  `urn:samsung.com:device:RemoteControlReceiver:1`.

Nenhum trecho de código-fonte do samsung-tv-ws-api foi copiado — só os fatos
de protocolo acima (formato de URL/mensagem, nomes de campo e evento) foram
reimplementados como Kotlin original. Por não incorporar nenhum código-fonte
LGPL a este projeto, as condições de copyleft da licença não se aplicam
aqui — a mesma lógica já usada para as referências Apache/MIT do Android TV.

**Este provider não foi validado contra uma TV Samsung real no momento em
que este arquivo foi escrito**, embora exista uma disponível (Samsung
UN50RU7100G, ~2019) para essa validação assim que alguém testar — ver
`docs/providers/samsung-tizen.md`.
