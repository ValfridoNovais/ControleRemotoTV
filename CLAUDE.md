# CLAUDE.md

Este arquivo fornece orientações para o Claude Code (claude.ai/code) ao trabalhar com código neste repositório.

## Idioma

**Toda interação, resposta ou documentação deve ser em Português do Brasil.** Isso vale para mensagens ao usuário, comentários explicativos, resumos, commits e qualquer texto gerado — independentemente do idioma em que a solicitação for feita.

## Estrutura do projeto

A raiz do repositório contém apenas um `README.md`/`.gitattributes` de exemplo. O projeto de fato — **MMPG Remote**, um app de controle remoto para Android TV / Google TV (dispositivo alvo inicial: SEMP TCL 32S615) — está inteiramente em [MMPG_Remote/](MMPG_Remote/), que atualmente não está rastreado pelo git (o único commit, "Initial commit", adicionou apenas os arquivos de exemplo da raiz). Trate `MMPG_Remote/` como o código-base real para qualquer trabalho neste repositório.

Stack: Android (Kotlin), Gradle Kotlin DSL, AGP 8.7.3, Kotlin 2.0.21, compileSdk/targetSdk 35, minSdk 26 (Android 8+), Java 17. Pacote: `online.mmpg.remote`.

## Comandos

Todos os caminhos abaixo são relativos a `MMPG_Remote/`.

- **O caminho de build preferido é o GitHub Actions**, não o Android Studio local, para manter a máquina de desenvolvimento leve:
  - `.github/workflows/build-debug.yml` — dispara em push para `main` ou manualmente; executa `gradle --no-daemon assembleDebug`; artefato `MMPG-Remote-debug`.
  - `.github/workflows/build-release.yml` — apenas disparo manual; decodifica o secret `ANDROID_KEYSTORE_BASE64`, executa `gradle --no-daemon assembleRelease`, verifica com `apksigner`; artefato `MMPG-Remote-release`. Veja [SIGNING.md](MMPG_Remote/SIGNING.md) para configuração de keystore/secrets.
- **Build local** (ainda exige Android SDK + Gradle instalados localmente): `python tools/build.py debug` ou `python tools/build.py release` — o script apenas localiza `ANDROID_HOME`/`ANDROID_SDK_ROOT` e o Gradle, e então executa `gradle --no-daemon assembleDebug|assembleRelease`.
- **Testes unitários**: `gradle test` (atualmente apenas um teste, `RemoteProtocolTest`, em `app/src/test/java/online/mmpg/remote/protocol/`). Nenhum dos workflows de CI executa `test` ou `lint` — apenas `assemble*` — então rode os testes localmente/manualmente antes de confiar que o CI vai pegar regressões.

## Arquitetura

App somente cliente, sem backend. Ele fala diretamente com a TV pela LAN usando o **Android TV Remote Protocol v2** do Google — sem ADB, sem portas expostas à Internet.

```
WebView (assets/index.html, app.js, style.css)
        │  JavascriptInterface ("MMPGNative")
        ▼
TvBridge.kt
        ├── TvDiscovery.kt (NSD/mDNS: _androidtvremote2._tcp)
        ├── CertificateStore.kt
        └── protocol/
             ├── PairingClient.kt   (TLS, TCP 6467)
             ├── RemoteClient.kt    (TLS, TCP 6466)
             └── RemoteProtocol.kt  (constantes/chaves compartilhadas)
                    ▼
              TCL / Android TV
```

Todo o código Kotlin está em `app/src/main/java/online/mmpg/remote/`:

