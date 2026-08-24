# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.0.0/).
Este projeto ainda não segue Semantic Versioning à risca (é um app cliente sem
API pública), mas o `versionName`/`versionCode` em `app/build.gradle.kts`
seguem o padrão `MAJOR.MINOR.PATCH`.

## [1.0.0] — 2026-08-23

Primeira versão "completa" do MMPG Remote: toda a esteira de prompts
(1 a 10 em `PROMPTS_AGENT.md`) foi implementada e revisada. **A validação
final contra hardware real (SEMP TCL 32S615) ainda está pendente** — veja
"Pendências conhecidas" abaixo e o checklist em `TESTE_MANUAL_TV_REAL.md`.
Nenhuma etapa deste changelog declara pareamento/conexão "funcionando" sem
essa ressalva.

### Adicionado

- **Descoberta de TVs na LAN** (`discovery/TvDiscovery.kt`,
  `discovery/TvDeviceRegistry.kt`): busca `_androidtvremote2._tcp` via
  `NsdManager`, com lifecycle de start/stop idempotente, dedupe por nome de
  serviço, atualização de IP/porta em re-resolve e remoção ao perder o
  serviço (`onServiceLost`). Pedido de permissão `NEARBY_WIFI_DEVICES`
  (Android 13+) sob demanda, com fallback correto para "abrir configurações
  do app" quando negado permanentemente.
- **Pareamento Android TV Remote Protocol v2** (`protocol/PairingClient.kt`,
  `protocol/PairingMessages.kt`, `protocol/ProtoWire.kt`): handshake TLS
  completo na porta 6467 (`PairingRequest` → `PairingRequestAck` → `Options`
  → `Options` → `Configuration` → `ConfigurationAck` → `Secret` →
  `SecretAck`), com framing protobuf varint escrito à mão e cálculo do
  desafio de PIN (SHA-256 sobre módulo/expoente RSA de cliente e servidor +
  nonce derivado do PIN de 6 dígitos hex), validado com um vetor de teste
  calculado independentemente em Python.
- **Canal remoto/D-pad e comandos completos** (`protocol/RemoteClient.kt`,
  `protocol/RemoteMessages.kt`): canal TCP/TLS na porta 6466 com handshake
  `RemoteConfigure`/`RemoteSetActive`, heartbeat `RemotePingRequest`/
  `RemotePingResponse`, e envio de tecla via `RemoteKeyInject`. As 14 teclas
  de `RemoteProtocol.supportedKeys` estão mapeadas e implementadas: UP,
  DOWN, LEFT, RIGHT, ENTER, HOME, BACK, POWER, VOLUME_UP, VOLUME_DOWN, MUTE,
  CHANNEL_UP, CHANNEL_DOWN, PLAY_PAUSE. Reconexão fecha sempre o socket
  anterior antes de abrir um novo; teardown guardado por identidade evita
  que uma conexão obsoleta libere o estado de uma conexão mais nova
  (nenhum socket órfão em reconexão).
- **Segurança e persistência** (`protocol/CertificateStore.kt`): identidade
  de cliente TLS (par RSA 2048 + certificado autoassinado) gerada e mantida
  inteiramente dentro do Android Keystore — a chave privada nunca é
  exportada nem vista em texto plano por este código. `SharedPreferences`
  guarda apenas um booleano `paired:<host>` por TV, nunca certificado, PIN
  ou chave. Ações "Esquecer TV" (remove só o flag daquela TV) e "Redefinir
  identidade do app" (apaga a identidade Keystore inteira e todos os flags
  `paired:*`, expondo explicitamente que isso desautoriza *todas* as TVs)
  expostas na UI (`index.html`/`app.js`) e na ponte (`TvBridge.kt`).
- **UI WebView** (`assets/index.html`, `assets/app.js`, `assets/style.css`):
  D-pad, botões de volume/canal/mudo/play-pause, power/home/back, seletor de
  dispositivo, diálogo de pareamento com PIN, ação de esquecer TV e ação de
  redefinir identidade — tudo comunicando com o Kotlin exclusivamente via
  `window.MMPGNative.*` / `window.MMPG.onNative(...)`.
- **Build e CI** (`.github/workflows/build-debug.yml`, `tools/build.py`,
  `gradlew`): workflow de build debug (push em `main` ou manual), rodando
  testes unitários e publicando o APK como artifact. Build local documentado
  via wrapper do Gradle ou `tools/build.py`, sem exigir Android Studio.
