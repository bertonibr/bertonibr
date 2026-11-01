package com.bertonibr.pdfstudio.model

/**
 * Anotações sobrepostas a uma página. Coordenadas em pontos (1/72") no
 * sistema de exibição da página: origem no canto superior esquerdo, já
 * considerando a rotação da página. Cores em ARGB (android.graphics.Color).
 */
sealed interface Annotation {
    /** Texto livre; (x, y) é o canto superior esquerdo da primeira linha. */
    data class Text(val x: Float, val y: Float, val text: String, val size: Float, val color: Int) : Annotation

    /** Marca-texto semitransparente. */
    data class Highlight(val x: Float, val y: Float, val w: Float, val h: Float, val color: Int) : Annotation

    /** Desenho à mão livre. Pontos alternados: x0, y0, x1, y1, ... */
    data class Ink(val points: FloatArray, val color: Int, val width: Float) : Annotation

    /** Retângulo opaco (corretivo). */
    data class Whiteout(val x: Float, val y: Float, val w: Float, val h: Float, val color: Int) : Annotation

    /** Imagem PNG (assinatura, carimbo). */
    class Image(val png: ByteArray, val x: Float, val y: Float, val w: Float, val h: Float) : Annotation
}
