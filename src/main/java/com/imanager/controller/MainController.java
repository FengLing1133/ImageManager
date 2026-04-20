package com.imanager.controller;

import com.imanager.service.DirectoryTreeService;
import com.imanager.service.FileService;
import com.imanager.util.AlterUtil;
import com.imanager.util.VBoxFactory;
import javafx.application.Platform;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
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
    private final List<File> allFiles = new ArrayList<>(); // 当前目录下所有文件
    private static final int THUMB_SIZE = 120; // 缩略图尺寸
    private long cachedImageCount = 0;// 当前目录图片数量缓存
    private long cachedTotalSize = 0;// 当前目录总大小缓存
    private long activeLoadToken = 0;// 活动加载令牌，用于异步加载结果的有效性验证
    private Timeline buildTimeline;// 构建VBox的动画时间轴
    private Timeline renderTimeline;// 渲染VBox的动画时间轴
    private final Deque<Runnable> pendingBuildTasks = new ArrayDeque<>();// 待构建任务队列，存储需要构建的VBox任务
    private final Deque<CardRenderTask> pendingRenderTasks = new ArrayDeque<>();// 待渲染任务队列，存储需要渲染到FlowPane的VBox任务
    private static final Insets CARD_MARGIN = new Insets(5);// 卡片间距
    private static final int HOVER_EFFECT_THRESHOLD = 500;// 启用悬停效果的文件数量阈值，超过后禁用以提升性能
    private static final int PROGRESSIVE_RENDER_THRESHOLD = 600;// 启用渐进式渲染的文件数量阈值，超过后分批构建和渲染以避免界面冻结
    private static final int BUILD_BATCH_SIZE = 50;// 每批构建的VBox数量，过多可能导致界面卡顿，过少则加载过慢
    private static final int RENDER_BATCH_SIZE = 50;// 每批渲染的VBox数量，过多可能导致界面卡顿，过少则加载过慢

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
                navigateToDirectory(selectedDir, false);// 树选择已同步，无需再次反向同步树
            }
        });
    }

    private void navigateToDirectory(File dir, boolean syncTreeSelection) {// 更新当前目录并刷新图片预览区
        if (dir != null && dir.isDirectory()) {
            if (dir.equals(currentDir)) {
                return;
            }
            currentDir = dir;
            pathField.setText(dir.getAbsolutePath());// 初始化路径输入框显示当前目录
            loadImagesToFlowPane(dir);
            if (syncTreeSelection) {
                directoryTreeService.expandAndSelectInTree(dir.getAbsolutePath());// 同步更新目录树选择状态
            }
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
                navigateToDirectory(file, true);// 如果路径有效，更新当前目录并刷新图片预览区
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



    private void loadImagesToFlowPane(File dir) { // 加载目录下文件到FlowPane
        long loadToken = ++activeLoadToken;
        stopProgressivePipelines();
        allFiles.clear();
        selectedVBoxes.clear();
        vBoxToFile.clear();
        imageFlowPane.getChildren().clear();

        imageExecutor.submit(() -> {
            File[] files = dir.listFiles();// 获取目录下所有文件
            if (files == null || files.length == 0) {
                Platform.runLater(() -> {
                    if (isStaleLoad(loadToken, dir)) return;
                    emptyTipLabel.setVisible(true);// 显示空目录提示
                    cachedImageCount = 0;
                    cachedTotalSize = 0;
                    updateTipLabel();
                });
                return;
            }

            // 过滤掉系统/隐藏文件（不可见文件）
            List<File> visibleFiles = new ArrayList<>();
            for (File file : files) {
                if (fileService.isVisibleFile(file)) {// 使用FileService的isVisibleFile方法过滤不可见文件
                    visibleFiles.add(file);
                }
            }
            if (visibleFiles.isEmpty()) {
                Platform.runLater(() -> {
                    if (isStaleLoad(loadToken, dir)) return;
                    emptyTipLabel.setVisible(true);// 显示空目录提示
                    cachedImageCount = 0;
                    cachedTotalSize = 0;
                    updateTipLabel();
                });
                return;
            }

            visibleFiles.sort((f1, f2) -> {
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                return f1.getName().compareToIgnoreCase(f2.getName());// 目录优先，按名称排序
            });

            List<File> imageFiles = new ArrayList<>();
            List<File> nonImageFiles = new ArrayList<>();
            long imageCount = 0;
            long totalSize = 0;
            for (File file : visibleFiles) {
                if (file.isFile()) {
                    totalSize += file.length();
                }
                if (!file.isDirectory()) {
                    boolean imageFile = fileService.isImageFile(file);
                    if (imageFile) {
                        imageFiles.add(file);// 分类图片文件和非图片文件
                        imageCount++;
                    } else {
                        nonImageFiles.add(file);
                    }
                } else {
                    nonImageFiles.add(file);
                }
            }
            final long finalImageCount = imageCount;
            final long finalTotalSize = totalSize;
            final boolean enableHoverEffects = visibleFiles.size() <= HOVER_EFFECT_THRESHOLD;// 根据文件数量决定是否启用悬停效果
            final boolean progressiveRender = visibleFiles.size() >= PROGRESSIVE_RENDER_THRESHOLD;// 根据文件数量决定是否启用渐进式渲染

            Platform.runLater(() -> {
                if (isStaleLoad(loadToken, dir)) return; // 如果用户已经切换到其他目录，抛弃当前加载结果
                emptyTipLabel.setVisible(false);// 显示空目录提示
                allFiles.addAll(visibleFiles);// 更新当前目录文件列表
                cachedImageCount = finalImageCount;
                cachedTotalSize = finalTotalSize;
                vBoxFactory.setHoverEffectsEnabled(enableHoverEffects);

                Map<File, Integer> fileIndexMap = new HashMap<>();
                List<VBox> placeholders = new ArrayList<>(visibleFiles.size());
                for (int i = 0; i < visibleFiles.size(); i++) {
                    fileIndexMap.put(visibleFiles.get(i), i);// 记录文件在FlowPane中的索引位置
                    VBox placeholder = new VBox();
                    placeholder.setPrefSize(130, 150);
                    placeholder.setStyle("-fx-background-color: transparent;");
                    placeholders.add(placeholder);// 先构建占位符，后续一次性添加
                }
                imageFlowPane.getChildren().setAll(placeholders);

                for (File file : nonImageFiles) {
                    pendingBuildTasks.addLast(() -> createVBoxAsync(file, vBox -> {
                        Integer index = fileIndexMap.get(file);// 获取文件对应的索引位置
                        if (index != null) {
                            enqueueRenderTask(loadToken, dir, index, vBox);// 构建完成后添加到渲染队列
                        }
                    }));
                }

                for (File imageFile : imageFiles) {
                    pendingBuildTasks.addLast(() -> createImageVBoxAsync(imageFile, vBox -> {
                        Integer index = fileIndexMap.get(imageFile);// 获取文件对应的索引位置
                        if (index != null) {
                            enqueueRenderTask(loadToken, dir, index, vBox);// 构建完成后添加到渲染队列
                        }
                    }));
                }

                if (progressiveRender) {// 如果文件数量较多，启用渐进式构建和渲染
                    startBuildPump(loadToken, dir);// 启动构建管道，分批执行构建任务以避免界面冻结
                } else {
                    while (!pendingBuildTasks.isEmpty() && !isStaleLoad(loadToken, dir)) {
                        pendingBuildTasks.pollFirst().run();// 直接执行所有构建任务
                    }
                }
                updateTipLabel();
            });
        });
    }

    private boolean isStaleLoad(long loadToken, File dir) {// 判断加载结果是否过时
        return loadToken != activeLoadToken || !Objects.equals(currentDir, dir);// 验证加载令牌和当前目录是否匹配，防止过时的异步加载结果影响界面
    }

    private void enqueueRenderTask(long loadToken, File dir, int index, VBox vBox) {// 将渲染任务添加到队列，并确保渲染管道正在运行
        if (isStaleLoad(loadToken, dir)) {
            return;
        }
        pendingRenderTasks.addLast(new CardRenderTask(index, vBox));// 将渲染任务添加到队列
        ensureRenderPump(loadToken, dir);// 确保渲染管道正在运行
    }

    private void startBuildPump(long loadToken, File dir) {// 启动构建管道，分批执行构建任务以避免界面冻结
        if (buildTimeline != null) {
            buildTimeline.stop();// 如果构建管道已经在运行，先停止它
        }
        buildTimeline = new Timeline(new KeyFrame(Duration.millis(16), event -> {// 每16毫秒执行一次，约等于60帧每秒
            if (isStaleLoad(loadToken, dir)) {
                stopProgressivePipelines();// 如果加载过时，停止所有渐进式管道并清空任务队列
                return;
            }
            int built = 0;
            while (built < BUILD_BATCH_SIZE && !pendingBuildTasks.isEmpty()) {// 分批构建VBox
                pendingBuildTasks.pollFirst().run();// 执行构建任务
                built++;// 计数已构建的任务数量
            }
            if (pendingBuildTasks.isEmpty()) {
                buildTimeline.stop();// 如果所有构建任务完成，停止构建管道
            }
        }));
        buildTimeline.setCycleCount(Animation.INDEFINITE);// 设置为无限循环，直到手动停止
        buildTimeline.play();// 启动构建管道
    }

    private void ensureRenderPump(long loadToken, File dir) {// 确保渲染管道正在运行
        if (renderTimeline != null && renderTimeline.getStatus() == Animation.Status.RUNNING) {// 如果渲染管道已经在运行，直接返回
            return;
        }
        renderTimeline = new Timeline(new KeyFrame(Duration.millis(16), event -> {// 每16毫秒执行一次，约等于60帧每秒
            if (isStaleLoad(loadToken, dir)) {
                stopProgressivePipelines();// 如果加载过时，停止所有渐进式管道并清空任务队列
                return;
            }
            int rendered = 0;
            while (rendered < RENDER_BATCH_SIZE && !pendingRenderTasks.isEmpty()) {// 分批渲染VBox到FlowPane
                CardRenderTask task = pendingRenderTasks.pollFirst();// 获取下一个渲染任务
                if (task != null && task.index < imageFlowPane.getChildren().size()) {
                    imageFlowPane.getChildren().set(task.index, task.card);// 将构建好的VBox放到对应的索引位置
                    FlowPane.setMargin(task.card, CARD_MARGIN);// 设置卡片间距
                }
                rendered++;
            }
            if (pendingRenderTasks.isEmpty()) {
                renderTimeline.stop();// 如果所有渲染任务完成，停止渲染管道
            }
        }));
        renderTimeline.setCycleCount(Animation.INDEFINITE);// 设置为无限循环，直到手动停止
        renderTimeline.play();// 启动渲染管道
    }

    private void stopProgressivePipelines() {// 停止所有渐进式管道并清空任务队列
        if (buildTimeline != null) {
            buildTimeline.stop();// 停止构建管道
            buildTimeline = null;
        }
        if (renderTimeline != null) {
            renderTimeline.stop();
            renderTimeline = null;
        }
        pendingBuildTasks.clear();// 清空待构建任务队列
        pendingRenderTasks.clear();// 清空待渲染任务队列
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
            ContextMenu menu = vBoxFactory.buildContextMenu(selCount, this::deleteSelected, this::copySelected, this::renameSelected, this::pasteFiles);
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
                () -> navigateToDirectory(file, true)
        );
    }

    private void createImageVBoxAsync(File file, Consumer<VBox> callback) { // 异步创建图片VBox
        vBoxFactory.createImageVBoxAsync(
                file,
                vBox -> {
                    setupVBoxContextMenu(vBox);
                    if (callback != null) {
                        callback.accept(vBox);
                    }
                },
                imageCache,
                imageExecutor,
                THUMB_SIZE,
                NORMAL_STYLE,
                SELECTED_STYLE,
                selectedVBoxes,
                vBoxToFile,
                this::updateTipLabel,
                () -> openSlideShowForImage(file)
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
                    navigateToDirectory(targetDir, false);
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
                navigateToDirectory(parent, true);
            } else {
                activeLoadToken++; // 使旧的异步加载结果失效
                stopProgressivePipelines();
                currentDir = null;
                allFiles.clear();
                selectedVBoxes.clear();
                vBoxToFile.clear();
                Platform.runLater(() -> {
                    imageFlowPane.getChildren().clear();
                    initFlowPaneHint();
                    pathField.setText(""); // 清空路径输入框
                    cachedImageCount = 0;
                    cachedTotalSize = 0;
                    updateTipLabel();
                });
                dirTreeView.getSelectionModel().clearSelection();// 取消目录树选择状态
            }
        }
    }

    @FXML
    public void onUndo() {// 撤销返回操作
        if (!dirHistoryStack.isEmpty()) {
            navigateToDirectory(dirHistoryStack.pop(), true);
        } else {
            showAlert(Alert.AlertType.INFORMATION, "提示", "无可撤销的操作");
        }
    }

    private boolean isImageFile(File file) {
        return fileService.isImageFile(file); // 判断是否为图片文件
    }

    private void updateTipLabel() { // 更新底部提示信息
        if (currentDir == null) {
            tipLabel.setText("Welcome to Image Manager");
            return;
        }
        String sizeStr = fileService.formatSize(cachedTotalSize);// 格式化总大小
        String selectedStr = selectedVBoxes.isEmpty() ? "" : " | 选中: " + selectedVBoxes.size();// 选中数量提示
        long selectedImageSize = selectedVBoxes.stream()
                .map(vBoxToFile::get)
                .filter(Objects::nonNull)
                .filter(this::isImageFile)
                .mapToLong(File::length)
                .sum();
        String selectedSizeStr = (selectedVBoxes.isEmpty() || selectedImageSize == 0) ? "" : " | 选中图片总大小: " + fileService.formatSize(selectedImageSize);
        tipLabel.setText("目录: " + currentDir.getName() + " | 图片数量: " + cachedImageCount + " | 总大小: " + sizeStr + selectedStr + selectedSizeStr);
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
                            recalculateDirectoryStats();
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
                    ((Label) vBox.getChildren().get(1)).setText(VBoxFactory.truncateFileName(newNameWithExt));// 更新VBox中文件名标签显示
                    allFiles.set(allFiles.indexOf(file), newFile);// 更新当前目录文件列表中的File对象
                    recalculateDirectoryStats();
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

    private void recalculateDirectoryStats() {// 重新计算当前目录的图片数量和总大小
        long imageCount = 0;
        long totalSize = 0;
        for (File file : allFiles) {
            if (file == null || !file.exists()) {
                continue;
            }
            if (file.isFile()) {
                totalSize += file.length();
                if (isImageFile(file)) {
                    imageCount++;
                }
            }
        }
        cachedImageCount = imageCount;// 更新图片数量缓存
        cachedTotalSize = totalSize;// 更新总大小缓存
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        AlterUtil.showAlert(type, title, content, dirTreeView.getScene().getWindow()); // 弹窗提示
    }

    private static class CardRenderTask {
        private final int index;// VBox在FlowPane中的索引位置
        private final VBox card;// 已经构建好的VBox卡片，准备渲染到界面

        private CardRenderTask(int index, VBox card) {
            this.index = index;
            this.card = card;
        }
    }

    public void shutdown() {
        stopProgressivePipelines();
        if (directoryTreeService != null) {
            directoryTreeService.shutdown(); // 关闭目录树相关线程池
        }
        imageExecutor.shutdown(); // 关闭图片加载线程池
    }
}
