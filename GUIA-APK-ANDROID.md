# Como o instalador (APK) do app é gerado e como instalar no celular

Este guia explica como o app **Recomendações** (pasta `android-app/`) vira um arquivo `.apk` e como instalá-lo num celular Android, sem Play Store e sem precisar de computador.

---

## 1. Visão geral

```
Código (android-app/)  ──push──▶  GitHub Actions  ──compila──▶  Recomendacoes.apk
                                   (servidor do GitHub,            │
                                    já tem Android SDK)            ▼
                                                        Release "apk-latest" no GitHub
                                                                   │
                                                     celular baixa pelo link e instala
```

- O app é nativo, escrito em **Kotlin + Jetpack Compose**, e compilado com **Gradle**.
- A compilação não roda no seu computador: quem faz é o **GitHub Actions**, de graça para repositórios públicos.
- A cada alteração em `android-app/`, o GitHub compila de novo e substitui o APK no mesmo link.

---

## 2. Peças do projeto

| Arquivo | Para que serve |
|---|---|
| `android-app/settings.gradle.kts`, `build.gradle.kts` | Configuração do projeto Gradle (plugins Android e Kotlin) |
| `android-app/app/build.gradle.kts` | Configuração do app: `applicationId`, versão, `minSdk 26` (Android 8+), dependências |
| `android-app/gradlew`, `gradle/wrapper/` | *Gradle Wrapper*: baixa a versão certa do Gradle (8.9) automaticamente |
| `android-app/app/debug.keystore` | Chave que **assina** o APK (o Android só instala APK assinado) |
| `android-app/app/src/main/...` | Código Kotlin, manifesto, ícone |
| `.github/workflows/android.yml` | Receita que o GitHub executa para compilar e publicar o APK |

### Assinatura (por que existe o `debug.keystore`)

Todo APK precisa ser assinado. Se cada compilação usasse uma chave nova, o Android recusaria instalar a versão nova por cima da antiga ("conflito com pacote existente"). Por isso a chave fica **fixa e versionada** no repositório. Ela foi criada uma única vez com:

