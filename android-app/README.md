# Recomendações — app Android

Versão Android (Kotlin + Jetpack Compose) do app de recomendações de corretoras.

## Instalar no celular

1. No celular, abra **https://github.com/bertonibr/bertonibr/releases/tag/apk-latest**
2. Baixe o arquivo **Recomendacoes.apk**
3. Abra o arquivo e permita "instalar apps de fontes desconhecidas" quando o Android pedir.

O APK é compilado automaticamente pelo GitHub Actions (`.github/workflows/android.yml`) a cada alteração em `android-app/`. Novas versões instalam por cima da anterior sem perder os dados.

## Funcionalidades

- **Aba Analistas (automática)**: consenso de analistas do Yahoo Finance para as principais ações do Ibovespa + as suas — recomendação (compra forte → venda), nº de analistas, preço-alvo médio e potencial. Atualiza sozinho a cada 12 h (ou no botão ↻).
- **Detalhe da ação**: distribuição das recomendações, preço-alvo médio/mín/máx (linha roxa no gráfico) e mudanças recentes por banco (elevou/rebaixou, alvo anterior → novo).
- **Aba Minhas**: pesquisa por ticker, filtros Compra/Neutro/Venda e por corretora, consenso por ação (recomendação mais recente de cada corretora) e preço-alvo médio.
- **Gráfico**: candles, médias móveis 20/50, volume e linhas de preço-alvo das corretoras; toque ou arraste no gráfico para ver os valores do dia. Períodos de 1 mês a 5 anos.
- Pesquise qualquer ticker (ex.: `WEGE3`, `AAPL`) para ver o gráfico mesmo sem recomendação cadastrada.
- **+ → Tirar foto / Imagem ou print / Ler PDF**: lê a carteira recomendada ou relatório da corretora com OCR no próprio celular (offline) e encontra tickers, recomendação (compra/neutro/venda, outperform etc.) e preço-alvo. Você revisa antes de salvar.
- Também dá para **compartilhar** um PDF ou imagem do WhatsApp/e-mail direto para o app ("Ler recomendações").
- **+ → Digitar** adiciona manualmente; a lixeira remove.
- **Menu ⋮ → Importar/Exportar CSV**: mesmo formato do app de desktop (`recomendacoes-acoes/dados/recomendacoes.csv`), aceita CSV do Excel com `;`.

Cotações: Yahoo Finance (podem ter atraso). As recomendações iniciais são exemplos fictícios.

## Compilar localmente

Abra a pasta `android-app` no Android Studio, ou:

```bash
cd android-app
./gradlew assembleDebug   # requer Android SDK (ANDROID_HOME)
```

> Ferramenta de estudo — não é recomendação de investimento.
