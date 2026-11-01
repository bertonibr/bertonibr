package com.bertonibr.pdfstudio.model

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.IOException

/** Grava (achata) as anotações no conteúdo das páginas do PDF. */
object Flattener {

    fun loadFont(context: Context, doc: PDDocument): PDFont = try {
        context.assets.open("fonts/DejaVuSans.ttf").use { PDType0Font.load(doc, it) }
    } catch (e: IOException) {
        PDType1Font.HELVETICA
    }

    /** Matriz que leva coordenadas de exibição (y para baixo) ao espaço do PDF. */
    private fun displayToUser(page: PDPage): Matrix {
        val crop = page.cropBox
        val llx = crop.lowerLeftX
        val lly = crop.lowerLeftY
        val w = crop.width
        val h = crop.height
        return when (((page.rotation % 360) + 360) % 360) {
            90 -> Matrix(0f, 1f, 1f, 0f, llx, lly)
            180 -> Matrix(-1f, 0f, 0f, 1f, llx + w, lly)
            270 -> Matrix(0f, -1f, -1f, 0f, llx + w, lly + h)
            else -> Matrix(1f, 0f, 0f, -1f, llx, lly + h)
        }
    }

    fun flatten(doc: PDDocument, page: PDPage, list: List<Annotation>?, font: PDFont) {
        if (list.isNullOrEmpty()) return
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.transform(displayToUser(page))
            for (a in list) {
                when (a) {
                    is Annotation.Text -> {
                        cs.fill(a.color)
                        cs.beginText()
                        cs.setFont(font, a.size)
                        a.text.split(Regex("\\R")).forEachIndexed { i, line ->
                            cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, a.x, a.y + a.size + i * a.size * 1.2f))
                            cs.showText(sanitize(line, font))
                        }
                        cs.endText()
                    }
                    is Annotation.Highlight -> {
                        cs.saveGraphicsState()
                        val gs = PDExtendedGraphicsState()
                        gs.nonStrokingAlphaConstant = 0.35f
                        gs.blendMode = BlendMode.MULTIPLY
                        cs.setGraphicsStateParameters(gs)
                        cs.fill(a.color)
                        cs.addRect(a.x, a.y, a.w, a.h)
                        cs.fill()
                        cs.restoreGraphicsState()
                    }
                    is Annotation.Whiteout -> {
                        cs.fill(a.color)
                        cs.addRect(a.x, a.y, a.w, a.h)
                        cs.fill()
                    }
                    is Annotation.Ink -> {
                        if (a.points.size < 4) continue
                        cs.setStrokingColor(Color.red(a.color) / 255f, Color.green(a.color) / 255f, Color.blue(a.color) / 255f)
                        cs.setLineWidth(a.width)
                        cs.setLineCapStyle(1)
                        cs.setLineJoinStyle(1)
                        cs.moveTo(a.points[0], a.points[1])
                        var i = 2
                        while (i + 1 < a.points.size) {
                            cs.lineTo(a.points[i], a.points[i + 1])
                            i += 2
                        }
                        cs.stroke()
                    }
                    is Annotation.Image -> {
                        val bmp = BitmapFactory.decodeByteArray(a.png, 0, a.png.size) ?: continue
                        val img = LosslessFactory.createFromImage(doc, bmp)
                        cs.drawImage(img, Matrix(a.w, 0f, 0f, -a.h, a.x, a.y + a.h))
                    }
                }
            }
        }
    }

    private fun PDPageContentStream.fill(color: Int) =
        setNonStrokingColor(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)

    /** Troca por '?' os caracteres que a fonte não consegue codificar. */
    private fun sanitize(s: String, font: PDFont): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val ch = if (cp == '\t'.code) "    " else String(Character.toChars(cp))
            try {
                font.encode(ch)
                sb.append(ch)
            } catch (e: Exception) {
                sb.append('?')
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
