# Política de Privacidade — MMPG Remote

_Última atualização: 24 de agosto de 2026_

O MMPG Remote é um aplicativo de controle remoto para Android TV / Google TV, desenvolvido por MMPG Novais.

## Resumo

**O MMPG Remote não coleta, armazena, transmite ou compartilha nenhum dado pessoal.** Ele não tem servidor próprio, não usa conta de usuário, não tem SDK de analytics, anúncios ou rastreamento de terceiros, e não envia nenhuma informação para fora da rede local (Wi-Fi) do usuário.

## Como o app funciona

O MMPG Remote se comunica **exclusivamente na rede local (LAN)** do usuário, diretamente com a televisão selecionada, usando o protocolo padrão Android TV Remote Protocol v2 do Google. Não existe backend, servidor na nuvem ou API externa envolvida em nenhuma etapa do uso do app.

## Permissões usadas e por quê

| Permissão | Uso |
|---|---|
| `INTERNET` | Necessária pelo Android para abrir conexões de rede (socket TCP/TLS) mesmo quando o destino é um dispositivo na mesma rede local — não é usada para acessar a internet pública. |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE` | Necessárias para a descoberta de TVs na rede local via mDNS (protocolo padrão de descoberta de dispositivos). |
| `NEARBY_WIFI_DEVICES` (Android 13+) | Necessária pelo sistema Android para permitir a busca de dispositivos próximos na rede Wi-Fi — não é usada para localização geográfica (o app declara `neverForLocation`). |

Nenhuma dessas permissões é usada para coletar, enviar ou compartilhar dados do usuário.

## Dados armazenados no aparelho

O app guarda, apenas no armazenamento privado do próprio aplicativo (nunca compartilhado, nunca enviado a servidores):

- Um identificador criptográfico (certificado TLS) usado para reconhecer o celular junto às TVs já pareadas — não contém nenhuma informação pessoal, é gerado aleatoriamente no próprio aparelho.
- Um sinalizador simples por TV indicando se ela já foi pareada.

Nenhum desses dados sai do aparelho em nenhuma circunstância. Desinstalar o app remove tudo.

## Dados de terceiros

O MMPG Remote não usa nenhum SDK de terceiros (analytics, anúncios, crash reporting, etc.). O código-fonte é aberto para inspeção.

## Contato

Dúvidas sobre esta política podem ser enviadas através do site [mmpg.online](https://mmpg.online).

## Alterações a esta política

Qualquer alteração futura a esta política será publicada nesta mesma página, com a data de atualização revisada no topo.
