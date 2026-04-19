package com.imanager.app;

import com.imanager.controller.MainController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApplication extends Application { // 应用程序主类，继承自JavaFX Application

    private FXMLLoader fxmlLoader; // 用于加载FXML界面

    @Override
    public void start(Stage primaryStage) throws Exception {
        fxmlLoader = new FXMLLoader(getClass().getResource("/main.fxml")); // 加载主界面FXML
        Scene scene = new Scene(fxmlLoader.load()); // 创建场景
        java.net.URL styleUrl = getClass().getResource("/style.css"); // 获取样式表资源
        if (styleUrl != null) scene.getStylesheets().add(styleUrl.toExternalForm()); // 防止NullPointerException
        primaryStage.setTitle("图片管理器"); // 设置窗口标题
        primaryStage.setScene(scene); // 设置场景
        primaryStage.show(); // 显示窗口
    }

    @Override
    public void stop() throws Exception {
        super.stop(); // 调用父类的stop方法
        if (fxmlLoader != null) {
            MainController controller = fxmlLoader.getController(); // 获取主控制器
            if (controller != null) {
                controller.shutdown(); // 关闭线程池，释放资源
            }
        }
    }

}
