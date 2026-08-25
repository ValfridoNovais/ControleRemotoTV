# Assinatura do APK

## 1. Criar a chave uma única vez

Em uma máquina com JDK:

```bash
keytool -genkeypair -v \
  -keystore mmpg-remote.keystore \
  -alias mmpgremote \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Guarde essa chave fora do repositório. Perder a chave impede atualizar o mesmo aplicativo assinado.

## 2. Converter para Base64

Linux/macOS:

```bash
base64 -w 0 mmpg-remote.keystore > keystore.base64
```

PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("mmpg-remote.keystore")) | Set-Content keystore.base64
```

## 3. Criar GitHub Secrets

No repositório:
Settings → Secrets and variables → Actions

Crie os 4 secrets abaixo (os 4 são obrigatórios — o workflow falha cedo, com uma mensagem indicando qual secret falta, se algum não estiver configurado):
- `ANDROID_KEYSTORE_BASE64` (conteúdo do `keystore.base64` gerado no passo 2)
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Depois de copiar o conteúdo de `keystore.base64` para o secret, apague o arquivo local (`mmpg-remote.keystore` e `keystore.base64` já estão cobertos pelo `.gitignore`, mas não devem ficar soltos em disco por precaução — nenhum dos dois deve ser commitado ou anexado a PRs/issues).

## 4. Build

Execute manualmente o workflow `Build Signed Release (AAB + APK)` (aba Actions → workflow_dispatch).

O workflow roda os testes unitários, valida que os 4 secrets estão presentes, decodifica o keystore apenas em um arquivo temporário no runner (`$RUNNER_TEMP`), compila e assina o release em dois formatos — `app-release.aab` (o que se envia ao Play Console) e `app-release.apk` (útil para instalar direto num aparelho e testar antes de enviar) —, verifica a assinatura de cada um (`apksigner` no APK, `jarsigner` no AAB) e, ao final (mesmo em caso de falha), apaga o keystore temporário.

O keystore não deve ser commitado.
