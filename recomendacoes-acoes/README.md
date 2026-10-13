# Recomendações de Ações

App local para pesquisar ações recomendadas (compra/venda) por corretoras e ver o gráfico com as recomendações e preços-alvo marcados.

## Instalar e executar

```bash
cd recomendacoes-acoes
python -m venv .venv
source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt
streamlit run app.py
```

Abre em http://localhost:8501.

## Funcionalidades

- **Recomendações**: consenso por ação (contagem de Compra/Neutro/Venda considerando a recomendação mais recente de cada corretora) e preço-alvo médio.
- **Gráfico**: candles + médias móveis 20/50 + volume; linhas pontilhadas nas datas das recomendações e tracejadas nos preços-alvo (verde = compra, vermelho = venda).
- **Consenso Yahoo**: contagem de recomendações e upgrades/downgrades de analistas (boa cobertura para EUA, limitada para B3).
- **Adicionar**: formulário para registrar novas recomendações.
- Filtros por ticker, corretora, tipo de recomendação e período.

## De onde vêm as recomendações

As corretoras brasileiras (XP, BTG, Itaú BBA etc.) não têm API pública das carteiras recomendadas. Por isso elas ficam em `dados/recomendacoes.csv`, que você alimenta pelo app ou editando no Excel:

```
data,corretora,ticker,recomendacao,preco_alvo,fonte
2026-10-01,XP,PETR4,Compra,45.00,Carteira mensal
```

`recomendacao` deve ser `Compra`, `Neutro` ou `Venda`. As linhas atuais do CSV são **exemplos fictícios** — apague-as.

Tickers da B3 (ex.: `PETR4`) recebem o sufixo `.SA` automaticamente; tickers americanos (`AAPL`) funcionam direto.

> Ferramenta de estudo — não é recomendação de investimento.
