package com.imanager.service;

import javafx.scene.layout.VBox;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;

public class FileService {

    // 仅保留文件操作相关方法，不持有UI状态和控件

    // 判断是否为图片文件
    public boolean isImageFile(File file) {
        if (!file.isFile()) return false;
        String name = file.getName();
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    // 删除选中文件
    public void deleteSelected(Set<VBox> selectedVBoxes, Map<VBox, File> vBoxToFile, List<File> allFiles, Consumer<List<VBox>> onFilesDeleted) {
        if (selectedVBoxes.isEmpty()) return;
        List<VBox> toRemove = new ArrayList<>(selectedVBoxes);
        for (VBox vBox : toRemove) {
            File file = vBoxToFile.get(vBox);
            if (file != null && file.delete()) {
                selectedVBoxes.remove(vBox);
                vBoxToFile.remove(vBox);
                allFiles.remove(file);
            }
        }
        if (onFilesDeleted != null) onFilesDeleted.accept(toRemove);
    }

    // 复制选中文件
    public void copySelected(Set<VBox> selectedVBoxes, Map<VBox, File> vBoxToFile, List<File> copiedFiles) {
        copiedFiles.clear();
        for (VBox vBox : selectedVBoxes) {
            File file = vBoxToFile.get(vBox);
            if (file != null) {
                copiedFiles.add(file);
            }
        }
    }

    // 重命名操作建议由Controller实现，FileService只提供重命名逻辑
    public boolean renameFile(File file, String newNameWithExt) {
        File newFile = new File(file.getParent(), newNameWithExt);
        return file.renameTo(newFile);
    }

    // 粘贴文件
    public List<File> pasteFiles(List<File> copiedFiles, File currentDir) {
        List<File> pastedFiles = new ArrayList<>();
        if (copiedFiles == null || copiedFiles.isEmpty() || currentDir == null) return pastedFiles;
        for (File src : copiedFiles) {
            try {
                File dest = new File(currentDir, src.getName());
                if (dest.exists()) {
                    String name = src.getName();
                    int dotIndex = name.lastIndexOf('.');
                    String base = dotIndex > 0 ? name.substring(0, dotIndex) : name;
                    String ext = dotIndex > 0 ? name.substring(dotIndex) : "";
                    int count = 1;
                    while (dest.exists()) {
                        dest = new File(currentDir, base + "(" + count + ")" + ext);
                        count++;
                    }
                }
                Files.copy(src.toPath(), dest.toPath());
                pastedFiles.add(dest);
            } catch (Exception e) {
                // 由Controller处理异常和提示
            }
        }
        return pastedFiles;
    }

    // 格式化文件大小
    public String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}
