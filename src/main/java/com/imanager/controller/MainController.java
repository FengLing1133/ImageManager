package com.imanager.controller;


import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.TextFieldTreeCell;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.File;
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

    private int currentPage = 0;
    private final int PAGE_SIZE = 50;//每页加载50张图片
    private File currentDir;//记录当前选中的目录

    //程序启动后初始化
    @FXML
    public void initialize() {
        dirTreeView.setCellFactory(TextFieldTreeCell.forTreeView());//基础单元格样式
        dirTreeView.setPrefWidth(250); // 固定目录树宽度，避免布局抖动

        //选中目录触发图片加载
        dirTreeView.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            if (newItem != null) {
                // 拼接完整路径：从根节点逐级拼接
                String fullPath = getFullPath(newItem);
                File selectedDir = new File(fullPath);
                if (selectedDir != null && selectedDir.isDirectory()) {
                    currentDir = selectedDir;
                    currentPage = 0;
                    loadImagesToFlowPane(selectedDir,currentPage);
                }
            }
        });

        //FlowPane滚动到底部加载下一页(分页核心)
        imageFlowPane.setOnScroll(event -> {
            if (currentDir == null) return;
            //判断是否滚动到底部
            boolean isAtBottom = event.getTotalDeltaY() >= imageFlowPane.getHeight() - 100;
            if (isAtBottom) {
                currentPage++;
                loadImagesToFlowPane(currentDir,currentPage);//加载下一页图片
            }
        });

        // 初始化FlowPane默认提示
        initFlowPaneHint();
        //初始化目录树(加载本地文件系统)
        initDirectoryTree();
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
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/slideShow.fxml"));
            Stage slideStage = new Stage();
            slideStage.setTitle("幻灯片播放");
            slideStage.setScene(new javafx.scene.Scene(loader.load()));
            slideStage.show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void clickBlank() {
        //临时实现：清空FlowPane中所有节点选中的样式
        imageFlowPane.getChildren().forEach(node -> node.setStyle(""));
    }

    //初始化全盘符目录树（支持扫描全硬盘）
    private void initDirectoryTree() {
        //创建根节点(显示“我的电脑”)
        TreeItem<String> computerRoot = new TreeItem<>("我的电脑");
        computerRoot.setExpanded(true);
        dirTreeView.setRoot(computerRoot);
        dirTreeView.setShowRoot(true);//显示根节点

        // 获取所有磁盘盘符（C:\、D:\、E:\等）
        File[] roots = File.listRoots();
        if(roots == null) roots = new File[0];

        //为每个盘符创建节点
        for(File root : roots){
            TreeItem<String> driveItem = new TreeItem<>(root.getAbsolutePath());
            driveItem.setExpanded(false);//盘符默认关闭，避免一次性加载系统目录
            computerRoot.getChildren().add(driveItem);

            //盘符展开是加载子目录
            driveItem.expandedProperty().addListener((obs, oldVal, newVal) -> {
                if(newVal && driveItem.getChildren().isEmpty()){
                    loadChildrenAsync(driveItem, root, 1);
                }
            });
        }
    }

    //异步加载子目录
    private void loadChildrenAsync(TreeItem<String> parentItem, File parentFile, int depth) {
        // 防重复加载锁（核心：避免多次点击触发重复任务）
        AtomicBoolean isLoading = new AtomicBoolean(false);//默认未加载
        if(isLoading.get() || !parentItem.getChildren().isEmpty()){
            return;
        }
        isLoading.set(true);

        if (depth > 5) {// 最多加载6级目录，避免无限递归
            isLoading.set(false);
            return;
        }

        //添加加载占位符
        TreeItem<String> loadingItem = new TreeItem<>("加载中...");
        Platform.runLater(() -> parentItem.getChildren().add(loadingItem));

        //强制设置为展开状态（避免点击后收起）
        parentItem.setExpanded(true);

        //后台线程读取目录
        dirExecutor.submit(() -> {
            File[] childFiles = parentFile.listFiles(File::isDirectory);
            if (childFiles == null) {
                childFiles = new File[0];//权限不足时返回空数组
                System.out.println("❌ 读取目录失败（权限不足）：" + parentFile.getAbsolutePath());
                // ========== 修复1：空目录/权限不足时，显示“无访问权限”提示 ==========
                Platform.runLater(() -> {
                    parentItem.getChildren().remove(loadingItem);
                    TreeItem<String> emptyItem = new TreeItem<>("无访问权限");
                    parentItem.getChildren().add(emptyItem);
                    parentItem.setExpanded(true); // 强制保持展开
                });
                isLoading.set(false);
                return;
            }

            //过滤系统敏感目录、隐藏目录
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
                // 移除“加载中...”占位符
                parentItem.getChildren().remove(loadingItem);

                // 空目录时显示“无子目录”提示，避免空白
                if (filteredFiles.isEmpty()) {
                    TreeItem<String> emptyItem = new TreeItem<>("无子目录");
                    parentItem.getChildren().add(emptyItem);
                } else {
                    // 正常加载子目录（保留原有逻辑）
                    for (File childFile : filteredFiles) {
                        TreeItem<String> childItem = new TreeItem<>(childFile.getName());
                        childItem.expandedProperty().addListener((obs, oldVal, newVal) -> {
                            if (newVal && childItem.getChildren().isEmpty()) {
                                childItem.setExpanded(true);
                                loadChildrenAsync(childItem, childFile, depth + 1);
                            }
                        });
                        parentItem.getChildren().add(childItem);
                    }
                }
                // 加载完成后再次确认展开状态
                parentItem.setExpanded(true);
                isLoading.set(false);
            });
        });
    }

    //递归拼接TreeItem的完整路径
    private String getFullPath(TreeItem<String> item) {
        //根节点是“我的电脑”,跳过
        if(item.getParent() == null || item.getParent().getValue().equals("我的电脑"))
        {
            return item.getValue();//盘符直接返回
        }
        // 递归拼接：父路径 + 系统分隔符 + 当前目录名
        String parentPath = getFullPath(item.getParent());
        return parentPath + File.separator + item.getValue();
    }

    //异步加载单张图片(带缓存)
    private void loadImageAsync(File file, Consumer<ImageView> callback) {
        imageExecutor.submit(() ->{
            try{
                String filePath = file.getAbsolutePath();
                Image image;
                //优先从缓存读取，避免重复解码
                if(imageCache.containsKey(filePath)){
                    image = imageCache.get(filePath);
                }else{
                    //异步加载缩略图
                    image = new Image(file.toURI().toString(), 150, 150, true, true,true);
                    imageCache.put(filePath, image);
                }
                //构建ImageView
                ImageView imageView = new ImageView(image);
                imageView.setFitWidth(150);
                imageView.setFitHeight(150);
                imageView.setPreserveRatio( true);
                imageView.setSmooth( true);//抗锯齿，优化图片质量

                // 选中样式
                imageView.setOnMouseClicked(event -> {
                    Platform.runLater(() -> {
                        imageFlowPane.getChildren().forEach(node -> node.setStyle(""));
                        imageView.setStyle("-fx-border-color: blue; -fx-border-width: 2px;");
                    });
                });
                callback.accept(imageView);
            }catch (Exception e){
                System.out.println("⚠️ 图片加载失败：" + file.getName());
            }
        });
    }

    // 分页加载图片到FlowPane
    private void loadImagesToFlowPane(File dir,int page) {
        //第一页清空原有内容，后续页追加
        if (page == 0) {
            Platform.runLater(() -> imageFlowPane.getChildren().clear());
        }

        File[] files = dir.listFiles();//获取文件夹下的所有文件
        if (files == null || files.length == 0) {
            if (page == 0) { // 只有第一页为空时显示提示
                // 空目录提示也异步执行，不阻塞主线程
                Platform.runLater(() -> {
                    Label emptyLabel = new Label("当前目录无文件");
                    emptyLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 14px;");
                    imageFlowPane.getChildren().add(emptyLabel);
                    FlowPane.setMargin(emptyLabel, new Insets(20, 0, 0, 0));
                });
            }
            return;
        }

        //筛选图片文件
        List<File> imageFiles = new ArrayList<>();
        for (File file : files) {
            String name = file.getName().toLowerCase();
            if (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".gif") || name.endsWith(".jpeg")) {
                imageFiles.add(file);
            }
        }

        //分页计算
        int start = page * PAGE_SIZE;
        if(start >= imageFiles.size()){
            return;//没有更多图片
        }
        int end = Math.min(start + PAGE_SIZE, imageFiles.size());
        List<File> pageFiles = imageFiles.subList(start, end);

        //异步加载当前页图片
        for(File file : pageFiles){
            loadImageAsync(file, imageView -> {
                Platform.runLater(() -> {
                    FlowPane.setMargin(imageView, new Insets(5));
                    imageFlowPane.getChildren().add(imageView);
                });
            });
        }

        // 第一页无图片时显示提示
        if (page == 0 && imageFiles.isEmpty()) {
            Platform.runLater(() -> {
                Label noImageLabel = new Label("当前目录无支持的图片格式（仅支持jpg/png/gif/jpeg）");
                noImageLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 14px;");
                imageFlowPane.getChildren().add(noImageLabel);
                FlowPane.setMargin(noImageLabel, new Insets(20, 0, 0, 0));
            });
        }
    }
    //关闭所有线程池
    public void shutdown() {
        dirExecutor.shutdown();
        imageExecutor.shutdown();
    }
}