```bash
keytool -genkeypair -keystore app/debug.keystore \
  -storepass android -alias androiddebugkey -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

E está configurada em `app/build.gradle.kts`:

```kotlin
signingConfigs {
    getByName("debug") {
        storeFile = file("debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
}
```

> É uma chave de *debug* (senha pública "android"), adequada para uso pessoal. Para publicar na Play Store seria preciso criar uma chave de *release* própria e guardá-la como *secret* do GitHub, nunca no repositório.

---

## 3. A receita do GitHub Actions

Arquivo `.github/workflows/android.yml`:

```yaml
name: App Android

on:
  push:
    paths:
      - 'android-app/**'                # roda quando o app muda
      - '.github/workflows/android.yml'
  workflow_dispatch:                    # permite rodar manualmente

permissions:
  contents: write                       # necessário para criar a release

jobs:
  apk:
    runs-on: ubuntu-latest              # máquina do GitHub (já vem com Android SDK)
    defaults:
      run:
        working-directory: android-app
    steps:
      - uses: actions/checkout@v4       # baixa o código

      - uses: actions/setup-java@v4     # instala o Java 17
        with:
          distribution: temurin
          java-version: '17'

      - uses: gradle/actions/setup-gradle@v4   # cache do Gradle

      - name: Testes e build do APK
        run: |
          chmod +x gradlew
          ./gradlew --no-daemon testDebugUnitTest assembleDebug
          cp app/build/outputs/apk/debug/app-debug.apk Recomendacoes.apk

      - uses: actions/upload-artifact@v4      # guarda o APK no resultado da execução
        with:
          name: Recomendacoes-apk
          path: android-app/Recomendacoes.apk

      - name: Publicar APK na release "apk-latest"
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          gh release delete apk-latest --yes --cleanup-tag || true
          gh release create apk-latest Recomendacoes.apk \
            --prerelease \
            --target "$GITHUB_SHA" \
            --title "App Android (última versão)" \
            --notes "APK gerado automaticamente a partir do commit ${GITHUB_SHA::7}."
```

Passo a passo do que acontece:

1. **checkout**: o GitHub baixa o código do repositório.
2. **setup-java**: instala o Java 17, que o Gradle e o plugin Android exigem.
3. **`./gradlew testDebugUnitTest assembleDebug`**:
   - roda os testes automáticos (`app/src/test/`); se algum falhar, para tudo e nenhum APK é publicado;
   - compila o app e gera `app/build/outputs/apk/debug/app-debug.apk`, já assinado com o `debug.keystore`.
4. **upload-artifact**: guarda uma cópia do APK na página da execução (aba *Actions*).
5. **release `apk-latest`**: apaga a release anterior e cria outra com o APK novo. Assim o **link de download nunca muda**.

### Como disparar uma nova compilação

- **Automático:** qualquer `git push` que altere algo em `android-app/`.
- **Manual:** GitHub → aba **Actions** → **App Android** → **Run workflow**.
- **Acompanhar:** aba **Actions** do repositório. Verde = APK publicado; vermelho = clique para ver o erro.

---

## 4. Instalar no celular

### Link direto (sempre a última versão)

```
https://github.com/bertonibr/bertonibr/releases/download/apk-latest/Recomendacoes.apk
```

Página da release: https://github.com/bertonibr/bertonibr/releases/tag/apk-latest

### Passos

1. **Abra o link no Chrome do celular.** Se o link abrir dentro do WhatsApp ou de outro app, copie e cole no Chrome, porque navegadores internos costumam não baixar arquivos.
2. Se aparecer *"Este tipo de arquivo pode ser perigoso"*, toque em **Baixar mesmo assim**.
3. Quando terminar o download (~53 MB), toque na notificação ou abra **Chrome → ⋮ → Downloads → Recomendacoes.apk**. Também dá para abrir pelo app **Arquivos / Meus arquivos → Downloads**.
4. Na primeira vez o Android pede permissão para **instalar apps desconhecidos**: toque em **Configurações**, ative para o Chrome (ou para o app Arquivos) e volte.
5. Se o **Play Protect** avisar, toque em **Mais detalhes → Instalar mesmo assim**. Esse aviso aparece só porque o app não veio da Play Store.
6. Toque em **Instalar** → **Abrir**.

### Atualizar

Baixe pelo **mesmo link** e instale por cima. Como a chave de assinatura é sempre a mesma, a atualização preserva os dados: recomendações cadastradas, carteira da Genial importada e cache do consenso.

---

## 5. Alternativa: compilar no próprio computador

Necessário: **Android Studio** (já inclui o Android SDK e o Java).

1. Android Studio → **Open** → selecione a pasta `android-app`.
2. Aguarde o Gradle sincronizar (baixa as dependências na primeira vez).
3. Menu **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. O arquivo sai em `android-app/app/build/outputs/apk/debug/app-debug.apk`.
5. Passe para o celular (cabo USB, Google Drive, e-mail) e instale como no item 4, ou, com o celular conectado por USB e a *Depuração USB* ativada, clique em **Run ▶**.

Pela linha de comando, com o Android SDK instalado e a variável `ANDROID_HOME` configurada:

```bash
cd android-app
./gradlew assembleDebug            # gera o APK
./gradlew installDebug             # instala direto no celular conectado por USB
```

---

## 6. Problemas comuns

| Sintoma | Solução |
|---|---|
| Não acho o arquivo baixado | Chrome → ⋮ → **Downloads**, ou app **Arquivos → Downloads**. Se não estiver lá, baixe de novo pelo Chrome (não pelo navegador interno de outro app). |
| "App não instalado" / "conflito com pacote" | Uma versão com outra assinatura está instalada (por exemplo, compilada no Android Studio com outra chave). Desinstale a antiga e instale de novo. |
| Botão "Instalar" não aparece | Falta liberar **Instalar apps desconhecidos** para o app que abriu o APK (Chrome ou Arquivos). |
| Execução vermelha no GitHub Actions | Abra a execução na aba **Actions** e veja o passo que falhou: em geral é um teste que quebrou ou um erro de compilação. Nesse caso nenhum APK novo é publicado e o link continua com a versão anterior. |
| Android antigo | O app exige **Android 8.0 ou superior** (`minSdk 26`). |
