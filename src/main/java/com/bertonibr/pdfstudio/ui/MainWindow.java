package com.bertonibr.pdfstudio.ui;

import com.bertonibr.pdfstudio.model.Annotation;
import com.bertonibr.pdfstudio.model.PdfDocumentModel;
import com.bertonibr.pdfstudio.service.ClaudeService;
import com.bertonibr.pdfstudio.service.SpeechService;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Janela principal do PDF Studio. */
public final class MainWindow {

    private enum Tool { SELECT, TEXT, HIGHLIGHT, INK, WHITEOUT, EDIT_TEXT, SIGN }

    private record Rendered(Image image, double w, double h, List<Annotation> annotations) {}

    private final Stage stage;
    /** Todo acesso ao PDFBox acontece nesta única thread. */
    private final ExecutorService pdfExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pdf-worker");
        t.setDaemon(true);
        return t;
    });
    private final SpeechService speech = new SpeechService();

    private PdfDocumentModel model;
    private int pageCount;
    private int current;
    private double zoom = 1.0;
    private long renderGen;
    private long docGen;
    private Rendered rendered;

    // UI
    private final ImageView pageView = new ImageView();
    private final Canvas overlay = new Canvas();
    private final ScrollPane scroll = new ScrollPane();
    private final ListView<Integer> thumbs = new ListView<>();
    private final Map<Integer, Image> thumbCache = new HashMap<>();
    private final Label status = new Label("Abra um PDF (Ctrl+O) ou arraste um arquivo para a janela.");
    private final TextField pageField = new TextField();
    private final Label pageTotal = new Label("/ 0");
    private final Label zoomLabel = new Label("100%");
    private final ToggleGroup toolGroup = new ToggleGroup();
    private final ColorPicker colorPicker = new ColorPicker(Color.web("#d32f2f"));
    private final Spinner<Integer> sizeSpinner = new Spinner<>(6, 96, 14);
    private final TextArea aiOutput = new TextArea();
    private final TextField aiQuestion = new TextField();
    private final TextArea textOutput = new TextArea();
    private final List<Node> needsDoc = new ArrayList<>();
    private final List<MenuItem> needsDocMenu = new ArrayList<>();
    private final List<Node> aiControls = new ArrayList<>();
    private final Map<byte[], Image> imageCache = new IdentityHashMap<>();

    // estado do desenho em andamento
    private double dragX0, dragY0, dragX1, dragY1;
    private boolean dragging;
    private List<double[]> inkPoints;
    private byte[] signaturePng;

    public MainWindow(Stage stage) {
        this.stage = stage;
        BorderPane root = new BorderPane();
        root.setTop(new VBox(buildMenu(), buildToolbar()));
        root.setCenter(buildCenter());
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1280, 820);
        scene.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles()) {
                File f = db.getFiles().get(0);
                if (f.getName().toLowerCase().endsWith(".pdf")) open(f);
            }
            e.setDropCompleted(true);
            e.consume();
        });
        stage.setScene(scene);
        stage.setTitle("PDF Studio");
        stage.setOnCloseRequest(e -> {
            if (!confirmDiscard()) e.consume();
            else {
                speech.stop();
                Platform.exit();
            }
        });
        updateEnabled();
    }

    public void show() {
        stage.show();
    }

    // =================================================================== layout

    private MenuBar buildMenu() {
        Menu file = new Menu("_Arquivo");
        file.getItems().addAll(
                item("Abrir...", "Shortcut+O", false, this::chooseAndOpen),
                item("Criar PDF a partir de imagens...", null, false, this::createFromImages),
                new SeparatorMenuItem(),
                item("Salvar", "Shortcut+S", true, this::save),
                item("Salvar como...", "Shortcut+Shift+S", true, this::saveAs),
                new SeparatorMenuItem(),
                item("Fechar documento", "Shortcut+W", true, this::closeDocument),
                item("Sair", "Shortcut+Q", false, () -> stage.fireEvent(
                        new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST))));

        Menu view = new Menu("_Exibir");
        view.getItems().addAll(
                item("Ampliar", "Shortcut+Equals", true, () -> setZoom(zoom * 1.2)),
                item("Reduzir", "Shortcut+Minus", true, () -> setZoom(zoom / 1.2)),
                item("Tamanho real (100%)", "Shortcut+0", true, () -> setZoom(1.0)),
                item("Ajustar à largura", "Shortcut+1", true, this::fitWidth),
                new SeparatorMenuItem(),
                item("Página anterior", "Shortcut+Left", true, () -> goTo(current - 1)),
                item("Próxima página", "Shortcut+Right", true, () -> goTo(current + 1)),
                item("Localizar...", "Shortcut+F", true, this::find));

        Menu pages = new Menu("_Páginas");
        pages.getItems().addAll(
                item("Girar à esquerda", "Shortcut+L", true, () -> rotate(-90)),
                item("Girar à direita", "Shortcut+R", true, () -> rotate(90)),
                item("Excluir página", null, true, this::deletePage),
                item("Mover página para cima", null, true, () -> movePage(current, current - 1)),
                item("Mover página para baixo", null, true, () -> movePage(current, current + 1)),
                new SeparatorMenuItem(),
                item("Inserir PDF no final (juntar)...", null, true, this::appendPdf),
                item("Inserir imagens no final...", null, true, this::appendImages),
                new SeparatorMenuItem(),
                item("Extrair páginas para novo PDF...", null, true, this::extractPages),
                item("Dividir em um PDF por página...", null, true, this::splitPages));

        Menu export = new Menu("E_xportar");
        export.getItems().addAll(
                item("Texto do documento (.txt)...", null, true, this::exportText),
                item("Página atual como imagem (PNG)...", null, true, () -> exportImages(List.of(current))),
                item("Todas as páginas como imagens (PNG)...", null, true,
                        () -> exportImages(IntStream.range(0, pageCount).boxed().toList())));

        Menu tools = new Menu("_Ferramentas");
        tools.getItems().addAll(
                item("Desfazer última anotação", "Shortcut+Z", true, this::undo),
                item("Limpar anotações da página", null, true, this::clearPageAnnotations),
                item("Criar/alterar assinatura...", null, false, this::newSignature),
                new SeparatorMenuItem(),
                item("Ler página em voz alta", "Shortcut+Shift+R", true, () -> readAloud(false)),
                item("Ler documento em voz alta", null, true, () -> readAloud(true)),
                item("Parar leitura", "Escape", false, speech::stop));

        Menu ai = new Menu("_Claude");
        ai.getItems().addAll(
                item("Resumir documento", null, true, () -> askClaude(false, PROMPT_SUMMARY_DOC)),
                item("Resumir página atual", null, true, () -> askClaude(true, PROMPT_SUMMARY_PAGE)),
                item("Perguntar sobre o documento...", null, true, () -> aiQuestion.requestFocus()),
                new SeparatorMenuItem(),
                item("Configurar chave de API...", null, false, this::configureApiKey));

        Menu help = new Menu("Aj_uda");
        help.getItems().add(item("Sobre", null, false, () -> info("PDF Studio",
                "Leitor e editor de PDF em JavaFX + Apache PDFBox.\nResumo e perguntas com Claude ("
                        + ClaudeService.MODEL + ").")));

        return new MenuBar(file, view, pages, export, tools, ai, help);
    }

    private MenuItem item(String text, String accel, boolean requiresDoc, Runnable action) {
        MenuItem mi = new MenuItem(text);
        if (accel != null) mi.setAccelerator(KeyCombination.keyCombination(accel));
        mi.setOnAction(e -> action.run());
        if (requiresDoc) needsDocMenu.add(mi);
        return mi;
    }

    private Button button(String text, String tip, boolean requiresDoc, Runnable action) {
        Button b = new Button(text);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        b.setFocusTraversable(false);
        if (requiresDoc) needsDoc.add(b);
        return b;
    }

    private ToggleButton toolButton(String text, String tip, Tool tool) {
        ToggleButton b = new ToggleButton(text);
        b.setUserData(tool);
        b.setToggleGroup(toolGroup);
        b.setTooltip(new Tooltip(tip));
        b.setFocusTraversable(false);
        needsDoc.add(b);
        return b;
    }

    private Node buildToolbar() {
        pageField.setPrefColumnCount(3);
        pageField.setAlignment(Pos.CENTER_RIGHT);
        pageField.setOnAction(e -> {
            try {
                goTo(Integer.parseInt(pageField.getText().trim()) - 1);
            } catch (NumberFormatException ex) {
                pageField.setText(String.valueOf(current + 1));
            }
        });
        needsDoc.add(pageField);
        zoomLabel.setMinWidth(45);
        zoomLabel.setAlignment(Pos.CENTER);

        ToggleButton select = toolButton("🖐 Navegar", "Apenas visualizar", Tool.SELECT);
        select.setSelected(true);
        toolGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n == null) o.setSelected(true); // sempre uma ferramenta ativa
            overlay.setCursor(currentTool() == Tool.SELECT ? javafx.scene.Cursor.DEFAULT
                    : javafx.scene.Cursor.CROSSHAIR);
        });

        colorPicker.setTooltip(new Tooltip("Cor do texto/desenho/destaque"));
        colorPicker.setPrefWidth(110);
        sizeSpinner.setPrefWidth(70);
        sizeSpinner.setEditable(true);
        sizeSpinner.setTooltip(new Tooltip("Tamanho da fonte / espessura"));
        needsDoc.add(colorPicker);
        needsDoc.add(sizeSpinner);

        ToolBar tb = new ToolBar(
                button("📂 Abrir", "Abrir PDF (Ctrl+O)", false, this::chooseAndOpen),
                button("💾 Salvar", "Salvar (Ctrl+S)", true, this::save),
                new Separator(),
                button("◀", "Página anterior", true, () -> goTo(current - 1)),
                pageField, pageTotal,
                button("▶", "Próxima página", true, () -> goTo(current + 1)),
                new Separator(),
                button("−", "Reduzir zoom", true, () -> setZoom(zoom / 1.2)),
                zoomLabel,
                button("+", "Ampliar zoom", true, () -> setZoom(zoom * 1.2)),
                button("↔", "Ajustar à largura", true, this::fitWidth),
                new Separator(),
                button("⟲", "Girar à esquerda", true, () -> rotate(-90)),
                button("⟳", "Girar à direita", true, () -> rotate(90)),
                new Separator(),
                button("🔊 Ler", "Ler página em voz alta", true, () -> readAloud(false)),
                button("⏹", "Parar leitura (Esc)", false, speech::stop));
        ToolBar edit = new ToolBar(
                new Label("Ferramentas:"),
                select,
                toolButton("T Texto", "Clique na página para inserir texto", Tool.TEXT),
                toolButton("🖍 Destacar", "Arraste para marcar uma área", Tool.HIGHLIGHT),
                toolButton("✏ Desenhar", "Desenho à mão livre", Tool.INK),
                toolButton("▭ Apagar", "Cobre uma área com um retângulo branco", Tool.WHITEOUT),
                toolButton("✎ Editar texto", "Selecione um trecho de texto do PDF para reescrevê-lo", Tool.EDIT_TEXT),
                toolButton("✍ Assinar", "Clique para posicionar sua assinatura", Tool.SIGN),
                new Separator(),
                new Label("Cor:"), colorPicker,
                new Label("Tamanho:"), sizeSpinner,
                button("↶ Desfazer", "Desfazer anotação (Ctrl+Z)", true, this::undo));
        return new VBox(tb, edit);
    }

    private Node buildCenter() {
        // miniaturas
        thumbs.setPrefWidth(170);
        thumbs.setCellFactory(lv -> new ThumbCell());
        thumbs.getSelectionModel().selectedIndexProperty().addListener((obs, o, n) -> {
            if (n != null && n.intValue() >= 0 && n.intValue() != current) goTo(n.intValue());
        });
        thumbs.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) deletePage();
        });

        // página + camada de anotações
        StackPane pageStack = new StackPane(pageView, overlay);
        pageStack.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 12, 0, 0, 2);");
        StackPane center = new StackPane(pageStack);
        center.setPadding(new Insets(20));
        center.setStyle("-fx-background-color: #5f6368;");
        center.minWidthProperty().bind(scroll.widthProperty().subtract(2));
        center.minHeightProperty().bind(scroll.heightProperty().subtract(2));
        scroll.setContent(center);
        scroll.setPannable(false);
        scroll.setStyle("-fx-background: #5f6368;");
        scroll.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (e.isShortcutDown() && model != null) {
                setZoom(e.getDeltaY() > 0 ? zoom * 1.1 : zoom / 1.1);
                e.consume();
            }
        });
        scroll.setOnKeyPressed(e -> {
            if (model == null) return;
            if (e.getCode() == KeyCode.PAGE_DOWN) goTo(current + 1);
            else if (e.getCode() == KeyCode.PAGE_UP) goTo(current - 1);
            else if (e.getCode() == KeyCode.HOME) goTo(0);
            else if (e.getCode() == KeyCode.END) goTo(pageCount - 1);
        });

        overlay.setOnMousePressed(this::onPress);
        overlay.setOnMouseDragged(this::onDrag);
        overlay.setOnMouseReleased(this::onRelease);

        SplitPane split = new SplitPane(thumbs, scroll, buildSidePanel());
        split.setDividerPositions(0.13, 0.72);
        SplitPane.setResizableWithParent(thumbs, false);
        return split;
    }

    private Node buildSidePanel() {
        aiOutput.setWrapText(true);
        aiOutput.setEditable(false);
        aiOutput.setPromptText("O resumo e as respostas do Claude aparecem aqui.");
        aiOutput.setFont(Font.font(13));
        VBox.setVgrow(aiOutput, Priority.ALWAYS);

        Button sumDoc = new Button("Resumir documento");
        sumDoc.setOnAction(e -> askClaude(false, PROMPT_SUMMARY_DOC));
        Button sumPage = new Button("Resumir página");
        sumPage.setOnAction(e -> askClaude(true, PROMPT_SUMMARY_PAGE));
        aiQuestion.setPromptText("Pergunte algo sobre o PDF e tecle Enter");
        Button askBtn = new Button("Perguntar");
        Runnable ask = () -> {
            String q = aiQuestion.getText().trim();
            if (!q.isEmpty()) askClaude(false, q);
        };
        aiQuestion.setOnAction(e -> ask.run());
        askBtn.setOnAction(e -> ask.run());
        HBox.setHgrow(aiQuestion, Priority.ALWAYS);
        Button readAi = new Button("🔊 Ouvir resposta");
        readAi.setOnAction(e -> speakText(aiOutput.getText()));
        Button copyAi = new Button("Copiar");
        copyAi.setOnAction(e -> copy(aiOutput.getText()));
        Button key = new Button("Chave de API...");
        key.setOnAction(e -> configureApiKey());
        aiControls.addAll(List.of(sumDoc, sumPage, aiQuestion, askBtn));
        needsDoc.addAll(aiControls);

        VBox aiBox = new VBox(8,
                new HBox(6, sumDoc, sumPage),
                new HBox(6, aiQuestion, askBtn),
                aiOutput,
                new HBox(6, readAi, copyAi, key));
        aiBox.setPadding(new Insets(8));

        textOutput.setWrapText(true);
        textOutput.setEditable(false);
        textOutput.setPromptText("Texto extraído do PDF.");
        VBox.setVgrow(textOutput, Priority.ALWAYS);
        Button pageText = button("Texto da página", "Extrair texto da página atual", true, () ->
                runPdf("Extraindo texto...", () -> model.extractText(current, current), textOutput::setText));
        Button docText = button("Texto do documento", "Extrair texto de todas as páginas", true, () ->
                runPdf("Extraindo texto...", () -> model.allText(), textOutput::setText));
        Button readText = new Button("🔊 Ler");
        readText.setOnAction(e -> speakText(textOutput.getText()));
        Button copyText = new Button("Copiar");
        copyText.setOnAction(e -> copy(textOutput.getText()));
        VBox textBox = new VBox(8, new HBox(6, pageText, docText), textOutput, new HBox(6, readText, copyText));
        textBox.setPadding(new Insets(8));

        Tab aiTab = new Tab("✦ Claude", aiBox);
        Tab textTab = new Tab("Texto", textBox);
        TabPane tabs = new TabPane(aiTab, textTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setMinWidth(260);
        return tabs;
    }

    private Node buildStatusBar() {
        HBox bar = new HBox(status);
        bar.setPadding(new Insets(4, 8, 4, 8));
        bar.setStyle("-fx-background-color: #eceff1; -fx-border-color: #cfd8dc; -fx-border-width: 1 0 0 0;");
        return bar;
    }

    private final class ThumbCell extends ListCell<Integer> {
        private final ImageView iv = new ImageView();
        private final Label num = new Label();
        private final VBox box = new VBox(4, iv, num);

        ThumbCell() {
            box.setAlignment(Pos.CENTER);
            iv.setPreserveRatio(true);
            iv.setFitWidth(120);
            setOnDragDetected(e -> {
                if (getItem() == null) return;
                Dragboard db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent cc = new ClipboardContent();
                cc.putString(String.valueOf(getItem()));
                db.setContent(cc);
                e.consume();
            });
            setOnDragOver(e -> {
                if (e.getGestureSource() != this && e.getDragboard().hasString()) e.acceptTransferModes(TransferMode.MOVE);
                e.consume();
            });
            setOnDragDropped(e -> {
                if (getItem() == null) return;
                try {
                    int from = Integer.parseInt(e.getDragboard().getString());
                    movePage(from, getItem());
                    e.setDropCompleted(true);
                } catch (NumberFormatException ex) {
                    e.setDropCompleted(false);
                }
                e.consume();
            });
        }

        @Override
        protected void updateItem(Integer idx, boolean empty) {
            super.updateItem(idx, empty);
            if (empty || idx == null) {
                setGraphic(null);
                return;
            }
            num.setText(String.valueOf(idx + 1));
            Image img = thumbCache.get(idx);
            iv.setImage(img);
            if (img == null) requestThumb(idx);
            setGraphic(box);
        }
    }

    private void requestThumb(int idx) {
        long gen = docGen;
        PdfDocumentModel m = model;
        if (m == null) return;
        pdfExec.submit(() -> {
            if (gen != docGen || idx >= m.pageCount()) return;
            try {
                Image img = SwingFXUtils.toFXImage(m.render(idx, 0.25f), null);
                Platform.runLater(() -> {
                    if (gen != docGen) return;
                    thumbCache.put(idx, img);
                    thumbs.refresh();
                });
            } catch (Exception ignored) {
                // miniatura é opcional
            }
        });
    }

    // =================================================================== documento

    private void chooseAndOpen() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Abrir PDF");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        File f = fc.showOpenDialog(stage);
        if (f != null) open(f);
    }

    public void open(File f) {
        if (!confirmDiscard()) return;
        runPdf("Abrindo " + f.getName() + "...", () -> PdfDocumentModel.open(f), this::setModel);
    }

    private void createFromImages() {
        if (!confirmDiscard()) return;
        List<File> files = chooseImages();
        if (files == null) return;
        runPdf("Criando PDF...", () -> PdfDocumentModel.fromImages(files), this::setModel);
    }

    private List<File> chooseImages() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Escolha as imagens (na ordem desejada)");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imagens", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
        return fc.showOpenMultipleDialog(stage);
    }

    private void setModel(PdfDocumentModel m) {
        PdfDocumentModel old = model;
        if (old != null) pdfExec.submit(() -> {
            try { old.close(); } catch (Exception ignored) { }
        });
        model = m;
        current = 0;
        aiOutput.clear();
        textOutput.clear();
        documentChanged();
        Platform.runLater(this::fitWidth);
    }

    private void closeDocument() {
        if (!confirmDiscard()) return;
        PdfDocumentModel old = model;
        model = null;
        if (old != null) pdfExec.submit(() -> {
            try { old.close(); } catch (Exception ignored) { }
        });
        pageCount = 0;
        rendered = null;
        pageView.setImage(null);
        overlay.setWidth(0);
        overlay.setHeight(0);
        thumbs.getItems().clear();
        thumbCache.clear();
        updateTitle();
        updateEnabled();
        status.setText("Documento fechado.");
    }

    /** Recarrega miniaturas e a página após mudança estrutural. */
    private void documentChanged() {
        runPdf(null, () -> model.pageCount(), n -> {
            pageCount = n;
            docGen++;
            thumbCache.clear();
            current = Math.max(0, Math.min(current, n - 1));
            thumbs.getItems().setAll(IntStream.range(0, n).boxed().toList());
            updateEnabled();
            renderPage();
        });
    }

    private void save() {
        if (model == null) return;
        if (model.file() == null) {
            saveAs();
            return;
        }
        File target = model.file();
        runPdf("Salvando...", () -> {
            model.save(target);
            return null;
        }, v -> {
            updateTitle();
            status.setText("Salvo em " + target.getAbsolutePath());
        });
    }

    private void saveAs() {
        if (model == null) return;
        File target = chooseSave("Salvar como", "pdf", baseName() + ".pdf");
        if (target == null) return;
        runPdf("Salvando...", () -> {
            model.save(target);
            return null;
        }, v -> {
            updateTitle();
            status.setText("Salvo em " + target.getAbsolutePath());
        });
    }

    private boolean confirmDiscard() {
        if (model == null || !model.isDirty()) return true;
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Há alterações não salvas. Deseja descartá-las?", ButtonType.YES, ButtonType.NO);
        a.initOwner(stage);
        a.setHeaderText(null);
        return a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    // =================================================================== navegação

    private void goTo(int page) {
        if (model == null || page < 0 || page >= pageCount) return;
        current = page;
        renderPage();
        scroll.setVvalue(0);
    }

    private void setZoom(double z) {
        if (model == null) return;
        zoom = Math.max(0.1, Math.min(8, z));
        zoomLabel.setText(Math.round(zoom * 100) + "%");
        renderPage();
    }

    private void fitWidth() {
        if (model == null) return;
        runPdf(null, () -> model.displaySize(current), size -> {
            double avail = scroll.getViewportBounds().getWidth() - 50;
            if (avail > 100) setZoom(avail / size[0]);
        });
    }

    private void renderPage() {
        if (model == null) return;
        long gen = ++renderGen;
        int page = current;
        double z = zoom;
        double outScale = stage.getOutputScaleX() > 0 ? stage.getOutputScaleX() : 1.0;
        pageField.setText(String.valueOf(page + 1));
        pageTotal.setText("/ " + pageCount);
        zoomLabel.setText(Math.round(zoom * 100) + "%");
        if (thumbs.getSelectionModel().getSelectedIndex() != page) {
            thumbs.getSelectionModel().select(page);
            thumbs.scrollTo(Math.max(0, page - 1));
        }
        PdfDocumentModel m = model;
        pdfExec.submit(() -> {
            if (gen != renderGen) return; // pedido já superado
            try {
                double[] size = m.displaySize(page);
                BufferedImage img = m.render(page, (float) (z * outScale));
                Rendered r = new Rendered(SwingFXUtils.toFXImage(img, null), size[0], size[1], m.annotations(page));
                Platform.runLater(() -> {
                    if (gen != renderGen) return;
                    rendered = r;
                    pageView.setImage(r.image());
                    pageView.setFitWidth(r.w() * z);
                    pageView.setFitHeight(r.h() * z);
                    overlay.setWidth(r.w() * z);
                    overlay.setHeight(r.h() * z);
                    redrawOverlay();
                    updateTitle();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> error("Erro ao renderizar a página", ex));
            }
        });
    }

    private void refreshAnnotations() {
        int page = current;
        runPdf(null, () -> model.annotations(page), list -> {
            if (rendered != null && page == current) {
                rendered = new Rendered(rendered.image(), rendered.w(), rendered.h(), list);
                redrawOverlay();
            }
            updateTitle();
        });
    }

    private void find() {
        TextInputDialog d = new TextInputDialog();
        d.initOwner(stage);
        d.setTitle("Localizar");
        d.setHeaderText(null);
        d.setContentText("Procurar por:");
        d.showAndWait().map(String::trim).filter(s -> !s.isEmpty()).ifPresent(term ->
                runPdf("Procurando...", () -> model.search(term), hits -> {
                    if (hits.isEmpty()) {
                        status.setText("\"" + term + "\" não encontrado.");
                        return;
                    }
                    int next = hits.stream().filter(p -> p > current).findFirst().orElse(hits.get(0));
                    goTo(next);
                    status.setText("\"" + term + "\" encontrado nas páginas: "
                            + hits.stream().map(p -> String.valueOf(p + 1)).collect(Collectors.joining(", ")));
                }));
    }

    // =================================================================== páginas

    private void rotate(int delta) {
        if (model == null) return;
        int page = current;
        runPdf("Girando...", () -> {
            model.rotate(page, delta);
            return null;
        }, v -> {
            thumbCache.remove(page);
            thumbs.refresh();
            renderPage();
        });
    }

    private void deletePage() {
        if (model == null) return;
        int page = current;
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Excluir a página " + (page + 1) + "?",
                ButtonType.YES, ButtonType.NO);
        a.initOwner(stage);
        a.setHeaderText(null);
        if (a.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        runPdf("Excluindo página...", () -> {
            model.deletePage(page);
            return null;
        }, v -> documentChanged());
    }

    private void movePage(int from, int to) {
        if (model == null || to < 0 || to >= pageCount || from == to) return;
        runPdf(null, () -> {
            model.movePage(from, to);
            return null;
        }, v -> {
            current = to;
            documentChanged();
        });
    }

    private void appendPdf() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Escolha os PDFs para juntar");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        List<File> files = fc.showOpenMultipleDialog(stage);
        if (files == null) return;
        runPdf("Juntando PDFs...", () -> {
            for (File f : files) model.appendPdf(f);
            return null;
        }, v -> {
            documentChanged();
            status.setText(files.size() + " arquivo(s) inserido(s) no final.");
        });
    }

    private void appendImages() {
        List<File> files = chooseImages();
        if (files == null) return;
        runPdf("Inserindo imagens...", () -> {
            model.appendImages(files);
            return null;
        }, v -> documentChanged());
    }

    private void extractPages() {
        TextInputDialog d = new TextInputDialog(String.valueOf(current + 1));
        d.initOwner(stage);
        d.setTitle("Extrair páginas");
        d.setHeaderText("Informe as páginas (ex.: 1-3, 5, 8-10). Total: " + pageCount);
        d.setContentText("Páginas:");
        Optional<String> r = d.showAndWait();
        if (r.isEmpty()) return;
        List<Integer> idx;
        try {
            idx = parseRange(r.get(), pageCount);
        } catch (IllegalArgumentException ex) {
            info("Intervalo inválido", ex.getMessage());
            return;
        }
        File target = chooseSave("Salvar páginas extraídas", "pdf", baseName() + "_extraido.pdf");
        if (target == null) return;
        runPdf("Extraindo páginas...", () -> {
            model.extractPages(idx, target);
            return null;
        }, v -> status.setText(idx.size() + " página(s) salvas em " + target.getAbsolutePath()));
    }

    private void splitPages() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Pasta de destino");
        File dir = dc.showDialog(stage);
        if (dir == null) return;
        runPdf("Dividindo...", () -> model.splitEachPage(dir, baseName()),
                n -> status.setText(n + " arquivos criados em " + dir.getAbsolutePath()));
    }

    static List<Integer> parseRange(String spec, int max) {
        TreeSet<Integer> out = new TreeSet<>();
        for (String part : spec.split("[,;\\s]+")) {
            if (part.isBlank()) continue;
            String[] ab = part.split("-", -1);
            try {
                int a = Integer.parseInt(ab[0].trim());
                int b = ab.length > 1 ? (ab[1].isBlank() ? max : Integer.parseInt(ab[1].trim())) : a;
                if (a < 1 || b > max || a > b) throw new IllegalArgumentException("Fora do intervalo 1-" + max + ": " + part);
                for (int i = a; i <= b; i++) out.add(i - 1);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Não entendi \"" + part + "\".");
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Nenhuma página informada.");
        return new ArrayList<>(out);
    }

    // =================================================================== exportação

    private void exportText() {
        File target = chooseSave("Exportar texto", "txt", baseName() + ".txt");
        if (target == null) return;
        runPdf("Extraindo texto...", () -> {
            String text = model.allText();
            Files.writeString(target.toPath(), text, StandardCharsets.UTF_8);
            return text;
        }, text -> {
            textOutput.setText(text);
            status.setText("Texto salvo em " + target.getAbsolutePath());
        });
    }

    private void exportImages(List<Integer> pages) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Pasta para salvar as imagens");
        File dir = dc.showDialog(stage);
        if (dir == null) return;
        runPdf("Gerando imagens (150 DPI)...", () -> model.exportImages(pages, dir, baseName(), 150f),
                n -> status.setText(n + " imagem(ns) salva(s) em " + dir.getAbsolutePath()));
    }

    // =================================================================== anotações

    private Tool currentTool() {
        Toggle t = toolGroup.getSelectedToggle();
        return t == null ? Tool.SELECT : (Tool) t.getUserData();
    }

    private double px(MouseEvent e) { return clamp(e.getX(), overlay.getWidth()) / zoom; }
    private double py(MouseEvent e) { return clamp(e.getY(), overlay.getHeight()) / zoom; }
    private static double clamp(double v, double max) { return Math.max(0, Math.min(max, v)); }

    private void onPress(MouseEvent e) {
        if (model == null || rendered == null) return;
        Tool tool = currentTool();
        double x = px(e), y = py(e);
        switch (tool) {
            case HIGHLIGHT, WHITEOUT, EDIT_TEXT -> {
                dragX0 = dragX1 = x;
                dragY0 = dragY1 = y;
                dragging = true;
            }
            case INK -> {
                inkPoints = new ArrayList<>();
                inkPoints.add(new double[]{x, y});
            }
            case TEXT -> {
                Optional<String> text = textDialog("Inserir texto", "", "Digite o texto:");
                text.filter(s -> !s.isBlank()).ifPresent(s ->
                        addAnnotation(new Annotation.Text(x, y, s, sizeSpinner.getValue(), colorPicker.getValue())));
            }
            case SIGN -> placeSignature(x, y);
            default -> { }
        }
    }

    private void onDrag(MouseEvent e) {
        if (model == null) return;
        if (dragging) {
            dragX1 = px(e);
            dragY1 = py(e);
            redrawOverlay();
        } else if (inkPoints != null) {
            inkPoints.add(new double[]{px(e), py(e)});
            redrawOverlay();
        }
    }

    private void onRelease(MouseEvent e) {
        if (model == null) return;
        if (inkPoints != null) {
            List<double[]> pts = inkPoints;
            inkPoints = null;
            if (pts.size() > 1) {
                addAnnotation(new Annotation.Ink(pts, colorPicker.getValue(), Math.max(1, sizeSpinner.getValue() / 6.0)));
            } else {
                redrawOverlay();
            }
            return;
        }
        if (!dragging) return;
        dragging = false;
        double x = Math.min(dragX0, dragX1), y = Math.min(dragY0, dragY1);
        double w = Math.abs(dragX1 - dragX0), h = Math.abs(dragY1 - dragY0);
        if (w < 3 || h < 3) {
            redrawOverlay();
            return;
        }
        switch (currentTool()) {
            case HIGHLIGHT -> addAnnotation(new Annotation.Highlight(x, y, w, h, highlightColor()));
            case WHITEOUT -> addAnnotation(new Annotation.Whiteout(x, y, w, h, Color.WHITE));
            case EDIT_TEXT -> editTextArea(x, y, w, h);
            default -> redrawOverlay();
        }
    }

    /** Destaque: se a cor escolhida for escura (padrão vermelho do texto), usa amarelo. */
    private Color highlightColor() {
        Color c = colorPicker.getValue();
        return c.getBrightness() < 0.5 || c.equals(Color.web("#d32f2f")) ? Color.web("#ffeb3b") : c;
    }

    private void editTextArea(double x, double y, double w, double h) {
        int page = current;
        runPdf("Lendo texto da área...", () -> model.textInArea(page, x, y, w, h), original -> {
            redrawOverlay();
            Optional<String> text = textDialog("Editar texto",
                    original.isEmpty() ? "" : original,
                    original.isEmpty() ? "Nenhum texto encontrado na área. Digite o novo texto:"
                            : "Altere o texto. A área original será coberta e o novo texto escrito por cima:");
            text.ifPresent(s -> {
                int size = sizeSpinner.getValue();
                addAnnotation(new Annotation.Whiteout(x, y, w, h, Color.WHITE));
                if (!s.isBlank()) {
                    addAnnotation(new Annotation.Text(x, y, s, size, colorPicker.getValue().equals(Color.web("#d32f2f"))
                            ? Color.BLACK : colorPicker.getValue()));
                }
            });
        });
    }

    private void newSignature() {
        SignatureDialog.show(stage).ifPresent(png -> {
            signaturePng = png;
            status.setText("Assinatura pronta. Use a ferramenta ✍ Assinar e clique na página.");
        });
    }

    private void placeSignature(double x, double y) {
        if (signaturePng == null) newSignature();
        if (signaturePng == null) return;
        Image img = image(signaturePng);
        double w = 160, h = w * img.getHeight() / Math.max(1, img.getWidth());
        addAnnotation(new Annotation.Image(signaturePng, x - w / 2, y - h / 2, w, h));
    }

    private void addAnnotation(Annotation a) {
        int page = current;
        runPdf(null, () -> {
            model.addAnnotation(page, a);
            return null;
        }, v -> {
            refreshAnnotations();
            thumbCache.remove(page);
        });
    }

    private void undo() {
        if (model == null) return;
        int page = current;
        runPdf(null, () -> model.undoAnnotation(page), ok -> {
            if (!ok) status.setText("Nada para desfazer nesta página.");
            refreshAnnotations();
        });
    }

    private void clearPageAnnotations() {
        int page = current;
        runPdf(null, () -> {
            model.clearAnnotations(page);
            return null;
        }, v -> refreshAnnotations());
    }

    private Image image(byte[] png) {
        return imageCache.computeIfAbsent(png, b -> new Image(new ByteArrayInputStream(b)));
    }

    private void redrawOverlay() {
        GraphicsContext g = overlay.getGraphicsContext2D();
        g.setTransform(1, 0, 0, 1, 0, 0);
        g.clearRect(0, 0, overlay.getWidth(), overlay.getHeight());
        if (rendered == null) return;
        g.scale(zoom, zoom);
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        for (Annotation a : rendered.annotations()) drawAnnotation(g, a);

        if (inkPoints != null && inkPoints.size() > 1) {
            drawAnnotation(g, new Annotation.Ink(inkPoints, colorPicker.getValue(), Math.max(1, sizeSpinner.getValue() / 6.0)));
        }
        if (dragging) {
            double x = Math.min(dragX0, dragX1), y = Math.min(dragY0, dragY1);
            double w = Math.abs(dragX1 - dragX0), h = Math.abs(dragY1 - dragY0);
            switch (currentTool()) {
                case HIGHLIGHT -> drawAnnotation(g, new Annotation.Highlight(x, y, w, h, highlightColor()));
                case WHITEOUT -> drawAnnotation(g, new Annotation.Whiteout(x, y, w, h, Color.WHITE));
                default -> { }
            }
            g.setStroke(Color.web("#1976d2"));
            g.setLineWidth(1 / zoom);
            g.setLineDashes(4 / zoom);
            g.strokeRect(x, y, w, h);
            g.setLineDashes((double[]) null);
        }
    }

    private void drawAnnotation(GraphicsContext g, Annotation a) {
        switch (a) {
            case Annotation.Text t -> {
                g.setFill(t.color());
                g.setFont(Font.font("Arial", t.size()));
                String[] lines = t.text().split("\\R", -1);
                for (int i = 0; i < lines.length; i++) {
                    g.fillText(lines[i], t.x(), t.y() + t.size() + i * t.size() * 1.2);
                }
            }
            case Annotation.Highlight h -> {
                g.setGlobalAlpha(0.35);
                g.setFill(h.color());
                g.fillRect(h.x(), h.y(), h.w(), h.h());
                g.setGlobalAlpha(1);
            }
            case Annotation.Whiteout w -> {
                g.setFill(w.color());
                g.fillRect(w.x(), w.y(), w.w(), w.h());
            }
            case Annotation.Ink ink -> {
                g.setStroke(ink.color());
                g.setLineWidth(ink.width());
                g.beginPath();
                double[] p0 = ink.points().get(0);
                g.moveTo(p0[0], p0[1]);
                for (double[] p : ink.points()) g.lineTo(p[0], p[1]);
                g.stroke();
            }
            case Annotation.Image im -> g.drawImage(image(im.png()), im.x(), im.y(), im.w(), im.h());
        }
    }

    // =================================================================== voz

    private void readAloud(boolean wholeDoc) {
        if (model == null) return;
        int page = current;
        runPdf("Preparando leitura...", () -> wholeDoc ? model.allText() : model.extractText(page, page),
                this::speakText);
    }

    private void speakText(String text) {
        if (text == null || text.isBlank()) {
            status.setText("Não há texto para ler (o PDF pode ser digitalizado/imagem).");
            return;
        }
        try {
            speech.speak(text, () -> Platform.runLater(() -> status.setText("Leitura concluída.")));
            status.setText("Lendo em voz alta... (Esc ou ⏹ para parar)");
        } catch (Exception ex) {
            error("Leitura em voz alta indisponível", ex);
        }
    }

    // =================================================================== Claude

    private static final String PROMPT_SUMMARY_DOC = """
            Faça um resumo deste documento: comece com uma frase dizendo do que se trata, \
            depois liste os pontos principais, números/datas/prazos importantes e, se houver, \
            ações ou decisões necessárias.""";
    private static final String PROMPT_SUMMARY_PAGE = """
            Resuma esta página de forma objetiva, destacando os pontos principais.""";

    private void askClaude(boolean currentPageOnly, String instruction) {
        if (model == null) return;
        if (!ClaudeService.hasCredentials() && !configureApiKey()) return;
        int page = current;
        aiControls.forEach(n -> n.setDisable(true));
        aiOutput.setText((currentPageOnly ? "▶ Página " + (page + 1) + ": " : "▶ ")
                + (instruction.length() > 120 ? instruction.substring(0, 120) + "..." : instruction) + "\n\n");
        runPdf("Preparando documento para o Claude...", () -> {
            byte[] pdf = currentPageOnly ? model.pagesToBytes(List.of(page)) : model.toBytes(true);
            String text = currentPageOnly ? model.extractText(page, page) : model.allText();
            return new Object[]{pdf, text};
        }, data -> {
            status.setText("Consultando o Claude...");
            Thread t = new Thread(() -> {
                try {
                    ClaudeService.ask((byte[]) data[0], (String) data[1], instruction,
                            delta -> Platform.runLater(() -> aiOutput.appendText(delta)));
                    Platform.runLater(() -> status.setText("Resposta do Claude concluída."));
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        aiOutput.appendText("\n\n[Erro] " + ex.getMessage());
                        status.setText("Falha ao consultar o Claude.");
                    });
                } finally {
                    Platform.runLater(() -> aiControls.forEach(n -> n.setDisable(model == null)));
                }
            }, "claude-request");
            t.setDaemon(true);
            t.start();
        });
    }

    private boolean configureApiKey() {
        Dialog<String> d = new Dialog<>();
        d.initOwner(stage);
        d.setTitle("Chave de API do Claude");
        d.setHeaderText("""
                Para usar o Claude no app é preciso uma chave de API (começa com sk-ant-).
                Crie a sua em https://console.anthropic.com → API Keys.
                Obs.: a assinatura do claude.ai (Pro/Max) não serve aqui; a API é cobrada por uso.""");
        PasswordField pf = new PasswordField();
        pf.setText(ClaudeService.savedApiKey());
        pf.setPromptText("sk-ant-...");
        pf.setPrefColumnCount(40);
        Label note = new Label("A chave fica salva apenas neste computador (preferências do usuário).\n"
                + "Deixe em branco para usar a variável de ambiente ANTHROPIC_API_KEY.");
        note.setStyle("-fx-text-fill: #555;");
        VBox box = new VBox(8, pf, note);
        box.setPadding(new Insets(10));
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setResultConverter(bt -> bt == ButtonType.OK ? pf.getText() : null);
        Platform.runLater(pf::requestFocus);
        Optional<String> r = d.showAndWait();
        r.ifPresent(ClaudeService::saveApiKey);
        if (r.isPresent() && !ClaudeService.hasCredentials()) {
            info("Chave não configurada", "Informe uma chave de API para usar os recursos do Claude.");
        }
        return r.isPresent() && ClaudeService.hasCredentials();
    }

    // =================================================================== util

    private <T> void runPdf(String busy, Callable<T> work, Consumer<T> onSuccess) {
        if (busy != null) {
            status.setText(busy);
            stage.getScene().setCursor(javafx.scene.Cursor.WAIT);
        }
        pdfExec.submit(() -> {
            try {
                T result = work.call();
                Platform.runLater(() -> {
                    if (busy != null) {
                        stage.getScene().setCursor(javafx.scene.Cursor.DEFAULT);
                        if (status.getText().equals(busy)) status.setText("Pronto.");
                    }
                    onSuccess.accept(result);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    stage.getScene().setCursor(javafx.scene.Cursor.DEFAULT);
                    error(busy != null ? busy.replace("...", "") + " falhou" : "Erro", ex);
                    status.setText("Erro: " + ex.getMessage());
                });
            }
        });
    }

    private void updateEnabled() {
        boolean noDoc = model == null;
        needsDoc.forEach(n -> n.setDisable(noDoc));
        needsDocMenu.forEach(m -> m.setDisable(noDoc));
        updateTitle();
    }

    private void updateTitle() {
        if (model == null) {
            stage.setTitle("PDF Studio");
            return;
        }
        String name = model.file() != null ? model.file().getName() : "Sem título.pdf";
        stage.setTitle((model.isDirty() ? "• " : "") + name + " — PDF Studio");
    }

    private String baseName() {
        if (model == null || model.file() == null) return "documento";
        String n = model.file().getName();
        return n.toLowerCase().endsWith(".pdf") ? n.substring(0, n.length() - 4) : n;
    }

    private File chooseSave(String title, String ext, String initialName) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.setInitialFileName(initialName);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(ext.toUpperCase(), "*." + ext));
        if (model != null && model.file() != null) fc.setInitialDirectory(model.file().getParentFile());
        File f = fc.showSaveDialog(stage);
        if (f != null && !f.getName().toLowerCase().endsWith("." + ext)) f = new File(f.getPath() + "." + ext);
        return f;
    }

    private Optional<String> textDialog(String title, String initial, String header) {
        Dialog<String> d = new Dialog<>();
        d.initOwner(stage);
        d.setTitle(title);
        d.setHeaderText(header);
        TextArea ta = new TextArea(initial);
        ta.setPrefRowCount(5);
        ta.setPrefColumnCount(40);
        ta.setWrapText(false);
        d.getDialogPane().setContent(ta);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setResultConverter(bt -> bt == ButtonType.OK ? ta.getText() : null);
        Platform.runLater(ta::requestFocus);
        return d.showAndWait();
    }

    private void copy(String s) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(s);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
        status.setText("Copiado.");
    }

    private void info(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.initOwner(stage);
        a.setTitle(title);
        a.setHeaderText(null);
        a.showAndWait();
    }

    private void error(String title, Throwable ex) {
        Alert a = new Alert(Alert.AlertType.ERROR, String.valueOf(ex.getMessage()), ButtonType.OK);
        a.initOwner(stage);
        a.setTitle("Erro");
        a.setHeaderText(title);
        a.showAndWait();
    }
}
