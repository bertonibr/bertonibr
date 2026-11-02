package com.bertonibr.pdfstudio

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bertonibr.pdfstudio.model.Annotation
import com.bertonibr.pdfstudio.model.Glyph
import com.bertonibr.pdfstudio.model.TextLayout
import com.bertonibr.pdfstudio.model.PdfEditor
import com.bertonibr.pdfstudio.service.ClaudeService
import com.bertonibr.pdfstudio.service.MlKitTranslator
import com.bertonibr.pdfstudio.service.Speech
import com.bertonibr.pdfstudio.service.TextTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

enum class Tool(val label: String) {
    NAVIGATE("Navegar"),
    TEXT("Texto"),
    HIGHLIGHT("Destacar"),
    INK("Desenhar"),
    WHITEOUT("Apagar"),
    EDIT_TEXT("Editar texto"),
    SIGN("Assinar"),
}

class PageImage(
    val bitmap: ImageBitmap,
    val index: Int,
    val widthPt: Float,
    val heightPt: Float,
    val renderWidth: Int = 0,
    val version: Int = 0,
)

/** Estado do quadro de tradução. */
sealed interface TranslationState {
    data class Working(val message: String) : TranslationState
    data class Done(val text: String, val sourceLanguage: String, val alreadyPortuguese: Boolean) : TranslationState
    data class Failed(val message: String) : TranslationState
}

class PdfStudioViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()

    /** Todo acesso ao PDF acontece nesta única thread. */
    private val pdf = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var editor: PdfEditor? = null
    private val prefs = app.getSharedPreferences("pdfstudio", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ estado da UI
    var hasDocument by mutableStateOf(false); private set
    var docName by mutableStateOf("PDF Studio"); private set
    var docUri by mutableStateOf<Uri?>(null); private set
    var dirty by mutableStateOf(false); private set
    var pageCount by mutableIntStateOf(0); private set
    /** Página "atual": a que está no meio da tela (atualizada enquanto se rola). */
    var current by mutableIntStateOf(0); private set

    /** Tamanho (pt) de cada página, imagens já renderizadas e anotações por página. */
    var pageSizes by mutableStateOf<List<FloatArray>>(emptyList()); private set
    val pageBitmaps = mutableStateMapOf<Int, PageImage>()
    val annotationsByPage = mutableStateMapOf<Int, List<Annotation>>()

    /** Pedido para a tela rolar até uma página (setas, busca, painel de páginas). */
    var scrollRequest by mutableStateOf<Int?>(null)

    /** Imagem e anotações da página atual. */
    val page: PageImage? get() = pageBitmaps[current]
    val annotations: List<Annotation> get() = annotationsByPage[current] ?: emptyList()
    private var lastEditedPage = 0
    var busy by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)
    var docVersion by mutableIntStateOf(0); private set
    val thumbnails = mutableStateMapOf<Int, ImageBitmap>()

    var tool by mutableStateOf(Tool.NAVIGATE)
    var color by mutableIntStateOf(Color.parseColor("#D32F2F"))
    var textSize by mutableStateOf(14f)
    var signaturePng by mutableStateOf<ByteArray?>(null)

    var speaking by mutableStateOf(false); private set
    var aiText by mutableStateOf(""); private set
    var aiRunning by mutableStateOf(false); private set
    var extractedText by mutableStateOf<String?>(null)

    /** Texto da página atual (com posições) e seleção feita segurando o dedo. */
    var pageGlyphs by mutableStateOf<List<Glyph>>(emptyList()); private set
    var selection by mutableStateOf<IntRange?>(null); private set
    var selectionPage by mutableIntStateOf(0); private set
    private var glyphCache: Triple<Int, Int, List<Glyph>>? = null // (versão, página, glifos) — só na thread do PDF

    /** PDF que pediu senha (a tela mostra o diálogo de senha). */
    var passwordRequest by mutableStateOf<Uri?>(null)

    // ------------------------------------------------------------ tradução (estado)
    private var translatorImpl: TextTranslator? = null

    /** Tradutor usado (ML Kit; os testes trocam por um simulado). */
    var translator: TextTranslator
        get() = translatorImpl ?: MlKitTranslator().also { translatorImpl = it }
        set(value) {
            translatorImpl = value
        }
    var translation by mutableStateOf<TranslationState?>(null); private set
    private var autoTranslateState by mutableStateOf(prefs.getBoolean("autoTranslate", true))

    /** Traduzir sozinho ao selecionar (muda com [setAutoTranslate]). */
    val autoTranslate: Boolean get() = autoTranslateState

    /** A tradução mostrada veio da seleção (some quando a seleção é fechada). */
    var translationFromSelection = false
        private set
    private var translateJob: Job? = null

    /** Só para testes automáticos: aponta o Claude para um servidor simulado. */
    var claudeBaseUrl: String? = null

    private val speech = Speech(app) { s -> viewModelScope.launch { speaking = s } }

    init {
        PDFBoxResourceLoader.init(app)
    }

    // ------------------------------------------------------------ util

    /** Executa [work] na thread do PDF mostrando [label]; erros viram mensagem. */
    private fun run(label: String?, work: suspend () -> Unit) {
        viewModelScope.launch {
            if (label != null) busy = label
            try {
                withContext(pdf) { work() }
            } catch (e: Throwable) {
                message = "Erro: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (label != null && busy == label) busy = null
            }
        }
    }

    private suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    private fun readBytes(uri: Uri): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Não foi possível ler o arquivo.")

    private fun displayName(uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "documento.pdf"
    }

    val baseName: String get() = docName.removeSuffix(".pdf").removeSuffix(".PDF")

    private fun loadBitmaps(uris: List<Uri>): List<Bitmap> = uris.mapNotNull { uri ->
        val bytes = readBytes(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // Reduz fotos muito grandes para no máximo ~2400 px.
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ------------------------------------------------------------ documento

    fun open(uri: Uri, password: String? = null) = run("Abrindo...") {
        val bytes = readBytes(uri)
        val name = displayName(uri)
        val e = try {
            PdfEditor.open(ctx, bytes, password)
        } catch (ex: InvalidPasswordException) {
            ui {
                passwordRequest = uri
                message = if (password == null) "Este PDF é protegido por senha." else "Senha incorreta."
            }
            return@run
        }
        setEditor(e, name, uri)
    }

    fun createFromImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        run("Criando PDF...") {
            val e = PdfEditor.fromImages(ctx, loadBitmaps(uris))
            setEditor(e, "Novo documento.pdf", null)
        }
    }

    private suspend fun setEditor(e: PdfEditor, name: String, uri: Uri?) {
        editor?.close()
        editor = e
        ui {
            hasDocument = true
            docName = name
            docUri = uri
            selection = null
            current = 0
            lastEditedPage = 0
            aiText = ""
            tool = Tool.NAVIGATE
            scrollRequest = 0
        }
        structureChanged()
    }

    fun closeDocument() = run(null) {
        editor?.close()
        editor = null
        ui {
            hasDocument = false
            selection = null
            docName = "PDF Studio"
            docUri = null
            pageBitmaps.clear()
            annotationsByPage.clear()
            pageSizes = emptyList()
            pageCount = 0
            dirty = false
            thumbnails.clear()
        }
    }

    /** Após mudar páginas: atualiza contagem, miniaturas e a página atual. */
    private suspend fun structureChanged() {
        val e = editor ?: return
        val n = e.pageCount
        val sizes = e.pageSizes()
        ui {
            selection = null
            pageCount = n
            pageSizes = sizes
            current = current.coerceIn(0, n - 1)
            dirty = e.dirty
            docVersion++
            thumbnails.clear()
            pageBitmaps.clear()
            annotationsByPage.clear()
        }
    }

    fun saveTo(uri: Uri, rememberAsCurrent: Boolean = true) = run("Salvando...") {
        val e = editor ?: return@run
        val bytes = e.toBytes(true)
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IllegalStateException("Não foi possível gravar o arquivo.")
        e.markSaved()
        val name = displayName(uri)
        ui {
            if (rememberAsCurrent) {
                docUri = uri
                docName = name
            }
            dirty = false
            message = "Salvo: $name"
        }
    }

    /** Salva no próprio arquivo. Retorna false se não houver permissão (usar "Salvar como"). */
    fun canSaveInPlace(): Boolean {
        val uri = docUri ?: return false
        return ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    }

    fun shareIntent(onReady: (Intent) -> Unit) = run("Preparando...") {
        val e = editor ?: return@run
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, if (docName.endsWith(".pdf", true)) docName else "$docName.pdf")
        file.writeBytes(e.toBytes(true))
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ui { onReady(Intent.createChooser(intent, "Compartilhar PDF")) }
    }

    // ------------------------------------------------------------ navegação e renderização

    /** Rola até a página (setas, busca, painel de páginas). */
    fun goTo(index: Int) {
        if (!hasDocument || index !in 0 until pageCount) return
        current = index
        scrollRequest = index
    }

    /** A tela avisa qual página está visível enquanto o usuário rola. */
    fun onVisiblePage(index: Int) {
        if (hasDocument && index in 0 until pageCount) current = index
    }

    /**
     * Renderiza uma página com a largura pedida (chamada pela própria página
     * quando aparece na tela; se ela sair da tela antes, o pedido é cancelado).
     */
    suspend fun renderPage(index: Int, widthPx: Int) {
        val have = pageBitmaps[index]
        if (have != null && have.renderWidth == widthPx && have.version == docVersion) return
        val version = docVersion
        try {
            val r = withContext(pdf) {
                val e = editor ?: return@withContext null
                if (version != docVersion || index >= e.pageCount) null else e.render(index, widthPx)
            } ?: return
            if (version == docVersion) {
                pageBitmaps[index] = PageImage(r.bitmap.asImageBitmap(), index, r.widthPt, r.heightPt, widthPx, version)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            message = "Erro ao mostrar a página ${index + 1}: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Libera da memória as imagens das páginas longe da tela. */
    fun trimPages(keep: IntRange) {
        pageBitmaps.keys.filter { it !in keep }.forEach { pageBitmaps.remove(it) }
    }

    /** Carrega as anotações (ainda não gravadas) de uma página. */
    suspend fun loadAnnotations(index: Int) {
        val version = docVersion
        val list = withContext(pdf) { editor?.takeIf { index < it.pageCount }?.annotations(index) } ?: return
        if (version == docVersion) annotationsByPage[index] = list
    }

    fun requestThumbnail(index: Int) {
        if (thumbnails.containsKey(index)) return
        val version = docVersion
        viewModelScope.launch {
            val bmp = withContext(pdf) {
                val e = editor ?: return@withContext null
                if (version != docVersion || index >= e.pageCount) null else e.render(index, 220).bitmap
            }
            if (bmp != null && version == docVersion) thumbnails[index] = bmp.asImageBitmap()
        }
    }

    fun search(term: String) = run("Procurando...") {
        val e = editor ?: return@run
        val hits = e.search(term)
        ui {
            if (hits.isEmpty()) {
                message = "\"$term\" não encontrado."
            } else {
                val next = hits.firstOrNull { it > current } ?: hits.first()
                goTo(next)
                message = "Encontrado nas páginas: " + hits.joinToString(", ") { (it + 1).toString() }
            }
        }
    }

    // ------------------------------------------------------------ páginas

    fun rotate(index: Int = current, delta: Int) = run("Girando...") {
        editor?.rotate(index, delta)
        structureChanged()
    }

    fun deletePage(index: Int = current) = run("Excluindo...") {
        editor?.deletePage(index)
        structureChanged()
    }

    fun movePage(from: Int, to: Int) {
        if (to !in 0 until pageCount) return
        run(null) {
            editor?.movePage(from, to)
            structureChanged()
            ui {
                current = to
                scrollRequest = to
            }
        }
    }

    fun appendPdfs(uris: List<Uri>) {
        if (uris.isEmpty()) return
        run("Juntando PDFs...") {
            for (u in uris) editor?.appendPdf(readBytes(u))
            structureChanged()
            ui { message = "${uris.size} arquivo(s) inserido(s) no final." }
        }
    }

    fun appendImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        run("Inserindo imagens...") {
            editor?.appendImages(loadBitmaps(uris))
            structureChanged()
        }
    }

    fun extractPages(pages: List<Int>, target: Uri) = run("Extraindo páginas...") {
        val bytes = editor?.pagesToBytes(pages) ?: return@run
        ctx.contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) }
        ui { message = "${pages.size} página(s) salvas." }
    }

    fun splitInto(tree: Uri) = run("Dividindo...") {
        val e = editor ?: return@run
        val dir = DocumentFile.fromTreeUri(ctx, tree) ?: throw IllegalStateException("Pasta inválida.")
        for (i in 0 until e.pageCount) {
            val f = dir.createFile("application/pdf", "%s_p%03d.pdf".format(baseName, i + 1))
                ?: throw IllegalStateException("Não foi possível criar arquivo na pasta.")
            ctx.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(e.pagesToBytes(listOf(i))) }
        }
        ui { message = "${e.pageCount} arquivos criados." }
    }

    fun exportText(target: Uri) = run("Exportando texto...") {
        val text = editor?.allText() ?: return@run
        ctx.contentResolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray()) }
        ui { message = "Texto exportado." }
    }

    fun exportPagePng(target: Uri) = run("Gerando imagem...") {
        val bmp = editor?.renderFlattened(current, 150) ?: return@run
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        ctx.contentResolver.openOutputStream(target, "wt")?.use { it.write(bos.toByteArray()) }
        ui { message = "Imagem salva." }
    }

    fun showText(wholeDoc: Boolean) = run("Extraindo texto...") {
        val e = editor ?: return@run
        val t = if (wholeDoc) e.allText() else e.extractText(current, current)
        ui { extractedText = t.ifBlank { "(Sem texto selecionável — o PDF pode ser uma imagem digitalizada.)" } }
    }

    // ------------------------------------------------------------ anotações

    private fun refreshAnnotations(index: Int) {
        val version = docVersion
        run(null) {
            val list = editor?.annotations(index) ?: emptyList()
            val d = editor?.dirty ?: false
            ui {
                if (version == docVersion) annotationsByPage[index] = list
                dirty = d
            }
        }
    }

    fun addAnnotation(a: Annotation) = addAnnotation(current, a)

    fun addAnnotation(index: Int, a: Annotation) {
        lastEditedPage = index
        run(null) { editor?.addAnnotation(index, a) }
        refreshAnnotations(index)
    }

    /** Desfaz a última anotação da página editada por último. */
    fun undo() {
        val idx = lastEditedPage.coerceIn(0, max(0, pageCount - 1))
        run(null) {
            if (editor?.undoAnnotation(idx) != true) ui { message = "Nada para desfazer." }
        }
        refreshAnnotations(idx)
    }

    fun clearPageAnnotations() {
        val idx = current
        run(null) { editor?.clearAnnotations(idx) }
        refreshAnnotations(idx)
    }

    /** Ferramenta "Editar texto": lê o texto da área e chama [onText] com ele. */
    fun textInArea(index: Int, x: Float, y: Float, w: Float, h: Float, onText: (String) -> Unit) {
        val idx = index
        run("Lendo texto da área...") {
            val t = editor?.textInArea(idx, x, y, w, h) ?: ""
            ui { onText(t) }
        }
    }

    // ------------------------------------------------------------ área de transferência

    /** Último texto copiado pelo app (útil para conferir nos testes). */
    var lastCopied: String? = null
        private set

    fun copyToClipboard(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PDF Studio", text))
        lastCopied = text
        // A partir do Android 13 o próprio sistema mostra o aviso de "copiado".
        if (Build.VERSION.SDK_INT < 33) message = "Texto copiado."
    }

    // ------------------------------------------------------------ seleção de texto

    private fun glyphsFor(index: Int, version: Int): List<Glyph> {
        glyphCache?.let { (v, p, g) -> if (v == version && p == index) return g }
        val g = editor?.glyphs(index) ?: emptyList()
        glyphCache = Triple(version, index, g)
        return g
    }

    /** Segurar o dedo: seleciona a palavra sob o ponto (coordenadas em pontos). */
    fun selectWordAt(index: Int, x: Float, y: Float) {
        val version = docVersion
        run(null) {
            val glyphs = glyphsFor(index, version)
            val i = TextLayout.nearest(glyphs, x, y, maxDistance = 24f)
            ui {
                if (version != docVersion) return@ui
                if (i == null) {
                    message = if (glyphs.isEmpty()) "Esta página não tem texto selecionável (pode ser uma imagem digitalizada)."
                    else "Nenhum texto neste ponto."
                } else {
                    pageGlyphs = glyphs
                    selectionPage = index
                    selection = TextLayout.wordAt(glyphs, i)
                }
            }
        }
    }

    /** Arrastar uma alça: move o início ou o fim da seleção para o glifo mais próximo. */
    fun moveSelectionEdge(start: Boolean, x: Float, y: Float) {
        val sel = selection ?: return
        val i = TextLayout.nearest(pageGlyphs, x, y) ?: return
        selection = if (start) {
            if (i <= sel.last) i..sel.last else sel.last..i
        } else {
            if (i >= sel.first) sel.first..i else i..sel.first
        }
    }

    fun selectAll() {
        if (selection != null) TextLayout.all(pageGlyphs)?.let { selection = it }
    }

    fun clearSelection() {
        selection = null
    }

    val selectedText: String
        get() = selection?.let { TextLayout.text(pageGlyphs, it) } ?: ""

    /** Marca o trecho selecionado com destaque amarelo (um retângulo por linha). */
    fun highlightSelection() {
        val sel = selection ?: return
        TextLayout.lineRects(pageGlyphs, sel).forEach { r ->
            addAnnotation(selectionPage, Annotation.Highlight(r[0], r[1], r[2] - r[0], r[3] - r[1], Color.parseColor("#FFEB3B")))
        }
        selection = null
    }

    // ------------------------------------------------------------ tradução

    fun setAutoTranslate(on: Boolean) {
        autoTranslateState = on
        prefs.edit().putBoolean("autoTranslate", on).apply()
        if (!on && translationFromSelection) clearTranslation()
    }

    /** Traduz o trecho selecionado para o português. */
    fun translateSelection() {
        val text = selectedText
        if (text.isNotBlank()) translate(text, fromSelection = true)
    }

    /** Traduz o texto da página atual. */
    fun translatePage() {
        val index = current
        run("Lendo o texto da página...") {
            val text = editor?.extractText(index, index).orEmpty()
            ui {
                if (text.isBlank()) message = "Esta página não tem texto para traduzir (pode ser uma imagem digitalizada)."
                else translate(text, fromSelection = false)
            }
        }
    }

    private fun translate(text: String, fromSelection: Boolean) {
        translateJob?.cancel()
        translationFromSelection = fromSelection
        translateJob = viewModelScope.launch {
            translation = TranslationState.Working("Traduzindo...")
            try {
                val r = translator.translate(text.trim()) { status -> translation = TranslationState.Working(status) }
                translation = TranslationState.Done(r.text, r.sourceLanguage, r.alreadyPortuguese)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                translation = TranslationState.Failed(e.message ?: "Não foi possível traduzir.")
            }
        }
    }

    fun clearTranslation() {
        translateJob?.cancel()
        translateJob = null
        translation = null
        translationFromSelection = false
    }

    // ------------------------------------------------------------ voz

    fun readPageAloud(wholeDoc: Boolean = false) = run("Preparando leitura...") {
        val e = editor ?: return@run
        val t = if (wholeDoc) e.allText() else e.extractText(current, current)
        ui { speak(t) }
    }

    fun speak(text: String) {
        if (text.isBlank()) {
            message = "Não há texto para ler (o PDF pode ser uma imagem digitalizada)."
            return
        }
        speech.speak(text)
    }

    fun stopSpeaking() = speech.stop()

    // ------------------------------------------------------------ Claude

    var apiKey: String
        get() = prefs.getString("anthropicApiKey", "") ?: ""
        set(v) = prefs.edit().putString("anthropicApiKey", v.trim()).apply()

    fun askClaude(currentPageOnly: Boolean, instruction: String) {
        val key = apiKey
        if (key.isBlank()) {
            message = "Configure sua chave de API do Claude primeiro."
            return
        }
        if (aiRunning) return
        val idx = current
        aiRunning = true
        aiText = (if (currentPageOnly) "▶ Página ${idx + 1}: " else "▶ ") +
            instruction.take(120) + (if (instruction.length > 120) "..." else "") + "\n\n"
        viewModelScope.launch {
            try {
                val (bytes, text) = withContext(pdf) {
                    val e = editor ?: throw IllegalStateException("Nenhum documento aberto.")
                    if (currentPageOnly) e.pagesToBytes(listOf(idx)) to e.extractText(idx, idx)
                    else e.toBytes(true) to e.allText()
                }
                withContext(Dispatchers.IO) {
                    ClaudeService.ask(key, bytes, text, instruction, claudeBaseUrl) { delta ->
                        viewModelScope.launch { aiText += delta }
                    }
                }
            } catch (e: Throwable) {
                aiText += "\n\n[Erro] ${e.message ?: e.javaClass.simpleName}"
            } finally {
                aiRunning = false
            }
        }
    }

    override fun onCleared() {
        speech.shutdown()
        translatorImpl?.close()
        val e = editor
        editor = null
        // Fecha na própria thread do PDF, depois de qualquer tarefa em andamento.
        pdf.executor.execute { e?.close() }
        pdf.close()
    }
}
