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
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class MainController {

    // 与 FXML 中的 fx:id 绑定
    @FXML
    private TreeView<String> dirTreeView;

    @FXML
    private FlowPane imageFlowPane;

    @FXML
    private ScrollPane imageScrollPane;

    private File currentDir;//记录当前选中的目录

    //目录节点加载状态缓存
    private final Map<TreeItem<String>, String> treeItemStatus = new HashMap<>();
    // 状态常量
    private static final String STATUS_UNLOADED = "unloaded";   // 未加载
    private static final String STATUS_LOADING = "loading";     // 加载中
    private static final String STATUS_LOADED = "loaded";       // 已加载


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

        // 添加滚动监听器，当滚动到80%时加载下一批
        imageScrollPane.vvalueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.doubleValue() > 0.8) {
                loadNextBatch();
            }
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
        //根节点是“我的电脑”,跳过
        if (item.getParent() == null || item.getParent().getValue().equals("我的电脑")) {
            return item.getValue();//盘符直接返回
        }
        // 递归拼接：父路径 + 系统分隔符 + 当前目录名
        String parentPath = getFullPath(item.getParent());
        return parentPath + File.separator + item.getValue();
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

    // 加载图片和文件夹到FlowPane（分页加载）
    private void loadImagesToFlowPane(File dir) {
        allFiles.clear();
        currentLoadedCount = 0;
        isLoading = false;
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
        loadNextBatch();
    }

    //异步创建VBox（图标+名称+点击事件）
    private void createVBoxAsync(File file, Consumer<VBox> callback) {
        if (file.isDirectory()) {
            // 文件夹：同步加载图标
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
            vBox.setStyle("-fx-alignment: center; -fx-border-color: #cccccc; -fx-border-width: 2px; -fx-background-color: #fff;");
            // 单击选中，双击进入
            vBox.setOnMouseClicked(event -> {
                if (event.getClickCount() == 1) {
                    imageFlowPane.getChildren().forEach(node -> node.setStyle("-fx-alignment: center; -fx-border-color: #cccccc; -fx-border-width: 2px; -fx-background-color: #fff;"));
                    vBox.setStyle("-fx-border-color: #2196f3; -fx-border-width: 2px; -fx-background-color: #e3f2fd; -fx-alignment: center;");
                } else if (event.getClickCount() == 2 && file.isDirectory()) {
                    loadImagesToFlowPane(file);
                }
                event.consume();
            });
            callback.accept(vBox);
        } else {
            String name = file.getName().toLowerCase();
            if (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".gif") || name.endsWith(".jpeg")) {
                // 图片文件：异步加载缩略图
                loadImageAsync(file, imageView -> {
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
                    vBox.setStyle("-fx-alignment: center; -fx-border-color: #cccccc; -fx-border-width: 2px; -fx-background-color: #fff;");
                    // 单击选中，双击文件夹进入（但这里是文件，所以双击无操作）
                    vBox.setOnMouseClicked(event -> {
                        if (event.getClickCount() == 1) {
                            imageFlowPane.getChildren().forEach(node -> node.setStyle("-fx-alignment: center; -fx-border-color: #cccccc; -fx-border-width: 2px; -fx-background-color: #fff;"));
                            vBox.setStyle("-fx-border-color: #2196f3; -fx-border-width: 2px; -fx-background-color: #e3f2fd; -fx-alignment: center;");
                        }
                        event.consume();
                    });
                    callback.accept(vBox);
                });
            } else {
                // 其他文件：同步加载图标
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
                vBox.setStyle("-fx-alignment: center; -fx-border-color: #cccccc; -fx-border-width: 2px; -fx-background-color: #fff;");
                // 单击选中
                vBox.setOnMouseClicked(event -> {
                    if (event.getClickCount() == 1) {
                        imageFlowPane.getChildren().forEach(node -> node.setStyle("-fx-alignment: center; -fx-border-color: #cccccc; -fx-border-width: 2px; -fx-background-color: #fff;"));
                        vBox.setStyle("-fx-border-color: #2196f3; -fx-border-width: 2px; -fx-background-color: #e3f2fd; -fx-alignment: center;");
                    }
                    event.consume();
                });
                callback.accept(vBox);
            }
        }
    }

    //初始化FlowPane提示
    private void initFlowPaneHint() {
        Label hintLabel = new Label("请选择包含图片的文件夹");
        hintLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 14px;");
        imageFlowPane.getChildren().add(hintLabel);
        FlowPane.setMargin(hintLabel, new Insets(20, 0, 0, 0));
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
                        //排除目录，只处理文件
                        if (file.isFile()) {
                            String name = file.getName().toLowerCase();
                            //确保后缀判断完整
                            if (name.endsWith(".jpg") || name.endsWith(".png")
                                    || name.endsWith(".gif") || name.endsWith(".jpeg")) {
                                //拼接正确的文件路径（file:前缀可省略，Image可直接识别绝对路径）
                                imagePaths.add(file.getAbsolutePath());
                                System.out.println("收集到图片：" + file.getAbsolutePath()); // 调试日志
                            }
                        }
                    }
                }
                // 调试：打印收集到的图片数量
                System.out.println("选中目录：" + currentDir + "，收集到图片数量：" + imagePaths.size());
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
        //临时实现：清空FlowPane中所有节点选中的样式
        imageFlowPane.getChildren().forEach(node -> node.setStyle(""));
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

    // 加载下一批文件到FlowPane
    private void loadNextBatch() {
        if (isLoading || currentLoadedCount >= allFiles.size()) {
            return;
        }
        isLoading = true;
        int end = Math.min(currentLoadedCount + batchSize, allFiles.size());
        for (int i = currentLoadedCount; i < end; i++) {
            File file = allFiles.get(i);
            createVBoxAsync(file, vBox -> {
                Platform.runLater(() -> {
                    imageFlowPane.getChildren().add(vBox);
                    FlowPane.setMargin(vBox, new Insets(5));
                });
            });
        }
        currentLoadedCount = end;
        isLoading = false;
    }
}
