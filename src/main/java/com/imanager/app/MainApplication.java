package com.imanager.app;

import com.imanager.controller.MainController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApplication extends Application {

    private FXMLLoader fxmlLoader;

    @Override
    public void start(Stage primaryStage) throws Exception {
        fxmlLoader = new FXMLLoader(getClass().getResource("/main.fxml"));
        Scene scene = new Scene(fxmlLoader.load());
        // 加载全局CSS
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        primaryStage.setTitle("图片管理器");
        primaryStage.setScene(scene);
        primaryStage.show();
    }


    @Override
    public void stop() throws Exception {
        super.stop();
        if (fxmlLoader != null) {
            MainController controller = fxmlLoader.getController();
            if (controller != null) {
                controller.shutdown(); // 关闭线程池
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
