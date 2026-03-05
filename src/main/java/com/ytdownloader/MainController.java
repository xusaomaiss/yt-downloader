package com.ytdownloader;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.io.*;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainController {

    // 输入区
    @FXML private TextArea urlArea;
    @FXML private Button fetchBtn;
    @FXML private Button pasteBtn;
    @FXML private Button clearBtn;

    // 下载设置
    @FXML private ComboBox<String> qualityBox;
    @FXML private ComboBox<String> formatBox;
    @FXML private TextField outputPathField;
    @FXML private Button browseBtn;

    // 预览
    @FXML private VBox previewBox;
    @FXML private ImageView thumbnailView;
    @FXML private Label videoTitleLabel;
    @FXML private Label videoDurationLabel;
    @FXML private VBox thumbPlaceholder;
    @FXML private ProgressIndicator thumbSpinner;

    // 下载
    @FXML private Button downloadBtn;
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;

    // 完成提示
    @FXML private HBox completionBar;
    @FXML private Label completionLabel;

    // 任务列表
    @FXML private TableView<DownloadTask> taskTable;
    @FXML private TableColumn<DownloadTask, String> colTitle;
    @FXML private TableColumn<DownloadTask, String> colQuality;
    @FXML private TableColumn<DownloadTask, String> colProgress;
    @FXML private TableColumn<DownloadTask, String> colStatus;

    // 日志
    @FXML private TextArea logArea;

    private Scene scene;
    private Preferences appPrefs;
    private final List<String> downloadQueue = new ArrayList<>();
    private final ObservableList<DownloadTask> taskList = FXCollections.observableArrayList();
    private int currentIndex = 0;

    // Fix 5: 缓存 yt-dlp 路径，避免每次重新探测
    private String cachedYtDlpPath = null;

    // Fix 6: 线程池替代裸 new Thread()
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    private static final String PREF_OUTPUT_PATH = "outputPath";
    private static final String PREF_PROXY_HOST  = "proxyHost";
    private static final String PREF_PROXY_PORT  = "proxyPort";
    private static final String PREF_HISTORY     = "downloadHistory";
    private final Preferences prefs = Preferences.userNodeForPackage(MainController.class);

    // Fix 4: YouTube URL 正则
    private static final Pattern YOUTUBE_URL_PATTERN =
        Pattern.compile("https?://(www\\.)?(youtube\\.com/|youtu\\.be/)\\S+");

    public void setScene(Scene scene) { this.scene = scene; }
    public void setAppPrefs(Preferences prefs) { this.appPrefs = prefs; }

    // ── DownloadTask 数据模型 ──────────────────────────────────────────
    public static class DownloadTask {
        private final javafx.beans.property.SimpleStringProperty title;
        private final javafx.beans.property.SimpleStringProperty quality;
        private final javafx.beans.property.SimpleStringProperty progress;
        private final javafx.beans.property.SimpleStringProperty status;

        public DownloadTask(String title, String quality) {
            this.title    = new javafx.beans.property.SimpleStringProperty(title);
            this.quality  = new javafx.beans.property.SimpleStringProperty(quality);
            this.progress = new javafx.beans.property.SimpleStringProperty("0%");
            this.status   = new javafx.beans.property.SimpleStringProperty("等待中");
        }
        public String getTitle()    { return title.get(); }
        public String getQuality()  { return quality.get(); }
        public String getProgress() { return progress.get(); }
        public String getStatus()   { return status.get(); }
        public void setProgress(String v) { progress.set(v); }
        public void setStatus(String v)   { status.set(v); }
        public javafx.beans.property.SimpleStringProperty titleProperty()    { return title; }
        public javafx.beans.property.SimpleStringProperty qualityProperty()  { return quality; }
        public javafx.beans.property.SimpleStringProperty progressProperty() { return progress; }
        public javafx.beans.property.SimpleStringProperty statusProperty()   { return status; }
    }

    // ── 初始化 ────────────────────────────────────────────────────────
    @FXML
    public void initialize() {
        String desktop = Paths.get(System.getProperty("user.home"), "Desktop").toString();
        outputPathField.setText(prefs.get(PREF_OUTPUT_PATH, desktop));

        qualityBox.setItems(FXCollections.observableArrayList("最佳画质", "1080p", "720p", "480p", "360p"));
        qualityBox.setValue("720p");

        formatBox.setItems(FXCollections.observableArrayList("MP4（视频+音频）", "MP3（仅音频）", "仅视频（无声）"));
        formatBox.setValue("MP4（视频+音频）");

        colTitle.setCellValueFactory(new PropertyValueFactory<>("title"));
        colQuality.setCellValueFactory(new PropertyValueFactory<>("quality"));
        colProgress.setCellValueFactory(new PropertyValueFactory<>("progress"));
        colStatus.setCellValueFactory(new PropertyValueFactory<>("status"));
        taskTable.setItems(taskList);
        taskTable.setPlaceholder(new Label("暂无下载任务"));

        downloadBtn.setDisable(true);
        previewBox.setVisible(false);
        previewBox.setManaged(false);
        completionBar.setVisible(false);
        completionBar.setManaged(false);

        // 自动检测剪贴板
        urlArea.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) autoFillFromClipboard();
        });
    }

    // ── 剪贴板 ────────────────────────────────────────────────────────
    // Fix 7: 提取公共方法，消除 autoFillFromClipboard 和 onPaste 的重复代码
    private String readClipboard() {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            return (String) cb.getData(DataFlavor.stringFlavor);
        } catch (Exception e) {
            return null;
        }
    }

    private void autoFillFromClipboard() {
        String text = readClipboard();
        if (text != null && isValidYoutubeUrl(text) && urlArea.getText().trim().isEmpty()) {
            urlArea.setText(text.trim());
            appendLog("📋 已自动填入剪贴板链接\n");
        }
    }

    @FXML private void onPaste() {
        String text = readClipboard();
        if (text != null && !text.trim().isEmpty()) {
            urlArea.setText(text.trim());
            appendLog("📋 已粘贴链接\n");
        } else {
            appendLog("粘贴失败：剪贴板为空\n");
        }
    }

    @FXML private void onClearUrl() {
        urlArea.clear();
        previewBox.setVisible(false);
        previewBox.setManaged(false);
        completionBar.setVisible(false);
        completionBar.setManaged(false);
        downloadBtn.setDisable(true);
        downloadQueue.clear();
        taskList.clear();
    }

    @FXML private void onClearLog() { logArea.clear(); }

    // ── 解析视频 ──────────────────────────────────────────────────────
    // Fix 4: 校验 URL 格式，过滤无效链接
    private boolean isValidYoutubeUrl(String url) {
        return YOUTUBE_URL_PATTERN.matcher(url).find();
    }

    @FXML
    private void onFetch() {
        String raw = urlArea.getText().trim();
        if (raw.isEmpty()) { showAlert("请输入至少一个 YouTube 视频链接"); return; }

        downloadQueue.clear();
        List<String> invalid = new ArrayList<>();
        for (String line : raw.split("\\n")) {
            String url = line.trim();
            if (url.isEmpty()) continue;
            if (isValidYoutubeUrl(url)) {
                downloadQueue.add(url);
            } else {
                invalid.add(url);
            }
        }

        if (!invalid.isEmpty()) {
            appendLog("⚠️ 已忽略 " + invalid.size() + " 个无效链接：\n");
            invalid.forEach(u -> appendLog("  " + u + "\n"));
        }
        if (downloadQueue.isEmpty()) { showAlert("未检测到有效的 YouTube 链接"); return; }
        fetchVideoInfo(downloadQueue.get(0));
    }

    private void fetchVideoInfo(String url) {
        fetchBtn.setDisable(true);
        downloadBtn.setDisable(true);
        statusLabel.setText("正在解析视频...");
        logArea.clear();
        completionBar.setVisible(false);
        completionBar.setManaged(false);

        Task<String[]> task = new Task<>() {
            @Override
            protected String[] call() throws Exception {
                List<String> cmd = new ArrayList<>();
                cmd.add(getYtDlpPath());
                cmd.addAll(getProxyArgs());
                cmd.add("--print");
                cmd.add("%(title)s\n%(duration_string)s\n%(thumbnail)s");
                cmd.add("--no-playlist");
                cmd.add(url);
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                List<String> output = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("WARNING") && !t.startsWith("Deprecated"))
                        output.add(t);
                }
                process.waitFor();
                return output.toArray(new String[0]);
            }
        };

        task.setOnSucceeded(e -> {
            String[] info = task.getValue();
            if (info.length >= 1 && !info[0].contains("ERROR")) {
                String title    = info.length > 0 ? info[0] : "未知标题";
                String duration = info.length > 1 ? info[1] : "";
                String thumbUrl = info.length > 2 ? info[2] : "";

                videoTitleLabel.setText(title);
                videoDurationLabel.setText("时长：" + duration);
                previewBox.setVisible(true);
                previewBox.setManaged(true);

                if (!thumbUrl.isEmpty()) loadThumbnailViaYtDlp(downloadQueue.get(0));

                int count = downloadQueue.size();
                statusLabel.setText(count > 1 ? "✅ 已添加 " + count + " 个视频到队列" : "✅ " + title);
                logArea.appendText("标题：" + title + "\n时长：" + duration + "\n");
                if (count > 1) {
                    logArea.appendText("批量模式：共 " + count + " 个视频\n");
                    for (int i = 0; i < downloadQueue.size(); i++)
                        logArea.appendText("  " + (i+1) + ". " + downloadQueue.get(i) + "\n");
                }
                downloadBtn.setDisable(false);
            } else {
                statusLabel.setText("❌ 解析失败，请检查链接或网络");
            }
            fetchBtn.setDisable(false);
        });

        task.setOnFailed(e -> {
            statusLabel.setText("❌ 解析失败：" + task.getException().getMessage());
            fetchBtn.setDisable(false);
        });

        executor.submit(task); // Fix 6
    }

    // ── 浏览路径 ──────────────────────────────────────────────────────
    @FXML
    private void onBrowse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择下载目录");
        File currentDir = new File(outputPathField.getText());
        if (currentDir.exists()) chooser.setInitialDirectory(currentDir);
        File dir = chooser.showDialog(browseBtn.getScene().getWindow());
        if (dir != null) {
            outputPathField.setText(dir.getAbsolutePath());
            prefs.put(PREF_OUTPUT_PATH, dir.getAbsolutePath());
        }
    }

    // ── 开始下载 ──────────────────────────────────────────────────────
    @FXML
    private void onDownload() {
        if (downloadQueue.isEmpty()) { showAlert("请先解析视频"); return; }
        currentIndex = 0;
        downloadBtn.setDisable(true);
        fetchBtn.setDisable(true);
        progressBar.setProgress(0);
        logArea.clear();
        completionBar.setVisible(false);
        completionBar.setManaged(false);

        taskList.clear();
        for (String url : downloadQueue) {
            String tempTitle = url.contains("v=")
                ? "视频 " + url.substring(url.indexOf("v=") + 2, Math.min(url.indexOf("v=") + 13, url.length()))
                : url.substring(Math.max(0, url.lastIndexOf("/") + 1));
            taskList.add(new DownloadTask(tempTitle, qualityBox.getValue()));
        }
        downloadNext();
    }

    private void downloadNext() {
        if (currentIndex >= downloadQueue.size()) {
            Platform.runLater(() -> {
                progressBar.setProgress(1.0);
                statusLabel.setText("✅ 全部下载完成！共 " + downloadQueue.size() + " 个视频");
                downloadBtn.setDisable(false);
                fetchBtn.setDisable(false);
                completionLabel.setText("✅ 下载完成！共 " + downloadQueue.size() + " 个视频已保存");
                completionBar.setVisible(true);
                completionBar.setManaged(true);
                sendSystemNotification("下载完成 🎬",
                    "共 " + downloadQueue.size() + " 个视频已保存到：" + outputPathField.getText());
            });
            return;
        }

        String url = downloadQueue.get(currentIndex);
        String outputPath = outputPathField.getText().trim();
        String formatArg = getFormatArg();
        int total = downloadQueue.size();
        int current = currentIndex + 1;

        Platform.runLater(() -> statusLabel.setText("下载中 (" + current + "/" + total + ")..."));

        // Fix 2: Task<String> 返回从 yt-dlp 捕获的真实标题
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                List<String> cmd = new ArrayList<>();
                cmd.add(getYtDlpPath());
                cmd.addAll(getProxyArgs());
                // Fix 2: 用带前缀的 --print 标记标题行，避免与普通输出混淆
                cmd.add("--print"); cmd.add("TITLE:%(title)s");

                String fmt = formatBox.getValue();
                if (fmt.contains("MP3")) {
                    cmd.add("-x"); cmd.add("--audio-format"); cmd.add("mp3");
                } else if (fmt.contains("仅视频")) {
                    cmd.add("-f"); cmd.add("bestvideo[ext=mp4]");
                } else {
                    cmd.add("-f"); cmd.add(formatArg);
                    cmd.add("--merge-output-format"); cmd.add("mp4");
                }
                cmd.add("-o"); cmd.add(outputPath + "/%(title)s.%(ext)s");
                cmd.add(url);

                appendLog("\n▶ [" + current + "/" + total + "] " + url + "\n");

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                Pattern pat = Pattern.compile("\\[download\\]\\s+(\\d+\\.?\\d*)%");
                String capturedTitle = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    // Fix 2: 识别带前缀的标题行
                    if (line.startsWith("TITLE:") && capturedTitle == null) {
                        capturedTitle = line.substring(6);
                        continue;
                    }
                    appendLog(line + "\n");
                    Matcher m = pat.matcher(line);
                    if (m.find()) {
                        double fp = Double.parseDouble(m.group(1)) / 100.0;
                        double tp = (currentIndex + fp) / total;
                        String pStr = String.format("%.0f%%", fp * 100);
                        Platform.runLater(() -> {
                            progressBar.setProgress(tp);
                            statusLabel.setText(String.format("下载中 (%d/%d) %.1f%%", current, total, fp * 100));
                            if (currentIndex < taskList.size()) taskList.get(currentIndex).setProgress(pStr);
                        });
                    }
                }
                // Fix 1: 检查退出码，非 0 则抛出异常触发 setOnFailed
                int exitCode = process.waitFor();
                if (exitCode != 0) throw new IOException("yt-dlp 退出码：" + exitCode);
                return capturedTitle;
            }
        };

        task.setOnSucceeded(e -> {
            String title = task.getValue();
            appendLog("✅ 第 " + current + " 个完成\n");
            int doneIndex = currentIndex;
            Platform.runLater(() -> {
                if (doneIndex < taskList.size()) {
                    taskList.get(doneIndex).setProgress("100%");
                    taskList.get(doneIndex).setStatus("✅ 完成");
                    // Fix 2: 使用实际标题而非 videoTitleLabel（只有第一个视频的标题）
                    if (title != null) taskList.get(doneIndex).titleProperty().set(title);
                }
            });
            String finalTitle = title != null ? title : videoTitleLabel.getText();
            saveHistory(url, outputPath, qualityBox.getValue(), finalTitle);
            currentIndex++;
            downloadNext();
        });

        task.setOnFailed(e -> {
            // Fix 1: 下载失败时正确标记状态
            String errMsg = task.getException() != null ? task.getException().getMessage() : "未知错误";
            appendLog("❌ 第 " + current + " 个失败：" + errMsg + "\n");
            int doneIndex = currentIndex;
            Platform.runLater(() -> {
                if (doneIndex < taskList.size()) taskList.get(doneIndex).setStatus("❌ 失败");
            });
            currentIndex++;
            downloadNext();
        });

        executor.submit(task); // Fix 6
    }

    // ── 打开文件夹 ────────────────────────────────────────────────────
    @FXML private void onOpenFolder() { openOutputFolder(); }

    private void openOutputFolder() {
        try { Desktop.getDesktop().open(new File(outputPathField.getText().trim())); }
        catch (Exception e) { appendLog("无法打开文件夹：" + e.getMessage() + "\n"); }
    }

    // ── 封面加载 ──────────────────────────────────────────────────────
    private void loadThumbnailViaYtDlp(String url) {
        Platform.runLater(() -> {
            thumbPlaceholder.setVisible(true);
            thumbPlaceholder.setManaged(true);
            thumbnailView.setVisible(false);
            thumbSpinner.setProgress(-1);
        });

        // Fix 3: Task<Image> —— 在后台线程读取图片数据并删除临时文件，不留残余
        Task<Image> task = new Task<>() {
            @Override
            protected Image call() throws Exception {
                File tmpDir = new File(System.getProperty("java.io.tmpdir"));
                String basePath = tmpDir.getAbsolutePath() + "/yt_thumb_" + System.currentTimeMillis();
                List<String> cmd = new ArrayList<>();
                cmd.add(getYtDlpPath());
                cmd.addAll(getProxyArgs());
                cmd.add("--write-thumbnail");
                cmd.add("--skip-download");
                cmd.add("--convert-thumbnails"); cmd.add("jpg");
                cmd.add("--no-playlist");
                cmd.add("-o"); cmd.add(basePath);
                cmd.add(url);
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                new BufferedReader(new InputStreamReader(process.getInputStream())).lines().forEach(l -> {});
                process.waitFor();

                File jpg = new File(basePath + ".jpg");
                if (!jpg.exists()) return null;
                // Fix 3: 将图片数据读入内存，立即删除临时文件
                try (InputStream is = new FileInputStream(jpg)) {
                    return new Image(is, 160, 90, true, true);
                } finally {
                    jpg.delete();
                }
            }
        };

        task.setOnSucceeded(e -> {
            Image img = task.getValue();
            Platform.runLater(() -> {
                if (img != null) {
                    thumbnailView.setImage(img);
                    thumbnailView.setVisible(true);
                    thumbPlaceholder.setVisible(false);
                    thumbPlaceholder.setManaged(false);
                } else {
                    thumbSpinner.setVisible(false);
                }
            });
        });
        task.setOnFailed(e -> Platform.runLater(() -> thumbSpinner.setVisible(false)));
        executor.submit(task); // Fix 6
    }

    // ── 历史记录 ──────────────────────────────────────────────────────
    // Fix 2: 增加 title 参数，不再依赖 videoTitleLabel
    private void saveHistory(String url, String outputPath, String quality, String title) {
        try {
            String existing = prefs.get(PREF_HISTORY, "");
            String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date());
            String newRecord = time + "|" + title + "|" + quality + "|" + outputPath + "|" + url + "\n";
            String[] records = existing.split("\n");
            StringBuilder sb = new StringBuilder(newRecord);
            int count = 0;
            for (String r : records) {
                if (!r.trim().isEmpty() && count < 49) { sb.append(r).append("\n"); count++; }
            }
            prefs.put(PREF_HISTORY, sb.toString());
        } catch (Exception e) { appendLog("历史记录保存失败\n"); }
    }

    @FXML
    private void onOpenHistory() {
        String history = prefs.get(PREF_HISTORY, "");
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("下载历史");
        dialog.setHeaderText(null);
        VBox content = new VBox(8);
        content.setPadding(new javafx.geometry.Insets(16));
        content.setPrefWidth(600);

        if (history.trim().isEmpty()) {
            Label empty = new Label("暂无下载记录");
            empty.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 14px;");
            content.getChildren().add(empty);
        } else {
            for (String record : history.split("\n")) {
                if (record.trim().isEmpty()) continue;
                String[] parts = record.split("\\|");
                if (parts.length < 4) continue;
                VBox item = new VBox(4);
                item.setStyle("-fx-background-color: #f8fafc; -fx-padding: 10; -fx-background-radius: 8; -fx-border-color: #e2e8f0; -fx-border-radius: 8;");
                Label titleLbl = new Label(parts.length > 1 ? parts[1] : "未知标题");
                titleLbl.setStyle("-fx-text-fill: #1e293b; -fx-font-size: 13px; -fx-font-weight: bold;");
                titleLbl.setWrapText(true);
                Label metaLbl = new Label("🕐 " + parts[0] + "  |  🎞 " + (parts.length > 2 ? parts[2] : "") + "  |  📁 " + (parts.length > 3 ? parts[3] : ""));
                metaLbl.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11px;");
                metaLbl.setWrapText(true);
                item.getChildren().addAll(titleLbl, metaLbl);
                content.getChildren().add(item);
            }
        }

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(400);
        dialog.getDialogPane().setContent(scrollPane);
        dialog.getDialogPane().setPrefWidth(620);
        ButtonType clearBtn = new ButtonType("🗑 清空历史", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(clearBtn, ButtonType.CLOSE);
        dialog.showAndWait().ifPresent(result -> {
            if (result == clearBtn) { prefs.remove(PREF_HISTORY); statusLabel.setText("✅ 历史记录已清空"); }
        });
    }

    // ── 设置面板 ──────────────────────────────────────────────────────
    @FXML
    private void onOpenSettings() {
        String currentHost = prefs.get(PREF_PROXY_HOST, "");
        String currentPort = prefs.get(PREF_PROXY_PORT, "");
        String currentTheme = appPrefs != null ? appPrefs.get("theme", "light") : "light";

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("设置  —  v" + MainApp.VERSION);
        dialog.setHeaderText(null);

        TextField hostField = new TextField(currentHost);
        TextField portField = new TextField(currentPort);
        hostField.setPromptText("127.0.0.1（留空则使用系统代理）");
        portField.setPromptText("9674");

        ToggleGroup themeGroup = new ToggleGroup();
        RadioButton lightBtn = new RadioButton("☀️  浅色");
        RadioButton darkBtn  = new RadioButton("🌙  深色");
        lightBtn.setToggleGroup(themeGroup);
        darkBtn.setToggleGroup(themeGroup);
        if (currentTheme.equals("light")) lightBtn.setSelected(true);
        else darkBtn.setSelected(true);
        HBox themeBox = new HBox(20, lightBtn, darkBtn);

        VBox content = new VBox(10);
        content.setPadding(new javafx.geometry.Insets(20));
        content.setPrefWidth(380);
        content.getChildren().addAll(
            new Label("🌐  代理地址："), hostField,
            new Label("🔌  代理端口："), portField,
            new Separator(),
            new Label("🎨  界面主题："), themeBox,
            new Separator(),
            new Label("版本：v" + MainApp.VERSION)
        );

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                prefs.put(PREF_PROXY_HOST, hostField.getText().trim());
                prefs.put(PREF_PROXY_PORT, portField.getText().trim());

                if (appPrefs != null && scene != null) {
                    String selectedTheme = lightBtn.isSelected() ? "light" : "dark";
                    appPrefs.put("theme", selectedTheme);
                    MainApp.applyTheme(scene, selectedTheme);
                }

                statusLabel.setText("✅ 设置已保存");
            }
        });
    }

    // ── 系统通知 ──────────────────────────────────────────────────────
    // Fix 8: 正确转义 AppleScript 字符串，防止特殊字符导致脚本出错
    private void sendSystemNotification(String title, String message) {
        try {
            String safeTitle   = title.replace("\\", "\\\\").replace("\"", "\\\"");
            String safeMessage = message.replace("\\", "\\\\").replace("\"", "\\\"");
            String script = String.format(
                "display notification \"%s\" with title \"%s\" sound name \"Glass\"",
                safeMessage, safeTitle);
            new ProcessBuilder("osascript", "-e", script).start();
        } catch (Exception e) { appendLog("通知发送失败\n"); }
    }

    // ── 工具方法 ──────────────────────────────────────────────────────
    private void appendLog(String text) { Platform.runLater(() -> logArea.appendText(text)); }

    private List<String> getProxyArgs() {
        String host = prefs.get(PREF_PROXY_HOST, "");
        String port = prefs.get(PREF_PROXY_PORT, "");
        List<String> args = new ArrayList<>();
        if (!host.isEmpty() && !port.isEmpty()) {
            args.add("--proxy"); args.add("http://" + host + ":" + port);
        }
        return args;
    }

    private String getFormatArg() {
        return switch (qualityBox.getValue()) {
            case "1080p" -> "bestvideo[height<=1080]+bestaudio/best[height<=1080]";
            case "720p"  -> "bestvideo[height<=720]+bestaudio/best[height<=720]";
            case "480p"  -> "bestvideo[height<=480]+bestaudio/best[height<=480]";
            case "360p"  -> "bestvideo[height<=360]+bestaudio/best[height<=360]";
            default      -> "bestvideo+bestaudio/best";
        };
    }

    // Fix 5: 缓存探测结果，只在首次调用时启动子进程
    private String getYtDlpPath() {
        if (cachedYtDlpPath != null) return cachedYtDlpPath;
        String[] candidates = {"/opt/miniconda3/bin/yt-dlp", "/opt/homebrew/bin/yt-dlp", "/usr/local/bin/yt-dlp", "yt-dlp"};
        for (String path : candidates) {
            try {
                Process p = new ProcessBuilder(path, "--version").start();
                p.waitFor();
                if (p.exitValue() == 0) { cachedYtDlpPath = path; return path; }
            } catch (Exception ignored) {}
        }
        cachedYtDlpPath = "yt-dlp";
        return cachedYtDlpPath;
    }

    private void showAlert(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("提示"); alert.setHeaderText(null);
            alert.setContentText(message); alert.showAndWait();
        });
    }
}
