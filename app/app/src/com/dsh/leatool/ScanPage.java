package com.dsh.leatool;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 功能 3：地址探测（标准 BLE 扫描 8 秒，绝不发厂商命令）。 */
final class ScanPage {

    private static final long SCAN_MS = 8000L;
    /** 列表上限，避免"显示全部"时被几百个设备撑爆。 */
    private static final int MAX_ROWS = 150;

    private final MainActivity a;
    private final ScrollView root;
    private final LinearLayout content;

    private TextView tvState;
    private LinearLayout listBox;
    private Button btnScan;
    private Button btnStop;
    private Switch swShowAll;
    private EditText inPrefixes;

    private final Map<String, Found> found = new LinkedHashMap<String, Found>();
    private final Set<String> seenAddr = new HashSet<String>();
    private boolean scanning;
    private BluetoothLeScanner scanner;
    private boolean permDenied;
    private int seenCount;
    private boolean truncated;
    private boolean showAll;
    /** 扫描开始时解析一次，避免扫描中改输入框导致行为漂移。 */
    private String[] prefixes = Parsers.parsePrefixes(Parsers.DEFAULT_OUI_PREFIX);

    ScanPage(MainActivity a) {
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

    private final Runnable stopRunnable = new Runnable() {
        @Override
        public void run() {
            stopScan(true);
        }
    };

    // ------------------------------------------------------------------ 构建

    private void build() {
        content.addView(Ui.sectionTitle(a,
                "扫描 8 秒：名称含 OpenFeel/-LEA、或 MAC 前缀命中，都会列出来"));

        // ---- 过滤条件
        LinearLayout c0 = Ui.card(a, content, "过滤条件（任一命中即列出）");
        c0.addView(Ui.note(a, "① 名称含 OpenFeel 或 -LEA\n"
                        + "② MAC 前缀命中（耳机 OUI，默认 84:1E:1A）\n"
                        + "③ 打开「显示全部」时不过滤\n"
                        + "耳机 LE 广播经常不带名字（名字在 scan response 或压根不广播），"
                        + "所以靠 OUI 兜底，不用名字也能扫到。"),
                Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));

        c0.addView(Ui.caption(a, "MAC 前缀（逗号分隔，可留空）"),
                Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(10), 0, 0));
        inPrefixes = Ui.input(a, "84:1E:1A");
        inPrefixes.setText(Parsers.DEFAULT_OUI_PREFIX);
        c0.addView(inPrefixes, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(6), 0, 0));

        swShowAll = new Switch(a);
        swShowAll.setText("显示全部（不过滤，按地址去重）");
        swShowAll.setTextSize(13f);
        swShowAll.setTextColor(Ui.C_TXT);
        swShowAll.setChecked(false);
        swShowAll.setOnCheckedChangeListener((buttonView, isChecked) -> showAll = isChecked);
        c0.addView(swShowAll, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(8), 0, 0));
        c0.addView(Ui.caption(a, "改完前缀/开关后点「开始扫描」生效"),
                Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(4), 0, 0));

        // ---- 扫描
        LinearLayout c1 = Ui.card(a, content, "BLE 扫描");
        c1.addView(Ui.note(a, "使用系统 BluetoothLeScanner，标准扫描回调；"
                        + "不建立连接、不写特征值、不发送任何厂商私有命令。"),
                Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
        btnScan = Ui.button(a, "开始扫描（8 秒）", true);
        btnStop = Ui.button(a, "停止", false);
        btnScan.setOnClickListener(v -> startScan());
        btnStop.setOnClickListener(v -> stopScan(true));
        Ui.buttonRow(a, c1, btnScan, btnStop);

        tvState = Ui.caption(a, "未扫描");
        c1.addView(tvState, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(8), 0, 0));

        // ---- 结果
        LinearLayout c2 = Ui.card(a, content, "扫描结果");
        listBox = Ui.column(a);
        c2.addView(listBox, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, a.dp(2), 0, 0));
        listBox.addView(Ui.note(a, "暂无匹配设备"));
    }

    // ------------------------------------------------------------------ 权限

    private String[] neededPerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPerms() {
        for (String p : neededPerms()) {
            if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestPerms() {
        if (permDenied) {
            a.toastLong("蓝牙权限被拒绝，请到系统设置里手动授予「附近的设备」权限");
            return;
        }
        tvState.setText("正在申请蓝牙权限…");
        try {
            a.requestPermissions(neededPerms(), MainActivity.REQ_BLE);
        } catch (Throwable t) {
            a.toast("申请权限失败：" + t);
        }
    }

    void onPermissionResult(boolean granted) {
        if (granted) {
            permDenied = false;
            startScan();
        } else {
            permDenied = true;
            tvState.setText("权限被拒绝：无法扫描（需要 BLUETOOTH_SCAN / CONNECT）");
            a.toastLong("扫描需要蓝牙权限，已拒绝");
        }
    }

    // ------------------------------------------------------------------ 扫描

    void startScan() {
        if (scanning) {
            return;
        }
        if (!hasPerms()) {
            requestPerms();
            return;
        }
        BluetoothManager bm = null;
        try {
            bm = (BluetoothManager) a.getSystemService(Context.BLUETOOTH_SERVICE);
        } catch (Throwable ignored) {
        }
        if (bm == null) {
            tvState.setText("系统没有蓝牙服务");
            return;
        }
        BluetoothAdapter adapter = null;
        try {
            adapter = bm.getAdapter();
        } catch (Throwable ignored) {
        }
        if (adapter == null) {
            tvState.setText("此设备不支持蓝牙");
            return;
        }
        boolean enabled = false;
        try {
            enabled = adapter.isEnabled();
        } catch (Throwable ignored) {
        }
        if (!enabled) {
            tvState.setText("蓝牙未开启，请先打开蓝牙");
            a.toast("请先打开蓝牙");
            return;
        }
        BluetoothLeScanner sc = null;
        try {
            sc = adapter.getBluetoothLeScanner();
        } catch (Throwable t) {
            tvState.setText("获取扫描器失败：" + t.getClass().getSimpleName());
            return;
        }
        if (sc == null) {
            tvState.setText("扫描器不可用（蓝牙可能正在关闭）");
            return;
        }

        // 生效当前过滤条件
        if (inPrefixes != null) {
            prefixes = Parsers.parsePrefixes(inPrefixes.getText() == null ? ""
                    : inPrefixes.getText().toString());
        }
        showAll = swShowAll != null && swShowAll.isChecked();

        found.clear();
        seenAddr.clear();
        seenCount = 0;
        truncated = false;
        renderList();

        ScanSettings settings;
        try {
            settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
        } catch (Throwable t) {
            tvState.setText("构造扫描参数失败：" + t.getClass().getSimpleName());
            return;
        }

        scanner = sc;
        scanning = true;
        btnScan.setEnabled(false);
        tvState.setText("扫描中… 最长 8 秒" + (showAll ? "（显示全部）" : ""));
        try {
            sc.startScan(null, settings, callback);
        } catch (Throwable t) {
            scanning = false;
            btnScan.setEnabled(true);
            tvState.setText("启动扫描失败：" + t.getClass().getSimpleName()
                    + "（权限或蓝牙状态异常）");
            return;
        }
        a.handler().removeCallbacks(stopRunnable);
        a.handler().postDelayed(stopRunnable, SCAN_MS);
    }

    void stopScan(boolean user) {
        a.handler().removeCallbacks(stopRunnable);
        if (!scanning) {
            return;
        }
        scanning = false;
        try {
            if (scanner != null) {
                scanner.stopScan(callback);
            }
        } catch (Throwable ignored) {
        }
        btnScan.setEnabled(true);
        StringBuilder sb = new StringBuilder();
        sb.append(user ? "扫描已停止" : "扫描结束")
                .append("，列出 ").append(found.size()).append(" 个设备")
                .append("（收到 ").append(seenCount).append(" 条广播 / ")
                .append(seenAddr.size()).append(" 个不同地址）");
        if (truncated) {
            sb.append("，列表已截断到 ").append(MAX_ROWS).append(" 个");
        }
        if (found.isEmpty() && !showAll) {
            sb.append(" · 打开「显示全部」再扫一次，可以看到到底收到了什么");
        }
        tvState.setText(sb.toString());
    }

    private final ScanCallback callback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handle(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) {
                return;
            }
            for (ScanResult r : results) {
                handle(r);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            try {
                if (scanner != null) {
                    scanner.stopScan(this);
                }
            } catch (Throwable ignored) {
            }
            btnScan.setEnabled(true);
            tvState.setText("扫描失败，errorCode=" + errorCode);
            a.toast("BLE 扫描失败，errorCode=" + errorCode);
        }
    };

    private void handle(ScanResult result) {
        if (result == null || !scanning) {
            return;
        }
        BluetoothDevice dev = result.getDevice();
        if (dev == null) {
            return;
        }
        seenCount++;

        String address = null;
        try {
            address = dev.getAddress();
        } catch (Throwable ignored) {
        }
        if (address == null || address.isEmpty()) {
            return;
        }
        String addr = address.toUpperCase(Locale.US);
        seenAddr.add(addr);

        ScanRecord rec = null;
        try {
            rec = result.getScanRecord();
        } catch (Throwable ignored) {
        }

        // 名字可能在 device 缓存、也可能在 scanRecord / scan response 里
        String name = null;
        try {
            name = dev.getName();
        } catch (Throwable ignored) {
        }
        String recName = null;
        if (rec != null) {
            try {
                recName = rec.getDeviceName();
            } catch (Throwable ignored) {
            }
        }
        if (name == null || name.trim().isEmpty()) {
            name = recName;
        }
        boolean hasName = name != null && !name.trim().isEmpty();
        String displayName = hasName ? name.trim() : "(无名)";

        // 广播详情：厂商数据字节数 / 服务 UUID 数 / 原始 adv 字节数
        int mfrBytes = 0;
        int uuidCount = 0;
        int advBytes = 0;
        if (rec != null) {
            try {
                android.util.SparseArray<byte[]> mfr = rec.getManufacturerSpecificData();
                if (mfr != null) {
                    for (int i = 0; i < mfr.size(); i++) {
                        byte[] b = mfr.valueAt(i);
                        mfrBytes += (b == null ? 0 : b.length);
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                List<android.os.ParcelUuid> uuids = rec.getServiceUuids();
                uuidCount = (uuids == null ? 0 : uuids.size());
            } catch (Throwable ignored) {
            }
            try {
                byte[] raw = rec.getBytes();
                advBytes = (raw == null ? 0 : raw.length);
            } catch (Throwable ignored) {
            }
        }

        String reason = Parsers.scanMatchReason(hasName ? name.trim() : null,
                addr, prefixes, showAll);
        if (reason == null) {
            return;
        }
        if (!found.containsKey(addr) && found.size() >= MAX_ROWS) {
            truncated = true;
            return;
        }

        final Found f = new Found(displayName, addr, result.getRssi(), reason,
                hasName, recName != null && !recName.trim().isEmpty(), mfrBytes, advBytes, uuidCount);
        boolean isNew = !found.containsKey(addr);
        found.put(addr, f);
        if (isNew) {
            a.onUi(new Runnable() {
                @Override
                public void run() {
                    renderList();
                    tvState.setText("扫描中… 已列出 " + found.size() + " 个"
                            + "（收到 " + seenCount + " 条广播 / " + seenAddr.size() + " 个地址）");
                }
            });
        }
    }

    // ------------------------------------------------------------------ 渲染

    private void renderList() {
        if (listBox == null) {
            return;
        }
        listBox.removeAllViews();
        if (found.isEmpty()) {
            listBox.addView(Ui.note(a, scanning ? "正在等待广播…" : "暂无匹配设备"));
            return;
        }
        for (Found f : new ArrayList<Found>(found.values())) {
            listBox.addView(deviceRow(f), Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, a.dp(8)));
        }
    }

    private View deviceRow(final Found f) {
        LinearLayout box = Ui.column(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF7F8FA);
        bg.setCornerRadius(a.dp(10));
        bg.setStroke(a.dp(1), Ui.C_LINE);
        box.setBackground(bg);
        box.setPadding(a.dp(11), a.dp(9), a.dp(11), a.dp(9));

        TextView name = new TextView(a);
        name.setText(f.name);
        name.setTextSize(14f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(f.hasAnyName ? Ui.C_TXT : Ui.C_SUB);
        box.addView(name);

        TextView addr = new TextView(a);
        addr.setText(f.address + "   " + f.rssi + " dBm   · 命中: " + f.reason);
        addr.setTextSize(12f);
        addr.setTypeface(Typeface.MONOSPACE);
        addr.setTextColor(Ui.C_SUB);
        addr.setTextIsSelectable(true);
        box.addView(addr);

        TextView adv = new TextView(a);
        adv.setText("广播: 名字" + (f.hasAnyName ? "✓" : "✗")
                + "  厂商数据 " + f.mfrBytes + "B"
                + "  服务UUID " + f.uuidCount
                + "  adv " + f.advBytes + "B");
        adv.setTextSize(11f);
        adv.setTextColor(Ui.C_SUB);
        box.addView(adv);

        Button auto = Ui.small(a, "设为 auto_mac", true);
        Button target = Ui.small(a, "加入 target_macs", false);
        auto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.actionsPage.setAutoMac(f.address);
            }
        });
        target.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.actionsPage.appendTargetMac(f.address);
            }
        });
        Ui.buttonRow(a, box, auto, target);
        return box;
    }

    private static final class Found {
        final String name;
        final String address;
        final int rssi;
        final String reason;
        final boolean hasAnyName;
        final boolean recordHasName;
        final int mfrBytes;
        final int advBytes;
        final int uuidCount;

        Found(String name, String address, int rssi, String reason, boolean hasAnyName,
              boolean recordHasName, int mfrBytes, int advBytes, int uuidCount) {
            this.name = name;
            this.address = address;
            this.rssi = rssi;
            this.reason = reason;
            this.hasAnyName = hasAnyName;
            this.recordHasName = recordHasName;
            this.mfrBytes = mfrBytes;
            this.advBytes = advBytes;
            this.uuidCount = uuidCount;
        }
    }
}
