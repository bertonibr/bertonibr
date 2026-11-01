package com.bertonibr.pdfstudio.model;

import javafx.scene.paint.Color;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Grava (achata) as anotações no conteúdo das páginas do PDF. */
public final class Flattener {

    private static final String[] FONT_CANDIDATES = {
            "C:/Windows/Fonts/arial.ttf",
            "/System/Library/Fonts/Supplemental/Arial.ttf",
            "/Library/Fonts/Arial.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    };

    private Flattener() {}

    public static PDFont loadFont(PDDocument doc) {
        for (String path : FONT_CANDIDATES) {
            File f = new File(path);
            if (f.isFile()) {
                try {
                    return PDType0Font.load(doc, f);
                } catch (IOException ignored) {
                    // tenta a próxima
                }
            }
        }
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    /** Matriz que leva coordenadas de exibição (y para baixo) ao espaço do PDF. */
    static Matrix displayToUser(PDPage page) {
        PDRectangle crop = page.getCropBox();
        float llx = crop.getLowerLeftX(), lly = crop.getLowerLeftY();
        float w = crop.getWidth(), h = crop.getHeight();
        int rot = ((page.getRotation() % 360) + 360) % 360;
        return switch (rot) {
            case 90 -> new Matrix(0, 1, 1, 0, llx, lly);
            case 180 -> new Matrix(-1, 0, 0, 1, llx + w, lly);
            case 270 -> new Matrix(0, -1, -1, 0, llx + w, lly + h);
            default -> new Matrix(1, 0, 0, -1, llx, lly + h);
        };
    }

    public static void flatten(PDDocument doc, PDPage page, List<Annotation> list, PDFont font) throws IOException {
        if (list == null || list.isEmpty()) return;
        try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {
            cs.transform(displayToUser(page));
            for (Annotation a : list) {
                switch (a) {
                    case Annotation.Text t -> drawText(cs, t, font);
                    case Annotation.Highlight hl -> {
                        cs.saveGraphicsState();
                        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                        gs.setNonStrokingAlphaConstant(0.35f);
                        gs.setBlendMode(org.apache.pdfbox.pdmodel.graphics.blend.BlendMode.MULTIPLY);
                        cs.setGraphicsStateParameters(gs);
                        fill(cs, hl.color());
                        cs.addRect((float) hl.x(), (float) hl.y(), (float) hl.w(), (float) hl.h());
                        cs.fill();
                        cs.restoreGraphicsState();
                    }
                    case Annotation.Whiteout wo -> {
                        fill(cs, wo.color());
                        cs.addRect((float) wo.x(), (float) wo.y(), (float) wo.w(), (float) wo.h());
                        cs.fill();
                    }
                    case Annotation.Ink ink -> {
                        if (ink.points().size() < 2) continue;
                        cs.setStrokingColor((float) ink.color().getRed(), (float) ink.color().getGreen(),
                                (float) ink.color().getBlue());
                        cs.setLineWidth((float) ink.width());
                        cs.setLineCapStyle(1);
                        cs.setLineJoinStyle(1);
                        double[] p0 = ink.points().get(0);
                        cs.moveTo((float) p0[0], (float) p0[1]);
                        for (int i = 1; i < ink.points().size(); i++) {
                            double[] p = ink.points().get(i);
                            cs.lineTo((float) p[0], (float) p[1]);
                        }
                        cs.stroke();
                    }
                    case Annotation.Image im -> {
                        PDImageXObject img = PDImageXObject.createFromByteArray(doc, im.png(), "imagem");
                        cs.drawImage(img, new Matrix((float) im.w(), 0, 0, (float) -im.h(),
                                (float) im.x(), (float) (im.y() + im.h())));
                    }
                }
            }
        }
    }

    private static void fill(PDPageContentStream cs, Color c) throws IOException {
        cs.setNonStrokingColor((float) c.getRed(), (float) c.getGreen(), (float) c.getBlue());
    }

    private static void drawText(PDPageContentStream cs, Annotation.Text t, PDFont font) throws IOException {
        float size = (float) t.size();
        fill(cs, t.color());
        cs.beginText();
        cs.setFont(font, size);
        String[] lines = t.text().split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            float baseline = (float) (t.y() + size + i * size * 1.2);
            cs.setTextMatrix(new Matrix(1, 0, 0, -1, (float) t.x(), baseline));
            cs.showText(sanitize(lines[i], font));
        }
        cs.endText();
    }

    /** Troca por '?' os caracteres que a fonte não consegue codificar. */
    static String sanitize(String s, PDFont font) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            if (cp == '\t') ch = "    ";
            try {
                font.encode(ch);
                sb.append(ch);
            } catch (IOException | IllegalArgumentException e) {
                sb.append('?');
            }
        });
        return sb.toString();
    }
}
