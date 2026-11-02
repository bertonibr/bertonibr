package com.bertonibr.pdfstudio.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Um caractere do texto da página, em coordenadas de exibição (pontos, origem
 * no topo). Separadores (espaço/quebra de linha inseridos pela extração) têm
 * largura zero e não aparecem na tela, mas entram no texto copiado.
 */
class Glyph(val text: String, val x: Float, val top: Float, val w: Float, val h: Float) {
    val isSeparator: Boolean get() = w <= 0f
    val isSpace: Boolean get() = isSeparator || text.isBlank()
}

/** Seleção de texto: localizar palavras, estender e montar os retângulos por linha. */
object TextLayout {

    /** Glifo mais próximo do ponto (distância vertical pesa mais, para ficar na mesma linha). */
    fun nearest(glyphs: List<Glyph>, x: Float, y: Float, maxDistance: Float = Float.MAX_VALUE): Int? {
        var best = -1
        var bestD = Float.MAX_VALUE
        glyphs.forEachIndexed { i, g ->
            if (g.isSeparator) return@forEachIndexed
            val dx = when {
                x < g.x -> g.x - x
                x > g.x + g.w -> x - (g.x + g.w)
                else -> 0f
            }
            val dy = when {
                y < g.top -> g.top - y
                y > g.top + g.h -> y - (g.top + g.h)
                else -> 0f
            }
            val d = sqrt(dx * dx + 4 * dy * dy)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return if (best >= 0 && bestD <= maxDistance) best else null
    }

    /** Palavra que contém o glifo [i]. */
    fun wordAt(glyphs: List<Glyph>, i: Int): IntRange {
        var a = i
        var b = i
        while (a > 0 && !glyphs[a - 1].isSpace) a--
        while (b < glyphs.lastIndex && !glyphs[b + 1].isSpace) b++
        return a..b
    }

    fun all(glyphs: List<Glyph>): IntRange? {
        val a = glyphs.indexOfFirst { !it.isSeparator }
        val b = glyphs.indexOfLast { !it.isSeparator }
        return if (a < 0) null else a..b
    }

    fun text(glyphs: List<Glyph>, range: IntRange): String =
        range.filter { it in glyphs.indices }.joinToString("") { glyphs[it].text }
            .replace(Regex("[ \\t]+"), " ") // espaços repetidos viram um só
            .replace(Regex(" ?\\n ?"), "\n")
            .trim()

    /** Retângulos [esquerda, topo, direita, base] — um por linha selecionada. */
    fun lineRects(glyphs: List<Glyph>, range: IntRange): List<FloatArray> {
        val rects = mutableListOf<FloatArray>()
        var cur: FloatArray? = null
        for (i in range) {
            val g = glyphs.getOrNull(i) ?: continue
            if (g.isSeparator) continue
            val c = cur
            if (c != null && abs(g.top - c[1]) < g.h * 0.5f && g.x >= c[0] - 1f) {
                c[2] = max(c[2], g.x + g.w)
                c[3] = max(c[3], g.top + g.h)
            } else {
                val r = floatArrayOf(g.x, g.top, g.x + g.w, g.top + g.h)
                rects += r
                cur = r
            }
        }
        return rects
    }

    /** Primeiro/último glifo visível da seleção (onde ficam as alças). */
    fun edges(glyphs: List<Glyph>, range: IntRange): Pair<Glyph, Glyph>? {
        val a = range.firstOrNull { glyphs.getOrNull(it)?.isSeparator == false } ?: return null
        val b = range.reversed().firstOrNull { glyphs.getOrNull(it)?.isSeparator == false } ?: return null
        return glyphs[a] to glyphs[b]
    }
}
