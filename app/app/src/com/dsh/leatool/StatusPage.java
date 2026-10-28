package com.dsh.leatool;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/** 功能 1：只读状态面板（自动刷新）。 */
final class StatusPage {

    private final MainActivity a;
    private final ScrollView root;
    private final LinearLayout content;

    private TextView tvUpdated;
    private TextView tvRoot;
    private TextView tvDualProp;
    private TextView tvGroup;
    private TextView tvAudioOut;
    private TextView tvDualEnabled;
    private TextView tvA2dp;
    private TextView tvMusic;
    private TextView tvRoute;
    private TextView tvLogFile;
    private TextView tvBtConfig;
    private TextView tvBtAse;
    private TextView tvBattery;
    private TextView tvModule;
    private TextView tvIssues;
    private LinearLayout cardIssues;
    private Switch swAuto;
    private Button btnRefresh;

    StatusPage(MainActivity a) {
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
        // 刷新控制
        LinearLayout c0 = Ui.card(a, content, "自动刷新");
        LinearLayout row = Ui.row(a);
        c0.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        swAuto = new Switch(a);
        swAuto.setText("每 8 秒刷新");
        swAuto.setTextSize(13f);
        swAuto.setTextColor(Ui.C_TXT);
        swAuto.setChecked(a.autoRefresh());
        swAuto.setOnCheckedChangeListener((buttonView, isChecked) -> a.setAutoRefresh(isChecked));
        row.addView(swAuto, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnRefresh = Ui.small(a, "立即刷新", true);
        btnRefresh.setOnClickListener(v -> a.refreshStatus());
        row.addView(btnRefresh);

        tvUpdated = Ui.caption(a, "最后更新：—");
        c0.addView(tvUpdated, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(6), 0, 0));

        tvRoot = Ui.kv(a, c0, "执行环境");
        tvRoot.setText("—");

        Button recheck = Ui.small(a, "重新检测 ROOT", false);
        recheck.setOnClickListener(v -> {
            Shell.resetRoot();
            a.refreshStatus();
            a.toast("已清除 root 缓存，重新检测中…");
        });
        Ui.buttonRow(a, c0, recheck);

        // 1. 双模属性
        LinearLayout c1 = Ui.card(a, content, "1 · 双模开关属性");
        tvDualProp = Ui.kv(a, c1, "persist.bluetooth.enable_dual_mode_audio");
        c1.addView(Ui.note(a, "true = 双模音频已开启（LE Audio + 经典蓝牙可同时在线）"),
                Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(4), 0, 0));

        // 2. bluetooth_manager
        LinearLayout c2 = Ui.card(a, content, "2 · 蓝牙管理器 dumpsys bluetooth_manager");
        tvGroup = Ui.kv(a, c2, "currentlyActiveGroupId");
        tvAudioOut = Ui.kv(a, c2, "mActiveAudioOutDevice");
        tvDualEnabled = Ui.kv(a, c2, "isDualModeAudioEnabled");
        tvA2dp = Ui.kv(a, c2, "A2dpStateMachine · mConnectionState");

        // 3. 音频路由
        LinearLayout c3 = Ui.card(a, content, "3 · 音频路由 dumpsys audio");
        tvMusic = Ui.kv(a, c3, "AUDIO_STREAM_MUSIC · Devices");
        tvRoute = Ui.kv(a, c3, "判定");

        // 4. 蓝牙日志
        LinearLayout c4 = Ui.card(a, content, "4 · 蓝牙日志（最新一份）");
        tvLogFile = Ui.kv(a, c4, "文件");
        tvBtConfig = Ui.kv(a, c4, "active configuration name");
        tvBtAse = Ui.kv(a, c4, "ASE state");

        // 5. 电量
        LinearLayout c5 = Ui.card(a, content, "5 · 电量（通知）");
        tvBattery = Ui.kv(a, c5, "cn.ikaile.ruoshui.client · android.text");

        // 6. LSPosed 模块
        LinearLayout c6 = Ui.card(a, content, "6 · LSPosed 模块");
        tvModule = Ui.kv(a, c6, "com.dsh.lea");

