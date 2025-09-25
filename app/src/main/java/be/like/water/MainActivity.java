package be.like.water;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import be.like.water.frida.ui.FridaFragment;

/**
 * MainActivity 负责整个 App 的模块管理
 * - 点击 Frida 按钮加载 FridaFragment
 * - 隐藏主界面按钮
 * - 支持返回栈恢复按钮显示
 */
public class MainActivity extends AppCompatActivity {

    private Button btnFrida; // 主界面 Frida 模块按钮

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // 加载主布局

        btnFrida = findViewById(R.id.btnFrida); // 获取按钮控件

        // 点击按钮时加载 Frida 模块
        btnFrida.setOnClickListener(v -> {
            // 隐藏按钮，防止重复点击
            btnFrida.setVisibility(View.GONE);

            // 使用 FragmentTransaction 替换容器为 FridaFragment
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.container, new FridaFragment()) // 替换容器
                    .addToBackStack(null) // 加入返回栈，可通过返回键回到主界面
                    .commit();
        });

        // 监听 Fragment 返回栈变化
        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            // 如果返回栈为空，说明没有 Fragment，显示主按钮
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                btnFrida.setVisibility(View.VISIBLE);
            }
        });
    }
}
