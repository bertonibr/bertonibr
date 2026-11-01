package com.bertonibr.pdfstudio.model;

import javafx.scene.paint.Color;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfDocumentModelTest {

    @TempDir Path tmp;

    private File sample(int pages) throws Exception {
        File f = tmp.resolve("sample" + pages + ".pdf").toFile();
        try (PDDocument d = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage p = new PDPage(PDRectangle.A4);
                d.addPage(p);
                try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Pagina " + i + " conteudo original");
                    cs.endText();
                }
            }
            d.save(f);
        }
        return f;
    }

    private static String text(File f) throws Exception {
        try (PDDocument d = Loader.loadPDF(f)) {
            return new PDFTextStripper().getText(d);
        }
    }

    @Test
    void annotationsAreFlattenedOnSave() throws Exception {
        File out = tmp.resolve("out.pdf").toFile();
        try (PdfDocumentModel m = PdfDocumentModel.open(sample(1))) {
            m.addAnnotation(0, new Annotation.Text(72, 100, "Olá, ação!\nSegunda linha", 12, Color.RED));
            m.addAnnotation(0, new Annotation.Highlight(70, 130, 200, 20, Color.YELLOW));
            m.addAnnotation(0, new Annotation.Ink(List.of(new double[]{10, 10}, new double[]{50, 50}), Color.BLUE, 2));
            m.addAnnotation(0, new Annotation.Whiteout(300, 300, 50, 50, Color.WHITE));
            m.save(out);
        }
        String t = text(out);
        assertTrue(t.contains("Olá, ação!"), t);
        assertTrue(t.contains("Segunda linha"), t);
        assertTrue(t.contains("Pagina 1"), t);
    }

    @Test
    void textPositionMatchesDisplayCoordinatesOnRotatedPage() throws Exception {
        File out = tmp.resolve("rot.pdf").toFile();
        try (PdfDocumentModel m = PdfDocumentModel.open(sample(1))) {
            m.rotate(0, 90);
            double[] size = m.displaySize(0);
            assertEquals(PDRectangle.A4.getHeight(), size[0], 0.1);
            m.addAnnotation(0, new Annotation.Text(100, 50, "MARCA", 12, Color.BLACK));
            m.save(out);
            try (PdfDocumentModel r = PdfDocumentModel.open(out)) {
                assertEquals("MARCA", r.textInArea(0, 90, 40, 80, 40));
            }
        }
    }

    @Test
    void pageOperations() throws Exception {
        try (PdfDocumentModel m = PdfDocumentModel.open(sample(3))) {
            m.appendPdf(sample(2));
            assertEquals(5, m.pageCount());
            m.movePage(0, 2);
            assertTrue(m.extractText(2, 2).contains("Pagina 1"));
            m.deletePage(4);
            assertEquals(4, m.pageCount());
            File part = tmp.resolve("part.pdf").toFile();
            m.extractPages(List.of(0, 2), part);
            try (PDDocument d = Loader.loadPDF(part)) {
                assertEquals(2, d.getNumberOfPages());
            }
            assertEquals(List.of(2, 3), m.search("pagina 1 "));
            assertEquals(4, m.splitEachPage(tmp.toFile(), "split"));
            assertEquals(1, m.exportImages(List.of(0), tmp.toFile(), "img", 72));
        }
    }

    @Test
    void imageAnnotationAndImagesToPdf() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(40, 20, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        File png = tmp.resolve("sig.png").toFile();
        javax.imageio.ImageIO.write(img, "png", png);
        byte[] bytes = java.nio.file.Files.readAllBytes(png.toPath());
        try (PdfDocumentModel m = PdfDocumentModel.fromImages(List.of(png, png))) {
            assertEquals(2, m.pageCount());
            m.addAnnotation(1, new Annotation.Image(bytes, 10, 10, 80, 40));
            assertTrue(m.toBytes(true).length > 0);
        }
    }
}
