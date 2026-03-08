package com.imanager.controller;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.Label;
import javafx.util.Duration;

import java.io.File;
import java.util.List;

public class SlideShowController {

    @FXML
    private ImageView slideImageView;
    @FXML
    private Label pageLabel;//页码标签

    private List<String> imagePaths;//图片路径列表(从主控制器传递)
    private int currentIndex = 0;//当前图片索引
    private double zoomScale = 1.0;//图片缩放比例
    private Timeline playTimeline;//自动播放的时间线

    //初始化方法(FXML加载后自动执行)
    @FXML
    public void initialize() {
        // 调试：验证pageLabel是否绑定成功（启动后看控制台）
        System.out.println("pageLabel绑定状态：" + (pageLabel == null ? "失败（null）" : "成功"));

        //初始化自动播放时间线
        playTimeline = new Timeline(new KeyFrame(Duration.seconds(2), event -> nextImage()));
        playTimeline.setCycleCount(Timeline.INDEFINITE);//无限循环
    }

    //接收从主控制器传递的图片路径列表
    public void setImagePaths(List<String> imagePaths) {
        this.imagePaths = imagePaths;
        //更新页码标签
        if (imagePaths != null && !imagePaths.isEmpty()) {
            pageLabel.setText((currentIndex + 1) + "/" + imagePaths.size());
            //显示第一张图片
            loadImage(imagePaths.get(currentIndex));
        } else {
            pageLabel.setText("0/0");
            slideImageView.setImage(null);
            System.out.println("提示：未收集到图片路径，幻灯片无图片可显示");
        }
    }

    //加载图片到ImageView
    private void loadImage(String imagePath) {
        try {
            Image image = new Image(new File(imagePath).toURI().toString(),
                    slideImageView.getFitWidth(),
                    slideImageView.getFitHeight(),
                    true, true);
            slideImageView.setImage(image);
            slideImageView.setScaleX(zoomScale);
            slideImageView.setScaleY(zoomScale);
        } catch (Exception e) {
            System.out.println("加载图片失败：" + imagePath);
            e.printStackTrace();
        }
    }

    //上一张图片
    @FXML
    public void prevImage() {
        if (imagePaths == null || imagePaths.isEmpty()) return;
        currentIndex = (currentIndex - 1 + imagePaths.size()) % imagePaths.size();
        loadImage(imagePaths.get(currentIndex));
        pageLabel.setText((currentIndex + 1) + "/" + imagePaths.size());
    }

    //下一张图片
    @FXML
    public void nextImage() {
        if (imagePaths == null || imagePaths.isEmpty()) return;
        currentIndex = (currentIndex + 1) % imagePaths.size();
        loadImage(imagePaths.get(currentIndex));
        pageLabel.setText((currentIndex + 1) + "/" + imagePaths.size());
    }

    //放大图片
    @FXML
    public void zoomIn() {
        zoomScale += 0.1;
        slideImageView.setScaleX(zoomScale);
        slideImageView.setScaleY(zoomScale);
    }

    //缩小图片
    @FXML
    public void zoomOut() {
        if (zoomScale > 0.1) {
            zoomScale -= 0.1;
            slideImageView.setScaleX(zoomScale);
            slideImageView.setScaleY(zoomScale);
        }
    }

    //开始自动播放
    @FXML
    public void startPlay() {
        if (imagePaths == null || imagePaths.isEmpty()) return;
        playTimeline.play();
    }

    //停止自动播放
    @FXML
    public void stopPlay() {
        playTimeline.stop();
    }
}
