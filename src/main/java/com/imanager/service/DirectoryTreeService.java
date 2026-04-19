package com.imanager.service;

import javafx.application.Platform;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DirectoryTreeService {
    public DirectoryTreeService(TreeView<String> dirTreeView) {
        this.dirTreeView = dirTreeView; // 目录树控件
    }

    private final TreeView<String> dirTreeView; // 目录树控件引用
    private final Map<TreeItem<String>, String> treeItemStatus = new HashMap<>(); // 节点加载状态
    private static final String STATUS_UNLOADED = "unloaded";   // 未加载
    private static final String STATUS_LOADING = "loading";     // 加载中
    private static final String STATUS_LOADED = "loaded";       // 已加载
    private final ExecutorService dirExecutor = Executors.newFixedThreadPool(2, runnable -> { // 目录加载线程池
        Thread thread = new Thread(runnable);
        thread.setDaemon(true); // 守护线程，程序退出自动关闭
        thread.setName("Directory Loader");
        return thread;
    });

    //初始化全盘符目录树（支持扫描全硬盘）
    public void initDirectoryTree() {
        TreeItem<String> computerRoot = new TreeItem<>("我的电脑"); // 根节点
        computerRoot.setExpanded(true);
        dirTreeView.setRoot(computerRoot);
        dirTreeView.setShowRoot(true); // 显示根节点
        File[] roots = File.listRoots(); // 获取所有磁盘盘符
        if (roots == null) roots = new File[0];
        for (File root : roots) { // 为每个盘符创建节点
            TreeItem<String> driveItem = new TreeItem<>(root.getAbsolutePath());
            driveItem.setExpanded(false); // 默认关闭
            treeItemStatus.put(driveItem, STATUS_UNLOADED); // 初始化状态
            computerRoot.getChildren().add(driveItem);
            driveItem.expandedProperty().addListener((obs, oldVal, newVal) -> { // 展开时加载
                if (newVal && STATUS_UNLOADED.equals(treeItemStatus.get(driveItem))) {
                    loadChildrenAsync(driveItem, root, 1);// 异步加载子目录
                }
            });
        }
    }

    //异步加载子目录
    private void loadChildrenAsync(TreeItem<String> parentItem, File parentFile, int depth) {
        String status = treeItemStatus.getOrDefault(parentItem, STATUS_UNLOADED); // 状态校验
        if (STATUS_LOADING.equals(status) || STATUS_LOADED.equals(status)) {
            return;
        }
        treeItemStatus.put(parentItem, STATUS_LOADING); // 标记为加载中
        if (depth > 5) { // 限制递归深度
            treeItemStatus.put(parentItem, STATUS_LOADED);
            return;
        }
        if (!parentItem.isExpanded()) {
            parentItem.setExpanded(true);
        }
        dirExecutor.submit(() -> { // 异步加载
            File[] childFiles = parentFile.listFiles(File::isDirectory);
            if (childFiles == null) { // 无法访问
                Platform.runLater(() -> {
                    TreeItem<String> emptyItem = new TreeItem<>("无访问权限");
                    parentItem.getChildren().add(emptyItem);
                });
                treeItemStatus.put(parentItem, STATUS_LOADED);
                return;
            }
            List<File> filteredFiles = Arrays.stream(childFiles) // 过滤系统目录
                    .filter(file -> {
                        String name = file.getName();
                        return !name.equals("System Volume Information")
                                && !name.equals("$Recycle.Bin")
                                && !name.equals("Windows")
                                && !name.equals("Program Files")
                                && !file.isHidden();
                    })
                    .sorted((f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()))
                    .toList();
            Platform.runLater(() -> {
                if (filteredFiles.isEmpty()) {
                    TreeItem<String> emptyItem = new TreeItem<>("无子目录");
                    parentItem.getChildren().add(emptyItem);
                } else {
                    for (File childFile : filteredFiles) {
                        TreeItem<String> childItem = new TreeItem<>(childFile.getName());
                        treeItemStatus.put(childItem, STATUS_UNLOADED); // 子节点未加载
                        childItem.expandedProperty().addListener((obs, oldVal, newVal) -> {
                            if (newVal && STATUS_UNLOADED.equals(treeItemStatus.get(childItem))) {
                                loadChildrenAsync(childItem, childFile, depth + 1);
                            }
                        });
                        parentItem.getChildren().add(childItem);
                    }
                }
                treeItemStatus.put(parentItem, STATUS_LOADED);
            });
        });
    }

    //递归拼接TreeItem的完整路径
    public String getFullPath(TreeItem<String> item) {
        if (item.getParent() == null || item.getParent().getValue().equals("我的电脑")) {
            return item.getValue(); // 根节点或盘符
        }
        String parentPath = getFullPath(item.getParent());
        String fullPath = parentPath + File.separator + item.getValue(); // 拼接路径
        return fullPath.replace("\\\\", "\\");
    }

    //展开并选中目录树中的指定路径
    public void expandAndSelectInTree(String targetPath) {
        TreeItem<String> root = dirTreeView.getRoot();
        if (root == null) {
            return;
        }
        Platform.runLater(() -> expandPathStepByStep(root, targetPath, 0)); // 进入递归展开
    }

    //逐级展开路径（修复根节点处理）
    private void expandPathStepByStep(TreeItem<String> currentItem, String targetPath, int depth) {
        String indent = "  ".repeat(depth); // 缩进用于日志
        String currentPath = getFullPath(currentItem);

        if (depth > 15) { // 防止死循环
            return;
        }

        if (targetPath.equals(currentPath)) { // 找到目标
            currentItem.setExpanded(true);
            dirTreeView.getSelectionModel().select(currentItem);
            return;
        }

        if (currentItem.getValue().equals("我的电脑")) { // 根节点特殊处理
            String driveLetter = targetPath.substring(0, 3);
            TreeItem<String> driveItem = findChildByName(currentItem, driveLetter);
            if (driveItem == null) {
                return;
            }
            if (!driveItem.isExpanded()) {
                driveItem.setExpanded(true);
                Platform.runLater(() -> {
                    try {
                        Thread.sleep(300);// 等待加载盘符
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();// 恢复中断状态
                    }
                    Platform.runLater(() -> expandPathStepByStep(driveItem, targetPath, depth + 1));// 继续展开
                });
            } else {
                expandPathStepByStep(driveItem, targetPath, depth + 1);
            }
            return;
        }

        if (!targetPath.startsWith(currentPath)) { // 路径不匹配
            return;
        }

        String separator = File.separator.equals("\\") ? "\\\\" : File.separator;// 处理Windows路径分隔符
        String remaining = targetPath.substring(currentPath.length());// 获取剩余路径
        String[] parts = remaining.split(separator);// 分割路径部分

        parts = Arrays.stream(parts).filter(s -> !s.isEmpty()).toArray(String[]::new);// 过滤掉空字符串

        if (parts.length == 0) {
            return;
        }

        String nextName = parts[0];

        if (currentItem.getChildren().isEmpty()) { // 子节点未加载
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

        TreeItem<String> nextChild = findChildByName(currentItem, nextName);

        if (nextChild == null) {
            return;
        }

        if (!nextChild.isExpanded()) { // 递归展开
            nextChild.setExpanded(true);

            Platform.runLater(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Platform.runLater(() -> {
                    expandPathStepByStep(nextChild, targetPath, depth + 1);
                });
            });
        } else {
            expandPathStepByStep(nextChild, targetPath, depth + 1);
        }
    }

    //根据名称查找子节点
    private TreeItem<String> findChildByName(TreeItem<String> parent, String name) {
        for (TreeItem<String> child : parent.getChildren()) {
            if (child.getValue().equals(name)) { // 名称匹配
                return child;
            }
        }
        return null;
    }

    // 提供线程池关闭方法
    public void shutdown() {
        dirExecutor.shutdown(); // 关闭线程池
    }
}
