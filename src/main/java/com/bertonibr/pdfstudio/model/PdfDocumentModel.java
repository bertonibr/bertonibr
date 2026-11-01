package com.bertonibr.pdfstudio.model;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;

import javax.imageio.ImageIO;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Documento PDF aberto + anotações pendentes. Não é thread-safe: todo acesso
 * deve ocorrer na mesma thread (a UI usa um executor de thread única).
 */
public final class PdfDocumentModel implements Closeable {

    private final PDDocument doc;
    private final PDFRenderer renderer;
    /** Chave: dicionário COS da página (getPage() cria um wrapper novo a cada chamada). */
    private final Map<COSDictionary, List<Annotation>> annotations = new IdentityHashMap<>();
    private File file;
    private boolean dirty;

    private PdfDocumentModel(PDDocument doc, File file) {
        this.doc = doc;
        this.renderer = new PDFRenderer(doc);
        this.file = file;
    }

    public static PdfDocumentModel open(File f) throws IOException {
        return open(f, null);
    }

    /**
     * Abre o PDF (em memória, para permitir salvar por cima do mesmo arquivo).
     * Lança {@link org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException}
     * se o arquivo exigir senha e ela estiver ausente ou errada.
     */
    public static PdfDocumentModel open(File f, String password) throws IOException {
        byte[] bytes = Files.readAllBytes(f.toPath());
        PDDocument doc = password == null || password.isEmpty()
                ? Loader.loadPDF(bytes) : Loader.loadPDF(bytes, password);
        // PDFs protegidos não podem ser regravados com a proteção original sem a
        // senha do proprietário; a proteção é removida ao salvar.
        if (doc.isEncrypted()) doc.setAllSecurityToBeRemoved(true);
        return new PdfDocumentModel(doc, f);
    }

