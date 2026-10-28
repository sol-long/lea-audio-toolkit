package com.dsh.leatool;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 功能 4：诊断（属性快照 / 剪贴板 / 清空 / 日志）。 */
final class DiagPage {

    static final String CMD_LOG =
            "F=$(ls -t /data/misc/bluetooth/logs/bluetooth_*.log 2>/dev/null | head -n 1); "
                    + "if [ -z \"$F\" ]; then "
                    + "echo '!! 未找到蓝牙日志（需要 root：/data/misc/bluetooth/logs）'; "
                    + "else echo \"# $F\"; "
                    + "grep -aE 'LeaEnabler|ASE|bond|LC3' \"$F\" | tail -n 200; fi";

    /** 属性快照保留的关键字（小写匹配）。 */
    private static final String[] KEY_FILTERS = {
            "persist.sys.lea.", "persist.bluetooth", "bluetooth", "le_audio",
            "a2dp", "dual_mode", "persist.sys.oplus.bt", "bt_audio", "lea_"
    };

    private final MainActivity a;
    private final ScrollView root;
    private final LinearLayout content;

    private TextView tvSnapshot;
    private TextView tvLog;
    private TextView tvSnapshotMeta;
    private Button btnSnapshot;
    private Button btnCopy;
    private Button btnClear;
    private Button btnLog;

    private String snapshotText = "";
    private boolean loaded;

    DiagPage(MainActivity a) {
        this.a = a;
        root = new ScrollView(a);
        root.setFillViewport(true);
        content = Ui.column(a);
        content.setPadding(0, a.dp(4), 0, a.dp(28));
        root.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        build();
    }

    View view() {
        return root;
    }

    private void build() {
        content.addView(Ui.sectionTitle(a, "所有 persist.sys.lea.* 与蓝牙相关属性的快照"));

        LinearLayout c1 = Ui.card(a, content, "属性快照");
        btnSnapshot = Ui.button(a, "刷新快照", true);
        btnCopy = Ui.button(a, "复制到剪贴板", false);
        Ui.buttonRow(a, c1, btnSnapshot, btnCopy);
        btnClear = Ui.button(a, "清空 persist.sys.lea.*", false);
        Ui.buttonRow(a, c1, btnClear);

        tvSnapshotMeta = Ui.caption(a, "尚未读取");
        c1.addView(tvSnapshotMeta, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(8), 0, 0));

