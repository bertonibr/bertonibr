#!/usr/bin/env python3
"""Volatilidade implícita (IV) da VALE3.

Fontes:
  1. OpLab (api.oplab.com.br) – IV atual e IV Rank oficiais, se houver OPLAB_TOKEN.
  2. Opções.Net.Br – cotações da grade de opções; a IV ATM é calculada aqui via
     Black-Scholes (spot: B3/brapi/Yahoo, taxa: Selic meta do Banco Central).

Saída: resumo em Markdown em stdout (e em $GITHUB_STEP_SUMMARY) e linha
adicionada ao CSV de histórico.
"""
import csv
import datetime as dt
import json
import math
import os
import re
import sys
import urllib.request

TICKER = "VALE3"
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
      "Accept": "application/json, text/plain, */*"}
BRT = dt.timezone(dt.timedelta(hours=-3))
HIST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "historico.csv")


def get_json(url, headers=None):
    req = urllib.request.Request(url, headers={**UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))


# ---------- dados de mercado ----------

def spot():
    """Preço da ação: B3 → brapi → Yahoo (primeira que responder)."""
    fontes = [
        ("B3", f"https://cotacao.b3.com.br/mds/api/v1/instrumentQuotation/{TICKER}",
         lambda d: d["Trad"][0]["scty"]["SctyQtn"]["curPrc"]),
        ("brapi", f"https://brapi.dev/api/quote/{TICKER}",
         lambda d: d["results"][0]["regularMarketPrice"]),
        ("Yahoo", f"https://query2.finance.yahoo.com/v8/finance/chart/{TICKER}.SA?interval=1d&range=5d",
         lambda d: d["chart"]["result"][0]["meta"]["regularMarketPrice"]),
    ]
    erros = []
    for nome, url, f in fontes:
        try:
            return float(f(get_json(url))), nome
        except Exception as e:  # noqa: BLE001
            erros.append(f"{nome}: {e}")
    raise RuntimeError("spot indisponível (" + "; ".join(erros) + ")")


def selic_bcb():
    d = get_json("https://api.bcb.gov.br/dados/serie/bcdata.sgs.432/dados/ultimos/1?formato=json")
    return float(str(d[-1]["valor"]).replace(",", ".")) / 100


def oplab(token):
    d = get_json(f"https://api.oplab.com.br/v3/market/stocks/{TICKER}",
                 {"Access-Token": token})
    pick = lambda *ks: next((d[k] for k in ks if d.get(k) is not None), None)
    return {"iv": pick("iv_current"), "iv_rank": pick("iv_1y_rank"),
            "iv_pct": pick("iv_1y_percentile"), "spot": pick("close", "last")}


# ---------- Black-Scholes ----------

def _ncdf(x):
    return 0.5 * (1 + math.erf(x / math.sqrt(2)))


def bs_price(call, s, k, t, r, vol):
    d1 = (math.log(s / k) + (r + vol * vol / 2) * t) / (vol * math.sqrt(t))
    d2 = d1 - vol * math.sqrt(t)
    if call:
        return s * _ncdf(d1) - k * math.exp(-r * t) * _ncdf(d2)
    return k * math.exp(-r * t) * _ncdf(-d2) - s * _ncdf(-d1)


def implied_vol(call, price, s, k, t, r):
    lo, hi = 1e-4, 5.0
    intrinsic = max(0.0, (s - k * math.exp(-r * t)) if call else (k * math.exp(-r * t) - s))
    if price <= intrinsic or price >= bs_price(call, s, k, t, r, hi):
        return None
    for _ in range(100):
        mid = (lo + hi) / 2
        if bs_price(call, s, k, t, r, mid) > price:
            hi = mid
        else:
            lo = mid
    return (lo + hi) / 2


def business_days(d0, d1):
    n, d = 0, d0
    while d < d1:
        d += dt.timedelta(days=1)
        if d.weekday() < 5:
            n += 1
    return n


# ---------- Opções.Net.Br ----------

def _num(x):
    if x is None or x == "":
        return None
    try:
        return float(str(x).replace(".", "").replace(",", ".")) if isinstance(x, str) and "," in x else float(x)
    except ValueError:
        return None


