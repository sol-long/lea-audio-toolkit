package com.dsh.leatool;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LEA Toolbox —— 单 Activity，全部界面用代码构建。
 *
 * 安全边界：本 App 只做 setprop / LSPosed CLI / BLE 扫描 / 模块广播，
 * 绝不向耳机发送任何蓝牙厂商命令。
 */
public class MainActivity extends Activity {

    static final int TAB_STATUS = 0;
    static final int TAB_ACTIONS = 1;
    static final int TAB_SCAN = 2;
    static final int TAB_DIAG = 3;
    static final int TAB_WIZARD = 4;

    static final int REQ_BLE = 101;
    static final long AUTO_REFRESH_MS = 8000L;

    private Handler ui;
    private ExecutorService io;

    private LinearLayout header;
    private TextView pill;
    private FrameLayout content;

    private final TextView[] tabText = new TextView[5];
    private final View[] tabBar = new View[5];

    StatusPage statusPage;
    ActionsPage actionsPage;
    ScanPage scanPage;
    DiagPage diagPage;
    WizardPage wizardPage;

    /** 模块 com.dsh.lea 的 RESULT 广播（onCreate 注册，onDestroy 注销）。 */
    private BroadcastReceiver moduleReceiver;

    private volatile Snapshot lastSnapshot;
    private volatile boolean refreshing;
    private boolean autoRefresh = true;
    private int tab = TAB_STATUS;
    private boolean destroyed;

    private final Runnable autoTick = new Runnable() {
        @Override
        public void run() {
            if (!destroyed && autoRefresh && tab == TAB_STATUS && !refreshing) {
                refreshStatus();
            }
        }
    };

    // -------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Handler(Looper.getMainLooper());
        io = Executors.newSingleThreadExecutor();

        buildUi();
        ModuleBridge.init(this);
        registerModuleReceiver();

