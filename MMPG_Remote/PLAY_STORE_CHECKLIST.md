# Checklist para publicar o MMPG Remote na Google Play

## Já pronto (código/assets)

- [x] Ícone do launcher (legado + adaptativo, todas as densidades) a partir de `logo_mmpg_remoto.jpg`.
- [x] `versionName`/`versionCode` definidos (`app/build.gradle.kts`) — `1.0.1` / `1` (primeiro envio à loja usa `versionCode = 1`; incremente a cada envio seguinte).
- [x] `PRIVACY_POLICY.md` — política de privacidade (a Play Store exige uma URL pública, não um arquivo no repositório — veja abaixo).
- [x] Workflow de release assinado (`.github/workflows/build-release.yml`, agora `Build Signed Release (AAB + APK)`) — gera `app-release.aab` (o que se envia ao Play Console) e `app-release.apk` (para instalar/testar direto num aparelho), ambos assinados e verificados (`jarsigner`/`apksigner`).
- [x] `AndroidManifest.xml` sem permissões excessivas, todas justificadas na política de privacidade.
- [x] App funciona 100% offline/LAN — nenhuma dependência de servidor para revisão funcionar.

## Google Play Billing (paywall premium)

Código já implementado (`BillingManager.kt`, `TvBridge.kt`, `paywall.js`) — vitalício R$ 24,90 (INAPP) ou assinatura R$ 3,90/mês (SUBS), paywall fullscreen com 10 variantes rotativas a partir da 2ª sessão de uso (depois em toda sessão seguinte), cooldown de 10min. "Sessão" inclui abrir o app do zero e voltar do segundo plano depois de 5min parado (`MainActivity.onResume`). Falta só o cadastro manual no Play Console:

1. **Ativar o Payments Profile** (Play Console → Configurações → Pagamentos) — precisa de conta bancária brasileira vinculada ao seu CPF e do formulário de informações fiscais.
2. **Criar os dois produtos**, com estes IDs exatos (já são os que o código espera em `BillingManager.PRODUCT_LIFETIME`/`PRODUCT_MONTHLY`):
   - Produto no app (INAPP) `mmpg_remote_lifetime` — não consumível, R$ 24,90.
   - Assinatura (SUBS) `mmpg_remote_mensal` — plano base mensal, R$ 3,90.
3. **Adicionar sua própria conta como "testador de licença"** (Play Console → Configurações → Testadores de licença) antes de testar compras no app — sem isso, compras reais seriam cobradas de verdade.
4. Os preços exibidos no app **vêm direto do que for cadastrado aqui** — o código nunca fixa R$ 24,90/R$ 3,90, só busca o preço localizado via `getProductPrices()`.

**Antes dos produtos existirem no Play Console**: o BillingClient falha ao conectar com `DEVELOPER_ERROR` (confirmado em teste real) — isso por si só não quebra nada (o app trata como "não-premium" com timeout de 5s, ver `BillingManager.queryEntitlement`/`fetchPrices`), mas explica por que o paywall pode não aparecer em testes feitos antes do cadastro: sem esse timeout, a checagem de entitlement ficava pendurada esperando uma conexão que `enableAutoServiceReconnection()` tentava de novo pra sempre, e o evento nunca chegava à WebView.

## Pendências técnicas (posso fazer, mas ainda não fiz)

- [ ] **Hospedar a política de privacidade numa URL pública**: a Play Store exige um link (não aceita arquivo). `site/privacidade/index.html` já está pronto para publicar — falta só subir esse arquivo em `mmpg.online/privacidade` (passo de infraestrutura do site, fora do escopo deste repositório).

## Passos manuais (só você pode fazer)

1. **Criar conta de desenvolvedor Google Play** — mmpg.online/play.google.com/console, taxa única de US$25.
2. **Criar o app no Play Console**, preencher:
   - Título, descrição curta/longa (posso ajudar a escrever se quiser).
   - Categoria (sugestão: Ferramentas ou Utilitários).
   - Ícone 512×512 — gerado em `store/icon-512.png` a partir de `logo_mmpg_remoto.jpg`.
   - Gráfico de destaque 1024×500 — gerado em `store/feature-graphic.png`.
   - Ao menos 2 capturas de tela do app em uso (celular) — precisam ser tiradas manualmente com o app rodando (feitas).
3. **Formulário de Segurança de Dados** (Data safety) no Play Console — com base em `PRIVACY_POLICY.md`: nenhum dado é coletado ou compartilhado.
4. **Classificação de conteúdo** (content rating) — questionário padrão do Play Console; para este app, deve resultar em classificação livre/básica.
5. **Link da política de privacidade** — cole a URL pública do passo de hospedagem acima.
6. **Enviar o `.aab` assinado** — baixar o artifact `MMPG-Remote-release-aab` do workflow `Build Signed Release (AAB + APK)` e subir no Play Console.
7. **Revisão do Google** — normalmente leva de algumas horas a poucos dias.

## Observação importante sobre o escopo do app

Vale deixar claro na descrição da loja que o app controla **Android TV/Google TV** (validado contra TV real), **Samsung Tizen 2016+** (validado contra TV real — descoberta, pareamento e desligar confirmados; ligar via Wake-on-LAN ainda em teste) e, de forma **experimental**, **LG webOS** (implementado mas nunca testado contra uma TV real). Não afirmar suporte "confirmado" para LG na ficha da loja enquanto isso não mudar. Em todos os casos, celular e TV precisam estar na mesma rede Wi-Fi — evita avaliações negativas por expectativa equivocada.
