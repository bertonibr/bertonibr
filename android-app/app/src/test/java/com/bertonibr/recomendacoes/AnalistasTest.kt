package com.bertonibr.recomendacoes

import com.bertonibr.recomendacoes.data.AnalistasJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AnalistasTest {
    private val resposta = """
        {"quoteSummary":{"result":[{
          "financialData":{"currentPrice":{"raw":38.5,"fmt":"38.50"},"targetMeanPrice":{"raw":45.2},
            "targetLowPrice":{"raw":30.0},"targetHighPrice":{"raw":60.0},"recommendationMean":{"raw":1.9},
            "recommendationKey":"buy","numberOfAnalystOpinions":{"raw":14},"financialCurrency":"BRL"},
          "price":{"currency":"BRL","regularMarketPrice":{"raw":38.4}},
          "recommendationTrend":{"trend":[{"period":"0m","strongBuy":4,"buy":6,"hold":3,"sell":1,"strongSell":0},
                                          {"period":"-1m","strongBuy":1,"buy":1,"hold":1,"sell":1,"strongSell":1}]},
          "upgradeDowngradeHistory":{"history":[
            {"epochGradeDate":1760000000,"firm":"Goldman Sachs","toGrade":"Buy","fromGrade":"Neutral","action":"up","currentPriceTarget":48.0,"priorPriceTarget":40.0},
            {"epochGradeDate":1750000000,"firm":"JP Morgan","toGrade":"Overweight","fromGrade":"","action":"init"}]}
        }],"error":null}}
    """.trimIndent()

    @Test
    fun interpretaRespostaDoYahoo() {
        val c = AnalistasJson.deYahoo("PETR4", resposta, 200, agora = 123L)
        assertEquals("buy", c.chave)
        assertEquals("Compra", c.rotulo)
        assertEquals(14, c.analistas)
        assertEquals(45.2, c.alvoMedio!!, 1e-9)
        assertEquals(38.5, c.precoAtual!!, 1e-9)
        assertEquals(4, c.tendencia!!.compraForte)
        assertEquals(14, c.tendencia!!.total)
        assertEquals(2, c.historico.size)
        assertEquals("Goldman Sachs", c.historico[0].firma)
        assertEquals(48.0, c.historico[0].alvo!!, 1e-9)
        assertNull(c.historico[1].alvo)
        assertTrue(c.temCobertura)
        assertEquals(c, AnalistasJson.ler(AnalistasJson.escrever(listOf(c))).single())
    }

    @Test
    fun semCobertura() {
        val c = AnalistasJson.deYahoo(
            "XPTO3",
            """{"quoteSummary":{"result":[{"financialData":{"recommendationKey":"none"},"price":{"currency":"BRL"}}],"error":null}}""",
            200,
        )
        assertFalse(c.temCobertura)
        assertEquals(c, AnalistasJson.ler(AnalistasJson.escrever(listOf(c))).single())
    }

    @Test(expected = IOException::class)
    fun erroDoYahoo() {
        AnalistasJson.deYahoo("X", """{"quoteSummary":{"result":null,"error":{"code":"Not Found","description":"Quote not found"}}}""", 404)
    }
}
