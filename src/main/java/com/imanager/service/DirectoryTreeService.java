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
        this.dirTreeView = dirTreeView;
    }

    private final TreeView<String> dirTreeView;
    //目录节点加载状态缓存
    private final Map<TreeItem<String>, String> treeItemStatus = new HashMap<>();
    // 状态常量
    private static final String STATUS_UNLOADED = "unloaded";   // 未加载
    private static final String STATUS_LOADING = "loading";     // 加载中
    private static final String STATUS_LOADED = "loaded";       // 已加载

    //目录加载线程池
    private final ExecutorService dirExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);//线程设置为守护线程，程序退出时自动关闭
        thread.setName("Directory Loader");
        return thread;
    });

    //初始化全盘符目录树（支持扫描全硬盘）
    public void initDirectoryTree() {
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
        if (!parentItem.isExpanded()) {
            parentItem.setExpanded(true);
        }
        dirExecutor.submit(() -> {
            File[] childFiles = parentFile.listFiles(File::isDirectory);
            if (childFiles == null) {
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
                                && !name.equals("Windows")
                                && !name.equals("Program Files")
                                && !file.isHidden();
                    })
                    .sorted((f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()))
                    .toList();
            System.out.println("🔍 加载 " + parentFile.getName() + " 的子目录数量：" + filteredFiles.size());
            Platform.runLater(() -> {
                if (filteredFiles.isEmpty()) {
                    TreeItem<String> emptyItem = new TreeItem<>("无子目录");
                    parentItem.getChildren().add(emptyItem);
                } else {
                    for (File childFile : filteredFiles) {
                        TreeItem<String> childItem = new TreeItem<>(childFile.getName());
                        treeItemStatus.put(childItem, STATUS_UNLOADED);
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
            return item.getValue();
        }
        String parentPath = getFullPath(item.getParent());
        String fullPath = parentPath + File.separator + item.getValue();
        return fullPath.replace("\\\\", "\\");
    }

    //展开并选中目录树中的指定路径
    public void expandAndSelectInTree(String targetPath) {
        System.out.println("\n========== 开始同步目录树 ==========");
        System.out.println("目标路径: " + targetPath);

        TreeItem<String> root = dirTreeView.getRoot();
        if (root == null) {
            System.out.println("✗ 目录树根节点为null");
            return;
        }

        System.out.println("根节点: " + root.getValue());
        System.out.println("根节点子节点数: " + root.getChildren().size());

        Platform.runLater(() -> expandPathStepByStep(root, targetPath, 0));
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
                    System.out.println(indent + "↻ 继续��度" + (depth + 1));
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

    // 提供线程池关闭方法
    public void shutdown() {
        dirExecutor.shutdown();
    }
}
