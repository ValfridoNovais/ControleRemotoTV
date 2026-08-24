# Checklist de teste manual na TV real (Prompt 7)

Este checklist substitui a execução automática do Prompt 7 de `PROMPTS_AGENT.md`, que exige um celular e a SEMP TCL 32S615 (ou outra Android TV/Google TV) na mesma rede Wi-Fi — algo que nenhum agente consegue fazer sozinho. Use-o você mesmo, na ordem, depois de instalar o APK debug (gerado pelo workflow `Build Debug APK` do GitHub Actions).

Todo o código abaixo já foi implementado e revisado (Prompts 2–6), mas **nunca foi executado contra uma TV real** — é esperado que o primeiro teste real revele ajustes finos que só aparecem em hardware de verdade.

## Antes de começar

- [ ] Celular e TV na mesma rede Wi-Fi (2,4GHz ou 5GHz, sem isolamento de cliente/AP isolation no roteador).
- [ ] TV com "Android TV Remote Service" habilitado (geralmente ativo por padrão em Android TV/Google TV).
- [ ] APK debug instalado no celular (baixado do artifact `MMPG-Remote-debug` do GitHub Actions).
- [ ] Se quiser acompanhar logs durante o teste: `adb logcat -s TvDiscovery:* TvBridge:* MainActivity:* PairingClient:* RemoteClient:* CertificateStore:*` — o ADB aqui é só para depuração do app no celular, nunca para controlar a TV (isso violaria a regra do projeto).

## 1. Descoberta (Prompt 2)

- [ ] Abrir o app, tocar em "Procurar".
- [ ] Se for a primeira vez em Android 13+: deve aparecer o diálogo de permissão "Dispositivos próximos". Conceder.
- [ ] A TCL deve aparecer na lista em poucos segundos, com nome, IP e porta corretos.
- [ ] Desligar a TV (ou tirá-la da rede) e confirmar que ela desaparece da lista sem precisar reabrir o app.

**Se falhar:** cole aqui só a saída de `adb logcat -s TvDiscovery:*` referente à tentativa (não precisa do log inteiro).

## 2. Pareamento com PIN (Prompt 3)

- [ ] Selecionar a TCL encontrada, tocar em "Parear".
- [ ] A TV deve exibir um PIN de 6 dígitos hexadecimais na tela.
- [ ] Digitar o PIN no app e confirmar.
- [ ] Esperado: `pairResult` com `ok=true`, status muda para "Pareada".

**Se falhar:**
- PIN errado/rejeitado pela TV → confira se o app mostra `WRONG_PIN` (esperado quando o PIN foi digitado errado) ou algo diferente (indicaria bug no cálculo do desafio, que já foi validado matematicamente contra três fontes independentes, mas nunca contra hardware real).
- Erro de conexão/timeout → confirme que a porta 6467 não está bloqueada por firewall/roteador.
- Cole a saída de `adb logcat -s PairingClient:*` da tentativa (nunca vai conter o PIN em texto, isso é proposital).

## 3. Conexão no canal remoto (Prompt 4)

- [ ] Com a TV já pareada, tocar em "Conectar".
- [ ] Esperado: `connectResult` com `ok=true`, status "Conectada".

**Se falhar:** cole a saída de `adb logcat -s RemoteClient:*`. Preste atenção especial a qualquer erro relacionado ao bitmask de capacidades (`RemoteConfigure`) — é o ponto mais provável de comportamento inesperado em hardware real, segundo o revisor da etapa.

## 4. D-pad (Prompt 4)

Testar nesta ordem, um de cada vez, observando a reação na tela da TV:

- [ ] UP
- [ ] DOWN
- [ ] LEFT
- [ ] RIGHT
- [ ] ENTER

Cada botão deve produzir um toque normal (não um "pressionar e segurar" — isso foi corrigido explicitamente na revisão do Prompt 4).

**Se falhar:** cole a saída de `adb logcat -s RemoteClient:*` da tentativa, e diga qual tecla especificamente não funcionou (todas, ou só algumas).

## 5. Comandos completos (Prompt 5)

Testar cada um, na ordem sugerida (a mais provável de dar problema primeiro, segundo a ressalva registrada pelo revisor do Prompt 5):

- [ ] POWER (cuidado: isso pode desligar a TV — tenha como ligá-la de volta)
- [ ] VOLUME_UP
- [ ] VOLUME_DOWN
- [ ] MUTE
- [ ] HOME
- [ ] BACK
- [ ] CHANNEL_UP
- [ ] CHANNEL_DOWN
- [ ] PLAY_PAUSE (testar com algum app de vídeo/música tocando)

**Se POWER/VOLUME_UP/VOLUME_DOWN/MUTE não funcionarem mas o resto sim:** é o cenário que o revisor do Prompt 5 já previu como mais provável de precisar de ajuste — a causa mais provável é o bitmask de capacidades (`Feature.SUPPORTED`) declarado no handshake não incluir os bits `POWER`/`VOLUME`. A correção mínima seria adicionar esses bits em `RemoteMessages.kt` (`Feature.SUPPORTED`) e re-testar — não precisa reimplementar nada além disso.

## 6. "Esquecer TV" e "Redefinir identidade" (Prompt 6)

- [ ] Tocar em "Esquecer" na TV pareada → deve pedir pareamento de novo na próxima tentativa de conectar, mas sem precisar reinstalar o app.
- [ ] (Opcional, mais destrutivo) Testar "Redefinir identidade do app" na seção "Avançado" **só se tiver mais de uma TV pareada disponível** para confirmar que todas realmente precisam ser pareadas de novo depois — esse é o comportamento esperado e documentado.

## Ao final

Depois de rodar este checklist, atualize `MMPG_Remote/README.md` (seção "Estado atual") removendo a ressalva de que o protocolo não foi validado em hardware real, e anote aqui embaixo quaisquer ajustes que precisou fazer:

```
Data do teste: ____
Resultado: ____
Ajustes necessários: ____
```
