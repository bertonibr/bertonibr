"""Camada de dados: recomendações (CSV local) e cotações/consenso (Yahoo Finance)."""
from __future__ import annotations

import re
from pathlib import Path

import pandas as pd

CSV_PATH = Path(__file__).parent / "dados" / "recomendacoes.csv"
COLUNAS = ["data", "corretora", "ticker", "recomendacao", "preco_alvo", "fonte"]
RECOMENDACOES = ["Compra", "Neutro", "Venda"]

_B3 = re.compile(r"^[A-Z]{4}\d{1,2}[A-Z]?$")


def ticker_yahoo(ticker: str) -> str:
    """PETR4 -> PETR4.SA; tickers estrangeiros (AAPL) ou já com sufixo ficam iguais."""
    t = ticker.strip().upper()
    return f"{t}.SA" if _B3.match(t) else t


def carregar_recomendacoes(path: Path = CSV_PATH) -> pd.DataFrame:
    if not path.exists():
        return pd.DataFrame(columns=COLUNAS)
    df = pd.read_csv(path, parse_dates=["data"])
    df["ticker"] = df["ticker"].str.strip().str.upper()
    df["recomendacao"] = df["recomendacao"].str.strip().str.capitalize()
    df["preco_alvo"] = pd.to_numeric(df["preco_alvo"], errors="coerce")
    return df.sort_values("data", ascending=False).reset_index(drop=True)


def salvar_recomendacao(registro: dict, path: Path = CSV_PATH) -> None:
    novo = pd.DataFrame([{c: registro.get(c) for c in COLUNAS}])
    novo["ticker"] = novo["ticker"].str.strip().str.upper()
    path.parent.mkdir(parents=True, exist_ok=True)
    novo.to_csv(path, mode="a", header=not path.exists(), index=False)


def consenso(df: pd.DataFrame) -> pd.DataFrame:
    """Uma linha por ticker com a recomendação mais recente de cada corretora."""
    if df.empty:
        return pd.DataFrame(columns=["ticker", *RECOMENDACOES, "total", "saldo", "alvo_medio", "consenso"])
    ultima = df.sort_values("data").groupby(["ticker", "corretora"]).tail(1)
    tab = ultima.pivot_table(index="ticker", columns="recomendacao", values="corretora",
                             aggfunc="count", fill_value=0)
    tab = tab.reindex(columns=RECOMENDACOES, fill_value=0)
    tab["total"] = tab.sum(axis=1)
    tab["saldo"] = tab["Compra"] - tab["Venda"]
    tab["alvo_medio"] = ultima.groupby("ticker")["preco_alvo"].mean().round(2)
    tab["consenso"] = tab["saldo"].map(lambda s: "Compra" if s > 0 else "Venda" if s < 0 else "Neutro")
    return tab.reset_index().sort_values(["saldo", "total"], ascending=False).reset_index(drop=True)


def historico_precos(ticker: str, periodo: str = "1y") -> pd.DataFrame:
    import yfinance as yf

    df = yf.Ticker(ticker_yahoo(ticker)).history(period=periodo, auto_adjust=False)
    if not df.empty:
        df["MM20"] = df["Close"].rolling(20).mean()
        df["MM50"] = df["Close"].rolling(50).mean()
    return df


def consenso_yahoo(ticker: str) -> dict:
    """Consenso de analistas do Yahoo (cobertura limitada para ações da B3)."""
    import yfinance as yf

    t = yf.Ticker(ticker_yahoo(ticker))
    out = {}
    for nome in ("recommendations", "upgrades_downgrades", "analyst_price_targets"):
        try:
            out[nome] = getattr(t, nome)
        except Exception:
            out[nome] = None
    return out
