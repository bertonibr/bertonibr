package com.bertonibr.pdfstudio.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * Arrasto que começa exatamente no ponto tocado (o detectDragGestures padrão
 * só começa depois de alguns milímetros, cortando o início de traços e
 * seleções). Um toque sem movimento também chega a [onEnd] (vira um ponto).
 * Se outro gesto assumir o toque (ex.: pinça com dois dedos), chama [onCancel].
 */
suspend fun PointerInputScope.detectExactDrag(
    onStart: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown()
    down.consume()
    onStart(down.position)
    val completed = drag(down.id) { change ->
        onMove(change.position)
        change.consume()
    }
    if (completed) onEnd() else onCancel()
}
