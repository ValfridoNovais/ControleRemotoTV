# MMPG Remote

Projeto-base para um controle remoto Android TV/Google TV, pensado inicialmente para a SEMP TCL 32S615 e para desenvolvimento leve no VS Code, sem necessidade de Android Studio.

## Objetivo

- interface em HTML/CSS/JavaScript dentro de uma WebView;
- descoberta de TVs Android na LAN via NSD/mDNS;
- ponte JS ↔ Kotlin;
- camada nativa preparada para Android TV Remote Protocol v2;
- build via GitHub Actions para não sobrecarregar o computador;
- assinatura de APK por secrets do GitHub;
- modo mock para desenvolver a interface sem TV;
- prompts prontos para Codex/Claude/Gemini finalizarem e validarem o protocolo.

## Estado atual

A UI, ponte WebView, descoberta NSD, estrutura de persistência, modelo de dispositivos, comandos e pipelines de build estão estruturados.

**A parte criptográfica/protobuf do Android TV Remote Protocol v2 está deliberadamente isolada em `protocol/` e marcada com TODOs.**
Isso evita fingir uma implementação "funcional" sem teste real na TCL. O agente deve finalizar essa camada usando uma implementação aberta e compatível como referência, validar o pareamento na porta 6467 e o canal remoto na 6466 e só então remover o modo `NOT_IMPLEMENTED`.

Referências técnicas recomendadas:
- https://github.com/tronikos/androidtvremote2
- https://github.com/louis49/androidtv-remote
- https://github.com/kud/androidtv-remote

## Arquitetura

```text
WebView (assets/index.html)
        │
        │ JavascriptInterface
        ▼
TvBridge.kt
        │
        ├── TvDiscovery.kt (NSD/mDNS)
        ├── CertificateStore.kt
        └── protocol/
             ├── PairingClient.kt
             ├── RemoteClient.kt
             └── RemoteProtocol.kt
                    │
                    ▼
              TCL / Android TV
              TCP 6467 / 6466
```

## Build sem Android Studio

A forma mais leve é usar o GitHub Actions:

1. crie um repositório no GitHub;
2. envie o conteúdo desta pasta;
3. abra a aba **Actions**;
4. execute `Build Debug APK`;
5. baixe o artefato `MMPG-Remote-debug`.

O APK debug já é assinado com a chave de debug do Android e serve para instalação/teste.

## Build local pelo VS Code

GitHub Actions continua sendo o caminho preferido (menor consumo de recursos na máquina local). Ainda assim, é possível compilar localmente — em qualquer caso é obrigatório ter Java 17 e o Android SDK instalados; Android Studio **não** é necessário, apenas o SDK (command-line tools) e as variáveis `ANDROID_HOME`/`ANDROID_SDK_ROOT` configuradas.

Há duas formas equivalentes:

1. **Wrapper do Gradle** (recomendado, não depende de nada extra no PATH):

   ```bash
   ./gradlew assembleDebug
   # Windows: gradlew.bat assembleDebug
   ```

   O wrapper já fixa a versão do Gradle (8.10.2, a mesma usada no CI) via `gradle/wrapper/gradle-wrapper.properties`, então não é preciso instalar Gradle manualmente.

2. **`tools/build.py`**, que apenas coordena as ferramentas (Python não substitui o Android SDK):

   ```bash
   python tools/build.py debug
   ```

   O script prefere automaticamente o wrapper do projeto (`gradlew`/`gradlew.bat`) quando ele existe, e só cai para um `gradle` instalado no PATH como alternativa. Ele também ajuda a validar se `ANDROID_HOME`/`ANDROID_SDK_ROOT` estão definidos, apontando o erro certo quando faltam.

## Release assinado no GitHub

Leia `SIGNING.md`.

## Ordem sugerida para o agente

1. ler `AGENTS.md`;
2. executar os testes existentes;
3. concluir `protocol/PairingClient.kt`;
4. concluir `protocol/RemoteClient.kt`;
5. testar discovery com a TCL;
6. testar pareamento com PIN;
7. testar D-pad;
8. testar volume/Home/Back/Power;
9. endurecer armazenamento do certificado;
10. gerar APK release assinado.

Veja `PROMPTS_AGENT.md`.
