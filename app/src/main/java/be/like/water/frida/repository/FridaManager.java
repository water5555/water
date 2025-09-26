package be.like.water.frida.repository;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.RequiresPermission;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

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
 * - 下载路径：app 私有目录 /files/frida/version/os/arch
 * - 启动前会拷贝到 /data/local/tmp 并赋予可执行权限
 * - 下载进度通过通知栏显示
 */
public class FridaManager {

    private Process fridaProcess; // 保存 frida-server 的进程对象
    private final Handler mainHandler = new Handler(Looper.getMainLooper()); // 主线程 Handler，用于回调 UI
    private final Context context;

    // 通知相关常量
    private static final String CHANNEL_ID = "frida_download_channel";
    private static final int NOTIFICATION_ID = 1001;

    // 是否正在下载标志位，避免并发下载
    private volatile boolean isDownloading = false;

    public FridaManager(Context context) {
        this.context = context.getApplicationContext();
        initNotificationChannel(); // 初始化通知渠道（Android 8.0+ 必须）
    }

    /**
     * 启动 frida-server
     * 逻辑：
     * 1. 检查 /data/local/tmp 是否已有 frida-server 文件
     * 2. 没有则去 app 私有目录找，没有就下载并解压
     * 3. 拷贝到 /data/local/tmp 并赋权限
     * 4. 通过 su 执行启动 frida-server
     */
    public void startFrida(String version, LogCallback callback) {
        new Thread(() -> {
            try {
                String os = getOs();       // 获取操作系统 (android/linux/windows)
                String arch = getArch();   // 获取 CPU 架构 (arm64/arm/x86_64/x86)
                String fileName = "frida-server-" + version + "-" + os + "-" + arch;

                File tmpFile = new File("/data/local/tmp/", fileName);

                if (!tmpFile.exists()) { // /data/local/tmp 下不存在，需处理下载逻辑
                    File appDir = new File(context.getFilesDir(), "frida/" + version + "/" + os + "/" + arch);
                    if (!appDir.exists()) appDir.mkdirs();

                    File xzFile = new File(appDir, fileName + ".xz"); // 压缩包
                    File fridaFile = new File(appDir, fileName);      // 解压后的可执行文件

                    if (!fridaFile.exists()) { // app 私有目录也没有，需要下载
                        callback.onLog("INFO", "开始下载 frida: " + fileName);
                        try {
                            downloadFrida(version, os, arch, xzFile, callback); // 下载并显示通知
                        } catch (Exception e) {
                            callback.onLog("ERROR", "下载失败: " + e.getMessage());
                            updateNotificationFailed("下载失败");
                            return;
                        }

                        // 解压 .xz 文件
                        try (FileInputStream fis = new FileInputStream(xzFile);
                             XZInputStream xzIn = new XZInputStream(fis);
                             FileOutputStream fos = new FileOutputStream(fridaFile)) {

                            byte[] buffer = new byte[8192];
                            int n;
                            while ((n = xzIn.read(buffer)) != -1) fos.write(buffer, 0, n);
                        }
                        callback.onLog("SUCCESS", "解压完成: " + fridaFile.getAbsolutePath());
                        xzFile.delete(); // 删除压缩包
                    }

                    // 拷贝到 /data/local/tmp 并赋可执行权限
                    Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "cp " + fridaFile.getAbsolutePath() + " " + tmpFile.getAbsolutePath()}).waitFor();
                    Runtime.getRuntime().exec(new String[]{"su", "-c",
                            "chmod 755 " + tmpFile.getAbsolutePath()}).waitFor();
                    callback.onLog("SUCCESS", "已拷贝到 /data/local/tmp: " + tmpFile.getAbsolutePath());
                } else {
                    callback.onLog("INFO", "File exists in /data/local/tmp, 使用已存在文件.");
                }

                // 启动 frida-server（后台启动）
                // 使用 nohup 和 & 让它在后台运行并不阻塞当前进程读取
                String startCmd = tmpFile.getAbsolutePath() + " &";
                Runtime.getRuntime().exec(new String[]{"su", "-c", startCmd});
                callback.onLog("SUCCESS", "Frida 启动命令已执行: " + tmpFile.getName());

                // 等待并用 ps 打印 frida-server 进程信息（尝试若干次，直到找到为止）
                boolean found = false;
                final int maxTries = 5;
                for (int i = 0; i < maxTries; i++) {
                    try {
                        // 使用 su -c "ps -A | grep frida-server" 来支持管道
                        Process psProc = Runtime.getRuntime().exec(new String[]{"su", "-c", "ps -A | grep frida-server"});
                        BufferedReader psReader = new BufferedReader(new InputStreamReader(psProc.getInputStream()));
                        String psLine;
                        StringBuilder out = new StringBuilder();
                        while ((psLine = psReader.readLine()) != null) {
                            out.append(psLine).append("\n");
                        }
                        psProc.waitFor();

                        String outStr = out.toString().trim();
                        if (!outStr.isEmpty()) {
                            // 打印所有匹配到的行
                            String[] lines = outStr.split("\\r?\\n");
                            for (String l : lines) {
                                String finalLine = l;
                                mainHandler.post(() -> callback.onLog("PS", finalLine));
                            }
                            found = true;
                            break;
                        }
                    } catch (Exception ignore) {
                        callback.onLog("WARN", ignore.getMessage());
                    }

                    // 未找到则睡眠 1s 再试（等待 frida-server 完全跑起来）
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }

                if (!found) {
                    callback.onLog("WARN", "未能在 ps 中找到 frida-server（尝试 " + maxTries + " 次）");
                }



            } catch (Exception e) {
                callback.onLog("ERROR",  e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }


    /**
     * 停止 frida-server
     */
    public void stopFrida(LogCallback callback) {
        new Thread(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "pkill -9 frida-server"});
                int code = p.waitFor();

                // 读取可能的 stderr 用于更详细日志（可选）
                BufferedReader errReader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                StringBuilder errSb = new StringBuilder();
                String l;
                while ((l = errReader.readLine()) != null) errSb.append(l).append('\n');
                errReader.close();

                if (code == 0) {
                    mainHandler.post(() -> callback.onLog("SUCCESS", "frida-server 已停止"));
                } else {
                    // pkill 返回非0：可能是未找到进程或命令不可用
                    final String errMsg = errSb.toString().trim();
                    mainHandler.post(() -> callback.onLog("WARNING", "pkill 返回代码 " + code + (errMsg.isEmpty() ? "" : "，stderr: " + errMsg)));
                }
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                mainHandler.post(() -> callback.onLog("ERROR", "停止 Frida 失败: " + msg));
            }
        }).start();
    }


    /**
     * 下载 frida-server 压缩包，下载进度用通知显示
     * - 避免并发下载 (isDownloading 标志)
     * - 如果文件已存在则跳过
     * - 下载中更新通知栏进度
     * - 下载完成后发送完成通知
     */
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private void downloadFrida(String version, String os, String arch, File destFile, LogCallback callback) throws Exception {
        if (isDownloading) {
            throw new IllegalStateException("已有下载任务进行中，忽略本次请求");
        }

        isDownloading = true;

        try {
            // 如果文件已存在，直接跳过下载
            if (destFile.exists() && destFile.length() > 0) {
                callback.onLog("INFO", "已存在压缩包，跳过下载: " + destFile.getAbsolutePath());
                updateNotificationFinished("已存在，无需下载");
                return;
            }

            // 构造下载地址
            String urlStr = "https://github.com/frida/frida/releases/download/" + version +
                    "/frida-server-" + version + "-" + os + "-" + arch + ".xz";

            callback.onLog("INFO", "Downloading: " + urlStr);
            updateNotificationProgress(-1, "开始下载 Frida");

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.connect();

            int totalSize = conn.getContentLength(); // 文件总大小
            int downloaded = 0;                      // 已下载字节数
            int lastProgress = -1;                   // 上次通知的进度，避免频繁刷新

            // 执行下载
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
                            updateNotificationProgress(progress, "正在下载: " + progress + "%");
                        }
                    } else {
                        updateNotificationProgress(-1, "正在下载...");
                    }
                }
            }

            // 下载完成
            updateNotificationProgress(100, "下载完成");
            mainHandler.post(() -> callback.onLog("SUCCESS", "Download finished"));
        } catch (Exception e) {
            updateNotificationFailed("下载失败");
            mainHandler.post(() -> callback.onLog("ERROR", "下载失败: " + e.getMessage()));
            throw e;
        } finally {
            isDownloading = false; // 下载结束（无论成功失败）都要重置
        }
    }

    /**
     * 获取 CPU 架构
     */
    private String getArch() {
        String arch = System.getProperty("os.arch");
        if (arch == null) arch = "";
        arch = arch.toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) return "arm64";
        else if (arch.contains("arm")) return "arm";
        else if (arch.contains("x86_64") || arch.contains("amd64")) return "x86_64";
        else return "x86";
    }

    /**
     * 获取操作系统
     */
    private String getOs() {
        try {
            Class.forName("android.os.Build"); // Android 环境
            return "android";
        } catch (ClassNotFoundException e) {
            String osName = System.getProperty("os.name");
            if (osName == null) osName = "unknown";
            osName = osName.toLowerCase();
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

    // ---------------- 通知栏相关 ----------------

    /**
     * 初始化通知渠道（仅 Android 8.0+）
     */
    private void initNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Frida 下载";
            String description = "显示 frida-server 下载进度和状态";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 更新下载进度通知
     * @param progress -1 表示未知进度（转圈），0-100 表示具体进度
     */
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private void updateNotificationProgress(int progress, String contentText) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Frida 下载")
                .setContentText(contentText)
                .setOnlyAlertOnce(true) // 避免频繁提醒声音/震动
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true); // 常驻通知

        if (progress < 0) {
            builder.setProgress(0, 0, true); // 不确定进度条
        } else {
            builder.setProgress(100, progress, false);
        }

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
    }

    /**
     * 下载完成通知
     */
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private void updateNotificationFinished(String contentText) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Frida 下载")
                .setContentText(contentText)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(false)
                .setProgress(0, 0, false);

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
    }

    /**
     * 下载失败通知
     */
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private void updateNotificationFailed(String contentText) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Frida 下载")
                .setContentText(contentText)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(false)
                .setProgress(0, 0, false);

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
    }
}
