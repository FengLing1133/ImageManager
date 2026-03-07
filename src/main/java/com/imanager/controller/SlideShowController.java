package com.imanager.controller;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.util.Duration;

public class SlideShowController {

    @FXML
    private ImageView slideImageView;

    private int currentindex = 0;
    private Timeline slideTimeLine;

    private Image[] sampleImages = {};

    @FXML
    public void initialize() {
        slideImageView.setImage(sampleImages[0]);
    }

    @FXML
    private void prevImage() {
        currentindex = (currentindex - 1 + sampleImages.length) % sampleImages.length;
        slideImageView.setImage(sampleImages[currentindex]);
    }

    @FXML
    private void nextImage() {
        currentindex = (currentindex + 1) % sampleImages.length;
        slideImageView.setImage(sampleImages[currentindex]);
    }

    @FXML
    private void startSlideShow() {
        if(slideTimeLine == null){
            slideTimeLine = new Timeline(new KeyFrame(Duration.seconds(2), event -> nextImage()));
            slideTimeLine.setCycleCount(Timeline.INDEFINITE);
        }
        slideTimeLine.play();
    }

    @FXML
    private void stopSlideShow() {
        if(slideTimeLine != null){
            slideTimeLine.stop();
        }
    }
}
