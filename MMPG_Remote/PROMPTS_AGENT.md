# Prompts para finalizar o MMPG Remote

Copie um prompt por vez para seu agente no VS Code. Peça para ele ler `AGENTS.md` e `README.md` antes de alterar arquivos.

---

## Prompt 1 — auditoria inicial

Analise integralmente este repositório MMPG Remote. Leia README.md e AGENTS.md. Não implemente nada ainda. Produza um diagnóstico objetivo contendo: estrutura atual, pontos compiláveis, TODOs, riscos, dependências faltantes e plano incremental para tornar o APK funcional na TCL/Android TV pelo Android TV Remote Protocol v2. Verifique também os workflows do GitHub Actions. Não suponha que o protocolo esteja pronto se houver stubs.

---

## Prompt 2 — finalizar descoberta da TV

Implemente e valide a descoberta Android NSD/mDNS para `_androidtvremote2._tcp`. Garanta lifecycle correto, cancelamento de discovery, deduplicação de dispositivos, atualização de IP/porta e callback para a WebView. Adicione tratamento das permissões necessárias por versão do Android. Crie testes onde for possível e logs sem dados sensíveis. Não mexa ainda em pareamento.

Critério: a UI deve exibir a TCL encontrada com nome, IP e porta.

---

## Prompt 3 — implementar pareamento Remote v2

Implemente `PairingClient.kt` para Android TV Remote Protocol v2 usando TLS e protobuf, tomando como referência implementações abertas confiáveis (tronikos/androidtvremote2 e/ou louis49/androidtv-remote), sem copiar código incompatível com a licença. O pareamento deve conectar na porta 6467, gerar/usar certificado persistente, iniciar a sessão, solicitar o PIN exibido pela TV, calcular corretamente o desafio criptográfico e informar sucesso ou erro à WebView. Nunca registre PIN ou chave privada em log.

Antes de concluir, descreva exatamente quais mensagens protobuf e etapas do handshake foram implementadas e quais arquivos de licença/atribuição foram adicionados.

---

## Prompt 4 — canal remoto e D-pad

Implemente `RemoteClient.kt` para o canal Android TV Remote v2 em TCP/TLS 6466, incluindo framing varint protobuf, mensagens de inicialização, heartbeat/ping e envio de key events com DOWN/UP. Comece apenas com UP, DOWN, LEFT, RIGHT e ENTER. Faça reconexão controlada e fechamento de socket no lifecycle.

Critério real: os cinco botões devem navegar na TCL pareada.

---

## Prompt 5 — comandos completos

Após D-pad validado, acrescente: HOME, BACK, POWER, VOLUME_UP, VOLUME_DOWN, MUTE, CHANNEL_UP, CHANNEL_DOWN, PLAY_PAUSE. Mapeie os comandos do JS para o enum/protobuf nativo. Trate comando desconhecido com erro explícito. Não use ADB.

---

## Prompt 6 — segurança e persistência

Audite CertificateStore e o fluxo de pareamento. Migre material sensível para Android Keystore quando aplicável. Garanta que PIN não seja persistido. Crie uma ação "Esquecer TV" que apague somente credenciais daquela TV. Faça revisão de logs e de permissões do Manifest.

---

## Prompt 7 — teste na TCL

Estou com o celular e a TCL na mesma Wi-Fi. Conduza um teste guiado usando logs via `adb logcat` somente para depuração do aplicativo no celular (não use ADB para controlar a TV). Quero validar na ordem: discovery, pairing PIN, conexão 6466, UP, ENTER, HOME, volume. A cada falha, peça somente o dado necessário do log e proponha a correção mínima.

---

## Prompt 8 — build sem Android Studio

Prepare o projeto para ser compilado no VS Code e principalmente pelo GitHub Actions. Corrija versões de Gradle/AGP/JDK e dependências. O workflow deve gerar um APK debug instalável e publicar como artifact. Não exija Android Studio. Documente o comando local opcional.

---

## Prompt 9 — assinatura release

Implemente o workflow de release assinado usando GitHub Secrets. Não grave keystore nem senhas no repositório. Use secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. O workflow deve decodificar a chave apenas no runner, compilar release, verificar assinatura e apagar o arquivo temporário ao final.

---

## Prompt 10 — preparação da versão 1.0

Faça uma revisão final do MMPG Remote. Remova código morto e modo mock da build release, mantenha-o apenas na debug. Execute testes, lint e build. Gere changelog 1.0. Confirme que nenhuma chave, PIN, IP privado fixo ou segredo foi commitado. O APK deve funcionar sem servidor, sem VPS e sem Internet, desde que celular e TV estejam na mesma LAN.
