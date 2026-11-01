package com.bertonibr.pdfstudio.model

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.util.Matrix

/**
 * O PdfRenderer do Android não desenha anotações nem campos de formulário.
 * Para a exibição, gravamos a aparência delas no conteúdo de uma CÓPIA do
 * documento (o arquivo do usuário continua com as anotações originais).
 */
object ExistingAnnotations {

    fun flattenForDisplay(doc: PDDocument) {
        // 1) Campos de formulário preenchidos
        try {
            val form = doc.documentCatalog.acroForm
            if (form != null) {
                val fields = form.fieldTree.toList()
                try {
                    form.flatten(fields, form.needAppearances)
                } catch (e: Exception) {
                    form.flatten()
                }
            }
        } catch (_: Exception) {
            // formulário malformado: segue sem ele
        }

        // 2) Demais anotações (destaques, comentários, carimbos, desenhos...)
        for (page in doc.pages) {
            val annotations = try {
                page.annotations
            } catch (_: Exception) {
                continue
            }
            if (annotations.isEmpty()) continue
            try {
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (a in annotations) {
                        if (a is PDAnnotationLink || a is PDAnnotationPopup) continue
                        if (a.isHidden || a.isNoView || a.isInvisible) continue
                        val rect = a.rectangle ?: continue
                        var ap = a.normalAppearanceStream
                        if (ap == null) {
                            try {
                                a.constructAppearances(doc)
                                ap = a.normalAppearanceStream
                            } catch (_: Exception) {
                            }
                        }
                        if (ap != null) drawAppearance(cs, rect, ap)
                    }
                }
                // Remove da cópia para não desenhar duas vezes (Android 15+ já desenha algumas).
                page.annotations = emptyList()
            } catch (_: Exception) {
                // página problemática: mantém como está
            }
        }
    }

    /** Algoritmo da especificação PDF (12.5.5): encaixa a aparência no retângulo da anotação. */
    private fun drawAppearance(cs: PDPageContentStream, rect: PDRectangle, ap: PDAppearanceStream) {
        val bbox = ap.getBBox() ?: return
        val m = ap.matrix
        val corners = listOf(
            m.transformPoint(bbox.lowerLeftX, bbox.lowerLeftY),
            m.transformPoint(bbox.upperRightX, bbox.lowerLeftY),
            m.transformPoint(bbox.upperRightX, bbox.upperRightY),
            m.transformPoint(bbox.lowerLeftX, bbox.upperRightY),
        )
        val minX = corners.minOf { it.x }
        val minY = corners.minOf { it.y }
        val w = corners.maxOf { it.x } - minX
        val h = corners.maxOf { it.y } - minY
        if (w <= 0f || h <= 0f || rect.width <= 0f || rect.height <= 0f) return
        val a = Matrix.getTranslateInstance(rect.lowerLeftX, rect.lowerLeftY)
        a.concatenate(Matrix.getScaleInstance(rect.width / w, rect.height / h))
        a.concatenate(Matrix.getTranslateInstance(-minX, -minY))
        cs.saveGraphicsState()
        cs.transform(a)
        cs.drawForm(ap)
        cs.restoreGraphicsState()
    }
}
