# LG webOS — pesquisa técnica e status do provider

Segue o roteiro pedido em `PROMPT_NOVOS_RECURSO.md`, seção 19, antes de
implementar `LgWebOsProvider`.

## Status

**EXPERIMENTAL — não validado contra hardware real.** Implementado a partir
de documentação de terceiros (código aberto reverso-projetado; a LG não
publica um protocolo oficial de controle remoto de segunda tela para
terceiros), sem uma TV LG disponível para testar de ponta a ponta. Ao
contrário do `AndroidTvProvider` (confirmado contra uma TCL 32S615 física),
nenhum resultado `ok=true` deste provider foi observado numa TV real ainda —
ele nunca finge sucesso (todo `CommandResult` só reporta `ok=true` depois de
uma resposta real da TV no socket), mas isso não substitui validação real.
Ver AGENTS.md: "Não invente sucesso de pareamento: só marque como funcional
após teste real."

## 1. Documentação oficial

Não existe uma API pública/oficial da LG para controle remoto de segunda
tela por terceiros — o SDK oficial webOS TV (`webostv.developer.lge.com`) é
voltado a apps que rodam DENTRO da TV, não a controlá-la remotamente de um
celular. O protocolo usado aqui (SSAP sobre WebSocket) é o mesmo que o app
oficial "LG ThinQ"/"LG TV Plus" usa, e foi reverso-projetado pela comunidade
ao longo de vários anos.

## 2. Projetos open source consolidados

- **hobbyquaker/lgtv2** (Node.js, MIT) — referência principal; ativo há mais
  de 8 anos, usado por diversos plugins Homebridge/Home Assistant.
- **klattimer/LGWebOSRemote** (Python) — usado para checagem cruzada dos
  nomes de botão do socket de ponteiro.
- **home-assistant-libs/aiowebostv** (Python) — usado para checagem cruzada
  dos endpoints `ssap://`.

Ver `THIRD_PARTY_NOTICES.md` para a lista completa de arquivos consultados e
como cada um foi usado.

## 3. Licença

hobbyquaker/lgtv2 é MIT — permite reprodução/adaptação com atribuição, o que
este projeto faz em `THIRD_PARTY_NOTICES.md`. O manifesto de pareamento
(`pairing.json`) é reproduzido verbatim em `LgManifest.kt` porque é um bloco
assinado criptograficamente pela LG (mudar qualquer campo invalidaria a
assinatura) — não há "adaptação" possível nesse arquivo específico.

## 4. Versões de firmware afetadas

- **webOS < 2018 (antes da linha 4.x)**: só a porta insegura `ws://host:3000`
  existe.
- **webOS 4.x+ (2018+)**: porta segura `wss://host:3001` recomendada; `3000`
  pode continuar respondendo dependendo do modelo.
- **webOS ≥ 2023**: alguns firmwares bloqueiam a porta `3000` por completo —
  só `3001` funciona.

`LgSsapClient.connect()` tenta `wss://:3001` primeiro e cai para
`ws://:3000` automaticamente, cobrindo as duas gerações sem precisar saber a
versão de antemão.

## 5. Portas

- `3001` (WSS, preferencial) / `3000` (WS, fallback) — canal principal SSAP.
- Um segundo WebSocket, cujo endereço só é conhecido depois de pedir
  `ssap://com.webos.service.networkinput/getPointerInputSocket` no canal
  principal, é usado só para botões/ponteiro (protocolo de texto simples,
  não JSON).

## 6. Discovery

SSDP: M-SEARCH multicast para `239.255.255.250:1900` com
`ST: urn:lge-com:service:webos-second-screen:1`. Diferente do NSD/mDNS usado
pelo Android TV Remote Protocol v2 (escuta contínua orientada a evento),
SSDP aqui é uma varredura pontual de alguns segundos por chamada — ver
`providers/ssdp/SsdpDiscovery.kt` (genérica, compartilhada com o provider
Samsung).

**SSDP sozinho não é confiável para TVs LG** — mesmo problema documentado
para Samsung (ver docs/providers/samsung-tizen.md), mais o próprio consenso
da comunidade lgtv2: ferramentas de descoberta terceiras já descrevem o
padrão "tenta SSDP primeiro, depois cai para uma varredura limitada da
sub-rede pela porta WebSocket do webOS" como a forma confiável de encontrar
a TV. Por isso `LgWebOsProvider.startDiscovery()` roda as duas em paralelo:
`LgSubnetScan` sonda a porta WebSocket (3001/3000) direto em cada endereço
da sub-rede /24 do celular, sem nunca mandar "register" (só abre e fecha o
socket) — como é o "register" que dispara o prompt de confirmação na TV,
essa sondagem não incomoda o usuário em TVs que ele nem selecionou ainda.

## 7. Método de pareamento

