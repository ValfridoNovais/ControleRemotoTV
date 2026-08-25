# Politica de Privacidade - MMPG Remote

_Ultima atualizacao: 25 de agosto de 2026_

Esta Politica de Privacidade explica como o **MMPG Remote**, aplicativo de controle remoto para Android TV / Google TV, LG webOS e Samsung Tizen, desenvolvido por **MMPG Novais**, trata informacoes relacionadas ao uso do app, do site e das compras opcionais.

Esta politica foi escrita para atender aos principios de transparencia, necessidade, seguranca e finalidade previstos em legislacoes de protecao de dados, incluindo a **LGPD** (Brasil), e para facilitar a revisao em plataformas de distribuicao como **Google Play** e, se aplicavel no futuro, outras lojas de aplicativos.

## Resumo rapido

O MMPG Remote foi projetado para funcionar de forma local e privada:

- O app nao cria conta de usuario.
- O app nao coleta nome, e-mail, telefone, CPF, documentos, contatos, fotos, arquivos, agenda, microfone, camera ou localizacao geografica.
- O app nao usa anuncios, analytics, rastreamento, SDK de marketing, SDK de redes sociais ou crash reporting de terceiros.
- O app nao possui servidor proprio, backend, API em nuvem ou painel administrativo recebendo dados de uso.
- O controle da TV acontece diretamente entre o celular e a televisao, dentro da mesma rede local.
- Compras opcionais sao processadas pelo Google Play Billing. O app nao recebe dados de cartao, banco ou forma de pagamento.

## Quem e o controlador dos dados

Para os dados que eventualmente estejam sob responsabilidade direta do MMPG Remote, o controlador e:

