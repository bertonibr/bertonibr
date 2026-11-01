package com.bertonibr.pdfstudio;

import com.bertonibr.pdfstudio.ui.MainWindow;
import javafx.application.Application;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;

public class PdfStudioApp extends Application {

    @Override
    public void start(Stage stage) {
        MainWindow window = new MainWindow(stage);
        window.show();
        List<String> args = getParameters().getUnnamed();
        if (!args.isEmpty()) window.open(new File(args.get(0)));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
