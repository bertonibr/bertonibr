package com.bertonibr.recomendacoes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bertonibr.recomendacoes.data.ConsensoAnalistas
import com.bertonibr.recomendacoes.data.traduzirAcao

val CorCompraForte = Color(0xFF1B5E20)
val CorVendaForte = Color(0xFF8E0000)
val CorConsenso = Color(0xFF8E24AA)

fun corConsenso(c: ConsensoAnalistas) = when (c.chave) {
    "strong_buy" -> CorCompraForte
    "sell" -> CorVendaForte
    else -> corTipo(c.tipo)
}

@Composable
fun CartaoAnalistas(c: ConsensoAnalistas, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.ticker, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(
                        c.analistas?.let { "$it analistas" },
                        c.media?.let { "nota ${it.fmt(1)}" },
                    ).joinToString(" · ").ifEmpty { "Sem número de analistas" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                c.alvoMedio?.let { alvo ->
                    val pot = c.potencial
                    Text(
                        "Alvo ${alvo.fmt()}" + (pot?.let { " (${pct(it)})" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            pot == null -> MaterialTheme.colorScheme.onSurface
                            pot >= 0 -> CorCompra
                            else -> CorVenda
                        },
                    )
                }
            }
            Selo(c.rotulo, corConsenso(c))
        }
    }
}

/** Seção da tela de detalhe com o consenso de analistas do Yahoo. */
@Composable
fun SecaoConsenso(c: ConsensoAnalistas?, carregando: Boolean, erro: String?, onTentar: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Consenso de analistas", style = MaterialTheme.typography.titleMedium)
            when {
                c == null && carregando -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Buscando no Yahoo Finance…", style = MaterialTheme.typography.bodySmall)
                }
                c == null -> Column {
                    Text("Não foi possível obter o consenso: ${erro ?: "sem dados"}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onTentar) { Text("Tentar de novo") }
                }
                !c.temCobertura -> Text(
                    "Sem cobertura de analistas no Yahoo Finance para este ativo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> ConteudoConsenso(c)
            }
        }
    }
}

@Composable
private fun ConteudoConsenso(c: ConsensoAnalistas) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Selo(c.rotulo, corConsenso(c))
        Text(
            listOfNotNull(c.analistas?.let { "$it analistas" }, c.media?.let { "nota ${it.fmt(1)} (1 = compra forte, 5 = venda)" })
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    c.tendencia?.takeIf { it.total > 0 }?.let { t ->
        val partes = listOf(
            Triple("Compra forte", t.compraForte, CorCompraForte),
            Triple("Compra", t.compra, CorCompra),
            Triple("Neutro", t.neutro, CorNeutro),
            Triple("Venda", t.venda, CorVenda),
            Triple("Venda forte", t.vendaForte, CorVendaForte),
        )
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
            partes.filter { it.second > 0 }.forEach { (_, n, cor) ->
                Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(cor))
            }
        }
        Text(
            partes.joinToString(" · ") { "${it.first} ${it.second}" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    c.alvoMedio?.let { alvo ->
        Text(
            listOfNotNull(
                "Preço-alvo médio ${alvo.fmt()}" + (c.potencial?.let { " (${pct(it)})" } ?: ""),
                c.alvoMin?.let { "mín ${it.fmt()}" },
                c.alvoMax?.let { "máx ${it.fmt()}" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    if (c.historico.isNotEmpty()) {
        HorizontalDivider()
        Text("Mudanças recentes de recomendação", style = MaterialTheme.typography.titleSmall)
        c.historico.take(10).forEach { m ->
            Column {
                Text(
                    "${m.firma} · ${traduzirAcao(m.acao)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                val grau = if (m.de.isNotBlank() && m.de != m.para) "${m.de} → ${m.para}" else m.para
                val alvo = m.alvo?.let { a -> "alvo " + (m.alvoAnterior?.takeIf { it != a }?.let { "${it.fmt()} → " } ?: "") + a.fmt() }
                Text(
                    listOfNotNull(m.data.br(), grau.ifBlank { null }, alvo).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Text(
        "Fonte: Yahoo Finance. Atualizado em ${java.time.Instant.ofEpochMilli(c.quando).atZone(java.time.ZoneId.systemDefault()).toLocalDate().br()}.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
