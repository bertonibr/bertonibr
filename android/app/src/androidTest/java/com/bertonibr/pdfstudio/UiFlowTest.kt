package com.bertonibr.pdfstudio

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bertonibr.pdfstudio.model.Annotation
import com.bertonibr.pdfstudio.model.PdfEditor
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Fluxos de uso pela interface, como um usuário tocando na tela. */
@RunWith(AndroidJUnit4::class)
class UiFlowTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var vm: PdfStudioViewModel
    private val ctx get() = TestPdfs.context

    @Before
    fun setUp() {
        TestPdfs.init()
        vm = rule.runOnUiThread { ViewModelProvider(rule.activity)[PdfStudioViewModel::class.java] }
        rule.runOnUiThread {
            vm.apiKey = ""
            vm.claudeBaseUrl = null
        }
    }

    // ---------------------------------------------------------------- ajudantes

    private fun openSample(pages: Int = 3, name: String = "teste.pdf") {
        val file = TestPdfs.writeCache(name, TestPdfs.sample(pages))
        rule.runOnUiThread { vm.open(Uri.fromFile(file)) }
        rule.waitUntil(20_000) { vm.pageCount == pages && vm.page != null && vm.busy == null }
        rule.waitForIdle()
    }

    private fun waitIdleVm() = rule.waitUntil(20_000) { vm.busy == null }

    private fun waitAnnotations(n: Int) {
        rule.waitUntil(10_000) { vm.annotations.size == n }
        rule.waitForIdle()
    }

    private fun selectTool(label: String) {
        rule.onNodeWithTag("tools").performScrollToNode(hasText(label))
        rule.onNode(hasText(label) and hasClickAction()).performClick()
        rule.waitForIdle()
    }

    private fun swipeOnPage(fromX: Float = 0.2f, fromY: Float = 0.25f, toX: Float = 0.6f, toY: Float = 0.32f) {
        rule.onNodeWithTag("page").performTouchInput {
            swipe(Offset(width * fromX, height * fromY), Offset(width * toX, height * toY), durationMillis = 300)
        }
        rule.waitForIdle()
    }

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    // ---------------------------------------------------------------- testes

    @Test
    fun welcomeScreen() {
        rule.onNodeWithText("Abrir PDF").assertIsDisplayed()
        rule.onNodeWithText("Criar PDF a partir de imagens").assertIsDisplayed()
    }

    @Test
    fun navigationZoomAndPinch() {
        openSample()
        rule.onNodeWithText("teste.pdf").assertIsDisplayed()
        rule.onNodeWithText("1 / 3").assertIsDisplayed()
        rule.onNodeWithContentDescription("Próxima").performClick()
        rule.waitUntil(5_000) { exists("2 / 3") }
        rule.onNodeWithContentDescription("Anterior").performClick()
        rule.waitUntil(5_000) { exists("1 / 3") }

        // Todos os botões da barra inferior cabem na tela.
        rule.onNodeWithContentDescription("Ler página").assertIsDisplayed()
        rule.onNodeWithContentDescription("Ampliar").assertIsDisplayed()

        rule.onNodeWithContentDescription("Ampliar").performClick()
        rule.waitUntil(5_000) { exists("125%") }
        rule.onNodeWithContentDescription("Reduzir").performClick()
        rule.waitUntil(5_000) { exists("100%") }

        // Pinça com dois dedos amplia a página.
        rule.waitUntil(10_000) { vm.busy == null && vm.page != null }
        val before = vm.page!!.bitmap.width
        rule.onNodeWithTag("page").performTouchInput {
            pinch(
                start0 = center - Offset(40f, 0f), end0 = center - Offset(260f, 0f),
                start1 = center + Offset(40f, 0f), end1 = center + Offset(260f, 0f),
                durationMillis = 500,
            )
        }
        rule.waitUntil(15_000) { (vm.page?.bitmap?.width ?: 0) > before }
        assertTrue(!exists("100%"))

        // Toque duplo volta para 100%.
        rule.onRoot().performTouchInput { doubleClick(center) }
        rule.waitUntil(10_000) { exists("100%") }
    }

    @Test
    fun annotationTools() {
        openSample(1)

        selectTool("Texto")
        rule.onNodeWithTag("page").performTouchInput { click(Offset(width * 0.2f, height * 0.5f)) }
        rule.waitUntil(5_000) { exists("Inserir texto") }
        rule.onNode(hasSetTextAction()).performTextInput("Teste de UI")
        rule.onNodeWithText("OK").performClick()
        waitAnnotations(1)
        assertTrue(vm.annotations.last() is Annotation.Text)
        assertEquals("Teste de UI", (vm.annotations.last() as Annotation.Text).text)
        assertTrue(vm.dirty)
        rule.waitUntil(5_000) { exists("• teste.pdf") }

        selectTool("Destacar")
        swipeOnPage()
        waitAnnotations(2)
        assertTrue(vm.annotations.last() is Annotation.Highlight)

        selectTool("Desenhar")
        swipeOnPage(0.2f, 0.6f, 0.7f, 0.65f)
        waitAnnotations(3)
        assertTrue(vm.annotations.last() is Annotation.Ink)

        selectTool("Apagar")
        swipeOnPage(0.5f, 0.7f, 0.8f, 0.8f)
        waitAnnotations(4)
        assertTrue(vm.annotations.last() is Annotation.Whiteout)

        rule.onNodeWithContentDescription("Desfazer").performClick()
        waitAnnotations(3)

        // Editar texto: arrastar sobre "Pagina 1 conteudo original" (base em y = 142 pt).
        selectTool("Editar texto")
        rule.onNodeWithTag("page").performTouchInput {
            val k = width / 595f
            swipe(Offset(60f * k, 112f * k), Offset(330f * k, 160f * k), durationMillis = 300)
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Editar texto").fetchSemanticsNodes().size >= 2 }
        rule.onNode(hasSetTextAction()).assertTextContains("Pagina 1", substring = true)
        rule.onNode(hasSetTextAction()).performTextReplacement("Novo texto")
        rule.onNodeWithText("OK").performClick()
        waitAnnotations(5)
        assertTrue(vm.annotations[3] is Annotation.Whiteout)
        assertEquals("Novo texto", (vm.annotations[4] as Annotation.Text).text)

        // Assinatura: desenhar no quadro e posicionar na página.
        selectTool("Assinar")
        rule.onNodeWithTag("page").performTouchInput { click(Offset(width * 0.5f, height * 0.4f)) }
        rule.waitUntil(5_000) { exists("Assinatura") }
        rule.onNodeWithTag("signaturePad").performTouchInput {
            swipe(Offset(30f, 40f), Offset(width - 30f, height - 40f), durationMillis = 300)
        }
        rule.onNodeWithText("OK").performClick()
        waitAnnotations(6)
        assertTrue(vm.annotations.last() is Annotation.Image)
    }

    @Test
    fun pagesSheet() {
        openSample(3)
        rule.onNodeWithContentDescription("Páginas").performClick()
        rule.waitUntil(5_000) { exists("Páginas (3)") }

        rule.onAllNodesWithContentDescription("Girar")[0].performClick()
        rule.waitUntil(15_000) { vm.page?.let { it.widthPt > it.heightPt } == true }

        rule.onAllNodesWithContentDescription("Excluir")[0].performClick()
        rule.waitUntil(5_000) { exists("Excluir página 1?") }
        rule.onNodeWithText("Excluir").performClick()
        rule.waitUntil(15_000) { vm.pageCount == 2 }
        rule.waitUntil(5_000) { exists("Páginas (2)") }

        rule.onAllNodesWithContentDescription("Mover para frente")[0].performClick()
        rule.waitUntil(15_000) { vm.busy == null && vm.current == 1 }
        rule.runOnUiThread { vm.showText(true) }
        rule.waitUntil(15_000) { vm.extractedText != null }
        val text = vm.extractedText!!
        assertTrue(text, text.indexOf("Pagina 3") in 0 until text.indexOf("Pagina 2"))
        assertTrue(!text.contains("Pagina 1"))
    }

    @Test
    fun menuTextAndSearch() {
        openSample(3)
        rule.onNodeWithContentDescription("Mais").performClick()
        rule.onNodeWithText("Texto da página").performClick()
        rule.waitUntil(10_000) { exists("Texto extraído") }
        rule.onNodeWithText("Pagina 1 conteudo original", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Fechar").performClick()

        rule.onNodeWithContentDescription("Mais").performClick()
        rule.onNodeWithText("Localizar...").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Pagina 3")
        rule.onNodeWithText("OK").performClick()
        rule.waitUntil(15_000) { vm.current == 2 }
        rule.waitUntil(5_000) { exists("3 / 3") }
    }

    @Test
    fun claudeWithoutKeyAsksForIt() {
        openSample(1)
        rule.onNodeWithContentDescription("Claude").performClick()
        rule.waitUntil(5_000) { exists("Configurar chave") }
        rule.onNodeWithText("Resumir documento").performClick()
        rule.waitUntil(5_000) { exists("Configure sua chave de API do Claude primeiro.") }
    }

    @Test
    fun claudeSummaryWithSimulatedServer() {
        openSample(2)
        val server = MockWebServer()
        server.enqueue(TestPdfs.claudeStream("Resumo ", "do teste"))
        server.enqueue(TestPdfs.claudeStream("Resposta ", "da pergunta"))
        server.start()
        try {
            rule.runOnUiThread {
                vm.apiKey = "sk-test"
                vm.claudeBaseUrl = server.url("/").toString()
            }
            rule.onNodeWithContentDescription("Claude").performClick()
            rule.waitUntil(5_000) { exists("Resumir documento") }
            rule.onNodeWithText("Resumir documento").performClick()
            rule.waitUntil(30_000) { vm.aiText.contains("Resumo do teste") && !vm.aiRunning }
            rule.onNodeWithText("Resumo do teste", substring = true).assertIsDisplayed()
            assertTrue(server.takeRequest().body.readUtf8().contains("\"type\":\"document\""))

            rule.onNode(hasSetTextAction()).performTextInput("Qual o assunto?")
            rule.onNodeWithContentDescription("Perguntar").performClick()
            rule.waitUntil(30_000) { vm.aiText.contains("Resposta da pergunta") && !vm.aiRunning }
            assertTrue(server.takeRequest().body.readUtf8().contains("Qual o assunto?"))
        } finally {
            server.shutdown()
            rule.runOnUiThread {
                vm.apiKey = ""
                vm.claudeBaseUrl = null
            }
        }
    }

    @Test
    fun saveExportAndReadAloud() {
        openSample(2)
        rule.runOnUiThread { vm.addAnnotation(Annotation.Text(72f, 300f, "Salvo pelo teste", 14f, Color.BLACK)) }
        rule.waitUntil(10_000) { vm.dirty }

        val out = File(ctx.cacheDir, "salvo.pdf").apply { delete() }
        rule.runOnUiThread { vm.saveTo(Uri.fromFile(out)) }
        rule.waitUntil(15_000) { !vm.dirty && out.length() > 0 }
        PdfEditor.open(ctx, out.readBytes()).use { assertTrue(it.extractText(0, 0).contains("Salvo pelo teste")) }
        rule.waitUntil(5_000) { exists("salvo.pdf") }

        val txt = File(ctx.cacheDir, "texto.txt").apply { delete() }
        rule.runOnUiThread { vm.exportText(Uri.fromFile(txt)) }
        waitIdleVm()
        assertTrue(txt.readText().contains("Pagina 2"))

        val png = File(ctx.cacheDir, "pagina.png").apply { delete() }
        rule.runOnUiThread { vm.exportPagePng(Uri.fromFile(png)) }
        waitIdleVm()
        val bmp = BitmapFactory.decodeFile(png.absolutePath)
        assertEquals(1240f, bmp.width.toFloat(), 2f) // A4 a 150 dpi

        val part = File(ctx.cacheDir, "parte.pdf").apply { delete() }
        rule.runOnUiThread { vm.extractPages(listOf(1), Uri.fromFile(part)) }
        waitIdleVm()
        PdfEditor.open(ctx, part.readBytes()).use {
            assertEquals(1, it.pageCount)
            assertTrue(it.extractText(0, 0).contains("Pagina 2"))
        }

        // Ler em voz alta não pode travar o app (o áudio em si não é verificável aqui).
        rule.onNodeWithContentDescription("Ler página").performClick()
        Thread.sleep(1500)
        rule.runOnUiThread { vm.stopSpeaking() }
        rule.onNodeWithText("salvo.pdf").assertIsDisplayed()
    }

    @Test
    fun backWithUnsavedChangesAsks() {
        openSample(1)
        rule.runOnUiThread { vm.addAnnotation(Annotation.Whiteout(10f, 10f, 20f, 20f, Color.WHITE)) }
        rule.waitUntil(10_000) { vm.dirty }
        rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(5_000) { exists("Alterações não salvas") }
        rule.onNodeWithText("Cancelar").performClick()
        rule.waitForIdle()
        assertTrue(vm.hasDocument)
    }

    @Test
    fun passwordProtectedPdfAsksForPassword() {
        val file = TestPdfs.writeCache("protegido.pdf", TestPdfs.protectedPdf("dono", "1234"))
        rule.runOnUiThread { vm.open(Uri.fromFile(file)) }
        rule.waitUntil(15_000) { exists("PDF protegido") }
        rule.onNode(hasSetTextAction()).performTextInput("1234")
        rule.onNodeWithText("Abrir").performClick()
        rule.waitUntil(15_000) { vm.pageCount == 1 && vm.page != null }
        rule.waitUntil(5_000) { exists("protegido.pdf") }
    }

    @Test
    fun createPdfFromImages() {
        val img = File(ctx.cacheDir, "foto.png").apply { writeBytes(TestPdfs.png(800, 600, Color.GREEN)) }
        rule.runOnUiThread { vm.createFromImages(listOf(Uri.fromFile(img), Uri.fromFile(img))) }
        rule.waitUntil(20_000) { vm.pageCount == 2 && vm.page != null }
        rule.waitUntil(5_000) { exists("• Novo documento.pdf") }
    }

    @Test
    fun openWithFromAnotherApp() {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "compartilhado.pdf").apply { writeBytes(TestPdfs.sample(2)) }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        rule.runOnUiThread {
            rule.activity.handleIntent(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
        rule.waitUntil(20_000) { vm.pageCount == 2 && vm.page != null }
        rule.waitUntil(5_000) { exists("compartilhado.pdf") }
        rule.onNodeWithText("1 / 2").assertIsDisplayed()
    }
}
