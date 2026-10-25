package com.bertonibr.recomendacoes.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import com.bertonibr.recomendacoes.AppViewModel
import com.bertonibr.recomendacoes.data.Consenso
import com.bertonibr.recomendacoes.data.Genial
import com.bertonibr.recomendacoes.data.TIPOS
import com.bertonibr.recomendacoes.data.consenso
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListaScreen(vm: AppViewModel, onAbrir: (String) -> Unit) {
    val ctx = LocalContext.current
    var busca by rememberSaveable { mutableStateOf("") }
    var aba by rememberSaveable { mutableStateOf(0) }
    var tipos by remember { mutableStateOf(TIPOS.toSet()) }
    var corretora by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var menuCorretora by remember { mutableStateOf(false) }
    var nova by remember { mutableStateOf(false) }
    var menuAdicionar by remember { mutableStateOf(false) }

    val fotoUri = remember {
        val arquivo = File(ctx.cacheDir, "fotos").apply { mkdirs() }.resolve("foto.jpg")
        FileProvider.getUriForFile(ctx, ctx.packageName + ".arquivos", arquivo)
    }
    val tirarFoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) vm.lerArquivo(fotoUri)
    }
    val escolherImagem = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.lerArquivo(uri)
    }
    val escolherPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.lerArquivo(uri)
    }

    val importar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val r = runCatching {
                val texto = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                vm.importar(texto)
            }
            val msg = r.fold({ "$it recomendações importadas" }, { "Erro ao importar: ${it.message}" })
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }
    val exportar = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val r = runCatching {
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(vm.exportar().toByteArray()) }
            }
            val msg = if (r.isSuccess) "CSV exportado" else "Erro ao exportar: ${r.exceptionOrNull()?.message}"
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    val q = busca.trim().uppercase()
    val filtradas = vm.recs.filter {
        it.recomendacao in tipos && (corretora == null || it.corretora == corretora) && it.ticker.contains(q)
    }
    val lista = consenso(filtradas)
    val corretoras = vm.recs.map { it.corretora }.distinct().sorted()
    val rankingAnalistas = vm.analistas.values
        .filter { it.temCobertura && it.tipo in tipos && it.ticker.contains(q) }
        .sortedBy { it.media ?: Double.MAX_VALUE }
    val mostrarVerGrafico = q.isNotEmpty() &&
        (if (aba == 0) lista.none { it.ticker == q } else rankingAnalistas.none { it.ticker == q })

    LaunchedEffect(aba) { if (aba == 1) vm.atualizarAnalistas() }
    LaunchedEffect(Unit) { vm.verificarGenial() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recomendações") },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Mais opções") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Importar CSV") }, onClick = {
                                menu = false
                                importar.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
                            })
                            DropdownMenuItem(text = { Text("Exportar CSV") }, onClick = {
                                menu = false
                                exportar.launch("recomendacoes.csv")
                            })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { menuAdicionar = true }) { Icon(Icons.Default.Add, "Adicionar recomendação") }
                DropdownMenu(expanded = menuAdicionar, onDismissRequest = { menuAdicionar = false }) {
                    DropdownMenuItem(text = { Text("Carteira da Genial (automático)") }, onClick = {
                        menuAdicionar = false
                        vm.buscarGenial()
                    })
                    DropdownMenuItem(text = { Text("Tirar foto") }, onClick = {
                        menuAdicionar = false
                        runCatching { tirarFoto.launch(fotoUri) }
                            .onFailure { Toast.makeText(ctx, "Câmera indisponível", Toast.LENGTH_LONG).show() }
                    })
                    DropdownMenuItem(text = { Text("Imagem ou print da galeria") }, onClick = {
                        menuAdicionar = false
                        escolherImagem.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    DropdownMenuItem(text = { Text("Ler PDF") }, onClick = {
                        menuAdicionar = false
                        escolherPdf.launch(arrayOf("application/pdf"))
                    })
                    DropdownMenuItem(text = { Text("Digitar") }, onClick = {
                        menuAdicionar = false
                        nova = true
                    })
                }
            }
        },
    ) { pad ->
      Column(Modifier.fillMaxSize().padding(pad)) {
        TabRow(selectedTabIndex = aba) {
            Tab(selected = aba == 0, onClick = { aba = 0 }, text = { Text("Minhas") })
            Tab(selected = aba == 1, onClick = { aba = 1 }, text = { Text("Analistas") })
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = busca,
                    onValueChange = { busca = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Pesquisar ticker (ex.: PETR4, AAPL)") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { if (q.isNotEmpty()) onAbrir(q) }),
                )
            }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TIPOS.forEach { t ->
                        FilterChip(
                            selected = t in tipos,
                            onClick = { tipos = if (t in tipos) tipos - t else tipos + t },
                            label = { Text(t) },
                        )
                    }
                    if (aba == 0) Box {
                        FilterChip(
                            selected = corretora != null,
                            onClick = { menuCorretora = true },
                            label = { Text(corretora ?: "Corretora") },
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                        )
                        DropdownMenu(expanded = menuCorretora, onDismissRequest = { menuCorretora = false }) {
                            DropdownMenuItem(text = { Text("Todas") }, onClick = {
                                corretora = null
                                menuCorretora = false
                            })
                            corretoras.forEach { c ->
                                DropdownMenuItem(text = { Text(c) }, onClick = {
                                    corretora = c
                                    menuCorretora = false
                                })
                            }
                        }
                    }
                }
            }
            if (aba == 0) {
                item { CartaoGenial(vm) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Resumo("Ações", lista.size, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                        Resumo("Compra", lista.count { it.consenso == "Compra" }, CorCompra, Modifier.weight(1f))
                        Resumo("Venda", lista.count { it.consenso == "Venda" }, CorVenda, Modifier.weight(1f))
                    }
                }
            } else {
                item {
                    val prog = vm.progressoAnalistas
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val atualizado = vm.analistas.values.maxOfOrNull { it.quando }
                            Text(
                                when {
                                    prog != null -> "Atualizando ${prog.first} de ${prog.second}…"
                                    atualizado != null -> "${rankingAnalistas.size} ações · atualizado " +
                                        Instant.ofEpochMilli(atualizado).atZone(ZoneId.systemDefault()).format(DATA_HORA)
                                    else -> "Consenso de analistas (Yahoo Finance)"
                                },
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(onClick = { vm.atualizarAnalistas(forcar = true) }, enabled = prog == null) {
                                Icon(Icons.Default.Refresh, "Atualizar")
                            }
                        }
                        if (prog != null) {
                            LinearProgressIndicator(
                                progress = { prog.first.toFloat() / prog.second.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        vm.erroAnalistas?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (mostrarVerGrafico) {
                item {
                    OutlinedCard(onClick = { onAbrir(q) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Ver gráfico de $q",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            if (aba == 0) {
                items(lista, key = { it.ticker }) { c -> CartaoConsenso(c) { onAbrir(c.ticker) } }
                if (lista.isEmpty() && q.isEmpty()) {
                    item {
                        Text(
                            "Nenhuma recomendação. Toque em + para ler uma foto/PDF de carteira recomendada ou digitar.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(rankingAnalistas, key = { it.ticker }) { c -> CartaoAnalistas(c) { onAbrir(c.ticker) } }
            }
        }
      }
    }

    if (nova) {
        DialogoNova(
            onSalvar = {
                vm.adicionar(it)
                nova = false
            },
            onFechar = { nova = false },
        )
    }
}

@Composable
private fun CartaoGenial(vm: AppViewModel) {
    val disponivel = vm.genialDisponivel
    val importado = vm.genialImportado?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
    OutlinedCard(onClick = { vm.buscarGenial() }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Carteira Genial Investimentos", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        disponivel != null -> "Nova carteira de ${disponivel.rotulo}: ${disponivel.extracao.itens.size} ações. Toque para revisar e importar."
                        importado != null -> "Carteira de ${Genial.rotuloMes(importado)} importada. Toque para buscar de novo."
                        else -> "Toque para buscar a carteira recomendada de ações do mês."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (disponivel != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.Refresh, null)
        }
    }
}

private val DATA_HORA = DateTimeFormatter.ofPattern("dd/MM HH:mm")

@Composable
private fun Resumo(rotulo: String, valor: Int, cor: Color, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(rotulo, style = MaterialTheme.typography.labelMedium)
            Text(
                valor.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = cor,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun CartaoConsenso(c: Consenso, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.ticker, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Compra ${c.compra} · Neutro ${c.neutro} · Venda ${c.venda}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                c.alvoMedio?.let { Text("Alvo médio ${it.fmt()}", style = MaterialTheme.typography.bodySmall) }
            }
            Selo(c.consenso)
        }
    }
}
