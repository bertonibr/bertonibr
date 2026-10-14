package com.bertonibr.recomendacoes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.bertonibr.recomendacoes.data.Candle
import com.bertonibr.recomendacoes.data.Recomendacao
import com.bertonibr.recomendacoes.data.mediaMovel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

val CorMM20 = Color(0xFF1E88E5)
val CorMM50 = Color(0xFFFB8C00)
private val CorAlta = Color(0xFF26A69A)
private val CorBaixa = Color(0xFFEF5350)
private val DATA_CURTA = DateTimeFormatter.ofPattern("dd/MM/yy")

/** Candles + médias móveis + volume + linhas de preço-alvo. Toque ou arraste para ver os valores do dia. */
@Composable
fun GraficoCandles(
    historico: List<Candle>,
    inicio: LocalDate,
    recs: List<Recomendacao>,
    modifier: Modifier = Modifier,
    alvoConsenso: Double? = null,
) {
    val i0 = remember(historico, inicio) { historico.indexOfFirst { !it.data.isBefore(inicio) }.coerceAtLeast(0) }
    val candles = remember(historico, i0) { historico.subList(i0, historico.size) }
    val mm20 = remember(historico, i0) { mediaMovel(historico, 20).drop(i0) }
    val mm50 = remember(historico, i0) { mediaMovel(historico, 50).drop(i0) }
    val medidor = rememberTextMeasurer()
    val corTexto = MaterialTheme.colorScheme.onSurfaceVariant
    val corGrade = MaterialTheme.colorScheme.outlineVariant
    val corRotulo = MaterialTheme.colorScheme.surfaceVariant
    val estilo = MaterialTheme.typography.labelSmall.copy(color = corTexto)
    val estiloForte = estilo.copy(color = MaterialTheme.colorScheme.onSurface)
    val eixoPx = with(LocalDensity.current) { 48.dp.toPx() }
    var sel by remember(candles) { mutableStateOf<Int?>(null) }
    val n = candles.size

    fun indice(x: Float, largura: Int) =
        ((x / (largura - eixoPx)) * n).toInt().coerceIn(0, n - 1)

    Canvas(
        modifier
            .pointerInput(candles) {
                detectTapGestures { o -> sel = indice(o.x, size.width).takeIf { it != sel } }
            }
            .pointerInput(candles) {
                detectHorizontalDragGestures(
                    onDragEnd = { sel = null },
                    onDragCancel = { sel = null },
                ) { change, _ -> sel = indice(change.position.x, size.width) }
            },
    ) {
        val rodape = 18.dp.toPx()
        val w = size.width - eixoPx
        val hTotal = size.height - rodape
        val hPreco = hTotal * 0.76f
        val topoVol = hTotal * 0.80f
        val hVol = hTotal - topoVol

        val alvos = recs.mapNotNull { it.precoAlvo } + listOfNotNull(alvoConsenso)
        val medias = (mm20 + mm50).filterNotNull()
        var minP = minOf(candles.minOf { it.minima }, alvos.minOrNull() ?: Double.MAX_VALUE, medias.minOrNull() ?: Double.MAX_VALUE)
        var maxP = maxOf(candles.maxOf { it.maxima }, alvos.maxOrNull() ?: -Double.MAX_VALUE, medias.maxOrNull() ?: -Double.MAX_VALUE)
        val folga = ((maxP - minP) * 0.06).coerceAtLeast(0.01)
        minP -= folga
        maxP += folga
        fun y(p: Double) = (hPreco * (1 - (p - minP) / (maxP - minP))).toFloat()
        val passo = w / n
        fun x(i: Int) = passo * i + passo / 2

        // Grade e eixo de preços
        for (k in 0..4) {
            val p = minP + (maxP - minP) * k / 4
            val yy = y(p)
            drawLine(corGrade, Offset(0f, yy), Offset(w, yy), 1f)
            val txt = medidor.measure(p.fmt(), estilo)
            val topo = (yy - txt.size.height / 2f).coerceIn(0f, max(0f, hPreco - txt.size.height))
            drawText(txt, topLeft = Offset(w + 4.dp.toPx(), topo))
        }

        // Candles e volume
        val maxVol = max(1L, candles.maxOf { it.volume })
        val corpo = max(1f, passo * 0.7f)
        val pavio = (passo * 0.12f).coerceIn(1f, 3f)
        candles.forEachIndexed { i, c ->
            val cor = if (c.fechamento >= c.abertura) CorAlta else CorBaixa
            val cx = x(i)
            drawLine(cor, Offset(cx, y(c.maxima)), Offset(cx, y(c.minima)), pavio)
            val topo = y(max(c.abertura, c.fechamento))
            val base = y(min(c.abertura, c.fechamento))
            drawRect(cor, Offset(cx - corpo / 2, topo), Size(corpo, max(1f, base - topo)))
            val hv = (hVol * c.volume / maxVol).toFloat()
            drawRect(cor.copy(alpha = 0.35f), Offset(cx - corpo / 2, hTotal - hv), Size(corpo, hv))
        }

        // Médias móveis
        fun linha(valores: List<Double?>, cor: Color) {
            val path = Path()
            var iniciou = false
            valores.forEachIndexed { i, v ->
                if (v != null) {
                    if (iniciou) path.lineTo(x(i), y(v)) else path.moveTo(x(i), y(v))
                    iniciou = true
                }
            }
            drawPath(path, cor, style = Stroke(1.5.dp.toPx()))
        }
        linha(mm20, CorMM20)
        linha(mm50, CorMM50)

        // Recomendações: data (pontilhado vertical) e preço-alvo (tracejado horizontal)
        val tracejado = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
        val pontilhado = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
        recs.forEach { r ->
            val cor = corTipo(r.recomendacao)
            if (!r.data.isBefore(candles.first().data)) {
                val i = candles.indexOfFirst { !it.data.isBefore(r.data) }
                if (i >= 0) {
                    drawLine(cor.copy(alpha = 0.6f), Offset(x(i), 0f), Offset(x(i), hPreco), 1.5f, pathEffect = pontilhado)
                }
            }
            r.precoAlvo?.let { a ->
                val yy = y(a)
                drawLine(cor, Offset(0f, yy), Offset(w, yy), 1.5.dp.toPx(), pathEffect = tracejado)
                val txt = medidor.measure("${r.corretora}: ${r.recomendacao} ${a.fmt()}", estilo.copy(color = cor))
                drawText(txt, topLeft = Offset(4.dp.toPx(), max(0f, yy - txt.size.height - 2f)))
            }
        }

        alvoConsenso?.let { a ->
            val yy = y(a)
            drawLine(CorConsenso, Offset(0f, yy), Offset(w, yy), 1.5.dp.toPx(), pathEffect = tracejado)
            val txt = medidor.measure("Consenso analistas ${a.fmt()}", estilo.copy(color = CorConsenso))
            drawText(txt, topLeft = Offset(4.dp.toPx(), (yy + 2f).coerceAtMost(hPreco - txt.size.height)))
        }

        // Datas no rodapé
        listOf(0, n / 2, n - 1).distinct().forEach { i ->
            val txt = medidor.measure(candles[i].data.format(DATA_CURTA), estilo)
            val esquerda = (x(i) - txt.size.width / 2f).coerceIn(0f, max(0f, w - txt.size.width))
            drawText(txt, topLeft = Offset(esquerda, hTotal + 2.dp.toPx()))
        }

        // Valores do dia selecionado
        sel?.let { i ->
            val c = candles[i]
            val cx = x(i)
            drawLine(corTexto, Offset(cx, 0f), Offset(cx, hTotal), 1f)
            val txt = medidor.measure(
                "${c.data.br()}  A ${c.abertura.fmt()}  F ${c.fechamento.fmt()}  Máx ${c.maxima.fmt()}  Mín ${c.minima.fmt()}",
                estiloForte,
            )
            val margem = 4.dp.toPx()
            val larg = txt.size.width + 2 * margem
            val bx = (cx - larg / 2).coerceIn(0f, max(0f, size.width - larg))
            drawRoundRect(corRotulo, Offset(bx, 0f), Size(larg, txt.size.height + 2 * margem), CornerRadius(margem * 1.5f))
            drawText(txt, topLeft = Offset(bx + margem, margem))
        }
    }
}
