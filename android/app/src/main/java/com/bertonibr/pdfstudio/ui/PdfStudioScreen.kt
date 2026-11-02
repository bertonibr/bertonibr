package com.bertonibr.pdfstudio.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Translate
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.scale
import com.bertonibr.pdfstudio.TranslationState
import com.bertonibr.pdfstudio.service.languageName
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberUpdatedState
import com.bertonibr.pdfstudio.model.TextLayout
import kotlin.math.roundToInt
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bertonibr.pdfstudio.PdfStudioViewModel
import com.bertonibr.pdfstudio.Tool
import com.bertonibr.pdfstudio.model.Annotation
import com.bertonibr.pdfstudio.service.ClaudeService
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import android.graphics.Color as AColor

private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 4f

private val PALETTE = listOf("#D32F2F", "#000000", "#1565C0", "#2E7D32", "#FFEB3B", "#FF9800")
    .map { AColor.parseColor(it) }

private val TOOL_ICONS: Map<Tool, ImageVector> = mapOf(
    Tool.NAVIGATE to Icons.Filled.PanTool,
    Tool.TEXT to Icons.Filled.TextFields,
    Tool.HIGHLIGHT to Icons.Filled.BorderColor,
    Tool.INK to Icons.Filled.Draw,
    Tool.WHITEOUT to Icons.Filled.CropSquare,
    Tool.EDIT_TEXT to Icons.Filled.EditNote,
    Tool.SIGN to Icons.Filled.Gesture,
)

