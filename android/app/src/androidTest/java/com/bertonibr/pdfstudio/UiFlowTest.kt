package com.bertonibr.pdfstudio

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
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
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bertonibr.pdfstudio.model.Annotation
import com.bertonibr.pdfstudio.model.PdfEditor
import com.bertonibr.pdfstudio.model.RecentStore
import com.bertonibr.pdfstudio.service.TextTranslator
import com.bertonibr.pdfstudio.service.TranslationResult
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
            // Tradução simulada (rápida e sem internet) nos testes de interface.
            vm.translator = fakeTranslator
            vm.setAutoTranslate(true)
            // Cada teste começa com o histórico vazio (senão reabriria na página onde o anterior parou).
            vm.clearRecents(includeFavorites = true)
        }
    }

    private val fakeTranslator = object : TextTranslator {
        override suspend fun translate(text: String, onStatus: (String) -> Unit): TranslationResult {
            onStatus("Baixando o tradutor...")
            kotlinx.coroutines.delay(50)
            return TranslationResult("PT: $text", "en")
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
        rule.onNodeWithTag("page-0").performTouchInput {
            swipe(Offset(width * fromX, height * fromY), Offset(width * toX, height * toY), durationMillis = 300)
        }
        rule.waitForIdle()
    }

    /** Árvore de acessibilidade de todas as janelas — vai na mensagem de erro. */
    private fun dumpTree(): String = try {
        rule.onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE).take(8000)
    } catch (e: Throwable) {
        "(árvore indisponível: $e)"
    }

    private fun vmState(): String = try {
        "message=${vm.message} selection=${vm.selection} busy=${vm.busy} tool=${vm.tool} " +
            "annotations=${vm.annotations.size} page=${vm.current + 1}/${vm.pageCount}"
    } catch (e: Throwable) {
        "(estado indisponível: $e)"
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs, condition)
        } catch (e: Throwable) {
            throw AssertionError("Tempo esgotado esperando: $what\n--- Estado ---\n${vmState()}\n--- Tela ---\n${dumpTree()}", e)
        }
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
    fun continuousScrollThroughPages() {
        openSample(4)
        rule.onNodeWithText("1 / 4").assertIsDisplayed()
        // Arrastar para cima várias vezes passa pelas páginas seguintes.
        repeat(3) {
            rule.onNodeWithTag("viewer").performTouchInput {
                swipeUp(startY = height * 0.85f, endY = height * 0.15f, durationMillis = 250)
            }
        }
        waitFor("rolar para a página 2 ou mais", 15_000) { vm.current >= 1 }
        waitFor("página visível renderizada", 15_000) { vm.pageBitmaps.containsKey(vm.current) }
        waitFor("indicador acompanha a rolagem") { exists("${vm.current + 1} / 4") }

        // Até o fim do documento: última página.
        repeat(8) {
            rule.onNodeWithTag("viewer").performTouchInput {
                swipeUp(startY = height * 0.85f, endY = height * 0.15f, durationMillis = 200)
            }
        }
        waitFor("chegar à última página", 15_000) { vm.current == 3 }
        waitFor("indicador 4 / 4") { exists("4 / 4") }

        // E de volta para cima.
        repeat(10) {
            rule.onNodeWithTag("viewer").performTouchInput {
                swipeDown(startY = height * 0.15f, endY = height * 0.85f, durationMillis = 200)
            }
        }
        waitFor("voltar à primeira página", 15_000) { vm.current == 0 }

        // A seta "próxima" rola até a página seguinte.
        rule.onNodeWithContentDescription("Próxima").performClick()
        waitFor("seta leva à página 2") { vm.current == 1 && exists("2 / 4") }
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
        rule.onNodeWithTag("page-0").performTouchInput {
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

    /**
     * Pinça como num celular de verdade: o primeiro dedo encosta e já começa a
     * arrastar (rolando a página); o segundo chega depois; os dedos se afastam.
     */
    private fun realisticPinch() {
        rule.onNodeWithTag("viewer").performTouchInput {
            val c = Offset(width * 0.5f, height * 0.3f)
            down(0, c - Offset(60f, 0f))
            moveTo(0, c - Offset(60f, 40f)) // primeiro dedo sozinho, além da tolerância de toque
            moveTo(0, c - Offset(60f, 80f))
            down(1, c + Offset(60f, -80f))
            for (step in 1..12) {
                updatePointerTo(0, c - Offset(60f + step * 20f, 80f))
                updatePointerTo(1, c + Offset(60f + step * 20f, -80f))
                move()
            }
            up(0)
            up(1)
        }
    }

    @Test
    fun pinchWithFingersLandingOneAfterAnother() {
        openSample(2)
        waitFor("página pronta") { vm.busy == null && vm.page != null }
        val before = vm.page!!.bitmap.width
        realisticPinch()
        waitFor("zoom maior após a pinça (navegar)", 15_000) { (vm.page?.bitmap?.width ?: 0) > before }
        assertTrue(!exists("100%"))

        // Também funciona com uma ferramenta de desenho ativa, sem riscar a página.
        rule.onRoot().performTouchInput { doubleClick(center) }
        waitFor("voltar a 100%") { exists("100%") }
        waitFor("render em 100%", 15_000) { vm.busy == null && vm.page?.bitmap?.width == before }
        selectTool("Desenhar")
        realisticPinch()
        waitFor("zoom maior após a pinça (desenhar)", 15_000) { (vm.page?.bitmap?.width ?: 0) > before }
        rule.waitForIdle()
        assertTrue("a pinça não pode virar desenho", vm.annotations.isEmpty())
    }

    private fun inSelectionBar(label: String) =
        rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag("selectionBar")))

    private fun longPressOnFirstWord(k: Float) {
        // "Pagina" fica em x ≈ 72..116 pt, com a base da linha em y = 142 pt.
        rule.onNodeWithTag("page-0").performTouchInput { longClick(Offset(76f * k, 137f * k)) }
    }

    @Test
    fun longPressSelectsAndCopiesText() {
        openSample(1)
        val k = rule.onNodeWithTag("page-0").fetchSemanticsNode().size.width / 595f

        longPressOnFirstWord(k)
        waitFor("palavra selecionada") { vm.selection != null }
        assertEquals("Pagina", vm.selectedText)
        rule.onNodeWithTag("selectionBar").assertIsDisplayed()
        rule.onNodeWithTag("selStart").assertExists()

        inSelectionBar("Copiar").performClick()
        waitFor("seleção fechada após copiar") { vm.selection == null }
        assertEquals("Pagina", vm.lastCopied)

        // Arrastar a alça do fim para a direita estende a seleção.
        longPressOnFirstWord(k)
        waitFor("seleção de novo") { vm.selection != null }
        rule.onNodeWithTag("selEnd").performTouchInput {
            swipe(center, center + Offset(130f * k, 0f), durationMillis = 500)
        }
        waitFor("seleção estendida") { vm.selectedText.contains("conteudo") }
        assertTrue(vm.selectedText, vm.selectedText.startsWith("Pagina 1"))

        inSelectionBar("Tudo").performClick()
        waitFor("tudo selecionado") { vm.selectedText == "Pagina 1 conteudo original" }

        inSelectionBar("Destacar").performClick()
        waitAnnotations(1)
        assertTrue(vm.annotations.single() is Annotation.Highlight)
        assertTrue(vm.selection == null)
    }

    @Test
    fun tapClearsSelectionAndBlankLongPressSelectsNothing() {
        openSample(1)
        val k = rule.onNodeWithTag("page-0").fetchSemanticsNode().size.width / 595f

        longPressOnFirstWord(k)
        waitFor("palavra selecionada") { vm.selection != null }
        rule.onNodeWithTag("page-0").performTouchInput { click(Offset(300f * k, 600f * k)) }
        waitFor("seleção fechada com toque") { vm.selection == null }

        rule.onNodeWithTag("page-0").performTouchInput { longClick(Offset(300f * k, 600f * k)) }
        val notice = "Nenhum texto neste ponto."
        waitFor("aviso de nenhum texto") { vm.message == notice || exists(notice) }
        assertTrue(vm.selection == null)
    }

    @Test
    fun selectingTextShowsPortugueseTranslation() {
        openSample(1)
        val k = rule.onNodeWithTag("page-0").fetchSemanticsNode().size.width / 595f

        // Tradução automática ao selecionar.
        longPressOnFirstWord(k)
        waitFor("tradução automática da seleção") { exists("PT: Pagina") }
        rule.onNodeWithTag("translationCard").assertIsDisplayed()
        rule.onNodeWithText("Tradução (inglês → português)").assertExists()

        // Copiar a tradução.
        rule.onNodeWithText("Copiar tradução").performClick()
        waitFor("tradução copiada") { vm.lastCopied == "PT: Pagina" }

        // Desligar a automática: selecionar não traduz mais sozinho...
        rule.onNodeWithTag("autoTranslate").performClick()
        waitFor("automática desligada") { !vm.autoTranslate && vm.translation == null }
        rule.onNodeWithTag("page-0").performTouchInput { click(Offset(300f * k, 600f * k)) }
        waitFor("seleção fechada") { vm.selection == null }
        longPressOnFirstWord(k)
        waitFor("seleção de novo") { vm.selection != null }
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        assertTrue("não deveria traduzir sozinho", vm.translation == null)

        // ...mas o botão Traduzir da barra traduz.
        inSelectionBar("Traduzir").performClick()
        waitFor("tradução pelo botão") { exists("PT: Pagina") }

        // Fechar a seleção fecha a tradução dela.
        rule.onNodeWithTag("page-0").performTouchInput { click(Offset(300f * k, 600f * k)) }
        waitFor("tradução fecha com a seleção") { vm.translation == null }
        rule.runOnUiThread { vm.setAutoTranslate(true) }
    }

    @Test
    fun translatePageFromMenu() {
        openSample(1)
        rule.onNodeWithContentDescription("Mais").performClick()
        rule.onNodeWithText("Traduzir página").performClick()
        waitFor("tradução da página") { exists("PT: Pagina 1 conteudo original", substring = true) }
        rule.onNodeWithContentDescription("Fechar tradução").performClick()
        waitFor("quadro fechado") { vm.translation == null }
    }

    @Test
    fun annotationTools() {
        openSample(1)

        selectTool("Texto")
        rule.onNodeWithTag("page-0").performTouchInput { click(Offset(width * 0.2f, height * 0.5f)) }
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
        rule.onNodeWithTag("page-0").performTouchInput {
            val k = width / 595f
            swipe(Offset(40f * k, 112f * k), Offset(330f * k, 160f * k), durationMillis = 300)
        }
        waitFor("diálogo Editar texto") { rule.onAllNodesWithText("Editar texto").fetchSemanticsNodes().size >= 2 }
        rule.onNode(hasSetTextAction()).assertTextContains("Pagina 1", substring = true)
        rule.onNode(hasSetTextAction()).performTextReplacement("Novo texto")
        rule.onNodeWithText("OK").performClick()
        waitAnnotations(5)
        assertTrue(vm.annotations[3] is Annotation.Whiteout)
        assertEquals("Novo texto", (vm.annotations[4] as Annotation.Text).text)

        // Assinatura: desenhar no quadro e posicionar na página.
        selectTool("Assinar")
        rule.onNodeWithTag("page-0").performTouchInput { click(Offset(width * 0.5f, height * 0.4f)) }
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
        waitFor("página 1 girada", 15_000) { vm.busy == null && vm.page?.let { it.widthPt > it.heightPt } == true }
        waitFor("botões da grade de páginas") {
            rule.onAllNodesWithContentDescription("Excluir").fetchSemanticsNodes().size == 3
        }

        rule.onAllNodesWithContentDescription("Excluir")[0].performClick()
        waitFor("confirmação de exclusão") { exists("Excluir página 1?") }
        rule.onNodeWithText("Excluir").performClick()
        waitFor("2 páginas", 15_000) { vm.pageCount == 2 && vm.busy == null }
        waitFor("título da grade com 2 páginas") { exists("Páginas (2)") }
        waitFor("botões após excluir") {
            rule.onAllNodesWithContentDescription("Mover para frente").fetchSemanticsNodes().isNotEmpty()
        }

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
            val url = server.url("/").toString() // resolve o host aqui, fora da thread de interface
            rule.runOnUiThread {
                vm.apiKey = "sk-test"
                vm.claudeBaseUrl = url
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

    private fun pressBack() = rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun recentDocumentsHistory() {
        rule.onNodeWithText("Os PDFs que você abrir aparecem aqui.").assertIsDisplayed()

        // Abre e vai até a página 3.
        openSample(3, "relatorio.pdf")
        rule.runOnUiThread { vm.goTo(2) }
        waitFor("página 3") { vm.current == 2 && exists("3 / 3") }

        // Voltar fecha o documento e mostra a lista de recentes, com miniatura.
        pressBack()
        waitFor("tela inicial com o histórico") { !vm.hasDocument && exists("relatorio.pdf") }
        rule.onNodeWithText("3 páginas", substring = true).assertIsDisplayed()
        val id = vm.recents.single().id
        waitFor("miniatura e cópia guardadas") {
            RecentStore(ctx).thumbFile(id).length() > 0 && RecentStore(ctx).copyFile(id).length() > 0
        }

        // Tocar no item reabre na página em que parou.
        rule.onNodeWithText("relatorio.pdf").performClick()
        waitFor("reaberto na página 3", 20_000) { vm.hasDocument && vm.busy == null && vm.current == 2 && exists("3 / 3") }
        waitFor("página 3 desenhada", 15_000) { vm.pageBitmaps.containsKey(2) }

        // O arquivo original sumiu (ex.: veio do WhatsApp): reabre pela cópia guardada.
        pressBack()
        waitFor("de volta à lista") { !vm.hasDocument && exists("relatorio.pdf") }
        File(ctx.cacheDir, "relatorio.pdf").delete()
        rule.onNodeWithText("relatorio.pdf").performClick()
        waitFor("reaberto pela cópia", 20_000) { vm.hasDocument && vm.pageCount == 3 && vm.busy == null }
        assertEquals(1, vm.recents.size)
        pressBack()
        waitFor("lista de novo") { !vm.hasDocument && exists("relatorio.pdf") }

        // A aba Favoritos começa vazia.
        rule.onNodeWithText("Favoritos").performClick()
        waitFor("aba Favoritos vazia") { exists("Nenhum favorito ainda", substring = true) && !exists("relatorio.pdf") }
        rule.onNodeWithText("Recentes").performClick()
        waitFor("aba Recentes") { exists("relatorio.pdf") }

        // Favoritar pelo menu ⋮ e ver na aba Favoritos.
        rule.onNodeWithContentDescription("Opções de relatorio.pdf").performClick()
        rule.onNodeWithText("Favoritar").performClick()
        waitFor("favoritado") { vm.recents.single().favorite }
        rule.onNodeWithText("Favoritos").performClick()
        waitFor("aba Favoritos com o documento") {
            exists("relatorio.pdf") && !exists("Nenhum favorito ainda", substring = true)
        }
        waitFor("favorito gravado no disco") { RecentStore(ctx).load().singleOrNull()?.favorite == true }

        // "Limpar recentes" mantém os favoritos.
        rule.runOnUiThread { vm.clearRecents() }
        rule.waitForIdle()
        assertEquals(1, vm.recents.size)

        // Remover da lista.
        rule.onNodeWithText("Recentes").performClick()
        rule.onNodeWithContentDescription("Opções de relatorio.pdf").performClick()
        rule.onNodeWithText("Remover da lista").performClick()
        waitFor("removido da lista") { vm.recents.isEmpty() && !exists("relatorio.pdf") }
        waitFor("arquivos do item apagados") { !RecentStore(ctx).thumbFile(id).exists() && !RecentStore(ctx).copyFile(id).exists() }
        rule.onNodeWithText("Os PDFs que você abrir aparecem aqui.").assertIsDisplayed()
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
