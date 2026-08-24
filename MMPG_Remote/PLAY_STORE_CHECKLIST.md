# Checklist para publicar o MMPG Remote na Google Play

## Já pronto (código/assets)

- [x] Ícone do launcher (legado + adaptativo, todas as densidades) a partir de `logo_mmpg_remoto.jpg`.
- [x] `versionName`/`versionCode` definidos (`app/build.gradle.kts`) — `1.0.1` / `1` (primeiro envio à loja usa `versionCode = 1`; incremente a cada envio seguinte).
- [x] `PRIVACY_POLICY.md` — política de privacidade (a Play Store exige uma URL pública, não um arquivo no repositório — veja abaixo).
- [x] Workflow de release assinado (`.github/workflows/build-release.yml`) — gera um `.apk` assinado. A Play Store atual pede **Android App Bundle (`.aab`)**, não `.apk`, para apps novos — veja "Pendências técnicas" abaixo.
- [x] `AndroidManifest.xml` sem permissões excessivas, todas justificadas na política de privacidade.
- [x] App funciona 100% offline/LAN — nenhuma dependência de servidor para revisão funcionar.

## Pendências técnicas (posso fazer, mas ainda não fiz)

- [ ] **Gerar `.aab` em vez de `.apk` para o release**: o workflow atual roda `assembleRelease` (gera `.apk`). Adicionar um job/step `bundleRelease` (gera `app-release.aab`) usando os mesmos secrets de assinatura. É uma mudança pequena no workflow — posso fazer quando você quiser.
- [ ] **Hospedar a política de privacidade numa URL pública**: a Play Store exige um link (não aceita arquivo). Sugestão: publicar o conteúdo de `PRIVACY_POLICY.md` em `mmpg.online/privacidade` (ou uma página similar no seu site) — o texto já está pronto para copiar.

## Passos manuais (só você pode fazer)

1. **Criar conta de desenvolvedor Google Play** — mmpg.online/play.google.com/console, taxa única de US$25.
2. **Criar o app no Play Console**, preencher:
   - Título, descrição curta/longa (posso ajudar a escrever se quiser).
   - Categoria (sugestão: Ferramentas ou Utilitários).
   - Ícone (já pronto, 512×512 — exportar de `logo_mmpg_remoto.jpg` ou usar `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`).
   - **Gráfico de destaque** (1024×500) — ainda não existe, preciso que você peça se quiser que eu gere um a partir da logo.
   - Ao menos 2 capturas de tela do app em uso (celular) — precisam ser tiradas manualmente com o app rodando.
3. **Formulário de Segurança de Dados** (Data safety) no Play Console — com base em `PRIVACY_POLICY.md`: nenhum dado é coletado ou compartilhado.
4. **Classificação de conteúdo** (content rating) — questionário padrão do Play Console; para este app, deve resultar em classificação livre/básica.
5. **Link da política de privacidade** — cole a URL pública do passo de hospedagem acima.
6. **Enviar o `.aab` assinado** — baixar o artifact do workflow `Build Signed Release APK` (depois de eu adicionar o `bundleRelease`) e subir no Play Console.
7. **Revisão do Google** — normalmente leva de algumas horas a poucos dias.

## Observação importante sobre o escopo do app

Esta é a primeira versão pública. Vale deixar claro na descrição da loja que o app **só funciona com TVs Android TV/Google TV** (não Samsung/Tizen, não LG/WebOS) e que celular e TV precisam estar na mesma rede Wi-Fi — evita avaliações negativas por expectativa equivocada.