/** Diálogo de texto pendente (título, rótulo, valor inicial, ação). */
private class TextRequest(
    val title: String,
    val label: String,
    val initial: String = "",
    val singleLine: Boolean = false,
    val onOk: (String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfStudioScreen(vm: PdfStudioViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var menuOpen by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    var showAssistant by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }
    var showSignature by remember { mutableStateOf(false) }
    var pendingSignature by remember { mutableStateOf<Triple<Int, Float, Float>?>(null) }
    var textRequest by remember { mutableStateOf<TextRequest?>(null) }
    var confirmDiscard by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmDelete by remember { mutableStateOf<Int?>(null) }
    var pendingExtract by remember { mutableStateOf<List<Int>?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    fun guarded(action: () -> Unit) {
        if (vm.dirty) confirmDiscard = action else action()
    }

    // ---------------------------------------------------------------- seletores de arquivo
    val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.takePermission(uri)
            vm.open(uri)
            zoom = 1f
        }
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            context.takePermission(uri)
            vm.saveTo(uri)
        }
    }
    val newFromImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        vm.createFromImages(uris)
    }
    val appendImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        vm.appendImages(uris)
    }
    val appendPdfs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.appendPdfs(uris)
    }
    val extractTarget = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val pages = pendingExtract
        if (uri != null && pages != null) vm.extractPages(pages, uri)
        pendingExtract = null
    }
    val splitDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.splitInto(uri)
    }
    val exportText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.exportText(uri)
    }
    val exportPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) vm.exportPagePng(uri)
    }

    fun save() {
        if (vm.canSaveInPlace()) vm.docUri?.let { vm.saveTo(it) } else saveAs.launch(vm.baseName + ".pdf")
    }

    // ---------------------------------------------------------------- layout
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            (if (vm.dirty) "• " else "") + vm.docName,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    actions = {
                        IconButton(onClick = { guarded { openPdf.launch(arrayOf("application/pdf")) } }) {
                            Icon(Icons.Filled.FolderOpen, "Abrir")
                        }
                        if (vm.hasDocument) {
                            IconButton(onClick = { save() }) { Icon(Icons.Filled.Save, "Salvar") }
                            IconButton(onClick = { showAssistant = true }) { Icon(Icons.Filled.AutoAwesome, "Claude") }
                            IconButton(onClick = { showPages = true }) { Icon(Icons.Filled.GridView, "Páginas") }
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Mais") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                val close = { menuOpen = false }
                                MenuEntry("Criar PDF a partir de imagens", true, close) { guarded { newFromImages.launch("image/*") } }
                                MenuEntry("Salvar como...", vm.hasDocument, close) { saveAs.launch(vm.baseName + ".pdf") }
                                MenuEntry("Compartilhar", vm.hasDocument, close) { vm.shareIntent { context.startActivity(it) } }
                                HorizontalDivider()
                                MenuEntry("Localizar...", vm.hasDocument, close) {
                                    textRequest = TextRequest("Localizar", "Procurar por", singleLine = true) {
                                        if (it.isNotBlank()) vm.search(it.trim())
                                    }
                                }
                                MenuEntry("Traduzir página", vm.hasDocument, close) { vm.translatePage() }
                                MenuEntry("Texto da página", vm.hasDocument, close) { vm.showText(false) }
                                MenuEntry("Texto do documento", vm.hasDocument, close) { vm.showText(true) }
                                MenuEntry("Ler documento em voz alta", vm.hasDocument, close) { vm.readPageAloud(true) }
                                HorizontalDivider()
                                MenuEntry("Juntar com outros PDFs...", vm.hasDocument, close) { appendPdfs.launch(arrayOf("application/pdf")) }
                                MenuEntry("Inserir imagens no final...", vm.hasDocument, close) { appendImages.launch("image/*") }
                                MenuEntry("Extrair páginas...", vm.hasDocument, close) {
                                    textRequest = TextRequest(
                                        "Extrair páginas", "Ex.: 1-3, 5 (total ${vm.pageCount})",
                                        (vm.current + 1).toString(), singleLine = true,
                                    ) {
                                        try {
                                            pendingExtract = parseRange(it, vm.pageCount)
                                            extractTarget.launch(vm.baseName + "_extraido.pdf")
                                        } catch (e: IllegalArgumentException) {
                                            vm.message = e.message
                                        }
                                    }
                                }
                                MenuEntry("Dividir em um PDF por página...", vm.hasDocument, close) { splitDir.launch(null) }
                                MenuEntry("Exportar texto (.txt)...", vm.hasDocument, close) { exportText.launch(vm.baseName + ".txt") }
                                MenuEntry("Exportar página como PNG...", vm.hasDocument, close) {
                                    exportPng.launch("%s_p%03d.png".format(vm.baseName, vm.current + 1))
                                }
                                HorizontalDivider()
                                MenuEntry("Limpar anotações da página", vm.hasDocument, close) { vm.clearPageAnnotations() }
                                MenuEntry("Nova assinatura...", true, close) { showSignature = true }
                                MenuEntry("Chave de API do Claude...", true, close) { showApiKey = true }
                                MenuEntry("Fechar documento", vm.hasDocument, close) { guarded { vm.closeDocument() } }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Versão ${appVersion()}") }, enabled = false, onClick = {})
                            }
                        }
                    },
                )
                if (vm.busy != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (vm.hasDocument) ToolRow(vm)
            }
        },
        bottomBar = {
            if (vm.hasDocument) {
                BottomAppBar(contentPadding = PaddingValues(horizontal = 4.dp)) {
                    val small = Modifier.size(40.dp)
                    IconButton(onClick = { vm.goTo(vm.current - 1) }, enabled = vm.current > 0, modifier = small) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Anterior")
                    }
                    Text(
                        "${vm.current + 1} / ${vm.pageCount}",
                        maxLines = 1,
                        modifier = Modifier.clickable {
                            textRequest = TextRequest("Ir para página", "Página (1-${vm.pageCount})", singleLine = true) {
                                it.trim().toIntOrNull()?.let { p -> vm.goTo(p - 1) }
                            }
                        }.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                    IconButton(onClick = { vm.goTo(vm.current + 1) }, enabled = vm.current < vm.pageCount - 1, modifier = small) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Próxima")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(MIN_ZOOM) }, modifier = small) {
                        Icon(Icons.Filled.ZoomOut, "Reduzir")
                    }
                    Text("${(zoom * 100).toInt()}%", maxLines = 1, modifier = Modifier.clickable { zoom = 1f })
                    IconButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(MAX_ZOOM) }, modifier = small) {
                        Icon(Icons.Filled.ZoomIn, "Ampliar")
                    }
                    if (vm.speaking) {
                        IconButton(onClick = { vm.stopSpeaking() }, modifier = small) { Icon(Icons.Filled.Stop, "Parar leitura") }
                    } else {
                        IconButton(onClick = { vm.readPageAloud() }, modifier = small) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, "Ler página")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(Color(0xFF5F6368))) {
            if (!vm.hasDocument) {
                Welcome(
                    onOpen = { openPdf.launch(arrayOf("application/pdf")) },
                    onImages = { newFromImages.launch("image/*") },
                )
            } else {
                PdfViewer(
                    vm = vm,
                    zoom = zoom,
                    onZoom = { zoom = it },
                    onTextAt = { page, x, y ->
                        textRequest = TextRequest("Inserir texto", "Texto") {
                            if (it.isNotBlank()) vm.addAnnotation(page, Annotation.Text(x, y, it, vm.textSize, vm.color))
                        }
                    },
                    onSignAt = { page, x, y ->
                        val png = vm.signaturePng
                        if (png == null) {
                            pendingSignature = Triple(page, x, y)
                            showSignature = true
                        } else {
                            placeSignature(vm, page, png, x, y)
                        }
                    },
                    onEditArea = { page, x, y, w, h ->
                        vm.textInArea(page, x, y, w, h) { original ->
                            textRequest = TextRequest(
                                "Editar texto",
                                if (original.isEmpty()) "Nenhum texto na área. Novo texto:" else "Novo texto",
                                original,
                            ) { newText ->
                                vm.addAnnotation(page, Annotation.Whiteout(x, y, w, h, AColor.WHITE))
                                if (newText.isNotBlank()) {
                                    val c = if (vm.color == PALETTE[0]) AColor.BLACK else vm.color
                                    vm.addAnnotation(page, Annotation.Text(x, y, newText, vm.textSize, c))
                                }
                            }
                        }
                    },
                )
            }
            vm.busy?.let {
                Text(
                    it,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                        .background(Color(0xAA000000), MaterialTheme.shapes.small).padding(8.dp, 4.dp),
                )
            }
            if (vm.translation != null) {
                TranslationCard(
                    vm = vm,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
                    onCopy = { vm.copyToClipboard(it) },
                    onSpeak = { vm.speak(it) },
                )
            }
            if (vm.selection != null && vm.tool == Tool.NAVIGATE) {
                SelectionBar(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 8.dp, end = 8.dp),
                    onCopy = {
                        vm.copyToClipboard(vm.selectedText)
                        vm.clearSelection()
                    },
                    onTranslate = { vm.translateSelection() },
                    onHighlight = { vm.highlightSelection() },
                    onSpeak = { vm.speak(vm.selectedText) },
                    onExplain = {
                        val t = vm.selectedText
                        vm.clearSelection()
                        showAssistant = true
                        vm.askClaude(false, "Explique de forma simples este trecho do documento:\n\n\"$t\"")
                    },
                    onAll = { vm.selectAll() },
                    onClose = { vm.clearSelection() },
                )
            }
        }
    }

    // Seleção de texto só existe na ferramenta Navegar.
    LaunchedEffect(vm.tool) { if (vm.tool != Tool.NAVIGATE) vm.clearSelection() }

    // Tradução automática: ao selecionar (e depois de parar de mexer nas alças), traduz.
    LaunchedEffect(vm.selection, vm.selectionPage, vm.autoTranslate) {
        if (vm.selection == null) {
            if (vm.translationFromSelection) vm.clearTranslation()
            return@LaunchedEffect
        }
        if (!vm.autoTranslate) return@LaunchedEffect
        delay(700)
        vm.translateSelection()
    }

    // ---------------------------------------------------------------- diálogos e painéis
    textRequest?.let { req ->
        TextInputDialog(
            title = req.title, label = req.label, initial = req.initial, singleLine = req.singleLine,
            onDismiss = { textRequest = null },
            onConfirm = {
                textRequest = null
                req.onOk(it)
            },
        )
    }

    confirmDiscard?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text("Alterações não salvas") },
            text = { Text("Deseja descartar as alterações deste documento?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = null
                    action()
                }) { Text("Descartar") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text("Cancelar") } },
        )
    }

    confirmDelete?.let { idx ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Excluir página ${idx + 1}?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    vm.deletePage(idx)
                }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
        )
    }

    if (showApiKey) {
        ApiKeyDialog(vm.apiKey, onDismiss = { showApiKey = false }) {
            vm.apiKey = it
            showApiKey = false
        }
    }

    if (showSignature) {
        SignatureDialog(onDismiss = {
            showSignature = false
            pendingSignature = null
        }) { png ->
            vm.signaturePng = png
            showSignature = false
            pendingSignature?.let { (page, x, y) -> placeSignature(vm, page, png, x, y) }
            pendingSignature = null
            vm.tool = Tool.SIGN
        }
    }

    vm.passwordRequest?.let { uri ->
        TextInputDialog(
            title = "PDF protegido",
            label = "Senha",
            singleLine = true,
            password = true,
            confirm = "Abrir",
            onDismiss = { vm.passwordRequest = null },
            onConfirm = {
                vm.passwordRequest = null
                vm.open(uri, it)
            },
        )
    }

    BackHandler(enabled = vm.dirty && confirmDiscard == null) {
        confirmDiscard = { (context as? Activity)?.finish() }
    }

    vm.extractedText?.let { text ->
        TextViewerDialog(
            text = text,
            onCopy = { vm.copyToClipboard(text) },
            onSpeak = { vm.speak(text) },
            onDismiss = { vm.extractedText = null },
        )
    }

    if (showPages) {
        ModalBottomSheet(
            onDismissRequest = { showPages = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            PagesGrid(
                vm = vm,
                onOpen = {
                    vm.goTo(it)
                    showPages = false
                },
                onDelete = { confirmDelete = it },
            )
        }
    }

    if (showAssistant) {
        ModalBottomSheet(
            onDismissRequest = { showAssistant = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            AssistantPanel(vm, onConfigureKey = { showApiKey = true }, onCopy = { vm.copyToClipboard(vm.aiText) })
        }
    }
}

/** Versão instalada (ex.: 1.1.12) — para conferir se a atualização entrou. */
@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }
}

