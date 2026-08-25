# Samsung Tizen — pesquisa técnica e status do provider

Segue o roteiro pedido em `PROMPT_NOVOS_RECURSO.md`, seção 19, antes de
implementar `SamsungTizenProvider`.

## Status

**Descoberta, pareamento, conexão e desligar confirmados contra hardware
real** (Samsung UN50RU7100G, linha RU7100 ~2019, Tizen): SSDP/varredura de
sub-rede encontrou a TV, o prompt `TV_CONFIRMATION` apareceu e foi aceito na
tela, o token foi salvo, e `KEY_POWER` desligou a TV de fato.

**Ligar via Wake-on-LAN ainda não confirmado** - a tentativa não teve
efeito num primeiro teste; ver seção 10 abaixo para o diagnóstico em
andamento (log de captura de MAC adicionado, broadcast passou a ser
mandado por dois endereços). D-pad/volume/canal ainda não testados
explicitamente, mas usam o mesmo canal WebSocket já confirmado funcionando
para `KEY_POWER`.

## 1. Documentação oficial

Como no caso da LG, não existe API pública/oficial da Samsung para controle
remoto de terceiros — o protocolo usado aqui é o mesmo que o app oficial
"SmartThings"/"Samsung TV Remote" usa, reverso-projetado pela comunidade.

## 2. Projetos open source consolidados

- **xchwarze/samsung-tv-ws-api** (Python, LGPL-3.0) — referência principal.
- Checagem cruzada em outros clientes (samsungctl, samsung-tv-api,
  integrações Home Assistant/openHAB/Platypush) para confirmar nomes de
  evento e de tecla.

Ver `THIRD_PARTY_NOTICES.md` para a lista completa de arquivos consultados.

## 3. Licença

xchwarze/samsung-tv-ws-api é **LGPL-3.0**. Nenhum trecho de código foi
copiado ou incorporado a este projeto — só fatos de protocolo (formato de
URL, payload JSON, nomes de campo) foram consultados e reimplementados em
Kotlin original, exatamente como já é a prática deste projeto para as
referências Apache/MIT do Android TV. Por não incorporar código-fonte
nenhum, as condições de copyleft da LGPL (relativas a vinculação/derivação
de código) não se aplicam aqui.

## 4. Versões de firmware afetadas

Tizen 2016+ (a partir da linha J/K). O modelo de referência disponível para
teste (UN50RU7100G) é da linha 2019, dentro dessa faixa.

## 5. Portas

- `8001` (WS, sem TLS) / `8002` (WSS, com TLS) — canal de controle
  (`samsung.remote.control`).
- `8001` também serve, sem autenticação, um GET simples (`/api/v2/`) com
  informações do aparelho — nome, modelo e **endereço MAC Wi-Fi**
  (`SamsungInfoClient`). Isso é o que viabiliza Wake-on-LAN para "ligar" a
  TV, algo que não foi possível confirmar de forma confiável para a LG.

## 6. Discovery

SSDP: `ST: urn:samsung.com:device:RemoteControlReceiver:1` — reaproveita a
mesma varredura M-SEARCH genérica (`SsdpDiscovery`) criada originalmente
para a LG, só trocando o Search Target.

**SSDP sozinho não é confiável para TVs Samsung** — achado testando contra
uma UN50RU7100G real (apareceu na rede, mas nunca respondeu ao M-SEARCH), e
confirmado como problema conhecido e documentado (não peculiaridade de um
aparelho): a documentação do binding Samsung TV do openHAB descreve
explicitamente que roteadores/switches podem passar a filtrar tráfego
multicast, tornando o SSDP intermitente, e usuários do fórum Control4
relatam o mesmo sintoma (TV alcançável por ping, invisível ao SSDP) em TVs
novas. Por isso `SamsungTizenProvider.startDiscovery()` roda duas
estratégias em paralelo, não só SSDP: `SamsungSubnetScan` sonda
`http://ip:8001/api/v2/` (a mesma chamada de `SamsungInfoClient`) em cada
endereço da sub-rede /24 do celular — funciona mesmo quando a TV nunca
responde ao multicast.

## 7. Método de pareamento

