package com.bertonibr.pdfstudio.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** Diálogo com campo de texto (várias linhas). */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    singleLine: Boolean = false,
    password: Boolean = false,
    confirm: String = "OK",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 3,
                visualTransformation = if (password) PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Mostra um texto longo com opções de copiar e ouvir. */
@Composable
fun TextViewerDialog(text: String, onCopy: () -> Unit, onSpeak: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Texto extraído") },
        text = {
            SelectionContainer {
                Text(text, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        dismissButton = {
            Row {
                TextButton(onClick = onCopy) { Text("Copiar") }
                TextButton(onClick = onSpeak) { Text("Ouvir") }
            }
        },
    )
}

@Composable
fun ApiKeyDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chave de API do Claude") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Crie sua chave em console.anthropic.com → API Keys (começa com sk-ant-). " +
                        "A assinatura do claude.ai (Pro/Max) não vale para a API, que é cobrada por uso. " +
                        "A chave fica salva apenas neste aparelho."
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("sk-ant-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Desenhar (ou carregar) assinatura. Devolve PNG com fundo transparente. */
@Composable
fun SignatureDialog(onDismiss: () -> Unit, onDone: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val ink = Color(0xFF0B2A6F)

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (bmp != null) onDone(bmp.toPng())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assinatura") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Desenhe sua assinatura com o dedo:")
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .testTag("signaturePad")
                        .background(Color.White)
                        .border(1.dp, Color.Gray)
                        .pointerInput(Unit) {
                            detectExactDrag(
                                onStart = { currentStroke = listOf(it) },
                                onMove = { currentStroke = currentStroke + it },
                                onEnd = {
                                    // Toque curto vira um ponto (ex.: pingo do "i").
                                    val s = currentStroke
                                    strokes.add(if (s.size == 1) s + (s[0] + Offset(0.5f, 0.5f)) else s)
                                    currentStroke = emptyList()
                                },
                                onCancel = { currentStroke = emptyList() },
                            )
                        }
                ) {
                    (strokes + listOf(currentStroke)).forEach { pts ->
                        if (pts.size > 1) {
                            val path = Path().apply {
                                moveTo(pts[0].x, pts[0].y)
                                pts.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(path, ink, style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
                Row {
                    TextButton(onClick = { strokes.clear() }) { Text("Limpar") }
                    TextButton(onClick = { pickImage.launch("image/*") }) { Text("Usar imagem...") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = strokes.isNotEmpty(), onClick = {
                renderStrokes(strokes, android.graphics.Color.rgb(0x0B, 0x2A, 0x6F))?.let { onDone(it) }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun renderStrokes(strokes: List<List<Offset>>, color: Int): ByteArray? {
    val all = strokes.flatten()
    if (all.isEmpty()) return null
    val m = 8f
    val minX = all.minOf { it.x } - m
    val minY = all.minOf { it.y } - m
    val w = max(1, (all.maxOf { it.x } - minX + m).toInt())
    val h = max(1, (all.maxOf { it.y } - minY + m).toInt())
    val bmp = Bitmap.createBitmap(min(w, 4000), min(h, 4000), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { pts ->
        val path = android.graphics.Path()
        path.moveTo(pts[0].x - minX, pts[0].y - minY)
        pts.drop(1).forEach { path.lineTo(it.x - minX, it.y - minY) }
        canvas.drawPath(path, paint)
    }
    return bmp.toPng()
}

private fun Bitmap.toPng(): ByteArray {
    val bos = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.PNG, 100, bos)
    return bos.toByteArray()
}

/** Converte "1-3, 5, 8-" em índices 0-based. */
fun parseRange(spec: String, max: Int): List<Int> {
    val out = sortedSetOf<Int>()
    for (part in spec.split(Regex("[,;\\s]+"))) {
        if (part.isBlank()) continue
        val ab = part.split("-")
        val a = ab[0].trim().toIntOrNull() ?: throw IllegalArgumentException("Não entendi \"$part\".")
        val b = if (ab.size > 1) (if (ab[1].isBlank()) max else ab[1].trim().toIntOrNull()
            ?: throw IllegalArgumentException("Não entendi \"$part\".")) else a
        require(a in 1..b && b <= max) { "Fora do intervalo 1-$max: $part" }
        for (i in a..b) out += i - 1
    }
    require(out.isNotEmpty()) { "Nenhuma página informada." }
    return out.toList()
}
