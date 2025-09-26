# Frida Manager for Android

一个 Android 上的 Frida 管理工具，支持 **下载指定版本的 frida-server、启动/停止 frida-server**，并实时显示日志和下载进度。适合逆向工程、动态分析和调试 Android 应用。

---

## 功能

- 自动下载指定版本的 `frida-server`
- 支持多架构 Android 设备（arm, arm64, x86, x86_64）
- 启动和停止 `frida-server`
- 日志实时回调并显示
- 下载过程中显示下载进度
- 支持在应用内部管理 Frida，不依赖终端手动操作

---

## 使用说明

### 1. 集成到项目

将 `FridaManager`、`FridaViewModel`、`FridaFragment` 及相关布局文件添加到你的项目中。

### 2. 配置 UI

在布局文件中放置以下组件：

- 输入框（Frida 版本号）
- 启动、停止和清空日志按钮
- RecyclerView 显示日志
- 可选 ProgressBar 显示下载进度（只在下载时显示）

示例布局见 `fragment_frida.xml`。

### 3. 初始化和使用

在 Fragment 或 Activity 中：

```java
FridaViewModel viewModel = new ViewModelProvider(this).get(FridaViewModel.class);

// 启动 Frida
viewModel.startFridaServer("17.3.2");

// 停止 Frida
viewModel.stopFridaServer();

// 清空日志
viewModel.clearLogs();

// 观察日志更新
viewModel.getLogListLiveData().observe(this, logs -> {
    logAdapter.setLogs(logs);
});
