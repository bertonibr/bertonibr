package com.bertonibr.recomendacoes.data

import java.time.LocalDate
import java.util.Locale

data class Recomendacao(
    val data: LocalDate,
    val corretora: String,
    val ticker: String,
    val recomendacao: String,
    val precoAlvo: Double?,
    val fonte: String = "",
)

val TIPOS = listOf("Compra", "Neutro", "Venda")

data class Consenso(
    val ticker: String,
    val compra: Int,
    val neutro: Int,
    val venda: Int,
    val alvoMedio: Double?,
) {
    val total get() = compra + neutro + venda
    val saldo get() = compra - venda
    val consenso get() = when {
        saldo > 0 -> "Compra"
        saldo < 0 -> "Venda"
        else -> "Neutro"
    }
}

/** Recomendação mais recente de cada corretora. */
fun ultimasPorCorretora(recs: List<Recomendacao>): List<Recomendacao> =
    recs.groupBy { it.corretora }.values.map { lista -> lista.maxBy { it.data } }

fun alvoMedio(recs: List<Recomendacao>): Double? =
    ultimasPorCorretora(recs).mapNotNull { it.precoAlvo }.takeIf { it.isNotEmpty() }?.average()

fun consenso(recs: List<Recomendacao>): List<Consenso> =
    recs.groupBy { it.ticker }.map { (ticker, lista) ->
        val u = ultimasPorCorretora(lista)
        Consenso(
            ticker = ticker,
            compra = u.count { it.recomendacao == "Compra" },
            neutro = u.count { it.recomendacao == "Neutro" },
            venda = u.count { it.recomendacao == "Venda" },
            alvoMedio = alvoMedio(lista),
        )
    }.sortedWith(compareByDescending<Consenso> { it.saldo }.thenByDescending { it.total }.thenBy { it.ticker })

private val B3 = Regex("^[A-Z]{4}\\d{1,2}[A-Z]?$")

/** PETR4 -> PETR4.SA; tickers estrangeiros (AAPL) ou já com sufixo ficam iguais. */
fun tickerYahoo(ticker: String): String {
    val t = ticker.trim().uppercase()
    return if (B3.matches(t)) "$t.SA" else t
}

fun normalizarTipo(s: String): String = s.trim().lowercase().replaceFirstChar { it.uppercase() }

/** Mesmo formato do app de desktop (dados/recomendacoes.csv). Aceita ',' ou ';' (Excel em português). */
object Csv {
    const val CABECALHO = "data,corretora,ticker,recomendacao,preco_alvo,fonte"

    fun ler(texto: String): List<Recomendacao> {
        val linhas = texto.removePrefix("﻿").lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .toList()
        val primeira = linhas.firstOrNull() ?: return emptyList()
        val sep = if (primeira.count { it == ';' } > primeira.count { it == ',' }) ';' else ','
        return linhas
            .filterNot { it.startsWith("data$sep", ignoreCase = true) }
            .mapNotNull { linha ->
                val c = campos(linha, sep)
                if (c.size < 4) return@mapNotNull null
                val data = runCatching { LocalDate.parse(c[0].trim().take(10)) }.getOrNull()
                    ?: return@mapNotNull null
                Recomendacao(
                    data = data,
                    corretora = c[1].trim(),
                    ticker = c[2].trim().uppercase(),
                    recomendacao = normalizarTipo(c[3]),
                    precoAlvo = c.getOrNull(4)?.trim()?.replace(',', '.')?.toDoubleOrNull(),
                    fonte = c.getOrNull(5)?.trim().orEmpty(),
                )
            }
    }

    fun escrever(recs: List<Recomendacao>): String = buildString {
        appendLine(CABECALHO)
        recs.forEach { r ->
            val campos = listOf(
                r.data.toString(),
                r.corretora,
                r.ticker,
                r.recomendacao,
                r.precoAlvo?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                r.fonte,
            )
            appendLine(campos.joinToString(",") { escapar(it) })
        }
    }

    private fun escapar(s: String) =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun campos(linha: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var aspas = false
        var i = 0
        while (i < linha.length) {
            val ch = linha[i]
            when {
                aspas && ch == '"' && i + 1 < linha.length && linha[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> aspas = !aspas
                ch == sep && !aspas -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