@Composable
private fun MenuEntry(text: String, enabled: Boolean, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, enabled = enabled, onClick = {
        close()
        action()
    })
}

private fun placeSignature(vm: PdfStudioViewModel, page: Int, png: ByteArray, x: Float, y: Float) {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(png, 0, png.size, opts)
    val w = 150f
    val h = w * opts.outHeight / opts.outWidth.coerceAtLeast(1)
    vm.addAnnotation(page, Annotation.Image(png, x - w / 2, y - h / 2, w, h))
}

private fun Context.takePermission(uri: Uri) {
    try {
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    } catch (_: SecurityException) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }
}

// ==================================================================== componentes

@Composable
private fun Welcome(onOpen: () -> Unit, onImages: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("PDF Studio", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            "Leia, anote, assine, organize e resuma PDFs com o Claude.",
            color = Color.White.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpen) { Text("Abrir PDF") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onImages) { Text("Criar PDF a partir de imagens", color = Color.White) }
        Spacer(Modifier.height(24.dp))
        Text("Versão ${appVersion()}", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ToolRow(vm: PdfStudioViewModel) {
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        LazyRow(
            modifier = Modifier.testTag("tools"),
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(Tool.entries) { t ->
                FilterChip(
                    selected = vm.tool == t,
                    onClick = { vm.tool = t },
                    label = { Text(t.label) },
                    leadingIcon = { Icon(TOOL_ICONS.getValue(t), null, Modifier.size(18.dp)) },
                )
            }
        }
        if (vm.tool != Tool.NAVIGATE && vm.tool != Tool.SIGN && vm.tool != Tool.WHITEOUT) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PALETTE.forEach { c ->
                        Box(
                            Modifier.size(24.dp).background(Color(c), CircleShape)
                                .border(
                                    if (vm.color == c) 3.dp else 1.dp,
                                    if (vm.color == c) MaterialTheme.colorScheme.primary else Color.Gray,
                                    CircleShape,
                                )
                                .clickable { vm.color = c },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.textSize = (vm.textSize - 2).coerceAtLeast(6f) }, modifier = Modifier.size(36.dp)) {
                    Text("A−")
                }
                Text("${vm.textSize.toInt()}", maxLines = 1)
                IconButton(onClick = { vm.textSize = (vm.textSize + 2).coerceAtMost(72f) }, modifier = Modifier.size(36.dp)) {
                    Text("A+")
                }
                IconButton(onClick = { vm.undo() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Desfazer")
                }
            }
        } else if (vm.tool != Tool.NAVIGATE) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (vm.tool == Tool.SIGN) "Toque na página para posicionar a assinatura."
                    else "Arraste sobre a área que deseja cobrir.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, "Desfazer") }
            }
        }
    }
}

