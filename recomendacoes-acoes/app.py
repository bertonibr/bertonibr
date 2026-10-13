"""Recomendações de corretoras + gráfico das ações. Execute: streamlit run app.py"""
from datetime import date

import pandas as pd
import plotly.graph_objects as go
import streamlit as st
from plotly.subplots import make_subplots

import dados

st.set_page_config(page_title="Recomendações de Ações", page_icon="📈", layout="wide")

CORES = {"Compra": "#2e7d32", "Neutro": "#9e9e9e", "Venda": "#c62828"}


@st.cache_data(ttl=900, show_spinner=False)
def precos(ticker, periodo):
    return dados.historico_precos(ticker, periodo)


@st.cache_data(ttl=3600, show_spinner=False)
def yahoo(ticker):
    return dados.consenso_yahoo(ticker)


recs = dados.carregar_recomendacoes()

# ---------- Barra lateral ----------
st.sidebar.title("📈 Filtros")
busca = st.sidebar.text_input("Pesquisar ticker", placeholder="ex.: PETR4, VALE3, AAPL").strip().upper()
corretoras = st.sidebar.multiselect("Corretoras", sorted(recs["corretora"].unique()))
tipos = st.sidebar.multiselect("Recomendação", dados.RECOMENDACOES, default=dados.RECOMENDACOES)
periodo = st.sidebar.selectbox("Período do gráfico", ["1mo", "3mo", "6mo", "1y", "2y", "5y"], index=3)

filtrado = recs[recs["recomendacao"].isin(tipos)]
if corretoras:
    filtrado = filtrado[filtrado["corretora"].isin(corretoras)]
if busca:
    filtrado = filtrado[filtrado["ticker"].str.contains(busca, regex=False)]

aba_lista, aba_grafico, aba_nova = st.tabs(["📋 Recomendações", "📊 Gráfico", "➕ Adicionar"])

# ---------- Lista / consenso ----------
with aba_lista:
    cons = dados.consenso(filtrado)
    c1, c2, c3 = st.columns(3)
    c1.metric("Ações", len(cons))
    c2.metric("Consenso de compra", int((cons["consenso"] == "Compra").sum()))
    c3.metric("Consenso de venda", int((cons["consenso"] == "Venda").sum()))

    st.subheader("Consenso por ação")
    st.caption("Considera a recomendação mais recente de cada corretora.")
    st.dataframe(cons, hide_index=True, width="stretch",
                 column_config={"alvo_medio": st.column_config.NumberColumn("Alvo médio", format="%.2f")})

    st.subheader("Todas as recomendações")
    st.dataframe(filtrado, hide_index=True, width="stretch",
                 column_config={"data": st.column_config.DateColumn("Data", format="DD/MM/YYYY"),
                                "preco_alvo": st.column_config.NumberColumn("Preço-alvo", format="%.2f")})

