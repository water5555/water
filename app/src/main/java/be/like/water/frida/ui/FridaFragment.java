package be.like.water.frida.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import be.like.water.R;
import be.like.water.frida.viewmodel.FridaViewModel;

/**
 * FridaFragment 只负责：
 * - 渲染 UI
 * - 绑定用户事件（按钮点击）
 * - 观察 ViewModel 的 LiveData
 */
public class FridaFragment extends Fragment {

    private FridaViewModel viewModel;
    private LogAdapter logAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_frida, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 获取 ViewModel
        viewModel = new ViewModelProvider(this).get(FridaViewModel.class);

        // 获取 UI 控件
        EditText etVersion = view.findViewById(R.id.etFridaVersion);
        Button btnStart = view.findViewById(R.id.btnStartFrida);
        Button btnStop = view.findViewById(R.id.btnStopFrida);
        Button btnClear = view.findViewById(R.id.btnClearLog);
        RecyclerView rvLog = view.findViewById(R.id.rvLog);

        // 初始化 RecyclerView
        logAdapter = new LogAdapter();
        rvLog.setLayoutManager(new LinearLayoutManager(getContext()));
        rvLog.setAdapter(logAdapter);

        // 启动按钮点击事件
        btnStart.setOnClickListener(v -> {
            String version = etVersion.getText().toString().trim();
            if (!version.isEmpty()) {
                viewModel.startFridaServer(version); // 调用 ViewModel 启动
            } else {
                viewModel.addTip("请输入正确的版本号"); // 通过 ViewModel 添加带时间戳的提示
            }
        });

        // 停止按钮点击事件
        btnStop.setOnClickListener(v -> viewModel.stopFridaServer());

        // 清空日志按钮点击事件
        btnClear.setOnClickListener(v -> viewModel.clearLogs());

        // 观察 ViewModel 日志列表 LiveData
        viewModel.getLogListLiveData().observe(getViewLifecycleOwner(), logs -> {
            logAdapter.setLogs(logs);
            rvLog.scrollToPosition(logAdapter.getItemCount() - 1);
        });
    }
}
