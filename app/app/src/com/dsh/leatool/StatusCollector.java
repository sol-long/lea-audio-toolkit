package com.dsh.leatool;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 状态采集器：并行跑几条只读命令，解析成 {@link Snapshot}。
 * 任何一条失败都只记录到 issues，不影响其它条目。
 */
final class StatusCollector {

    static final String PKG_BATTERY = "cn.ikaile.ruoshui.client";
    static final String MODULE_ID = "com.dsh.lea";

    static final String CMD_DUAL_PROP = "getprop persist.bluetooth.enable_dual_mode_audio";
    static final String CMD_BT_DUMP = "dumpsys bluetooth_manager";
    static final String CMD_AUDIO_DUMP = "dumpsys audio";

    /** 一次 su 调用拿到日志路径 + 两条关键日志。 */
    static final String CMD_BT_LOG =
            "F=$(ls -t /data/misc/bluetooth/logs/bluetooth_*.log 2>/dev/null | head -n 1); "
                    + "if [ -z \"$F\" ]; then echo NO_LOG; else echo FILE=$F; "
                    + "grep -a 'active configuration name' \"$F\" | tail -n 1; "
                    + "echo ===ASE===; "
                    + "grep -aE 'ASE state|ASE State|ase state|ase_state|ASE id' \"$F\" "
                    + "| tail -n 2 | tr '\\n' ' '; fi";

    static final String CMD_NOTIF =
            "dumpsys notification --noredact 2>/dev/null "
                    + "| grep -a -A 100 'cn\\.ikaile\\.ruoshui\\.client' "
                    + "| grep -a 'android\\.text=' | grep -aE '[0-9]+%|\u8033|\u4ed3' | tail -n 1";

    static final String CMD_MODULE = "/data/adb/lspd/cli modules ls";

    private StatusCollector() {
    }

    static Snapshot collect() {
        final Snapshot s = new Snapshot();
        s.time = System.currentTimeMillis();
        s.root = Shell.rootAvailable();

        ExecutorService pool = Executors.newFixedThreadPool(6);
        final CountDownLatch latch = new CountDownLatch(6);

        pool.execute(new Task(latch, "双模属性") {
            void body() {
                // 注意：app uid 直接 getprop 可能被 SELinux 挡掉（返回空串），
                // 所以这里走 Shell.run()（有 root 就 su），并把原始输出直接展示。
                Shell.Result r = Shell.run(CMD_DUAL_PROP, 10000);
                s.dualModeProp = pick(s, r, "双模属性");
                if (s.dualModeProp == null) {
                    s.issues.add("双模属性读取为空：su 未授权成功，或该机型没有 "
                            + "persist.bluetooth.enable_dual_mode_audio（app uid 直连 getprop 会被 SELinux 拦掉）");
                }
            }
        });

        pool.execute(new Task(latch, "bluetooth_manager") {
            void body() {
                Shell.Result r = Shell.run(CMD_BT_DUMP, 15000);
                String dump = usable(s, r, "dumpsys bluetooth_manager");
                if (dump == null) {
                    return;
                }
                s.groupId = Parsers.activeGroupId(dump);
                s.activeAudioOut = Parsers.activeAudioOut(dump);
                s.dualModeEnabled = Parsers.dualModeAudioEnabled(dump);
                s.a2dpActiveDevice = Parsers.a2dpActiveDevice(dump);
                s.dumpConfigName = Parsers.activeConfigName(dump);
                s.dumpAseState = Parsers.aseStateFromDump(dump);
                Map<String, String> states = Parsers.a2dpStates(dump);
                if (states != null && !states.isEmpty()) {
                    s.a2dp.putAll(states);
                }
            }
        });

        pool.execute(new Task(latch, "audio") {
            void body() {
                Shell.Result r = Shell.run(CMD_AUDIO_DUMP, 15000);
                String dump = usable(s, r, "dumpsys audio");
                if (dump == null) {
                    return;
                }
                s.musicDevices = Parsers.musicDevices(dump);
                s.routeKind = Parsers.routeKind(s.musicDevices);
            }
        });

        pool.execute(new Task(latch, "蓝牙日志") {
            void body() {
                Shell.Result r = Shell.run(CMD_BT_LOG, 15000);
                String out = usable(s, r, "蓝牙日志");
                if (out == null) {
                    return;
                }
                if (out.contains("NO_LOG")) {
                    s.issues.add("蓝牙日志目录不可读（/data/misc/bluetooth/logs 需要 root）");
                    return;
                }
                String[] parts = out.split("===ASE===", 2);
                String head = parts[0];
                String ase = parts.length > 1 ? parts[1] : null;
                StringBuilder cfg = new StringBuilder();
                for (String line : head.split("\n")) {
                    String t = line.trim();
                    if (t.isEmpty()) {
                        continue;
                    }
                    if (t.startsWith("FILE=")) {
                        s.btLogFile = t.substring("FILE=".length()).trim();
                    } else {
                        cfg.append(t).append('\n');
                    }
                }
                s.btConfigName = Parsers.lastLine(cfg.toString());
                s.btAseState = Parsers.tidyAseLine(Parsers.lastLine(ase));
            }
        });

        pool.execute(new Task(latch, "通知电量") {
            void body() {
                Shell.Result r = Shell.run(CMD_NOTIF, 20000);
                if (r == null || r.timedOut) {
                    s.issues.add("电量通知：读取超时（dumpsys notification 太慢），稍后点「立即刷新」重试");
                    return;
                }
                String out = r.out.trim();
                if (out.isEmpty()) {
                    s.issues.add("电量通知：未找到 cn.ikaile.ruoshui.client 的电量文本 · "
                            + "打开 Roselink（柔水）让它推一次设备状态通知后再刷新；"
                            + "若通知明明在也取不到，可能是系统限制了 dumpsys notification（需 root）");
                    return;
                }
                String v = Parsers.extractTextField(out);
                if (v == null) {
                    v = Parsers.batteryTextFromGrep(out);
                }
                s.batteryText = v;
                if (v == null) {
                    s.issues.add("电量通知：匹配到该通知，但没找到 android.text 电量字段（字段格式可能不同）");
                }
            }
        });

        pool.execute(new Task(latch, "LSPosed 模块") {
            void body() {
                Shell.Result r = Shell.run(CMD_MODULE, 10000);
                String out = usable(s, r, "lspd modules ls");
                if (out == null) {
                    return;
                }
                s.moduleRaw = out.length() > 4000 ? out.substring(0, 4000) : out;
                s.moduleState = Parsers.moduleState(out, MODULE_ID);
            }
        });

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }

