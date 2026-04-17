package com.imanager.controller;

import com.imanager.service.DirectoryTreeService;
import com.imanager.service.FileService;
import com.imanager.util.AlterUtil;
import com.imanager.util.VBoxFactory;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTreeCell;
import javafx.scene.layout.FlowPane;
import javafx.scene.image.Image;
import javafx.scene.layout.GridPane;
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
    // 空白处右键菜单引用
    private ContextMenu blankContextMenu = null;

    // 与 FXML 中的 fx:id 绑定
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
    private File currentDir;//记录当前选中的目录
    private DirectoryTreeService directoryTreeService;
    private final FileService fileService = new FileService();
    private final VBoxFactory vBoxFactory = new VBoxFactory();
    private final Set<VBox> selectedVBoxes = new HashSet<>();
    private final Map<VBox, File> vBoxToFile = new HashMap<>();
    private final List<File> copiedFiles = new ArrayList<>();
    private static final String NORMAL_STYLE = "-fx-alignment: center; -fx-border-color: #cccccc; -fx-border-width: 2px; -fx-background-color: #fff;";
    private static final String SELECTED_STYLE = "-fx-border-color: #2196f3; -fx-border-width: 2px; -fx-background-color: #e3f2fd; -fx-alignment: center;";


    // 目录历史栈，用于返回和撤销操作
    private final Stack<File> dirHistoryStack = new Stack<>();

    //程序启动后初始化
    @FXML
    public void initialize() {
        directoryTreeService = new DirectoryTreeService(dirTreeView);
        setupDirTreeCellFactory();
        setupDirTreeSelectionListener();
        setupPathFieldListener();
        // 初始化FlowPane默认提示
        initFlowPaneHint();
        //初始化目录树(加载本地文件系统)
        directoryTreeService.initDirectoryTree();
        // 强制 FlowPane 的宽度等于 ScrollPane 视口的宽度
        imageScrollPane.viewportBoundsProperty().addListener((obs, oldBounds, newBounds) -> imageFlowPane.setPrefWidth(newBounds.getWidth()));

        // 空白处右键菜单：仅粘贴可用，其他项禁用。
        imageFlowPane.setOnContextMenuRequested(event -> {
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

        // 点击空白处关闭空白右键菜单
        imageFlowPane.setOnMousePressed(event -> {
            if (blankContextMenu != null && blankContextMenu.isShowing()) {
                blankContextMenu.hide();
            }
        });
    }

    // 统一处理目录切换和图片刷新
    private void updateCurrentDirAndRefresh(File dir) {
        if (dir != null && dir.isDirectory()) {
            currentDir = dir;
            pathField.setText(dir.getAbsolutePath());
            loadImagesToFlowPane(dir);
            directoryTreeService.expandAndSelectInTree(dir.getAbsolutePath());
        }
    }

    private void setupDirTreeCellFactory() {
        dirTreeView.setCellFactory(tv -> {
            TreeCell<String> cell = new TextFieldTreeCell<>();
            cell.setOnMouseClicked(event -> {
                if (event.getClickCount() == 1 && cell.getTreeItem() != null) {
                    TreeItem<String> item = cell.getTreeItem();
                    if (!item.getValue().equals("我的电脑")) {
                        item.setExpanded(!item.isExpanded());
                    }
                }
            });
            return cell;
        });
        dirTreeView.setPrefWidth(250);
    }

    private void setupDirTreeSelectionListener() {
        dirTreeView.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            if (newItem != null) {
                String fullPath = directoryTreeService.getFullPath(newItem);
                File selectedDir = new File(fullPath);
                updateCurrentDirAndRefresh(selectedDir);
            }
        });
    }

    private void setupPathFieldListener() {
        if (currentDir != null) {
            pathField.setText(currentDir.getAbsolutePath());
        }
        pathField.setOnAction(event -> {
            String inputPath = pathField.getText();
            File file = new File(inputPath);
            if (file.exists() && file.isDirectory()) {
                updateCurrentDirAndRefresh(file);
            } else {
                pathField.setStyle("-fx-background-color: #ffe6e6;");
                pathField.setText("路径无效");
                PauseTransition pause = new PauseTransition(Duration.seconds(1));
                pause.setOnFinished(e -> {
                    if (currentDir != null) pathField.setText(currentDir.getAbsolutePath());
                    pathField.setStyle("-fx-background-color: #ffffff;");
                });
                pause.play();
            }
        });
    }

    //图片加载线程池
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.setName("Image Loader");
        return thread;
    });

    //图片缓存(LRU策略，最多缓存200张，避免重复加载)
    private final Map<String, Image> imageCache = Collections.synchronizedMap(
            new LinkedHashMap<>(100, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
                    return size() > 200;
                }
            });

    private final List<File> allFiles = new ArrayList<>();

    //统一缩略图尺寸
    private static final int THUMB_SIZE = 120;
    private static final int FILE_NAME_MAX_LENGTH = 18;

    // 加载图片和文件夹到FlowPane
    private void loadImagesToFlowPane(File dir) {
        allFiles.clear();
        selectedVBoxes.clear();
        vBoxToFile.clear();
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            Platform.runLater(() -> {
                imageFlowPane.getChildren().clear();
                Label emptyLabel = new Label("当前目录无文件");
                emptyLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 14px;");
                imageFlowPane.getChildren().add(emptyLabel);
                FlowPane.setMargin(emptyLabel, new Insets(20, 0, 0, 0));
            });
            return;
        }
        Arrays.sort(files, (f1, f2) -> {
            if (f1.isDirectory() && !f2.isDirectory()) return -1;
            if (!f1.isDirectory() && f2.isDirectory()) return 1;
            return f1.getName().compareToIgnoreCase(f2.getName());
        });
        allFiles.addAll(Arrays.asList(files));
        Platform.runLater(() -> imageFlowPane.getChildren().clear());

        List<File> imageFiles = new ArrayList<>();
        for (File file : allFiles) {
            if (!file.isDirectory() && fileService.isImageFile(file)) {
                imageFiles.add(file);
            } else {
                createVBoxAsync(file, vBox -> Platform.runLater(() -> {
                    imageFlowPane.getChildren().add(vBox);
                    FlowPane.setMargin(vBox, new Insets(5));
                }));
            }
        }

        for (File imageFile : imageFiles) {
            createImageVBoxAsync(imageFile, vBox -> Platform.runLater(() -> {
                imageFlowPane.getChildren().add(vBox);
                FlowPane.setMargin(vBox, new Insets(5));
            }));
        }
        updateTipLabel();
    }

    //异步创建VBox（图标+名称+点击事件）
    private void createVBoxAsync(File file, Consumer<VBox> callback) {
        vBoxFactory.createVBoxAsync(
                file,
                vBox -> {
                    vBox.setOnContextMenuRequested(event -> {
                        int selCount = selectedVBoxes.size();
                        ContextMenu menu = vBoxFactory.buildContextMenu(selCount, this::deleteSelected, this::copySelected, this::renameSelected, this::pasteFiles);
                        menu.show(vBox, event.getScreenX(), event.getScreenY());
                    });
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
                this::pasteFiles,
                this::deleteSelected,
                this::copySelected,
                this::renameSelected
        );
    }

    //异步创建图片VBox（后台加载完成后显示）
    private void createImageVBoxAsync(File file, Consumer<VBox> callback) {
        vBoxFactory.createImageVBoxAsync(
                file,
                vBox -> Platform.runLater(() -> {
                    vBox.setOnContextMenuRequested(event -> {
                        int selCount = selectedVBoxes.size();
                        ContextMenu menu = vBoxFactory.buildContextMenu(selCount, this::deleteSelected, this::copySelected, this::renameSelected, this::pasteFiles);
                        menu.show(vBox, event.getScreenX(), event.getScreenY());
                    });
                    imageFlowPane.getChildren().add(vBox);
                    FlowPane.setMargin(vBox, new Insets(5));
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

    //初始化FlowPane默认提示
    private void initFlowPaneHint() {
        // 添加快捷入口
        File[] roots = File.listRoots();
        if (roots != null) {
            for (File root : roots) {
                createShortcutVBox(root, root.getAbsolutePath());
            }
        }

        // 添加 Pictures 快捷方式
        String userHome = System.getProperty("user.home");
        File picturesDir = new File(userHome, "Pictures");
        if (picturesDir.exists() && picturesDir.isDirectory()) {
            createShortcutVBox(picturesDir, "我的图片");
        }

    }

    //创建快捷入口VBox
    private void createShortcutVBox(File targetDir, String displayName) {
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

    //点击Play按钮，打开幻灯片界面
    @FXML
    private void openSlideShow() {
        List<String> imagePaths = getImagePathsInCurrentDir();
        if (imagePaths.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "未选择目录", "请先在左侧选择包含图片的文件夹");
            return;
        }
        showSlideShowWindow(imagePaths, 0);
    }

    @FXML
    private void clickBlank(javafx.scene.input.MouseEvent event) {
        if (event.getTarget() != imageFlowPane || event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        selectedVBoxes.forEach(v -> v.setStyle(NORMAL_STYLE));
        selectedVBoxes.clear();
        updateTipLabel();
    }

    // 返回上一级目录
    @FXML
    public void onBack() {
        if (currentDir != null) {
            File parent = currentDir.getParentFile();
            if (parent != null) {
                dirHistoryStack.push(currentDir); // 将当前目录压入历史栈
                currentDir = parent;
                loadImagesToFlowPane(currentDir);
                directoryTreeService.expandAndSelectInTree(currentDir.getAbsolutePath());
            } else {
                // 已经是根目录，回到初始界面
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
                // 取消目录树选中
                dirTreeView.getSelectionModel().clearSelection();
            }
        }
    }

    // 撤销操作（如无撤销功能可先留空或弹窗提示）
    @FXML
    public void onUndo() {
        if (!dirHistoryStack.isEmpty()) {
            currentDir = dirHistoryStack.pop();
            loadImagesToFlowPane(currentDir);
            directoryTreeService.expandAndSelectInTree(currentDir.getAbsolutePath());
        } else {
            showAlert(Alert.AlertType.INFORMATION, "提示", "无可撤销的操作");
        }
    }

    // 判断是否为图片文件
    private boolean isImageFile(File file) {
        return fileService.isImageFile(file);
    }

    // 更新提示信息
    private void updateTipLabel() {
        if (currentDir == null) {
            tipLabel.setText("Welcome to Image Manager");
            return;
        }
        long imageCount = allFiles.stream().filter(this::isImageFile).count();
        long totalSize = allFiles.stream().filter(File::isFile).mapToLong(File::length).sum();
        String sizeStr = fileService.formatSize(totalSize);
        String selectedStr = selectedVBoxes.isEmpty() ? "" : " | 选中: " + selectedVBoxes.size();
        // 新增：选中图片总大小
        long selectedImageSize = selectedVBoxes.stream()
                .map(vBoxToFile::get)
                .filter(Objects::nonNull)
                .filter(this::isImageFile)
                .mapToLong(File::length)
                .sum();
        String selectedSizeStr = (selectedVBoxes.isEmpty() || selectedImageSize == 0) ? "" : " | 选中图片总大小: " + fileService.formatSize(selectedImageSize);
        tipLabel.setText("目录: " + currentDir.getName() + " | 图片数量: " + imageCount + " | 总大小: " + sizeStr + selectedStr + selectedSizeStr);
    }

    // 双击图片打开幻灯片
    private void openSlideShowForImage(File imageFile) {
        List<String> imagePaths = getImagePathsInCurrentDir();
        if (imagePaths.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "未找到图片", "当前目录中没有可播放的图片");
            return;
        }
        int index = imagePaths.indexOf(imageFile.getAbsolutePath());
        showSlideShowWindow(imagePaths, Math.max(index, 0));
    }

    // 抽取：获取当前目录下所有图片路径
    private List<String> getImagePathsInCurrentDir() {
        List<String> imagePaths = new ArrayList<>();
        if (currentDir != null) {
            File[] files = currentDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && isImageFile(file)) {
                        imagePaths.add(file.getAbsolutePath());
                    }
                }
            }
        }
        Collections.sort(imagePaths);
        return imagePaths;
    }

    // 抽取：统一打开幻灯片窗口
    private void showSlideShowWindow(List<String> imagePaths, int startIndex) {
        try {
            URL fxmlUrl = getClass().getResource("/slideShow.fxml");
            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            Parent slideRoot = loader.load();
            SlideShowController slideController = loader.getController();
            slideController.setImagePaths(imagePaths);
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

    // 删除选中文件
    private void deleteSelected() {
        if (selectedVBoxes.isEmpty()) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "确认删除选中的文件？", ButtonType.YES, ButtonType.NO);
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                // 复制一份，避免遍历时修改集合
                Set<VBox> toDelete = new HashSet<>(selectedVBoxes);
                fileService.deleteSelected(toDelete, vBoxToFile, allFiles, deletedVBoxes ->
                        Platform.runLater(() -> imageFlowPane.getChildren().removeAll(deletedVBoxes))
                );
                selectedVBoxes.removeAll(toDelete);
                updateTipLabel();
            }
        });
    }

    // 复制选中文件
    @FXML
    public void copySelected() {
        fileService.copySelected(selectedVBoxes, vBoxToFile, copiedFiles);
    }

    // 重命名选中文件
    private void renameSelected() {
        if (selectedVBoxes.isEmpty()) return;
        if (selectedVBoxes.size() > 1) {
            showAlert(Alert.AlertType.INFORMATION, "提示", "多选时不支持重命名");
            return;
        }

        // 单选：输入新文件名
        VBox vBox = selectedVBoxes.iterator().next();
        File file = vBoxToFile.get(vBox);
        if (file == null) return;
        TextInputDialog dialog = new TextInputDialog(file.getName());
        dialog.setTitle("重命名");
        dialog.setHeaderText("输入新文件名");
        dialog.showAndWait().ifPresent(newName -> {
            if (!newName.trim().isEmpty()) {
                String ext = "";
                int dotIndex = file.getName().lastIndexOf('.');
                if (dotIndex > 0) {
                    ext = file.getName().substring(dotIndex);
                }
                String newNameWithExt;
                if (newName.contains(".")) {
                    newNameWithExt = newName;
                } else {
                    newNameWithExt = newName + ext;
                }
                File newFile = new File(file.getParent(), newNameWithExt);
                if (fileService.renameFile(file, newNameWithExt)) {
                    vBoxToFile.put(vBox, newFile);
                    ((Label) vBox.getChildren().get(1)).setText(truncateFileName(newNameWithExt));
                    allFiles.set(allFiles.indexOf(file), newFile);
                } else {
                    showAlert(Alert.AlertType.ERROR, "重命名失败", "无法重命名文件");
                }
            }
        });
    }

    // 粘贴文件
    private void pasteFiles() {
        List<File> pasted = fileService.pasteFiles(copiedFiles, currentDir);
        loadImagesToFlowPane(currentDir); // 粘贴后整体刷新图片预览区，保证新图片立即可见
        // 粘贴后高亮新图片
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

    //文件名过长省略工具方法
    private String truncateFileName(String name) {
        if (name == null || name.length() <= FILE_NAME_MAX_LENGTH) return name;
        int keep = FILE_NAME_MAX_LENGTH - 3;
        int prefix = keep / 2;
        int suffix = keep - prefix;
        return name.substring(0, prefix) + "..." + name.substring(name.length() - suffix);
    }

    //辅助方法：弹出提示框
    private void showAlert(Alert.AlertType type, String title, String content) {
        AlterUtil.showAlert(type, title, content, dirTreeView.getScene().getWindow());
    }

    //关闭所有线程池
    public void shutdown() {
        if (directoryTreeService != null) {
            directoryTreeService.shutdown();
        }
        imageExecutor.shutdown();
    }
}