- **Release assinado** (`.github/workflows/build-release.yml`,
  `SIGNING.md`): workflow manual que valida a presença dos 4 secrets
  (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
  `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) antes de compilar, decodifica
  o keystore só em arquivo temporário no runner, compila `assembleRelease`,
  verifica a assinatura com `apksigner verify` e apaga o keystore temporário
  ao final (mesmo em caso de falha).
- Indicador visual "DEBUG" na UI (`app.js`/`index.html`/`style.css`),
  visível apenas quando `BuildConfig.ALLOW_MOCK` é `true` (ou seja, apenas
  em build debug — release sempre reporta `false`). É uma decoração puramente
  visual: nenhuma descoberta, pareamento ou tecla é simulada; todo comando
  continua passando pelo caminho real NSD/TLS/protobuf.

### Segurança

- `AcceptAnyServerTrustManager` aceita qualquer certificado apresentado pela
  TV — decisão de protocolo (a TV usa certificado autoassinado, sem CA
  pública), não atalho de segurança; a confiança real vem do desafio de PIN
  no pareamento e do flag `paired:<host>`. Avisos de lint
  `TrustAllX509TrustManager`/`CustomX509TrustManager` foram revisados e
  suprimidos com `@SuppressLint` e justificativa em código, não ignorados
  silenciosamente.
- PIN de pareamento nunca é logado nem persistido — vive apenas em memória
  durante a chamada de `pair()`.
- `usesCleartextTraffic="false"` no Manifest; toda comunicação com a TV usa
  TLS (portas 6466/6467). Nenhuma chamada de rede é feita para fora da LAN:
  não há endpoint de internet, telemetria ou SDK de terceiros em
  `TvDiscovery`, `PairingClient`, `RemoteClient` ou em qualquer dependência
  declarada em `app/build.gradle.kts`.

### Corrigido / Limpo (revisão final v1.0)

- Removida variável `state.connected` não utilizada em `assets/app.js`.
- `TvBridge.getBuildInfo()` deixou de ser um método morto (nunca chamado
  pela UI) — agora alimenta o indicador visual "DEBUG" descrito acima.
- Comentário desatualizado em `protocol/RemoteProtocol.kt`, que ainda
  descrevia `PairingClient`/`RemoteClient` como stubs a implementar,
  corrigido para refletir o estado real (ambos implementados; o que falta é
  validação em hardware).

### Conhecido / decidido não corrigir nesta revisão

`gradle lint` (relatório completo em
`app/build/reports/lint-results-debug.html`) reportou 0 erros e os
seguintes avisos, mantidos deliberadamente:

- `OldTargetApi` — `targetSdk = 35` não é a versão mais recente conhecida
  pelo Lint. `compileSdk`/`targetSdk = 35` são um requisito fixado do
  projeto (ver `CLAUDE.md`); não alterado sem uma etapa dedicada de
  validação em Android mais novo.
- `UnusedAttribute` — `android:usesPermissionFlags="neverForLocation"` só
  tem efeito a partir da API 31, mas `minSdk = 26`. Inofensivo (o atributo é
  ignorado em versões antigas); mantido porque melhora o comportamento em
  API 31+ sem custo em API 26-30.
- `GradleDependency` (×3) — versões mais novas de `androidx.core:core-ktx`,
  `androidx.appcompat:appcompat` e `androidx.webkit:webkit` existem. Não
  atualizadas nesta revisão final para não introduzir mudanças de
  comportamento não testadas às vésperas do freeze de v1.0, sem um ciclo de
  build/teste dedicado; candidatas a uma manutenção futura pós-validação em
  hardware real.
- `SetJavaScriptEnabled` — necessário: a UI é a WebView HTML/JS descrita em
  `CLAUDE.md`, que é uma restrição dura do projeto ("não remova a UI web").
- `MissingApplicationIcon` — o Manifest não declara `android:icon`; o app
  fica com o ícone padrão do sistema no launcher. Puramente cosmético (não
  afeta funcionamento) e fora do escopo desta revisão de protocolo/
  segurança/build; fica registrado para uma futura passada de design/branding.

Nenhum destes avisos é um erro de build, um problema de segurança para além
do já documentado acima, ou um bloqueador para o uso do app.

### Pendências conhecidas

- **Validação em hardware real**: nenhuma parte do handshake de pareamento
  (porta 6467) ou do canal remoto (porta 6466) foi exercitada contra uma TV
  de verdade neste ambiente de desenvolvimento — não há uma Android TV
  disponível aqui. Toda a implementação foi cross-checada contra duas
  implementações abertas independentes (`tronikos/androidtvremote2`,
  `louis49/androidtv-remote`) e, no caso do desafio de PIN, também contra um
  vetor de teste calculado de forma independente — mas o teste definitivo só
  acontece na SEMP TCL 32S615 (ou outra Android TV/Google TV), seguindo o
  checklist em `TESTE_MANUAL_TV_REAL.md`. Nenhum resultado de pareamento ou
  conexão é fabricado: cada `ProtocolResult` só reporta `ok=true` depois de
  uma resposta real da TV no socket.
- `assembleRelease` local (sem os 4 secrets/keystore configurados) compila e
  empacota com sucesso, mas produz um APK **não assinado**
  (`app-release-unsigned.apk` — confirmado com
  `apksigner verify` retornando `DOES NOT VERIFY` / `Missing
  META-INF/MANIFEST.MF`). Isso é o comportamento esperado do Gradle/AGP
  (ausência de `signingConfig` não é, por si, um erro de build) — a
  salvaguarda real contra publicar um release sem assinatura válida é o
  workflow `build-release.yml`, que falha cedo se qualquer um dos 4 secrets
  estiver ausente e roda `apksigner verify --verbose` no artifact antes de
  publicá-lo como artifact do GitHub Actions.
- Nenhum ícone de aplicativo dedicado (ver aviso de lint acima).

### Testes

30 testes unitários (compilados e executados para as variantes debug e
release, ambas passando):

- `RemoteProtocolTest` (1) — sanity check de `RemoteProtocol.supportedKeys`.
- `TvDeviceRegistryTest` (7) — upsert/remove/clear/snapshot ordenado da
  bookkeeping de dispositivos descobertos.
- `PairingMessagesTest` (7) — encoding/decoding das mensagens de pareamento
  e o vetor de teste independente do cálculo do desafio de PIN.
- `RemoteMessagesTest` (15) — round-trip de encoding/decoding das mensagens
  do canal remoto e mapeamento das 14 teclas suportadas para os keycodes
  reais do protocolo.

`gradle test`, `gradle lint` e `gradle assembleDebug` executados localmente
sem erros; `gradle assembleRelease` local executado sem os secrets de
assinatura para confirmar que a compilação/empacotamento em si não depende
deles (ver "Pendências conhecidas" acima).
