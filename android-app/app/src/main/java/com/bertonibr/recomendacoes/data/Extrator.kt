package com.bertonibr.recomendacoes.data

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

/** Uma palavra reconhecida pelo OCR, com a posição na página. */
data class Trecho(val texto: String, val esq: Float, val topo: Float, val dir: Float, val base: Float) {
    val cx get() = (esq + dir) / 2
    val cy get() = (topo + base) / 2
    val altura get() = base - topo
}

data class ItemExtraido(
    val ticker: String,
    val recomendacao: String?,
    val precoAlvo: Double?,
    val linha: String,
)

data class Extracao(val corretora: String?, val itens: List<ItemExtraido>, val textoCompleto: String)

/** Encontra tickers, recomendação e preço-alvo no texto de carteiras/relatórios de corretoras. */
object Extrator {
    private val SUFIXOS = setOf(3, 4, 5, 6, 7, 8, 11, 31, 32, 33, 34, 35, 39)
    private val TICKER = Regex("\\b([A-Z]{4})\\s?(\\d{1,2})\\b")
    private val NUMERO = Regex("(?<![\\d,.])(\\d{1,3}(?:\\.\\d{3})*,\\d{2}|\\d+\\.\\d{2})(?![\\d,.])(?!\\s?%)")

    private val PALAVRAS = listOf(
        "Compra" to listOf("compra", "comprar", "buy", "outperform", "overweight"),
        "Venda" to listOf("venda", "vender", "sell", "underperform", "underweight"),
        "Neutro" to listOf("neutro", "neutra", "neutral", "manter", "hold", "market perform", "equal weight"),
    )

    private val CORRETORAS = listOf(
        "XP Investimentos" to "xp", "BTG Pactual" to "btg", "Itaú BBA" to "itau bba", "Itaú" to "itau",
        "Bradesco BBI" to "bradesco bbi", "Ágora" to "agora investimentos", "Safra" to "safra",
        "Santander" to "santander", "Genial" to "genial", "Inter" to "inter invest", "Órama" to "orama",
        "Rico" to "rico", "Clear" to "clear corretora", "Toro" to "toro", "Nu Invest" to "nu invest",
        "BB Investimentos" to "bb investimentos", "Guide" to "guide investimentos", "Ativa" to "ativa investimentos",
        "Mirae" to "mirae", "Warren" to "warren", "Empiricus" to "empiricus", "Modal" to "modal",
        "Planner" to "planner", "Necton" to "necton", "Terra" to "terra investimentos", "Eleven" to "eleven",
        "Suno" to "suno", "Levante" to "levante", "Nord" to "nord",
    )

    fun normalizar(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    /** Junta as palavras que estão na mesma altura da página (linhas de tabela). */
    fun agruparLinhas(trechos: List<Trecho>): List<List<Trecho>> {
        val linhas = mutableListOf<MutableList<Trecho>>()
        for (t in trechos.sortedBy { it.cy }) {
            val ultima = linhas.lastOrNull()
            if (ultima != null && abs(t.cy - ultima.map { it.cy }.average()) < max(t.altura, ultima[0].altura) * 0.5f) {
                ultima.add(t)
            } else {
                linhas.add(mutableListOf(t))
            }
        }
        return linhas.map { l -> l.sortedBy { it.esq } }
    }

    fun extrair(paginas: List<List<Trecho>>): Extracao {
        val itens = mutableListOf<ItemExtraido>()
        val texto = StringBuilder()
        for (pagina in paginas) {
            if (pagina.isEmpty()) continue
            val largura = pagina.maxOf { it.dir } - pagina.minOf { it.esq }
            var colunaAlvo: Float? = null
            for (linha in agruparLinhas(pagina)) {
                val textoLinha = linha.joinToString(" ") { it.texto }
                texto.appendLine(textoLinha)
                val tickers = tickers(textoLinha)
                if (tickers.isEmpty()) {
                    linha.firstOrNull { normalizar(it.texto).let { n -> "alvo" in n || "target" in n } }
                        ?.let { colunaAlvo = it.cx }
                    continue
                }
                val unico = tickers.size == 1
                tickers.forEach { t ->
                    itens += ItemExtraido(
                        ticker = t,
                        recomendacao = if (unico) recomendacao(textoLinha) else null,
                        precoAlvo = if (unico) alvo(linha, textoLinha, colunaAlvo, largura) else null,
                        linha = textoLinha,
                    )
                }
            }
        }
        val unicos = itens.groupBy { it.ticker }.map { (ticker, l) ->
            ItemExtraido(
                ticker,
                l.firstNotNullOfOrNull { it.recomendacao },
                l.firstNotNullOfOrNull { it.precoAlvo },
                (l.firstOrNull { it.recomendacao != null || it.precoAlvo != null } ?: l[0]).linha,
            )
        }
        return Extracao(corretora(texto.toString()), unicos, texto.toString())
    }

    private val SAIDA = Regex("\\b(sai|saem|sairam|saida|saidas|retirad\\w*|exclu\\w*|remov\\w*|deixa\\w*)\\b")

    /**
     * Extrai de texto já convertido de HTML: células separadas por " | " viram colunas.
     * Se houver tabela com 3+ tickers, usa só a tabela; senão ignora linhas de ações que "saíram".
     */
    fun extrairTexto(texto: String): Extracao {
        val linhas = texto.lines().map { it.trim() }.filter { it.isNotBlank() }
        val tabela = linhas.filter { '|' in it }
        val usar = if (tabela.flatMap { tickers(it) }.distinct().size >= 3) {
            linhas.filter { '|' in it || tickers(it).isEmpty() }
        } else {
            linhas.filterNot { tickers(it).isNotEmpty() && SAIDA.containsMatchIn(normalizar(it)) }
        }
        val trechos = usar.flatMapIndexed { y, linha ->
            linha.split('|').map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { x, celula ->
                Trecho(celula, x * 100f, y * 20f, x * 100f + 90f, y * 20f + 10f)
            }
        }
        return extrair(listOf(trechos)).copy(textoCompleto = texto)
    }

    fun tickers(texto: String): List<String> =
        TICKER.findAll(texto)
            .filter { it.groupValues[2].toInt() in SUFIXOS }
            .map { it.groupValues[1] + it.groupValues[2] }
            .distinct()
            .toList()

    fun recomendacao(texto: String): String? {
        val n = normalizar(texto)
        return PALAVRAS.firstOrNull { (_, chaves) -> chaves.any { Regex("\\b$it\\b").containsMatchIn(n) } }?.first
    }

    private fun numero(s: String): Double? =
        (if (',' in s) s.replace(".", "").replace(',', '.') else s).toDoubleOrNull()

    private fun alvo(linha: List<Trecho>, textoLinha: String, colunaAlvo: Float?, largura: Float): Double? {
        if (colunaAlvo != null) {
            val candidato = linha
                .mapNotNull { t -> NUMERO.find(t.texto)?.let { m -> t to m.groupValues[1] } }
                .minByOrNull { (t, _) -> abs(t.cx - colunaAlvo) }
            if (candidato != null && abs(candidato.first.cx - colunaAlvo) < largura * 0.12f) {
                return numero(candidato.second)
            }
        }
        val n = normalizar(textoLinha)
        val pos = listOf("alvo", "target").map { n.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return null
        return NUMERO.find(textoLinha, pos)?.let { numero(it.groupValues[1]) }
    }

    fun corretora(texto: String): String? {
        val n = normalizar(texto)
        return CORRETORAS
            .map { (nome, chave) -> nome to Regex("\\b$chave\\b").findAll(n).count() }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }
}
