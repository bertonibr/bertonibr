# Volatilidade implícita diária – VALE3

GitHub Action (`.github/workflows/vol-vale3.yml`) que roda **de segunda a sexta às 12:00 (Brasília)** e:

1. Calcula a IV ATM da VALE3 (Black-Scholes) a partir das cotações de opções do **Opções.Net.Br**, com spot do **Yahoo Finance** e Selic do **Banco Central (SGS 432)**. Reporta a IV dos 2 próximos vencimentos e a IV interpolada para ~21 dias úteis.
2. Se o secret `OPLAB_TOKEN` existir, busca também a IV atual, IV Rank e IV Percentil oficiais da **OpLab**.
3. Abre uma issue `IV VALE3 – dd/mm/aaaa` atribuída a você (o GitHub envia e-mail/push) e fecha a do dia anterior.
4. Salva o histórico em `vol-vale3/historico.csv`.

## Secrets opcionais (Settings → Secrets and variables → Actions)

| Secret | Uso |
|---|---|
| `OPLAB_TOKEN` | Token da API OpLab (IV + IV Rank oficiais) |
| `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` | Envia o resumo também no Telegram |

## Rodar manualmente

Aba **Actions → Volatilidade implícita VALE3 → Run workflow**, ou localmente:

```bash
python vol-vale3/vol_vale3.py
```

> O `schedule` do GitHub pode atrasar alguns minutos e só roda a partir do branch padrão (`main`).