# ---------- Gráfico ----------
with aba_grafico:
    opcoes = sorted(set(filtrado["ticker"]) | ({busca} if busca else set()))
    if not opcoes:
        st.info("Nenhuma ação encontrada. Pesquise um ticker na barra lateral.")
    else:
        ticker = st.selectbox("Ação", opcoes)
        try:
            hist = precos(ticker, periodo)
        except Exception as e:
            hist = pd.DataFrame()
            st.error(f"Erro ao baixar cotações: {e}")

        if hist.empty:
            st.warning(f"Sem cotações para {dados.ticker_yahoo(ticker)}.")
        else:
            ult, ant = hist["Close"].iloc[-1], hist["Close"].iloc[-2] if len(hist) > 1 else hist["Close"].iloc[-1]
            rec_t = recs[recs["ticker"] == ticker]
            m1, m2, m3 = st.columns(3)
            m1.metric("Último preço", f"{ult:.2f}", f"{(ult / ant - 1) * 100:.2f}%")
            if rec_t["preco_alvo"].notna().any():
                alvo = rec_t.sort_values("data").groupby("corretora").tail(1)["preco_alvo"].mean()
                m2.metric("Preço-alvo médio", f"{alvo:.2f}", f"{(alvo / ult - 1) * 100:.1f}% potencial")
            m3.metric("Variação no período", f"{(ult / hist['Close'].iloc[0] - 1) * 100:.1f}%")

            fig = make_subplots(rows=2, cols=1, shared_xaxes=True, row_heights=[0.78, 0.22], vertical_spacing=0.03)
            fig.add_trace(go.Candlestick(x=hist.index, open=hist["Open"], high=hist["High"], low=hist["Low"],
                                         close=hist["Close"], name=ticker), row=1, col=1)
            for mm, cor in (("MM20", "#1e88e5"), ("MM50", "#fb8c00")):
                fig.add_trace(go.Scatter(x=hist.index, y=hist[mm], name=mm, line=dict(width=1.2, color=cor)), row=1, col=1)
            fig.add_trace(go.Bar(x=hist.index, y=hist["Volume"], name="Volume", marker_color="#90a4ae"), row=2, col=1)

            # Marca no gráfico cada recomendação (data + preço-alvo)
            inicio = hist.index[0].tz_localize(None) if hist.index.tz is not None else hist.index[0]
            for _, r in rec_t[rec_t["data"] >= inicio].iterrows():
                cor = CORES.get(r["recomendacao"], "#555")
                fig.add_vline(x=r["data"], line=dict(color=cor, dash="dot", width=1), row=1, col=1)
                if pd.notna(r["preco_alvo"]):
                    fig.add_hline(y=r["preco_alvo"], line=dict(color=cor, dash="dash", width=1), row=1, col=1,
                                  annotation_text=f"{r['corretora']}: {r['recomendacao']} alvo {r['preco_alvo']:.2f}",
                                  annotation_position="top left")

            fig.update_layout(height=650, xaxis_rangeslider_visible=False, margin=dict(l=10, r=10, t=30, b=10),
                              legend=dict(orientation="h", y=1.04))
            st.plotly_chart(fig, width="stretch")

            if not rec_t.empty:
                st.subheader(f"Recomendações das corretoras para {ticker}")
                st.dataframe(rec_t, hide_index=True, width="stretch")

        with st.expander("Consenso de analistas (Yahoo Finance)"):
            try:
                y = yahoo(ticker)
                if y.get("analyst_price_targets"):
                    st.write("**Preços-alvo:**", y["analyst_price_targets"])
                if isinstance(y.get("recommendations"), pd.DataFrame) and not y["recommendations"].empty:
                    st.write("**Contagem de recomendações (strongBuy → strongSell):**")
                    st.dataframe(y["recommendations"], hide_index=True)
                if isinstance(y.get("upgrades_downgrades"), pd.DataFrame) and not y["upgrades_downgrades"].empty:
                    st.write("**Upgrades/downgrades recentes:**")
                    st.dataframe(y["upgrades_downgrades"].head(20))
                if not any(v.empty is False if isinstance(v, pd.DataFrame) else bool(v) for v in y.values()):
                    st.info("Sem dados de analistas para esta ação no Yahoo.")
            except Exception as e:
                st.info(f"Não foi possível obter dados do Yahoo: {e}")

# ---------- Adicionar recomendação ----------
with aba_nova:
    st.caption(f"Os dados ficam salvos em `{dados.CSV_PATH}` — você pode editar o arquivo direto no Excel também.")
    with st.form("nova", clear_on_submit=True):
        a, b, c = st.columns(3)
        d_data = a.date_input("Data", date.today(), format="DD/MM/YYYY")
        d_corr = b.text_input("Corretora")
        d_tick = c.text_input("Ticker")
        a, b, c = st.columns(3)
        d_rec = a.selectbox("Recomendação", dados.RECOMENDACOES)
        d_alvo = b.number_input("Preço-alvo (0 = sem alvo)", min_value=0.0, step=0.5)
        d_fonte = c.text_input("Fonte / observação")
        if st.form_submit_button("Salvar"):
            if not d_corr or not d_tick:
                st.error("Preencha corretora e ticker.")
            else:
                dados.salvar_recomendacao({"data": d_data.isoformat(), "corretora": d_corr.strip(), "ticker": d_tick,
                                           "recomendacao": d_rec, "preco_alvo": d_alvo or None, "fonte": d_fonte})
                st.success("Recomendação salva!")
                st.rerun()

st.sidebar.divider()
st.sidebar.caption("Não é recomendação de investimento. Cotações: Yahoo Finance (podem ter atraso).")
