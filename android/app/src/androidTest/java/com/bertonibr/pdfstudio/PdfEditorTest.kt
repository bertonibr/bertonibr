package com.bertonibr.pdfstudio

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bertonibr.pdfstudio.TestPdfs.assertRed
import com.bertonibr.pdfstudio.TestPdfs.assertWhite
import com.bertonibr.pdfstudio.TestPdfs.describe
import com.bertonibr.pdfstudio.model.Annotation
import com.bertonibr.pdfstudio.model.PdfEditor
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Motor de PDF no Android: abrir, desenhar, anotar, páginas, texto, senha. */
@RunWith(AndroidJUnit4::class)
class PdfEditorTest {

    private val ctx get() = TestPdfs.context

    @Before
    fun setUp() = TestPdfs.init()

    private fun open(bytes: ByteArray, password: String? = null) = PdfEditor.open(ctx, bytes, password)

    @Test
    fun opensRendersAndExtractsText() {
        open(TestPdfs.sample(3)).use { e ->
            assertEquals(3, e.pageCount)
            val r = e.render(0, 600)
            assertEquals(595f, r.widthPt, 1f)
            assertEquals(842f, r.heightPt, 1f)
            assertEquals(600, r.bitmap.width)
            assertTrue("a página deveria ter texto desenhado", TestPdfs.countDarkPixels(r.bitmap) > 20)
            assertTrue(e.extractText(1, 1).contains("Pagina 2 conteudo original"))
            assertTrue(e.allText().contains("Pagina 3"))
            assertEquals("Pagina 1 conteudo original", e.textInArea(0, 60f, 120f, 400f, 40f))
            assertEquals("", e.textInArea(0, 60f, 400f, 100f, 40f))
            assertEquals(listOf(2), e.search("PAGINA 3"))
            assertFalse(e.dirty)
        }
    }

    /** Miniatura do histórico: largura pedida e, depois de salvar, com as anotações. */
    @Test
    fun thumbnailForHistory() {
        open(TestPdfs.sample(2)).use { e ->
            val plain = e.thumbnail(200)
            assertEquals(200, plain.width)
            assertEquals(283f, plain.height.toFloat(), 2f) // proporção A4
            assertWhite(plain.getPixel(100, 25), "sem anotação:")

            // Quadrado vermelho de 300 pt no topo da página 1 (ainda não gravado no documento).
            e.addAnnotation(0, Annotation.Whiteout(150f, 20f, 300f, 300f, Color.RED))
            assertWhite(e.thumbnail(200).getPixel(100, 25), "sem os bytes salvos:")
            val saved = e.toBytes(true)
            assertRed(e.thumbnail(200, saved).getPixel(100, 25), "com os bytes salvos:")
        }
    }

    /** A marca precisa cair no mesmo lugar da tela em todas as rotações. */
    @Test
    fun annotationsLandWhereDrawnOnEveryRotation() {
        for (rot in listOf(0, 90, 180, 270)) {
            open(TestPdfs.sample(1)).use { e ->
                if (rot != 0) e.rotate(0, rot)
                val r = e.render(0, 400)
                if (rot % 180 == 0) {
                    assertTrue("rot $rot: ${r.widthPt}x${r.heightPt}", r.widthPt < r.heightPt)
                } else {
                    assertTrue("rot $rot: ${r.widthPt}x${r.heightPt}", r.widthPt > r.heightPt)
                }
                e.addAnnotation(0, Annotation.Whiteout(100f, 60f, 50f, 40f, Color.RED))
                val bmp = e.renderFlattened(0, 72) // 1 pixel = 1 ponto
                assertEquals("rot $rot largura", r.widthPt, bmp.width.toFloat(), 2f)
                assertRed(bmp.getPixel(125, 80), "rot $rot dentro da marca:")
                assertWhite(bmp.getPixel(125, 20), "rot $rot acima da marca:")
                assertWhite(bmp.getPixel(200, 80), "rot $rot à direita da marca:")
            }
        }
    }

