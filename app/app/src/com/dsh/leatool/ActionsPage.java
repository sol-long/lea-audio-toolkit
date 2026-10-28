package com.dsh.leatool;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** 功能 2：操作面板（全部是写操作，必须二次确认）。 */
final class ActionsPage {

    private static final String P_DUAL = "persist.bluetooth.enable_dual_mode_audio";
    private static final String P_WL = "persist.sys.oplus.bt.le_audio_white_list_enable";
    private static final String P_AUTO_MAC = "persist.sys.lea.auto_mac";
    private static final String P_TARGET_MACS = "persist.sys.lea.target_macs";
    private static final String P_BOND = "persist.sys.lea.bond_mac";
    private static final String P_CONNECT = "persist.sys.lea.connect_mac";

    private final MainActivity a;
    private final ScrollView root;
    private final LinearLayout content;

    private EditText inAutoMac;
    private EditText inTargetMacs;
    private EditText inBondMac;

    ActionsPage(MainActivity a) {
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

    // ------------------------------------------------------------------ 构建

    private void build() {
        TextView tip = Ui.sectionTitle(a, "全部写操作都需要 root，并且会弹出二次确认");
        content.addView(tip);

        // ---- 双模
        LinearLayout c1 = Ui.card(a, content, "双模音频开关");
        c1.addView(Ui.caption(a, P_DUAL));
        Button onDual = Ui.button(a, "开启双模 (true)", true);
        Button offDual = Ui.button(a, "关闭双模 (false)", false);
        onDual.setOnClickListener(v -> a.confirmWrite("开启双模音频",
                "setprop " + P_DUAL + " true",
                "写入 persist.bluetooth.enable_dual_mode_audio = true\n"
                        + "允许 LE Audio 与经典蓝牙同时在线。"));
        offDual.setOnClickListener(v -> a.confirmWrite("关闭双模音频",
                "setprop " + P_DUAL + " false",
                "写入 persist.bluetooth.enable_dual_mode_audio = false"));
        Ui.buttonRow(a, c1, onDual, offDual);

        // ---- 白名单
        LinearLayout c2 = Ui.card(a, content, "LE Audio 白名单");
        c2.addView(Ui.caption(a, P_WL));
        c2.addView(Ui.note(a, "false = 放宽（不启用厂商白名单，非白名单 LE Audio 设备也能连）\n"
                        + "true = 收回（只允许白名单内的设备）"),
                Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(4), 0, 0));
        Button wlOpen = Ui.button(a, "放宽白名单 (false)", true);
        Button wlClose = Ui.button(a, "恢复白名单 (true)", false);
        wlOpen.setOnClickListener(v -> a.confirmWrite("放宽白名单",
                "setprop " + P_WL + " false",
                "写入 persist.sys.oplus.bt.le_audio_white_list_enable = false\n"
                        + "含义：不启用白名单限制。"));
        wlClose.setOnClickListener(v -> a.confirmWrite("恢复白名单",
                "setprop " + P_WL + " true",
                "写入 persist.sys.oplus.bt.le_audio_white_list_enable = true\n"
                        + "含义：启用白名单限制。"));
        Ui.buttonRow(a, c2, wlOpen, wlClose);

        // ---- auto_mac
        LinearLayout c3 = Ui.card(a, content, "自动配对 MAC");
        c3.addView(Ui.caption(a, P_AUTO_MAC));
        inAutoMac = Ui.input(a, "AA:BB:CC:DD:EE:FF");
        c3.addView(inAutoMac, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(8), 0, 0));
        Button setAuto = Ui.button(a, "写入 auto_mac", true);
        setAuto.setOnClickListener(v -> {
            String mac = readMac(inAutoMac, "auto_mac");
            if (mac == null) {
                return;
            }
            a.confirmWrite("写入 auto_mac",
                    "setprop " + P_AUTO_MAC + " " + Shell.quote(mac),
                    "persist.sys.lea.auto_mac = " + mac);
        });
        Ui.buttonRow(a, c3, setAuto);

