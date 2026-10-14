package com.bertonibr.recomendacoes

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bertonibr.recomendacoes.data.AnalistasJson
import com.bertonibr.recomendacoes.data.ConsensoAnalistas
import com.bertonibr.recomendacoes.data.Cotacoes
import com.bertonibr.recomendacoes.data.Csv
import com.bertonibr.recomendacoes.data.Extracao
import com.bertonibr.recomendacoes.data.Extrator
import com.bertonibr.recomendacoes.data.Genial
import com.bertonibr.recomendacoes.data.IBOVESPA
import com.bertonibr.recomendacoes.data.Leitor
import com.bertonibr.recomendacoes.data.Recomendacao
import com.bertonibr.recomendacoes.data.Repositorio
import com.bertonibr.recomendacoes.data.Yahoo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.time.YearMonth

sealed interface EstadoGrafico {
    data object Carregando : EstadoGrafico
    data class Ok(val ticker: String, val periodo: String, val cotacoes: Cotacoes) : EstadoGrafico
    data class Erro(val ticker: String, val mensagem: String) : EstadoGrafico
}

data class Leitura(
    val extracao: Extracao,
    val nomeArquivo: String?,
    val substituir: Boolean = false,
    val url: String? = null,
    val mesGenial: String? = null,
)

sealed interface EstadoLeitura {
    data object Nada : EstadoLeitura
    data class Lendo(val etapa: String) : EstadoLeitura
    data class Pronto(val leitura: Leitura) : EstadoLeitura
    data class Erro(val mensagem: String) : EstadoLeitura
}