`TV_CONFIRMATION`, igual à LG: o app abre o WebSocket com um parâmetro
`name` (nome do app, em base64); a TV mostra um prompt de permissão; se
aceito, a TV envia `{"event":"ms.channel.connect","data":{"token":"..."}}`
- o token é persistido (`SamsungKeyStore`) e reenviado (`&token=...` na URL)
em conexões futuras para pular o prompt. Rejeição:
`{"event":"ms.channel.unauthorized"}`. Sem PIN, então `beginPairing()`
sozinho já conclui (ou falha) o pareamento inteiro — mesmo desvio do
contrato original documentado em `TvProvider.kt`.

**Bug real encontrado testando contra a UN50RU7100G**: a primeira versão
deste provider esperava `"MS_CHANNEL_CONNECT_EVENT"`/`"MS_CHANNEL_UNAUTHORIZED"`
(maiúsculo) - o usuário via o prompt na TV, permitia, e o app mesmo assim
nunca conectava (estourava timeout). Causa: essa pesquisa tinha vindo de um
resumo de terceiro do código-fonte, que reportou o **nome da constante
Python** (`Event.MS_CHANNEL_CONNECT_EVENT`), não a **string que a TV
realmente manda no protocolo** (`"ms.channel.connect"`, minúsculo, com
pontos - confirmado direto no arquivo-fonte `event.py`). Corrigido em
`SamsungWsClient.kt`. Lição para as próximas fases: para strings literais
de protocolo (nomes de evento, campos JSON), vale a pena confirmar contra o
arquivo-fonte bruto, não só um resumo/documentação derivada dele.

## 8. Comandos

D-pad, HOME, BACK (`KEY_RETURN`), ENTER, VOLUME_UP/DOWN, MUTE,
CHANNEL_UP/DOWN, POWER (liga/desliga) — todos via
`{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"KEY_X",
"Option":"false","TypeOfRemote":"SendRemoteKey"}}` no canal principal (não
existe, aqui, um socket separado de ponteiro como na LG — o mesmo canal
serve para tudo).

**PLAY_PAUSE não é suportado**, pela mesma razão da LG: `KEY_PLAY` e
`KEY_PAUSE` existem separados, sem alternador único confirmado — reportado
como `NOT_IMPLEMENTED` honesto em vez de adivinhar.

**Ligar a TV é suportado** (diferente da LG) via Wake-on-LAN, usando o MAC
obtido de `SamsungInfoClient` na última vez que a TV respondeu ao GET de
info enquanto ligada. Se o MAC nunca foi descoberto (nenhuma conexão prévia
bem-sucedida), o app reporta `NOT_IMPLEMENTED` honesto em vez de tentar às
cegas. Depende de a TV ter Wake-on-LAN/Wi-Fi habilitado nas configurações
(padrão em modelos recentes, não garantido em todos).

## 9. Funciona sem nuvem?

Sim — WebSocket local nas portas 8001/8002, GET local na 8001, Wake-on-LAN
por broadcast na LAN. Nenhuma dependência de servidor da Samsung.

## 10. Outros problemas conhecidos pesquisados proativamente

Depois que o SSDP se mostrou pouco confiável na prática, valeu revisar
issues reais de xchwarze/samsung-tv-ws-api, samsungctl e da integração
Samsung TV do Home Assistant atrás de outros defeitos já documentados pela
comunidade, em vez de esperar o próximo aparecer sozinho:

- **`usesCleartextTraffic="false"` bloqueava toda a descoberta desta
  fase.** Achado ao testar a varredura de sub-rede da LG contra hardware
  real: o log mostrou `CLEARTEXT communication not permitted by network
  security policy` para toda tentativa `ws://`/`http://`. O
  `AndroidManifest.xml` já vinha com essa flag em `false` desde antes deste
  provider existir - o `SamsungInfoClient.fetch()` (GET em
  `http://host:8001/api/v2/`, usado tanto para identificar a TV quanto para
  a própria descoberta por varredura de sub-rede) nunca teve chance de
  funcionar, silenciosamente, porque o Android bloqueia o socket antes do
  pacote sair do celular. Corrigido junto com a mesma correção na LG
  (`usesCleartextTraffic="true"`) - ver comentário no próprio manifest para
  a justificativa completa. **Isso explica por que a varredura de
  sub-rede/SSDP da Samsung nunca encontrou a TV real mesmo depois da
  correção anterior desta seção.**

