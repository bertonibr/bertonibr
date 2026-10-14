package com.bertonibr.recomendacoes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
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

/** Cotações diárias pelo endpoint público de gráficos do Yahoo Finance. */
object Yahoo {
    suspend fun historico(ticker: String, periodo: String): Cotacoes = withContext(Dispatchers.IO) {
        val simbolo = URLEncoder.encode(tickerYahoo(ticker), "UTF-8")
        val url = URL("https://query1.finance.yahoo.com/v8/finance/chart/$simbolo?range=$periodo&interval=1d")
        val con = url.openConnection() as HttpURLConnection
        con.connectTimeout = 15_000
        con.readTimeout = 15_000
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
        try {
            val codigo = con.responseCode
            val corpo = (if (codigo in 200..299) con.inputStream else con.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
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
        } finally {
            con.disconnect()
        }
    }
}