        // ---- target_macs
        LinearLayout c4 = Ui.card(a, content, "目标 MAC 列表");
        c4.addView(Ui.caption(a, P_TARGET_MACS));
        inTargetMacs = Ui.input(a, "AA:BB:CC:DD:EE:FF,11:22:33:44:55:66");
        c4.addView(inTargetMacs, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(8), 0, 0));
        Button setTargets = Ui.button(a, "写入 target_macs", true);
        setTargets.setOnClickListener(v -> {
            String raw = inTargetMacs.getText() == null ? ""
                    : inTargetMacs.getText().toString().trim();
            if (raw.isEmpty()) {
                a.toast("请先填写 MAC（多个用逗号分隔）");
                return;
            }
            String norm = Parsers.normalizeMacList(raw);
            if (norm == null) {
                a.toast("MAC 格式不对，应为 AA:BB:CC:DD:EE:FF（可逗号分隔多个）");
                return;
            }
            a.confirmWrite("写入 target_macs",
                    "setprop " + P_TARGET_MACS + " " + Shell.quote(norm),
                    "persist.sys.lea.target_macs = " + norm);
        });
        Ui.buttonRow(a, c4, setTargets);

        // ---- 触发配对 / 连接
        LinearLayout c5 = Ui.card(a, content, "触发 LE 配对 / 连接");
        inBondMac = Ui.input(a, "目标耳机 MAC，如 AA:BB:CC:DD:EE:FF");
        c5.addView(inBondMac, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(4), 0, 0));
        c5.addView(Ui.note(a, "只是写属性，由 LSPosed 模块（com.dsh.lea）读取后动作；"
                        + "本 App 不发送任何蓝牙厂商命令。"),
                Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(6), 0, 0));
        Button bond = Ui.button(a, "触发 LE 配对", true);
        Button connect = Ui.button(a, "触发 LE 连接", false);
        bond.setOnClickListener(v -> {
            String mac = readMac(inBondMac, "bond_mac");
            if (mac == null) {
                return;
            }
            a.confirmWrite("触发 LE 配对",
                    "setprop " + P_BOND + " " + Shell.quote(mac),
                    "persist.sys.lea.bond_mac = " + mac);
        });
        connect.setOnClickListener(v -> {
            String mac = readMac(inBondMac, "connect_mac");
            if (mac == null) {
                return;
            }
            a.confirmWrite("触发 LE 连接",
                    "setprop " + P_CONNECT + " " + Shell.quote(mac),
                    "persist.sys.lea.connect_mac = " + mac);
        });
        Ui.buttonRow(a, c5, bond, connect);

        // ---- 重启蓝牙
        LinearLayout c6 = Ui.card(a, content, "蓝牙服务");
        c6.addView(Ui.note(a, "svc bluetooth disable; sleep 2; svc bluetooth enable\n"
                        + "蓝牙会断开约 3~8 秒，期间扫描/连接都会中断。"),
                Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(2), 0, 0));
        Button restart = Ui.button(a, "重启蓝牙", true);
        restart.setOnClickListener(v -> a.confirmWrite("重启蓝牙",
                "svc bluetooth disable; sleep 2; svc bluetooth enable",
                "将关闭并重新打开蓝牙。"));
        Ui.buttonRow(a, c6, restart);

        // ---- 模块
        LinearLayout c7 = Ui.card(a, content, "LSPosed 模块 com.dsh.lea");
        c7.addView(Ui.caption(a, "/data/adb/lspd/cli modules enable|disable com.dsh.lea"));
        Button enable = Ui.button(a, "启用模块", true);
        Button disable = Ui.button(a, "禁用模块", false);
        enable.setOnClickListener(v -> a.confirmWrite("启用模块",
                "/data/adb/lspd/cli modules enable " + StatusCollector.MODULE_ID,
                "启用 LSPosed 模块 com.dsh.lea。"));
        disable.setOnClickListener(v -> a.confirmWrite("禁用模块",
                "/data/adb/lspd/cli modules disable " + StatusCollector.MODULE_ID,
                "禁用 LSPosed 模块 com.dsh.lea。"));
        Ui.buttonRow(a, c7, enable, disable);

        // ---- 模块运行时开关（附加，非规格必选）
        c7.addView(Ui.note(a, "额外：模块运行时总开关 persist.sys.lea.enable（0=停用，1=启用）"),
                Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(10), 0, 0));
        Button modOn = Ui.button(a, "模块总开关 = 1", false);
        Button modOff = Ui.button(a, "模块总开关 = 0", false);
        modOn.setOnClickListener(v -> a.confirmWrite("模块总开关置 1",
                "setprop persist.sys.lea.enable 1",
                "persist.sys.lea.enable = 1（模块逻辑生效）。"));
        modOff.setOnClickListener(v -> a.confirmWrite("模块总开关置 0",
                "setprop persist.sys.lea.enable 0",
                "persist.sys.lea.enable = 0（临时停用模块逻辑，不用禁用模块本身）。"));
        Ui.buttonRow(a, c7, modOn, modOff);

        // ---- 安全声明
        LinearLayout c8 = Ui.card(a, content, "安全边界");
        c8.addView(Ui.note(a, "本 App 只做 setprop / LSPosed CLI / 标准 BLE 扫描，"
                        + "不发送 0x24 / 0x2F 等任何蓝牙厂商私有命令，不做固件写入。"),
                Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
    }

    // ------------------------------------------------------------------ 工具

    private String readMac(EditText field, String what) {
        String raw = field.getText() == null ? "" : field.getText().toString().trim();
        if (raw.isEmpty()) {
            a.toast("请先填写 MAC 地址");
            return null;
        }
        if (!Parsers.isMac(raw)) {
            a.toast("MAC 格式不对，应为 AA:BB:CC:DD:EE:FF");
            return null;
        }
        return raw.toUpperCase(Locale.US);
    }

    /** 来自地址探测页：把扫描到的地址写进 auto_mac。 */
    void setAutoMac(final String mac) {
        if (!Parsers.isMac(mac)) {
            a.toast("地址无效：" + mac);
            return;
        }
        a.confirmWrite("设为 auto_mac",
                "setprop " + P_AUTO_MAC + " " + Shell.quote(mac.toUpperCase(Locale.US)),
                "persist.sys.lea.auto_mac = " + mac.toUpperCase(Locale.US));
    }

    /** 来自地址探测页：把扫描到的地址追加进 target_macs。 */
    void appendTargetMac(final String mac) {
        if (!Parsers.isMac(mac)) {
            a.toast("地址无效：" + mac);
            return;
        }
        final String up = mac.toUpperCase(Locale.US);
        a.postIo(() -> {
            final Shell.Result r = Shell.run("getprop " + P_TARGET_MACS, 6000);
            final String cur = Parsers.propValue(r.out);
            a.onUi(() -> {
                String next;
                if (cur == null || cur.trim().isEmpty()) {
                    next = up;
                } else if (cur.toUpperCase(Locale.US).contains(up)) {
                    a.toast("target_macs 里已经有 " + up);
                    return;
                } else {
                    String norm = Parsers.normalizeMacList(cur);
                    next = (norm == null ? up : (norm + "," + up));
                }
                a.confirmWrite("加入 target_macs",
                        "setprop " + P_TARGET_MACS + " " + Shell.quote(next),
                        "当前值：" + Ui.nz(cur) + "\n新值：" + next);
            });
        });
    }

    /** 供探测页预填输入框。 */
    void fillBondMac(String mac) {
        if (inBondMac != null && mac != null) {
            inBondMac.setText(mac);
        }
    }
}