/** Onde a tela estava quando o zoom mudou (para manter o mesmo ponto à vista). */
private class ZoomAnchor(val first: Int, val offset: Int, val scrollX: Int, val ratio: Float, val pivot: Offset)

/** Página que ocupa o meio da tela (no começo/fim do documento, a primeira/última visível). */
private fun visiblePage(state: LazyListState): Int {
    val info = state.layoutInfo
    val items = info.visibleItemsInfo
    if (items.isEmpty()) return state.firstVisibleItemIndex
    if (!state.canScrollForward) return items.last().index
    if (!state.canScrollBackward) return items.first().index
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val hit = items.firstOrNull { it.offset <= center && center < it.offset + it.size }
    return (hit ?: items.minBy { abs(it.offset + it.size / 2 - center) }).index
}

/** Todas as páginas em sequência: rolagem contínua, zoom e pinça. */
@Composable
private fun PdfViewer(
    vm: PdfStudioViewModel,
    zoom: Float,
    onZoom: (Float) -> Unit,
    onTextAt: (Int, Float, Float) -> Unit,
    onSignAt: (Int, Float, Float) -> Unit,
    onEditArea: (Int, Float, Float, Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Escala visual durante a pinça; ao soltar os dedos vira zoom de verdade (re-renderiza nítido).
    var pinch by remember { mutableFloatStateOf(1f) }
    var pivot by remember { mutableStateOf(Offset.Zero) }
    // Ponto da tela que deve ficar parado quando o zoom mudar (dedos, toque duplo ou centro da tela).
    var zoomPivot by remember { mutableStateOf<Offset?>(null) }
    val previousZoom = remember { floatArrayOf(zoom) }
    val navigate = vm.tool == Tool.NAVIGATE

    // Página atual = a que está no meio da tela.
    LaunchedEffect(listState) {
        snapshotFlow { visiblePage(listState) }.distinctUntilChanged().collect { vm.onVisiblePage(it) }
    }
    // Setas, busca e painel de páginas pedem para rolar até uma página.
    LaunchedEffect(vm.scrollRequest) {
        val target = vm.scrollRequest ?: return@LaunchedEffect
        withFrameNanos { }
        listState.scrollToItem(target.coerceIn(0, max(0, vm.pageCount - 1)))
        vm.scrollRequest = null
    }
    // Só mantém na memória as imagens das páginas perto da tela.
    LaunchedEffect(listState, zoom) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { visible ->
                if (visible.isNotEmpty()) {
                    val extra = if (zoom > 1.5f) 1 else 2
                    vm.trimPages((visible.first() - extra)..(visible.last() + extra))
                }
            }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val marginPx = with(density) { 12.dp.toPx() }
        val pageWidthPx = ((constraints.maxWidth - 2 * marginPx) * zoom).roundToInt().coerceAtLeast(64)
        val contentWidthPx = pageWidthPx + 2 * marginPx
        val viewportCenter = Offset(constraints.maxWidth / 2f, constraints.maxHeight / 2f)

        // Quando o zoom muda, guarda (antes do novo layout) onde a tela estava...
        val anchor = if (previousZoom[0] != zoom) {
            val ratio = zoom / previousZoom[0]
            previousZoom[0] = zoom
            Snapshot.withoutReadObservation {
                ZoomAnchor(
                    listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset,
                    hScroll.value, ratio, zoomPivot ?: viewportCenter,
                )
            }
        } else {
            null
        }
        // ...e depois rola para que esse ponto continue no mesmo lugar da tela.
        LaunchedEffect(zoom) {
            val a = anchor ?: return@LaunchedEffect
            zoomPivot = null
            listState.scrollToItem(a.first, ((a.offset + a.pivot.y) * a.ratio - a.pivot.y).roundToInt().coerceAtLeast(0))
            hScroll.scrollTo(((a.scrollX + a.pivot.x) * a.ratio - a.pivot.x).roundToInt().coerceAtLeast(0))
        }

        /** Toque duplo numa página: alterna o zoom centrado no ponto tocado. */
        val onDoubleTapAt: (Int, Offset) -> Unit = { index, local ->
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (item != null) zoomPivot = Offset(marginPx + local.x - hScroll.value, item.offset + local.y)
            onZoom(if (zoom > 1.01f) 1f else 2f)
        }

        Box(
            Modifier.fillMaxSize()
                // Dois dedos: zoom (pinça) e rolagem, em qualquer ferramenta. Um dedo rola ou desenha.
                .pointerInput(zoom) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var pinching = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                if (!pinching) {
                                    pinching = true
                                    pivot = event.calculateCentroid(useCurrent = true)
                                }
                                pinch = (pinch * event.calculateZoom()).coerceIn(MIN_ZOOM / zoom, MAX_ZOOM / zoom)
                                val pan = event.calculatePan()
                                listState.dispatchRawDelta(-pan.y / pinch)
                                hScroll.dispatchRawDelta(-pan.x / pinch)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (pinching) {
                            val newZoom = (zoom * pinch).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            pinch = 1f
                            if (newZoom != zoom) {
                                // Mantém sob os dedos o ponto onde a pinça começou.
                                zoomPivot = pivot
                                onZoom(newZoom)
                            }
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = pinch
                    scaleY = pinch
                    transformOrigin = TransformOrigin(
                        (pivot.x / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
                        (pivot.y / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
                    )
                }
                .horizontalScroll(hScroll, enabled = navigate),
        ) {
            LazyColumn(
                state = listState,
                userScrollEnabled = navigate,
                modifier = Modifier
                    .width(with(density) { contentWidthPx.toDp() })
                    .fillMaxHeight()
                    .testTag("viewer"),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(vm.pageCount, key = { "${vm.docVersion}-$it" }) { index ->
                    PageItem(vm, index, pageWidthPx, zoom, onDoubleTapAt, onTextAt, onSignAt, onEditArea)
                }
            }
        }
    }
}

/** Uma página: imagem (carregada quando aparece na tela) + anotações + seleção. */
@Composable
private fun PageItem(
    vm: PdfStudioViewModel,
    index: Int,
    pageWidthPx: Int,
    zoom: Float,
    onDoubleTapAt: (Int, Offset) -> Unit,
    onTextAt: (Int, Float, Float) -> Unit,
    onSignAt: (Int, Float, Float) -> Unit,
    onEditArea: (Int, Float, Float, Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val sizePt = vm.pageSizes.getOrNull(index) ?: floatArrayOf(595f, 842f)
    val k = pageWidthPx / sizePt[0] // pixels por ponto
    val heightPx = sizePt[1] * k
    LaunchedEffect(index, pageWidthPx, vm.docVersion) { vm.renderPage(index, pageWidthPx) }
    LaunchedEffect(index, vm.docVersion) { vm.loadAnnotations(index) }
    val img = vm.pageBitmaps[index]
    Box(
        Modifier
            .testTag("page-$index")
            .size(with(density) { pageWidthPx.toDp() }, with(density) { heightPx.toDp() })
            .background(Color.White),
    ) {
        if (img != null) Image(img.bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        AnnotationLayer(vm, index, k, zoom, { local -> onDoubleTapAt(index, local) }, onTextAt, onSignAt, onEditArea)
        if (vm.tool == Tool.NAVIGATE && vm.selectionPage == index) SelectionHandles(vm, k)
    }
}

@Composable
private fun AnnotationLayer(
    vm: PdfStudioViewModel,
    index: Int,
    k: Float,
    zoom: Float,
    onDoubleTap: (Offset) -> Unit,
    onTextAt: (Int, Float, Float) -> Unit,
    onSignAt: (Int, Float, Float) -> Unit,
    onEditArea: (Int, Float, Float, Float, Float) -> Unit,
) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var inkPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val bitmaps = remember { IdentityHashMap<ByteArray, android.graphics.Bitmap>() }
    val tool = vm.tool
    val haptics = LocalHapticFeedback.current

    Canvas(
        Modifier.fillMaxSize().pointerInput(tool, k, zoom) {
            when (tool) {
                Tool.NAVIGATE -> detectTapGestures(
                    onDoubleTap = { onDoubleTap(it) },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.selectWordAt(index, it.x / k, it.y / k)
                    },
                    onTap = { vm.clearSelection() },
                )
                Tool.TEXT -> detectTapGestures { onTextAt(index, it.x / k, it.y / k) }
                Tool.SIGN -> detectTapGestures { onSignAt(index, it.x / k, it.y / k) }
                Tool.INK -> detectExactDrag(
                    onStart = { inkPoints = listOf(it) },
                    onMove = { inkPoints = inkPoints + it },
                    onEnd = {
                        // Um toque sem arrastar vira um ponto (ex.: pingo do "i").
                        val pts = if (inkPoints.size == 1) inkPoints + (inkPoints[0] + Offset(0.5f, 0.5f)) else inkPoints
                        if (pts.size > 1) {
                            val arr = FloatArray(pts.size * 2)
                            pts.forEachIndexed { i, p ->
                                arr[2 * i] = p.x / k
                                arr[2 * i + 1] = p.y / k
                            }
                            vm.addAnnotation(index, Annotation.Ink(arr, vm.color, (vm.textSize / 6f).coerceAtLeast(1f)))
                        }
                        inkPoints = emptyList()
                    },
                    onCancel = { inkPoints = emptyList() },
                )
                Tool.HIGHLIGHT, Tool.WHITEOUT, Tool.EDIT_TEXT -> detectExactDrag(
                    onStart = {
                        dragStart = it
                        dragEnd = it
                    },
                    onMove = { dragEnd = it },
                    onEnd = {
                        val s = dragStart
                        val e = dragEnd
                        dragStart = null
                        dragEnd = null
                        if (s != null && e != null) {
                            val x = min(s.x, e.x) / k
                            val y = min(s.y, e.y) / k
                            val w = abs(e.x - s.x) / k
                            val h = abs(e.y - s.y) / k
                            if (w > 3 && h > 3) when (tool) {
                                Tool.HIGHLIGHT -> vm.addAnnotation(index, Annotation.Highlight(x, y, w, h, highlightColor(vm.color)))
                                Tool.WHITEOUT -> vm.addAnnotation(index, Annotation.Whiteout(x, y, w, h, AColor.WHITE))
                                else -> onEditArea(index, x, y, w, h)
                            }
                        }
                    },
                    onCancel = {
                        dragStart = null
                        dragEnd = null
                    },
                )
            }
        }
    ) {
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.scale(k, k)
        vm.annotationsByPage[index]?.forEach { drawAnnotation(canvas, it, bitmaps) }
        if (inkPoints.size > 1) {
            val arr = FloatArray(inkPoints.size * 2)
            inkPoints.forEachIndexed { i, p ->
                arr[2 * i] = p.x / k
                arr[2 * i + 1] = p.y / k
            }
            drawAnnotation(canvas, Annotation.Ink(arr, vm.color, (vm.textSize / 6f).coerceAtLeast(1f)), bitmaps)
        }
        val s = dragStart
        val e = dragEnd
        if (s != null && e != null) {
            val x = min(s.x, e.x) / k
            val y = min(s.y, e.y) / k
            val w = abs(e.x - s.x) / k
            val h = abs(e.y - s.y) / k
            when (tool) {
                Tool.HIGHLIGHT -> drawAnnotation(canvas, Annotation.Highlight(x, y, w, h, highlightColor(vm.color)), bitmaps)
                Tool.WHITEOUT -> drawAnnotation(canvas, Annotation.Whiteout(x, y, w, h, AColor.WHITE), bitmaps)
                else -> {}
            }
            canvas.drawRect(x, y, x + w, y + h, Paint().apply {
                style = Paint.Style.STROKE
                color = AColor.rgb(0x19, 0x76, 0xD2)
                strokeWidth = 1.5f / k
            })
        }
        if (vm.selectionPage == index) {
            vm.selection?.let { sel ->
                val paint = Paint().apply { color = AColor.argb(90, 0x21, 0x96, 0xF3) }
                TextLayout.lineRects(vm.pageGlyphs, sel).forEach { r -> canvas.drawRect(r[0], r[1], r[2], r[3], paint) }
            }
        }
        canvas.restore()
    }
}

/** Alças nas pontas da seleção: arraste para aumentar ou diminuir o trecho. */
@Composable
private fun SelectionHandles(vm: PdfStudioViewModel, k: Float) {
    val sel = vm.selection ?: return
    val (first, last) = TextLayout.edges(vm.pageGlyphs, sel) ?: return
    SelectionHandle(Offset(first.x * k, (first.top + first.h) * k), "selStart") { p ->
        vm.moveSelectionEdge(true, p.x / k, p.y / k)
    }
    SelectionHandle(Offset((last.x + last.w) * k, (last.top + last.h) * k), "selEnd") { p ->
        vm.moveSelectionEdge(false, p.x / k, p.y / k)
    }
}

@Composable
private fun SelectionHandle(anchor: Offset, tag: String, onMove: (Offset) -> Unit) {
    val density = LocalDensity.current
    val sizePx = with(density) { 28.dp.toPx() }
    val currentAnchor by rememberUpdatedState(anchor)
    val currentOnMove by rememberUpdatedState(onMove)
    val color = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .offset { IntOffset((anchor.x - sizePx / 2).roundToInt(), anchor.y.roundToInt()) }
            .size(28.dp)
            .testTag(tag)
            .pointerInput(Unit) {
                var finger = Offset.Zero
                detectDragGestures(
                    onDragStart = { finger = currentAnchor },
                    onDrag = { change, amount ->
                        change.consume()
                        finger += amount
                        currentOnMove(finger)
                    },
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height / 2), strokeWidth = 3.dp.toPx())
            drawCircle(color, radius = size.minDimension / 3, center = Offset(size.width / 2, size.height * 0.62f))
        }
    }
}