private const val VALIDADE = 12 * 60 * 60 * 1000L

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repositorio(app)

    var recs by mutableStateOf(repo.carregar())
        private set
    var grafico by mutableStateOf<EstadoGrafico>(EstadoGrafico.Carregando)
        private set

    var leitura by mutableStateOf<EstadoLeitura>(EstadoLeitura.Nada)
        private set

    private val arquivoAnalistas = File(app.filesDir, "analistas.json")
    var analistas by mutableStateOf(carregarAnalistas())
        private set
    var progressoAnalistas by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var erroAnalistas by mutableStateOf<String?>(null)
        private set
    var carregandoConsenso by mutableStateOf<Set<String>>(emptySet())
        private set
    var errosConsenso by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    private val prefs = app.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
    var genialImportado by mutableStateOf(prefs.getString("genial_mes", null))
        private set
    var genialDisponivel by mutableStateOf<Genial.Carteira?>(null)
        private set
    private var verificouGenial = false

    private var job: Job? = null
    private var jobLeitura: Job? = null
    private var jobAnalistas: Job? = null
    private val cache = mutableMapOf<Pair<String, String>, Pair<Long, Cotacoes>>()

    fun adicionar(r: Recomendacao) = atualizar((recs + r).sortedByDescending { it.data })

    fun adicionarVarias(novas: List<Recomendacao>) = atualizar((recs + novas).sortedByDescending { it.data })

    /** Lê foto, print ou PDF com OCR e abre a tela de revisão. */
    fun lerArquivo(uri: Uri) {
        jobLeitura?.cancel()
        leitura = EstadoLeitura.Lendo("Abrindo arquivo…")
        jobLeitura = viewModelScope.launch {
            leitura = try {
                val app = getApplication<Application>()
                val paginas = Leitor.ler(app, uri) { leitura = EstadoLeitura.Lendo(it) }
                EstadoLeitura.Pronto(Leitura(Extrator.extrair(paginas), Leitor.nomeArquivo(app, uri)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EstadoLeitura.Erro(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Salva o que foi revisado; se pedido, apaga antes as recomendações antigas da mesma corretora. */
    fun salvarLeitura(leitura: Leitura, novas: List<Recomendacao>, substituir: Boolean) {
        val corretoras = novas.map { it.corretora }.toSet()
        val base = if (substituir) recs.filter { it.corretora !in corretoras } else recs
        atualizar((base + novas).sortedByDescending { it.data })
        leitura.mesGenial?.let {
            prefs.edit().putString("genial_mes", it).apply()
            genialImportado = it
            genialDisponivel = null
        }
        fecharLeitura()
    }

    private fun leituraGenial(c: Genial.Carteira) = Leitura(
        c.extracao,
        "Carteira recomendada de ações – ${c.rotulo}",
        substituir = true,
        url = c.url,
        mesGenial = c.mes.toString(),
    )

    /** Busca agora a carteira da Genial e abre a revisão. */
    fun buscarGenial() {
        genialDisponivel?.let {
            leitura = EstadoLeitura.Pronto(leituraGenial(it))
            return
        }
        jobLeitura?.cancel()
        leitura = EstadoLeitura.Lendo("Conectando à Genial…")
        jobLeitura = viewModelScope.launch {
            leitura = try {
                EstadoLeitura.Pronto(leituraGenial(Genial.buscar(getApplication()) { leitura = EstadoLeitura.Lendo(it) }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EstadoLeitura.Erro("Genial: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Ao abrir o app, verifica em segundo plano se há carteira nova da Genial ainda não importada. */
    fun verificarGenial() {
        if (verificouGenial || genialImportado == YearMonth.now().toString()) return
        verificouGenial = true
        viewModelScope.launch {
            try {
                val c = Genial.buscar(getApplication()) {}
                if (c.mes.toString() != genialImportado && c.extracao.itens.isNotEmpty()) genialDisponivel = c
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // silencioso: o usuário pode buscar manualmente
            }
        }
    }

    fun fecharLeitura() {
        jobLeitura?.cancel()
        leitura = EstadoLeitura.Nada
    }

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

    private fun carregarAnalistas(): Map<String, ConsensoAnalistas> = runCatching {
        AnalistasJson.ler(arquivoAnalistas.readText()).associateBy { it.ticker }
    }.getOrDefault(emptyMap())

    private suspend fun salvarAnalistas() {
        val texto = AnalistasJson.escrever(analistas.values)
        withContext(Dispatchers.IO) { runCatching { arquivoAnalistas.writeText(texto) } }
    }

    private fun recente(c: ConsensoAnalistas?) = c != null && System.currentTimeMillis() - c.quando < VALIDADE

    /** Atualiza o consenso de analistas das ações do Ibovespa + as cadastradas (cache de 12 h). */
    fun atualizarAnalistas(forcar: Boolean = false) {
        if (jobAnalistas?.isActive == true) return
        val tickers = (IBOVESPA + recs.map { it.ticker }).distinct().filter { forcar || !recente(analistas[it]) }
        if (tickers.isEmpty()) return
        erroAnalistas = null
        jobAnalistas = viewModelScope.launch {
            var feitos = 0
            var falhas = 0
            var ultimoErro: String? = null
            progressoAnalistas = 0 to tickers.size
            val limite = Semaphore(4)
            coroutineScope {
                tickers.forEach { t ->
                    launch {
                        limite.withPermit {
                            try {
                                val c = Yahoo.consenso(t)
                                analistas = analistas + (t to c)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                falhas++
                                ultimoErro = e.message ?: e.javaClass.simpleName
                            }
                            feitos++
                            progressoAnalistas = feitos to tickers.size
                        }
                    }
                }
            }
            progressoAnalistas = null
            if (falhas == tickers.size) erroAnalistas = "Não foi possível atualizar: $ultimoErro"
            salvarAnalistas()
        }
    }

    /** Garante o consenso de um ticker específico (tela de detalhe). */
    fun garantirConsenso(ticker: String, forcar: Boolean = false) {
        if (!forcar && recente(analistas[ticker])) return
        if (ticker in carregandoConsenso) return
        carregandoConsenso = carregandoConsenso + ticker
        errosConsenso = errosConsenso - ticker
        viewModelScope.launch {
            try {
                analistas = analistas + (ticker to Yahoo.consenso(ticker))
                salvarAnalistas()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errosConsenso = errosConsenso + (ticker to (e.message ?: e.javaClass.simpleName))
            } finally {
                carregandoConsenso = carregandoConsenso - ticker
            }
        }
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
