# SnapFloat

一个最小化 Android 截图工具。

功能只有一个：

- 启动悬浮窗。
- 点击悬浮窗按钮。
- 把当前屏幕保存到 `Pictures/SnapFloat`。

## 结构

- `app/src/main/java/com/codex/snapfloat/MainActivity.kt`
- `app/src/main/java/com/codex/snapfloat/overlay/OverlayService.kt`

## 使用

1. 用 Android Studio 打开 `SnapFloat` 目录。
2. 让 IDE 自动同步 Gradle 依赖。
3. 安装到 Android 10 及以上设备。
4. 先在主页授予悬浮窗权限。
5. 再授予截图权限。
6. 点击“启动悬浮按钮”。
7. 在任意界面点击悬浮按钮“截屏”。

## 说明

- 当前环境没有可用的 Gradle 命令，所以这个项目未在此处实际编译。
- 截图权限当前保存在进程内；应用进程被系统杀掉后，需要重新授权。