        // 采集提示
        cardIssues = Ui.card(a, content, "采集提示");
        tvIssues = Ui.note(a, "—");
        tvIssues.setTextColor(Ui.C_WARN);
        cardIssues.addView(tvIssues, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(2), 0, 0));
        cardIssues.setVisibility(View.GONE);
    }

    void setBusy(boolean busy) {
        if (btnRefresh != null) {
            btnRefresh.setEnabled(!busy);
            btnRefresh.setText(busy ? "刷新中…" : "立即刷新");
        }
    }

    private void setv(TextView t, String v) {
        if (t == null) {
            return;
        }
        if (v == null || v.trim().isEmpty()) {
            t.setText("—");
            t.setTextColor(Ui.C_SUB);
        } else {
            t.setText(v);
            t.setTextColor(Ui.C_TXT);
        }
    }

    private void setFlag(TextView t, String v, String goodValue) {
        if (t == null) {
            return;
        }
        if (v == null || v.trim().isEmpty()) {
            t.setText("—");
            t.setTextColor(Ui.C_SUB);
            return;
        }
        t.setText(v);
        t.setTextColor(v.equalsIgnoreCase(goodValue) ? Ui.C_OK : Ui.C_WARN);
    }

    void renderError(String msg) {
        if (tvUpdated != null) {
            tvUpdated.setText("最后更新：失败 — " + msg);
        }
        if (cardIssues != null) {
            cardIssues.setVisibility(View.VISIBLE);
            tvIssues.setText(msg);
        }
    }

    void render(Snapshot s) {
        if (s == null) {
            renderError("空结果");
            return;
        }
        tvUpdated.setText("最后更新：" + new SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new Date(s.time)));

        if (s.root) {
            tvRoot.setText("ROOT 已授权（su 可用）");
            tvRoot.setTextColor(Ui.C_OK);
        } else {
            tvRoot.setText("未授权 · 只读模式（写操作会提示；日志/模块/通知可能取不到）");
            tvRoot.setTextColor(Ui.C_WARN);
        }

        if (s.dualModeProp == null || s.dualModeProp.trim().isEmpty()) {
            // 不显示干巴巴的 "—"，直接给可操作提示
            tvDualProp.setText("— 读取为空（需要 root，或该机型无此属性）");
            tvDualProp.setTextColor(Ui.C_WARN);
        } else {
            // 原始输出直接展示，不再做值域校验（避免 true 被“解析丢掉”）
            tvDualProp.setText(s.dualModeProp.trim());
            tvDualProp.setTextColor("true".equalsIgnoreCase(s.dualModeProp.trim())
                    ? Ui.C_OK : Ui.C_TXT);
        }
        setv(tvGroup, s.groupId);
        setv(tvAudioOut, s.activeAudioOut);
        setFlag(tvDualEnabled, s.dualModeEnabled, "true");

        if (s.a2dp.isEmpty()) {
            if (s.a2dpActiveDevice != null) {
                tvA2dp.setText("无 A2dpStateMachine 块；mActiveDevice = " + s.a2dpActiveDevice);
                tvA2dp.setTextColor(Ui.C_SUB);
            } else {
                tvA2dp.setText("—");
                tvA2dp.setTextColor(Ui.C_SUB);
            }
        } else {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : s.a2dp.entrySet()) {
                sb.append(e.getKey()).append("   ").append(e.getValue()).append('\n');
            }
            tvA2dp.setText(sb.toString().trim());
            boolean anyConnected = false;
            for (String v : s.a2dp.values()) {
                if ("CONNECTED".equalsIgnoreCase(v) || "CONNECTED".equals(v)) {
                    anyConnected = true;
                }
            }
            tvA2dp.setTextColor(anyConnected ? Ui.C_OK : Ui.C_TXT);
            tvA2dp.setTypeface(Typeface.MONOSPACE);
        }

        setv(tvMusic, s.musicDevices);
        setv(tvRoute, s.routeKind);
        if (s.routeKind != null) {
            tvRoute.setTextColor(s.routeKind.contains("ble_headset") ? Ui.C_OK
                    : (s.routeKind.startsWith("bt_a2dp") ? Ui.C_TXT : Ui.C_SUB));
        }

        setv(tvLogFile, s.btLogFile);
        setv(tvBtConfig, s.btConfigName);
        setv(tvBtAse, s.btAseState);

        if (s.batteryText == null || s.batteryText.trim().isEmpty()) {
            tvBattery.setText("— 未找到（打开 Roselink 让它推一次设备状态通知后刷新）");
            tvBattery.setTextColor(Ui.C_WARN);
        } else {
            tvBattery.setText(s.batteryText);
            tvBattery.setTextColor(Ui.C_OK);
        }

        setv(tvModule, s.moduleState);
        if (s.moduleState != null) {
            tvModule.setTextColor(s.moduleState.startsWith("enabled") ? Ui.C_OK
                    : (s.moduleState.startsWith("disabled") ? Ui.C_WARN : Ui.C_SUB));
        }

        // 采集提示
        StringBuilder iss = new StringBuilder();
        synchronized (s.issues) {
            for (String i : s.issues) {
                iss.append("· ").append(i).append('\n');
            }
        }
        if (iss.length() == 0) {
            cardIssues.setVisibility(View.GONE);
        } else {
            cardIssues.setVisibility(View.VISIBLE);
            tvIssues.setText(iss.toString().trim());
        }

        if (s.isEmpty() && iss.length() == 0) {
            cardIssues.setVisibility(View.VISIBLE);
            tvIssues.setText("没有取到任何数据。请确认已授权 root（su）。");
        }
        tvIssues.setGravity(Gravity.START);
    }
}
