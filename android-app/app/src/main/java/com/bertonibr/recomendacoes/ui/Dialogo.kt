package com.bertonibr.recomendacoes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bertonibr.recomendacoes.data.Recomendacao
import com.bertonibr.recomendacoes.data.TIPOS
import java.time.LocalDate

@Composable
fun DialogoNova(tickerInicial: String = "", onSalvar: (Recomendacao) -> Unit, onFechar: () -> Unit) {
    var ticker by remember { mutableStateOf(tickerInicial) }
    var corretora by remember { mutableStateOf("") }
    var tipo by remember { mutableStateOf("Compra") }
    var alvo by remember { mutableStateOf("") }
    var data by remember { mutableStateOf(LocalDate.now().br()) }
    var fonte by remember { mutableStateOf("") }

    val dataOk = lerDataBr(data)
    val alvoNum = alvo.trim().replace(',', '.').toDoubleOrNull()
    val valido = ticker.isNotBlank() && corretora.isNotBlank() && dataOk != null && (alvo.isBlank() || alvoNum != null)

    AlertDialog(
        onDismissRequest = onFechar,
        title = { Text("Nova recomendação") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ticker,
                    onValueChange = { ticker = it.uppercase() },
                    label = { Text("Ticker") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                OutlinedTextField(
                    value = corretora,
                    onValueChange = { corretora = it },
                    label = { Text("Corretora") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TIPOS.forEach { t -> FilterChip(selected = tipo == t, onClick = { tipo = t }, label = { Text(t) }) }
                }
                OutlinedTextField(
                    value = alvo,
                    onValueChange = { alvo = it },
                    label = { Text("Preço-alvo (opcional)") },
                    singleLine = true,
                    isError = alvo.isNotBlank() && alvoNum == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = data,
                    onValueChange = { data = it },
                    label = { Text("Data (dd/mm/aaaa)") },
                    singleLine = true,
                    isError = dataOk == null,
                )
                OutlinedTextField(
                    value = fonte,
                    onValueChange = { fonte = it },
                    label = { Text("Fonte / observação") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valido,
                onClick = {
                    onSalvar(Recomendacao(dataOk!!, corretora.trim(), ticker.trim().uppercase(), tipo, alvoNum, fonte.trim()))
                },
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onFechar) { Text("Cancelar") } },
    )
}