        // 先探测 root（后台），再拉一次状态
        io.execute(new Runnable() {
            @Override
            public void run() {
                final boolean root = Shell.rootAvailable();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        updatePill(root);
                    }
                });
            }
        });
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (autoRefresh && tab == TAB_STATUS) {
            ui.removeCallbacks(autoTick);
            ui.postDelayed(autoTick, AUTO_REFRESH_MS);
        }
        if (tab == TAB_WIZARD && wizardPage != null) {
            wizardPage.onShown();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(autoTick);
        if (scanPage != null) {
            scanPage.stopScan(false);
        }
        if (wizardPage != null) {
            wizardPage.onHidden();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        if (scanPage != null) {
            scanPage.stopScan(false);
        }
        if (wizardPage != null) {
            wizardPage.onDestroy();
        }
        unregisterModuleReceiver();
        ModuleBridge.shutdown();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (tab != TAB_STATUS) {
            selectTab(TAB_STATUS);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLE && scanPage != null) {
            boolean granted = grantResults != null && grantResults.length > 0;
            for (int r : grantResults) {
                if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                }
            }
            scanPage.onPermissionResult(granted);
        }
    }

    // ------------------------------------------------------- 模块广播（RESULT）

    /**
     * 注册模块回包接收器。API 33+ 必须显式声明 RECEIVER_EXPORTED（模块是另一个应用）；
     * 29~32 走老的两参数重载（默认就是导出的）。
     */
    private void registerModuleReceiver() {
        moduleReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                ModuleBridge.onReceive(intent);
            }
        };
        try {
            IntentFilter f = new IntentFilter(ModuleBridge.ACTION_RESULT);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(moduleReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(moduleReceiver, f);
            }
        } catch (Throwable t) {
            moduleReceiver = null;
            toastLong("模块广播接收器注册失败：" + t.getClass().getSimpleName()
                    + "（向导仍可用，只是不能删除配对）");
        }
    }

    private void unregisterModuleReceiver() {
        if (moduleReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(moduleReceiver);
        } catch (Throwable ignored) {
            // 已注销 / 进程销毁，忽略
        }
        moduleReceiver = null;
    }

    // ------------------------------------------------------------------ 界面

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.C_BG);

        // ---- 顶部标题
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Ui.C_HEADER);
        header.setPadding(dp(16), dp(14), dp(16), dp(12));

        LinearLayout hrow = Ui.row(this);
        LinearLayout hcol = Ui.column(this);
        TextView title = new TextView(this);
        title.setText("LEA Toolbox");
        title.setTextSize(20f);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        hcol.addView(title);
        TextView sub = new TextView(this);
        sub.setText("LE Audio 调试面板 · setprop / 诊断 / 扫描");
        sub.setTextSize(11f);
        sub.setTextColor(0xFF9FB3CC);
        hcol.addView(sub);
        hrow.addView(hcol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        pill = new TextView(this);
        pill.setText("检测中…");
        pill.setTextSize(10.5f);
        pill.setTextColor(0xFFFFFFFF);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setPadding(dp(9), dp(5), dp(9), dp(5));
        pill.setBackground(pillBg(0x33FFFFFF));
        hrow.addView(pill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.addView(hrow);

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- Tab 栏
        LinearLayout tabs = Ui.row(this);
        tabs.setBackgroundColor(0xFFFFFFFF);
        String[] names = {"状态", "操作", "探测", "诊断", "向导"};
        for (int i = 0; i < 5; i++) {
            LinearLayout item = Ui.column(this);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(names[i]);
            t.setTextSize(13.5f);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(11), 0, dp(9));
            item.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View bar = new View(this);
            bar.setBackgroundColor(Ui.C_ACCENT);
            item.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(idx);
                }
            });
            tabText[i] = t;
            tabBar[i] = bar;
            tabs.addView(item, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View line = new View(this);
        line.setBackgroundColor(Ui.C_LINE);
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // ---- 内容区
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusPage = new StatusPage(this);
        actionsPage = new ActionsPage(this);
        scanPage = new ScanPage(this);
        diagPage = new DiagPage(this);
        wizardPage = new WizardPage(this);

        content.addView(statusPage.view(), fullParams());
        content.addView(actionsPage.view(), fullParams());
        content.addView(scanPage.view(), fullParams());
        content.addView(diagPage.view(), fullParams());
        content.addView(wizardPage.view(), fullParams());

        setContentView(root);
        selectTab(TAB_STATUS);

        // 边到边（API 30+）：把系统栏高度变成 header / 内容的内边距
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            final LinearLayout rootRef = root;
            rootRef.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    android.graphics.Insets bars =
                            insets.getInsets(WindowInsets.Type.systemBars());
                    header.setPadding(dp(16), dp(14) + bars.top, dp(16), dp(12));
                    rootRef.setPadding(0, 0, 0, bars.bottom);
                    return insets;
                }
            });
            rootRef.requestApplyInsets();
        }
    }

    private FrameLayout.LayoutParams fullParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        return p;
    }

    private GradientDrawable pillBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(20));
        return g;
    }

    void selectTab(int idx) {
        tab = idx;
        View[] pages = {statusPage.view(), actionsPage.view(), scanPage.view(),
                diagPage.view(), wizardPage.view()};
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabText[i].setTextColor(i == idx ? Ui.C_ACCENT : Ui.C_SUB);
            tabText[i].setTypeface(i == idx ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tabBar[i].setVisibility(i == idx ? View.VISIBLE : View.INVISIBLE);
        }
        ui.removeCallbacks(autoTick);
        if (idx == TAB_STATUS) {
            if (lastSnapshot == null) {
                refreshStatus();
            } else if (autoRefresh) {
                ui.postDelayed(autoTick, AUTO_REFRESH_MS);
            }
        } else if (idx == TAB_DIAG) {
            diagPage.onShown();
        } else if (idx == TAB_WIZARD) {
            wizardPage.onShown();
        }
        if (idx != TAB_WIZARD && wizardPage != null) {
            wizardPage.onHidden();
        }
        if (idx != TAB_SCAN && scanPage != null) {
            scanPage.stopScan(false);
        }
    }

    // ------------------------------------------------------------ 公共工具

    int dp(float v) {
        return Ui.dp(this, v);
    }

    Handler handler() {
        return ui;
    }

    ExecutorService io() {
        return io;
    }

    void onUi(Runnable r) {
        if (!destroyed) {
            ui.post(r);
        }
    }

    void postIo(Runnable r) {
        if (destroyed) {
            return;
        }
        try {
            io.execute(r);
        } catch (Throwable ignored) {
            // executor 已关闭（Activity 正在销毁）
        }
    }

    void toast(String s) {
        if (!destroyed && s != null) {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        }
    }

    void toastLong(String s) {
        if (!destroyed && s != null) {
            Toast.makeText(this, s, Toast.LENGTH_LONG).show();
        }
    }

    void updatePill(boolean root) {
        if (pill == null) {
            return;
        }
        if (root) {
            pill.setText("ROOT 已授权");
            pill.setBackground(pillBg(0x3327C06D));
        } else {
            pill.setText("只读模式 · 无 root");
            pill.setBackground(pillBg(0x33FFB020));
        }
    }

    /** 二次确认对话框。所有写操作都必须经过这里。 */
    void confirm(String title, String message, final Runnable onConfirm) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(true)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认执行", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        if (onConfirm != null) {
                            onConfirm.run();
                        }
                    }
                })
                .show();
    }

    void showReport(String title, String body) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body == null ? "" : body)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 需要 root 才能执行的动作：无 root / 未探测时给出明确提示，绝不崩溃。 */
    void withRoot(final String what, final Runnable action) {
        Boolean cached = Shell.rootCached();
        if (cached == null) {
            postIo(new Runnable() {
                @Override
                public void run() {
                    final boolean ok = Shell.rootAvailable();
                    onUi(new Runnable() {
                        @Override
                        public void run() {
                            updatePill(ok);
                            if (ok) {
                                action.run();
                            } else {
                                noRoot(what);
                            }
                        }
                    });
                }
            });
            return;
        }
        if (!cached) {
            noRoot(what);
            return;
        }
        action.run();
    }

    private void noRoot(String what) {
        toastLong("无 root：无法" + what + "。状态面板仍可查看（部分数据需要 root）。");
    }

    /** 有 root 时二次确认 + 执行，成功刷新状态，失败弹报告。 */
    void confirmWrite(final String title, final String command, final String explain) {
        withRoot(title, new Runnable() {
            @Override
            public void run() {
                confirm(title, explain + "\n\n$ " + command, new Runnable() {
                    @Override
                    public void run() {
                        execWrite(title, command);
                    }
                });
            }
        });
    }

    void execWrite(final String title, final String command) {
        statusPage.setBusy(true);
        postIo(new Runnable() {
            @Override
            public void run() {
                final Shell.Result r = Shell.run(command, 30000);
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        statusPage.setBusy(false);
                        if (r.ok()) {
                            toast("✓ " + title + " 已执行");
                        } else {
                            String head = r.timedOut ? "超时" : (r.error != null ? r.error
                                    : ("exit " + r.code));
                            showReport("✗ " + title + " 失败",
                                    head + "\n\n$ " + command + "\n\n"
                                            + trim(r.out.trim(), 2500));
                        }
                        refreshStatus();
                    }
                });
            }
        });
    }

    static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n…（已截断）";
    }

    // ------------------------------------------------------------ 状态刷新

    Snapshot lastSnapshot() {
        return lastSnapshot;
    }

    void setAutoRefresh(boolean on) {
        autoRefresh = on;
        ui.removeCallbacks(autoTick);
        if (on && tab == TAB_STATUS) {
            ui.postDelayed(autoTick, 600);
        }
    }

    boolean autoRefresh() {
        return autoRefresh;
    }

    private boolean pendingRefresh;

    void refreshStatus() {
        if (destroyed) {
            return;
        }
        if (refreshing) {
            // 正在采集：标记一下，结束后立刻再来一轮（写完属性后需要立即看到新值）
            pendingRefresh = true;
            return;
        }
        refreshing = true;
        statusPage.setBusy(true);
        postIo(new Runnable() {
            @Override
            public void run() {
                Snapshot s = null;
                try {
                    s = StatusCollector.collect();
                } catch (Throwable ignored) {
                }
                final Snapshot fs = s;
                onUi(new Runnable() {
                    @Override
                    public void run() {
                        refreshing = false;
                        statusPage.setBusy(false);
                        if (fs != null) {
                            lastSnapshot = fs;
                            statusPage.render(fs);
                            updatePill(fs.root);
                        } else {
                            statusPage.renderError("状态采集失败（未知异常）");
                        }
                        if (pendingRefresh) {
                            pendingRefresh = false;
                            refreshStatus();
                            return;
                        }
                        ui.removeCallbacks(autoTick);
                        if (autoRefresh && tab == TAB_STATUS) {
                            ui.postDelayed(autoTick, AUTO_REFRESH_MS);
                        }
                    }
                });
            }
        });
    }
}
