package be.like.water.frida.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

import org.tukaani.xz.XZInputStream;

/**
 * FridaManager
 * 负责下载、启动和停止 frida-server
 * 下载路径在 app 私有目录 /files/frida/version/os/arch
 * 启动前会拷贝到 /data/local/tmp 并赋可执行权限
 * 下载进度显示为 Toast，其余日志通过 LogCallback
 */
public class FridaManager {

    private Process fridaProcess;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Context context;

    public FridaManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 启动 frida-server
     */
    public void startFrida(String version, LogCallback callback) {
        new Thread(() -> {
            try {
                String os = getOs();
                String arch = getArch();
                String fileName = "frida-server-" + version + "-" + os + "-" + arch;

                File tmpFile = new File("/data/local/tmp/", fileName);

                if (!tmpFile.exists()) {
                    File appDir = new File(context.getFilesDir(), "frida/" + version + "/" + os + "/" + arch);
                    if (!appDir.exists()) appDir.mkdirs();

                    File xzFile = new File(appDir, fileName + ".xz");
                    File fridaFile = new File(appDir, fileName);

                    if (!fridaFile.exists()) {
                        downloadFrida(version, os, arch, xzFile);

                        // 解压 xz 文件到可执行文件
                        try (FileInputStream fis = new FileInputStream(xzFile);
                             XZInputStream xzIn = new XZInputStream(fis);
                             FileOutputStream fos = new FileOutputStream(fridaFile)) {

                            byte[] buffer = new byte[8192];
                            int n;
                            while ((n = xzIn.read(buffer)) != -1) fos.write(buffer, 0, n);
                        }
                        callback.onLog("SUCCESS", "Uncompressed to: " + fridaFile.getAbsolutePath());
                        xzFile.delete();
                    }

                    // 拷贝到 /data/local/tmp 并赋可执行权限
                    Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "cp " + fridaFile.getAbsolutePath() + " " + tmpFile.getAbsolutePath()}).waitFor();
                    Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "chmod 755 " + tmpFile.getAbsolutePath()}).waitFor();
                    callback.onLog("SUCCESS", "Copied to /data/local/tmp: " + tmpFile.getAbsolutePath());
                } else {
                    callback.onLog("INFO", "File exists in /data/local/tmp, using existing file.");
                }

                // 启动 frida-server
                fridaProcess = Runtime.getRuntime().exec(new String[]{"su", "-c", tmpFile.getAbsolutePath()});
                callback.onLog("SUCCESS", "Frida started: " + tmpFile.getName());

                BufferedReader reader = new BufferedReader(new InputStreamReader(fridaProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    String finalLine = line;
                    mainHandler.post(() -> callback.onLog("INFO", finalLine));
                }

            } catch (Exception e) {
                callback.onLog("ERROR", "启动失败: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * 停止 frida-server
     */
    public void stopFrida(LogCallback callback) {
        if (fridaProcess != null) {
            fridaProcess.destroy();
            callback.onLog("SUCCESS", "Frida stopped");
        } else {
            callback.onLog("WARNING", "Frida 没有运行");
        }
    }

    /**
     * 下载 frida-server 压缩包，下载进度用 Toast 显示
     */
    private void downloadFrida(String version, String os, String arch, File destFile) throws Exception {
        String urlStr = "https://github.com/frida/frida/releases/download/" + version +
                "/frida-server-" + version + "-" + os + "-" + arch + ".xz";

        mainHandler.post(() -> Toast.makeText(context, "Downloading: " + urlStr, Toast.LENGTH_SHORT).show());

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.connect();

        int totalSize = conn.getContentLength();
        int downloaded = 0;
        int lastProgress = -1;

        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloaded += read;

                if (totalSize > 0) {
                    int progress = (int) ((downloaded * 100L) / totalSize);
                    if (progress != lastProgress) {
                        lastProgress = progress;
                        int finalProgress = progress;
                        mainHandler.post(() -> Toast.makeText(context, "Downloading... " + finalProgress + "%", Toast.LENGTH_SHORT).show());
                    }
                }
            }
        }

        mainHandler.post(() -> Toast.makeText(context, "Download finished", Toast.LENGTH_SHORT).show());
    }

    /**
     * 获取 CPU 架构
     */
    private String getArch() {
        String arch = System.getProperty("os.arch");
        if (arch.contains("aarch64") || arch.contains("arm64")) return "arm64";
        else if (arch.contains("arm")) return "arm";
        else if (arch.contains("x86_64")) return "x86_64";
        else return "x86";
    }

    /**
     * 获取操作系统
     */
    private String getOs() {
        try {
            Class.forName("android.os.Build");
            return "android";
        } catch (ClassNotFoundException e) {
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("linux")) return "linux";
            else if (osName.contains("windows")) return "windows";
            else if (osName.contains("mac")) return "darwin";
            else return "unknown";
        }
    }

    /**
     * 日志回调接口
     */
    public interface LogCallback {
        void onLog(String type, String message); // type 可为 INFO / SUCCESS / WARNING / ERROR
    }
}