- **Porta errada por geração do aparelho** — modelos 2016-2018 só respondem
  em `ws://:8001` (sem TLS); modelos mais novos exigem `wss://:8002`.
  Corrigido: `SamsungWsClient.connect()` tenta `:8002` primeiro e cai para
  `:8001` automaticamente **só quando a conexão TCP/TLS em si falha** —
  nunca depois que o socket abriu (isso evitaria mostrar um segundo prompt
  de confirmação na tela por engano).
- **TVs Orsay (anteriores a ~2016, linha H ou mais antiga)** usam um
  protocolo completamente diferente (Encrypted API v1, XML sobre HTTP na
  porta 8080/8000) — não é uma variação do SSAP, é outra família de
  protocolo. Fora de escopo desta fase (seria um provider à parte). Quando
  as duas portas falham, a mensagem de erro já sugere essa possibilidade em
  vez de um genérico "erro de conexão".
- **Restrição de sub-rede/VLAN**: documentação da própria
  samsung-tv-ws-api confirma que a TV geralmente recusa conexões
  WebSocket vindas de fora da sub-rede/VLAN dela. Não é algo para "corrigir"
  em código — é a mesma premissa que este app já tem desde o início
  (celular e TV na mesma Wi-Fi, ver AGENTS.md) — mas explica um sintoma
  possível (TV encontrada, pareamento falha) se o usuário estiver numa rede
  de convidados/IoT isolada da TV.
- **Prompt de confirmação reaparecendo toda vez**, mesmo com token salvo:
  issues da comunidade apontam a configuração da própria TV
  (`Configurações → Conexões geral → Gerenciador de Conexão de
  Dispositivos → Notificação de Acesso` → mudar para "Primeira vez
  somente", e remover entradas antigas da lista de dispositivos) como causa
  mais comum, não um bug do lado do cliente.
- **Wake-on-LAN parando de funcionar depois de um tempo em espera
  profunda**: vários relatos de que o Wi-Fi da TV se desliga de vez em modo
  standby prolongado a menos que "Conexão rápida"/Wi-Fi em espera esteja
  habilitado nas configurações de energia da TV — mesma ressalva que já
  estava documentada na seção 8 acima, agora com a causa raiz confirmada.
- **Primeira tentativa real de ligar via Wake-on-LAN não teve efeito**
  (desligar funcionou; ligar de volta, não). Duas mudanças em resposta,
  ainda sem confirmação de que resolveram: (1) `WakeOnLan.send()` agora
  manda o pacote mágico para **dois** endereços de broadcast, não só
  `255.255.255.255` - em Wi-Fi, o broadcast dirigido da própria sub-rede
  (`192.168.x.255`) costuma ser roteado de forma mais confiável por
  alguns stacks Android/roteadores; (2) log de diagnóstico adicionado em
  `SamsungTizenProvider` mostrando se o MAC foi capturado na descoberta e
  se o envio do WoL foi tentado - antes disso era impossível saber, pelo
  log, se o problema era "MAC nunca capturado" ou "MAC capturado mas o
  pacote não acordou a TV" (esse último normalmente é a configuração de
  energia da própria TV, não algo que o app controle).

## Próximos passos para sair de EXPERIMENTAL

1. Testar contra a UN50RU7100G real: descoberta (SSDP e/ou varredura de
   sub-rede), pareamento (`TV_CONFIRMATION`), D-pad, volume, canal.
2. Confirmar que o GET `/api/v2/` desse modelo específico realmente devolve
   `wifiMac` (nem todo firmware garante o campo).
3. Testar Wake-on-LAN de fato (TV desligada → ligar pelo app) — depende de
   uma configuração da própria TV que pode estar desligada por padrão.
4. Confirmar que reconectar com um token já salvo pula o prompt na tela
   (comportamento esperado, não observado ainda).
5. Se a varredura de sub-rede falhar por a rede não ser /24, considerar
   ler o range real via `DhcpInfo` em vez de assumir /24.
