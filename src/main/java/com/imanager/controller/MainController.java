package com.imanager.controller;

import com.imanager.service.DirectoryTreeService;
import com.imanager.service.FileService;
import com.imanager.util.AlterUtil;
import com.imanager.util.VBoxFactory;
import javafx.application.Platform;
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
import javafx.stage.Stage;
import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class MainController {

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
                Platform.runLater(() -> {
                    try { Thread.sleep(1000); } catch (Exception ignored) {}
                    if (currentDir != null) pathField.setText(currentDir.getAbsolutePath());
                    pathField.setStyle("-fx-background-color: #ffffff;");
                });
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
                callback,
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
                callback,
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
    private void clickBlank() {
        selectedVBoxes.forEach(v -> v.setStyle(NORMAL_STYLE));
        selectedVBoxes.clear();
        updateTipLabel();
    }

    // 返回上一级目录
    @FXML
    public void onBack() {
        if (currentDir != null && currentDir.getParentFile() != null) {
            dirHistoryStack.push(currentDir); // 将当前目录压入历史栈
            currentDir = currentDir.getParentFile();
            loadImagesToFlowPane(currentDir);
            directoryTreeService.expandAndSelectInTree(currentDir.getAbsolutePath());
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
                fileService.deleteSelected(selectedVBoxes, vBoxToFile, allFiles, deletedVBoxes ->
                        Platform.runLater(() -> imageFlowPane.getChildren().removeAll(deletedVBoxes))
                );
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
        if (selectedVBoxes.size() == 1) {
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
                    // 新增：如果用户输入已带扩展名，则不再追加
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
        } else {
            // 多选：批量重命名
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.setTitle("批量重命名");
            dialog.setHeaderText("输入批量重命名参数");

            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(20, 150, 10, 10));

            TextField prefixField = new TextField();
            prefixField.setPromptText("名称前缀");
            TextField startNumField = new TextField("1");
            startNumField.setPromptText("起始编号");
            TextField digitsField = new TextField("4");
            digitsField.setPromptText("编号位数");

            grid.add(new Label("前缀:"), 0, 0);
            grid.add(prefixField, 1, 0);
            grid.add(new Label("起始编号:"), 0, 1);
            grid.add(startNumField, 1, 1);
            grid.add(new Label("位数:"), 0, 2);
            grid.add(digitsField, 1, 2);

            dialog.getDialogPane().setContent(grid);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

            dialog.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    try {
                        String prefix = prefixField.getText().trim();
                        int startNum = Integer.parseInt(startNumField.getText().trim());
                        int digits = Integer.parseInt(digitsField.getText().trim());
                        String format = "%0" + digits + "d";

                        List<VBox> list = new ArrayList<>(selectedVBoxes);
                        for (int i = 0; i < list.size(); i++) {
                            VBox vBox = list.get(i);
                            File file = vBoxToFile.get(vBox);
                            if (file != null) {
                                String ext = "";
                                int dotIndex = file.getName().lastIndexOf('.');
                                if (dotIndex > 0) {
                                    ext = file.getName().substring(dotIndex);
                                }
                                String newName = prefix + String.format(format, startNum + i);
                                // 新增：如果原文件有扩展名且 newName 没有扩展名，则追加
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
                                }
                            }
                        }
                    } catch (NumberFormatException e) {
                        showAlert(Alert.AlertType.ERROR, "输入错误", "起始编号和位数必须是数字");
                    }
                }
            });
        }
    }

    // 粘贴文件
    private void pasteFiles() {
        fileService.pasteFiles(copiedFiles, currentDir);
        loadImagesToFlowPane(currentDir); // 粘贴后整体刷新图片预览区，保证新图片立即可见
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

