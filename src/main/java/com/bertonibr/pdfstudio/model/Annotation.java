package com.bertonibr.pdfstudio.model;

import javafx.scene.paint.Color;

import java.util.List;

/**
 * Anotações sobrepostas a uma página. As coordenadas estão em pontos (1/72")
 * no sistema de exibição da página (origem no canto superior esquerdo, já
 * considerando a rotação da página), ou seja, iguais aos pixels da página
 * renderizada com zoom 100%.
 */
public sealed interface Annotation {

    /** Texto livre. (x, y) é o canto superior esquerdo da primeira linha. */
    record Text(double x, double y, String text, double size, Color color) implements Annotation {}

    /** Marca-texto semitransparente. */
    record Highlight(double x, double y, double w, double h, Color color) implements Annotation {}

    /** Desenho à mão livre. Cada ponto é {x, y}. */
    record Ink(List<double[]> points, Color color, double width) implements Annotation {}

    /** Retângulo opaco (corretivo) - usado para apagar/editar conteúdo. */
    record Whiteout(double x, double y, double w, double h, Color color) implements Annotation {}

    /** Imagem (assinatura, carimbo). png contém a imagem codificada. */
    record Image(byte[] png, double x, double y, double w, double h) implements Annotation {}
}
