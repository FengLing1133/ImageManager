package com.imanager.controller;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.Label;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.io.File;
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

    @FXML
    public void initialize() {
        slideImageView.setPreserveRatio(true);
        slideImageView.setSmooth(true);
        slideImageView.setCache(false);

        slideImageView.imageProperty().addListener((obs, oldImg, newImg) -> {
            if (newImg != null) {
                Platform.runLater(() -> {
                    double windowWidth = stackPane.getWidth();
                    double windowHeight = stackPane.getHeight();
                    double padding = 40.0;

                    double scaleX = (windowWidth - padding) / newImg.getWidth();
                    double scaleY = (windowHeight - padding) / newImg.getHeight();
                    baseScale = Math.min(scaleX, scaleY);
                    zoomScale = 1.0;

                    slideImageView.setFitWidth(newImg.getWidth() * baseScale);
                    slideImageView.setFitHeight(newImg.getHeight() * baseScale);
                });
            }
        });

        stackPane.setOnScroll(event -> {
            if (slideImageView.getImage() == null) return;

            double delta = event.getDeltaY();

            if (delta > 0) {
                zoomScale = Math.min(zoomScale * 1.15, 10.0);
            } else {
                zoomScale = Math.max(zoomScale / 1.15, 0.1);
            }

            double finalScale = baseScale * zoomScale;
            slideImageView.setFitWidth(slideImageView.getImage().getWidth() * finalScale);
            slideImageView.setFitHeight(slideImageView.getImage().getHeight() * finalScale);
        });
    }

    private void fitImageToWindow() {
        Image image = slideImageView.getImage();
        if (image == null) return;

        double windowWidth = stackPane.getWidth();
        double windowHeight = stackPane.getHeight();

        if (windowWidth <= 0 || windowHeight <= 0) return;

        double padding = 40.0;
        double availableWidth = windowWidth - padding;
        double availableHeight = windowHeight - padding;

        double scaleX = availableWidth / image.getWidth();
        double scaleY = availableHeight / image.getHeight();
        baseScale = Math.min(scaleX, scaleY);
        zoomScale = 1.0;

        slideImageView.setFitWidth(image.getWidth() * baseScale);
        slideImageView.setFitHeight(image.getHeight() * baseScale);
    }

    public void setImagePaths(List<String> imagePaths) {
        this.imagePaths = imagePaths;
        this.currentIndex = 0;
        if (imagePaths != null && !imagePaths.isEmpty()) {
            pageLabel.setText((currentIndex + 1) + "/" + imagePaths.size());
            Platform.runLater(() -> loadImage(imagePaths.get(currentIndex)));
        } else {
            pageLabel.setText("0/0");
            slideImageView.setImage(null);
        }
    }

    public void setCurrentIndex(int index) {
        this.currentIndex = index;
        loadImage(imagePaths.get(currentIndex));
        pageLabel.setText((currentIndex + 1) + "/" + imagePaths.size());
    }

    private void loadImage(String imagePath) {
        try {
            Image image = new Image(new File(imagePath).toURI().toString());
            slideImageView.setImage(image);
            zoomScale = 1.0;
        } catch (Exception e) {
            System.out.println("加载图片失败：" + imagePath);
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
        slideImageView.setFitWidth(slideImageView.getFitWidth() * 1.1);
        slideImageView.setFitHeight(slideImageView.getFitHeight() * 1.1);
    }

    @FXML
    public void zoomOut() {
        if (playTimeline != null) playTimeline.stop();
        zoomScale = Math.max(zoomScale / 1.1, 0.1);
        slideImageView.setFitWidth(slideImageView.getFitWidth() / 1.1);
        slideImageView.setFitHeight(slideImageView.getFitHeight() / 1.1);
    }

    @FXML
    public void startPlay() {
        if (imagePaths == null || imagePaths.isEmpty()) return;
        playTimeline.play();
    }

    @FXML
    public void stopPlay() {
        playTimeline.stop();
    }

    @FXML
    private void stopZooming(javafx.scene.input.MouseEvent event) {
        event.consume();
    }
}
