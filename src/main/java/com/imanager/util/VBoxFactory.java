package com.imanager.util;

import javafx.application.Platform;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.scene.effect.DropShadow;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class VBoxFactory {
    private static final int FILE_NAME_MAX_LENGTH = 18; // 文件名最大显示长度（含省略号）

    // 通用 VBox 创建方法（文件/文件夹/图片）
    private VBox createBaseVBox(ImageView imageView, String name, int width, String normalStyle) {
        imageView.setFitWidth(width);
        imageView.setFitHeight(width);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        Label nameLabel = new Label(truncateFileName(name));
        nameLabel.setMaxWidth(width);
        nameLabel.setStyle("-fx-font-size: 12px; -fx-alignment: center; -fx-text-alignment: center;");
        nameLabel.setWrapText(true);// 允许换行显示长文件名
        VBox vBox = new VBox(5, imageView, nameLabel);
        vBox.getStyleClass().add("card");
        vBox.setPadding(new Insets(5));
        vBox.setStyle(normalStyle);
        setupCardHoverEffect(vBox);
        return vBox;
    }

    // 通用右键菜单生成
    private ContextMenu buildBaseContextMenu(Runnable onDelete, Runnable onCopy, Runnable onRename, Runnable onPaste) {
        ContextMenu contextMenu = new ContextMenu();
        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> { if (onDelete != null) onDelete.run(); });
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> { if (onCopy != null) onCopy.run(); });
        MenuItem renameItem = new MenuItem("重命名");
        renameItem.setOnAction(e -> { if (onRename != null) onRename.run(); });
        MenuItem pasteItem = new MenuItem("粘贴");
        pasteItem.setOnAction(e -> { if (onPaste != null) onPaste.run(); });
        contextMenu.getItems().addAll(deleteItem, copyItem, renameItem, pasteItem); // 只添加一次
        return contextMenu;
    }

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
            Runnable onDelete,
            Runnable onCopy,
            Runnable onRename,
            Runnable onPaste
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
        VBox vBox = createBaseVBox(imageView, file.getName(), 120, normalStyle);
        vBox.setOnContextMenuRequested(event -> {
            ContextMenu contextMenu = buildBaseContextMenu( onDelete, onCopy, onRename, onPaste);
            contextMenu.show(vBox, event.getScreenX(), event.getScreenY());
        });
        setupFileVBoxSelection(vBox, normalStyle, selectedStyle, selectedVBoxes, updateTipLabel);
        vBox.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && file.isDirectory()) {
                if (onDoubleClickDir != null) onDoubleClickDir.run();
                event.consume();
            }
        });
        vBoxToFile.put(vBox, file);
        callback.accept(vBox);
    }

    // 创建图片VBox
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
        VBox vBox = createBaseVBox(imageView, file.getName(), thumbSize, normalStyle);
        setupImageVBox(vBox, normalStyle, selectedStyle, selectedVBoxes, updateTipLabel, onDoubleClickImage, onDelete, onCopy, onRename, onPaste);
        vBoxToFile.put(vBox, file);
        callback.accept(vBox);
    }

    // 异步创建图片VBox（后台加载完成后显示）
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
        String filePath = file.getAbsolutePath(); // 路径
        if (imageCache.containsKey(filePath)) {
            Image image = imageCache.get(filePath); // 缓存命中
            createImageVBox(file, image, callback, thumbSize, normalStyle, selectedStyle, selectedVBoxes, vBoxToFile, updateTipLabel, onDoubleClickImage, onDelete, onCopy, onRename, onPaste);
            return;
        }
        imageExecutor.submit(() -> { // 异步加载图片
            try {
                Image img = new Image(file.toURI().toString(), thumbSize, thumbSize, true, true, false); // 加载缩略图
                imageCache.put(filePath, img);// 加入缓存
                Platform.runLater(() -> createImageVBox(file, img, callback, thumbSize, normalStyle, selectedStyle, selectedVBoxes, vBoxToFile, updateTipLabel, onDoubleClickImage, onDelete, onCopy, onRename, onPaste));
            } catch (Exception e) {
                System.out.println("⚠️ 图片加载失败：" + file.getName());
            }
        });
    }

    // 配置图片VBox的事件和菜单
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
        ContextMenu contextMenu = buildBaseContextMenu(onDelete, onCopy, onRename, onPaste);
        vBox.setOnContextMenuRequested(event -> {
            // 只弹出菜单，不做选中处理，选中逻辑全部放在setOnMousePressed
            contextMenu.show(vBox, event.getScreenX(), event.getScreenY());
            event.consume();
        });

        // 图片卡片双击打开幻灯片（使用事件过滤避免被选中逻辑吞掉）
        vBox.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                if (onDoubleClickImage != null) {
                    onDoubleClickImage.run(); // 双击图片
                }
                event.consume();
            }
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
                } else {
                    selectedVBoxes.remove(vBox);
                    vBox.setStyle(normalStyle);
                }
            } else {
                // 左键或右键未选中时都只选中当前项
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

    // 文件名过长省略工具方法
    public static String truncateFileName(String name) {
        if (name == null || name.length() <= FILE_NAME_MAX_LENGTH) return name;
        int keep = FILE_NAME_MAX_LENGTH - 3; // 省略号占3
        int prefix = keep / 2;
        int suffix = keep - prefix;
        return name.substring(0, prefix) + "..." + name.substring(name.length() - suffix);
    }

    // 卡片悬停时轻微上浮，提升现代感
    private void setupCardHoverEffect(VBox vBox) {
        TranslateTransition up = new TranslateTransition(Duration.millis(140), vBox); // 上浮动画
        up.setToY(-4);
        TranslateTransition down = new TranslateTransition(Duration.millis(140), vBox); // 还原动画
        down.setToY(0);
        DropShadow hoverShadow = new DropShadow(14, Color.rgb(56, 68, 84, 0.18)); // 阴影
        hoverShadow.setOffsetY(3);

        vBox.setOnMouseEntered(event -> {
            down.stop();
            up.playFromStart();
            vBox.setEffect(hoverShadow); // 悬停阴影
        });
        vBox.setOnMouseExited(event -> {
            up.stop();
            down.playFromStart();
            vBox.setEffect(null); // 还原
        });
    }

    // 动态生成右键菜单
    public ContextMenu buildContextMenu(int selectedCount, Runnable onDelete, Runnable onCopy, Runnable onRename, Runnable onPaste) {
        ContextMenu menu = new ContextMenu(); // 右键菜单
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

    // 提供外部调用的快捷入口VBox创建方法，内部调用createBaseVBox
    public void createShortcutVBox(
            String displayName,
            int thumbSize,
            String normalStyle,
            String selectedStyle,
            Set<VBox> selectedVBoxes,
            FlowPane imageFlowPane,
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
        VBox vBox = createBaseVBox(imageView, displayName, thumbSize, normalStyle);
        (vBox.getChildren().get(1)).setStyle("-fx-font-size: 12px; -fx-alignment: center; -fx-text-alignment: center; -fx-font-weight: bold;");
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
}
