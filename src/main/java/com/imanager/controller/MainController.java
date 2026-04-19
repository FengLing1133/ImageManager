package com.imanager.controller;

import com.imanager.service.DirectoryTreeService;
import com.imanager.service.FileService;
import com.imanager.util.AlterUtil;
import com.imanager.util.VBoxFactory;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.scene.layout.AnchorPane;
import javafx.util.Duration;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTreeCell;
import javafx.scene.layout.FlowPane;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;
import javafx.scene.input.MouseButton;
import javafx.stage.Stage;
import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class MainController {

    @FXML
    private TreeView<String> dirTreeView;

    @FXML
    private FlowPane imageFlowPane;

    @FXML
    private ScrollPane imageScrollPane;

    @FXML
    private Label tipLabel;

    @FXML
    private TextField pathField;

    @FXML
    private AnchorPane imageAnchorPane;

    @FXML
    private Label emptyTipLabel;

    private File currentDir; // 当前目录
    private DirectoryTreeService directoryTreeService; // 目录树服务
    private final FileService fileService = new FileService(); // 文件服务
    private final VBoxFactory vBoxFactory = new VBoxFactory(); // VBox工厂
    private final Set<VBox> selectedVBoxes = new HashSet<>(); // 选中的VBox集合
    private final Map<VBox, File> vBoxToFile = new HashMap<>(); // VBox与文件的映射
    private final List<File> copiedFiles = new ArrayList<>(); // 已复制的文件列表
    private static final String NORMAL_STYLE = "-fx-alignment: center; -fx-border-color: #d4dce8; -fx-border-width: 1.5px; -fx-background-color: #ffffff; -fx-background-radius: 14; -fx-border-radius: 14; -fx-effect: dropshadow(gaussian, rgba(56, 68, 84, 0.11), 10, 0, 0, 2);"; // 未选中样式
    private static final String SELECTED_STYLE = "-fx-alignment: center; -fx-border-color: #5a98ea; -fx-border-width: 2px; -fx-background-color: #eaf2ff; -fx-background-radius: 14; -fx-border-radius: 14; -fx-effect: dropshadow(gaussian, rgba(90, 152, 234, 0.24), 12, 0, 0, 2);"; // 选中样式
    private ContextMenu blankContextMenu = null; // 空白区域右键菜单
    private ContextMenu imageContextMenu = null; // 图片右键菜单
    private final Stack<File> dirHistoryStack = new Stack<>(); // 目录历史栈

    @FXML
    public void initialize() {
        directoryTreeService = new DirectoryTreeService(dirTreeView);//初始化目录树服务
        setupDirTreeCellFactory();//设置目录树节点工厂
        setupDirTreeSelectionListener();//监听目录树选择事件
        setupPathFieldListener();//监听路径输入框事件
        initFlowPaneHint();//初始化FlowPane快捷入口
        directoryTreeService.initDirectoryTree();//初始化目录树

        dirTreeView.setCellFactory(tv -> new javafx.scene.control.TreeCell<>() { // 自定义目录树节点工厂
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);// 更新节点内容
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);// 设置节点文本
                    javafx.scene.image.ImageView iconView = null;// 根据节点类型设置图标
                    if (getTreeItem().getParent() == null || "我的电脑".equals(item)) {
                        setGraphic(null);// 根节点和“我的电脑”不显示图标
                    } else if (item.matches("^[A-Z]:\\\\$")) {
                        java.net.URL iconUrl = getClass().getResource("/icons/hard-drive.png"); // 获取硬盘图标资源
                        if (iconUrl != null) {
                            iconView = new javafx.scene.image.ImageView(iconUrl.toExternalForm()); // 防止NullPointerException
                        }
                    } else if (getTreeItem().isLeaf()) {
                        java.net.URL iconUrl = getClass().getResource("/icons/folder.png"); // 获取文件夹图标资源
                        if (iconUrl != null) {
                            iconView = new javafx.scene.image.ImageView(iconUrl.toExternalForm()); // 防止NullPointerException
                        }
                    } else {
                        java.net.URL iconUrl = getClass().getResource("/icons/folder.png"); // 获取文件夹图标资源
                        if (iconUrl != null) {
                            iconView = new javafx.scene.image.ImageView(iconUrl.toExternalForm()); // 防止NullPointerException
                        }
                    }
                    if (iconView != null) {
                        iconView.setFitWidth(18);
                        iconView.setFitHeight(18);
                        setGraphic(iconView);
                    }
                }
            }
        });

        imageScrollPane.viewportBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            imageFlowPane.setPrefWidth(newBounds.getWidth()); // 自适应FlowPane宽度
        });

        imageScrollPane.viewportBoundsProperty().addListener((obs, oldVal, newVal) -> {
            imageAnchorPane.setPrefHeight(newVal.getHeight()); // 自适应AnchorPane高度
            imageAnchorPane.setPrefWidth(newVal.getWidth()); // 自适应AnchorPane宽度
        });

        imageFlowPane.setOnContextMenuRequested(event -> { // 空白区域右键菜单
            if (event.getTarget() != imageFlowPane) {
                return;
            }
            selectedVBoxes.forEach(v -> v.setStyle(NORMAL_STYLE));
            selectedVBoxes.clear();
            updateTipLabel();
            if (blankContextMenu != null && blankContextMenu.isShowing()) {
                blankContextMenu.hide();
            }
            blankContextMenu = vBoxFactory.buildContextMenu(0, this::deleteSelected, this::copySelected, this::renameSelected, this::pasteFiles);
            blankContextMenu.show(imageFlowPane, event.getScreenX(), event.getScreenY());
            event.consume();
        });

        imageScrollPane.setOnContextMenuRequested(event -> { // 滚动区域右键菜单
            if (event.getTarget() == imageScrollPane || event.getTarget() == imageScrollPane.getContent()) {
                if (blankContextMenu != null && blankContextMenu.isShowing()) {
                    blankContextMenu.hide();
                }
                blankContextMenu = vBoxFactory.buildContextMenu(0, this::deleteSelected, this::copySelected, this::renameSelected, this::pasteFiles);
                blankContextMenu.show(imageScrollPane, event.getScreenX(), event.getScreenY());
                event.consume();
            }
        });

        imageAnchorPane.setOnContextMenuRequested(event -> { // AnchorPane右键菜单
            if (event.getTarget() == imageAnchorPane) {
                if (blankContextMenu != null && blankContextMenu.isShowing()) {
                    blankContextMenu.hide();
                }
                blankContextMenu = vBoxFactory.buildContextMenu(0, this::deleteSelected, this::copySelected, this::renameSelected, this::pasteFiles);
                blankContextMenu.show(imageAnchorPane, event.getScreenX(), event.getScreenY());
                event.consume();
            }
        });

        imageFlowPane.setOnMousePressed(event -> {
            if (blankContextMenu != null && blankContextMenu.isShowing()) {
                blankContextMenu.hide(); // 点击隐藏空白菜单
            }
        });
        imageAnchorPane.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                if (blankContextMenu != null && blankContextMenu.isShowing()) {
                    blankContextMenu.hide(); // 主区域左键隐藏菜单
                }
            }
        });
        imageScrollPane.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                if (blankContextMenu != null && blankContextMenu.isShowing()) {
                    blankContextMenu.hide(); // 滚动区域左键隐藏菜单
                }
            }
        });
    }

    private void setupDirTreeCellFactory() {//设置目录树节点工厂
        dirTreeView.setCellFactory(tv -> {
            TreeCell<String> cell = new TextFieldTreeCell<>();
            cell.setOnMouseClicked(event -> {
                if (event.getClickCount() == 1 && cell.getTreeItem() != null) {
                    TreeItem<String> item = cell.getTreeItem();
                    if (!item.getValue().equals("我的电脑")) {
                        item.setExpanded(!item.isExpanded());// 点击切换展开状态
                    }
                }
            });
            return cell;
        });
        dirTreeView.setPrefWidth(250);
    }

    private void setupDirTreeSelectionListener() {//监听目录树选择事件
        dirTreeView.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            if (newItem != null) {
                String fullPath = directoryTreeService.getFullPath(newItem);// 获取选中节点的完整路径
                File selectedDir = new File(fullPath);
                updateCurrentDirAndRefresh(selectedDir);// 更新当前目录并刷新图片预览区
            }
        });
    }

    private void updateCurrentDirAndRefresh(File dir) {// 更新当前目录并刷新图片预览区
        if (dir != null && dir.isDirectory()) {
            currentDir = dir;
            pathField.setText(dir.getAbsolutePath());// 初始化路径输入框显示当前目录
            loadImagesToFlowPane(dir);
            directoryTreeService.expandAndSelectInTree(dir.getAbsolutePath());// 同步更新目录树选择状态
        }
    }

    private void setupPathFieldListener() {//监听路径输入框事件
        if (currentDir != null) {
            pathField.setText(currentDir.getAbsolutePath());// 初始化路径输入框显示当前目录
        }
        pathField.setOnAction(event -> {
            String inputPath = pathField.getText();
            File file = new File(inputPath);// 根据输入路径创建File对象
            if (file.exists() && file.isDirectory()) {
                updateCurrentDirAndRefresh(file);// 如果路径有效，更新当前目录并刷新图片预览区
            } else {
                pathField.setStyle("-fx-background-color: #fff4f4; -fx-text-fill: #b92d2d; -fx-border-color: #efb4b4; -fx-border-width: 1; -fx-background-radius: 10; -fx-border-radius: 10;");
                pathField.setText("路径无效");
                PauseTransition pause = new PauseTransition(Duration.seconds(1));// 1秒后回退到原路径或清空
                pause.setOnFinished(e -> {
                    if (currentDir != null) pathField.setText(currentDir.getAbsolutePath());
                    pathField.setStyle(""); // 回退到 CSS 默认样式
                });
                pause.play();// 播放动画
            }
        });
    }

    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true); // 后台线程池
        thread.setName("Image Loader");
        return thread;
    });

    private final Map<String, Image> imageCache = Collections.synchronizedMap(
            new LinkedHashMap<>(100, 0.75f, true) { // LRU图片缓存，初始容量为100，填充到75%时会扩容，访问顺序
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
                    return size() > 200; // 超过200自动移除
                }
            });

    private final List<File> allFiles = new ArrayList<>(); // 当前目录下所有文件

    private static final int THUMB_SIZE = 120; // 缩略图尺寸
    private static final int FILE_NAME_MAX_LENGTH = 18; // 文件名最大显示长度

    private void loadImagesToFlowPane(File dir) { // 加载目录下文件到FlowPane
        allFiles.clear();
        selectedVBoxes.clear();
        vBoxToFile.clear();
        imageFlowPane.getChildren().clear();

        imageExecutor.submit(() -> {
            File[] files = dir.listFiles();// 获取目录下所有文件
            if (files == null || files.length == 0) {
                Platform.runLater(() -> {
                    if (currentDir != dir) return;
                    emptyTipLabel.setVisible(true);// 显示空目录提示
                    updateTipLabel();
                });
                return;
            }

            Arrays.sort(files, (f1, f2) -> {
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                return f1.getName().compareToIgnoreCase(f2.getName());// 目录优先，按名称排序
            });

            List<File> imageFiles = new ArrayList<>();
            List<File> nonImageFiles = new ArrayList<>();
            for (File file : files) {
                if (!file.isDirectory() && fileService.isImageFile(file)) {
                    imageFiles.add(file);// 分类图片文件和非图片文件
                } else {
                    nonImageFiles.add(file);
                }
            }

            Platform.runLater(() -> {
                if (currentDir != dir) return; // 如果用户已经切换到其他目录，抛弃当前加载结果
                emptyTipLabel.setVisible(false);// 显示空目录提示
                allFiles.addAll(Arrays.asList(files));// 更新当前目录文件列表

                Map<File, Integer> fileIndexMap = new HashMap<>();
                for (int i = 0; i < files.length; i++) {
                    fileIndexMap.put(files[i], i);// 记录文件在FlowPane中的索引位置
                    VBox placeholder = new VBox();
                    placeholder.setPrefSize(130, 150);
                    placeholder.setStyle("-fx-background-color: transparent;");
                    imageFlowPane.getChildren().add(placeholder);// 先添加占位符，后续加载完成后替换为实际VBox
                }

                for (File file : nonImageFiles) {
                    createVBoxAsync(file, vBox -> {
                        if (currentDir != dir) return; // 防止快速切换目录时覆盖新目录的元素
                        Integer index = fileIndexMap.get(file);// 获取文件对应的索引位置
                        if (index != null && index < imageFlowPane.getChildren().size()) {
                            imageFlowPane.getChildren().set(index, vBox);// 替换占位符为实际VBox
                            FlowPane.setMargin(vBox, new Insets(5));// 设置VBox之间的间距
                        }
                    });
                }

                for (File imageFile : imageFiles) {
                    createImageVBoxAsync(imageFile, vBox -> {
                        if (currentDir != dir) return; // 防止快速切换目录时覆盖新目录的元素
                        Integer index = fileIndexMap.get(imageFile);
                        if (index != null && index < imageFlowPane.getChildren().size()) {
                            imageFlowPane.getChildren().set(index, vBox);
                            FlowPane.setMargin(vBox, new Insets(5));
                        }
                    });
                }
                updateTipLabel();
            });
        });
    }

    private void setupVBoxContextMenu(VBox vBox) {//设置VBox的右键菜单逻辑
        vBox.setOnContextMenuRequested(event -> {
            if (imageContextMenu != null && imageContextMenu.isShowing()) {
                imageContextMenu.hide();
            }
            if (blankContextMenu != null && blankContextMenu.isShowing()) {
                blankContextMenu.hide();
            }
            int selCount = selectedVBoxes.size();
            boolean allImage = allSelectedAreImages();
            ContextMenu menu = vBoxFactory.buildContextMenu(selCount, allImage, this::deleteSelected, this::copySelected, this::renameSelected, this::pasteFiles);
            menu.show(vBox, event.getScreenX(), event.getScreenY());
            imageContextMenu = menu;
        });
    }

    private void createVBoxAsync(File file, Consumer<VBox> callback) { // 异步创建文件VBox
        vBoxFactory.createVBoxAsync(
                file,
                vBox -> {
                    setupVBoxContextMenu(vBox);
                    callback.accept(vBox);
                },
                selectedVBoxes,
                vBoxToFile,
                NORMAL_STYLE,
                SELECTED_STYLE,
                this::updateTipLabel,
                () -> {
                    currentDir = file;
                    loadImagesToFlowPane(file);
                    directoryTreeService.expandAndSelectInTree(file.getAbsolutePath());
                },
                this::deleteSelected,
                this::copySelected,
                this::renameSelected,
                this::pasteFiles
        );
    }

    private void createImageVBoxAsync(File file, Consumer<VBox> callback) { // 异步创建图片VBox
        vBoxFactory.createImageVBoxAsync(
                file,
                vBox -> Platform.runLater(() -> {
                    setupVBoxContextMenu(vBox);
                    if (callback != null) {
                        callback.accept(vBox);
                    }
                }),
                imageCache,
                imageExecutor,
                THUMB_SIZE,
                NORMAL_STYLE,
                SELECTED_STYLE,
                selectedVBoxes,
                vBoxToFile,
                this::updateTipLabel,
                () -> openSlideShowForImage(file),
                this::deleteSelected,
                this::copySelected,
                this::renameSelected,
                this::pasteFiles
        );
    }

    private void initFlowPaneHint() { // 初始化FlowPane快捷入口
        File[] roots = File.listRoots();// 获取系统根目录（如C:\、D:\等）
        if (roots != null) {
            for (File root : roots) {
                createShortcutVBox(root, root.getAbsolutePath());// 为每个根目录创建快捷入口VBox
            }
        }

        String userHome = System.getProperty("user.home");// 获取用户主目录
        File picturesDir = new File(userHome, "Pictures");// 构造“我的图片”目录路径
        if (picturesDir.exists() && picturesDir.isDirectory()) {
            createShortcutVBox(picturesDir, "我的图片");
        }

    }

    private void createShortcutVBox(File targetDir, String displayName) { // 创建快捷入口VBox
        vBoxFactory.createShortcutVBox(
                displayName,
                THUMB_SIZE,
                NORMAL_STYLE,
                SELECTED_STYLE,
                selectedVBoxes,
                imageFlowPane,
                this::updateTipLabel,
                () -> {
                    currentDir = targetDir;
                    loadImagesToFlowPane(targetDir);
                    if (dirTreeView.getRoot() != null) {
                        directoryTreeService.expandAndSelectInTree(targetDir.getAbsolutePath());
                    }
                }
        );
    }

    @FXML
    private void openSlideShow() {
        List<String> imagePaths = getImagePathsInCurrentDir();// 获取当前目录下所有图片路径
        if (imagePaths.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "未选择目录", "请先在左侧选择包含图片的文件夹");
            return;
        }
        showSlideShowWindow(imagePaths, 0);
    }

    @FXML
    private void clickBlank(javafx.scene.input.MouseEvent event) {
        if (event.getTarget() != imageFlowPane) {// 只有点击FlowPane空白区域才触发
            return;
        }
        if (event.getButton() == MouseButton.PRIMARY) {
            if (blankContextMenu != null && blankContextMenu.isShowing()) {
                blankContextMenu.hide();// 点击空白区域左键隐藏菜单
            }
            selectedVBoxes.forEach(v -> v.setStyle(NORMAL_STYLE));
            selectedVBoxes.clear();// 清空选中状态
            updateTipLabel();
        } else if (event.getButton() == MouseButton.SECONDARY) {
            if (blankContextMenu != null && blankContextMenu.isShowing()) {
                blankContextMenu.hide();
            }
            blankContextMenu = vBoxFactory.buildContextMenu(0, this::deleteSelected, this::copySelected, this::renameSelected, this::pasteFiles);
            blankContextMenu.show(imageFlowPane, event.getScreenX(), event.getScreenY());// 点击空白区域右键显示菜单
            event.consume();
        }
    }

    @FXML
    public void onBack() {// 返回上一级目录
        if (currentDir != null) {
            File parent = currentDir.getParentFile();
            if (parent != null) {
                dirHistoryStack.push(currentDir); // 将当前目录压入历史栈
                currentDir = parent;
                loadImagesToFlowPane(currentDir);
                directoryTreeService.expandAndSelectInTree(currentDir.getAbsolutePath());
            } else {
                currentDir = null;
                allFiles.clear();
                selectedVBoxes.clear();
                vBoxToFile.clear();
                Platform.runLater(() -> {
                    imageFlowPane.getChildren().clear();
                    initFlowPaneHint();
                    pathField.setText(""); // 清空路径输入框
                    updateTipLabel();
                });
                dirTreeView.getSelectionModel().clearSelection();// 取消目录树选择状态
            }
        }
    }

    @FXML
    public void onUndo() {// 撤销返回操作
        if (!dirHistoryStack.isEmpty()) {
            currentDir = dirHistoryStack.pop();
            loadImagesToFlowPane(currentDir);
            directoryTreeService.expandAndSelectInTree(currentDir.getAbsolutePath());
        } else {
            showAlert(Alert.AlertType.INFORMATION, "提示", "无可撤销的操作");
        }
    }

    private boolean isImageFile(File file) {
        return fileService.isImageFile(file); // 判断是否为图片文件
    }

    private boolean allSelectedAreImages() {// 判断选中项是否全部为图片
        if (selectedVBoxes.isEmpty()) return false;
        for (VBox vbox : selectedVBoxes) {
            File file = vBoxToFile.get(vbox);
            if (file == null || !file.isFile() || !isImageFile(file)) return false; // 任一不是图片则返回false
        }
        return true;
    }

    private void updateTipLabel() { // 更新底部提示信息
        if (currentDir == null) {
            tipLabel.setText("Welcome to Image Manager");
            return;
        }
        long imageCount = allFiles.stream().filter(this::isImageFile).count();// 统计图片数量
        long totalSize = allFiles.stream().filter(File::isFile).mapToLong(File::length).sum();// 计算总大小
        String sizeStr = fileService.formatSize(totalSize);// 格式化总大小
        String selectedStr = selectedVBoxes.isEmpty() ? "" : " | 选中: " + selectedVBoxes.size();// 选中数量提示
        long selectedImageSize = selectedVBoxes.stream()
                .map(vBoxToFile::get)
                .filter(Objects::nonNull)
                .filter(this::isImageFile)
                .mapToLong(File::length)
                .sum();
        String selectedSizeStr = (selectedVBoxes.isEmpty() || selectedImageSize == 0) ? "" : " | 选中图片总大小: " + fileService.formatSize(selectedImageSize);
        tipLabel.setText("目录: " + currentDir.getName() + " | 图片数量: " + imageCount + " | 总大小: " + sizeStr + selectedStr + selectedSizeStr);
    }

    private void openSlideShowForImage(File imageFile) { // 打开指定图片的幻灯片
        List<String> imagePaths = getImagePathsInCurrentDir();
        if (imagePaths.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "未找到图片", "当前目录中没有可播放的图片");
            return;
        }
        int index = imagePaths.indexOf(imageFile.getAbsolutePath());// 获取当前图片在列表中的索引
        showSlideShowWindow(imagePaths, Math.max(index, 0));// 打开幻灯片窗口，从当前图片开始播放
    }

    private List<String> getImagePathsInCurrentDir() { // 获取当前目录所有图片路径
        List<String> imagePaths = new ArrayList<>();
        if (currentDir != null) {
            File[] files = currentDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && isImageFile(file)) {
                        imagePaths.add(file.getAbsolutePath());// 只添加图片文件路径
                    }
                }
            }
        }
        Collections.sort(imagePaths);// 按路径排序，保证幻灯片顺序稳定
        return imagePaths;
    }

    private void showSlideShowWindow(List<String> imagePaths, int startIndex) { // 打开幻灯片窗口
        try {
            URL fxmlUrl = getClass().getResource("/slideShow.fxml");
            FXMLLoader loader = new FXMLLoader(fxmlUrl);// 加载幻灯片FXML界面
            Parent slideRoot = loader.load();// 加载FXML文件
            SlideShowController slideController = loader.getController();// 获取幻灯片控制器
            slideController.setImagePaths(imagePaths);// 设置图片路径列表
            if (startIndex > 0) {
                slideController.setCurrentIndex(startIndex);
            }
            Stage slideStage = new Stage();
            slideStage.setTitle("幻灯片播放");
            slideStage.setScene(new javafx.scene.Scene(slideRoot, 1000, 700));
            slideStage.setMinWidth(420);
            slideStage.setMinHeight(340);
            slideStage.initOwner(dirTreeView.getScene().getWindow());
            slideStage.centerOnScreen();
            slideStage.show();
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "打开幻灯片失败", e.getMessage());
            e.printStackTrace();
        }
    }

    private void deleteSelected() { // 删除选中项
        if (selectedVBoxes.isEmpty()) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "确认删除选中的文件？", ButtonType.YES, ButtonType.NO);
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                Set<VBox> toDelete = new HashSet<>(selectedVBoxes);
                fileService.deleteSelected(toDelete, vBoxToFile, allFiles, deletedVBoxes ->
                        Platform.runLater(() -> {
                            imageFlowPane.getChildren().removeAll(deletedVBoxes);// 从FlowPane移除被删除的VBox
                            selectedVBoxes.removeAll(new HashSet<>(deletedVBoxes));// 从选中集合中移除被删除的VBox
                            updateTipLabel();
                        })
                );
            }
        });
    }

    @FXML
    public void copySelected() { // 复制选中项
        fileService.copySelected(selectedVBoxes, vBoxToFile, copiedFiles);
    }

    private void renameSelected() { // 重命名选中项
        if (selectedVBoxes.isEmpty()) return;
        if (selectedVBoxes.size() > 1) {
            showAlert(Alert.AlertType.INFORMATION, "提示", "多选时不支持重命名");
            return;
        }

        VBox vBox = selectedVBoxes.iterator().next();// 获取唯一选中的VBox
        File file = vBoxToFile.get(vBox);
        if (file == null) return;
        TextInputDialog dialog = new TextInputDialog(file.getName());
        dialog.setTitle("重命名");
        dialog.setHeaderText("输入新文件名");
        dialog.showAndWait().ifPresent(newName -> {// 获取用户输入的新文件名
            if (!newName.trim().isEmpty()) {// 如果输入不为空，执行重命名
                String ext = "";
                int dotIndex = file.getName().lastIndexOf('.');// 保留原文件扩展名
                if (dotIndex > 0) {
                    ext = file.getName().substring(dotIndex);// 包含点号的扩展名
                }
                String newNameWithExt;
                if (newName.contains(".")) {
                    newNameWithExt = newName;// 如果用户输入的新名字已经包含扩展名，则直接使用
                } else {
                    newNameWithExt = newName + ext;// 否则在新名字后面添加原扩展名
                }
                File newFile = new File(file.getParent(), newNameWithExt);
                if (fileService.renameFile(file, newNameWithExt)) {
                    vBoxToFile.put(vBox, newFile);
                    ((Label) vBox.getChildren().get(1)).setText(truncateFileName(newNameWithExt));// 更新VBox中文件名标签显示
                    allFiles.set(allFiles.indexOf(file), newFile);// 更新当前目录文件列表中的File对象
                } else {
                    showAlert(Alert.AlertType.ERROR, "重命名失败", "无法重命名文件");
                }
            }
        });
    }

    private void pasteFiles() { // 粘贴文件
        List<File> pasted = fileService.pasteFiles(copiedFiles, currentDir);
        loadImagesToFlowPane(currentDir); // 粘贴后整体刷新图片预览区，保证新图片立即可见
        Platform.runLater(() -> {
            selectedVBoxes.clear();
            vBoxToFile.forEach((vbox, file) -> {
                if (pasted.contains(file)) {
                    vbox.setStyle(SELECTED_STYLE);
                    selectedVBoxes.add(vbox);
                } else {
                    vbox.setStyle(NORMAL_STYLE);
                }
            });
            updateTipLabel();
        });
    }

    private String truncateFileName(String name) { // 文件名过长时截断
        if (name == null || name.length() <= FILE_NAME_MAX_LENGTH) return name;// 如果文件名长度不超过最大限制，直接返回原名
        int keep = FILE_NAME_MAX_LENGTH - 3;// 计算需要保留的字符数，减去省略号占用的3个字符
        int prefix = keep / 2;// 保留前半部分字符数
        int suffix = keep - prefix;// 保留后半部分字符数
        return name.substring(0, prefix) + "..." + name.substring(name.length() - suffix);// 返回截断后的文件名，格式为“前缀...后缀”
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        AlterUtil.showAlert(type, title, content, dirTreeView.getScene().getWindow()); // 弹窗提示
    }

    public void shutdown() {
        if (directoryTreeService != null) {
            directoryTreeService.shutdown(); // 关闭目录树相关线程池
        }
        imageExecutor.shutdown(); // 关闭图片加载线程池
    }
}