    public static PdfDocumentModel fromImages(List<File> images) throws IOException {
        PDDocument doc = new PDDocument();
        for (File img : images) {
            PDImageXObject x = PDImageXObject.createFromFileByContent(img, doc);
            boolean landscape = x.getWidth() > x.getHeight();
            PDRectangle a4 = landscape ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth())
                    : PDRectangle.A4;
            PDPage page = new PDPage(a4);
            doc.addPage(page);
            float margin = 24;
            float maxW = a4.getWidth() - 2 * margin, maxH = a4.getHeight() - 2 * margin;
            float scale = Math.min(maxW / x.getWidth(), maxH / x.getHeight());
            float w = x.getWidth() * scale, h = x.getHeight() * scale;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(x, (a4.getWidth() - w) / 2, (a4.getHeight() - h) / 2, w, h);
            }
        }
        PdfDocumentModel m = new PdfDocumentModel(doc, null);
        m.dirty = true;
        return m;
    }

    public File file() { return file; }
    public boolean isDirty() { return dirty; }
    public int pageCount() { return doc.getNumberOfPages(); }

    /** Tamanho da página em pontos, já considerando a rotação: {largura, altura}. */
    public double[] displaySize(int i) {
        PDPage p = doc.getPage(i);
        PDRectangle r = p.getCropBox();
        int rot = ((p.getRotation() % 360) + 360) % 360;
        return (rot == 90 || rot == 270) ? new double[]{r.getHeight(), r.getWidth()}
                : new double[]{r.getWidth(), r.getHeight()};
    }

    public BufferedImage render(int i, float scale) throws IOException {
        return renderer.renderImage(i, scale, ImageType.RGB);
    }

    // ---------------------------------------------------------------- anotações

    private COSDictionary key(int i) {
        return doc.getPage(i).getCOSObject();
    }

    public List<Annotation> annotations(int i) {
        return List.copyOf(annotations.getOrDefault(key(i), List.of()));
    }

    public void addAnnotation(int i, Annotation a) {
        annotations.computeIfAbsent(key(i), k -> new ArrayList<>()).add(a);
        dirty = true;
    }

    public boolean undoAnnotation(int i) {
        List<Annotation> l = annotations.get(key(i));
        if (l == null || l.isEmpty()) return false;
        l.remove(l.size() - 1);
        return true;
    }

    public void clearAnnotations(int i) {
        annotations.remove(key(i));
    }

    /** Grava as anotações da página no conteúdo (deixam de ser editáveis). */
    private void bake(int i) throws IOException {
        PDPage p = doc.getPage(i);
        List<Annotation> l = annotations.remove(p.getCOSObject());
        if (l != null && !l.isEmpty()) Flattener.flatten(doc, p, l, Flattener.loadFont(doc));
    }

    // ---------------------------------------------------------------- páginas

    public void rotate(int i, int delta) throws IOException {
        bake(i);
        PDPage p = doc.getPage(i);
        p.setRotation(((p.getRotation() + delta) % 360 + 360) % 360);
        dirty = true;
    }

    public void deletePage(int i) {
        if (pageCount() <= 1) throw new IllegalStateException("O documento precisa ter ao menos uma página.");
        annotations.remove(key(i));
        doc.removePage(i);
        dirty = true;
    }

    /** Move a página "from" para a posição "to" (índices 0-based). */
    public void movePage(int from, int to) {
        if (from == to || to < 0 || to >= pageCount()) return;
        PDPageTree pages = doc.getPages();
        PDPage p = pages.get(from);
        pages.remove(from);
        if (to >= pages.getCount()) pages.add(p);
        else pages.insertBefore(p, pages.get(to));
        dirty = true;
    }

    public void appendPdf(File other) throws IOException {
        try (PDDocument src = Loader.loadPDF(Files.readAllBytes(other.toPath()))) {
            new PDFMergerUtility().appendDocument(doc, src);
        }
        dirty = true;
    }

    public void appendImages(List<File> images) throws IOException {
        try (PdfDocumentModel tmp = fromImages(images);
             PDDocument src = Loader.loadPDF(tmp.toBytes(false))) {
            new PDFMergerUtility().appendDocument(doc, src);
        }
        dirty = true;
    }

    // ---------------------------------------------------------------- saída

    /** Bytes do documento; com anotações, elas são gravadas numa cópia. */
    public byte[] toBytes(boolean withAnnotations) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.save(bos);
        if (!withAnnotations || annotations.values().stream().allMatch(List::isEmpty)) return bos.toByteArray();
        try (PDDocument copy = Loader.loadPDF(bos.toByteArray())) {
            PDFont font = Flattener.loadFont(copy);
            for (int i = 0; i < pageCount(); i++) {
                Flattener.flatten(copy, copy.getPage(i), annotations.get(key(i)), font);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy.save(out);
            return out.toByteArray();
        }
    }

    public void save(File target) throws IOException {
        Files.write(target.toPath(), toBytes(true));
        file = target;
        dirty = false;
    }

    /** PDF (com anotações) contendo apenas as páginas indicadas (0-based). */
    public byte[] pagesToBytes(Collection<Integer> pageIdx) throws IOException {
        try (PDDocument copy = Loader.loadPDF(toBytes(true))) {
            TreeSet<Integer> keep = new TreeSet<>(pageIdx);
            for (int i = copy.getNumberOfPages() - 1; i >= 0; i--) {
                if (!keep.contains(i)) copy.removePage(i);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy.save(out);
            return out.toByteArray();
        }
    }

    /** Salva apenas as páginas indicadas (0-based) em um novo PDF. */
    public void extractPages(Collection<Integer> pageIdx, File target) throws IOException {
        Files.write(target.toPath(), pagesToBytes(pageIdx));
    }

    /** Divide: um PDF por página, na pasta indicada. */
    public int splitEachPage(File dir, String baseName) throws IOException {
        int n = pageCount();
        for (int i = 0; i < n; i++) {
            extractPages(List.of(i), new File(dir, String.format("%s_p%03d.pdf", baseName, i + 1)));
        }
        return n;
    }

    public int exportImages(Collection<Integer> pageIdx, File dir, String baseName, float dpi) throws IOException {
        try (PDDocument copy = Loader.loadPDF(toBytes(true))) {
            PDFRenderer r = new PDFRenderer(copy);
            int count = 0;
            for (int i : new TreeSet<>(pageIdx)) {
                BufferedImage img = r.renderImageWithDPI(i, dpi, ImageType.RGB);
                ImageIO.write(img, "png", new File(dir, String.format("%s_p%03d.png", baseName, i + 1)));
                count++;
            }
            return count;
        }
    }

    // ---------------------------------------------------------------- texto

    public String extractText(int fromPage, int toPage) throws IOException {
        PDFTextStripper s = new PDFTextStripper();
        s.setSortByPosition(true);
        s.setStartPage(fromPage + 1);
        s.setEndPage(toPage + 1);
        return s.getText(doc);
    }

    public String allText() throws IOException {
        return extractText(0, pageCount() - 1);
    }

    /** Texto dentro de um retângulo (coordenadas de exibição, em pontos). */
    public String textInArea(int i, double x, double y, double w, double h) throws IOException {
        PDFTextStripperByArea s = new PDFTextStripperByArea();
        s.setSortByPosition(true);
        s.addRegion("r", new Rectangle2D.Double(x, y, w, h));
        s.extractRegions(doc.getPage(i));
        return s.getTextForRegion("r").strip();
    }

    /** Páginas (0-based) que contêm o termo, sem diferenciar maiúsculas. */
    public List<Integer> search(String term) throws IOException {
        String q = term.toLowerCase(Locale.ROOT);
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < pageCount(); i++) {
            if (extractText(i, i).toLowerCase(Locale.ROOT).contains(q)) hits.add(i);
        }
        return hits;
    }

    @Override
    public void close() throws IOException {
        doc.close();
    }
}
