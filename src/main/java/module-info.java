module com.imanager {
    requires javafx.controls;
    requires javafx.fxml;


    opens com.imanager.controller to javafx.fxml;
    exports com.imanager;
}