    @Test
    fun allAnnotationKindsAreSavedIntoThePdf() {
        open(TestPdfs.sample(1)).use { e ->
            e.addAnnotation(0, Annotation.Text(72f, 300f, "Olá, ação! ✓", 18f, Color.BLUE))
            e.addAnnotation(0, Annotation.Highlight(300f, 400f, 120f, 30f, Color.YELLOW))
            e.addAnnotation(0, Annotation.Ink(floatArrayOf(50f, 500f, 250f, 500f), Color.GREEN, 4f))
            e.addAnnotation(0, Annotation.Image(TestPdfs.png(40, 20, Color.BLUE), 400f, 600f, 80f, 40f))
            assertEquals(4, e.annotations(0).size)
            assertTrue(e.dirty)

            PdfEditor.open(ctx, e.toBytes(true)).use { saved ->
                val text = saved.extractText(0, 0)
                assertTrue(text, text.contains("Olá, ação!"))
                assertTrue(text.contains("Pagina 1"))
                assertTrue(saved.annotations(0).isEmpty())
            }
            // Sem anotações, o PDF original continua igual.
            PdfEditor.open(ctx, e.toBytes(false)).use { assertFalse(it.extractText(0, 0).contains("Olá")) }

            val bmp = e.renderFlattened(0, 72)
            val hl = bmp.getPixel(360, 415)
            assertTrue("destaque ${describe(hl)}", Color.red(hl) > 200 && Color.green(hl) > 200 && Color.blue(hl) < 210)
            val ink = bmp.getPixel(150, 500)
            assertTrue("desenho ${describe(ink)}", Color.green(ink) > 120 && Color.red(ink) < 120)
            val img = bmp.getPixel(440, 620)
            assertTrue("imagem ${describe(img)}", Color.blue(img) > 200 && Color.red(img) < 60)
            val txt = TestPdfs.countDarkPixels(android.graphics.Bitmap.createBitmap(bmp, 72, 300, 200, 25))
            assertTrue("texto inserido deveria aparecer", txt > 0 || hasBlue(bmp, 72, 300, 200, 25))
        }
    }