def opcoes_net_iv(spot, r, today):
    base = "https://opcoes.net.br/listaopcoes/completa"
    meta = get_json(f"{base}?idAcao={TICKER}&listarVencimentos=true&cotacoes=true")
    venc = [v["value"] for v in meta["data"]["vencimentos"]]
    venc = sorted(v for v in venc if dt.date.fromisoformat(v[:10]) > today)

    results = []
    for v in venc:
        exp = dt.date.fromisoformat(v[:10])
        du = business_days(today, exp)
        if du < 5:  # vencimento muito próximo distorce a IV
            continue
        d = get_json(f"{base}?idLista=ML&idAcao={TICKER}&listarVencimentos=false"
                     f"&cotacoes=true&vencimentos={v}")
        rows = d["data"]["cotacoesOpcoes"]
        if os.environ.get("DEBUG"):
            print("DEBUG linha exemplo:", json.dumps(rows[:2], ensure_ascii=False), file=sys.stderr)
        t = du / 252
        # só séries mensais (semanais têm "W<n>" no código) e só negócios do último pregão
        rows = [x for x in rows if not re.search(r"W\d+$", x[0].split("_")[0])]
        ultimo = max((x[11] for x in rows if x[11]), default=None)
        by_strike = {}
        for row in rows:
            # [0]=código, [2]=CALL/PUT, [5]=strike, [8]=último, [9]=negócios, [11]=data últ. negócio
            tipo, k, preco, neg = row[2], _num(row[5]), _num(row[8]), _num(row[9])
            if not k or not preco or not neg or row[11] != ultimo:
                continue
            iv = implied_vol(tipo == "CALL", preco, spot, k, t, r)
            if iv:
                by_strike.setdefault(k, {})[tipo] = (iv, row[0].split("_")[0])
        # 2 strikes mais próximos do spot, média de CALL e PUT disponíveis
        near = sorted(by_strike, key=lambda k: abs(k - spot))[:2]
        ivs = [iv for k in near for iv, _ in by_strike[k].values()]
        if ivs:
            results.append({"venc": exp, "du": du, "iv": sum(ivs) / len(ivs), "data": ultimo,
                            "series": [c for k in near for _, c in by_strike[k].values()]})
        if len(results) == 2:
            break
    if not results:
        raise RuntimeError("nenhuma opção ATM negociada encontrada")

    # IV interpolada para 21 dias úteis (~1 mês), variância linear no tempo
    iv21 = results[0]["iv"]
    if len(results) == 2 and results[0]["du"] < 21 < results[1]["du"]:
        (a, b) = results
        va, vb = a["iv"] ** 2 * a["du"], b["iv"] ** 2 * b["du"]
        iv21 = math.sqrt((va + (vb - va) * (21 - a["du"]) / (b["du"] - a["du"])) / 21)
    return results, iv21


# ---------- main ----------

def main():
    now = dt.datetime.now(BRT)
    today = now.date()
    out = [f"## Volatilidade implícita {TICKER} — {now:%d/%m/%Y %H:%M} (BRT)", ""]
    row = {"data": today.isoformat(), "hora": f"{now:%H:%M}"}
    ok = False

    token = os.environ.get("OPLAB_TOKEN")
    if token:
        try:
            o = oplab(token)
            out.append(f"**OpLab:** IV atual **{o['iv']:.2f}%**"
                       + (f" · IV Rank 1a: {o['iv_rank']:.0f}" if o["iv_rank"] is not None else "")
                       + (f" · IV Percentil 1a: {o['iv_pct']:.0f}" if o["iv_pct"] is not None else ""))
            row.update(iv_oplab=round(o["iv"], 2), iv_rank_oplab=o["iv_rank"])
            ok = True
        except Exception as e:  # noqa: BLE001
            out.append(f"_OpLab indisponível: {e}_")

    try:
        s, fonte_spot = spot()
        try:
            r = selic_bcb()
        except Exception as e:  # noqa: BLE001
            raise RuntimeError(f"Selic BCB: {e}") from e
        try:
            res, iv21 = opcoes_net_iv(s, r, today)
        except Exception as e:  # noqa: BLE001
            raise RuntimeError(f"opcoes.net.br: {e}") from e
        out += ["", f"**Opções.Net.Br (cálculo Black-Scholes ATM):** IV ~21 d.u. **{iv21 * 100:.2f}%**",
                "", f"Spot {TICKER}: R$ {s:.2f} ({fonte_spot}) · Selic: {r * 100:.2f}%", "",
                "| Vencimento | Dias úteis | IV ATM | Séries | Últ. negócio |", "|---|---|---|---|---|"]
        for x in res:
            out.append(f"| {x['venc']:%d/%m/%Y} | {x['du']} | {x['iv'] * 100:.2f}% | {', '.join(x['series'])} | {x['data']} |")
        row.update(iv_atm_21du=round(iv21 * 100, 2), spot=s, selic=round(r * 100, 2))
        ok = True
    except Exception as e:  # noqa: BLE001
        out.append(f"_Opções.Net.Br indisponível: {e}_")

    text = "\n".join(out) + "\n"
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write(text)
    with open(os.environ.get("REPORT_FILE", "relatorio.md"), "w") as f:
        f.write(text)

    if ok:
        cols = ["data", "hora", "iv_atm_21du", "iv_oplab", "iv_rank_oplab", "spot", "selic"]
        new = not os.path.exists(HIST)
        with open(HIST, "a", newline="") as f:
            w = csv.DictWriter(f, fieldnames=cols)
            if new:
                w.writeheader()
            w.writerow({c: row.get(c, "") for c in cols})
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
