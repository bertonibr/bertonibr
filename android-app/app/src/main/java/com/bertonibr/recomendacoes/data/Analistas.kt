package com.bertonibr.recomendacoes.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class Tendencia(val compraForte: Int, val compra: Int, val neutro: Int, val venda: Int, val vendaForte: Int) {
    val total get() = compraForte + compra + neutro + venda + vendaForte
}

data class Mudanca(
    val data: LocalDate,
    val firma: String,
    val de: String,
    val para: String,
    val acao: String,
    val alvo: Double?,
    val alvoAnterior: Double?,
)

/** Consenso de analistas do Yahoo Finance para um ativo. */
data class ConsensoAnalistas(
    val ticker: String,
    val chave: String?,
    val media: Double?,
    val analistas: Int?,
    val alvoMedio: Double?,
    val alvoMin: Double?,
    val alvoMax: Double?,
    val precoAtual: Double?,
    val moeda: String,
    val tendencia: Tendencia?,
    val historico: List<Mudanca>,
    val quando: Long,
) {
    val temCobertura get() = chave != null || (tendencia?.total ?: 0) > 0 || alvoMedio != null
    val rotulo get() = rotuloChave(chave)
    val tipo get() = tipoChave(chave)
    val potencial get() = if (alvoMedio != null && precoAtual != null && precoAtual > 0) (alvoMedio / precoAtual - 1) * 100 else null
}

fun rotuloChave(chave: String?) = when (chave) {
    "strong_buy" -> "Compra forte"
    "buy" -> "Compra"
    "hold" -> "Neutro"
    "underperform" -> "Venda"
    "sell" -> "Venda forte"
    else -> "Sem consenso"
}

fun tipoChave(chave: String?) = when (chave) {
    "strong_buy", "buy" -> "Compra"
    "underperform", "sell" -> "Venda"
    else -> "Neutro"
}

fun traduzirAcao(acao: String) = when (acao.lowercase()) {
    "up" -> "Elevou"
    "down" -> "Rebaixou"
    "main" -> "Manteve"
    "init" -> "Iniciou cobertura"
    "reit" -> "Reiterou"
    else -> acao
}

/** Universo padrão da aba Analistas: principais ações do Ibovespa (tickers inválidos são ignorados). */
val IBOVESPA = listOf(
    "ABEV3", "ALOS3", "ASAI3", "AZZA3", "B3SA3", "BBAS3", "BBDC3", "BBDC4", "BBSE3", "BEEF3", "BPAC11",
    "BRAP4", "BRAV3", "BRFS3", "BRKM5", "CMIG4", "CMIN3", "COGN3", "CPFE3", "CPLE6", "CSAN3", "CSMG3",
    "CSNA3", "CXSE3", "CYRE3", "DIRR3", "EGIE3", "ELET3", "ELET6", "EMBR3", "ENEV3", "ENGI11", "EQTL3",
    "FLRY3", "GGBR4", "GOAU4", "HAPV3", "HYPE3", "IGTI11", "IRBR3", "ISAE4", "ITSA4", "ITUB4", "KLBN11",
    "LREN3", "MGLU3", "MOTV3", "MRFG3", "MRVE3", "MULT3", "NATU3", "PETR3", "PETR4", "PETZ3", "POMO4",
    "PRIO3", "PSSA3", "RADL3", "RAIL3", "RAIZ4", "RDOR3", "RECV3", "RENT3", "SANB11", "SBSP3", "SLCE3",
    "SMFT3", "SMTO3", "STBP3", "SUZB3", "TAEE11", "TIMS3", "TOTS3", "UGPA3", "USIM5", "VALE3", "VAMO3",
    "VBBR3", "VIVA3", "VIVT3", "WEGE3", "YDUQ3",
)

object AnalistasJson {
    private const val MAX_HISTORICO = 15

