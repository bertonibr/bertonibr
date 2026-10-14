package com.bertonibr.recomendacoes.ui

import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bertonibr.recomendacoes.AppViewModel
import com.bertonibr.recomendacoes.EstadoLeitura

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val ctx = LocalContext.current
    var ticker by rememberSaveable { mutableStateOf<String?>(null) }
    val aberto = ticker
    val leitura = vm.leitura
    if (leitura is EstadoLeitura.Pronto) {
        RevisaoScreen(
            leitura.leitura,
            onSalvar = { lista, substituir ->
                vm.salvarLeitura(leitura.leitura, lista, substituir)
                Toast.makeText(ctx, "${lista.size} recomendações salvas", Toast.LENGTH_LONG).show()
            },
            onCancelar = vm::fecharLeitura,
        )
        return
    }
    when (leitura) {
        is EstadoLeitura.Lendo -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Lendo documento") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(leitura.etapa)
                }
            },
            confirmButton = { TextButton(onClick = vm::fecharLeitura) { Text("Cancelar") } },
        )
        is EstadoLeitura.Erro -> AlertDialog(
            onDismissRequest = vm::fecharLeitura,
            title = { Text("Não foi possível ler") },
            text = { Text(leitura.mensagem) },
            confirmButton = { TextButton(onClick = vm::fecharLeitura) { Text("OK") } },
        )
        else -> Unit
    }
    if (aberto == null) {
        ListaScreen(vm, onAbrir = { ticker = it })
    } else {
        BackHandler { ticker = null }
        DetalheScreen(vm, aberto, onVoltar = { ticker = null })
    }
}

@Composable
fun Selo(texto: String, corBase: Color = corTipo(texto)) {
    // No tema escuro clareia a cor para manter o contraste
    val cor = if (isSystemInDarkTheme()) lerp(corBase, Color.White, 0.35f) else corBase
    Surface(shape = RoundedCornerShape(50), color = cor.copy(alpha = 0.15f)) {
        Text(
            texto,
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            color = cor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
