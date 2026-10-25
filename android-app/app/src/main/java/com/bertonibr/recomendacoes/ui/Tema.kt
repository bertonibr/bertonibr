package com.bertonibr.recomendacoes.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

val CorCompra = Color(0xFF2E7D32)
val CorNeutro = Color(0xFF757575)
val CorVenda = Color(0xFFC62828)

fun corTipo(tipo: String) = when (tipo) {
    "Compra" -> CorCompra
    "Venda" -> CorVenda
    else -> CorNeutro
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val escuro = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val cores = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (escuro) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        escuro -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = cores, content = content)
}

private val BR = Locale.forLanguageTag("pt-BR")
private val DATA_BR = DateTimeFormatter.ofPattern("dd/MM/yyyy")

fun Double.fmt(casas: Int = 2): String = String.format(BR, "%.${casas}f", this)

fun pct(v: Double): String = (if (v >= 0) "+" else "") + v.fmt(1) + "%"

fun LocalDate.br(): String = format(DATA_BR)

fun lerDataBr(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim(), DATA_BR) }.getOrNull()