    /** Converte a resposta do endpoint quoteSummary do Yahoo. */
    fun deYahoo(ticker: String, corpo: String, codigo: Int, agora: Long = System.currentTimeMillis()): ConsensoAnalistas {
        val raiz = try {
            JSONObject(corpo).getJSONObject("quoteSummary")
        } catch (e: JSONException) {
            throw IOException("resposta inválida do Yahoo (HTTP $codigo)")
        }
        if (!raiz.isNull("error")) {
            throw IOException(raiz.getJSONObject("error").optString("description", "sem dados"))
        }
        val r = raiz.optJSONArray("result")?.optJSONObject(0) ?: throw IOException("sem dados de analistas")
        val fin = r.optJSONObject("financialData")
        val preco = r.optJSONObject("price")

        val trend = r.optJSONObject("recommendationTrend")?.optJSONArray("trend")
        val atual = trend?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("period") == "0m" } }
        val tendencia = atual?.let {
            Tendencia(
                num(it, "strongBuy")?.toInt() ?: 0, num(it, "buy")?.toInt() ?: 0, num(it, "hold")?.toInt() ?: 0,
                num(it, "sell")?.toInt() ?: 0, num(it, "strongSell")?.toInt() ?: 0,
            )
        }

        val hist = r.optJSONObject("upgradeDowngradeHistory")?.optJSONArray("history")
        val historico = hist?.let { arr ->
            (0 until minOf(arr.length(), MAX_HISTORICO)).mapNotNull { i ->
                val h = arr.optJSONObject(i) ?: return@mapNotNull null
                val epoch = num(h, "epochGradeDate")?.toLong() ?: return@mapNotNull null
                Mudanca(
                    data = Instant.ofEpochSecond(epoch).atOffset(ZoneOffset.UTC).toLocalDate(),
                    firma = h.optString("firm"),
                    de = h.optString("fromGrade"),
                    para = h.optString("toGrade"),
                    acao = h.optString("action"),
                    alvo = num(h, "currentPriceTarget")?.takeIf { it > 0 },
                    alvoAnterior = num(h, "priorPriceTarget")?.takeIf { it > 0 },
                )
            }
        }.orEmpty()

        return ConsensoAnalistas(
            ticker = ticker,
            chave = fin?.optString("recommendationKey")?.takeIf { it.isNotBlank() && it != "none" },
            media = num(fin, "recommendationMean"),
            analistas = num(fin, "numberOfAnalystOpinions")?.toInt()?.takeIf { it > 0 },
            alvoMedio = num(fin, "targetMeanPrice"),
            alvoMin = num(fin, "targetLowPrice"),
            alvoMax = num(fin, "targetHighPrice"),
            precoAtual = num(fin, "currentPrice") ?: num(preco, "regularMarketPrice"),
            moeda = preco?.optString("currency").orEmpty().ifBlank { fin?.optString("financialCurrency").orEmpty() },
            tendencia = tendencia,
            historico = historico,
            quando = agora,
        )
    }

    /** Lê número direto ou no formato {"raw": 1.2, "fmt": "1.20"}. */
    private fun num(o: JSONObject?, chave: String): Double? {
        val v = o?.opt(chave) ?: return null
        val d = when (v) {
            is Number -> v.toDouble()
            is JSONObject -> v.optDouble("raw", Double.NaN)
            else -> Double.NaN
        }
        return d.takeUnless { it.isNaN() }
    }

    fun escrever(lista: Collection<ConsensoAnalistas>): String = JSONArray().apply {
        lista.forEach { c ->
            put(JSONObject().apply {
                put("ticker", c.ticker)
                put("chave", c.chave)
                put("media", c.media)
                put("analistas", c.analistas)
                put("alvoMedio", c.alvoMedio)
                put("alvoMin", c.alvoMin)
                put("alvoMax", c.alvoMax)
                put("precoAtual", c.precoAtual)
                put("moeda", c.moeda)
                put("quando", c.quando)
                c.tendencia?.let {
                    put("tendencia", JSONArray(listOf(it.compraForte, it.compra, it.neutro, it.venda, it.vendaForte)))
                }
                put("historico", JSONArray().apply {
                    c.historico.forEach { m ->
                        put(JSONObject().apply {
                            put("data", m.data.toString())
                            put("firma", m.firma)
                            put("de", m.de)
                            put("para", m.para)
                            put("acao", m.acao)
                            put("alvo", m.alvo)
                            put("alvoAnterior", m.alvoAnterior)
                        })
                    }
                })
            })
        }
    }.toString()

    fun ler(texto: String): List<ConsensoAnalistas> {
        val arr = JSONArray(texto)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val t = o.optJSONArray("tendencia")
            val h = o.optJSONArray("historico") ?: JSONArray()
            ConsensoAnalistas(
                ticker = o.getString("ticker"),
                chave = o.optString("chave").takeIf { !o.isNull("chave") && it.isNotBlank() },
                media = dbl(o, "media"),
                analistas = dbl(o, "analistas")?.toInt(),
                alvoMedio = dbl(o, "alvoMedio"),
                alvoMin = dbl(o, "alvoMin"),
                alvoMax = dbl(o, "alvoMax"),
                precoAtual = dbl(o, "precoAtual"),
                moeda = o.optString("moeda"),
                tendencia = t?.let { Tendencia(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3), it.getInt(4)) },
                historico = (0 until h.length()).map { j ->
                    val m = h.getJSONObject(j)
                    Mudanca(
                        LocalDate.parse(m.getString("data")), m.optString("firma"), m.optString("de"),
                        m.optString("para"), m.optString("acao"), dbl(m, "alvo"), dbl(m, "alvoAnterior"),
                    )
                },
                quando = o.optLong("quando"),
            )
        }
    }

    private fun dbl(o: JSONObject, chave: String): Double? =
        if (o.isNull(chave)) null else o.optDouble(chave, Double.NaN).takeUnless { it.isNaN() }
}