/** Quadro com a tradução para o português (da seleção ou da página). */
@Composable
private fun TranslationCard(
    vm: PdfStudioViewModel,
    modifier: Modifier,
    onCopy: (String) -> Unit,
    onSpeak: (String) -> Unit,
) {
    val state = vm.translation ?: return
    Surface(
        modifier = modifier.testTag("translationCard"),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp).heightIn(max = 280.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Translate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                val title = when (state) {
                    is TranslationState.Done ->
                        if (state.alreadyPortuguese) "O texto já está em português"
                        else "Tradução (${languageName(state.sourceLanguage)} → português)"
                    else -> "Tradução"
                }
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Auto", style = MaterialTheme.typography.labelSmall)
                Switch(
                    checked = vm.autoTranslate,
                    onCheckedChange = { vm.setAutoTranslate(it) },
                    modifier = Modifier.scale(0.8f).testTag("autoTranslate"),
                )
                IconButton(onClick = { vm.clearTranslation() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, "Fechar tradução")
                }
            }
            when (state) {
                is TranslationState.Working -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                }
                is TranslationState.Failed -> Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                )
                is TranslationState.Done -> {
                    SelectionContainer(Modifier.weight(1f, fill = false)) {
                        Text(state.text, modifier = Modifier.verticalScroll(rememberScrollState()).padding(end = 8.dp))
                    }
                    Row {
                        TextButton(onClick = { onCopy(state.text) }) { Text("Copiar tradução") }
                        TextButton(onClick = { onSpeak(state.text) }) { Text("Ouvir") }
                    }
                }
            }
        }
    }
}

