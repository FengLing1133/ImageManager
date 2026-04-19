package com.imanager.controller;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import com.imanager.util.AlterUtil;
import javafx.scene.control.Alert;

public class SlideShowController {

    @FXML
    private ImageView slideImageView; // 显示图片的控件
    @FXML
    private Label pageLabel; // 显示页码的标签
    @FXML
    private StackPane stackPane; // 图片容器

    private List<String> imagePaths; // 图片路径列表
    private int currentIndex = 0; // 当前图片索引
    private double baseScale = 1.0; // 基础缩放比例
    private double zoomScale = 1.0; // 当前缩放比例
    private Timeline playTimeline; // 播放定时器
    private double dragStartX = 0; // 拖拽起始X
    private double dragStartY = 0; // 拖拽起始Y
    private double imageStartTranslateX = 0; // 拖拽起始图片X
    private double imageStartTranslateY = 0; // 拖拽起始图片Y

    @FXML
    public void initialize() {
        slideImageView.setPreserveRatio(true); // 保持宽高比
        slideImageView.setSmooth(true); // 平滑处理
        slideImageView.setCache(false); // 不使用缓存

        playTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> nextImage())); // 1秒切换图片
        playTimeline.setCycleCount(Timeline.INDEFINITE); // 无限循环

        // 监听stackPane尺寸变化，自动适应图片
        stackPane.widthProperty().addListener((obs, oldVal, newVal) -> fitImageToWindow());
        stackPane.heightProperty().addListener((obs, oldVal, newVal) -> fitImageToWindow());

        stackPane.setOnScroll(event -> { // 鼠标滚轮缩放
            if (slideImageView.getImage() == null) return;
            double delta = event.getDeltaY();
            if (delta > 0) {
                zoomScale = Math.min(zoomScale * 1.05, 10.0); // 放大
            } else {
                zoomScale = Math.max(zoomScale / 1.05, 0.1); // 缩小
            }
            double finalScale = baseScale * zoomScale;
            slideImageView.setFitWidth(slideImageView.getImage().getWidth() * finalScale);
            slideImageView.setFitHeight(slideImageView.getImage().getHeight() * finalScale);
            // 缩放时不重置平移
        });

        // 鼠标拖拽平移支持
        stackPane.setOnMousePressed(event -> { // 按下记录起点
            if (slideImageView.getImage() == null) return;
            dragStartX = event.getSceneX();// 记录鼠标起始位置
            dragStartY = event.getSceneY();
            imageStartTranslateX = slideImageView.getTranslateX();// 记录图片当前平移位置
            imageStartTranslateY = slideImageView.getTranslateY();
        });
        stackPane.setOnMouseDragged(event -> { // 拖动时平移图片
            if (slideImageView.getImage() == null) return;
            double offsetX = event.getSceneX() - dragStartX;// 计算鼠标偏移
            double offsetY = event.getSceneY() - dragStartY;
            slideImageView.setTranslateX(imageStartTranslateX + offsetX);// 更新图片平移位置
            slideImageView.setTranslateY(imageStartTranslateY + offsetY);
        });
    }

    private void fitImageToWindow() {
        Image image = slideImageView.getImage();
        if (image == null) return;

        double windowWidth = stackPane.getWidth();// 获取容器尺寸
        double windowHeight = stackPane.getHeight();
        if (windowWidth <= 0 || windowHeight <= 0) return;

        double padding = 40.0; // 边距
        double availableWidth = Math.max(windowWidth - padding, 1.0);// 可用宽度，至少1像素
        double availableHeight = Math.max(windowHeight - padding, 1.0);// 可用高度，至少1像素

        double imgWidth = image.getWidth();
        double imgHeight = image.getHeight();

        double scale = Math.min(1.0, Math.min(availableWidth / imgWidth, availableHeight / imgHeight)); // 只缩小不放大
        baseScale = scale;
        zoomScale = 1.0;

        slideImageView.setFitWidth(imgWidth * scale);// 设置适应宽度
        slideImageView.setFitHeight(imgHeight * scale);
        slideImageView.setTranslateX(0); // 重置平移
        slideImageView.setTranslateY(0);
    }

    public void setImagePaths(List<String> imagePaths) {// 设置图片路径列表
        this.imagePaths = imagePaths == null ? null : new ArrayList<>(imagePaths); // 复制路径
        this.currentIndex = 0;
        if (this.imagePaths != null && !this.imagePaths.isEmpty()) {
            pageLabel.setText((currentIndex + 1) + "/" + this.imagePaths.size()); // 显示页码
            loadImage(this.imagePaths.get(currentIndex)); // 加载首图
        } else {
            pageLabel.setText("0/0");
            slideImageView.setImage(null);
        }
    }

    public void setCurrentIndex(int index) {// 设置当前图片索引
        if (imagePaths == null || imagePaths.isEmpty()) {
            return;
        }
        this.currentIndex = Math.max(0, Math.min(index, imagePaths.size() - 1)); // 边界保护
        loadImage(imagePaths.get(currentIndex));
        pageLabel.setText((currentIndex + 1) + "/" + imagePaths.size());
    }

    private void loadImage(String imagePath) {
        try {
            File imageFile = new File(imagePath);
            if (!imageFile.exists() || !imageFile.isFile()) { // 文件不存在
                AlterUtil.showAlert(
                    Alert.AlertType.ERROR,
                    "图片加载失败",
                    "文件不存在：" + imagePath,
                    stackPane != null && stackPane.getScene() != null ? stackPane.getScene().getWindow() : null
                );
                return;
            }
            Image image = new Image(imageFile.toURI().toString(), false); // 加载图片
            if (image.isError()) { // 解码失败
                AlterUtil.showAlert(
                    Alert.AlertType.ERROR,
                    "图片加载失败",
                    "解码错误：" + imagePath,
                    stackPane != null && stackPane.getScene() != null ? stackPane.getScene().getWindow() : null
                );
                if (image.getException() != null) {
                    image.getException().printStackTrace();
                }
                return;
            }
            slideImageView.setImage(image);
            zoomScale = 1.0;
            fitImageToWindow(); // 这里会重置平移
        } catch (Exception e) {
            AlterUtil.showAlert(
                Alert.AlertType.ERROR,
                "图片加载失败",
                "加载图片失败：" + imagePath,
                stackPane != null && stackPane.getScene() != null ? stackPane.getScene().getWindow() : null
            );
            e.printStackTrace();
        }
    }

    @FXML
    public void prevImage() {
        if (imagePaths == null || imagePaths.isEmpty()) return;
        currentIndex = (currentIndex - 1 + imagePaths.size()) % imagePaths.size(); // 上一张，循环
        loadImage(imagePaths.get(currentIndex));
        pageLabel.setText((currentIndex + 1) + "/" + imagePaths.size());
    }

    @FXML
    public void nextImage() {
        if (imagePaths == null || imagePaths.isEmpty()) return;
        currentIndex = (currentIndex + 1) % imagePaths.size(); // 下一张，循环
        loadImage(imagePaths.get(currentIndex));
        pageLabel.setText((currentIndex + 1) + "/" + imagePaths.size());
    }

    @FXML
    public void zoomIn() {
        if (playTimeline != null) playTimeline.stop(); // 缩放时停止播放
        zoomScale = Math.min(zoomScale * 1.1, 5.0); // 放大
        slideImageView.setFitWidth(slideImageView.getFitWidth() * 1.02);
        slideImageView.setFitHeight(slideImageView.getFitHeight() * 1.02);
        // 缩放时不重置平移
    }

    @FXML
    public void zoomOut() {
        if (playTimeline != null) playTimeline.stop(); // 缩放时停止播放
        zoomScale = Math.max(zoomScale / 1.1, 0.1); // 缩小
        slideImageView.setFitWidth(slideImageView.getFitWidth() / 1.02);
        slideImageView.setFitHeight(slideImageView.getFitHeight() / 1.02);
        // 缩放时不重置平移
    }

    @FXML
    public void startPlay() {
        if (imagePaths == null || imagePaths.isEmpty() || playTimeline == null) return;
        playTimeline.play(); // 开始自动播放
    }

    @FXML
    public void stopPlay() {
        if (playTimeline != null) {
            playTimeline.stop(); // 停止自动播放
        }
    }

    @FXML
    private void stopZooming(javafx.scene.input.MouseEvent event) {
        event.consume(); // 阻止事件冒泡
    }
}
