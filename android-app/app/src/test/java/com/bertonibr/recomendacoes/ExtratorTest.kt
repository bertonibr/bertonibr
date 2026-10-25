package com.bertonibr.recomendacoes

import com.bertonibr.recomendacoes.data.Extrator
import com.bertonibr.recomendacoes.data.Trecho
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtratorTest {
    private fun t(texto: String, esq: Float, dir: Float, topo: Float) = Trecho(texto, esq, topo, dir, topo + 10f)

    @Test
    fun tabelaComColunaDePrecoAlvo() {
        val pagina = listOf(
            t("Carteira", 10f, 60f, -20f), t("Recomendada", 62f, 140f, -20f),
            t("Ação", 10f, 60f, 0f), t("Recomendação", 100f, 200f, 0f), t("Preço-alvo", 250f, 330f, 0f), t("Upside", 380f, 440f, 0f),
            t("PETR4", 10f, 60f, 20f), t("Compra", 100f, 160f, 21f), t("R$", 250f, 265f, 20f), t("45,00", 268f, 310f, 19f), t("18,5%", 380f, 430f, 20f),
            t("VALE3", 10f, 60f, 40f), t("Neutro", 100f, 160f, 40f), t("72,50", 268f, 310f, 40f), t("5%", 380f, 400f, 40f),
            t("Fonte:", 10f, 50f, 60f), t("XP", 52f, 70f, 60f), t("Investimentos", 72f, 160f, 60f),
        )
        val r = Extrator.extrair(listOf(pagina.shuffled()))
        assertEquals("XP Investimentos", r.corretora)
        assertEquals(listOf("PETR4", "VALE3"), r.itens.map { it.ticker })
        assertEquals("Compra", r.itens[0].recomendacao)
        assertEquals(45.0, r.itens[0].precoAlvo!!, 1e-9)
        assertEquals("Neutro", r.itens[1].recomendacao)
        assertEquals(72.5, r.itens[1].precoAlvo!!, 1e-9)
    }

    @Test
    fun textoCorrido() {
        val pagina = listOf(
            t("Elevamos", 0f, 50f, 0f), t("ITUB4", 55f, 90f, 0f), t("para", 95f, 120f, 0f), t("Outperform,", 125f, 190f, 0f),
            t("preço-alvo", 195f, 250f, 0f), t("de", 255f, 265f, 0f), t("R$", 270f, 285f, 0f), t("42,00", 290f, 320f, 0f),
            t("Ano", 0f, 30f, 20f), t("2026", 35f, 60f, 20f), t("TOTAL", 65f, 90f, 20f), t("12", 95f, 110f, 20f),
        )
        val r = Extrator.extrair(listOf(pagina))
        assertEquals(1, r.itens.size)
        assertEquals("ITUB4", r.itens[0].ticker)
        assertEquals("Compra", r.itens[0].recomendacao)
        assertEquals(42.0, r.itens[0].precoAlvo!!, 1e-9)
        assertNull(r.corretora)
    }

    @Test
    fun tickers() {
        assertEquals(listOf("TAEE11", "BBAS3", "AAPL34"), Extrator.tickers("TAEE11 BBAS 3 AAPL34 ABCD1 IBOV 2026"))
        assertEquals("Venda", Extrator.recomendacao("MGLU3 — Underperform"))
        assertEquals("Neutro", Extrator.recomendacao("WEGE3 manter"))
        assertNull(Extrator.recomendacao("RENT3"))
    }
}
