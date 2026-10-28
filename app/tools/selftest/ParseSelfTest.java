package com.dsh.leatool;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 纯 JVM 自测：只依赖 Parsers / Shell（无 Android API），
 * 用 lea-enabler/dbg 下的真实 dump（一加 Ace 3V）验证解析结果。
 */
public class ParseSelfTest {

    private static int pass;
    private static int fail;

    /** 报告行：最后以 UTF-8 落盘，避免 Windows 控制台编码把中文弄乱。 */
    private static final List<String> REPORT = new ArrayList<String>();

    private static void p(String s) {
        REPORT.add(s);
    }

    private static void p() {
        REPORT.add("");
    }

    private static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
            p("  PASS  " + what);
        } else {
            fail++;
            p("  FAIL  " + what);
        }
    }

    private static void eq(String what, Object got, Object want) {
        boolean same = (got == null) ? (want == null) : got.equals(want);
        if (same) {
            pass++;
            p("  PASS  " + what + "  = " + got);
        } else {
            fail++;
            p("  FAIL  " + what + "\n        got  = " + got
                    + "\n        want = " + want);
        }
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(Paths.get(f.getAbsolutePath())),
                StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        String dbg = System.getProperty("dbg", "../lea-enabler/dbg");
        String fixtures = System.getProperty("fixtures", ".");
        p("LEA Toolbox — 解析自测");
        p("dump 目录: " + new File(dbg).getAbsolutePath());
        p("fixtures: " + new File(fixtures).getAbsolutePath());
        p();

        testRealDumps(new File(dbg));
        testConnectedDump(new File(fixtures));
        testA2dp();
        testAudio();
        testNotification();
        testModule();
        testProps();
        testMacAndFilter();
        testScanFilter();
        testLogTidy();
        testWizardBonded(new File(fixtures));
        testWizardPre();
        testWizardStepsAndPairs(new File(fixtures));
        testWizardLc3();
        testWizardLe();
        testWizardActive();
        testWizardFinish(new File(fixtures));
        testWizardMessages();
        testWizardRobustness();
        testShellSmoke();

        p();
        p("结果: " + pass + " passed, " + fail + " failed");

        // 以 UTF-8 落盘（报告里的中文不经过控制台编码）
        String out = System.getProperty("out", "");
        if (!out.isEmpty()) {
            try {
                StringBuilder sb = new StringBuilder();
                for (String line : REPORT) {
                    sb.append(line).append('\n');
                }
                Files.write(Paths.get(out), sb.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Throwable t) {
                System.err.println("write report failed: " + t);
            }
        }
        // 控制台只留 ASCII，供脚本判断
        System.out.println("RESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 真实 dump

    private static void testRealDumps(File dbg) throws Exception {
        p("[1] 真实 dumpsys bluetooth_manager（OnePlus Ace 3V）");
        File bt2 = new File(dbg, "bt2.txt");
        File bt4 = new File(dbg, "bt4.txt");
        if (!bt2.exists()) {
            p("  SKIP  找不到 " + bt2.getAbsolutePath());
            return;
        }
        String d2 = read(bt2);
        eq("bt2 currentlyActiveGroupId", Parsers.activeGroupId(d2), "-1");
        eq("bt2 mActiveAudioOutDevice", Parsers.activeAudioOut(d2), "null");
        eq("bt2 isDualModeAudioEnabled", Parsers.dualModeAudioEnabled(d2), "false");
        ok("bt2 能读到 mActiveDevice 回退值", Parsers.a2dpActiveDevice(d2) != null);
        ok("bt2 未连接 A2DP，状态机块为空", Parsers.a2dpStates(d2).isEmpty());

        if (!bt4.exists()) {
            p("  SKIP  找不到 " + bt4.getAbsolutePath());
            return;
        }
        String d4 = read(bt4);
        eq("bt4 active configuration name", Parsers.activeConfigName(d4),
                "Two-OneChan-SnkAse-Lc3_48_2_155octs_High_Reliability_2");
        eq("bt4 ASE 状态回退", Parsers.aseStateFromDump(d4),
                "ASE1=IDLE（来自 dumpsys，日志不可用时回退）");
        ok("bt4 groupId 可解析", Parsers.activeGroupId(d4) != null);
    }

    /** 用户提供的"真实连接态"dump：ColorOS 会把 MAC 打码成 XX:XX:...，块标题带 === 和 (Active)。 */
    private static void testConnectedDump(File root) throws Exception {
        p();
        p("[1b] 真实 A2DP 连接态 dump（dbg_bt_connected.txt，MAC 已打码）");
        File f = new File(root, "dbg_bt_connected.txt");
        if (!f.exists()) {
            p("  SKIP  找不到 " + f.getAbsolutePath());
            return;
        }
        String d = read(f);
        eq("打码后 currentlyActiveGroupId", Parsers.activeGroupId(d), "1");
        eq("打码后 mActiveAudioOutDevice", Parsers.activeAudioOut(d), "XX:XX:XX:XX:74:04");
        eq("打码后 isDualModeAudioEnabled", Parsers.dualModeAudioEnabled(d), "true");
        eq("打码后 active configuration name", Parsers.activeConfigName(d),
                "Two-OneChan-SnkAse-Lc3_48_2_155octs_High_Reliability_2");

        Map<String, String> m = Parsers.a2dpStates(d);
        eq("状态机块数量（不应走兜底）", Integer.valueOf(m.size()), Integer.valueOf(1));
        String key = m.isEmpty() ? null : m.keySet().iterator().next();
        eq("打码 MAC 的 key（带 Active 标记）", key, "XX:XX:XX:XX:74:51 (Active)");
        eq("mConnectionState 只取第一个词", key == null ? null : m.get(key), "CONNECTED");

        ok("ASE 表回退仍可解析", Parsers.aseStateFromDump(d) != null);
    }

    // ----------------------------------------------------------------- A2DP

    private static void testA2dp() {
        p();
        p("[2] A2dpStateMachine 解析");
        String dump = "Profile: A2dpService\n"
                + "  mActiveDevice: 84:1E:1A:0C:74:04\n"
                + "A2dpStateMachine for 84:1E:1A:0C:74:04:\n"
                + "  mCurrentDevice=84:1E:1A:0C:74:04\n"
                + "  mConnectionState: CONNECTED\n"
                + "A2dpStateMachine for 84:1E:1A:0C:74:51:\n"
                + "  mConnectionState=DISCONNECTED\n";
        Map<String, String> m = Parsers.a2dpStates(dump);
        eq("状态机数量", Integer.valueOf(m.size()), Integer.valueOf(2));
        eq("74:04 状态", m.get("84:1E:1A:0C:74:04"), "CONNECTED");
        eq("74:51 状态", m.get("84:1E:1A:0C:74:51"), "DISCONNECTED");
        eq("mActiveDevice", Parsers.a2dpActiveDevice(dump), "84:1E:1A:0C:74:04");

        ok("空输入不炸", Parsers.a2dpStates(null).isEmpty());
        ok("无块时为空", Parsers.a2dpStates("nothing here").isEmpty());
    }

    // ---------------------------------------------------------------- 音频路由

    private static void testAudio() {
        p();
        p("[3] dumpsys audio 路由解析");
        String audio = "AudioService:\n"
                + "  Stream volumes (device: index)\n"
                + "  - STREAM_MUSIC:\n"
                + "      Muted: false\n"
                + "      Min: 0\n"
                + "      Max: 150\n"
                + "      Current: 40 (speaker:0x40000000)\n"
                + "      Devices: 2 (speaker, ble_headset)\n"
                + "  - STREAM_RING:\n"
                + "      Devices: 1 (speaker)\n";
        String dev = Parsers.musicDevices(audio);
        eq("Devices 行", dev, "Devices: 2 (speaker, ble_headset)");
        eq("路由判定", Parsers.routeKind(dev), "ble_headset（LE Audio 通道）");

        eq("经典蓝牙路由", Parsers.routeKind("Devices: 1 (bt_a2dp)"),
                "bt_a2dp（经典蓝牙通道）");
        eq("双通道", Parsers.routeKind("Devices: 2 (speaker, ble_headset, bt_a2dp)"),
                "ble_headset + bt_a2dp（同时存在）");
        eq("外放", Parsers.routeKind("Devices: 1 (speaker)"), "speaker（本机外放）");
        eq("空值", Parsers.routeKind(null), "未知");

        // 没有 STREAM_MUSIC 段时走回退
        String noSection = "whatever\n   Devices: 1 (bt_a2dp)\n";
        eq("无段落回退", Parsers.musicDevices(noSection), "Devices: 1 (bt_a2dp)");
        ok("完全无信息返回 null", Parsers.musicDevices("nothing") == null);
    }

    // ------------------------------------------------------------------ 电量

    private static void testNotification() {
        p();
        p("[4] 通知电量解析");
        String dump = "Notification List:\n"
                + "  NotificationRecord(0x1111: pkg=com.other uid=1000\n"
                + "    extras=Bundle[{android.title=别的东西, android.text=hello world}]\n"
                + "  )\n"
                + "  NotificationRecord(0x2222: pkg=cn.ikaile.ruoshui.client uid=10234\n"
                + "    extras=Bundle[{android.title=ROSE OpenFeel MK2, "
                + "android.text=左耳: 87% 右耳: 84% 充电仓: 100%}]\n"
                + "  )\n";
        eq("目标包电量文本", Parsers.batteryText(dump, "cn.ikaile.ruoshui.client"),
                "左耳: 87% 右耳: 84% 充电仓: 100%");

        eq("String(...) 包装", Parsers.extractTextField("  android.text=String (左耳: 55%)"),
                "左耳: 55%");
        ok("android.text=null 丢弃",
                Parsers.extractTextField("  android.text=null") == null);
        eq("grep 行直接解析", Parsers.batteryTextFromGrep("    android.text=左耳: 12% 右耳: 13%"),
                "左耳: 12% 右耳: 13%");
        ok("非电量文本不误判",
                Parsers.batteryTextFromGrep("android.text=正在播放") == null);
        ok("空 dump 不炸", Parsers.batteryText(null, "x") == null);
    }

    // ------------------------------------------------------------- LSPosed 模块

    private static void testModule() {
        p();
        p("[5] LSPosed CLI 模块状态");
        eq("enabled 行", Parsers.moduleState(
                "ID  Name  State\ncom.dsh.lea  1.0.0  enabled\n", "com.dsh.lea"),
                "enabled（已启用）");
        eq("disabled 行", Parsers.moduleState(
                "com.dsh.lea  1.0.0  [disabled]", "com.dsh.lea"),
                "disabled（已禁用）");
        eq("勾选样式", Parsers.moduleState("[x] com.dsh.lea", "com.dsh.lea"),
                "enabled（已启用）");
        eq("未安装", Parsers.moduleState("com.other  enabled", "com.dsh.lea"),
                "未找到模块（未安装？）");
        eq("空输出", Parsers.moduleState("", "com.dsh.lea"), "未知（无输出）");
    }

    // ------------------------------------------------------------------ 属性

    private static void testProps() {
        p();
        p("[6] getprop 解析");
        eq("true 值", Parsers.propValue("true\n"), "true");
        ok("null 视为未设置", Parsers.propValue(" null \n") == null);
        ok("空视为未设置", Parsers.propValue("\n") == null);

        String getprop = "[persist.sys.lea.auto_mac]: [84:1E:1A:0C:74:51]\n"
                + "[persist.sys.lea.target_macs]: []\n"
                + "[persist.sys.lea.enable]: [1]\n"
                + "[ro.build.version.sdk]: [34]\n";
        List<String> keys = Parsers.leaPropKeys(getprop);
        eq("persist.sys.lea.* 数量", Integer.valueOf(keys.size()), Integer.valueOf(3));
        ok("包含 auto_mac", keys.contains("persist.sys.lea.auto_mac"));

        String filtered = Parsers.filterProps(getprop,
                new String[]{"persist.sys.lea.", "bluetooth"});
        ok("过滤掉无关属性", filtered != null && !filtered.contains("ro.build.version.sdk"));
        ok("保留 lea 属性", filtered != null && filtered.contains("persist.sys.lea.enable"));
        ok("空输入返回 null", Parsers.filterProps(null, new String[]{"a"}) == null);

        eq("取值", Parsers.stripGetpropLine("[persist.sys.lea.enable]: [1]"), "1");
    }

    // ------------------------------------------------------------- MAC / 名称

    private static void testMacAndFilter() {
        p();
        p("[7] MAC 与设备名过滤");
        ok("大写 MAC", Parsers.isMac("84:1E:1A:0C:74:04"));
        ok("小写 MAC", Parsers.isMac("84:1e:1a:0c:74:04"));
        ok("无分隔符不算", !Parsers.isMac("841E1A0C7404"));
        ok("长度不对不算", !Parsers.isMac("84:1E:1A:0C:74"));
        ok("null 不算", !Parsers.isMac(null));

        eq("列表规范化",
                Parsers.normalizeMacList("84:1e:1a:0c:74:04, 84:1e:1a:0c:74:51"),
                "84:1E:1A:0C:74:04,84:1E:1A:0C:74:51");
        ok("列表含非法项返回 null",
                Parsers.normalizeMacList("84:1e:1a:0c:74:04,xyz") == null);
        ok("空列表返回 null", Parsers.normalizeMacList("  ") == null);

        ok("OpenFeel 命中", Parsers.isLeaCandidateName("ROSE OpenFeel MK2"));
        ok("小写 openfeel 命中", Parsers.isLeaCandidateName("rose openfeel"));
        ok("-LEA 命中", Parsers.isLeaCandidateName("ROSE-Lea-01"));
        ok("普通设备不命中", !Parsers.isLeaCandidateName("Mi Smart Band 8"));
        ok("null 不命中", !Parsers.isLeaCandidateName(null));

        eq("最后一行", Parsers.lastLine("a\n\nb\n"), "b");
        ok("空文本最后一行", Parsers.lastLine("") == null);
    }

    // ------------------------------------------------------------ 扫描过滤

    private static void testScanFilter() {
        p();
        p("[8] 扫描多条件过滤（名称 / OUI 前缀 / 显示全部）");
        String[] p1 = Parsers.parsePrefixes("84:1e:1a");
        eq("前缀数量", Integer.valueOf(p1.length), Integer.valueOf(1));
        eq("前缀规范化", p1.length == 1 ? p1[0] : null, "84:1E:1A");

        String[] multi = Parsers.parsePrefixes("84:1E:1A, aa:bb, zz:zz");
        eq("多前缀 + 非法项被丢弃", Integer.valueOf(multi.length), Integer.valueOf(2));

        // 耳机真实情况：广播里没有名字，只能靠 OUI 命中
        eq("无名 + OUI 命中（左耳）",
                Parsers.scanMatchReason(null, "84:1E:1A:0C:74:04", p1, false),
                "MAC 前缀 84:1E:1A");
        eq("无名 + OUI 命中（右耳，小写地址）",
                Parsers.scanMatchReason(null, "84:1e:1a:0c:b1:7f", p1, false),
                "MAC 前缀 84:1E:1A");
        eq("名称命中优先生效",
                Parsers.scanMatchReason("ROSE OpenFeel MK2", "AA:BB:CC:DD:EE:FF", p1, false),
                "名称");
        ok("都不命中则不列出",
                Parsers.scanMatchReason(null, "AA:BB:CC:DD:EE:FF", p1, false) == null);
        ok("有名字但不匹配也不列出",
                Parsers.scanMatchReason("Mi Band 8", "AA:BB:CC:DD:EE:FF", p1, false) == null);
        eq("显示全部兜底",
                Parsers.scanMatchReason(null, "AA:BB:CC:DD:EE:FF", p1, true), "显示全部");
        eq("显示全部时名字为空也列出",
                Parsers.scanMatchReason("随便什么", "11:22:33:44:55:66", p1, true), "显示全部");
        ok("空前缀不炸",
                Parsers.scanMatchReason(null, "AA:BB:CC:DD:EE:FF",
                        Parsers.parsePrefixes(""), false) == null);
    }

    // ---------------------------------------------------------- 日志行分隔符

    private static void testLogTidy() {
        p();
        p("[9] ASE 日志行粘连补分隔符");
        eq("粘连行", Parsers.tidyAseLine("ASE id: 0x1ASE state: Idle (0x0)"),
                "ASE id: 0x1 · ASE state: Idle (0x0)");
        eq("正常行不变", Parsers.tidyAseLine("  ASE state: Streaming (0x2)  "),
                "ASE state: Streaming (0x2)");
        eq("多空白压缩", Parsers.tidyAseLine("ASE id:  0x2   ASE state: Idle (0x0)"),
                "ASE id: 0x2 ASE state: Idle (0x0)");
        ok("空行返回 null", Parsers.tidyAseLine("   ") == null);
        ok("null 安全", Parsers.tidyAseLine(null) == null);
    }

    // ------------------------------------------------------------ 向导：配对列表

    private static WizardLogic.Inputs base() {
        WizardLogic.Inputs in = new WizardLogic.Inputs();
        in.root = true;
        in.modulesOut = "ID  Name  State\ncom.dsh.lea  1.0.0  enabled\n";
        in.leaEnablerCount = 27;
        in.dualProp = "true";
        in.whiteListProp = "false";
        in.btOn = "1";
        in.roselinkOut = "package:cn.ikaile.ruoshui.client";
        return in;
    }

    private static void testWizardBonded(File root) throws Exception {
        p();
        p("[11] 向导 · Bonded devices 解析（真实 dbg_bt_connected.txt）");
        File f = new File(root, "dbg_bt_connected.txt");
        if (!f.exists()) {
            p("  SKIP  找不到 " + f.getAbsolutePath());
            return;
        }
        String d = read(f);
        List<Parsers.Bonded> all = Parsers.bondedDevices(d);
        eq("Bonded 设备数量", Integer.valueOf(all.size()), Integer.valueOf(4));

        Parsers.Bonded classic = null;
        Parsers.Bonded left = null;
        Parsers.Bonded band = null;
        for (Parsers.Bonded b : all) {
            if ("84:1E:1A:0C:74:51".equals(b.mac)) {
                classic = b;
            }
            if ("84:1E:1A:0C:74:04".equals(b.mac)) {
                left = b;
            }
            if (b.mac.endsWith("E7:C5")) {
                band = b;
            }
        }
        ok("找到 [BR/EDR] 经典身份", classic != null && classic.isClassic());
        eq("经典身份名字", classic == null ? null : classic.name, "ROSE OpenFeel MK2");
        ok("找到 [LE] -LEA 身份", left != null && left.isLe());
        eq("LE 身份名字", left == null ? null : left.name, "ROSE OpenFeel MK2-LEA");
        ok("手环是 LE 且不是经典身份", band != null && band.isLe() && !band.isClassic());
        ok("关键字 OpenFeel 命中 LE 身份", left != null && left.nameContains("OpenFeel"));
        ok("关键字不命中手环", band != null && !band.nameContains("OpenFeel"));

        java.util.Set<String> conn = Parsers.connectedMacs(d);
        ok("连接集合含打码的经典地址", conn.contains("XX:XX:XX:XX:74:51"));
        ok("连接集合含打码的 LE 地址", conn.contains("XX:XX:XX:XX:74:04"));
        Parsers.markConnected(all, conn);
        ok("经典身份标记为当前连接", classic != null && classic.connected);
        ok("LE 身份标记为当前连接", left != null && left.connected);
        ok("手环未被误标为连接", band != null && !band.connected);

        ok("打码地址可匹配（尾两段）",
                Parsers.sameDevice("84:1E:1A:0C:74:04", "XX:XX:XX:XX:74:04"));
        ok("尾两段不同不匹配",
                !Parsers.sameDevice("84:1E:1A:0C:74:04", "XX:XX:XX:XX:74:51"));
        ok("两个完整地址不同不匹配",
                !Parsers.sameDevice("84:1E:1A:0C:74:04", "84:1E:1A:0C:B1:7F"));
        ok("无 Bonded 段返回空", Parsers.bondedDevices("nothing here").isEmpty());
        ok("null 安全", Parsers.bondedDevices(null).isEmpty());
    }

    // ------------------------------------------------------------ 向导：第 0 步

    private static void testWizardPre() {
        p();
        p("[12] 向导 · 第 0 步前置检查");
        WizardLogic.Result r = WizardLogic.eval(0, base());
        eq("全部满足 → PASS", r.status, WizardLogic.Status.PASS);
        eq("检查项数量", Integer.valueOf(r.checks.size()), Integer.valueOf(7));
        ok("结论是可开始", r.headline.contains("可以开始"));

        WizardLogic.Inputs noRoot = base();
        noRoot.root = false;
        r = WizardLogic.eval(0, noRoot);
        eq("无 root → FAIL", r.status, WizardLogic.Status.FAIL);
        ok("建议去 ReSukiSU 授权", r.advice.contains("ReSukiSU"));

        WizardLogic.Inputs disabled = base();
        disabled.modulesOut = "com.dsh.lea  1.0.0  [disabled]";
        r = WizardLogic.eval(0, disabled);
        eq("模块禁用 → FAIL", r.status, WizardLogic.Status.FAIL);
        ok("第 2 项是模块", !r.checks.get(WizardLogic.C_MODULE).pass);

        WizardLogic.Inputs noInject = base();
        noInject.leaEnablerCount = 0;
        r = WizardLogic.eval(0, noInject);
        ok("日志 0 行 [LeaEnabler] → 注入项失败",
                !r.checks.get(WizardLogic.C_INJECT).pass);

        WizardLogic.Inputs noLog = base();
        noLog.leaEnablerCount = -1;
        r = WizardLogic.eval(0, noLog);
        ok("日志读不到时提示需要 root",
                r.checks.get(WizardLogic.C_INJECT).detail.contains("root"));

        WizardLogic.Inputs propsBad = base();
        propsBad.dualProp = "false";
        propsBad.whiteListProp = "true";
        propsBad.btOn = "0";
        propsBad.roselinkOut = null;
        r = WizardLogic.eval(0, propsBad);
        ok("双模项失败", !r.checks.get(WizardLogic.C_DUAL).pass);
        ok("白名单项失败", !r.checks.get(WizardLogic.C_WHITE_LIST).pass);
        ok("蓝牙项失败", !r.checks.get(WizardLogic.C_BT_ON).pass);
        ok("Roselink 项失败", !r.checks.get(WizardLogic.C_ROSELINK).pass);
        eq("失败数", Integer.valueOf(r.failCount()), Integer.valueOf(4));
    }

    // ------------------------------------------------- 向导：第 1/2 步

    private static void testWizardStepsAndPairs(File root) throws Exception {
        p();
        p("[13] 向导 · 第 1/2 步（AAC 模式 / 经典配对）");
        File f = new File(root, "dbg_bt_connected.txt");
        String real = f.exists() ? read(f) : "";
        if (!real.isEmpty()) {
            WizardLogic.Inputs in = base();
            in.btDump = real;
            WizardLogic.Result r = WizardLogic.eval(1, in);
            eq("真实连接态 → 第 1 步 PASS", r.status, WizardLogic.Status.PASS);
            eq("识别出 1 条经典身份",
                    Integer.valueOf(r.classicDevices.size()), Integer.valueOf(1));
            eq("识别出 2 条 LE 身份",
                    Integer.valueOf(r.leDevices.size()), Integer.valueOf(2));

            r = WizardLogic.eval(2, in);
            eq("真实连接态 → 第 2 步 PASS", r.status, WizardLogic.Status.PASS);
            ok("第 2 步不误报僵尸", !r.zombie);
        } else {
            p("  SKIP  找不到真实 dump");
        }

        String onlyLe = "Bonded devices:\n"
                + "    84:1E:1A:0C:74:04 [  LE  ][ 0x240404 ] ROSE OpenFeel MK2-LEA\n";
        WizardLogic.Inputs lc3 = base();
        lc3.btDump = onlyLe;
        WizardLogic.Result r = WizardLogic.eval(1, lc3);
        eq("只有 -LEA → 第 1 步 FAIL", r.status, WizardLogic.Status.FAIL);
        ok("判定「耳机在 LC3 模式」", r.headline.contains("LC3"));
        ok("建议用 Roselink 切回 AAC", r.advice.contains("Roselink"));
        ok("含复位兜底提示", r.advice.contains("10 秒"));

        r = WizardLogic.eval(2, lc3);
        eq("只有 -LEA → 第 2 步 FAIL", r.status, WizardLogic.Status.FAIL);
        ok("指引里强调「不带 -LEA」", r.advice.contains("不带 -LEA"));

        WizardLogic.Inputs timeout = base();
        timeout.btDump = onlyLe;
        timeout.pairTimedOut = true;
        r = WizardLogic.eval(2, timeout);
        ok("配对超时 → 触发僵尸分支", r.zombie);
        ok("僵尸原因含「30 秒」", r.zombieReason.contains("30"));

        WizardLogic.Inputs auth = base();
        auth.btDump = onlyLe;
        auth.btLog = "pairing_report_bond_state_change: bd_addr=XX, status: 9\n";
        r = WizardLogic.eval(2, auth);
        ok("日志 status: 9 → 僵尸分支", r.zombie);
        ok("僵尸原因含认证失败", r.zombieReason.contains("认证失败"));
    }

    // ------------------------------------------------- 向导：第 3 步

    private static WizardLogic.Check check(WizardLogic.Result r, String keyword) {
        for (WizardLogic.Check c : r.checks) {
            if (c.label.contains(keyword)) {
                return c;
            }
        }
        return new WizardLogic.Check("(missing:" + keyword + ")", false, "", null, false);
    }

    private static void testWizardLc3() {
        p();
        p("[14] 向导 · 第 3 步（人工确认 + 自动佐证）");
        String bt = "Bonded devices:\n"
                + "    84:1E:1A:0C:74:51 [BR/EDR][ 0x240404 ] ROSE OpenFeel MK2\n";
        String leaBt = bt
                + "    84:1E:1A:0C:74:04 [  LE  ][ 0x240404 ] ROSE OpenFeel MK2-LEA\n";
        String eirLog = "===EIR===\n"
                + "[btif_dm.cc:2102] btif_dm_search_devices_evt: "
                + "0000184e-0000-1000-8000-00805f9b34fb\n"
                + "[btm_inq.cc:2453] BTM_GetEirUuidList: 0x184E\n"
                + "[btm_inq.cc:2453] BTM_GetEirUuidList: 0x1846\n";

        // 1) 有佐证但没勾选 → 等待人工确认（不能自动通过）
        WizardLogic.Inputs in = base();
        in.btDump = bt;
        in.btLog = eirLog;
        WizardLogic.Result r = WizardLogic.eval(3, in);
        eq("没勾选 → 等待人工确认（FAIL）", r.status, WizardLogic.Status.FAIL);
        ok("EIR 佐证通过", check(r, "btif_dm_search_devices_evt").pass);
        ok("建议里让你勾选", r.advice.contains("勾选"));

        // 2) 勾选后即使没有任何佐证也能通过（用户要求：无信号 + 勾选 = 允许下一步）
        in = base();
        in.btDump = bt;
        in.btLog = null;
        in.lc3Confirmed = true;
        r = WizardLogic.eval(3, in);
        eq("勾选 + 无任何佐证 → PASS", r.status, WizardLogic.Status.PASS);
        ok("无佐证时给出灰色提示", r.footnote.contains("未检测到自动佐证"));
        eq("佐证不计入失败", Integer.valueOf(r.failCount()), Integer.valueOf(0));

        // 3) 三条佐证分别命中
        in = base();
        in.btDump = bt;
        in.btLog = eirLog;
        in.lc3Confirmed = true;
        r = WizardLogic.eval(3, in);
        ok("佐证 1：btif_dm EIR UUID", check(r, "btif_dm_search_devices_evt").pass);
        ok("有佐证时 footnote 是汇总", r.footnote.contains("自动佐证："));

        in = base();
        in.btDump = bt;
        in.btLog = "btm_ble_process_adv_addr: bda=84:1E:1A:0C:74:04\n";
        in.lc3Confirmed = true;
        r = WizardLogic.eval(3, in);
        ok("佐证 2：btm_ble_process_adv_addr", check(r, "btm_ble_process_adv_addr").pass);

        in = base();
        in.btDump = leaBt;
        in.btLog = null;
        in.lc3Confirmed = true;
        r = WizardLogic.eval(3, in);
        ok("佐证 3：Bonded 里已有 -LEA", check(r, "-LEA 身份").pass);

        // 4) 关键回归：GATT 服务发现里的 184e 不能当佐证（AAC 模式也会打）
        in = base();
        in.btDump = bt;
        in.lc3Confirmed = true;
        in.btLog = "bta_gattc_search_service: found service "
                + "0000184e-0000-1000-8000-00805f9b34fb\n"
                + "bta_gattc_search_service: found service "
                + "00001846-0000-1000-8000-00805f9b34fb\n";
        r = WizardLogic.eval(3, in);
        eq("只有 GATT 服务发现 → 仍 PASS（靠人工确认）", r.status, WizardLogic.Status.PASS);
        ok("GATT 的 184e 不算佐证", !check(r, "btif_dm_search_devices_evt").pass);
        ok("GATT 场景显示未检测到", r.footnote.contains("未检测到自动佐证"));

        // 5) 特性开关也不是佐证
        in = base();
        in.btDump = bt;
        in.lc3Confirmed = true;
        in.btLog = "leaudio_allow_leaudio_only_devices: 1\n"
                + "leaudio_broadcast_volume_control_for_connected_devices: true\n"
                + "LEAUDIO enabled\n";
        r = WizardLogic.eval(3, in);
        ok("特性开关不算佐证", !check(r, "btif_dm_search_devices_evt").pass);
        ok("特性开关场景显示未检测到", r.footnote.contains("未检测到自动佐证"));

        // 6) 未勾选 + 无佐证 → FAIL，并提示可以跳过
        in = base();
        in.btDump = bt;
        in.btLog = "nothing useful";
        r = WizardLogic.eval(3, in);
        eq("未勾选 + 无佐证 → FAIL", r.status, WizardLogic.Status.FAIL);
        ok("建议里说明可跳过", r.advice.contains("跳过"));

        // 7) 打码广播地址（回归上一条真机 bug）
        in = base();
        in.btDump = leaBt;
        in.btLog = "btm_ble_process_adv_addr: bda=xx:xx:xx:xx:74:04 addr_type=0\n";
        r = WizardLogic.eval(3, in);
        ok("打码地址按尾两段命中佐证", check(r, "btm_ble_process_adv_addr").pass);
        ok("佐证详情含配对 MAC",
                check(r, "btm_ble_process_adv_addr").detail.contains("84:1E:1A:0C:74:04"));

        // 8) findLeAudioUuid 严格性
        ok("btif_dm 行命中", WizardLogic.findLeAudioUuid(
                "btif_dm_search_devices_evt: 0000184e-0000-1000-8000-00805f9b34fb") != null);
        ok("GATT 行不命中", WizardLogic.findLeAudioUuid(
                "bta_gattc_search_service: found service "
                        + "0000184e-0000-1000-8000-00805f9b34fb") == null);
        ok("两者混在一起只认 btif_dm", WizardLogic.findLeAudioUuid(
                "bta_gattc_search_service: found service "
                        + "0000184e-0000-1000-8000-00805f9b34fb\n"
                        + "btif_dm_search_devices_evt: 00001846-0000-1000-8000-00805f9b34fb")
                != null);
        ok("BTM_GetEirUuidList 命中",
                WizardLogic.findLeAudioUuid("BTM_GetEirUuidList: 0x184e") != null);
        ok("特性开关不命中",
                WizardLogic.findLeAudioUuid("leaudio_allow_leaudio_only_devices") == null);
        ok("其它服务 UUID 不命中",
                WizardLogic.findLeAudioUuid("0000180f-0000-1000-8000-00805f9b34fb") == null);
        ok("空日志安全", WizardLogic.findLeAudioUuid(null) == null);
    }

    // ------------------------------------------------- 向导：第 4 步

    private static void testWizardLe() {
        p();
        p("[15] 向导 · 第 4 步（-LEA 配对）");
        String two = "Bonded devices:\n"
                + "    84:1E:1A:0C:74:04 [  LE  ][ 0x240404 ] ROSE OpenFeel MK2-LEA\n"
                + "    84:1E:1A:0C:B1:7F [  LE  ][ 0x240404 ] ROSE OpenFeel MK2-LEA\n";
        WizardLogic.Inputs in = base();
        in.btDump = two;
        WizardLogic.Result r = WizardLogic.eval(4, in);
        eq("2 条 → PASS", r.status, WizardLogic.Status.PASS);
        ok("结论是双耳", r.headline.contains("双耳"));

        String one = "Bonded devices:\n"
                + "    84:1E:1A:0C:74:04 [  LE  ][ 0x240404 ] ROSE OpenFeel MK2-LEA\n";
        in = base();
        in.btDump = one;
        r = WizardLogic.eval(4, in);
        eq("1 条 → 仍算 PASS", r.status, WizardLogic.Status.PASS);
        ok("提示可能单耳", r.headline.contains("单耳"));
        ok("建议再点另一条", r.advice.contains("另一条"));

        String three = two
                + "    84:1E:1A:0C:AA:BB [  LE  ][ 0x240404 ] ROSE OpenFeel MK2-LEA\n";
        in = base();
        in.btDump = three;
        r = WizardLogic.eval(4, in);
        ok("3 条同名 -LEA → 僵尸分支", r.zombie);
        eq("3 条仍 PASS（≥1）", r.status, WizardLogic.Status.PASS);
        ok("僵尸原因提到多条", r.zombieReason.contains("多条"));

        in = base();
        in.btDump = "Bonded devices:\n";
        r = WizardLogic.eval(4, in);
        eq("0 条 → FAIL", r.status, WizardLogic.Status.FAIL);
        ok("建议先回第 3 步", r.advice.contains("第 3 步"));
    }

    // ------------------------------------------------- 向导：第 5 步

    private static void testWizardActive() {
        p();
        p("[16] 向导 · 第 5 步（激活与验证）");
        String bt = "Profile: LeAudioService\n"
                + "  currentlyActiveGroupId: 1\n"
                + "A2dpStateMachine for 84:1E:1A:0C:74:51:\n"
                + "  mConnectionState: CONNECTED\n"
                + "Bonded devices:\n"
                + "    84:1E:1A:0C:74:51 [BR/EDR][ 0x240404 ] ROSE OpenFeel MK2\n"
                + "    84:1E:1A:0C:74:04 [  LE  ][ 0x240404 ] ROSE OpenFeel MK2-LEA\n";
        String audio = "  - STREAM_MUSIC:\n"
                + "      Devices: 2 (speaker, ble_headset)\n";
        String log = "ASE state: Streaming (0x4)\n"
                + "active configuration name: "
                + "Two-OneChan-SnkAse-Lc3_48_2_155octs_High_Reliability_2\n";

        WizardLogic.Inputs in = base();
        in.btDump = bt;
        in.audioDump = audio;
        in.btLog = log;
        WizardLogic.Result r = WizardLogic.eval(5, in);
        eq("四项全绿 → PASS", r.status, WizardLogic.Status.PASS);
        ok("结论提到 ble_headset", r.headline.contains("ble_headset"));

        in = base();
        in.btDump = bt.replace("currentlyActiveGroupId: 1",
                "currentlyActiveGroupId: -1");
        in.audioDump = audio;
        in.btLog = log;
        r = WizardLogic.eval(5, in);
        eq("groupId=-1 → FAIL", r.status, WizardLogic.Status.FAIL);
        ok("第 1 项失败", !r.checks.get(0).pass);
        ok("仍给出播放建议", r.advice.contains("第 4 步"));

        in = base();
        in.btDump = bt;
        in.audioDump = "  - STREAM_MUSIC:\n      Devices: 1 (bt_a2dp)\n";
        in.btLog = log;
        r = WizardLogic.eval(5, in);
        ok("媒体走 bt_a2dp → 第 2 项失败", !r.checks.get(1).pass);

        in = base();
        in.btDump = bt;
        in.audioDump = audio;
        in.btLog = "ASE state: Idle (0x0)\n"
                + "active configuration name: Two-OneChan-SnkAse-Lc3_48_2\n";
        r = WizardLogic.eval(5, in);
        ok("ASE 不是 Streaming → 第 3 项失败", !r.checks.get(2).pass);

        in = base();
        in.btDump = bt;
        in.audioDump = audio;
        in.btLog = "ASE state: Streaming (0x4)\n"
                + "active configuration name: two-onechan-lc3_48_2\n";
        r = WizardLogic.eval(5, in);
        ok("Lc3 大小写不敏感", r.checks.get(2).pass);

        in = base();
        in.btDump = "Profile: LeAudioService\n  currentlyActiveGroupId: 1\n"
                + "Bonded devices:\n"
                + "    84:1E:1A:0C:74:51 [BR/EDR][ 0x240404 ] ROSE OpenFeel MK2\n";
        in.audioDump = audio;
        in.btLog = log;
        r = WizardLogic.eval(5, in);
        ok("经典链路掉线 → 第 4 项失败", !r.checks.get(3).pass);
    }

    // ------------------------------------------------- 向导：第 6 步

    private static void testWizardFinish(File root) throws Exception {
        p();
        p("[17] 向导 · 第 6 步（写入 auto_mac / target_macs）");
        File f = new File(root, "dbg_bt_connected.txt");
        if (!f.exists()) {
            p("  SKIP  找不到 " + f.getAbsolutePath());
            return;
        }
        String real = read(f);
        WizardLogic.Inputs in = base();
        in.btDump = real;
        WizardLogic.Result r = WizardLogic.eval(6, in);
        eq("还没写入 → READY", r.status, WizardLogic.Status.READY);
        eq("auto_mac 取第一条", r.desiredAutoMac, "84:1E:1A:0C:74:04");
        eq("target_macs 全量", WizardLogic.joinMacs(r.desiredTargetMacs),
                "84:1E:1A:0C:74:04,84:1E:1A:0C:B1:7F");

        in.autoMac = "84:1E:1A:0C:74:04";
        in.targetMacs = "84:1e:1a:0c:b1:7f,84:1E:1A:0C:74:04";
        r = WizardLogic.eval(6, in);
        eq("属性已一致 → PASS", r.status, WizardLogic.Status.PASS);

        ok("target_macs 顺序无关",
                WizardLogic.sameMacSet("84:1E:1A:0C:B1:7F,84:1E:1A:0C:74:04",
                        Arrays.asList("84:1E:1A:0C:74:04", "84:1E:1A:0C:B1:7F")));
        ok("target_macs 缺一个不算一致",
                !WizardLogic.sameMacSet("84:1E:1A:0C:74:04",
                        Arrays.asList("84:1E:1A:0C:74:04", "84:1E:1A:0C:B1:7F")));
        ok("target_macs 空值不算一致",
                !WizardLogic.sameMacSet(null, Arrays.asList("84:1E:1A:0C:74:04")));

        in.btDump = "Bonded devices:\n";
        r = WizardLogic.eval(6, in);
        eq("没有 -LEA → FAIL", r.status, WizardLogic.Status.FAIL);
        ok("建议回第 4 步", r.advice.contains("第 4 步"));
    }

    // ------------------------------------------------- 向导：广播消息

    private static void testWizardMessages() {
        p();
        p("[18] 向导 · 模块广播消息与日志判定");
        ok("pong 成功", !WizardLogic.isFailMsg("pong"));
        ok("forget 成功", !WizardLogic.isFailMsg("84:1E:1A:0C:74:04=true;"));
        ok("connect 成功", !WizardLogic.isFailMsg("connect=true"));
        ok("bond 成功", !WizardLogic.isFailMsg("bond=true"));
        ok("failed 开头失败", WizardLogic.isFailMsg("failed: java.lang.Exception"));
        ok("ERR 开头失败", WizardLogic.isFailMsg("ERR(x)"));
        ok("null 失败", WizardLogic.isFailMsg("null"));
        ok("null 消息失败", WizardLogic.isFailMsg(null));
        ok("unknown cmd 失败", WizardLogic.isFailMsg("unknown cmd: foo"));
        ok("逐项 ERR 失败",
                WizardLogic.isFailMsg("A=true;B=ERR(java.lang.IllegalStateException)"));
        ok("逐项 false 失败", WizardLogic.isFailMsg("A=false;"));
        ok("空消息不算失败", !WizardLogic.isFailMsg("   "));

        ok("日志 status: 9 命中",
                Parsers.bondAuthFailed(
                        "pairing_report_bond_state_change: bd_addr=XX, status: 9"));
        ok("status: 0 不命中",
                !Parsers.bondAuthFailed(
                        "pairing_report_bond_state_change: bd_addr=XX, status: 0"));
        ok("无关日志行不命中", !Parsers.bondAuthFailed("bond state changed status: 9"));
        ok("null 日志不命中", !Parsers.bondAuthFailed(null));

        eq("intValue 正常", Integer.valueOf(Parsers.intValue("27\n", -1)),
                Integer.valueOf(27));
        eq("intValue 失败默认", Integer.valueOf(Parsers.intValue("x", -1)),
                Integer.valueOf(-1));
        eq("状态持久化解析", WizardLogic.parseStatus("SKIP"), WizardLogic.Status.SKIP);
        eq("状态持久化坏值", WizardLogic.parseStatus("bogus"),
                WizardLogic.Status.NONE);

        eq("步骤数", Integer.valueOf(WizardLogic.STEP_COUNT), Integer.valueOf(7));
        ok("徽章文案含符号", WizardLogic.badge(WizardLogic.Status.PASS).contains("✅"));
        ok("跳过徽章", WizardLogic.badge(WizardLogic.Status.SKIP).contains("跳过"));
    }

    // ------------------------------------------------- 向导：空输入健壮性

    private static void testWizardRobustness() {
        p();
        p("[19] 向导 · 空输入 / 无 root 不崩溃");
        for (int s = 0; s < WizardLogic.STEP_COUNT; s++) {
            boolean threw = false;
            WizardLogic.Result er = null;
            try {
                er = WizardLogic.eval(s, new WizardLogic.Inputs());
            } catch (Throwable t) {
                threw = true;
            }
            ok("第 " + s + " 步空输入不抛异常", !threw && er != null && er.status != null);
        }
        boolean threw = false;
        WizardLogic.Result bad = null;
        try {
            bad = WizardLogic.eval(99, new WizardLogic.Inputs());
        } catch (Throwable t) {
            threw = true;
        }
        ok("未知步骤返回 FAIL 而不是异常",
                !threw && bad != null && bad.status == WizardLogic.Status.FAIL);
        ok("空 dump 的 Bonded 列表为空", Parsers.bondedDevices("").isEmpty());
        ok("findAdvLine 空输入安全", WizardLogic.findAdvLine(null, "84:1E:1A") == null);
        ok("isFailMsg 空串不崩", !WizardLogic.isFailMsg(""));
    }

    // ------------------------------------------------------------------ Shell

    private static void testShellSmoke() {
        p();
        p("[10] Shell 无 root 降级（本机无 su 也必须是安全失败）");
        eq("quote 转义", Shell.quote("a'b"), "'a'\\''b'");
        eq("quote 空串", Shell.quote(null), "''");

        Shell.Result empty = Shell.run("", 1000);
        ok("空命令直接返回", empty != null && empty.ok());

        boolean threw = false;
        Shell.Result r = null;
        try {
            r = Shell.run("echo lea", 4000);
        } catch (Throwable t) {
            threw = true;
        }
        ok("执行命令不抛异常", !threw && r != null);
        p("        本机执行结果: code=" + (r == null ? "?" : r.code)
                + " error=" + (r == null ? "?" : r.error)
                + " out=" + (r == null ? "?" : r.out.trim()));

        boolean rootThrew = false;
        boolean root = false;
        try {
            root = Shell.rootAvailable();
        } catch (Throwable t) {
            rootThrew = true;
        }
        ok("root 探测不抛异常", !rootThrew);
        p("        本机 rootAvailable=" + root + "（Windows 上应为 false）");
        ok("rootCached 已可读", Shell.rootCached() != null);
    }
}
