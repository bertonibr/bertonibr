package com.bertonibr.recomendacoes.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.YearMonth

/** Converte HTML em texto, mantendo linhas e células de tabela (separadas por " | "). */
object HtmlTexto {
    private val ENTIDADES = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "aacute" to "á", "agrave" to "à", "acirc" to "â", "atilde" to "ã", "eacute" to "é", "ecirc" to "ê",
        "iacute" to "í", "oacute" to "ó", "ocirc" to "ô", "otilde" to "õ", "uacute" to "ú", "ccedil" to "ç",
        "Aacute" to "Á", "Eacute" to "É", "Iacute" to "Í", "Oacute" to "Ó", "Uacute" to "Ú", "Ccedil" to "Ç",
        "Atilde" to "Ã", "Otilde" to "Õ", "ndash" to "–", "mdash" to "—", "rsquo" to "’", "lsquo" to "‘",
        "ldquo" to "“", "rdquo" to "”",
    )

    fun decodificar(s: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> ENTIDADES[e] ?: m.value
        }
    }

    fun converter(html: String): String {
        // Conteúdo de páginas Next.js/JSON embutido (ex.: __NEXT_DATA__) também pode conter a carteira
        val json = Regex("<script[^>]*type=\"application/(?:json|ld\\+json)\"[^>]*>(.*?)</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(html).joinToString("\n") { m ->
                m.groupValues[1].replace("\\n", "\n").replace(Regex("\\\\u([0-9a-fA-F]{4})")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
            }
        val texto = html
            .replace(Regex("<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            .replace(Regex("</t[dh]>", RegexOption.IGNORE_CASE), " | ")
            .replace(Regex("<br\\s*/?>|</(p|tr|li|h\\d|div|table|section|article)>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
        return (decodificar(texto) + "\n" + json)
            .lines()
            .map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim().trim('|').trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    fun links(html: String, base: String): List<String> =
        Regex("(?:href|src)=\"([^\"]+)\"", RegexOption.IGNORE_CASE).findAll(html)
            .map { decodificar(it.groupValues[1]) }
            .map { absoluto(it, base) }
            .distinct()
            .toList()

    fun absoluto(link: String, base: String): String = when {
        link.startsWith("http") -> link
        link.startsWith("//") -> "https:$link"
        link.startsWith("/") -> Regex("^https?://[^/]+").find(base)!!.value + link
        else -> base.substringBeforeLast('/') + "/" + link
    }
}

/** Carteira recomendada mensal de ações da Genial Investimentos (site Genial Analisa). */
object Genial {
    const val CORRETORA = "Genial Investimentos"
    private const val SITE = "https://analisa.genialinvestimentos.com.br"
    private const val LISTA = "$SITE/carteiras-recomendadas/renda-variavel"
    private val MESES = listOf(
        "janeiro", "fevereiro", "marco", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
    )
    private val NOMES = MESES.map { if (it == "marco") "março" else it }
    private val LINK_CARTEIRA = Regex("carteira-recomendada-de-acoes-([a-z]+)-(?:de-)?(\\d{4})")

    data class Carteira(val mes: YearMonth, val url: String, val extracao: Extracao) {
        val rotulo get() = rotuloMes(mes)
    }

    fun rotuloMes(m: YearMonth) = "${NOMES[m.monthValue - 1]}/${m.year}"

    private fun urlsDoMes(m: YearMonth): List<String> {
        val mes = MESES[m.monthValue - 1]
        return listOf(
            "$LISTA/carteira-recomendada-de-acoes-$mes-de-${m.year}",
            "$LISTA/carteira-recomendada-de-acoes-$mes-${m.year}",
        )
    }

    private fun mesDoLink(link: String): YearMonth? {
        val m = LINK_CARTEIRA.find(link) ?: return null
        val i = MESES.indexOf(m.groupValues[1].replace("ç", "c"))
        return if (i < 0) null else YearMonth.of(m.groupValues[2].toInt(), i + 1)
    }

    /** Procura a carteira mais recente: primeiro na listagem do site, depois montando a URL do mês. */
    suspend fun buscar(ctx: Context, progresso: (String) -> Unit): Carteira = withContext(Dispatchers.IO) {
        suspend fun aviso(s: String) = withContext(Dispatchers.Main) { progresso(s) }
        aviso("Procurando a carteira mais recente…")

        val candidatos = mutableListOf<Pair<YearMonth, String>>()
        runCatching { baixarTexto(LISTA) }.getOrNull()?.takeIf { it.first in 200..299 }?.let { (_, html) ->
            HtmlTexto.links(html, LISTA).mapNotNullTo(candidatos) { l -> mesDoLink(l)?.let { it to l } }
        }
        val hoje = YearMonth.now()
        for (m in listOf(hoje, hoje.minusMonths(1))) urlsDoMes(m).forEach { candidatos += m to it }

        var ultimoErro: String? = null
        for ((mes, url) in candidatos.distinctBy { it.second }.sortedByDescending { it.first }) {
            val (codigo, html) = try {
                baixarTexto(url)
            } catch (e: IOException) {
                ultimoErro = e.message
                continue
            }
            if (codigo !in 200..299) {
                if (codigo != 404) ultimoErro = "HTTP $codigo"
                continue
            }
            aviso("Lendo carteira de ${rotuloMes(mes)}…")
            var ext = Extrator.extrairTexto(HtmlTexto.converter(html))
            if (ext.itens.size < 3) ext = lerAnexos(ctx, html, url) { aviso(it) } ?: ext
            return@withContext Carteira(mes, url, ext.copy(corretora = CORRETORA))
        }
        throw IOException("não encontrei a carteira no site da Genial" + (ultimoErro?.let { " ($it)" } ?: ""))
    }

    /** Se a carteira estiver num PDF ou numa imagem, baixa e lê com OCR. */
    private suspend fun lerAnexos(ctx: Context, html: String, url: String, aviso: suspend (String) -> Unit): Extracao? {
        val links = HtmlTexto.links(html, url)
        val pdfs = links.filter { it.substringBefore('?').endsWith(".pdf", ignoreCase = true) }
        val imagens = links.filter { l ->
            val n = Extrator.normalizar(l)
            Regex("\\.(png|jpe?g|webp)(\\?|$)").containsMatchIn(n) && ("carteira" in n || "acoes" in n)
        }
        for ((i, link) in (pdfs + imagens).take(4).withIndex()) {
            aviso("Lendo anexo ${i + 1}…")
            val ext = if (link in pdfs) "pdf" else "img"
            val arquivo = File(ctx.cacheDir, "genial_$i.$ext")
            runCatching { baixarArquivo(link, arquivo) }.getOrNull() ?: continue
            val paginas = runCatching { Leitor.ler(ctx, Uri.fromFile(arquivo)) {} }.getOrNull() ?: continue
            val r = Extrator.extrair(paginas)
            if (r.itens.size >= 3) return r
        }
        return null
    }

    private fun conectar(endereco: String): HttpURLConnection =
        (URL(endereco).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9")
        }

    private fun baixarTexto(endereco: String): Pair<Int, String> {
        val con = conectar(endereco)
        try {
            val codigo = con.responseCode
            val corpo = if (codigo in 200..299) con.inputStream.bufferedReader().use { it.readText() } else ""
            return codigo to corpo
        } finally {
            con.disconnect()
        }
    }

    private fun baixarArquivo(endereco: String, destino: File) {
        val con = conectar(endereco)
        try {
            if (con.responseCode !in 200..299) throw IOException("HTTP ${con.responseCode}")
            con.inputStream.use { entrada -> destino.outputStream().use { entrada.copyTo(it) } }
        } finally {
            con.disconnect()
        }
    }
}
