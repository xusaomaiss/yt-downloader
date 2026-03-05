# YouTube 视频下载器 v1.0.0

基于 JavaFX + yt-dlp 开发的 macOS 桌面视频下载工具。

## 功能特性

- ✅ 支持批量下载（多链接，每行一个）
- ✅ 视频封面预览 + 标题/时长显示
- ✅ 画质选择（最佳/1080p/720p/480p/360p）
- ✅ 格式选择（MP4/MP3/仅视频）
- ✅ 下载任务列表（实时进度）
- ✅ 下载完成系统通知
- ✅ 历史记录（最多50条）
- ✅ 代理设置（支持自定义代理端口）
- ✅ 主题切换（浅色/深色）
- ✅ 自动检测剪贴板链接
- ✅ 记住上次保存路径

## 环境依赖

- macOS
- JDK 21（Microsoft OpenJDK）
- Maven
- yt-dlp（`pip install yt-dlp`）
- ffmpeg（`conda install -c conda-forge ffmpeg`）

## 运行方法

```bash
cd yt-downloader
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn clean javafx:run
```

## 项目结构

```
yt-downloader/
├── pom.xml
└── src/main/
    ├── java/com/ytdownloader/
    │   ├── MainApp.java          # 入口，窗口初始化，主题管理
    │   └── MainController.java   # 核心逻辑
    └── resources/com/ytdownloader/
        ├── main.fxml             # 界面布局
        ├── style-light.css       # 浅色主题
        └── style-dark.css        # 深色主题
```
