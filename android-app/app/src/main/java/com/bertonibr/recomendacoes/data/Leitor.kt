package com.bertonibr.recomendacoes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/** Lê fotos, prints e PDFs com OCR no próprio aparelho (ML Kit, funciona offline). */
object Leitor {
    private const val MAX_PAGINAS = 30

    fun nomeArquivo(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun ehPdf(ctx: Context, uri: Uri): Boolean {
        val tipo = ctx.contentResolver.getType(uri)
        return tipo == "application/pdf" ||
            (tipo == null && (uri.path ?: "").endsWith(".pdf", ignoreCase = true)) ||
            nomeArquivo(ctx, uri)?.endsWith(".pdf", ignoreCase = true) == true
    }

    /** Retorna as palavras reconhecidas, uma lista por página. */
    suspend fun ler(ctx: Context, uri: Uri, progresso: (String) -> Unit): List<List<Trecho>> =
        withContext(Dispatchers.IO) {
            val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                if (ehPdf(ctx, uri)) {
                    val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                        ?: throw IOException("não foi possível abrir o PDF")
                    PdfRenderer(pfd).use { pdf ->
                        val total = min(pdf.pageCount, MAX_PAGINAS)
                        (0 until total).map { i ->
                            withContext(Dispatchers.Main) { progresso("Lendo página ${i + 1} de $total…") }
                            val bmp = pdf.openPage(i).use { pagina ->
                                var escala = (2000f / pagina.width).coerceIn(1f, 4f)
                                if (pagina.height * escala > 6000f) escala = 6000f / pagina.height
                                val b = Bitmap.createBitmap(
                                    (pagina.width * escala).toInt().coerceAtLeast(1),
                                    (pagina.height * escala).toInt().coerceAtLeast(1),
                                    Bitmap.Config.ARGB_8888,
                                )
                                b.eraseColor(Color.WHITE)
                                pagina.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                b
                            }
                            try {
                                trechos(ocr.process(InputImage.fromBitmap(bmp, 0)).aguardar())
                            } finally {
                                bmp.recycle()
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) { progresso("Lendo imagem…") }
                    listOf(trechos(ocr.process(InputImage.fromFilePath(ctx, uri)).aguardar()))
                }
            } finally {
                ocr.close()
            }
        }

    private fun trechos(texto: Text): List<Trecho> =
        texto.textBlocks.flatMap { bloco ->
            bloco.lines.flatMap { linha ->
                linha.elements.mapNotNull { el ->
                    el.boundingBox?.let { r ->
                        Trecho(el.text, r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
                    }
                }
            }
        }

    private suspend fun <T> Task<T>.aguardar(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }
}
