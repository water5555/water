package be.like.water.frida.viewmodel;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import be.like.water.frida.repository.FridaManager;

/**
 * FridaViewModel
 * - 负责调用 FridaManager（下载/启动/停止 frida-server）
 * - 统一管理日志（带时间戳和分类前缀）
 */
public class FridaViewModel extends AndroidViewModel {

    private final MutableLiveData<List<String>> logListLiveData = new MutableLiveData<>(); // 日志列表
    private final List<String> logList = new ArrayList<>(); // 内部日志数据源
    private final FridaManager fridaManager; // 核心逻辑类

    private final Map<String, Consumer<String>> logHandlerMap = new HashMap<>();

    public FridaViewModel(@NonNull Application application) {
        super(application);
        this.fridaManager = new FridaManager(application);
        logHandlerMap.put("INFO", this::addInfo);
        logHandlerMap.put("SUCCESS", this::addSuccess);
        logHandlerMap.put("ERROR", this::addError);
        logHandlerMap.put("WARNING", this::addWarning);
    }


    /**
     * 暴露日志 LiveData
     */
    public LiveData<List<String>> getLogListLiveData() {
        return logListLiveData;
    }

    /**
     * 启动 frida-server
     */
    public void startFridaServer(String version) {
        fridaManager.startFrida(version, (type, message) -> {
            // 根据类型分发到对应方法，如果没有匹配类型，默认 INFO
            logHandlerMap.getOrDefault(type, this::addInfo).accept(message);
        });
    }

    /**
     * 停止 frida-server
     */
    public void stopFridaServer() {
        fridaManager.stopFrida((type, message) -> {
            logHandlerMap.getOrDefault(type, this::addInfo).accept(message);
        });
    }

    /**
     * 清空日志
     */
    public void clearLogs() {
        logList.clear();
        logListLiveData.postValue(new ArrayList<>(logList));
    }

    /**
     * 核心方法：添加日志并带时间戳
     */
    private void addLogWithTime(String message) {
        String time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date());
        logList.add("[" + time + "] " + message);
        logListLiveData.postValue(new ArrayList<>(logList));
    }



    /**
     * 核心方法：添加日志
     */
    private void addLog(String message) {
        logList.add(message);
        logListLiveData.postValue(new ArrayList<>(logList));
    }

    // ========= 日志分类方法 =========

    /** 成功日志 */
    public void addSuccess(String message) {
        addLog("[成功]: " + message);
    }

    /** 警告日志 */
    public void addWarning(String message) {
        addLog("[警告]: " + message);
    }

    /** 错误日志 */
    public void addError(String message) {
        addLog("[错误]: " + message);
    }

    /** 普通信息日志 */
    public void addInfo(String message) {
        addLog("[信息]: " + message);
    }
}
