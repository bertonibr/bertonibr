package com.bertonibr.recomendacoes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Candle(
    val data: LocalDate,
    val abertura: Double,
    val maxima: Double,
    val minima: Double,
    val fechamento: Double,
    val volume: Long,
)

data class Cotacoes(val simbolo: String, val moeda: String, val candles: List<Candle>)

fun mediaMovel(candles: List<Candle>, n: Int): List<Double?> {
    var soma = 0.0
    return candles.indices.map { i ->
        soma += candles[i].fechamento
        if (i >= n) soma -= candles[i - n].fechamento
        if (i >= n - 1) soma / n else null
    }
}

/** Cotações (endpoint público de gráficos) e consenso de analistas (quoteSummary, exige cookie + crumb). */
object Yahoo {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val mutex = Mutex()
    @Volatile private var crumb: String? = null

    init {
        if (CookieHandler.getDefault() == null) CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
    }

    private fun baixar(endereco: String): Pair<Int, String> {
        val con = URL(endereco).openConnection() as HttpURLConnection
        con.connectTimeout = 15_000
        con.readTimeout = 15_000
        con.setRequestProperty("User-Agent", UA)
        try {
            val codigo = con.responseCode
            val corpo = (if (codigo in 200..299) con.inputStream else con.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return codigo to corpo
        } finally {
            con.disconnect()
        }
    }

    private suspend fun obterCrumb(renovar: Boolean): String = mutex.withLock {
        val atual = crumb
        if (atual != null && !renovar) return@withLock atual
        runCatching { baixar("https://fc.yahoo.com") } // apenas para receber o cookie
        val (codigo, texto) = baixar("https://query1.finance.yahoo.com/v1/test/getcrumb")
        val novo = texto.trim()
        if (codigo !in 200..299 || novo.isEmpty() || novo.contains('<') || novo.contains('{')) {
            throw IOException("não foi possível autenticar no Yahoo (HTTP $codigo)")
        }
        crumb = novo
        novo
    }

    private suspend fun resumo(simbolo: String, renovar: Boolean): Pair<Int, String> {
        val c = URLEncoder.encode(obterCrumb(renovar), "UTF-8")
        return baixar(
            "https://query2.finance.yahoo.com/v10/finance/quoteSummary/$simbolo" +
                "?modules=financialData,recommendationTrend,upgradeDowngradeHistory,price&crumb=$c",
        )
    }

    suspend fun consenso(ticker: String): ConsensoAnalistas = withContext(Dispatchers.IO) {
        val simbolo = URLEncoder.encode(tickerYahoo(ticker), "UTF-8")
        val primeira = resumo(simbolo, renovar = false)
        val (codigo, corpo) = if (primeira.first == 401 || primeira.first == 403) resumo(simbolo, renovar = true) else primeira
        AnalistasJson.deYahoo(ticker.trim().uppercase(), corpo, codigo)
    }

    suspend fun historico(ticker: String, periodo: String): Cotacoes = withContext(Dispatchers.IO) {
        val simbolo = URLEncoder.encode(tickerYahoo(ticker), "UTF-8")
        val (codigo, corpo) = baixar("https://query1.finance.yahoo.com/v8/finance/chart/$simbolo?range=$periodo&interval=1d")
        val chart = try {
            JSONObject(corpo).getJSONObject("chart")
        } catch (e: JSONException) {
            throw IOException("resposta inválida do Yahoo (HTTP $codigo)")
        }
        if (!chart.isNull("error")) {
            throw IOException(chart.getJSONObject("error").optString("description", "ticker não encontrado"))
        }
        if (codigo !in 200..299) throw IOException("HTTP $codigo")

        val res = chart.getJSONArray("result").getJSONObject(0)
        val meta = res.getJSONObject("meta")
        val fuso = runCatching { ZoneId.of(meta.optString("exchangeTimezoneName")) }.getOrDefault(ZoneId.of("UTC"))
        val ts = res.optJSONArray("timestamp")
        val q = res.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val o = q.getJSONArray("open")
        val h = q.getJSONArray("high")
        val l = q.getJSONArray("low")
        val c = q.getJSONArray("close")
        val v = q.getJSONArray("volume")

        val candles = (0 until (ts?.length() ?: 0)).mapNotNull { i ->
            if (o.isNull(i) || h.isNull(i) || l.isNull(i) || c.isNull(i)) return@mapNotNull null
            Candle(
                data = Instant.ofEpochSecond(ts!!.getLong(i)).atZone(fuso).toLocalDate(),
                abertura = o.getDouble(i),
                maxima = h.getDouble(i),
                minima = l.getDouble(i),
                fechamento = c.getDouble(i),
                volume = v.optLong(i, 0L),
            )
        }
        Cotacoes(meta.optString("symbol", simbolo), meta.optString("currency", ""), candles)
    }
}