- **Camada de UI** (`app/src/main/assets/`): UI de controle remoto em HTML/JS/CSS puro (D-pad, volume/canal/mudo/play-pause, power/home/back, seletor de dispositivo, diálogo de pareamento). Só fala com o código nativo através de `window.MMPGNative.*` e recebe eventos assíncronos via `window.MMPG.onNative(event, json)` avaliado a partir do Kotlin. **Não remova essa UI em WebView nem contorne a ponte JS↔Kotlin** — é uma restrição rígida do projeto, não um detalhe de implementação incidental.
- **Bridge** (`TvBridge.kt`): expõe `getBuildInfo()`, `startDiscovery()`, `stopDiscovery()`, `pair(host, port, pin)`, `connect(host)`, `sendKey(key)`, `forget(host)` como métodos `@JavascriptInterface`, executa o trabalho em `CoroutineScope(SupervisorJob() + Dispatchers.IO)` e retorna os resultados via `webView.post { webView.evaluateJavascript(...) }`. Por ser a ponte JS da WebView, nenhuma dessas chamadas pode bloquear a thread de UI.
- **Discovery** (`discovery/TvDiscovery.kt`): usa `NsdManager` para buscar `_androidtvremote2._tcp.`, resolve os resultados em `TvDevice(name, host, port)` (porta padrão 6467), removendo duplicatas por host.
- **Camada de protocolo** (`protocol/`), orquestrada por `AndroidTvRemoteService` (dono de `CertificateStore`, `PairingClient`, `RemoteClient`):
  - `RemoteProtocol` — constantes `DEFAULT_PAIRING_PORT = 6467`, `DEFAULT_REMOTE_PORT = 6466`, e `supportedKeys` (UP/DOWN/LEFT/RIGHT/ENTER/HOME/BACK/POWER/VOLUME_UP/VOLUME_DOWN/MUTE/CHANNEL_UP/CHANNEL_DOWN/PLAY_PAUSE).
  - `PairingClient.pair()` e `RemoteClient.connect()`/`sendKey()` são **propositalmente stubs** (`NOT_IMPLEMENTED`) — esse é o estado real do projeto, não um bug. A implementação de criptografia/protobuf do Android TV Remote Protocol v2 (handshake TLS, mensagens protobuf com framing varint, desafio de PIN no pareamento, heartbeat ping/pong no canal remoto) foi deixada intencionalmente incompleta até poder ser validada contra uma TV real, para não simular um fluxo de pareamento/controle remoto "funcional" falsamente.
  - `CertificateStore` atualmente só guarda um booleano `paired:<host>` em `SharedPreferences`; certificados/chaves reais ainda precisam ser movidos para o Android Keystore.
  - `AndroidTvRemoteService.connect()` recusa com `NOT_PAIRED` a menos que `CertificateStore.isPaired(host)` seja verdadeiro.
- Builds de debug definem `BuildConfig.ALLOW_MOCK = true` ("modo mock" aspiracional para desenvolver a UI sem uma TV real — ainda não conectado a nenhum código Kotlin/JS); builds de release definem `false` e só leem a configuração de assinatura a partir de variáveis de ambiente (`ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`).

Implementações de referência a consultar (respeitando suas licenças) ao finalizar a camada de protocolo: `tronikos/androidtvremote2` (Apache-2.0), `louis49/androidtv-remote` (MIT), `kud/androidtv-remote` (MIT) — veja [THIRD_PARTY_NOTICES.md](MMPG_Remote/THIRD_PARTY_NOTICES.md).

## Regras de trabalho específicas deste projeto

Vêm de [MMPG_Remote/AGENTS.md](MMPG_Remote/AGENTS.md) e valem para qualquer agente (não só este) que trabalhe no código-base:

- Nunca exigir ADB do usuário; nunca expor portas da TV à Internet — comunicação só na LAN.
- Nunca simular um pareamento bem-sucedido — só marcar o protocolo como funcional depois de validado contra um dispositivo real.
- Nunca fixar o IP da TV no código; a descoberta via NSD e a entrada manual de host precisam continuar funcionando as duas.
- Nunca persistir o PIN de pareamento. Certificados/chaves privadas devem ser armazenados de forma segura (Android Keystore), não em `SharedPreferences`.
- Manter suporte a Android 8+ (minSdk 26) a menos que haja uma incompatibilidade técnica real.
- Preferir builds pelo GitHub Actions em vez de builds locais no Android Studio, para manter o consumo de recursos baixo.
- Código novo precisa de logs úteis, mas nunca deve logar segredos, certificados ou PINs.
- Um incremento só está "pronto" se: compila; os testes unitários passam; o APK debug builda via GitHub Actions; a descoberta encontra a TV real; os erros aparecem de forma legível para o usuário; os comandos nunca bloqueiam a thread de UI; a reconexão nunca vaza sockets.

Veja [MMPG_Remote/PROMPTS_AGENT.md](MMPG_Remote/PROMPTS_AGENT.md) para a ordem sugerida de tarefas (finalizar `PairingClient.kt` → `RemoteClient.kt` → testar descoberta/pareamento/D-pad/volume contra a TCL real → reforçar o armazenamento de certificados → APK de release assinado).
