package com.bertonibr.pdfstudio.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.PDFTextStripperByArea
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.min

/**
 * Documento aberto + anotações pendentes. A edição usa o PdfBox-Android e a
 * exibição usa o PdfRenderer nativo do Android (sobre uma cópia em cache).
 * Não é thread-safe: use sempre a mesma thread.
 */
class PdfEditor private constructor(
    private val context: Context,
    private val doc: PDDocument,
) : Closeable {

    /** Chave: dicionário COS da página (getPage() cria um wrapper novo a cada chamada). */
    private val annotations = IdentityHashMap<COSDictionary, MutableList<Annotation>>()
    private val renderFile = File(context.cacheDir, "render-${System.nanoTime()}.pdf")
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    var dirty = false
        private set

    val pageCount: Int get() = doc.numberOfPages

    companion object {
        fun open(context: Context, bytes: ByteArray): PdfEditor =
            PdfEditor(context, PDDocument.load(bytes)).also { it.refreshRenderer() }

        fun fromImages(context: Context, images: List<Bitmap>): PdfEditor {
            val doc = PDDocument()
            addImagePages(doc, images)
            return PdfEditor(context, doc).also {
                it.dirty = true
                it.refreshRenderer()
            }
        }

        private fun addImagePages(doc: PDDocument, images: List<Bitmap>) {
            for (bmp in images) {
                val landscape = bmp.width > bmp.height
                val a4 = if (landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
                val page = PDPage(a4)
                doc.addPage(page)
                val img = JPEGFactory.createFromImage(doc, bmp, 0.9f)
                val margin = 24f
                val scale = min((a4.width - 2 * margin) / bmp.width, (a4.height - 2 * margin) / bmp.height)
                val w = bmp.width * scale
                val h = bmp.height * scale
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(img, (a4.width - w) / 2, (a4.height - h) / 2, w, h)
                }
            }
        }
    }

    // ------------------------------------------------------------ exibição

    /** Regrava a cópia de exibição e reabre o renderizador nativo. */
    private fun refreshRenderer() {
        renderer?.close()
        pfd?.close()
        doc.save(renderFile)
        pfd = ParcelFileDescriptor.open(renderFile, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(pfd!!)
    }

    class Rendered(val bitmap: Bitmap, val widthPt: Float, val heightPt: Float)

    /** Renderiza a página com a largura indicada em pixels. */
    fun render(index: Int, widthPx: Int): Rendered {
        renderer!!.openPage(index).use { page ->
            val wPt = page.width.toFloat()
            val hPt = page.height.toFloat()
            val w = widthPx.coerceIn(16, 4096)
            val h = (w * hPt / wPt).toInt().coerceAtLeast(16)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return Rendered(bmp, wPt, hPt)
        }
    }

    /** Renderiza uma página já com as anotações gravadas (para exportar PNG). */
    fun renderFlattened(index: Int, dpi: Int): Bitmap {
        val tmp = File(context.cacheDir, "export-${System.nanoTime()}.pdf")
        try {
            tmp.writeBytes(toBytes(true))
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    r.openPage(index).use { page ->
                        val bmp = Bitmap.createBitmap(page.width * dpi / 72, page.height * dpi / 72, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        return bmp
                    }
                }
            }
        } finally {
            tmp.delete()
        }
    }

    // ------------------------------------------------------------ anotações

    private fun key(i: Int): COSDictionary = doc.getPage(i).cosObject

    fun annotations(i: Int): List<Annotation> = annotations[key(i)]?.toList() ?: emptyList()

    fun addAnnotation(i: Int, a: Annotation) {
        annotations.getOrPut(key(i)) { mutableListOf() }.add(a)
        dirty = true
    }

    fun undoAnnotation(i: Int): Boolean {
        val l = annotations[key(i)] ?: return false
        if (l.isEmpty()) return false
        l.removeAt(l.size - 1)
        return true
    }

    fun clearAnnotations(i: Int) {
        annotations.remove(key(i))
    }

    /** Grava as anotações da página no conteúdo (deixam de ser editáveis). */
    private fun bake(i: Int) {
        val page = doc.getPage(i)
        val l = annotations.remove(page.cosObject)
        if (!l.isNullOrEmpty()) Flattener.flatten(doc, page, l, Flattener.loadFont(context, doc))
    }

    // ------------------------------------------------------------ páginas

    fun rotate(i: Int, delta: Int) {
        bake(i)
        val p = doc.getPage(i)
        p.rotation = ((p.rotation + delta) % 360 + 360) % 360
        dirty = true
        refreshRenderer()
    }

    fun deletePage(i: Int) {
        check(pageCount > 1) { "O documento precisa ter ao menos uma página." }
        annotations.remove(key(i))
        doc.removePage(i)
        dirty = true
        refreshRenderer()
    }

    fun movePage(from: Int, to: Int) {
        if (from == to || to !in 0 until pageCount) return
        val pages = doc.pages
        val p = pages.get(from)
        pages.remove(from)
        if (to >= pages.count) pages.add(p) else pages.insertBefore(p, pages.get(to))
        dirty = true
        refreshRenderer()
    }

    fun appendPdf(bytes: ByteArray) {
        PDDocument.load(bytes).use { src -> PDFMergerUtility().appendDocument(doc, src) }
        dirty = true
        refreshRenderer()
    }

    fun appendImages(images: List<Bitmap>) {
        addImagePages(doc, images)
        dirty = true
        refreshRenderer()
    }

    // ------------------------------------------------------------ saída

    /** Bytes do documento; com anotações, elas são gravadas numa cópia. */
    fun toBytes(withAnnotations: Boolean): ByteArray {
        val bos = ByteArrayOutputStream()
        doc.save(bos)
        if (!withAnnotations || annotations.values.all { it.isEmpty() }) return bos.toByteArray()
        PDDocument.load(bos.toByteArray()).use { copy ->
            val font = Flattener.loadFont(context, copy)
            for (i in 0 until pageCount) {
                Flattener.flatten(copy, copy.getPage(i), annotations[key(i)], font)
            }
            val out = ByteArrayOutputStream()
            copy.save(out)
            return out.toByteArray()
        }
    }

    fun markSaved() {
        dirty = false
    }

    /** PDF (com anotações) contendo apenas as páginas indicadas (0-based). */
    fun pagesToBytes(pageIdx: Collection<Int>): ByteArray {
        PDDocument.load(toBytes(true)).use { copy ->
            val keep = pageIdx.toSet()
            for (i in copy.numberOfPages - 1 downTo 0) if (i !in keep) copy.removePage(i)
            val out = ByteArrayOutputStream()
            copy.save(out)
            return out.toByteArray()
        }
    }

    // ------------------------------------------------------------ texto

    fun extractText(from: Int, to: Int): String {
        val s = PDFTextStripper()
        s.sortByPosition = true
        s.startPage = from + 1
        s.endPage = to + 1
        return s.getText(doc)
    }

    fun allText(): String = extractText(0, pageCount - 1)

    /** Texto dentro de um retângulo (coordenadas de exibição, em pontos). */
    fun textInArea(i: Int, x: Float, y: Float, w: Float, h: Float): String {
        val s = PDFTextStripperByArea()
        s.sortByPosition = true
        s.addRegion("r", RectF(x, y, x + w, y + h))
        s.extractRegions(doc.getPage(i))
        return s.getTextForRegion("r").trim()
    }

    fun search(term: String): List<Int> {
        val q = term.lowercase(Locale.ROOT)
        return (0 until pageCount).filter { extractText(it, it).lowercase(Locale.ROOT).contains(q) }
    }

    override fun close() {
        renderer?.close()
        pfd?.close()
        renderFile.delete()
        doc.close()
    }
}