/** Barra com as ações do texto selecionado. */
@Composable
private fun SelectionBar(
    modifier: Modifier,
    onCopy: () -> Unit,
    onTranslate: () -> Unit,
    onHighlight: () -> Unit,
    onSpeak: () -> Unit,
    onExplain: () -> Unit,
    onAll: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = modifier.testTag("selectionBar"),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCopy) { Text("Copiar") }
            TextButton(onClick = onTranslate) { Text("Traduzir") }
            TextButton(onClick = onHighlight) { Text("Destacar") }
            TextButton(onClick = onSpeak) { Text("Ouvir") }
            TextButton(onClick = onExplain) { Text("Explicar") }
            TextButton(onClick = onAll) { Text("Tudo") }
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Close, "Fechar seleção") }
        }
    }
}

/** Destaque: cores escuras viram amarelo para não cobrir o texto. */
private fun highlightColor(c: Int): Int {
    val hsv = FloatArray(3)
    AColor.colorToHSV(c, hsv)
    return if (hsv[2] < 0.5f || c == PALETTE[0]) PALETTE[4] else c
}

private fun drawAnnotation(canvas: android.graphics.Canvas, a: Annotation, cache: IdentityHashMap<ByteArray, android.graphics.Bitmap>) {
    when (a) {
        is Annotation.Text -> {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = a.color
                textSize = a.size
                typeface = Typeface.SANS_SERIF
            }
            a.text.split(Regex("\\R")).forEachIndexed { i, line ->
                canvas.drawText(line, a.x, a.y + a.size + i * a.size * 1.2f, p)
            }
        }
        is Annotation.Highlight -> canvas.drawRect(a.x, a.y, a.x + a.w, a.y + a.h, Paint().apply {
            color = a.color
            alpha = 90
        })
        is Annotation.Whiteout -> canvas.drawRect(a.x, a.y, a.x + a.w, a.y + a.h, Paint().apply { color = a.color })
        is Annotation.Ink -> {
            val path = android.graphics.Path()
            path.moveTo(a.points[0], a.points[1])
            var i = 2
            while (i + 1 < a.points.size) {
                path.lineTo(a.points[i], a.points[i + 1])
                i += 2
            }
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = a.color
                style = Paint.Style.STROKE
                strokeWidth = a.width
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            })
        }
        is Annotation.Image -> {
            val bmp = cache.getOrPut(a.png) { BitmapFactory.decodeByteArray(a.png, 0, a.png.size) } ?: return
            canvas.drawBitmap(bmp, null, android.graphics.RectF(a.x, a.y, a.x + a.w, a.y + a.h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }
}

@Composable
private fun PagesGrid(vm: PdfStudioViewModel, onOpen: (Int) -> Unit, onDelete: (Int) -> Unit) {
    Column(Modifier.fillMaxHeight(0.9f)) {
        Text(
            "Páginas (${vm.pageCount})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(130.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(List(vm.pageCount) { it }, key = { _, i -> "${vm.docVersion}-$i" }) { _, i ->
                LaunchedEffect(vm.docVersion, i) { vm.requestThumbnail(i) }
                Card(Modifier.clickable { onOpen(i) }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val thumb = vm.thumbnails[i]
                        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).background(Color.White)) {
                            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                        Text(
                            "${i + 1}",
                            fontWeight = if (i == vm.current) FontWeight.Bold else FontWeight.Normal,
                            color = if (i == vm.current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                        Row {
                            SmallIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Mover para trás", i > 0) { vm.movePage(i, i - 1) }
                            SmallIcon(Icons.Filled.RotateRight, "Girar") { vm.rotate(i, 90) }
                            SmallIcon(Icons.Filled.Delete, "Excluir", vm.pageCount > 1) { onDelete(i) }
                            SmallIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Mover para frente", i < vm.pageCount - 1) {
                                vm.movePage(i, i + 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(icon, desc, Modifier.size(18.dp))
    }
}

@Composable
private fun AssistantPanel(vm: PdfStudioViewModel, onConfigureKey: () -> Unit, onCopy: () -> Unit) {
    var question by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxHeight(0.9f).imePadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Claude", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onConfigureKey) { Text(if (vm.apiKey.isBlank()) "Configurar chave" else "Chave de API") }
        }
        if (vm.apiKey.isBlank()) {
            Text(
                "Para usar o Claude, configure uma chave de API (console.anthropic.com).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !vm.aiRunning, onClick = { vm.askClaude(false, ClaudeService.PROMPT_SUMMARY_DOC) }) {
                Text("Resumir documento")
            }
            OutlinedButton(enabled = !vm.aiRunning, onClick = { vm.askClaude(true, ClaudeService.PROMPT_SUMMARY_PAGE) }) {
                Text("Resumir página")
            }
        }
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            label = { Text("Pergunte algo sobre o PDF") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(enabled = !vm.aiRunning && question.isNotBlank(), onClick = {
                    vm.askClaude(false, question.trim())
                    question = ""
                }) { Icon(Icons.AutoMirrored.Filled.Send, "Perguntar") }
            },
        )
        if (vm.aiRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                .padding(12.dp),
        ) {
            SelectionContainer {
                Text(
                    vm.aiText.ifEmpty { "O resumo e as respostas aparecem aqui." },
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
        Row(Modifier.padding(bottom = 16.dp)) {
            if (vm.speaking) {
                TextButton(onClick = { vm.stopSpeaking() }) {
                    Icon(Icons.Filled.Stop, null)
                    Text(" Parar")
                }
            } else {
                TextButton(enabled = vm.aiText.isNotBlank(), onClick = { vm.speak(vm.aiText) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                    Text(" Ouvir")
                }
            }
            TextButton(enabled = vm.aiText.isNotBlank(), onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, null)
                Text(" Copiar")
            }
        }
    }
}