    private fun hasBlue(b: android.graphics.Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean {
        for (yy in y until y + h) for (xx in x until x + w) {
            val c = b.getPixel(xx, yy)
            if (Color.blue(c) > 150 && Color.red(c) < 100) return true
        }
        return false
    }

    @Test
    fun undoAndClear() {
        open(TestPdfs.sample(1)).use { e ->
            e.addAnnotation(0, Annotation.Whiteout(1f, 1f, 5f, 5f, Color.WHITE))
            e.addAnnotation(0, Annotation.Whiteout(10f, 10f, 5f, 5f, Color.WHITE))
            assertTrue(e.undoAnnotation(0))
            assertEquals(1, e.annotations(0).size)
            e.clearAnnotations(0)
            assertTrue(e.annotations(0).isEmpty())
            assertFalse(e.undoAnnotation(0))
        }
    }

    @Test
    fun pageOperations() {
        open(TestPdfs.sample(3)).use { e ->
            e.appendPdf(TestPdfs.sample(2, "Extra"))
            assertEquals(5, e.pageCount)
            assertTrue(e.extractText(4, 4).contains("Extra 2"))

            e.movePage(0, 2)
            assertTrue(e.extractText(0, 0).contains("Pagina 2"))
            assertTrue(e.extractText(2, 2).contains("Pagina 1"))

            e.deletePage(4)
            assertEquals(4, e.pageCount)
            e.render(3, 200) // o renderizador acompanha as mudanças

            PdfEditor.open(ctx, e.pagesToBytes(listOf(0, 2))).use { part ->
                assertEquals(2, part.pageCount)
                assertTrue(part.extractText(1, 1).contains("Pagina 1"))
            }

            e.appendImages(listOf(TestPdfs.bitmap(300, 200, Color.RED)))
            assertEquals(5, e.pageCount)
            val r = e.render(4, 300)
            assertTrue("página de imagem paisagem", r.widthPt > r.heightPt)
            assertRed(r.bitmap.getPixel(r.bitmap.width / 2, r.bitmap.height / 2), "imagem inserida:")
            assertTrue(e.dirty)
        }
    }

    @Test
    fun cannotDeleteTheOnlyPage() {
        open(TestPdfs.sample(1)).use { e ->
            assertThrows(IllegalStateException::class.java) { e.deletePage(0) }
        }
    }

    @Test
    fun createPdfFromImages() {
        PdfEditor.fromImages(ctx, listOf(TestPdfs.bitmap(600, 800, Color.BLUE), TestPdfs.bitmap(800, 600, Color.GREEN))).use { e ->
            assertEquals(2, e.pageCount)
            val p1 = e.render(0, 300)
            assertTrue(p1.widthPt < p1.heightPt)
            val c = p1.bitmap.getPixel(150, p1.bitmap.height / 2)
            assertTrue(describe(c), Color.blue(c) > 200 && Color.red(c) < 60)
            assertTrue(e.render(1, 300).let { it.widthPt > it.heightPt })
            assertTrue(e.dirty)
        }
    }

    @Test
    fun ownerPasswordPdfOpensAndSaves() {
        open(TestPdfs.protectedPdf(owner = "dono", user = "")).use { e ->
            assertEquals(1, e.pageCount)
            e.addAnnotation(0, Annotation.Text(72f, 300f, "Anotado", 14f, Color.BLACK))
            PdfEditor.open(ctx, e.toBytes(true)).use { assertTrue(it.extractText(0, 0).contains("Anotado")) }
        }
    }

    @Test
    fun userPasswordPdfNeedsThePassword() {
        val bytes = TestPdfs.protectedPdf(owner = "dono", user = "1234")
        assertThrows(InvalidPasswordException::class.java) { PdfEditor.open(ctx, bytes) }
        assertThrows(InvalidPasswordException::class.java) { PdfEditor.open(ctx, bytes, "errada") }
        open(bytes, "1234").use { e ->
            assertTrue(e.extractText(0, 0).contains("Pagina 1"))
            PdfEditor.open(ctx, e.toBytes(true)).use { assertEquals(1, it.pageCount) } // salvo sem senha
        }
    }

    /** Anotações e formulários que já vêm no PDF precisam aparecer na tela. */
    @Test
    fun existingAnnotationsAndFormsAreDisplayed() {
        val bytes = PDDocument.load(TestPdfs.sample(1)).use { d ->
            val page = d.getPage(0)
            val red = PDColor(floatArrayOf(1f, 0f, 0f), PDDeviceRGB.INSTANCE)
            val square = PDAnnotationSquareCircle(PDAnnotationSquareCircle.SUB_TYPE_SQUARE)
            square.rectangle = PDRectangle(100f, 500f, 80f, 60f) // tela: x 100..180, y 282..342
            square.color = red
            square.interiorColor = red
            square.constructAppearances(d)

            val form = PDAcroForm(d)
            d.documentCatalog.acroForm = form
            val res = PDResources()
            res.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
            form.defaultResources = res
            form.defaultAppearance = "/Helv 0 Tf 0 g"
            val field = PDTextField(form)
            field.partialName = "nome"
            field.defaultAppearance = "/Helv 18 Tf 0 0 0 rg"
            val widget = field.widgets[0]
            widget.rectangle = PDRectangle(100f, 200f, 300f, 30f) // tela: y 612..642
            widget.page = page
            form.fields = listOf(field)
            page.annotations = listOf(square, widget)
            field.value = "Campo preenchido"
            TestPdfs.save(d)
        }

        open(bytes).use { e ->
            val bmp = e.render(0, 595).bitmap // 1 pixel ≈ 1 ponto
            assertRed(bmp.getPixel(140, 312), "anotação existente:")
            val formArea = android.graphics.Bitmap.createBitmap(bmp, 100, 612, 300, 30)
            assertTrue("campo de formulário preenchido deveria aparecer", TestPdfs.countDarkPixels(formArea) > 5)
            // O arquivo salvo continua com as anotações originais (não achatadas).
            PDDocument.load(e.toBytes(true)).use { saved ->
                assertEquals(2, saved.getPage(0).annotations.size)
                assertEquals("Campo preenchido", saved.documentCatalog.acroForm.getField("nome").valueAsString)
            }
        }
    }
}
