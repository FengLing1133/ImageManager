package com.imanager.util;

import javafx.application.Platform;
import javafx.scene.control.Alert;

public class AlterUtil {
    //辅助方法：弹出提示框
    public static void showAlert(Alert.AlertType type, String title, String content, javafx.stage.Window owner) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            if (owner != null) {
                alert.initOwner(owner);
            }
            alert.showAndWait();
        });
    }
}
