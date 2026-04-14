package com.imanager.controller;


import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTreeCell;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.image.Image;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.scene.shape.Rectangle;
import javafx.scene.paint.Color;
import javafx.geometry.Bounds;
import javafx.scene.Node;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
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

    private File currentDir;//记录当前选中的目录

    //目录节点加载状态缓存
    private final Map<TreeItem<String>, String> treeItemStatus = new HashMap<>();
    // 状态常量
    private static final String STATUS_UNLOADED = "unloaded";   // 未加载
    private static final String STATUS_LOADING = "loading";     // 加载中
    private static final String STATUS_LOADED = "loaded";       // 已加载

    // 新增字段
    private Set<VBox> selectedVBoxes = new HashSet<>();
    private Map<VBox, File> vBoxToFile = new HashMap<>();
    private List<File> copiedFiles = new ArrayList<>();
    private static final String NORMAL_STYLE = "-fx-alignment: center; -fx-border-color: #cccccc; -fx-border-width: 2px; -fx-background-color: #fff;";
    private static final String SELECTED_STYLE = "-fx-border-color: #2196f3; -fx-border-width: 2px; -fx-background-color: #e3f2fd; -fx-alignment: center;";


    //程序启动后初始化
    @FXML
    public void initialize() {
        //自定义CellFactory，让点击节点文本也能触发展开/折叠
        dirTreeView.setCellFactory(tv -> {
            TreeCell<String> cell = new TextFieldTreeCell<>();
            //点击节点文本也能触发展开/折叠
            cell.setOnMouseClicked(event -> {
                if (event.getClickCount() == 1 && cell.getTreeItem() != null) {
                    TreeItem<String> item = cell.getTreeItem();
                    //仅切换非根节点(避免点击"我的电脑"触发展开)
                    if (!item.getValue().equals("我的电脑")) {
                        item.setExpanded(!item.isExpanded());
                    }
                }
            });
            return cell;
        });
        dirTreeView.setPrefWidth(250); // 固定目录树宽度，避免布局抖动

        //选中目录触发图片加载
        dirTreeView.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            if (newItem != null) {
                // 拼接完整路径：从根节点逐级拼接
                String fullPath = getFullPath(newItem);
                File selectedDir = new File(fullPath);
                if (selectedDir != null && selectedDir.isDirectory()) {
                    currentDir = selectedDir;
                    loadImagesToFlowPane(selectedDir);
                }
            }
        });

        // 初始化FlowPane默认提示
        initFlowPaneHint();
        //初始化目录树(加载本地文件系统)
        initDirectoryTree();

        // 强制 FlowPane 的宽度等于 ScrollPane 视口的宽度
        imageScrollPane.viewportBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            imageFlowPane.setPrefWidth(newBounds.getWidth());
        });
    }

    //目录加载线程池
    private final ExecutorService dirExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);//线程设置为守护线程，程序退出时自动关闭
        thread.setName("Directory Loader");
        return thread;
    });

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

    private List<File> allFiles = new ArrayList<>();
    private int batchSize = 50;
    private int currentLoadedCount = 0;
    private boolean isLoading = false;

    //统一缩略图尺寸
    private static final int THUMB_SIZE = 120;

    //初始化全盘符目录树（支持扫描全硬盘）
    private void initDirectoryTree() {
        //创建根节点(显示“我的电脑”)
        TreeItem<String> computerRoot = new TreeItem<>("我的电脑");
        computerRoot.setExpanded(true);
        dirTreeView.setRoot(computerRoot);
        dirTreeView.setShowRoot(true);//显示根节点

        // 获取所有磁盘盘符（C:\、D:\、E:\等）
        File[] roots = File.listRoots();
        if (roots == null) roots = new File[0];

        //为每个盘符创建节点
        for (File root : roots) {
            TreeItem<String> driveItem = new TreeItem<>(root.getAbsolutePath());
            driveItem.setExpanded(false);//盘符默认关闭，避免一次性加载系统目录
            //初始化状态：未加载
            treeItemStatus.put(driveItem, STATUS_UNLOADED);
            computerRoot.getChildren().add(driveItem);

            //盘符展开是加载子目录
            driveItem.expandedProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal && STATUS_UNLOADED.equals(treeItemStatus.get(driveItem))) {
                    loadChildrenAsync(driveItem, root, 1);
                }
            });
        }
    }

    //异步加载子目录
    private void loadChildrenAsync(TreeItem<String> parentItem, File parentFile, int depth) {
        //校验状态：已加载/加载中则直接返回
        String status = treeItemStatus.getOrDefault(parentItem, STATUS_UNLOADED);
        if (STATUS_LOADING.equals(status) || STATUS_LOADED.equals(status)) {
            return;
        }

        //标记为加载中
        treeItemStatus.put(parentItem, STATUS_LOADING);

        if (depth > 5) {
            treeItemStatus.put(parentItem, STATUS_LOADED);
            return;
        }

        System.out.println("正在加载目录：" + parentFile.getAbsolutePath());

        //仅在首次触发时设置展开，避免重复修改
        if (!parentItem.isExpanded()) {
            parentItem.setExpanded(true);
        }

        //后台线程读取目录
        dirExecutor.submit(() -> {
            File[] childFiles = parentFile.listFiles(File::isDirectory);
            if (childFiles == null) {
                childFiles = new File[0];//权限不足时返回空数组
                System.out.println("❌ 读取目录失败（权限不足）：" + parentFile.getAbsolutePath());
                Platform.runLater(() -> {
                    TreeItem<String> emptyItem = new TreeItem<>("无访问权限");
                    parentItem.getChildren().add(emptyItem);
                });
                treeItemStatus.put(parentItem, STATUS_LOADED);
                return;
            }

            //过滤掉系统目录
            List<File> filteredFiles = Arrays.stream(childFiles)
                    .filter(file -> {
                        String name = file.getName();
                        return !name.equals("System Volume Information")
                                && !name.equals("$Recycle.Bin")
                                && !name.equals("Windows") //跳过Windows系统目录
                                && !name.equals("Program Files") //跳过Program Files
                                && !file.isHidden(); //跳过所有隐藏目录（注释掉则显示隐藏目录）
                    })
                    //排序：让目录名按字母顺序显示
                    .sorted((f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()))
                    .toList();

            // 打印日志，验证子目录加载数量
            System.out.println("🔍 加载 " + parentFile.getName() + " 的子目录数量：" + filteredFiles.size());


            //UI线程更新目录树
            Platform.runLater(() -> {
                // 空目录时显示“无子目录”提示，避免空白
                if (filteredFiles.isEmpty()) {
                    TreeItem<String> emptyItem = new TreeItem<>("无子目录");
                    parentItem.getChildren().add(emptyItem);
                } else {
                    // 正常加载子目录（保留原有逻辑）
                    for (File childFile : filteredFiles) {
                        TreeItem<String> childItem = new TreeItem<>(childFile.getName());
                        // 初始化子节点状态为未加载
                        treeItemStatus.put(childItem, STATUS_UNLOADED);
                        // 子节点展开监听
                        childItem.expandedProperty().addListener((obs, oldVal, newVal) -> {
                            if (newVal && STATUS_UNLOADED.equals(treeItemStatus.get(childItem))) {
                                loadChildrenAsync(childItem, childFile, depth + 1);
                            }
                        });
                        parentItem.getChildren().add(childItem);
                    }
                }
                // 加载完成后再次确认展开状态
                treeItemStatus.put(parentItem, STATUS_LOADED); // 标记为已加载
            });
        });
    }

    //递归拼接TreeItem的完整路径
    private String getFullPath(TreeItem<String> item) {
        if (item.getParent() == null || item.getParent().getValue().equals("我的电脑")) {
            return item.getValue();
        }
        String parentPath = getFullPath(item.getParent());
        String fullPath = parentPath + File.separator + item.getValue();
        return fullPath.replace("\\\\", "\\");
    }

    //展开并选中目录树中的指定路径
    private void expandAndSelectInTree(String targetPath) {
        System.out.println("\n========== 开始同步目录树 ==========");
        System.out.println("目标路径: " + targetPath);

        TreeItem<String> root = dirTreeView.getRoot();
        if (root == null) {
            System.out.println("✗ 目录树根节点为null");
            return;
        }

        System.out.println("根节点: " + root.getValue());
        System.out.println("根节点子节点数: " + root.getChildren().size());

        Platform.runLater(() -> {
            expandPathStepByStep(root, targetPath, 0);
        });
    }

    //逐级展开路径（修复根节点处理）
    private void expandPathStepByStep(TreeItem<String> currentItem, String targetPath, int depth) {
        String indent = "  ".repeat(depth);
        String currentPath = getFullPath(currentItem);

        System.out.println(indent + "【深度" + depth + "】当前节点: " + currentItem.getValue());
        System.out.println(indent + "  当前路径: " + currentPath);
        System.out.println(indent + "  目标路径: " + targetPath);
        System.out.println(indent + "  子节点数: " + currentItem.getChildren().size());

        if (depth > 15) {
            System.out.println(indent + "✗ 展开层级过深，停止");
            return;
        }

        if (targetPath.equals(currentPath)) {
            currentItem.setExpanded(true);
            dirTreeView.getSelectionModel().select(currentItem);
            System.out.println(indent + "✓✓✓ 找到目标！已选中");
            System.out.println("========== 同步完成 ==========\n");
            return;
        }

        if (currentItem.getValue().equals("我的电脑")) {
            System.out.println(indent + "  根节点特殊处理，查找盘符...");
            String driveLetter = targetPath.substring(0, 3);
            System.out.println(indent + "  提取盘符: " + driveLetter);

            TreeItem<String> driveItem = findChildByName(currentItem, driveLetter);
            if (driveItem == null) {
                System.out.println(indent + "✗ 未找到盘符: " + driveLetter);
                return;
            }
            System.out.println(indent + "✓ 找到盘符节点，进入下一级");

            if (!driveItem.isExpanded()) {
                driveItem.setExpanded(true);
                Platform.runLater(() -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    Platform.runLater(() -> expandPathStepByStep(driveItem, targetPath, depth + 1));
                });
            } else {
                expandPathStepByStep(driveItem, targetPath, depth + 1);
            }
            return;
        }

        if (!targetPath.startsWith(currentPath)) {
            System.out.println(indent + "✗ 目标路径不在当前节点下，停止");
            return;
        }

        String separator = File.separator.equals("\\") ? "\\\\" : File.separator;
        String remaining = targetPath.substring(currentPath.length());
        String[] parts = remaining.split(separator);

        System.out.println(indent + "  剩余路径片段: " + Arrays.toString(parts));

        parts = Arrays.stream(parts).filter(s -> !s.isEmpty()).toArray(String[]::new);

        if (parts.length == 0) {
            System.out.println(indent + "✗ 路径片段为空");
            return;
        }

        String nextName = parts[0];
        System.out.println(indent + "  查找下一节点: " + nextName);

        if (currentItem.getChildren().isEmpty()) {
            System.out.println(indent + "⏳ 子节点为空，等待300ms后重试...");
            Platform.runLater(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Platform.runLater(() -> {
                    System.out.println(indent + "↻ 重试深度" + depth);
                    expandPathStepByStep(currentItem, targetPath, depth);
                });
            });
            return;
        }

        System.out.println(indent + "  当前所有子节点: " +
                currentItem.getChildren().stream()
                        .map(TreeItem::getValue)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("无"));

        TreeItem<String> nextChild = findChildByName(currentItem, nextName);

        if (nextChild == null) {
            System.out.println(indent + "✗✗ 未找到节点: " + nextName);
            System.out.println("========== 同步失败 ==========\n");
            return;
        }

        System.out.println(indent + "✓ 找到节点: " + nextName);

        if (!nextChild.isExpanded()) {
            System.out.println(indent + "→ 展开节点并等待200ms...");
            nextChild.setExpanded(true);

            Platform.runLater(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Platform.runLater(() -> {
                    System.out.println(indent + "↻ 继续深度" + (depth + 1));
                    expandPathStepByStep(nextChild, targetPath, depth + 1);
                });
            });
        } else {
            System.out.println(indent + "⚡ 节点已展开，直接进入");
            expandPathStepByStep(nextChild, targetPath, depth + 1);
        }
    }

    //根据名称查找子节点
    private TreeItem<String> findChildByName(TreeItem<String> parent, String name) {
        for (TreeItem<String> child : parent.getChildren()) {
            if (child.getValue().equals(name)) {
                return child;
            }
        }
        return null;
    }

    //异步加载单张图片(带缓存)
    private void loadImageAsync(File file, Consumer<ImageView> callback) {
        imageExecutor.submit(() -> {
            try {
                String filePath = file.getAbsolutePath();
                Image image;
                //优先从缓存读取，避免重复解码
                if (imageCache.containsKey(filePath)) {
                    image = imageCache.get(filePath);
                } else {
                    //异步加载缩略图
                    image = new Image(file.toURI().toString(), THUMB_SIZE, THUMB_SIZE, true, true, true);
                    imageCache.put(filePath, image);
                }
                //构建ImageView
                ImageView imageView = new ImageView(image);
                imageView.setFitWidth(THUMB_SIZE);
                imageView.setFitHeight(THUMB_SIZE);
                imageView.setPreserveRatio(true);
                imageView.setSmooth(true);//抗锯齿，优化图片质量

                callback.accept(imageView);
            } catch (Exception e) {
                System.out.println("⚠️ 图片加载失败：" + file.getName());
            }
        });
    }

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
            if (!file.isDirectory() && isImageFile(file)) {
                imageFiles.add(file);
            } else {
                createVBoxAsync(file, vBox -> {
                    Platform.runLater(() -> {
                        imageFlowPane.getChildren().add(vBox);
                        FlowPane.setMargin(vBox, new Insets(5));
                    });
                });
            }
        }

        for (File imageFile : imageFiles) {
            createImageVBoxAsync(imageFile, vBox -> {
                Platform.runLater(() -> {
                    imageFlowPane.getChildren().add(vBox);
                    FlowPane.setMargin(vBox, new Insets(5));
                });
            });
        }
        updateTipLabel();
    }

    //异步创建VBox（图标+名称+点击事件）
    private void createVBoxAsync(File file, Consumer<VBox> callback) {
        if (file.isDirectory()) {
            ImageView imageView = new ImageView(new Image(getClass().getResourceAsStream("/folder-icon.png")));
            imageView.setFitWidth(THUMB_SIZE);
            imageView.setFitHeight(THUMB_SIZE);
            imageView.setPreserveRatio(true);
            imageView.setSmooth(true);
            imageView.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #ddd; -fx-border-width: 1px;");
            Label nameLabel = new Label(file.getName());
            nameLabel.setMaxWidth(THUMB_SIZE);
            nameLabel.setStyle("-fx-font-size: 12px; -fx-alignment: center; -fx-text-alignment: center;");
            nameLabel.setWrapText(true);
            VBox vBox = new VBox(5, imageView, nameLabel);
            vBox.setPadding(new Insets(5));
            vBox.setStyle(NORMAL_STYLE);

            ContextMenu contextMenu = new ContextMenu();
            MenuItem pasteItem = new MenuItem("粘贴");
            pasteItem.setOnAction(e -> pasteFiles());
            contextMenu.getItems().add(pasteItem);
            vBox.setOnContextMenuRequested(event -> {
                contextMenu.show(vBox, event.getScreenX(), event.getScreenY());
            });

            vBox.setOnMouseClicked(event -> {
                if (event.getClickCount() == 1) {
                    boolean ctrl = event.isControlDown();
                    if (!ctrl) {
                        selectedVBoxes.forEach(v -> v.setStyle(NORMAL_STYLE));
                        selectedVBoxes.clear();
                    }
                    if (selectedVBoxes.contains(vBox)) {
                        selectedVBoxes.remove(vBox);
                        vBox.setStyle(NORMAL_STYLE);
                    } else {
                        selectedVBoxes.add(vBox);
                        vBox.setStyle(SELECTED_STYLE);
                    }
                    updateTipLabel();
                } else if (event.getClickCount() == 2 && file.isDirectory()) {
                    currentDir = file;
                    loadImagesToFlowPane(file);
                    expandAndSelectInTree(file.getAbsolutePath());
                }
                event.consume();
            });
            vBoxToFile.put(vBox, file);
            callback.accept(vBox);
        } else {
            ImageView imageView = new ImageView(new Image(getClass().getResourceAsStream("/file-icon.png")));
            imageView.setFitWidth(THUMB_SIZE);
            imageView.setFitHeight(THUMB_SIZE);
            imageView.setPreserveRatio(true);
            imageView.setSmooth(true);
            imageView.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #ddd; -fx-border-width: 1px;");
            Label nameLabel = new Label(file.getName());
            nameLabel.setMaxWidth(THUMB_SIZE);
            nameLabel.setStyle("-fx-font-size: 12px; -fx-alignment: center; -fx-text-alignment: center;");
            nameLabel.setWrapText(true);
            VBox vBox = new VBox(5, imageView, nameLabel);
            vBox.setPadding(new Insets(5));
            vBox.setStyle(NORMAL_STYLE);

            ContextMenu contextMenu = new ContextMenu();
            MenuItem deleteItem = new MenuItem("删除");
            deleteItem.setOnAction(e -> deleteSelected());
            MenuItem copyItem = new MenuItem("复制");
            copyItem.setOnAction(e -> copySelected());
            MenuItem renameItem = new MenuItem("重命名");
            renameItem.setOnAction(e -> renameSelected());
            MenuItem pasteItem = new MenuItem("粘贴");
            pasteItem.setOnAction(e -> pasteFiles());
            contextMenu.getItems().addAll(deleteItem, copyItem, renameItem, pasteItem);
            vBox.setOnContextMenuRequested(event -> {
                contextMenu.show(vBox, event.getScreenX(), event.getScreenY());
            });

            vBox.setOnMouseClicked(event -> {
                if (event.getClickCount() == 1) {
                    boolean ctrl = event.isControlDown();
                    if (!ctrl) {
                        selectedVBoxes.forEach(v -> v.setStyle(NORMAL_STYLE));
                        selectedVBoxes.clear();
                    }
                    if (selectedVBoxes.contains(vBox)) {
                        selectedVBoxes.remove(vBox);
                        vBox.setStyle(NORMAL_STYLE);
                    } else {
                        selectedVBoxes.add(vBox);
                        vBox.setStyle(SELECTED_STYLE);
                    }
                    updateTipLabel();
                }
                event.consume();
            });
            vBoxToFile.put(vBox, file);
            callback.accept(vBox);
        }
    }

    //异步创建图片VBox（后台加载完成后显示）
    private void createImageVBoxAsync(File file, Consumer<VBox> callback) {
        String filePath = file.getAbsolutePath();

        if (imageCache.containsKey(filePath)) {
            Image image = imageCache.get(filePath);
            createImageVBox(file, image, callback);
            return;
        }

        imageExecutor.submit(() -> {
            try {
                Image img = new Image(file.toURI().toString(), THUMB_SIZE, THUMB_SIZE, true, true, false);
                imageCache.put(filePath, img);

                Platform.runLater(() -> createImageVBox(file, img, callback));
            } catch (Exception e) {
                System.out.println("⚠️ 图片加载失败：" + file.getName());
            }
        });
    }

    //创建图片VBox
    private void createImageVBox(File file, Image image, Consumer<VBox> callback) {
        ImageView imageView = new ImageView(image);
        imageView.setFitWidth(THUMB_SIZE);
        imageView.setFitHeight(THUMB_SIZE);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #ddd; -fx-border-width: 1px;");
        Label nameLabel = new Label(file.getName());
        nameLabel.setMaxWidth(THUMB_SIZE);
        nameLabel.setStyle("-fx-font-size: 12px; -fx-alignment: center; -fx-text-alignment: center;");
        nameLabel.setWrapText(true);
        VBox vBox = new VBox(5, imageView, nameLabel);
        vBox.setPadding(new Insets(5));
        vBox.setStyle(NORMAL_STYLE);
        setupImageVBox(vBox, file, null);
        vBoxToFile.put(vBox, file);
        callback.accept(vBox);
    }

    //配置图片VBox的事件和菜单
    private void setupImageVBox(VBox vBox, File file, ContextMenu existingMenu) {
        ContextMenu contextMenu = existingMenu != null ? existingMenu : new ContextMenu();
        if (existingMenu == null) {
            MenuItem deleteItem = new MenuItem("删除");
            deleteItem.setOnAction(e -> deleteSelected());
            MenuItem copyItem = new MenuItem("复制");
            copyItem.setOnAction(e -> copySelected());
            MenuItem renameItem = new MenuItem("重命名");
            renameItem.setOnAction(e -> renameSelected());
            MenuItem pasteItem = new MenuItem("粘贴");
            pasteItem.setOnAction(e -> pasteFiles());
            contextMenu.getItems().addAll(deleteItem, copyItem, renameItem, pasteItem);
        }
        vBox.setOnContextMenuRequested(event -> {
            contextMenu.show(vBox, event.getScreenX(), event.getScreenY());
        });

        vBox.setOnMouseClicked(event -> {
            if (event.getClickCount() == 1) {
                boolean ctrl = event.isControlDown();
                if (!ctrl) {
                    selectedVBoxes.forEach(v -> v.setStyle(NORMAL_STYLE));
                    selectedVBoxes.clear();
                }
                if (selectedVBoxes.contains(vBox)) {
                    selectedVBoxes.remove(vBox);
                    vBox.setStyle(NORMAL_STYLE);
                } else {
                    selectedVBoxes.add(vBox);
                    vBox.setStyle(SELECTED_STYLE);
                }
                updateTipLabel();
            } else if (event.getClickCount() == 2) {
                openSlideShowForImage(file);
            }
            event.consume();
        });
    }

    //初始化FlowPane默认提示
    private void initFlowPaneHint() {
        // 添加快捷入口
        File[] roots = File.listRoots();
        if (roots != null) {
            for (File root : roots) {
                createShortcutVBox(root, root.getAbsolutePath(), false);
            }
        }

        // 添加 Pictures 快捷方式
        String userHome = System.getProperty("user.home");
        File picturesDir = new File(userHome, "Pictures");
        if (picturesDir.exists() && picturesDir.isDirectory()) {
            createShortcutVBox(picturesDir, "我的图片", true);
        }

    }

    //创建快捷入口VBox
    private void createShortcutVBox(File targetDir, String displayName, boolean isSpecial) {
        ImageView imageView = new ImageView(new Image(getClass().getResourceAsStream("/folder-icon.png")));
        imageView.setFitWidth(THUMB_SIZE);
        imageView.setFitHeight(THUMB_SIZE);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        Label nameLabel = new Label(displayName);
        nameLabel.setMaxWidth(THUMB_SIZE);
        nameLabel.setStyle("-fx-font-size: 12px; -fx-alignment: center; -fx-text-alignment: center; -fx-font-weight: bold;");
        nameLabel.setWrapText(true);

        VBox vBox = new VBox(5, imageView, nameLabel);
        vBox.setPadding(new Insets(5));
        vBox.setStyle(NORMAL_STYLE);

        vBox.setOnMouseClicked(event -> {
            if (event.getClickCount() == 1) {
                selectedVBoxes.forEach(v -> v.setStyle(NORMAL_STYLE));
                selectedVBoxes.clear();
                selectedVBoxes.add(vBox);
                vBox.setStyle(SELECTED_STYLE);
            } else if (event.getClickCount() == 2) {
                currentDir = targetDir;
                loadImagesToFlowPane(targetDir);
                if (dirTreeView.getRoot() != null) {
                    expandAndSelectInTree(targetDir.getAbsolutePath());
                }
            }
            event.consume();
        });

        imageFlowPane.getChildren().add(vBox);
        FlowPane.setMargin(vBox, new Insets(5));
    }

    //点击Play按钮，打开幻灯片界面
    @FXML
    private void openSlideShow() {
        try {
            URL fxmlUrl = getClass().getResource("/slideShow.fxml");//获取FXML文件URL

            //收集当前目录的所有图片路径(传递给幻灯片)
            List<String> imagePaths = new ArrayList<>();
            if (currentDir != null) {
                File[] files = currentDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile() && isImageFile(file)) {
                            imagePaths.add(file.getAbsolutePath());
                        }
                    }
                } else {
                    System.out.println("[调试] files为null");
                }
                System.out.println("[调试] 收集到图片数量: " + imagePaths.size());
            } else {
                showAlert(Alert.AlertType.WARNING, "未选择目录", "请先在左侧选择包含图片的文件夹");
                return;
            }

            //加载FXML
            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            Parent slideRoot = loader.load();
            SlideShowController slideController = loader.getController();
            //传递图片路径列表给幻灯片控制器
            slideController.setImagePaths(imagePaths);

            //初始化幻灯片窗口
            Stage slideStage = new Stage();
            slideStage.setTitle("幻灯片播放");
            slideStage.setScene(new javafx.scene.Scene(slideRoot, 1000, 700));
            slideStage.initOwner((Stage) dirTreeView.getScene().getWindow());//绑定父窗口
            slideStage.centerOnScreen();//居中显示
            slideStage.show();

        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "打开幻灯片失败",
                    "错误信息：" + e.getMessage() + "\n" +
                            "请检查slideShow.fxml是否合法或控制器是否正确");
            e.printStackTrace();
        }
    }

    @FXML
    private void clickBlank() {
        selectedVBoxes.forEach(v -> v.setStyle(NORMAL_STYLE));
        selectedVBoxes.clear();
        updateTipLabel();
    }

    // 判断是否为图片文件
    private boolean isImageFile(File file) {
        if (!file.isFile()) return false;
        String name = file.getName();
        String lower = name.toLowerCase();
        boolean result = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".bmp");
        return result;
    }

    // 更新提示信息
    private void updateTipLabel() {
        if (currentDir == null) {
            tipLabel.setText("Welcome to Image Manager");
            return;
        }
        long imageCount = allFiles.stream().filter(this::isImageFile).count();
        long totalSize = allFiles.stream().filter(File::isFile).mapToLong(File::length).sum();
        String sizeStr = formatSize(totalSize);
        String selectedStr = selectedVBoxes.isEmpty() ? "" : " | 选中: " + selectedVBoxes.size();
        tipLabel.setText("目录: " + currentDir.getName() + " | 图片数量: " + imageCount + " | 总大小: " + sizeStr + selectedStr);
    }

    // 格式化文件大小
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    // 双击图片打开幻灯片
    private void openSlideShowForImage(File imageFile) {
        try {
            URL fxmlUrl = getClass().getResource("/slideShow.fxml");
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

            if (imagePaths.isEmpty()) {
                showAlert(Alert.AlertType.WARNING, "未找到图片",
                        "当前目录中没有可播放的图片");
                return;
            }

            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            Parent slideRoot = loader.load();
            SlideShowController slideController = loader.getController();
            slideController.setImagePaths(imagePaths);
            // 设置当前索引
            int index = imagePaths.indexOf(imageFile.getAbsolutePath());
            if (index >= 0) {
                slideController.setCurrentIndex(index);
            }
            Stage slideStage = new Stage();
            slideStage.setTitle("幻灯片播放");
            slideStage.setScene(new javafx.scene.Scene(slideRoot, 1000, 700));
            slideStage.initOwner((Stage) dirTreeView.getScene().getWindow());
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
                List<VBox> toRemove = new ArrayList<>(selectedVBoxes);
                for (VBox vBox : toRemove) {
                    File file = vBoxToFile.get(vBox);
                    if (file != null && file.delete()) {
                        imageFlowPane.getChildren().remove(vBox);
                        selectedVBoxes.remove(vBox);
                        vBoxToFile.remove(vBox);
                        allFiles.remove(file);
                    }
                }
                updateTipLabel();
            }
        });
    }

    // 复制选中文件
    private void copySelected() {
        copiedFiles.clear();
        for (VBox vBox : selectedVBoxes) {
            File file = vBoxToFile.get(vBox);
            if (file != null) {
                copiedFiles.add(file);
            }
        }
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
                    String newNameWithExt = newName + ext;
                    File newFile = new File(file.getParent(), newNameWithExt);
                    if (file.renameTo(newFile)) {
                        vBoxToFile.put(vBox, newFile);
                        ((Label) ((VBox) vBox).getChildren().get(1)).setText(newNameWithExt);
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
                                String newName = prefix + String.format(format, startNum + i) + ext;
                                File newFile = new File(file.getParent(), newName);
                                if (file.renameTo(newFile)) {
                                    vBoxToFile.put(vBox, newFile);
                                    ((Label) ((VBox) vBox).getChildren().get(1)).setText(newName);
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
        if (copiedFiles.isEmpty() || currentDir == null) return;
        for (File src : copiedFiles) {
            try {
                File dest = new File(currentDir, src.getName());
                if (dest.exists()) {
                    // 重命名
                    String name = src.getName();
                    String base = name.substring(0, name.lastIndexOf('.'));
                    String ext = name.substring(name.lastIndexOf('.'));
                    int count = 1;
                    while (dest.exists()) {
                        dest = new File(currentDir, base + "(" + count + ")" + ext);
                        count++;
                    }
                }
                Files.copy(src.toPath(), dest.toPath());
                // 添加到FlowPane
                allFiles.add(dest);
                createVBoxAsync(dest, vBox -> {
                    Platform.runLater(() -> {
                        imageFlowPane.getChildren().add(vBox);
                        FlowPane.setMargin(vBox, new Insets(5));
                    });
                });
            } catch (Exception e) {
                showAlert(Alert.AlertType.ERROR, "粘贴失败", e.getMessage());
            }
        }
        updateTipLabel();
    }

    //辅助方法：弹出提示框
    private void showAlert(Alert.AlertType type, String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.initOwner((Stage) dirTreeView.getScene().getWindow());
            alert.showAndWait();
        });
    }

    //关闭所有线程池
    public void shutdown() {
        dirExecutor.shutdown();
        imageExecutor.shutdown();
    }
}

