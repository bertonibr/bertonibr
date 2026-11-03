# PDF Studio

Leitor e editor de PDF para desktop, em JavaFX + Apache PDFBox, com resumo e perguntas usando o Claude.

## Recursos

- **Visualizar**: miniaturas, zoom (Ctrl + roda do mouse), ajustar à largura, ir para página, localizar texto (Ctrl+F), arrastar e soltar um PDF na janela.
- **Organizar páginas**: girar, excluir, reordenar (arrastando as miniaturas), juntar PDFs, inserir imagens, extrair intervalo (ex.: `1-3, 5`), dividir em um PDF por página.
- **Anotar e assinar**: texto, marca-texto, desenho à mão livre, corretivo (retângulo branco), assinatura desenhada ou carregada de imagem. Desfazer com Ctrl+Z. Ao salvar, as anotações são gravadas no PDF.
- **Editar texto**: com a ferramenta *Editar texto*, selecione um trecho; o texto original aparece para edição e é substituído (coberto + reescrito).
- **Exportar**: texto (.txt), páginas como PNG (150 DPI), criar PDF a partir de imagens.
- **Ler em voz alta**: página, documento, texto extraído ou a resposta do Claude. Usa a voz do sistema (Windows: nativo; macOS: `say`; Linux: `espeak-ng`).
- **Claude**: resumir o documento ou a página e fazer perguntas sobre o PDF (o PDF é enviado inteiro, incluindo imagens/tabelas).

## Configurar o Claude

O app usa a **API do Claude**, que precisa de uma chave (`sk-ant-...`) criada em <https://console.anthropic.com> → *API Keys*.
A assinatura do claude.ai (Pro/Max) não vale para a API; a API é cobrada por uso.

Informe a chave em **Claude → Configurar chave de API** (fica salva só no seu computador) ou defina a variável de ambiente `ANTHROPIC_API_KEY`.

## Requisitos

- JDK 21+
- Maven

## Executar

```bash
mvn javafx:run
```

Gerar um `.jar` executável:

```bash
mvn package
java -jar target/pdf-studio.jar [arquivo.pdf]
```

## Android

A versão Android nativa (Kotlin + Jetpack Compose) fica em `android/`, com os mesmos recursos adaptados para toque. Ela também aparece em "Abrir com" quando você toca num PDF em outro app.

- **APK pronto:** baixe direto em <https://github.com/bertonibr/bertonibr/releases/latest/download/pdf-studio.apk> e instale no celular (permita "instalar apps desconhecidos").
- **Testes automáticos:** a cada mudança, o GitHub Actions roda os testes num emulador Android (motor de PDF, anotações, páginas, senhas, Claude com servidor simulado e fluxos da interface). O APK só é publicado se todos passarem. Para rodar localmente com um aparelho/emulador conectado: `./gradlew connectedDebugAndroidTest`.
- **Recentes e Favoritos:** a tela inicial lista os PDFs abertos (miniatura, nome, data e páginas), como no Adobe. Tocar reabre na página onde você parou; o menu ⋮ de cada item favorita ou tira da lista. Arquivos recebidos pelo WhatsApp ou por "Abrir com" ficam guardados numa cópia privada para poderem ser reabertos depois. O botão Voltar fecha o documento e volta para a lista.
- **Tradução:** ao selecionar um trecho (segurando o dedo), a tradução para o português aparece sozinha; também há "Traduzir página" no menu ⋮. Usa o tradutor do Google no próprio celular (ML Kit): gratuito, sem chave, baixa o pacote do idioma uma vez (~30 MB) e depois funciona até sem internet.
- **Compilar localmente:** abra a pasta `android/` no Android Studio ou rode `./gradlew assembleDebug`.
- Requer Android 8.0 ou mais recente.

## Estrutura

| Arquivo | Função |
|---|---|
| `pdfstudio/ui/MainWindow.java` | Interface (menus, barras, visualizador, painel do Claude) |
| `pdfstudio/model/PdfDocumentModel.java` | Operações no PDF (páginas, texto, exportação, salvar) |
| `pdfstudio/model/Flattener.java` | Grava as anotações no conteúdo do PDF |
| `pdfstudio/service/ClaudeService.java` | Chamada à API do Claude (streaming) |
| `pdfstudio/service/SpeechService.java` | Leitura em voz alta |
