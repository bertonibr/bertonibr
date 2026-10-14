package com.bertonibr.recomendacoes

import com.bertonibr.recomendacoes.data.Candle
import com.bertonibr.recomendacoes.data.Csv
import com.bertonibr.recomendacoes.data.Recomendacao
import com.bertonibr.recomendacoes.data.consenso
import com.bertonibr.recomendacoes.data.mediaMovel
import com.bertonibr.recomendacoes.data.tickerYahoo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DadosTest {
    @Test
    fun lerEEscreverCsv() {
        val txt = "data,corretora,ticker,recomendacao,preco_alvo,fonte\n" +
            "2026-10-01,\"XP, Inc\",petr4,compra,45.00,Carteira\n" +
            "2026-10-02,BTG,VALE3,Venda,,\n"
        val lista = Csv.ler(txt)
        assertEquals(2, lista.size)
        assertEquals("XP, Inc", lista[0].corretora)
        assertEquals("PETR4", lista[0].ticker)
        assertEquals("Compra", lista[0].recomendacao)
        assertEquals(45.0, lista[0].precoAlvo!!, 1e-9)
        assertNull(lista[1].precoAlvo)
        assertEquals(lista, Csv.ler(Csv.escrever(lista)))
    }

    @Test
    fun csvDoExcelComPontoEVirgula() {
        val txt = "﻿data;corretora;ticker;recomendacao;preco_alvo;fonte\r\n2026-10-01;XP;PETR4;Compra;45,50;x\r\n"
        val lista = Csv.ler(txt)
        assertEquals(1, lista.size)
        assertEquals(45.5, lista[0].precoAlvo!!, 1e-9)
    }

    @Test
    fun consensoUsaUltimaRecomendacaoDeCadaCorretora() {
        val d = LocalDate.of(2026, 10, 1)
        val recs = listOf(
            Recomendacao(d, "A", "PETR4", "Venda", 30.0),
            Recomendacao(d.plusDays(5), "A", "PETR4", "Compra", 40.0),
            Recomendacao(d, "B", "PETR4", "Compra", 50.0),
            Recomendacao(d, "B", "MGLU3", "Venda", null),
        )
        val c = consenso(recs)
        assertEquals("PETR4", c[0].ticker)
        assertEquals(2, c[0].compra)
        assertEquals(0, c[0].venda)
        assertEquals(45.0, c[0].alvoMedio!!, 1e-9)
        assertEquals("Venda", c[1].consenso)
    }

    @Test
    fun sufixoDaB3() {
        assertEquals("PETR4.SA", tickerYahoo(" petr4 "))
        assertEquals("TAEE11.SA", tickerYahoo("TAEE11"))
        assertEquals("AAPL", tickerYahoo("AAPL"))
        assertEquals("PETR4.SA", tickerYahoo("PETR4.SA"))
    }

    @Test
    fun mediaMovel() {
        val d = LocalDate.of(2026, 1, 1)
        val c = (1..4).map { Candle(d.plusDays(it.toLong()), 0.0, 0.0, 0.0, it.toDouble(), 0) }
        assertEquals(listOf(null, 1.5, 2.5, 3.5), mediaMovel(c, 2))
    }
}

class SanitizarTest {
    private val d = java.time.LocalDate.of(2026, 10, 13)

    @Test
    fun minimaZeradaViraCorpoDoCandle() {
        val c = com.bertonibr.recomendacoes.data.sanitizar(d, 16.2, 16.4, 0.0, 15.99, 100)!!
        assertEquals(15.99, c.minima, 1e-9)
        assertEquals(16.4, c.maxima, 1e-9)
    }

    @Test
    fun valoresAbsurdosEFechamentoInvalido() {
        val c = com.bertonibr.recomendacoes.data.sanitizar(d, Double.NaN, 99.0, 1.0, 16.0, -5)!!
        assertEquals(16.0, c.abertura, 1e-9)
        assertEquals(16.0, c.maxima, 1e-9)
        assertEquals(16.0, c.minima, 1e-9)
        assertEquals(0L, c.volume)
        assertNull(com.bertonibr.recomendacoes.data.sanitizar(d, 1.0, 1.0, 1.0, 0.0, 0))
    }
}