        tvSnapshot = Ui.monoBlock(a);
        tvSnapshot.setHorizontallyScrolling(false);
        c1.addView(innerBox(tvSnapshot, 240), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                a.dp(240), 0, a.dp(6), 0, 0));

        LinearLayout c2 = Ui.card(a, content,
                "蓝牙日志 · LeaEnabler|ASE|bond|LC3 · 最后 200 行");
        btnLog = Ui.button(a, "读取日志", true);
        Button clearLog = Ui.button(a, "清空显示", false);
        Ui.buttonRow(a, c2, btnLog, clearLog);

        tvLog = Ui.monoBlock(a);
        tvLog.setHorizontallyScrolling(false);
        c2.addView(innerBox(tvLog, 300), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                a.dp(300), 0, a.dp(6), 0, 0));

        btnSnapshot.setOnClickListener(v -> refreshSnapshot());
        btnCopy.setOnClickListener(v -> copySnapshot());
        btnClear.setOnClickListener(v -> clearLeaProps());
        btnLog.setOnClickListener(v -> readLog());
        clearLog.setOnClickListener(v -> tvLog.setText(""));
    }

    private ScrollView innerBox(TextView tv, int heightDp) {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF7F8FA);
        bg.setCornerRadius(a.dp(10));
        bg.setStroke(a.dp(1), Ui.C_LINE);
        sv.setBackground(bg);
        sv.setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8));
        sv.addView(tv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    void onShown() {
        if (!loaded) {
            loaded = true;
            refreshSnapshot();
        }
    }

    // -------------------------------------------------------------- 属性快照

    private void refreshSnapshot() {
        btnSnapshot.setEnabled(false);
        btnSnapshot.setText("读取中…");
        tvSnapshotMeta.setText("读取中…");
        a.postIo(new Runnable() {
            @Override
            public void run() {
                final Shell.Result r = Shell.run("getprop", 15000);
                final String filtered = filterProps(r.out);
                a.onUi(new Runnable() {
                    @Override
                    public void run() {
                        btnSnapshot.setEnabled(true);
                        btnSnapshot.setText("刷新快照");
                        if (filtered == null || filtered.trim().isEmpty()) {
                            snapshotText = "";
                            tvSnapshot.setText("（没有匹配的属性，或 getprop 不可用）");
                            tvSnapshotMeta.setText("0 条");
                            return;
                        }
                        snapshotText = filtered;
                        tvSnapshot.setText(filtered);
                        int lines = filtered.split("\n").length;
                        tvSnapshotMeta.setText(lines + " 条属性 · " + filtered.length() + " 字符");
                    }
                });
            }
        });
    }

    /** 本地过滤 getprop 输出，避免依赖 grep 的选项差异。 */
    static String filterProps(String getpropOut) {
        return Parsers.filterProps(getpropOut, KEY_FILTERS);
    }

    private void copySnapshot() {
        if (snapshotText == null || snapshotText.trim().isEmpty()) {
            a.toast("快照为空，先点「刷新快照」");
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                a.toast("没有剪贴板服务");
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText("LEA Toolbox 属性快照", snapshotText));
            a.toast("已复制 " + snapshotText.length() + " 字符");
        } catch (Throwable t) {
            a.toast("复制失败：" + t.getClass().getSimpleName());
        }
    }

    private void clearLeaProps() {
        a.postIo(new Runnable() {
            @Override
            public void run() {
                final Shell.Result r = Shell.run("getprop", 15000);
                final List<String> keys = Parsers.leaPropKeys(r.out);
                a.onUi(new Runnable() {
                    @Override
                    public void run() {
                        if (keys.isEmpty()) {
                            a.toast("没有找到 persist.sys.lea.* 属性");
                            return;
                        }
                        final StringBuilder cmd = new StringBuilder();
                        for (String k : keys) {
                            cmd.append("setprop ").append(k).append(" ''; ");
                        }
                        a.withRoot("清空 persist.sys.lea.*", new Runnable() {
                            @Override
                            public void run() {
                                a.confirm("清空 persist.sys.lea.*",
                                        "将把下面 " + keys.size() + " 个属性置空：\n\n"
                                                + join(keys) + "\n\n$ " + cmd,
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                a.execWrite("清空 persist.sys.lea.*",
                                                        cmd.toString());
                                            }
                                        });
                            }
                        });
                    }
                });
            }
        });
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            sb.append("· ").append(s).append('\n');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ 日志

    private void readLog() {
        btnLog.setEnabled(false);
        btnLog.setText("读取中…");
        tvLog.setText("读取中…（需要 root）");
        a.postIo(new Runnable() {
            @Override
            public void run() {
                final Shell.Result r = Shell.run(CMD_LOG, 25000);
                a.onUi(new Runnable() {
                    @Override
                    public void run() {
                        btnLog.setEnabled(true);
                        btnLog.setText("读取日志");
                        if (r.timedOut) {
                            tvLog.setText("读取超时（日志可能很大，稍后重试）");
                            return;
                        }
                        String out = r.out.trim();
                        if (out.isEmpty()) {
                            String head = r.error != null ? r.error : ("exit " + r.code);
                            tvLog.setText("没有输出（" + head + "）。"
                                    + "通常是没有 root，或日志目录为空。");
                            return;
                        }
                        int lines = out.split("\n").length;
                        tvLog.setText(out);
                        a.toast("已读取 " + lines + " 行");
                    }
                });
            }
        });
    }
}