**MMPG Novais**  
Site: [https://mmpg.online](https://mmpg.online)

Como o app nao mantem conta, cadastro, servidor proprio ou base centralizada de usuarios, a maior parte das informacoes tratadas pelo app permanece exclusivamente no dispositivo do usuario.

## Como o app funciona

O MMPG Remote se comunica com a televisao selecionada na mesma rede local do usuario. Dependendo do modelo da TV, essa comunicacao pode usar protocolos de descoberta e controle compativeis com Android TV / Google TV, LG webOS ou Samsung Tizen.

Essa comunicacao local pode envolver:

- descoberta de TVs disponiveis na rede Wi-Fi;
- exibicao do nome, endereco local ou identificacao tecnica da TV encontrada;
- pareamento com a TV, quando exigido pelo fabricante;
- envio de comandos de controle remoto, como volume, navegacao, ligar/desligar, canal, confirmar, voltar e outros botoes;
- reconexao com TVs ja pareadas.

Essas informacoes sao usadas somente para permitir o funcionamento do controle remoto. Elas nao sao enviadas para servidores do MMPG Remote.

## Dados que o app nao coleta

O MMPG Remote nao coleta nem transmite ao desenvolvedor:

- dados de identificacao pessoal, como nome, e-mail, telefone, CPF, endereco ou data de nascimento;
- dados de localizacao geografica precisa ou aproximada;
- contatos, calendario, SMS, chamadas ou lista de apps instalados;
- fotos, videos, arquivos, documentos ou conteudo da area de transferencia;
- audio do microfone ou imagem da camera;
- historico de navegacao, historico de canais assistidos ou conteudo visto na TV;
- dados de publicidade, perfilamento, marketing ou rastreamento entre apps e sites;
- telemetria, analytics, eventos de uso ou relatorios automaticos de falha enviados a terceiros;
- dados sensiveis, como saude, biometria, religiao, opiniao politica, origem racial ou etnica.

## Dados tratados localmente no aparelho

Para funcionar, o app pode armazenar informacoes tecnicas no armazenamento privado do proprio aplicativo, no dispositivo do usuario:

- identidade criptografica local do app, composta por certificado TLS e chave privada, usada para reconhecer o celular junto a TVs ja pareadas;
- indicador de que uma TV especifica foi pareada;
- nome de exibicao e endereco local da TV pareada, quando disponivel, para mostrar a TV na tela "Minhas TVs";
- preferencias e estados locais de interface;
- contadores locais usados para controlar a exibicao da tela de oferta da versao gratuita.

Esses dados ficam no proprio dispositivo, dentro da area privada do aplicativo. Eles nao sao vendidos, compartilhados ou enviados para servidores do MMPG Remote.

O usuario pode remover esses dados ao:

- esquecer uma TV pareada dentro do app, quando a opcao estiver disponivel;
- redefinir a identidade de pareamento, quando a opcao estiver disponivel;
- limpar os dados do app nas configuracoes do Android;
- desinstalar o aplicativo.

## Permissoes usadas e finalidade

O app solicita ou declara apenas permissoes necessarias para o funcionamento do controle remoto na rede local:

| Permissao | Finalidade |
|---|---|
| `INTERNET` | Permite abrir conexoes de rede. No uso principal do app, essas conexoes sao feitas com TVs na rede local. Tambem permite que o Google Play Billing funcione quando o usuario consulta precos ou realiza compra opcional. |
| `ACCESS_NETWORK_STATE` | Permite verificar o estado da conexao de rede para orientar descoberta e conexao com TVs. |
| `ACCESS_WIFI_STATE` | Permite obter informacoes tecnicas da rede Wi-Fi necessarias para descoberta local de dispositivos. |
| `CHANGE_WIFI_MULTICAST_STATE` | Permite usar multicast/mDNS/SSDP ou mecanismos equivalentes de descoberta de dispositivos na rede local. |
| `NEARBY_WIFI_DEVICES` | Necessaria em versoes recentes do Android para descobrir dispositivos proximos na rede Wi-Fi. O app declara `neverForLocation` e nao usa essa permissao para localizar geograficamente o usuario. |

Nenhuma dessas permissoes e usada para publicidade, rastreamento, venda de dados ou perfilamento.

## Comunicacao local e trafego de rede

O app foi criado para controlar TVs na rede local. Por isso, pode se comunicar com enderecos privados da rede do usuario, como enderecos iniciados por `192.168.x.x`, `10.x.x.x` ou `172.16.x.x` a `172.31.x.x`, alem de nomes de dispositivos anunciados na rede.

Algumas TVs e firmwares antigos so oferecem determinados recursos por conexoes locais sem TLS, como `http://` ou `ws://`. Quando isso ocorre, o app pode usar esse tipo de comunicacao apenas para falar com a TV na rede local. Essa permissao nao significa que o app envie dados pessoais para a internet publica sem criptografia.

Quando ha compra ou consulta de produtos, o fluxo e feito pelo Google Play Billing, de acordo com as politicas e a infraestrutura do Google.

## Pareamento, PIN e certificados

Quando uma TV exige pareamento, o usuario pode precisar digitar no app um codigo ou PIN exibido na televisao.

Esse codigo:

- e usado somente durante a tentativa de pareamento;
- nao e salvo pelo app;
- nao e enviado ao desenvolvedor;
- nao e usado para identificar o usuario.

Para permitir reconexao futura, o app pode manter uma identidade criptografica local. Essa identidade e tecnica, gerada pelo proprio app, e serve para que a TV reconheca o celular ja pareado. A chave privada e armazenada de forma criptografada no dispositivo sempre que a plataforma oferecer suporte apropriado.

## Compras dentro do app

O MMPG Remote pode oferecer plano premium opcional, como compra vitalicia ou assinatura mensal, para remover a tela de oferta exibida na versao gratuita.

As compras sao processadas pelo **Google Play Billing**:

- o app nao recebe numero de cartao, dados bancarios, endereco de cobranca ou credenciais de pagamento;
- o app recebe apenas informacoes tecnicas necessarias para saber se o plano premium esta ativo;
- a compra pode ficar associada a Conta Google usada na Play Store, conforme as politicas do Google;
- cancelamentos, reembolsos, historico de pagamento, metodos de pagamento e assinaturas sao gerenciados pela Google Play Store ou pela Conta Google do usuario;
- o tratamento de dados pelo Google segue a Politica de Privacidade do Google e os termos aplicaveis do Google Play.

Links uteis:

- Politica de Privacidade do Google: [https://policies.google.com/privacy](https://policies.google.com/privacy)
- Google Payments: [https://payments.google.com](https://payments.google.com)
- Google Play: [https://play.google.com](https://play.google.com)

## Tela de oferta da versao gratuita

Na versao gratuita, o app pode exibir periodicamente uma tela convidando o usuario a apoiar o projeto ou adquirir o plano premium.

Para controlar a frequencia dessa tela, o app pode manter contadores e datas localmente no aparelho. Esses dados:

- nao identificam diretamente o usuario;
- nao sao enviados ao desenvolvedor;
- nao sao compartilhados com terceiros;
- podem ser removidos ao limpar os dados do app ou desinstala-lo.

## Compartilhamento de dados

O MMPG Remote nao vende, aluga, licencia ou compartilha dados pessoais do usuario com anunciantes, redes sociais, data brokers ou empresas de marketing.

Podem existir tratamentos por terceiros independentes nas seguintes situacoes:

- **Google Play / Google Play Billing:** quando o usuario baixa o app, consulta compras, assina, compra, cancela ou solicita reembolso pela Play Store.
- **Loja de aplicativos usada para distribuicao:** a loja pode tratar dados de conta, dispositivo, download, pagamento, avaliacao, diagnostico ou seguranca conforme suas proprias politicas.
- **Site mmpg.online:** caso o usuario acesse o site, o provedor de hospedagem, DNS, seguranca ou infraestrutura pode processar dados tecnicos normais de acesso, como endereco IP, data, hora, navegador e logs de servidor.
- **Obrigacao legal:** dados podem ser tratados ou preservados se houver obrigacao legal, ordem de autoridade competente ou necessidade de defesa de direitos.

O app em si nao envia uma base de dados de usuarios ao desenvolvedor.

## Site, links externos e publicacao web

O app pode conter links para paginas externas, como `mmpg.online`, politica de privacidade, suporte, contato ou paginas da loja.

Ao tocar em links externos, o usuario sai do ambiente principal do app e passa a usar o navegador, a loja ou outro servico. Esses ambientes podem ter suas proprias politicas de privacidade, cookies, logs e configuracoes.

O MMPG Remote nao controla sites, lojas, navegadores, provedores de hospedagem, servicos de DNS, ferramentas de seguranca ou plataformas de terceiros.

## Cookies e tecnologias semelhantes

O app Android nao usa cookies para rastreamento, publicidade ou analytics.

Se o site `mmpg.online` disponibilizar paginas web, o funcionamento tecnico da hospedagem pode gerar logs de acesso ou usar cookies estritamente necessarios, dependendo da infraestrutura configurada. Se futuramente forem usados cookies nao essenciais, analytics, formularios, newsletter, publicidade ou ferramentas similares, esta politica devera ser atualizada antes ou no momento da ativacao desses recursos.

## Criancas e adolescentes

O MMPG Remote nao e direcionado especificamente a criancas. O app e uma ferramenta utilitaria de controle remoto para uso domestico.

O app nao solicita idade, nao cria perfis infantis, nao exibe publicidade comportamental e nao coleta conscientemente dados pessoais de criancas ou adolescentes.

Caso um responsavel legal entenda que algum dado pessoal de crianca ou adolescente foi fornecido indevidamente por meio de canal de contato externo, podera solicitar a exclusao pelo site [https://mmpg.online](https://mmpg.online).

## Base legal e finalidades

Quando houver tratamento de dados pessoais sob responsabilidade direta do MMPG Remote, ele ocorrera conforme bases legais aplicaveis, incluindo:

- **execucao de contrato ou procedimentos preliminares:** para permitir que o app funcione como controle remoto e entregue recursos premium comprados pelo usuario;
- **cumprimento de obrigacao legal ou regulatoria:** quando exigido por lei, autoridade competente ou plataforma de distribuicao;
- **legitimo interesse:** para manter seguranca, prevenir abuso, diagnosticar problemas informados pelo usuario e proteger direitos do desenvolvedor, sempre respeitando expectativas razoaveis e minimizacao de dados;
- **consentimento:** quando algum recurso futuro exigir consentimento especifico, claro e revogavel.

## Retencao e exclusao

Como regra, o MMPG Remote nao mantem uma base centralizada de dados pessoais dos usuarios.

Dados locais permanecem no aparelho enquanto o app estiver instalado ou ate que o usuario os remova. Dados relacionados a compras, assinaturas, pagamentos, reembolsos e historico de transacoes sao retidos e gerenciados pelo Google ou pela loja de aplicativos conforme suas proprias politicas e obrigacoes legais.

Se o usuario entrar em contato por e-mail, formulario, loja de aplicativos ou outro canal externo, as mensagens e dados fornecidos voluntariamente poderao ser mantidos pelo tempo necessario para responder, cumprir obrigacoes legais, prevenir fraude, resolver disputas ou defender direitos.

## Seguranca

O MMPG Remote adota medidas tecnicas proporcionais ao tipo de app e aos dados tratados, incluindo:

- armazenamento local privado do Android;
- uso de criptografia para proteger a identidade tecnica de pareamento sempre que aplicavel;
- ausencia de backend proprio recebendo dados de uso;
- ausencia de SDKs de publicidade, analytics e rastreamento;
- minimizacao de dados;
- uso do Google Play Billing para pagamentos, evitando que o app manipule dados financeiros diretamente.

Nenhum sistema e absolutamente imune a falhas. Caso seja identificado incidente relevante envolvendo dados pessoais sob responsabilidade do MMPG Remote, serao adotadas as medidas cabiveis de investigacao, mitigacao e comunicacao conforme a legislacao aplicavel.

## Transferencias internacionais

O app nao envia dados pessoais para servidores proprios no Brasil ou no exterior.

Servicos de terceiros, como Google Play, Google Payments, lojas de aplicativos, hospedagem do site, DNS, navegador ou infraestrutura web, podem processar dados em diferentes paises conforme suas proprias politicas, contratos e bases legais.

## Direitos do usuario

Dependendo da legislacao aplicavel, o usuario pode ter direitos como:

- confirmar se ha tratamento de dados pessoais;
- acessar dados pessoais eventualmente mantidos;
- corrigir dados incompletos, inexatos ou desatualizados;
- solicitar anonimizacao, bloqueio ou eliminacao de dados desnecessarios, excessivos ou tratados em desconformidade;
- solicitar portabilidade, quando aplicavel;
- obter informacoes sobre compartilhamento;
- revogar consentimento, quando o tratamento depender de consentimento;
- solicitar exclusao de dados pessoais tratados com consentimento, observadas obrigacoes legais;
- opor-se a determinado tratamento, quando aplicavel;
- nao sofrer discriminacao pelo exercicio de direitos de privacidade, quando tal garantia for prevista pela lei local.

Como o app nao possui conta nem banco de dados proprio de usuarios, muitas solicitacoes poderao ser atendidas orientando o usuario a remover dados locais no proprio aparelho ou a gerenciar compras diretamente na Conta Google / loja de aplicativos.

Solicitacoes podem ser enviadas pelo site [https://mmpg.online](https://mmpg.online).

## Direitos especificos por regiao

### Brasil - LGPD

Usuarios no Brasil podem exercer os direitos previstos na Lei Geral de Protecao de Dados Pessoais (Lei 13.709/2018), incluindo confirmacao de tratamento, acesso, correcao, eliminacao, informacao sobre compartilhamento e revisao de consentimento quando aplicavel.

### Uniao Europeia, Espaco Economico Europeu e Reino Unido

Quando o GDPR, UK GDPR ou norma equivalente for aplicavel, usuarios podem ter direitos de acesso, retificacao, apagamento, restricao, oposicao, portabilidade e retirada de consentimento. Tambem podem ter o direito de apresentar reclamacao a uma autoridade local de protecao de dados.

### California e outras regioes dos Estados Unidos

Quando leis estaduais de privacidade dos Estados Unidos forem aplicaveis, usuarios podem ter direitos de saber, acessar, corrigir, excluir e optar por nao ter dados vendidos ou compartilhados para publicidade comportamental. O MMPG Remote nao vende dados pessoais e nao compartilha dados para publicidade comportamental.

## Publicidade, venda de dados e rastreamento

O MMPG Remote nao exibe anuncios de terceiros, nao usa identificadores de publicidade, nao vende dados pessoais e nao rastreia o usuario entre aplicativos, sites ou dispositivos.

Se no futuro forem adicionados publicidade, analytics, crash reporting, notificacoes push, contas de usuario, sincronizacao em nuvem, formularios ou qualquer recurso que altere esta pratica, esta politica devera ser atualizada e, quando exigido, o usuario sera informado ou consultado.

## Dados de diagnostico e suporte

O app pode exibir logs tecnicos na propria interface para ajudar o usuario a entender o estado da conexao, pareamento ou compra. Esses logs sao locais e nao sao enviados automaticamente ao desenvolvedor.

Se o usuario decidir enviar uma captura de tela, texto de erro, e-mail ou mensagem de suporte, esse envio sera voluntario e podera conter informacoes fornecidas pelo proprio usuario. Recomenda-se nao enviar senhas, dados bancarios, documentos ou informacoes sensiveis em pedidos de suporte.

## Seguranca de Dados do Google Play

Para fins de preenchimento da secao "Seguranca dos dados" do Google Play, a pratica atual do app e:

- coleta de dados pessoais pelo app: **nao**;
- compartilhamento de dados pessoais pelo app com terceiros: **nao**;
- dados criptografados em transito: **aplicavel ao Google Play Billing e a protocolos que suportam criptografia; alguns controles locais de TV podem depender de protocolos locais do fabricante sem TLS**;
- possibilidade de exclusao de dados: **sim, por limpeza de dados do app, desinstalacao, esquecimento de TV pareada ou solicitacao de suporte quando houver dado fornecido por canal externo**;
- conta de usuario no app: **nao possui**;
- publicidade ou rastreamento: **nao possui**.

A declaracao no Play Console deve sempre permanecer consistente com esta politica e com o comportamento real do aplicativo.

## Alteracoes nesta politica

Esta politica pode ser atualizada para refletir mudancas no app, no site, nas lojas de aplicativos, em requisitos legais ou em recursos futuros.

Quando houver alteracoes relevantes, a data de atualizacao no topo sera revisada. O uso continuado do app apos a publicacao da versao atualizada indicara ciencia da nova politica, salvo quando a lei exigir outra forma de aviso ou consentimento.

## Contato

Duvidas, solicitacoes de privacidade, pedidos de exclusao ou exercicio de direitos podem ser enviados por meio do site:

[https://mmpg.online](https://mmpg.online)