        // 日志拿不到时回退到 dumpsys 里的同名字段
        if (s.btConfigName == null) {
            s.btConfigName = s.dumpConfigName;
        }
        if (s.btAseState == null) {
            s.btAseState = s.dumpAseState;
        }
        return s;
    }

    /** 命令输出可用则返回 trim 后的文本，否则把原因写进 issues 并返回 null。 */
    private static String usable(Snapshot s, Shell.Result r, String what) {
        if (r == null) {
            s.issues.add(what + ": 无输出");
            return null;
        }
        if (r.timedOut) {
            s.issues.add(what + ": 超时");
            return null;
        }
        String t = r.out.trim();
        if (t.isEmpty()) {
            s.issues.add(what + ": 空输出"
                    + (r.code != 0 ? ("（exit " + r.code + "）") : ""));
            return null;
        }
        String low = t.toLowerCase();
        if (low.contains("permission denial") || low.contains("permission denied")
                || low.contains("not allowed") || low.contains("operation not permitted")) {
            s.issues.add(what + ": 权限不足（需要 root）");
            return null;
        }
        // 短输出 + 典型错误字样 => 命令本身没跑起来
        if (t.length() < 200 && (low.contains("unknown command") || low.contains("no such file")
                || low.contains("not found") || low.contains("inaccessible"))) {
            s.issues.add(what + ": " + firstLine(t));
            return null;
        }
        return t;
    }

    /** 单条属性值。 */
    private static String pick(Snapshot s, Shell.Result r, String what) {
        if (r == null) {
            return null;
        }
        if (r.timedOut) {
            s.issues.add(what + ": 超时");
            return null;
        }
        if (r.error != null) {
            s.issues.add(what + ": " + r.error);
            return null;
        }
        return Parsers.propValue(r.out);
    }

    private static String firstLine(String t) {
        int i = t.indexOf('\n');
        String s = i > 0 ? t.substring(0, i) : t;
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private abstract static class Task implements Runnable {
        final CountDownLatch latch;
        final String name;

        Task(CountDownLatch latch, String name) {
            this.latch = latch;
            this.name = name;
        }

        abstract void body();

        @Override
        public void run() {
            try {
                body();
            } catch (Throwable ignored) {
                // 采集不允许崩
            } finally {
                latch.countDown();
            }
        }
    }
}
