module com.imanager {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;

    opens com.imanager.controller to javafx.fxml;
    exports com.imanager;
}