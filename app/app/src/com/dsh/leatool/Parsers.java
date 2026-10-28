package com.dsh.leatool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯字符串解析逻辑（不依赖 Android API），把各条命令输出解析成结构化状态。
 * 所有方法对 null / 空串 / 异常都返回 null 或空集合，不抛异常。
 */
final class Parsers {

    private Parsers() {
    }

    // ---------------------------------------------------------------- 基础

    /** getprop 的返回值。null / 空 / "null" 都视为未设置。 */
    static String propValue(String out) {
        if (out == null) {
            return null;
        }
        String v = out.trim();
        while (v.startsWith("\n")) {
            v = v.substring(1).trim();
        }
        if (v.isEmpty() || "null".equals(v)) {
            return null;
        }
        return v;
    }

    static String find(String text, String regex) {
        return find(text, regex, 1, Pattern.MULTILINE);
    }

    static String find(String text, String regex, int flags) {
        return find(text, regex, 1, flags);
    }

    static String find(String text, String regex, int group, int flags) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            Matcher m = Pattern.compile(regex, flags).matcher(text);
            if (m.find()) {
                // 正则没有捕获组时退回 group(0)，避免 IndexOutOfBounds 被吞掉
                int g = group <= m.groupCount() ? group : 0;
                String v = m.group(g);
                if (v != null) {
                    v = v.trim();
                    return v.isEmpty() ? null : v;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 多行文本里最后一条非空行。 */
    static String lastLine(String out) {
        if (out == null) {
            return null;
        }
        String[] lines = out.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String s = lines[i].trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    // ------------------------------------------------- dumpsys bluetooth_manager

    static String activeGroupId(String dump) {
        if (dump == null) {
            return null;
        }
        String v = find(dump, "^\\s*(?:currentlyActiveGroupId|mActiveGroupId|activeGroupId"
                + "|currentlyActiveGroup)\\s*[:=]\\s*(\\S+)", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
        if (v == null) {
            v = find(dump, "activegroupid\\s*[:=]\\s*(\\S+)", Pattern.CASE_INSENSITIVE);
        }
        return v;
    }

    static String activeAudioOut(String dump) {
        if (dump == null) {
            return null;
        }
        String v = find(dump, "^\\s*mActiveAudioOutDevice\\s*[:=]\\s*(.+)$", Pattern.MULTILINE);
        if (v == null) {
            v = find(dump, "^\\s*[A-Za-z_]*ActiveAudioOut[A-Za-z_]*\\s*[:=]\\s*(.+)$",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
        }
        return v;
    }

    /**
     * A2dpStateMachine 块里的 mConnectionState。
     * <p>真实格式（ColorOS 实测）：
     * <pre>
     *   === A2dpStateMachine for XX:XX:XX:XX:74:51 (Active) ===
     *     mConnectionState: CONNECTED, mLastConnectionState: CONNECTING
     * </pre>
     * MAC 会被系统打码成 XX:XX:...，所以只按 "A2dpStateMachine for" 前缀取标识符、
     * 不校验是否合法 MAC；状态只取冒号后的第一个词（不把 mLastConnectionState 带进来）。
     */
    static Map<String, String> a2dpStates(String dump) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        if (dump == null || dump.isEmpty()) {
            return map;
        }
        Pattern headPat = Pattern.compile(
                "A2dpStateMachine for\\s+([A-Za-z0-9:_\\-]+)(?:\\s*\\(([^)]*)\\))?");
        Pattern statePat = Pattern.compile(
                "mConnectionState\\s*[:=]\\s*([A-Za-z_][A-Za-z_0-9]*)");
        String cur = null;
        String[] lines = dump.split("\n");
        for (String line : lines) {
            Matcher mm = headPat.matcher(line);
            if (mm.find()) {
                String id = mm.group(1);
                // 老格式标题结尾带冒号："A2dpStateMachine for AA:BB:...:"
                while (id.endsWith(":")) {
                    id = id.substring(0, id.length() - 1);
                }
                id = id.toUpperCase(Locale.US);
                String flag = mm.group(2);
                cur = (flag == null || flag.trim().isEmpty())
                        ? id : (id + " (" + flag.trim() + ")");
                if (!map.containsKey(cur)) {
                    map.put(cur, "?");
                }
                continue;
            }
            if (cur != null) {
                Matcher sm = statePat.matcher(line);
                if (sm.find()) {
                    map.put(cur, sm.group(1));
                }
            }
        }
        if (map.isEmpty()) {
            // 有些 ROM 用 "mStateMachines:" + "MAC:" + "Current State: XXX" 的形式
            map = a2dpStatesAlt(dump);
        }
        return map;
    }

    private static Map<String, String> a2dpStatesAlt(String dump) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        int idx = dump.indexOf("mStateMachines");
        if (idx < 0) {
            return map;
        }
        Pattern macLine = Pattern.compile(
                "^\\s*([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}):\\s*$");
        Pattern stateLine = Pattern.compile(
                "^\\s*(?:Current State|mState|mConnectionState)\\s*[:=]\\s*([A-Za-z_]+)");
        String cur = null;
        for (String line : dump.substring(idx).split("\n")) {
            if (line.startsWith("Profile:")) {
                break;
            }
            Matcher m = macLine.matcher(line);
            if (m.find()) {
                cur = m.group(1).toUpperCase(Locale.US);
                if (!map.containsKey(cur)) {
                    map.put(cur, "?");
                }
                continue;
            }
            if (cur != null) {
                Matcher s = stateLine.matcher(line);
                if (s.find()) {
                    map.put(cur, s.group(1));
                }
            }
        }
        return map;
    }

    static String dualModeAudioEnabled(String dump) {
        if (dump == null) {
            return null;
        }
        return find(dump, "DualModeAudioEnabled\\s*[:=]\\s*([A-Za-z0-9_]+)",
                Pattern.CASE_INSENSITIVE);
    }

    /** A2dpService 的 mActiveDevice / mA2dpActiveDevice（状态机块缺失时的补充）。 */
    static String a2dpActiveDevice(String dump) {
        if (dump == null) {
            return null;
        }
        String v = find(dump, "(?m)^\\s*mActiveDevice\\s*[:=]\\s*(.+)$");
        if (v == null) {
            v = find(dump, "(?m)^\\s*mA2dpActiveDevice\\s*[:=]\\s*(.+)$");
        }
        return v;
    }

    /** LE Audio group 的 active configuration name（日志缺失时的回退）。 */
    static String activeConfigName(String dump) {
        if (dump == null) {
            return null;
        }
        return find(dump, "(?m)^\\s*active configuration name:\\s*(.+)$");
    }

    /** 从 dumpsys 的 ASE 表里取第一条 ASE 的 cis_state（日志缺失时的回退）。 */
    static String aseStateFromDump(String dump) {
        if (dump == null) {
            return null;
        }
        int idx = dump.indexOf("== ASEs ==");
        if (idx < 0) {
            return null;
        }
        String[] lines = dump.substring(idx).split("\n");
        Pattern p = Pattern.compile(
                "^\\s*\\d+\\s+(?:true|false)\\s+(?:sink|source)\\s+.*?\\s+([A-Z_]+)\\s+\\(0x[0-9a-fA-F]+\\)");
        for (int i = 0; i < lines.length && i < 12; i++) {
            Matcher m = p.matcher(lines[i]);
            if (m.find()) {
                return "ASE1=" + m.group(1) + "（来自 dumpsys，日志不可用时回退）";
            }
        }
        return null;
    }

    // ------------------------------------------------------------- dumpsys audio

    /** 取 AUDIO_STREAM_MUSIC 段落里的 Devices: 行。 */
    static String musicDevices(String dump) {
        if (dump == null || dump.isEmpty()) {
            return null;
        }
        String[] lines = dump.split("\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].toUpperCase(Locale.US);
            if (l.contains("AUDIO_STREAM_MUSIC") || l.contains("STREAM_MUSIC")) {
                start = i;
                break;
            }
        }
        Pattern dev = Pattern.compile("^\\s*Device[s]?\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
        if (start >= 0) {
            for (int i = start + 1; i < lines.length && i < start + 80; i++) {
                Matcher m = dev.matcher(lines[i]);
                if (m.find()) {
                    return m.group(0).trim();
                }
            }
        }
        // 回退 1：任意一行 Devices: 里带路由关键字
        String any = find(dump, "(?im)^\\s*Device[s]?\\s*:.*(?:ble_headset|bt_a2dp|speaker).*$");
        if (any != null) {
            return any.trim();
        }
        // 回退 2：直接找提到 ble_headset / bt_a2dp 的第一行
        String kw = find(dump, "(?im)^.*(?:ble_headset|bt_a2dp).*$");
        return kw == null ? null : kw.trim();
    }

    /** 根据 Devices: 行判断当前音乐路由。 */
    static String routeKind(String devicesLine) {
        if (devicesLine == null || devicesLine.trim().isEmpty()) {
            return "未知";
        }
        String l = devicesLine.toLowerCase(Locale.US);
        boolean ble = l.contains("ble_headset") || l.contains("ble_broadcast")
                || l.contains("le_audio") || l.contains("ble_speaker");
        boolean a2dp = l.contains("bt_a2dp") || l.contains("bt_a2dp_hardware_offload")
                || l.contains("a2dp");
        if (ble && a2dp) {
            return "ble_headset + bt_a2dp（同时存在）";
        }
        if (ble) {
            return "ble_headset（LE Audio 通道）";
        }
        if (a2dp) {
            return "bt_a2dp（经典蓝牙通道）";
        }
        if (l.contains("speaker")) {
            return "speaker（本机外放）";
        }
        if (l.contains("usb") || l.contains("wired")) {
            return "有线/USB";
        }
        return "其他";
    }

    // ------------------------------------------------- dumpsys notification 电量

    /**
     * 从 `dumpsys notification --noredact` 全量输出里找目标包名通知的 android.text。
     * 按 NotificationRecord 分块，优先返回最后一个属于该包的块里的 android.text。
     */
    static String batteryText(String dump, String pkg) {
        if (dump == null || dump.isEmpty() || pkg == null) {
            return null;
        }
        String[] lines = dump.split("\n");
        String found = null;
        int i = 0;
        while (i < lines.length) {
            if (lines[i].contains("NotificationRecord")) {
                int end = lines.length;
                for (int j = i + 1; j < lines.length; j++) {
                    if (lines[j].contains("NotificationRecord")) {
                        end = j;
                        break;
                    }
                }
                StringBuilder sb = new StringBuilder();
                for (int j = i; j < end; j++) {
                    sb.append(lines[j]).append('\n');
                }
                String block = sb.toString();
                if (block.contains(pkg)) {
                    String t = extractTextField(block);
                    if (t != null) {
                        found = t;
                    }
                }
                i = end;
            } else {
                i++;
            }
        }
        if (found != null) {
            return found;
        }
        // 退化：包名出现位置之后 120 行内找 android.text
        int at = dump.indexOf(pkg);
        if (at >= 0) {
            String tail = dump.substring(at);
            String[] tl = tail.split("\n");
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < tl.length && k < 120; k++) {
                sb.append(tl[k]).append('\n');
            }
            return extractTextField(sb.toString());
        }
        return null;
    }

    /** 从一段文本里取最后一个非空 android.text= 值。 */
    static String extractTextField(String block) {
        if (block == null || block.isEmpty()) {
            return null;
        }
        String result = null;
        int from = 0;
        while (true) {
            int idx = block.indexOf("android.text=", from);
            if (idx < 0) {
                break;
            }
            int eol = block.indexOf('\n', idx);
            if (eol < 0) {
                eol = block.length();
            }
            String raw = block.substring(idx + "android.text=".length(), eol).trim();
            String v = cleanTextValue(raw);
            if (v != null) {
                result = v;
            }
            from = eol;
        }
        return result;
    }

    private static String cleanTextValue(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        // 同一行后面还跟着别的 key（如 , android.bigText=...）时截断
        int cut = v.indexOf(", android.");
        if (cut <= 0) {
            cut = v.indexOf(",android.");
        }
        if (cut > 0) {
            v = v.substring(0, cut).trim();
        }
        // 先去掉 bundle / 列表的尾部符号（不动右括号，避免破坏 String(...) 包装）
        v = stripTail(v, "}]");
        if (v.isEmpty() || "null".equals(v)) {
            return null;
        }
        // 常见的包装：String (...)、SpannableString(...)、StringBuilder{...}
        String[] wrappers = {"String (", "SpannableString(", "StringBuilder{"};
        for (String w : wrappers) {
            char end = w.endsWith("{") ? '}' : ')';
            if (v.startsWith(w) && v.length() > w.length() && v.charAt(v.length() - 1) == end) {
                v = v.substring(w.length(), v.length() - 1).trim();
                break;
            }
        }
        v = stripTail(v, "}]\")',");
        v = v.replace("\\n", " ").replace("\r", " ").trim();
        // 去掉成对引号
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1).trim();
        }
        if (v.isEmpty() || "null".equals(v)) {
            return null;
        }
        return v;
    }

    private static String stripTail(String v, String chars) {
        String s = v == null ? "" : v;
        while (!s.isEmpty() && chars.indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    /** grep 出来的一行 android.text= 里取值（配合 `grep -A | grep android.text`）。 */
    static String batteryTextFromGrep(String grepOut) {
        String v = extractTextField(grepOut);
        if (v == null) {
            return null;
        }
        if (v.contains("%") || v.contains("耳") || v.contains("仓")
                || v.toLowerCase(Locale.US).contains("bat")) {
            return v;
        }
        return null;
    }

    // --------------------------------------------------------------- LSPosed CLI

    /** `/data/adb/lspd/cli modules ls` 输出里 com.dsh.lea 的状态。 */
    static String moduleState(String out, String moduleId) {
        if (out == null || out.trim().isEmpty()) {
            return "未知（无输出）";
        }
        String[] lines = out.split("\n");
        for (String line : lines) {
            if (!line.contains(moduleId)) {
                continue;
            }
            String low = line.toLowerCase(Locale.US);
            if (low.contains("disabl") || line.contains("禁用")) {
                return "disabled（已禁用）";
            }
            if (low.contains("enabl") || line.contains("启用") || line.contains("[x]")
                    || line.contains("✓") || line.contains("✔")) {
                return "enabled（已启用）";
            }
            return "未知（原样：" + line.trim() + "）";
        }
        return "未找到模块（未安装？）";
    }

    // -------------------------------------------------------------- getprop 相关

    /** getprop 输出里所有 persist.sys.lea.* 的 key。 */
    static List<String> leaPropKeys(String getpropOut) {
        List<String> out = new ArrayList<String>();
        if (getpropOut == null || getpropOut.isEmpty()) {
            return out;
        }
        Pattern p = Pattern.compile("\\[(persist\\.sys\\.lea\\.[A-Za-z0-9_.\\-]+)\\]");
        Matcher m = p.matcher(getpropOut);
        while (m.find()) {
            String k = m.group(1);
            if (!out.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }

    /** 取一条 grep 结果里 "key]: [value]" 的值部分，没有则原样返回。 */
    static String stripGetpropLine(String line) {
        if (line == null) {
            return null;
        }
        String s = line.trim();
        int i = s.lastIndexOf("]: [");
        if (i >= 0 && s.endsWith("]")) {
            return s.substring(i + 4, s.length() - 1).trim();
        }
        return s;
    }

    /** 本地过滤 getprop 输出：key 命中任意 filter 就保留整行。 */
    static String filterProps(String getpropOut, String[] filters) {
        if (getpropOut == null || getpropOut.isEmpty() || filters == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : getpropOut.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            int lb = t.indexOf('[');
            int rb = t.indexOf(']');
            if (lb != 0 || rb <= 1) {
                continue;
            }
            String key = t.substring(1, rb).toLowerCase(Locale.US);
            for (String f : filters) {
                if (key.contains(f)) {
                    sb.append(t).append('\n');
                    break;
                }
            }
        }
        return sb.toString().trim();
    }

    // -------------------------------------------------------- Bonded devices

    /**
     * 一条已配对设备。真实格式（ColorOS，见 dbg_bt_connected.txt）：
     * <pre>
     *   Bonded devices:
     *     CC:FF:90:3C:E7:C5 [  LE  ][ 0x000000 ] HUAWEI Band 9-7C5
     *     84:1E:1A:0C:74:51 [BR/EDR][ 0x240404 ] ROSE OpenFeel MK2
     *     84:1E:1A:0C:74:04 [  LE  ][ 0x240404 ] ROSE OpenFeel MK2-LEA
     * </pre>
     */
    static final class Bonded {
        final String mac;
        /** BR/EDR | LE | DUAL | ? */
        final String transport;
        /** 原始 flags（如 0x240404），可能为 null。 */
        final String flags;
        final String name;
        /** 由 connectedMacs() 事后标记，仅用于 UI 展示。 */
        boolean connected;

        Bonded(String mac, String transport, String flags, String name) {
            this.mac = mac == null ? "" : mac.toUpperCase(Locale.US);
            this.transport = transport == null ? "?" : transport;
            this.flags = flags;
            this.name = name == null ? "" : name;
        }

        boolean isLe() {
            return "LE".equals(transport);
        }

        boolean isClassic() {
            return "BR/EDR".equals(transport) || "DUAL".equals(transport);
        }

        boolean nameContains(String keyword) {
            if (keyword == null || keyword.trim().isEmpty()) {
                return true;
            }
            return name.toLowerCase(Locale.US).contains(keyword.trim().toLowerCase(Locale.US));
        }

        String label() {
            return "[" + transport + "] " + mac + "  " + name;
        }
    }

    private static final Pattern BONDED_LINE = Pattern.compile(
            "^\\s*((?:[0-9A-Fa-fXx]{2}:){5}[0-9A-Fa-fXx]{2})\\s*"
                    + "\\[\\s*([A-Za-z/]+)\\s*\\]\\s*"
                    + "(?:\\[\\s*([^\\]]*?)\\s*\\])?\\s*(.*)$");

    /** 解析 "Bonded devices:" 段。空输入 / 没有该段都返回空列表。 */
    static List<Bonded> bondedDevices(String dump) {
        List<Bonded> out = new ArrayList<Bonded>();
        if (dump == null || dump.isEmpty()) {
            return out;
        }
        int idx = dump.indexOf("Bonded devices");
        if (idx < 0) {
            return out;
        }
        String[] lines = dump.substring(idx).split("\n");
        boolean started = false;
        for (int i = 1; i < lines.length; i++) {
            // dump 文件是 CRLF，split("\n") 后行尾会留 \r，先去掉再匹配
            String line = lines[i].replace("\r", "");
            Matcher m = BONDED_LINE.matcher(line);
            if (m.matches()) {
                started = true;
                String transport = m.group(2).toUpperCase(Locale.US).replace(" ", "");
                out.add(new Bonded(m.group(1), transport, m.group(3),
                        (m.group(4) == null ? "" : m.group(4).trim())));
                continue;
            }
            if (started) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                break;
            }
        }
        return out;
    }

    /** 地址是否被打码（ColorOS 会把前 4 段写成 XX）。 */
    static boolean isMaskedMac(String mac) {
        return mac != null && mac.toUpperCase(Locale.US).contains("XX");
    }

    /**
     * 两个地址是否指同一台设备：完全相同，或一方被打码、最后两段相同
     * （系统 dump 里 84:1E:1A:0C:74:04 会变成 XX:XX:XX:XX:74:04）。
     */
    static boolean sameDevice(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.toUpperCase(Locale.US);
        String y = b.toUpperCase(Locale.US);
        if (x.equals(y)) {
            return true;
        }
        if (!isMaskedMac(x) && !isMaskedMac(y)) {
            return false;
        }
        String[] xs = x.split(":");
        String[] ys = y.split(":");
        if (xs.length != 6 || ys.length != 6) {
            return false;
        }
        return xs[4].equals(ys[4]) && xs[5].equals(ys[5]);
    }

    /** A2dpStateMachine 里 state=CONNECTED 的设备地址（可能被打码）。 */
    static List<String> a2dpConnectedMacs(String dump) {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, String> e : a2dpStates(dump).entrySet()) {
            if (!"CONNECTED".equalsIgnoreCase(e.getValue())) {
                continue;
            }
            String k = e.getKey();
            int sp = k.indexOf(' ');
            out.add((sp > 0 ? k.substring(0, sp) : k).toUpperCase(Locale.US));
        }
        return out;
    }

    private static final Pattern CONN_CHANGED = Pattern.compile(
            "connectionStateChanged:\\s*device:\\s*([0-9A-Fa-fXx:]{17}),\\s*"
                    + "fromState=\\s*([A-Z_]+),\\s*toState=\\s*([A-Z_]+)");
    private static final Pattern LE_STATE_MACHINE = Pattern.compile(
            "StateMachine:\\s*name=[A-Za-z0-9_]+\\(([0-9A-Fa-fXx:]{17})\\)\\s*state=([A-Za-z_]+)");

    /**
     * 从 dumpsys bluetooth_manager 里推断「当前已连接」的地址集合。
     * 只用它给僵尸配对列表加「当前连接」标记，判据不依赖它。
     */
    static java.util.Set<String> connectedMacs(String dump) {
        java.util.Set<String> out = new java.util.LinkedHashSet<String>();
        if (dump == null || dump.isEmpty()) {
            return out;
        }
        Map<String, Boolean> state = new LinkedHashMap<String, Boolean>();
        // A2DP 先铺底，后面的 connectionStateChanged / StateMachine 行按出现顺序覆盖（后者更新）
        for (String mac : a2dpConnectedMacs(dump)) {
            state.put(mac, Boolean.TRUE);
        }
        for (String line : dump.split("\n")) {
            Matcher m = CONN_CHANGED.matcher(line);
            if (m.find()) {
                state.put(m.group(1).toUpperCase(Locale.US),
                        m.group(3).toUpperCase(Locale.US).contains("CONNECTED"));
                continue;
            }
            m = LE_STATE_MACHINE.matcher(line);
            if (m.find()) {
                state.put(m.group(1).toUpperCase(Locale.US),
                        "Connected".equalsIgnoreCase(m.group(2)));
            }
        }
        for (Map.Entry<String, Boolean> e : state.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** 给列表里的设备打上「当前连接」标记（按 sameDevice 匹配）。 */
    static void markConnected(List<Bonded> list, java.util.Set<String> connected) {
        if (list == null || connected == null) {
            return;
        }
        for (Bonded b : list) {
            for (String c : connected) {
                if (sameDevice(b.mac, c)) {
                    b.connected = true;
                    break;
                }
            }
        }
    }

    private static final Pattern BOND_STATUS_9 = Pattern.compile("status\\s*[:=]\\s*9\\b");

    /**
     * 蓝牙日志里是否出现配对认证失败：`pairing_report_bond_state_change` 行带 status: 9。
     * 这是「僵尸配对」的三个触发条件之一。
     */
    static boolean bondAuthFailed(String logText) {
        if (logText == null || logText.isEmpty()) {
            return false;
        }
        for (String line : logText.split("\n")) {
            if (!line.contains("pairing_report_bond_state_change")) {
                continue;
            }
            if (BOND_STATUS_9.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    /** 解析 `grep -c` 之类的整数输出；失败返回 def。 */
    static int intValue(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    // ------------------------------------------------------------------ MAC

    static boolean isMac(String s) {
        return s != null && MAC_PATTERN.matcher(s).matches();
    }

    private static final Pattern MAC_PATTERN =
            Pattern.compile("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$");

    /** 校验并规范化逗号/空格分隔的 MAC 列表；有非法项返回 null。 */
    static String normalizeMacList(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split("[,;\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (!isMac(t)) {
                return null;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(t.toUpperCase(Locale.US));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 扫描结果过滤：名称含 OpenFeel 或 -LEA。 */
    static boolean isLeaCandidateName(String name) {
        if (name == null) {
            return false;
        }
        String up = name.toUpperCase(Locale.US);
        return up.contains("OPENFEEL") || up.contains("-LEA");
    }

    // ------------------------------------------------------------- 扫描过滤

    /** 默认按 OUI 前缀兜底：这台耳机（ROSE OpenFeel MK2 左右耳）都是 84:1E:1A。 */
    static final String DEFAULT_OUI_PREFIX = "84:1E:1A";

    /** 把 "84:1e:1a, AA:BB" 这类输入规范化成 ["84:1E:1A","AA:BB"]。 */
    static String[] parsePrefixes(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new String[0];
        }
        String[] parts = raw.split("[,;\\s]+");
        List<String> out = new ArrayList<String>();
        for (String p : parts) {
            String t = p.trim().toUpperCase(Locale.US);
            if (t.isEmpty()) {
                continue;
            }
            // 允许 84:1E / 84:1E:1A / 841E1A 三种写法
            if (!t.matches("^[0-9A-F]{2}(:?[0-9A-F]{2}){0,4}$")) {
                continue;
            }
            out.add(t);
        }
        return out.toArray(new String[0]);
    }

    /** 地址是否命中任一前缀（大小写不敏感，忽略分隔符差异）。 */
    static String matchPrefix(String address, String[] prefixes) {
        if (address == null || prefixes == null) {
            return null;
        }
        String plain = address.toUpperCase(Locale.US).replace(":", "");
        for (String p : prefixes) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            String pp = p.toUpperCase(Locale.US).replace(":", "");
            if (plain.startsWith(pp)) {
                return p.toUpperCase(Locale.US);
            }
        }
        return null;
    }

    /**
     * 扫描结果是否要列出，返回命中原因；null = 不列出。
     * 依次判断：名称关键字 → MAC 前缀 → （showAll 时）全部。
     */
    static String scanMatchReason(String name, String address, String[] prefixes,
                                  boolean showAll) {
        if (isLeaCandidateName(name)) {
            return "名称";
        }
        String hit = matchPrefix(address, prefixes);
        if (hit != null) {
            return "MAC 前缀 " + hit;
        }
        if (showAll) {
            return "显示全部";
        }
        return null;
    }

    // ------------------------------------------------------------ 日志行整理

    /**
     * 整理 ASE 日志行：系统日志会把 "ASE id: 0x1" 和 "ASE state: Idle (0x0)"
     * 无分隔符地粘在一起（"ASE id: 0x1ASE state: Idle (0x0)"），这里补上分隔符。
     */
    static String tidyAseLine(String s) {
        if (s == null) {
            return null;
        }
        String v = s.replace('\t', ' ').replaceAll("\\s+", " ").trim();
        if (v.isEmpty()) {
            return null;
        }
        v = v.replaceAll("(?<=\\S)(ASE (?:id|state)\\s*:)", " \u00b7 $1");
        return v.trim();
    }
}
