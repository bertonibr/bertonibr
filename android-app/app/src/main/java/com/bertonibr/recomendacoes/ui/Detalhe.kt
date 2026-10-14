package com.bertonibr.recomendacoes.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bertonibr.recomendacoes.AppViewModel
import com.bertonibr.recomendacoes.EstadoGrafico
import com.bertonibr.recomendacoes.data.Cotacoes
import com.bertonibr.recomendacoes.data.Recomendacao
import com.bertonibr.recomendacoes.data.alvoMedio
import com.bertonibr.recomendacoes.data.tickerYahoo
import com.bertonibr.recomendacoes.data.ultimasPorCorretora

private val PERIODOS = listOf("1mo" to "1M", "3mo" to "3M", "6mo" to "6M", "1y" to "1A", "2y" to "2A", "5y" to "5A")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetalheScreen(vm: AppViewModel, ticker: String, onVoltar: () -> Unit) {
    var periodo by rememberSaveable { mutableStateOf("3mo") }
    var nova by remember { mutableStateOf(false) }
    var remover by remember { mutableStateOf<Recomendacao?>(null) }
    LaunchedEffect(ticker, periodo) { vm.carregarGrafico(ticker, periodo) }

    val recsT = vm.recs.filter { it.ticker == ticker }
    val ultimas = ultimasPorCorretora(recsT)
    val alvo = alvoMedio(recsT)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ticker)
                        Text(tickerYahoo(ticker), style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nova = true }) { Icon(Icons.Default.Add, "Adicionar recomendação") }
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val e = vm.grafico
                when {
                    e is EstadoGrafico.Ok && e.ticker == ticker && e.periodo == periodo -> {
                        if (e.cotacoes.candles.size < 2) {
                            Text("Sem cotações para ${e.cotacoes.simbolo}.")
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Metricas(e.cotacoes, alvo)
                                Legenda()
                                GraficoCandles(e.cotacoes.candles, ultimas, Modifier.fillMaxWidth().height(340.dp))
                            }
                        }
                    }
                    e is EstadoGrafico.Erro && e.ticker == ticker -> {
                        Column {
                            Text(
                                "Não foi possível carregar as cotações: ${e.mensagem}",
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = { vm.carregarGrafico(ticker, periodo) }) { Text("Tentar de novo") }
                        }
                    }
                    else -> {
                        Box(Modifier.fillMaxWidth().height(340.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PERIODOS.forEach { (valor, rotulo) ->
                        FilterChip(selected = periodo == valor, onClick = { periodo = valor }, label = { Text(rotulo) })
                    }
                }
            }
            item {
                Text(
                    if (recsT.isEmpty()) "Nenhuma corretora cadastrada para esta ação." else "Recomendações das corretoras",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(recsT.sortedByDescending { it.data }) { r -> LinhaRecomendacao(r, onRemover = { remover = r }) }
        }
    }

    if (nova) {
        DialogoNova(
            tickerInicial = ticker,
            onSalvar = {
                vm.adicionar(it)
                nova = false
            },
            onFechar = { nova = false },
        )
    }
    remover?.let { r ->
        AlertDialog(
            onDismissRequest = { remover = null },
            title = { Text("Remover recomendação?") },
            text = { Text("${r.corretora} · ${r.recomendacao} · ${r.data.br()}") },
            confirmButton = {
                TextButton(onClick = {
                    vm.remover(r)
                    remover = null
                }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { remover = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Metricas(c: Cotacoes, alvo: Double?) {
    val ultimo = c.candles.last().fechamento
    val anterior = c.candles[c.candles.size - 2].fechamento
    val inicio = c.candles.first().fechamento
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Metrica("Último ${c.moeda}".trim(), ultimo.fmt(), pct((ultimo / anterior - 1) * 100), Modifier.weight(1f))
        Metrica("No período", pct((ultimo / inicio - 1) * 100), null, Modifier.weight(1f))
        if (alvo != null) {
            Metrica("Alvo médio", alvo.fmt(), pct((alvo / ultimo - 1) * 100) + " pot.", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Metrica(rotulo: String, valor: String, variacao: String?, modifier: Modifier) {
    Column(modifier) {
        Text(rotulo, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(valor, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        variacao?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = if (it.startsWith("-")) CorVenda else CorCompra)
        }
    }
}

@Composable
private fun Legenda() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("━ MM20", color = CorMM20, style = MaterialTheme.typography.labelSmall)
        Text("━ MM50", color = CorMM50, style = MaterialTheme.typography.labelSmall)
        Text("╌ preço-alvo", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LinhaRecomendacao(r: Recomendacao, onRemover: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(r.corretora, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(r.data.br(), r.precoAlvo?.let { "alvo ${it.fmt()}" }, r.fonte.ifBlank { null })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Selo(r.recomendacao)
            IconButton(onClick = onRemover) { Icon(Icons.Default.Delete, "Remover") }
        }
    }
}
