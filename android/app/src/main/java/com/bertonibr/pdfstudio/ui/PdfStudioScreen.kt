package com.bertonibr.pdfstudio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
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
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bertonibr.pdfstudio.PdfStudioViewModel
import com.bertonibr.pdfstudio.Tool
import com.bertonibr.pdfstudio.model.Annotation
import com.bertonibr.pdfstudio.service.ClaudeService
import java.util.IdentityHashMap
import kotlin.math.abs
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
    var pendingSignaturePoint by remember { mutableStateOf<Offset?>(null) }
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
                BottomAppBar {
                    IconButton(onClick = { vm.goTo(vm.current - 1) }, enabled = vm.current > 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Anterior")
                    }
                    Text(
                        "${vm.current + 1} / ${vm.pageCount}",
                        modifier = Modifier.clickable {
                            textRequest = TextRequest("Ir para página", "Página (1-${vm.pageCount})", singleLine = true) {
                                it.trim().toIntOrNull()?.let { p -> vm.goTo(p - 1) }
                            }
                        }.padding(8.dp),
                    )
                    IconButton(onClick = { vm.goTo(vm.current + 1) }, enabled = vm.current < vm.pageCount - 1) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Próxima")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(MIN_ZOOM) }) { Icon(Icons.Filled.ZoomOut, "Reduzir") }
                    Text("${(zoom * 100).toInt()}%", modifier = Modifier.clickable { zoom = 1f })
                    IconButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(MAX_ZOOM) }) { Icon(Icons.Filled.ZoomIn, "Ampliar") }
                    if (vm.speaking) {
                        IconButton(onClick = { vm.stopSpeaking() }) { Icon(Icons.Filled.Stop, "Parar leitura") }
                    } else {
                        IconButton(onClick = { vm.readPageAloud() }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Ler página") }
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
                PageView(
                    vm = vm,
                    zoom = zoom,
                    onZoom = { zoom = it },
                    onTextAt = { x, y ->
                        textRequest = TextRequest("Inserir texto", "Texto") {
                            if (it.isNotBlank()) vm.addAnnotation(Annotation.Text(x, y, it, vm.textSize, vm.color))
                        }
                    },
                    onSignAt = { x, y ->
                        val png = vm.signaturePng
                        if (png == null) {
                            pendingSignaturePoint = Offset(x, y)
                            showSignature = true
                        } else {
                            placeSignature(vm, png, x, y)
                        }
                    },
                    onEditArea = { x, y, w, h ->
                        vm.textInArea(x, y, w, h) { original ->
                            textRequest = TextRequest(
                                "Editar texto",
                                if (original.isEmpty()) "Nenhum texto na área. Novo texto:" else "Novo texto",
                                original,
                            ) { newText ->
                                vm.addAnnotation(Annotation.Whiteout(x, y, w, h, AColor.WHITE))
                                if (newText.isNotBlank()) {
                                    val c = if (vm.color == PALETTE[0]) AColor.BLACK else vm.color
                                    vm.addAnnotation(Annotation.Text(x, y, newText, vm.textSize, c))
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
        }
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
            pendingSignaturePoint = null
        }) { png ->
            vm.signaturePng = png
            showSignature = false
            pendingSignaturePoint?.let { placeSignature(vm, png, it.x, it.y) }
            pendingSignaturePoint = null
            vm.tool = Tool.SIGN
        }
    }

    vm.extractedText?.let { text ->
        TextViewerDialog(
            text = text,
            onCopy = { context.copy(text); vm.message = "Copiado." },
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
            AssistantPanel(vm, onConfigureKey = { showApiKey = true }, onCopy = {
                context.copy(vm.aiText)
                vm.message = "Copiado."
            })
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

private fun placeSignature(vm: PdfStudioViewModel, png: ByteArray, x: Float, y: Float) {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(png, 0, png.size, opts)
    val w = 150f
    val h = w * opts.outHeight / opts.outWidth.coerceAtLeast(1)
    vm.addAnnotation(Annotation.Image(png, x - w / 2, y - h / 2, w, h))
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

private fun Context.copy(text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("PDF Studio", text))
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
    }
}

@Composable
private fun ToolRow(vm: PdfStudioViewModel) {
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        LazyRow(
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PALETTE.forEach { c ->
                    Box(
                        Modifier.size(26.dp).background(Color(c), CircleShape)
                            .border(if (vm.color == c) 3.dp else 1.dp, if (vm.color == c) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                            .clickable { vm.color = c },
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.textSize = (vm.textSize - 2).coerceAtLeast(6f) }) { Text("A−") }
                Text("${vm.textSize.toInt()}")
                TextButton(onClick = { vm.textSize = (vm.textSize + 2).coerceAtMost(72f) }) { Text("A+") }
                IconButton(onClick = { vm.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, "Desfazer") }
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

@Composable
private fun PageView(
    vm: PdfStudioViewModel,
    zoom: Float,
    onZoom: (Float) -> Unit,
    onTextAt: (Float, Float) -> Unit,
    onSignAt: (Float, Float) -> Unit,
    onEditArea: (Float, Float, Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Escala visual durante a pinça; ao soltar os dedos vira zoom de verdade (re-renderiza nítido).
    var pinch by remember { mutableFloatStateOf(1f) }
    var pivot by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val marginPx = with(density) { 12.dp.toPx() }
        val renderWidth = ((constraints.maxWidth - 2 * marginPx) * zoom).toInt().coerceAtLeast(64)
        LaunchedEffect(renderWidth, vm.current, vm.docVersion) { vm.requestRender(renderWidth) }

        val page = vm.page ?: return@BoxWithConstraints
        val k = renderWidth / page.widthPt // pixels por ponto
        val heightPx = page.heightPt * k
        val navigate = vm.tool == Tool.NAVIGATE

        Box(
            Modifier.fillMaxSize()
                // Pinça com dois dedos (funciona em qualquer ferramenta). Um dedo continua rolando/desenhando.
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
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (pinching) {
                            val newZoom = (zoom * pinch).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            val ratio = newZoom / zoom
                            val sx = hScroll.value
                            val sy = vScroll.value
                            val p = pivot
                            pinch = 1f
                            onZoom(newZoom)
                            // Mantém sob os dedos o ponto onde a pinça começou.
                            scope.launch {
                                withFrameNanos { }
                                withFrameNanos { }
                                hScroll.scrollTo(((sx + p.x) * ratio - p.x).toInt().coerceAtLeast(0))
                                vScroll.scrollTo(((sy + p.y) * ratio - p.y).toInt().coerceAtLeast(0))
                            }
                        }
                    }
                }
                .verticalScroll(vScroll, enabled = navigate)
                .horizontalScroll(hScroll, enabled = navigate),
        ) {
            Box(
                Modifier.padding(12.dp)
                    .size(with(density) { renderWidth.toDp() }, with(density) { heightPx.toDp() })
                    .graphicsLayer {
                        scaleX = pinch
                        scaleY = pinch
                        val w = size.width.coerceAtLeast(1f)
                        val h = size.height.coerceAtLeast(1f)
                        transformOrigin = TransformOrigin(
                            ((hScroll.value + pivot.x - marginPx) / w).coerceIn(0f, 1f),
                            ((vScroll.value + pivot.y - marginPx) / h).coerceIn(0f, 1f),
                        )
                    }
                    .background(Color.White),
            ) {
                Image(page.bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                AnnotationLayer(vm, k, zoom, onZoom, onTextAt, onSignAt, onEditArea)
            }
        }
    }
}

@Composable
private fun AnnotationLayer(
    vm: PdfStudioViewModel,
    k: Float,
    zoom: Float,
    onZoom: (Float) -> Unit,
    onTextAt: (Float, Float) -> Unit,
    onSignAt: (Float, Float) -> Unit,
    onEditArea: (Float, Float, Float, Float) -> Unit,
) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var inkPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val bitmaps = remember { IdentityHashMap<ByteArray, android.graphics.Bitmap>() }
    val tool = vm.tool

    Canvas(
        Modifier.fillMaxSize().pointerInput(tool, k, zoom) {
            when (tool) {
                Tool.NAVIGATE -> detectTapGestures(onDoubleTap = { onZoom(if (zoom > 1.01f) 1f else 2f) })
                Tool.TEXT -> detectTapGestures { onTextAt(it.x / k, it.y / k) }
                Tool.SIGN -> detectTapGestures { onSignAt(it.x / k, it.y / k) }
                Tool.INK -> detectDragGestures(
                    onDragStart = { inkPoints = listOf(it) },
                    onDrag = { change, _ ->
                        change.consume()
                        inkPoints = inkPoints + change.position
                    },
                    onDragEnd = {
                        if (inkPoints.size > 1) {
                            val arr = FloatArray(inkPoints.size * 2)
                            inkPoints.forEachIndexed { i, p ->
                                arr[2 * i] = p.x / k
                                arr[2 * i + 1] = p.y / k
                            }
                            vm.addAnnotation(Annotation.Ink(arr, vm.color, (vm.textSize / 6f).coerceAtLeast(1f)))
                        }
                        inkPoints = emptyList()
                    },
                    onDragCancel = { inkPoints = emptyList() },
                )
                Tool.HIGHLIGHT, Tool.WHITEOUT, Tool.EDIT_TEXT -> detectDragGestures(
                    onDragStart = {
                        dragStart = it
                        dragEnd = it
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragEnd = change.position
                    },
                    onDragEnd = {
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
                                Tool.HIGHLIGHT -> vm.addAnnotation(Annotation.Highlight(x, y, w, h, highlightColor(vm.color)))
                                Tool.WHITEOUT -> vm.addAnnotation(Annotation.Whiteout(x, y, w, h, AColor.WHITE))
                                else -> onEditArea(x, y, w, h)
                            }
                        }
                    },
                    onDragCancel = {
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
        vm.annotations.forEach { drawAnnotation(canvas, it, bitmaps) }
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
        canvas.restore()
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
    Column(Modifier.fillMaxHeight(0.9f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
