package be.like.water.frida.repository;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * FridaManager 负责：
 * 1. 下载指定版本的 frida-server（如果不存在）
 * 2. 启动 frida-server
 * 3. 停止 frida-server
 * 4. 输出日志给上层 UI（通过 LogCallback）
 */
public class FridaManager {

    // 当前 frida-server 进程引用，用于停止
    private Process fridaProcess;

    // 主线程 Handler，用于在主线程更新 UI 日志
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 启动 frida-server
     * @param version 用户输入的 frida 版本号
     * @param callback 日志回调，将启动过程和输出实时返回给 UI
     */
    public void startFrida(String version, LogCallback callback) {
        // 新建线程，避免阻塞 UI
        new Thread(() -> {
            try {
                // 获取设备架构，例如 arm64、arm、x86_64、x86
                String arch = getArch();
                // 构建文件名：frida-server-版本号-架构
                String fileName = "frida-server-" + version + "-" + arch;
                // 文件保存路径 /data/local/tmp/
                File fridaFile = new File("/data/local/tmp/", fileName);

                // 如果文件不存在，则下载
                if (!fridaFile.exists()) {
                    downloadFrida(version, arch, fridaFile, callback);
                }

                // 修改文件权限为可执行
                Runtime.getRuntime().exec("chmod 755 " + fridaFile.getAbsolutePath()).waitFor();

                // 启动 frida-server 进程
                fridaProcess = Runtime.getRuntime().exec(fridaFile.getAbsolutePath());
                callback.onLog("Frida started: " + fileName);

                // 读取 frida-server 输出流，将输出回调给 UI
                BufferedReader reader = new BufferedReader(new InputStreamReader(fridaProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    String finalLine = line;
                    // 在主线程回调 UI
                    mainHandler.post(() -> callback.onLog(finalLine));
                }

            } catch (Exception e) {
                // 启动过程中发生异常，输出错误日志
                callback.onLog("启动失败: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * 停止 frida-server
     * @param callback 日志回调，通知 UI 已停止
     */
    public void stopFrida(LogCallback callback) {
        if (fridaProcess != null) {
            fridaProcess.destroy(); // 销毁进程
            callback.onLog("Frida stopped"); // 回调日志
        }
    }

    /**
     * 下载指定版本 frida-server
     * @param version frida 版本号
     * @param arch 设备架构
     * @param destFile 下载保存目标文件
     * @param callback 日志回调，实时显示下载状态
     * @throws Exception 网络或文件操作异常
     */
    private void downloadFrida(String version, String arch, File destFile, LogCallback callback) throws Exception {
        String os = "android";
        // 拼接下载 URL
        String urlStr = "https://github.com/frida/frida/releases/download/" + version
                + "/frida-server-" + version + "-" + os + "-" + arch;
        callback.onLog("Downloading: " + urlStr);

        // 打开网络连接
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.connect();

        // 读取输入流
        InputStream in = conn.getInputStream();
        FileOutputStream out = new FileOutputStream(destFile);

        byte[] buffer = new byte[4096]; // 缓冲区
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read); // 写入文件
        }

        // 关闭流
        out.close();
        in.close();

        // 下载完成日志
        callback.onLog("Download finished: " + destFile.getAbsolutePath());
    }

    /**
     * 获取设备架构
     * @return 架构字符串，例如 arm64、arm、x86_64、x86
     */
    private String getArch() {
        String arch = System.getProperty("os.arch"); // 获取系统属性
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        } else if (arch.contains("arm")) {
            return "arm";
        } else if (arch.contains("x86_64")) {
            return "x86_64";
        } else {
            return "x86";
        }
    }

    /**
     * 日志回调接口
     */
    public interface LogCallback {
        void onLog(String message);
    }
}
