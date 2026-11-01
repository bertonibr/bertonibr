package com.bertonibr.pdfstudio.ui;

import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Optional;

/** Diálogo para desenhar (ou carregar) uma assinatura. Devolve um PNG com fundo transparente. */
final class SignatureDialog {

    private SignatureDialog() {}

    static Optional<byte[]> show(Window owner) {
        Dialog<byte[]> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Assinatura");
        dialog.setHeaderText("Desenhe sua assinatura com o mouse (ou carregue uma imagem).");

        Canvas canvas = new Canvas(520, 200);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setStroke(Color.web("#0b2a6f"));
        g.setLineWidth(2.6);
        g.setLineCap(StrokeLineCap.ROUND);
        boolean[] drawn = {false};
        byte[][] loaded = {null};
        Label info = new Label();

        canvas.setOnMousePressed(e -> {
            g.beginPath();
            g.moveTo(e.getX(), e.getY());
            g.stroke();
            drawn[0] = true;
            loaded[0] = null;
            info.setText("");
        });
        canvas.setOnMouseDragged(e -> {
            g.lineTo(e.getX(), e.getY());
            g.stroke();
        });

        StackPane pad = new StackPane(canvas);
        pad.setStyle("-fx-background-color: white; -fx-border-color: #999; -fx-border-style: dashed;");

        Button clear = new Button("Limpar");
        clear.setOnAction(e -> {
            g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
            drawn[0] = false;
            loaded[0] = null;
            info.setText("");
        });
        Button load = new Button("Carregar imagem...");
        load.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imagens", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
            File f = fc.showOpenDialog(owner);
            if (f == null) return;
            try {
                BufferedImage img = ImageIO.read(f);
                if (img == null) throw new IOException("Formato não suportado.");
                loaded[0] = toPng(img);
                info.setText("Imagem carregada: " + f.getName());
            } catch (IOException ex) {
                info.setText("Erro: " + ex.getMessage());
            }
        });

        VBox box = new VBox(10, pad, new HBox(10, clear, load, info));
        box.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            if (loaded[0] != null) return loaded[0];
            if (!drawn[0]) return null;
            SnapshotParameters sp = new SnapshotParameters();
            sp.setFill(Color.TRANSPARENT);
            WritableImage snap = canvas.snapshot(sp, null);
            BufferedImage img = crop(SwingFXUtils.fromFXImage(snap, null));
            try {
                return img == null ? null : toPng(img);
            } catch (IOException ex) {
                return null;
            }
        });
        return dialog.showAndWait();
    }

    /** Recorta a área desenhada (pixels não transparentes) com pequena margem. */
    private static BufferedImage crop(BufferedImage img) {
        int minX = img.getWidth(), minY = img.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) > 10) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) return null;
        int m = 4;
        minX = Math.max(0, minX - m);
        minY = Math.max(0, minY - m);
        maxX = Math.min(img.getWidth() - 1, maxX + m);
        maxY = Math.min(img.getHeight() - 1, maxY + m);
        return img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    static byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }
}
