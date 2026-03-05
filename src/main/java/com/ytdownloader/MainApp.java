package com.ytdownloader;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {

    public static final String VERSION = "1.0.0";

    @Override
    public void start(Stage stage) throws Exception {
        // 设置 Dock 图标（macOS）
        try {
            java.awt.Taskbar taskbar = java.awt.Taskbar.getTaskbar();
            java.net.URL iconUrl = getClass().getResource("logo.png");
            if (iconUrl != null) {
                java.awt.Image dockIcon = javax.imageio.ImageIO.read(iconUrl);
                taskbar.setIconImage(dockIcon);
            }
        } catch (Exception ignored) {}

        // 设置窗口图标
        try {
            javafx.scene.image.Image icon = new javafx.scene.image.Image(
                getClass().getResourceAsStream("logo.png"));
            stage.getIcons().add(icon);
        } catch (Exception ignored) {}

        FXMLLoader loader = new FXMLLoader(getClass().getResource("main.fxml"));
        Scene scene = new Scene(loader.load(), 700, 800);

        // 读取主题设置
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences
            .userNodeForPackage(MainApp.class);
        String theme = prefs.get("theme", "light");
        applyTheme(scene, theme);

        // 传递 scene 和 prefs 给 controller
        MainController controller = loader.getController();
        controller.setScene(scene);
        controller.setAppPrefs(prefs);

        stage.setTitle("视频下载器 v" + VERSION);
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();
    }

    public static void applyTheme(Scene scene, String theme) {
        scene.getStylesheets().clear();
        String css = theme.equals("light") ? "style-light.css" : "style-dark.css";
        scene.getStylesheets().add(MainApp.class.getResource(css).toExternalForm());
    }

    public static void main(String[] args) {
        launch(args);
    }
}