`TV_CONFIRMATION`: o app manda uma mensagem `register` com um manifesto de
permissões; a TV mostra um prompt ("Permitir que o celular controle esta
TV?"); se o usuário aceitar no controle físico da TV, o app recebe
`{"type":"registered","payload":{"client-key":"..."}}` — essa chave é
persistida (`LgKeyStore`) e reenviada em conexões futuras para pular o
prompt. Não há PIN para o usuário digitar no celular, ao contrário do
Android TV — por isso `beginPairing()` sozinho já conclui (ou falha) o
pareamento inteiro; `submitPairingCredential()` não é usado de fato nesse
fluxo (ver `TvProvider.kt` para os três desvios deliberados do contrato
original em `PROMPT_NOVOS_RECURSO.md`).

## 8. Comandos

D-pad, HOME, BACK, ENTER, VOLUME_UP/DOWN, MUTE, CHANNEL_UP/DOWN — todos via
o socket especializado de ponteiro/botão (`type:button\nname:X\n\n`).
Desligar — `ssap://system/turnOff` no canal principal.

**PLAY_PAUSE não é suportado** (`NOT_IMPLEMENTED` honesto, não uma tentativa
adivinhada): webOS tem botões `PLAY` e `PAUSE` separados, sem um alternador
único — mapear `PLAY_PAUSE` para um dos dois erraria metade das vezes sem
saber o estado atual de reprodução.

**Ligar uma TV desligada não é suportado.** Exigiria Wake-on-LAN com o
endereço MAC do aparelho; a resposta SSDP não devolve o MAC de forma
confiável e o Android não dá acesso direto à tabela ARP sem privilégios
especiais. `POWER` neste provider só cobre desligar (a TV precisa já estar
ligada e conectada) — reportado como `NOT_IMPLEMENTED` honesto quando a
tentativa de desligar falha, nunca como sucesso fingido.

## 9. Funciona sem nuvem?

Sim — tudo acontece direto na LAN (WebSocket local nas portas 3000/3001),
sem nenhuma dependência de servidor da LG. Mesma garantia que o app já tem
para Android TV (ver AGENTS.md: "A comunicação deve ser direta na LAN").

## 10. Outros problemas conhecidos pesquisados proativamente

Depois de descobrir (com uma TV Samsung real) que SSDP sozinho não é
confiável, valeu revisar também as issues reais de hobbyquaker/lgtv2 e da
integração LG webOS do Home Assistant atrás de outros defeitos já
documentados pela comunidade, em vez de esperar alguém topar com eles:

- **`usesCleartextTraffic="false"` bloqueava o fallback `ws://:3000` (e a
  varredura de sub-rede inteira).** Achado direto no log de um teste real:
  toda tentativa `ws://` falhava com `CLEARTEXT communication not permitted
  by network security policy` - o `AndroidManifest.xml` já tinha essa flag
  em `false` desde antes deste provider existir, e ninguém tinha checado se
  os novos providers precisariam de tráfego não criptografado até essa
  varredura expor o problema. Corrigido (`usesCleartextTraffic="true"`, ver
  comentário no manifest para a justificativa) - a mesma correção também
  resolveu um problema idêntico e até então não detectado no
  `SamsungInfoClient` (seção 10 de docs/providers/samsung-tizen.md).
- **SSDP pouco confiável** (seção 6 acima) — mesma classe de problema já
  achada na Samsung; corrigido com `LgSubnetScan`.
- **Porta 3000 fechada em alguns firmwares webOS 4** (issue #24 do
  hobbyquaker/lgtv2) — já coberto: `LgSsapClient.connect()` tenta
  `wss://:3001` primeiro e cai para `ws://:3000` automaticamente, só quando
  a conexão em si falha (nunca depois de abrir, para não arriscar um
  segundo prompt na tela).
- **"Nenhum pedido de pareamento aparece na TV" / "connection reset by
  peer" mesmo com as portas abertas** — causa mais comum relatada em issues
  do Home Assistant: a configuração **"LG Connect Apps"** (TVs mais
  antigas) ou **"Controle de IP em rede"/"Mobile TV On"** (TVs mais novas)
  desabilitada nas configurações de rede da própria TV, não um bug do
  cliente. As mensagens de erro de timeout/conexão do provider agora citam
  essa configuração explicitamente.
- **TVs muito antigas (anteriores a ~2014, pré-webOS/"NetCast")** usam um
  protocolo completamente diferente (UDAP sobre HTTP/XML) — mesma família
  de problema que os modelos Orsay da Samsung (ver
  docs/providers/samsung-tizen.md). Fora de escopo desta fase; a mensagem
  de erro de conexão já sugere essa possibilidade em vez de um genérico
  "erro de conexão".
- **webOS muito antigo (1.x) com suporte pouco claro**: pelo menos um
  relato de integração de terceiros com TV webOS 1.4 teve compatibilidade
  incerta mesmo sendo tecnicamente webOS (não NetCast). Não há detalhe
  técnico específico o suficiente na comunidade para corrigir algo
  concreto no código por causa disso — registrado aqui como limitação
  conhecida, não resolvida.

## Próximos passos para sair de EXPERIMENTAL

1. Testar contra uma TV LG webOS real: descoberta (SSDP e/ou varredura de
   sub-rede), pareamento (`TV_CONFIRMATION`), D-pad, volume, canal, desligar.
2. Confirmar que reconectar com uma `client-key` já salva realmente pula o
   prompt na TV (comportamento esperado, não observado ainda).
3. Investigar uma fonte confiável de endereço MAC (ex.: XML de descrição
   UPnP às vezes inclui um `<UDN>`/`<serialNumber>` do qual dá pra derivar o
   MAC em alguns modelos) para viabilizar Wake-on-LAN.
4. Se a varredura de sub-rede falhar por a rede não ser /24, considerar ler
   o range real via `DhcpInfo` em vez de assumir /24 (mesmo item pendente
   anotado para a Samsung).
