import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class DemoApp extends Application {

    @Override
    public void start(Stage stage) {
        Label label = new Label("Olá!");
        Button botao = new Button("Clique aqui");

        botao.setOnAction(e -> label.setText("Você clicou no botão!"));

        VBox layout = new VBox(10, label, botao);
        layout.setStyle("-fx-padding: 20; -fx-alignment: center;");

        Scene scene = new Scene(layout, 300, 150);
        stage.setScene(scene);
        stage.setTitle("Demo JavaFX");
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
