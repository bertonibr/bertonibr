package com.bertonibr.recomendacoes

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bertonibr.recomendacoes.data.Cotacoes
import com.bertonibr.recomendacoes.data.Csv
import com.bertonibr.recomendacoes.data.Recomendacao
import com.bertonibr.recomendacoes.data.Repositorio
import com.bertonibr.recomendacoes.data.Yahoo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface EstadoGrafico {
    data object Carregando : EstadoGrafico
    data class Ok(val ticker: String, val periodo: String, val cotacoes: Cotacoes) : EstadoGrafico
    data class Erro(val ticker: String, val mensagem: String) : EstadoGrafico
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repositorio(app)

    var recs by mutableStateOf(repo.carregar())
        private set
    var grafico by mutableStateOf<EstadoGrafico>(EstadoGrafico.Carregando)
        private set

    private var job: Job? = null
    private val cache = mutableMapOf<Pair<String, String>, Pair<Long, Cotacoes>>()

    fun adicionar(r: Recomendacao) = atualizar((recs + r).sortedByDescending { it.data })

    fun remover(r: Recomendacao) = atualizar(recs - r)

    /** Junta as recomendações do CSV às existentes (ignora duplicadas). Retorna quantas entraram. */
    fun importar(texto: String): Int {
        val novas = Csv.ler(texto).distinct().filter { it !in recs }
        atualizar((recs + novas).sortedByDescending { it.data })
        return novas.size
    }

    fun exportar(): String = Csv.escrever(recs)

    private fun atualizar(lista: List<Recomendacao>) {
        recs = lista
        repo.salvar(lista)
    }

    fun carregarGrafico(ticker: String, periodo: String) {
        job?.cancel()
        val chave = ticker to periodo
        cache[chave]?.let { (quando, cot) ->
            if (System.currentTimeMillis() - quando < 10 * 60_000) {
                grafico = EstadoGrafico.Ok(ticker, periodo, cot)
                return
            }
        }
        grafico = EstadoGrafico.Carregando
        job = viewModelScope.launch {
            grafico = try {
                val cot = Yahoo.historico(ticker, periodo)
                cache[chave] = System.currentTimeMillis() to cot
                EstadoGrafico.Ok(ticker, periodo, cot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EstadoGrafico.Erro(ticker, e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
