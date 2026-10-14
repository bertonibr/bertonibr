package com.bertonibr.recomendacoes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bertonibr.recomendacoes.AppViewModel

@Composable
fun App(vm: AppViewModel = viewModel()) {
    var ticker by rememberSaveable { mutableStateOf<String?>(null) }
    val aberto = ticker
    if (aberto == null) {
        ListaScreen(vm, onAbrir = { ticker = it })
    } else {
        BackHandler { ticker = null }
        DetalheScreen(vm, aberto, onVoltar = { ticker = null })
    }
}

@Composable
fun Selo(tipo: String) {
    val cor = corTipo(tipo)
    Surface(shape = RoundedCornerShape(50), color = cor.copy(alpha = 0.15f)) {
        Text(
            tipo,
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            color = cor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
