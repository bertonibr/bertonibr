package com.bertonibr.recomendacoes

import com.bertonibr.recomendacoes.data.Extrator
import com.bertonibr.recomendacoes.data.HtmlTexto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenialTest {
    @Test
    fun tabelaHtml() {
        val html = """
            <html><head><title>x</title><script>var a = "VALE3";</script></head><body>
            <h1>Carteira Recomendada de A&ccedil;&otilde;es &ndash; Outubro</h1>
            <p>Saem da carteira EMBR3 e TOTS3.</p>
            <table>
              <tr><th>Empresa</th><th>Ticker</th><th>Setor</th><th>Peso</th><th>Pre&ccedil;o-alvo</th></tr>
              <tr><td>Petrobras</td><td>PETR4</td><td>Petr&oacute;leo</td><td>10%</td><td>R$&nbsp;45,00</td></tr>
              <tr><td>Ita&uacute;</td><td>ITUB4</td><td>Bancos</td><td>10%</td><td>42,50</td></tr>
              <tr><td>Sabesp</td><td>SBSP3</td><td>Saneamento</td><td>10%</td><td>-</td></tr>
            </table>
            <aside>Leia também: carteira de dividendos com TAEE11</aside>
            </body></html>
        """.trimIndent()
        val texto = HtmlTexto.converter(html)
        assertTrue(texto.contains("Carteira Recomendada de Ações – Outubro"))
        val r = Extrator.extrairTexto(texto)
        assertEquals(listOf("PETR4", "ITUB4", "SBSP3"), r.itens.map { it.ticker })
        assertEquals(45.0, r.itens[0].precoAlvo!!, 1e-9)
        assertEquals(42.5, r.itens[1].precoAlvo!!, 1e-9)
        assertEquals(null, r.itens[2].precoAlvo)
    }

    @Test
    fun textoSemTabelaIgnoraSaidas() {
        val texto = "Para outubro, entram PRIO3 e BBAS3.\nSaem da carteira EMBR3 e TOTS3.\nMantemos WEGE3."
        assertEquals(listOf("PRIO3", "BBAS3", "WEGE3"), Extrator.extrairTexto(texto).itens.map { it.ticker })
    }

    @Test
    fun links() {
        val html = """<a href="/carteiras-recomendadas/renda-variavel/carteira-recomendada-de-acoes-outubro-de-2026">x</a><a href="https://x.com/a.pdf?v=1">pdf</a>"""
        val l = HtmlTexto.links(html, "https://analisa.genialinvestimentos.com.br/carteiras-recomendadas/renda-variavel")
        assertEquals("https://analisa.genialinvestimentos.com.br/carteiras-recomendadas/renda-variavel/carteira-recomendada-de-acoes-outubro-de-2026", l[0])
        assertEquals("https://x.com/a.pdf?v=1", l[1])
    }
}
