package com.imanager.controller;


import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.File;
import java.util.Locale;

public class MainController {

    // 与 FXML 中的 fx:id 绑定
    @FXML
    private TreeView<String> dirTreeView;

    @FXML
    private FlowPane imageFlowPane;

    @FXML
    private void clickBlank(){
        //临时实现：清空FlowPane中所有节点选中的样式
        imageFlowPane.getChildren().forEach(node -> node.setStyle( ""));
    }


    //程序启动后初始化
    @FXML
    public void initialize() {
        //初始化目录树(加载本地文件系统)
        initDirectoryTree();
    }

    //点击Play按钮，打开幻灯片界面
    @FXML
    private void openSlideShow(){
        try{
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/slideShow.fxml"));
            Stage slideStage = new Stage();
            slideStage.setTitle("幻灯片播放");
            slideStage.setScene(new javafx.scene.Scene(loader.load()));
            slideStage.show();
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    //初始化目录树
    private void initDirectoryTree() {
        //以用户目录为根
        File rootFile = File.listRoots()[0];
        TreeItem<String> rootItem = new TreeItem<>(rootFile.getAbsolutePath());
        rootItem.setExpanded(true);//展开

        //递归添加子节点
        addChildrenRecursively(rootItem, rootFile);

        //设置到TreeView
        dirTreeView.setRoot(rootItem);

        //监听目录树点击事件，切换文件夹
        dirTreeView.setOnMouseClicked(event -> {
            TreeItem<String> selectedItem = dirTreeView.getSelectionModel().getSelectedItem();
            if(selectedItem != null){
                String path = selectedItem.getValue();
                File selectedDir = new File(path);
                if(selectedDir.isDirectory()){
                    //加载该文件夹下的图片到右侧FlowPane
                    loadImagesToFlowPane(selectedDir);
                }
            }
        });
    }

    //递归添加子文件夹节点
    private void addChildrenRecursively(TreeItem<String> parentItem, File parentFile) {
        File[] children = parentFile.listFiles();
        if(children != null){
            for(File child : children){
                if(child.isDirectory() && !child.isHidden()){//只显示非隐藏文件夹
                    TreeItem<String> childItem = new TreeItem<>(child.getAbsolutePath());
                    parentItem.getChildren().add(childItem);
                    //递归
                    addChildrenRecursively(childItem, child);
                }
            }
        }
    }

    //加载文件夹中的图片到FlowPane
    private void loadImagesToFlowPane(File dir){
        imageFlowPane.getChildren().clear();//清空之前的缩略图

        File[] files = dir.listFiles();//获取文件夹下的所有文件
        if(files != null){
            for(File file : files){
                String name = file.getName().toLowerCase();
                //只加载常见图片格式
                if(name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".gif") || name.endsWith(".png")){
                    try{
                        //创建缩略图
                        Image image = new Image(file.toURI().toString(), 150, 150, true, true);
                        ImageView imageView = new ImageView(image);
                        imageView.setFitWidth(150);
                        imageView.setFitHeight(150);
                        imageView.setPreserveRatio(true);

                        //点击选中效果
                        imageView.setOnMouseClicked(event -> {
                            //先清空其他选中样式
                            imageFlowPane.getChildren().forEach(node -> node.setStyle(""));
                            //给当前选中的加边框
                            imageView.setStyle("-fx-border-color: blue; -fx-border-width: 2px;");
                        });

                        //添加到FlowPane
                        FlowPane.setMargin(imageView, new Insets(5));
                        imageFlowPane.getChildren().add(imageView);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}
