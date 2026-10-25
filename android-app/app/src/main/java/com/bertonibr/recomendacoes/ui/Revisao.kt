package com.bertonibr.recomendacoes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bertonibr.recomendacoes.Leitura
import com.bertonibr.recomendacoes.data.Recomendacao
import com.bertonibr.recomendacoes.data.TIPOS
import java.time.LocalDate

private data class Linha(
    val ticker: String,
    val tipo: String,
    val alvo: String,
    val trecho: String,
    val marcada: Boolean = true,
) {
    val alvoNum get() = alvo.trim().replace(',', '.').toDoubleOrNull()
    val valida get() = ticker.isNotBlank() && (alvo.isBlank() || alvoNum != null)
}

/** Confere o que o OCR encontrou antes de salvar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevisaoScreen(leitura: Leitura, onSalvar: (List<Recomendacao>, Boolean) -> Unit, onCancelar: () -> Unit) {
    val ext = leitura.extracao
    var corretora by remember { mutableStateOf(ext.corretora ?: "") }
    var data by remember { mutableStateOf(LocalDate.now().br()) }
    var fonte by remember { mutableStateOf(leitura.nomeArquivo ?: "") }
    val linhas = remember {
        ext.itens.map { Linha(it.ticker, it.recomendacao ?: "Compra", it.precoAlvo?.fmt() ?: "", it.linha) }
            .toMutableStateList()
    }
    var verTexto by remember { mutableStateOf(false) }
    var substituir by remember { mutableStateOf(leitura.substituir) }
    val abrirLink = LocalUriHandler.current

    val dataOk = lerDataBr(data)
    val marcadas = linhas.filter { it.marcada }
    val podeSalvar = corretora.isNotBlank() && dataOk != null && marcadas.isNotEmpty() && marcadas.all { it.valida }

    BackHandler(onBack = onCancelar)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Revisar leitura") },
                navigationIcon = { IconButton(onClick = onCancelar) { Icon(Icons.Default.Close, "Cancelar") } },
                actions = {
                    TextButton(enabled = podeSalvar, onClick = {
                        onSalvar(
                            marcadas.map {
                                Recomendacao(dataOk!!, corretora.trim(), it.ticker.trim().uppercase(), it.tipo, it.alvoNum, fonte.trim())
                            },
                            substituir,
                        )
                    }) { Text("Salvar (${marcadas.size})") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    if (linhas.isEmpty()) "Nenhuma ação encontrada. Adicione manualmente ou tente uma foto mais nítida."
                    else "${linhas.size} ações encontradas. Confira ticker, recomendação e preço-alvo — o OCR pode errar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = corretora,
                    onValueChange = { corretora = it },
                    label = { Text("Corretora") },
                    isError = corretora.isBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = data,
                        onValueChange = { data = it },
                        label = { Text("Data") },
                        isError = dataOk == null,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = fonte,
                        onValueChange = { fonte = it },
                        label = { Text("Fonte") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f),
                    )
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = substituir, onCheckedChange = { substituir = it })
                    Text(
                        "Substituir as recomendações anteriores desta corretora (ações que saíram da carteira deixam de contar)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            leitura.url?.let { url ->
                item {
                    TextButton(onClick = { runCatching { abrirLink.openUri(url) } }) { Text("Abrir a página original") }
                }
            }
            if (linhas.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Todas:", style = MaterialTheme.typography.labelLarge)
                        TIPOS.forEach { t ->
                            FilterChip(
                                selected = linhas.all { it.tipo == t },
                                onClick = { linhas.indices.forEach { i -> linhas[i] = linhas[i].copy(tipo = t) } },
                                label = { Text(t) },
                            )
                        }
                    }
                }
            }
            itemsIndexed(linhas) { i, l ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = l.marcada, onCheckedChange = { linhas[i] = l.copy(marcada = it) })
                            OutlinedTextField(
                                value = l.ticker,
                                onValueChange = { linhas[i] = l.copy(ticker = it.uppercase()) },
                                label = { Text("Ticker") },
                                singleLine = true,
                                isError = l.ticker.isBlank(),
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            )
                            OutlinedTextField(
                                value = l.alvo,
                                onValueChange = { linhas[i] = l.copy(alvo = it) },
                                label = { Text("Alvo") },
                                singleLine = true,
                                isError = l.alvo.isNotBlank() && l.alvoNum == null,
                                modifier = Modifier.width(110.dp).padding(start = 8.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            )
                        }
                        Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TIPOS.forEach { t ->
                                FilterChip(selected = l.tipo == t, onClick = { linhas[i] = l.copy(tipo = t) }, label = { Text(t) })
                            }
                        }
                        if (l.trecho.isNotBlank()) {
                            Text(
                                "“${l.trecho}”",
                                Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { linhas.add(Linha("", "Compra", "", "")) }) {
                    Icon(Icons.Default.Add, null)
                    Text("Adicionar ação")
                }
            }
            item {
                TextButton(onClick = { verTexto = !verTexto }) {
                    Text(if (verTexto) "Ocultar texto lido" else "Ver texto lido pelo OCR")
                }
            }
            if (verTexto) {
                item {
                    Text(
                        ext.textoCompleto.ifBlank { "(nenhum texto reconhecido)" },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
        }
    }
}
