package com.imanager.util;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class VBoxFactory {
    private static final int FILE_NAME_MAX_LENGTH = 18;

    //异步创建VBox（图标+名称+点击事件）
    public void createVBoxAsync(
            File file,
            Consumer<VBox> callback,
            Set<VBox> selectedVBoxes,
            Map<VBox, File> vBoxToFile,
            String normalStyle,
            String selectedStyle,
            Runnable updateTipLabel,
            Runnable onDoubleClickDir,
            Runnable onPaste,
            Runnable onDelete,
            Runnable onCopy,
            Runnable onRename
    ) {
        ImageView imageView;
        if (file.isDirectory()) {
            imageView = new ImageView();
            try {
                var stream = getClass().getResourceAsStream("/icons/folder.png");
                if (stream != null) {
                    imageView = new ImageView(new Image(stream));
                }
            } catch (Exception e) {
                imageView = new ImageView();
            }
        } else {
            imageView = new ImageView();
            try {
                var stream = getClass().getResourceAsStream("/icons/file.png");
                if (stream != null) {
                    imageView = new ImageView(new Image(stream));
                }
            } catch (Exception e) {
                imageView = new ImageView();
            }
        }
        imageView.setFitWidth(120);
        imageView.setFitHeight(120);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        Label nameLabel = new Label(truncateFileName(file.getName()));
        nameLabel.setMaxWidth(120);
        nameLabel.setStyle("-fx-font-size: 12px; -fx-alignment: center; -fx-text-alignment: center;");
        nameLabel.setWrapText(true);
        VBox vBox = new VBox(5, imageView, nameLabel);
        vBox.getStyleClass().add("card");
        vBox.setPadding(new Insets(5));
        vBox.setStyle(normalStyle);

        ContextMenu contextMenu = new ContextMenu();
        if (file.isDirectory()) {
            MenuItem pasteItem = new MenuItem("粘贴");
            pasteItem.setOnAction(e -> {
                if (onPaste != null) onPaste.run();
            });
            contextMenu.getItems().add(pasteItem);
        } else {
            MenuItem deleteItem = new MenuItem("删除");
            deleteItem.setOnAction(e -> {
                if (onDelete != null) onDelete.run();
            });
            MenuItem copyItem = new MenuItem("复制");
            copyItem.setOnAction(e -> {
                if (onCopy != null) onCopy.run();
            });
            MenuItem renameItem = new MenuItem("重命名");
            renameItem.setOnAction(e -> {
                if (onRename != null) onRename.run();
            });
            MenuItem pasteItem = new MenuItem("粘贴");
            pasteItem.setOnAction(e -> {
                if (onPaste != null) onPaste.run();
            });
            contextMenu.getItems().addAll(deleteItem, copyItem, renameItem, pasteItem);
        }
        vBox.setOnContextMenuRequested(event -> contextMenu.show(vBox, event.getScreenX(), event.getScreenY()));

        setupFileVBoxSelection(vBox, normalStyle, selectedStyle, selectedVBoxes, updateTipLabel);

        vBox.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && file.isDirectory()) {
                if (onDoubleClickDir != null) onDoubleClickDir.run();//进入该文件夹
                event.consume();
            }
        });
        vBoxToFile.put(vBox, file);
        callback.accept(vBox);
    }

    //创建图片VBox
    private void createImageVBox(
            File file,
            Image image,
            Consumer<VBox> callback,
            int thumbSize,
            String normalStyle,
            String selectedStyle,
            Set<VBox> selectedVBoxes,
            Map<VBox, File> vBoxToFile,
            Runnable updateTipLabel,
            Runnable onDoubleClickImage,
            Runnable onDelete,
            Runnable onCopy,
            Runnable onRename,
            Runnable onPaste
    ) {
        ImageView imageView = new ImageView(image);
        imageView.setFitWidth(thumbSize);
        imageView.setFitHeight(thumbSize);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        Label nameLabel = new Label(truncateFileName(file.getName()));
        nameLabel.setMaxWidth(thumbSize);
        nameLabel.setStyle("-fx-font-size: 12px; -fx-alignment: center; -fx-text-alignment: center;");
        nameLabel.setWrapText(true);
        VBox vBox = new VBox(5, imageView, nameLabel);
        vBox.getStyleClass().add("card");
        vBox.setPadding(new Insets(5));
        vBox.setStyle(normalStyle);
        setupImageVBox(vBox, normalStyle, selectedStyle, selectedVBoxes, updateTipLabel, onDoubleClickImage, onDelete, onCopy, onRename, onPaste);
        vBoxToFile.put(vBox, file);
        callback.accept(vBox);
    }

    //异步创建图片VBox（后台加载完成后显示）
    public void createImageVBoxAsync(
            File file,
            Consumer<VBox> callback,
            Map<String, Image> imageCache,
            java.util.concurrent.ExecutorService imageExecutor,
            int thumbSize,
            String normalStyle,
            String selectedStyle,
            Set<VBox> selectedVBoxes,
            Map<VBox, File> vBoxToFile,
            Runnable updateTipLabel,
            Runnable onDoubleClickImage,
            Runnable onDelete,
            Runnable onCopy,
            Runnable onRename,
            Runnable onPaste
    ) {
        String filePath = file.getAbsolutePath();
        if (imageCache.containsKey(filePath)) {
            Image image = imageCache.get(filePath);
            createImageVBox(file, image, callback, thumbSize, normalStyle, selectedStyle, selectedVBoxes, vBoxToFile, updateTipLabel, onDoubleClickImage, onDelete, onCopy, onRename, onPaste);
            return;
        }
        imageExecutor.submit(() -> {
            try {
                Image img = new Image(file.toURI().toString(), thumbSize, thumbSize, true, true, false);
                imageCache.put(filePath, img);
                Platform.runLater(() -> createImageVBox(file, img, callback, thumbSize, normalStyle, selectedStyle, selectedVBoxes, vBoxToFile, updateTipLabel, onDoubleClickImage, onDelete, onCopy, onRename, onPaste));
            } catch (Exception e) {
                System.out.println("⚠️ 图片加载失败：" + file.getName());
            }
        });
    }

    //配置图片VBox的事件和菜单
    private void setupImageVBox(
            VBox vBox,
            String normalStyle,
            String selectedStyle,
            Set<VBox> selectedVBoxes,
            Runnable updateTipLabel,
            Runnable onDoubleClickImage,
            Runnable onDelete,
            Runnable onCopy,
            Runnable onRename,
            Runnable onPaste
    ) {
        ContextMenu contextMenu = new ContextMenu();
        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> {
            if (onDelete != null) onDelete.run();
        });
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> {
            if (onCopy != null) onCopy.run();
        });
        MenuItem renameItem = new MenuItem("重命名");
        renameItem.setOnAction(e -> {
            if (onRename != null) onRename.run();
        });
        MenuItem pasteItem = new MenuItem("粘贴");
        pasteItem.setOnAction(e -> {
            if (onPaste != null) onPaste.run();
        });
        contextMenu.getItems().addAll(deleteItem, copyItem, renameItem, pasteItem);

        vBox.setOnContextMenuRequested(event -> {
            boolean alreadySelected = selectedVBoxes.contains(vBox);
            if (!alreadySelected) {
                selectedVBoxes.forEach(v -> v.setStyle(normalStyle));
                selectedVBoxes.clear();
                selectedVBoxes.add(vBox);
                vBox.setStyle(selectedStyle);
                if (updateTipLabel != null) updateTipLabel.run();
            }
            contextMenu.show(vBox, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        setupFileVBoxSelection(vBox, normalStyle, selectedStyle, selectedVBoxes, updateTipLabel);
    }

    // 统一的选中逻辑：左右键都可选中，仅 Ctrl+左键启用多选切换
    private void setupFileVBoxSelection(VBox vBox, String normalStyle, String selectedStyle, Set<VBox> selectedVBoxes, Runnable updateTipLabel) {
        vBox.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY && event.getButton() != MouseButton.SECONDARY) {
                return;
            }
            boolean ctrlMultiSelect = event.isControlDown() && event.getButton() == MouseButton.PRIMARY;
            if (ctrlMultiSelect) {
                if (!selectedVBoxes.contains(vBox)) {
                    selectedVBoxes.add(vBox);
                    vBox.setStyle(selectedStyle);
                }else{
                    selectedVBoxes.remove(vBox);
                    vBox.setStyle(normalStyle);
                }
            } else {
                // 只有单击未选中项时才清空其它选中
                if (!selectedVBoxes.contains(vBox)) {
                    selectedVBoxes.forEach(v -> v.setStyle(normalStyle));
                    selectedVBoxes.clear();
                    selectedVBoxes.add(vBox);
                    vBox.setStyle(selectedStyle);
                }
            }
            if (updateTipLabel != null) updateTipLabel.run();
            event.consume();
        });
    }


    //创建快捷入口VBox
    public void createShortcutVBox(
            String displayName,
            int thumbSize,
            String normalStyle,
            String selectedStyle,
            Set<VBox> selectedVBoxes,
            javafx.scene.layout.FlowPane imageFlowPane,
            Runnable updateTipLabel,
            Runnable onDoubleClickDir
    ) {
        ImageView imageView = new ImageView();
        try {
            var stream = getClass().getResourceAsStream("/icons/folder.png");
            if (stream != null) {
                imageView = new ImageView(new Image(stream));
            }
        } catch (Exception e) {
            imageView = new ImageView();
        }
        imageView.setFitWidth(thumbSize);
        imageView.setFitHeight(thumbSize);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        Label nameLabel = new Label(truncateFileName(displayName));
        nameLabel.setMaxWidth(thumbSize);
        nameLabel.setStyle("-fx-font-size: 12px; -fx-alignment: center; -fx-text-alignment: center; -fx-font-weight: bold;");
        nameLabel.setWrapText(true);
        VBox vBox = new VBox(5, imageView, nameLabel);
        vBox.getStyleClass().add("card");
        vBox.setPadding(new Insets(5));
        vBox.setStyle(normalStyle);

        vBox.setOnMouseClicked(event -> {
            if (event.getClickCount() == 1) {
                selectedVBoxes.forEach(v -> v.setStyle(normalStyle));
                selectedVBoxes.clear();
                selectedVBoxes.add(vBox);
                vBox.setStyle(selectedStyle);
                if (updateTipLabel != null) updateTipLabel.run();
            } else if (event.getClickCount() == 2) {
                if (onDoubleClickDir != null) onDoubleClickDir.run();
            }
            event.consume();
        });

        imageFlowPane.getChildren().add(vBox);
        FlowPane.setMargin(vBox, new Insets(5));
    }

    //文件名过长省略工具方法
    private String truncateFileName(String name) {
        if (name == null || name.length() <= FILE_NAME_MAX_LENGTH) return name;
        int keep = FILE_NAME_MAX_LENGTH - 3;
        int prefix = keep / 2;
        int suffix = keep - prefix;
        return name.substring(0, prefix) + "..." + name.substring(name.length() - suffix);
    }

    // 动态生成右键菜单
    public ContextMenu buildContextMenu(int selectedCount, Runnable onDelete, Runnable onCopy, Runnable onRename, Runnable onPaste) {
        ContextMenu menu = new ContextMenu();
        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> {
            if (onDelete != null) onDelete.run();
        });
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> {
            if (onCopy != null) onCopy.run();
        });
        MenuItem renameItem = new MenuItem("重命名");
        renameItem.setOnAction(e -> {
            if (onRename != null) onRename.run();
        });
        MenuItem pasteItem = new MenuItem("粘贴");
        pasteItem.setOnAction(e -> {
            if (onPaste != null) onPaste.run();
        });
        if (selectedCount == 1) {
            // 单选全部可用
            deleteItem.setDisable(false);
            copyItem.setDisable(false);
            renameItem.setDisable(false);
            pasteItem.setDisable(false);
        } else if (selectedCount > 1) {
            // 多选时重命名、粘贴禁用
            deleteItem.setDisable(false);
            copyItem.setDisable(false);
            renameItem.setDisable(true);
            pasteItem.setDisable(true);
        } else {
            // 空白或异常，只有粘贴可用
            deleteItem.setDisable(true);
            copyItem.setDisable(true);
            renameItem.setDisable(true);
            pasteItem.setDisable(false);
        }
        menu.getItems().addAll(deleteItem, copyItem, renameItem, pasteItem);
        return menu;
    }

    /**
     * 构建右键菜单，支持根据选中项类型动态禁用项。
     *
     * @param selectedCount 选中数量
     * @param allImage      是否全为图片
     * @param onDelete      删除回调
     * @param onCopy        复制回调
     * @param onRename      重命名回调
     * @param onPaste       粘贴回调
     * @return ContextMenu
     */
    public ContextMenu buildContextMenu(int selectedCount, boolean allImage, Runnable onDelete, Runnable onCopy, Runnable onRename, Runnable onPaste) {
        ContextMenu menu = new ContextMenu();
        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> {
            if (onDelete != null) onDelete.run();
        });
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> {
            if (onCopy != null) onCopy.run();
        });
        MenuItem renameItem = new MenuItem("重命名");
        renameItem.setOnAction(e -> {
            if (onRename != null) onRename.run();
        });
        MenuItem pasteItem = new MenuItem("粘贴");
        pasteItem.setOnAction(e -> {
            if (onPaste != null) onPaste.run();
        });
        if (selectedCount == 1) {
            // 单选全部可用
            deleteItem.setDisable(false);
            copyItem.setDisable(false);
            renameItem.setDisable(false);
            pasteItem.setDisable(false);
        } else if (selectedCount > 1) {
            // 多选时，允许删除和复制。重命名、粘贴暂且禁用。
            deleteItem.setDisable(false);
            copyItem.setDisable(false);
            renameItem.setDisable(true);
            pasteItem.setDisable(true);
        } else {
            // 空白或异常
            deleteItem.setDisable(true);
            copyItem.setDisable(true);
            renameItem.setDisable(true);
            pasteItem.setDisable(false);
        }
        menu.getItems().addAll(deleteItem, copyItem, renameItem, pasteItem);
        return menu;
    }
}
