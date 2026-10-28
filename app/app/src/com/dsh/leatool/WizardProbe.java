package com.dsh.leatool;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 向导的采集器：按步骤并行跑只读命令，拼成 {@link WizardLogic.Inputs}。
 *
 * <p>每条命令都有单独超时（{@link Shell#run} 内部 waitFor），整体再等 25 秒；
 * 没有 root 时命令失败只会让对应字段为 null，{@link WizardLogic} 会判成「未通过 + 需要 root」，
 * 不会崩。
 */
final class WizardProbe {

    static final String CMD_PROPS =
            "echo DUAL=$(getprop " + WizardLogic.P_DUAL + "); "
                    + "echo WL=$(getprop " + WizardLogic.P_WHITE_LIST + "); "
                    + "echo BTON=$(settings get global bluetooth_on 2>/dev/null); "
                    + "echo AUTO=$(getprop " + WizardLogic.P_AUTO_MAC + "); "
                    + "echo TARGETS=$(getprop " + WizardLogic.P_TARGET_MACS + "); "
                    + "echo PKG=$(pm list packages " + WizardLogic.PKG_ROSELINK
                    + " 2>/dev/null | head -n 1)";

    /**
     * 一次 su 拿到：日志路径、[LeaEnabler] 行数、广播地址 / LE Audio EIR UUID /
     * ASE / 配置名 / 配对失败。
     *
     * <p>===EIR=== 是第 3 步的主判据来源：切到 LC3 后蓝牙搜索会在日志里打
     * {@code btif_dm_search_devices_evt: 0000184e-...} / {@code BTM_GetEirUuidList: 0x184E}
     * （ASCS/CSIS 只有 LE Audio 设备才有）。dumpsys 里拿不到未配对设备的名字。
     */
    static final String CMD_BT_LOG =
            "F=$(ls -t /data/misc/bluetooth/logs/bluetooth_*.log 2>/dev/null | head -n 1); "
                    + "if [ -z \"$F\" ]; then echo NO_LOG; exit 0; fi; "
                    + "echo LOG=$F; "
                    + "echo LEA=$(grep -a -c 'LeaEnabler' \"$F\" 2>/dev/null); "
                    + "echo ===EIR===; grep -aiE '0000184[6e]-0000-1000|BTM_GetEirUuidList' "
                    + "\"$F\" 2>/dev/null | grep -av 'bta_gattc_search_service' | tail -n 40; "
                    + "echo ===ADV===; grep -a 'btm_ble_process_adv_addr' \"$F\" 2>/dev/null "
                    + "| tail -n 40; "
                    + "echo ===ASE===; grep -aE 'ASE state|ASE State' \"$F\" 2>/dev/null "
                    + "| tail -n 6; "
                    + "echo ===CFG===; grep -a 'active configuration name' \"$F\" 2>/dev/null "
                    + "| tail -n 4; "
                    + "echo ===BOND===; grep -a 'pairing_report_bond_state_change' \"$F\" "
                    + "2>/dev/null | tail -n 20";

    static final String CMD_MODULES = "/data/adb/lspd/cli modules ls";
    static final String CMD_BT = "dumpsys bluetooth_manager";
    static final String CMD_AUDIO = "dumpsys audio";

    private WizardProbe() {
    }

    static WizardLogic.Inputs collect(int step, String keyword, String oui,
                                      boolean pairTimedOut) {
        final WizardLogic.Inputs in = new WizardLogic.Inputs();
        in.keyword = (keyword == null || keyword.trim().isEmpty())
                ? WizardLogic.DEFAULT_KEYWORD : keyword.trim();
        in.ouiPrefix = (oui == null || oui.trim().isEmpty())
                ? WizardLogic.DEFAULT_OUI : oui.trim();
        in.pairTimedOut = pairTimedOut;

        final boolean needPre = step == 0 || step == 6;
        final boolean needAudio = step == 5;

        final String[] props = new String[1];
        final String[] mods = new String[1];
        final String[] log = new String[1];
        final String[] bt = new String[1];
        final String[] audio = new String[1];
        final boolean[] root = new boolean[1];

        int tasks = 3 + (needPre ? 2 : 0) + (needAudio ? 1 : 0);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(5, tasks));
        CountDownLatch latch = new CountDownLatch(tasks);

        pool.execute(new Task(latch) {
            void body() {
                root[0] = Shell.rootAvailable();
            }
        });
        pool.execute(new Task(latch) {
            void body() {
                bt[0] = read(CMD_BT, 20000);
            }
        });
        pool.execute(new Task(latch) {
            void body() {
                log[0] = read(CMD_BT_LOG, 20000);
            }
        });
        if (needPre) {
            pool.execute(new Task(latch) {
                void body() {
                    props[0] = read(CMD_PROPS, 12000);
                }
            });
            pool.execute(new Task(latch) {
                void body() {
                    mods[0] = read(CMD_MODULES, 12000);
                }
            });
        }
        if (needAudio) {
            pool.execute(new Task(latch) {
                void body() {
                    audio[0] = read(CMD_AUDIO, 20000);
                }
            });
        }

        try {
            latch.await(25, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }

        in.root = root[0];
        in.btDump = bt[0];
        in.modulesOut = mods[0];
        in.audioDump = audio[0];

        String logText = log[0];
        if (logText != null && logText.contains("NO_LOG")) {
            // 日志目录不可读：明确置 -1，界面提示需要 root，而不是「0 行」
            in.leaEnablerCount = -1;
            in.btLog = null;
        } else {
            in.btLog = logText;
            in.leaEnablerCount = Parsers.intValue(
                    Parsers.find(logText, "(?m)^LEA=(\\d+)\\s*$"), -1);
        }

        String p = props[0] == null ? "" : props[0];
        in.dualProp = Parsers.propValue(Parsers.find(p, "(?m)^DUAL=(.*)$"));
        in.whiteListProp = Parsers.propValue(Parsers.find(p, "(?m)^WL=(.*)$"));
        in.btOn = Parsers.propValue(Parsers.find(p, "(?m)^BTON=(.*)$"));
        in.autoMac = Parsers.propValue(Parsers.find(p, "(?m)^AUTO=(.*)$"));
        in.targetMacs = Parsers.propValue(Parsers.find(p, "(?m)^TARGETS=(.*)$"));
        in.roselinkOut = Parsers.find(p, "(?m)^PKG=(.*)$");
        return in;
    }

    /** 跑一条只读命令，任何异常都吞掉（返回 null 由判定层处理）。 */
    private static String read(String cmd, long timeoutMs) {
        try {
            Shell.Result r = Shell.run(cmd, timeoutMs);
            if (r == null) {
                return null;
            }
            String out = r.out;
            if (out == null || out.trim().isEmpty()) {
                return null;
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private abstract static class Task implements Runnable {
        final CountDownLatch latch;

        Task(CountDownLatch latch) {
            this.latch = latch;
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
