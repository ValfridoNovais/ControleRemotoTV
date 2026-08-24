# Instruções para agentes de desenvolvimento

Você está trabalhando no projeto **MMPG Remote**, um controle Android para Android TV/Google TV.

## Restrições

- Não introduza ADB como requisito do usuário.
- Não exponha portas da TV à Internet.
- A comunicação deve ser direta na LAN.
- Não remova a UI web; preserve a ponte WebView ↔ Kotlin.
- Não invente sucesso de pareamento: só marque como funcional após teste real.
- Não hardcode IP da TV; discovery e seleção manual devem coexistir.
- Não armazene PIN de pareamento.
- Certificados/chaves privadas devem ser persistidos de forma segura.
- Mantenha suporte inicial a Android 8+ se não houver incompatibilidade técnica.
- Priorize baixo consumo de recursos e build por GitHub Actions.
- Todo código novo deve ter logs úteis, sem vazar segredo/certificado/PIN.

## Protocolo alvo

Android TV Remote Protocol v2:
- discovery: `_androidtvremote2._tcp`
- pairing: TCP/TLS 6467
- remote/control: TCP/TLS 6466
- framing protobuf varint
- troca de certificado e desafio com PIN no pareamento
- heartbeat/ping no canal remoto

Use como referência implementações abertas, respeitando suas licenças:
- tronikos/androidtvremote2 (Apache-2.0)
- louis49/androidtv-remote (MIT)
- kud/androidtv-remote (MIT)

## Critérios de pronto

Um incremento só é considerado pronto se:
1. compila;
2. testes unitários passam;
3. APK debug é gerado pelo GitHub Actions;
4. descoberta lista a TV real;
5. o fluxo de erro é legível para o usuário;
6. comandos não bloqueiam a UI thread;
7. reconexão não cria sockets órfãos.
