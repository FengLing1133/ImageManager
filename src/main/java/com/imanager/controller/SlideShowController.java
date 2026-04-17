package com.imanager.controller;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SlideShowController {

    @FXML
    private ImageView slideImageView;
    @FXML
    private Label pageLabel;
    @FXML
    private StackPane stackPane;

    private List<String> imagePaths;
    private int currentIndex = 0;
    private double baseScale = 1.0;
    private double zoomScale = 1.0;
    private Timeline playTimeline;

    // 拖拽相关变量
    private double dragStartX = 0;
    private double dragStartY = 0;
    private double imageStartTranslateX = 0;
    private double imageStartTranslateY = 0;

    @FXML
    public void initialize() {
        slideImageView.setPreserveRatio(true);
        slideImageView.setSmooth(true);
        slideImageView.setCache(false);

        playTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> nextImage()));
        playTimeline.setCycleCount(Timeline.INDEFINITE);

        // 监听stackPane尺寸变化，自动适应图片
        stackPane.widthProperty().addListener((obs, oldVal, newVal) -> fitImageToWindow());
        stackPane.heightProperty().addListener((obs, oldVal, newVal) -> fitImageToWindow());

        stackPane.setOnScroll(event -> {
            if (slideImageView.getImage() == null) return;
            double delta = event.getDeltaY();
            if (delta > 0) {
                zoomScale = Math.min(zoomScale * 1.05, 10.0);
            } else {
                zoomScale = Math.max(zoomScale / 1.05, 0.1);
            }
            double finalScale = baseScale * zoomScale;
            slideImageView.setFitWidth(slideImageView.getImage().getWidth() * finalScale);
            slideImageView.setFitHeight(slideImageView.getImage().getHeight() * finalScale);
            // 缩放时不重置平移
        });

        // 鼠标拖拽平移支持
        stackPane.setOnMousePressed(event -> {
            if (slideImageView.getImage() == null) return;
            dragStartX = event.getSceneX();
            dragStartY = event.getSceneY();
            imageStartTranslateX = slideImageView.getTranslateX();
            imageStartTranslateY = slideImageView.getTranslateY();
        });
        stackPane.setOnMouseDragged(event -> {
            if (slideImageView.getImage() == null) return;
            double offsetX = event.getSceneX() - dragStartX;
            double offsetY = event.getSceneY() - dragStartY;
            slideImageView.setTranslateX(imageStartTranslateX + offsetX);
            slideImageView.setTranslateY(imageStartTranslateY + offsetY);
        });
        stackPane.setOnMouseReleased(event -> {
            // 可选：松开鼠标后可做边界限制
        });
    }

    private void fitImageToWindow() {
        Image image = slideImageView.getImage();
        if (image == null) return;

        double windowWidth = stackPane.getWidth();
        double windowHeight = stackPane.getHeight();
        if (windowWidth <= 0 || windowHeight <= 0) return;

        double padding = 40.0;
        double availableWidth = Math.max(windowWidth - padding, 1.0);
        double availableHeight = Math.max(windowHeight - padding, 1.0);

        double imgWidth = image.getWidth();
        double imgHeight = image.getHeight();

        // 只缩小不放大
        double scale = Math.min(1.0, Math.min(availableWidth / imgWidth, availableHeight / imgHeight));
        baseScale = scale;
        zoomScale = 1.0;

        slideImageView.setFitWidth(imgWidth * scale);
        slideImageView.setFitHeight(imgHeight * scale);
        // 重置平移
        slideImageView.setTranslateX(0);
        slideImageView.setTranslateY(0);
    }

    public void setImagePaths(List<String> imagePaths) {
        this.imagePaths = imagePaths == null ? null : new ArrayList<>(imagePaths);
        this.currentIndex = 0;
        if (this.imagePaths != null && !this.imagePaths.isEmpty()) {
            pageLabel.setText((currentIndex + 1) + "/" + this.imagePaths.size());
            loadImage(this.imagePaths.get(currentIndex));
        } else {
            pageLabel.setText("0/0");
            slideImageView.setImage(null);
        }
    }

    public void setCurrentIndex(int index) {
        if (imagePaths == null || imagePaths.isEmpty()) {
            return;
        }
        this.currentIndex = Math.max(0, Math.min(index, imagePaths.size() - 1));
        loadImage(imagePaths.get(currentIndex));
        pageLabel.setText((currentIndex + 1) + "/" + imagePaths.size());
    }

    private void loadImage(String imagePath) {
        try {
            File imageFile = new File(imagePath);
            if (!imageFile.exists() || !imageFile.isFile()) {
                System.out.println("加载图片失败，文件不存在：" + imagePath);
                return;
            }
            Image image = new Image(imageFile.toURI().toString(), false);
            if (image.isError()) {
                System.out.println("加载图片失败，解码错误：" + imagePath);
                if (image.getException() != null) {
                    image.getException().printStackTrace();
                }
                return;
            }
            slideImageView.setImage(image);
            zoomScale = 1.0;
            fitImageToWindow(); // 这里会重置平移
        } catch (Exception e) {
            System.out.println("加载图片失败：" + imagePath);
            e.printStackTrace();
        }
    }

    @FXML
    public void prevImage() {
        if (imagePaths == null || imagePaths.isEmpty()) return;
        currentIndex = (currentIndex - 1 + imagePaths.size()) % imagePaths.size();
        loadImage(imagePaths.get(currentIndex));
        pageLabel.setText((currentIndex + 1) + "/" + imagePaths.size());
    }

    @FXML
    public void nextImage() {
        if (imagePaths == null || imagePaths.isEmpty()) return;
        currentIndex = (currentIndex + 1) % imagePaths.size();
        loadImage(imagePaths.get(currentIndex));
        pageLabel.setText((currentIndex + 1) + "/" + imagePaths.size());
    }

    @FXML
    public void zoomIn() {
        if (playTimeline != null) playTimeline.stop();
        zoomScale = Math.min(zoomScale * 1.1, 5.0);
        slideImageView.setFitWidth(slideImageView.getFitWidth() * 1.02);
        slideImageView.setFitHeight(slideImageView.getFitHeight() * 1.02);
        // 缩放时不重置平移
    }

    @FXML
    public void zoomOut() {
        if (playTimeline != null) playTimeline.stop();
        zoomScale = Math.max(zoomScale / 1.1, 0.1);
        slideImageView.setFitWidth(slideImageView.getFitWidth() / 1.02);
        slideImageView.setFitHeight(slideImageView.getFitHeight() / 1.02);
        // 缩放时不重置平移
    }

    @FXML
    public void startPlay() {
        if (imagePaths == null || imagePaths.isEmpty() || playTimeline == null) return;
        playTimeline.play();
    }

    @FXML
    public void stopPlay() {
        if (playTimeline != null) {
            playTimeline.stop();
        }
    }

    @FXML
    private void stopZooming(javafx.scene.input.MouseEvent event) {
        event.consume();
    }
}
