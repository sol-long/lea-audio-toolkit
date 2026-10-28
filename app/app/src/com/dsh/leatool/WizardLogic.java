package com.dsh.leatool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 向导的纯逻辑：7 个步骤的定义、每步判据、结果文案。
 *
 * <p>这里不 import 任何 Android API，所以 tools/selftest 可以直接在 JVM 上跑判据单测。
 * UI 只负责把 {@link Inputs} 喂进来、把 {@link Result} 画出来。
 */
final class WizardLogic {

    static final int STEP_COUNT = 7;

    static final String MODULE_ID = "com.dsh.lea";
    static final String PKG_ROSELINK = "cn.ikaile.ruoshui.client";
    static final String DEFAULT_KEYWORD = "OpenFeel";
    static final String DEFAULT_OUI = Parsers.DEFAULT_OUI_PREFIX;

    static final String P_DUAL = "persist.bluetooth.enable_dual_mode_audio";
    static final String P_WHITE_LIST = "persist.sys.oplus.bt.le_audio_white_list_enable";
    static final String P_AUTO_MAC = "persist.sys.lea.auto_mac";
    static final String P_TARGET_MACS = "persist.sys.lea.target_macs";
    static final String P_FORGET_MAC = "persist.sys.lea.forget_mac";

    /** 第 0 步各项在 checks 里的固定下标（UI 靠它映射修复命令）。 */
    static final int C_ROOT = 0;
    static final int C_MODULE = 1;
    static final int C_INJECT = 2;
    static final int C_DUAL = 3;
    static final int C_WHITE_LIST = 4;
    static final int C_BT_ON = 5;
    static final int C_ROSELINK = 6;

    enum Status {
        /** 还没检测过 */
        NONE,
        /** 正在检测 */
        RUNNING,
        /** 通过 */
        PASS,
        /** 未通过 */
        FAIL,
        /** 用户跳过 */
        SKIP,
        /** 条件已满足、等待写入（第 6 步） */
        READY
    }

    static String badge(Status s) {
        if (s == null) {
            return "⚪ 未开始";
        }
        switch (s) {
            case PASS:
                return "✅ 通过";
            case RUNNING:
                return "⏳ 进行中";
            case READY:
                return "⏳ 待写入";
            case FAIL:
                return "❌ 未通过";
            case SKIP:
                return "⚪ 跳过";
            default:
                return "⚪ 未开始";
        }
    }

    /** 步骤条上的极简符号（7 个并列，放不下完整文字）。 */
    static String glyph(Status s) {        if (s == null) {
            return "○";
        }
        switch (s) {
            case PASS:
                return "✅";
            case RUNNING:
            case READY:
                return "⏳";
            case FAIL:
                return "❌";
            case SKIP:
                return "⚪";
            default:
                return "○";
        }
    }

    /** 持久化用的字符串 → 状态（坏值当 NONE，绝不抛）。 */
    static Status parseStatus(String name) {
        try {
            return Status.valueOf(name);
        } catch (Throwable t) {
            return Status.NONE;
        }
    }

    static final String[] TITLES = {
            "0 · 前置检查（自动）",
            "1 · 确认耳机在 AAC 模式",
            "2 · 配对经典身份",
            "3 · 切到 LC3 模式（人工确认）",
            "4 · 配对 LE 身份（-LEA）",
            "5 · 激活与验证",
            "6 · 收尾（自动写入）"
    };

    static final String[] GUIDES = {
            "先确认手机的「底子」没问题。下面 7 项全绿才能开始；有红的点「一键修复」，修完再点「重新检测」。",
            "看一眼耳机现在是不是走经典蓝牙（AAC）。这一步只看不改，不用动耳机。",
            "打开「设置 → 蓝牙」，让耳机进入配对状态，然后在列表里点名字是「ROSE OpenFeel MK2」"
                    + "的那一条（千万别点带「-LEA」的），弹窗点「配对」。",
            "打开 Roselink → 把「LC3 / LE Audio」开关切到 LC3，弹确认框点「确定」。"
                    + "耳机会自己重启，等约 10 秒，然后回来勾选下面的「我已在 Roselink 切到 LC3」。",
            "打开「设置 → 蓝牙」，点页面上的「刷新」让它重新扫描，然后在「可用设备」里点名字带"
                    + "「-LEA」的那一条；两只耳机通常会出现 1~2 条，两条都要点「配对」。",
            "随便播放一首歌（网易云 / QQ 音乐都行），音量调到能听见，等 3 秒，再点「重新检测」。",
            "把这次配到的 -LEA 地址写进模块配置，以后每次开蓝牙都会自动连。"
                    + "这一步会自动写入，看到绿色就点「完成」。"
    };

    /** 关键风险点 / 兜底提示，卡片底部小字。 */
    static final String[] NOTES = {
            "root 需要自己在 ReSukiSU / KernelSU 里授权，本页不会替你点授权。",
            "如果 Roselink 连不上：两只耳机放回仓、开盖、长按仓键 10 秒复位，默认回 AAC。",
            "配对卡住 30 秒没反应，会自动出现「僵尸配对清理」。",
            "切 LC3 后耳机会重启，约 10 秒。这一步以人工确认为准：下面的自动佐证只作参考，"
                    + "配对后第 4 步才是真正的验证。",
            "只配到 1 条时优先怀疑「只配了单耳」；出现 2 条以上同名 -LEA 时用「僵尸配对清理」删掉多余的。",
            "四项全绿才算成功；有一项红就按提示处理，不要只看「有声音」。",
            "写入的是 persist 属性，重启手机也不会丢；想换设备就重新走一遍第 4~6 步。"
    };

    /** 一次检测需要的原始输入。字段为 null 表示没取到。 */
    static final class Inputs {
        boolean root;
        String modulesOut;
        /** 蓝牙日志里 [LeaEnabler] 的行数；-1 = 没读到日志 */
        int leaEnablerCount = -1;
        String dualProp;
        String whiteListProp;
        String btOn;
        String roselinkOut;
        String btDump;
        String audioDump;
        /** 蓝牙日志的 grep 合集（含 ===ADV=== / ===ASE=== / ===CFG=== / ===BOND=== 分段） */
        String btLog;
        String keyword = DEFAULT_KEYWORD;
        String ouiPrefix = DEFAULT_OUI;
        /** 第 2/4 步等待配对超过 30 秒 */
        boolean pairTimedOut;
        /** 第 3 步：用户勾选「我已在 Roselink 切到 LC3」 */
        boolean lc3Confirmed;
        String autoMac;
        String targetMacs;
    }

    /** 单项检查。 */
    static final class Check {
        final String label;
        final boolean pass;
        /**
         * 辅助判据：不计入失败，UI 显示为黄色「跳过」。用于主判据已经能下结论、
         * 而这条只是锦上添花的场景（例如第 3 步的日志证据）。
         */
        final boolean skipped;
        final String detail;
        /** 失败时的人话建议；null 表示没有额外建议。 */
        final String fix;

        Check(String label, boolean pass, String detail, String fix, boolean skipped) {
            this.label = label;
            this.pass = pass;
            this.skipped = skipped;
            this.detail = detail == null ? "" : detail;
            this.fix = fix;
        }

        boolean failed() {
            return !pass && !skipped;
        }
    }

    /** 一步的判定结果。 */
    static final class Result {
        final int step;
        Status status = Status.NONE;
        String headline = "";
        String advice = "";
        /** 结论下方的灰色小字（第 3 步的「自动佐证」汇总）。 */
        String footnote = "";
        final List<Check> checks = new ArrayList<Check>();
        final List<Parsers.Bonded> bonded = new ArrayList<Parsers.Bonded>();
        final List<Parsers.Bonded> classicDevices = new ArrayList<Parsers.Bonded>();
        final List<Parsers.Bonded> leDevices = new ArrayList<Parsers.Bonded>();
        boolean zombie;
        String zombieReason = "";
        String desiredAutoMac;
        final List<String> desiredTargetMacs = new ArrayList<String>();

        Result(int step) {
            this.step = step;
        }

        void add(String label, boolean pass, String detail, String fix) {
            checks.add(new Check(label, pass, detail, fix, false));
        }

        void add(String label, boolean pass, String detail) {
            checks.add(new Check(label, pass, detail, null, false));
        }

        int failCount() {
            int n = 0;
            for (Check c : checks) {
                if (c.failed()) {
                    n++;
                }
            }
            return n;
        }

        String firstFailingFix() {
            for (Check c : checks) {
                if (c.failed() && c.fix != null && !c.fix.isEmpty()) {
                    return c.fix;
                }
            }
            return "";
        }

        /**
         * 主判据已经能下结论时，把没命中的辅助项降级为「跳过」（黄色），
         * 避免界面上出现红叉误导用户。
         */
        void relaxFailuresToSkipped() {
            for (int i = 0; i < checks.size(); i++) {
                Check c = checks.get(i);
                if (!c.pass && !c.skipped) {
                    checks.set(i, new Check(c.label, false, c.detail, c.fix, true));
                }
            }
        }

        static Result error(int step, String msg) {
            Result r = new Result(step);
            r.status = Status.FAIL;
            r.headline = msg;
            r.advice = "点「重新检测」再试一次；连续失败请把「诊断」页的日志发出来。";
            return r;
        }
    }

    private WizardLogic() {
    }

    // ---------------------------------------------------------------- 判定

    static Result eval(int step, Inputs in) {
        Inputs x = in == null ? new Inputs() : in;
        Result r = new Result(step);
        try {
            switch (step) {
                case 0:
                    evalPreCheck(r, x);
                    break;
                case 1:
                    evalAac(r, x);
                    break;
                case 2:
                    evalClassic(r, x);
                    break;
                case 3:
                    evalLc3(r, x);
                    break;
                case 4:
                    evalLeBond(r, x);
                    break;
                case 5:
                    evalActive(r, x);
                    break;
                case 6:
                    evalFinish(r, x);
                    break;
                default:
                    r.status = Status.FAIL;
                    r.headline = "未知步骤 " + step;
            }
        } catch (Throwable t) {
            return Result.error(step, "判定异常：" + t.getClass().getSimpleName());
        }
        return r;
    }

    private static void evalPreCheck(Result r, Inputs in) {
        String moduleState = Parsers.moduleState(in.modulesOut, MODULE_ID);
        boolean moduleEnabled = moduleState.startsWith("enabled");
        boolean injected = in.leaEnablerCount > 0;
        boolean dualOk = "true".equalsIgnoreCase(trim(in.dualProp));
        boolean wlOk = "false".equalsIgnoreCase(trim(in.whiteListProp));
        boolean btOn = "1".equals(trim(in.btOn));
        boolean roselink = in.roselinkOut != null && in.roselinkOut.contains(PKG_ROSELINK);

        r.add("root 已授权（su -c id 含 uid=0）", in.root,
                in.root ? "已授权" : "su 不可用或被拒绝",
                "去 ReSukiSU / KernelSU 里给「LEA Toolbox」授权 root，然后点「重新检测」。");
        r.add("模块已启用（" + MODULE_ID + "）", moduleEnabled,
                moduleState,
                "点「一键修复」会执行 lspd modules enable，完成后重启蓝牙或重启手机。");
        r.add("模块已注入蓝牙进程（日志含 [LeaEnabler]）", injected,
                in.leaEnablerCount < 0 ? "读不到蓝牙日志（需要 root）"
                        : ("日志里 " + in.leaEnablerCount + " 行 [LeaEnabler]"),
                "点「一键修复」会重启蓝牙；等 8 秒后再点「重新检测」。");
        r.add("双模开关 " + P_DUAL + " = true", dualOk,
                "当前 = " + or(in.dualProp),
                "点「一键修复」写 setprop true，然后重启蓝牙。");
        r.add("白名单已放宽 " + P_WHITE_LIST + " = false", wlOk,
                "当前 = " + or(in.whiteListProp),
                "点「一键修复」写 setprop false，然后重启蓝牙。");
        r.add("蓝牙已开启（settings get global bluetooth_on = 1）", btOn,
                "bluetooth_on = " + or(in.btOn),
                "下拉通知栏打开蓝牙；有 root 时点「一键修复」会直接 svc bluetooth enable。");
        r.add("Roselink 已安装（" + PKG_ROSELINK + "）", roselink,
                roselink ? "已安装" : "未安装",
                "先安装 Roselink（柔水）APK，装完点「重新检测」。");

        r.status = r.failCount() == 0 ? Status.PASS : Status.FAIL;
        if (r.status == Status.PASS) {
            r.headline = "7 项全部通过，可以开始了。";
        } else {
            r.headline = r.failCount() + " 项没通过，先修好再往下走。";
            r.advice = r.firstFailingFix();
        }
    }

    private static void evalAac(Result r, Inputs in) {
        loadBonded(r, in);
        boolean a2dpOn = classicConnected(r, in);
        r.add("找到经典身份 [BR/EDR] 且名字含「" + nz(in.keyword) + "」",
                !r.classicDevices.isEmpty(),
                r.classicDevices.isEmpty() ? "Bonded 里没有 [BR/EDR] 身份"
                        : r.classicDevices.get(0).label());
        r.add("经典链路已连接（A2dpStateMachine = CONNECTED）", a2dpOn,
                a2dpOn ? "CONNECTED" : "没有 CONNECTED 的 A2dpStateMachine");

        if (r.classicDevices.isEmpty() && !r.leDevices.isEmpty()) {
            r.status = Status.FAIL;
            r.headline = "耳机在 LC3 模式（只看到 " + r.leDevices.size() + " 条 -LEA 身份）";
            r.advice = "打开 Roselink → 切回 AAC（耳机会重启，约 10 秒）。"
                    + "如果 Roselink 连不上：两只耳机放回仓、开盖、长按仓键 10 秒复位，默认回 AAC。";
        } else if (r.classicDevices.isEmpty()) {
            r.status = Status.FAIL;
            r.headline = "还没看到任何已配对身份";
            r.advice = "把耳机放回仓再开盖（进入回连/配对状态），然后按第 2 步在蓝牙设置里配对"
                    + "「ROSE OpenFeel MK2」（不带 -LEA）。";
        } else if (!a2dpOn) {
            r.status = Status.FAIL;
            r.headline = "经典身份已配对，但当前没连上";
            r.advice = "在蓝牙设置里点一下「ROSE OpenFeel MK2」（不带 -LEA），等 3 秒再回本页检测。";
        } else {
            r.status = Status.PASS;
            r.headline = "耳机在 AAC 模式：" + r.classicDevices.get(0).name;
        }
    }

    private static void evalClassic(Result r, Inputs in) {
        loadBonded(r, in);
        boolean found = !r.classicDevices.isEmpty();
        r.add("Bonded 里出现 [BR/EDR] 且名字含「" + nz(in.keyword) + "」", found,
                found ? r.classicDevices.get(0).label() : "还没有 [BR/EDR] 身份");
        boolean tooManyLe = r.leDevices.size() > 2;
        r.zombie = !found && (in.pairTimedOut || Parsers.bondAuthFailed(in.btLog) || tooManyLe);
        if (r.zombie) {
            r.zombieReason = zombieReason(in, tooManyLe);
        }
        if (found) {
            r.status = Status.PASS;
            r.headline = "经典身份已就位：" + r.classicDevices.get(0).name
                    + "（" + r.classicDevices.get(0).mac + "）";
        } else {
            r.status = Status.FAIL;
            r.headline = "还没配对经典身份";
            r.advice = "打开「设置 → 蓝牙」→ 扫描 → 点「ROSE OpenFeel MK2」（不带 -LEA）→ 弹窗点「配对」。"
                    + "配对时耳机要保持开盖/在仓外。";
        }
    }

    /**
     * 第 3 步改成「人工确认 + 自动佐证」：
     * <ul>
     *   <li>人工确认（复选框）才是通过条件——配对前「切 LC3」没法可靠自动检测
     *       （dumpsys 没有未配对设备名、日志地址被打码、EIR 日志不稳定）；</li>
     *   <li>三条自动佐证只展示、不阻塞、不报红；</li>
     *   <li>真正的硬验证是第 4 步（Bonded 里必须有 -LEA）。</li>
     * </ul>
     */
    private static void evalLc3(Result r, Inputs in) {
        loadBonded(r, in);
        String oui = nz(in.ouiPrefix);
        boolean eirHit = findLeAudioUuid(in.btLog) != null;
        AdvHit hit = findAdvLine(in.btLog, r.leDevices, oui);
        boolean adv = hit != null;
        boolean paired = !r.leDevices.isEmpty();

        r.add("日志看到 LE Audio 广播（btif_dm_search_devices_evt · 0x184E/0x1846）", eirHit,
                eirHit ? "EIR 含 LE Audio UUID（0x184E/0x1846）" : "未检测到");
        r.add("看到 LE 广播活动（btm_ble_process_adv_addr）", adv,
                adv ? ("地址 " + (hit.bondedMac == null ? hit.bda : hit.bondedMac + " · bda=" + hit.bda))
                        : "未检测到");
        r.add("已配对 -LEA 身份（真正可靠的是第 4 步）", paired,
                paired ? (r.leDevices.size() + " 条") : "还没有 -LEA 配对");

        // 佐证永远不报红：没命中的显示为「跳过 / 未检测到」
        r.relaxFailuresToSkipped();

        if (in.lc3Confirmed) {
            r.status = Status.PASS;
            r.headline = "已人工确认：切到 LC3（自动佐证看下面，不作硬性要求）";
        } else {
            r.status = Status.FAIL;
            r.headline = "等待人工确认";
            r.advice = "打开 Roselink → 切 LC3（耳机会重启，约 10 秒）；回来后勾选上面的"
                    + "「我已在 Roselink 切到 LC3，耳机已重启」，就能点「下一步」。"
                    + "确实还没切可以先「跳过」，配对后第 4 步会做真正的验证。";
        }

        int evidence = (eirHit ? 1 : 0) + (adv ? 1 : 0) + (paired ? 1 : 0);
        if (evidence == 0) {
            r.footnote = "未检测到自动佐证（不影响继续，配对后第 4 步会验证）";
        } else {
            r.footnote = "自动佐证：日志 " + (eirHit ? "✓" : "—")
                    + " · 广播 " + (adv ? "✓" : "—")
                    + " · 已配对 " + (paired ? "✓" : "—");
        }
    }

    private static void evalLeBond(Result r, Inputs in) {
        loadBonded(r, in);
        int n = r.leDevices.size();
        r.add("已配对 LE 身份（-LEA）数量 ≥ 1", n >= 1, "当前 " + n + " 条");
        r.add("双耳齐全（期望 2 条）", n >= 2, "当前 " + n + " 条");

        boolean tooManyLe = n > 2;
        r.zombie = tooManyLe || (n == 0 && (in.pairTimedOut || Parsers.bondAuthFailed(in.btLog)));
        if (r.zombie) {
            r.zombieReason = zombieReason(in, tooManyLe);
        }
        if (n == 0) {
            r.status = Status.FAIL;
            r.headline = "还没有配对 -LEA 身份";
            r.advice = "先回第 3 步确认已切到 LC3；然后在蓝牙设置页点「刷新」，"
                    + "在「可用设备」里点带「-LEA」的条目并点「配对」。";
        } else if (n == 1) {
            r.status = Status.PASS;
            r.headline = "配到 1 条 -LEA（可能只配了单耳）";
            r.advice = "如果只有一只耳机有声音：在蓝牙设置点「刷新」，再点另一条「...-LEA」配对。";
        } else {
            r.status = Status.PASS;
            r.headline = "双耳已配对（" + n + " 条 -LEA）";
        }
    }

    private static void evalActive(Result r, Inputs in) {
        loadBonded(r, in);
        String group = Parsers.activeGroupId(in.btDump);
        boolean groupOk = group != null && !"-1".equals(group.trim());
        String music = Parsers.musicDevices(in.audioDump);
        boolean ble = music != null
                && music.toLowerCase(Locale.US).contains("ble_headset");
        boolean streaming = false;
        boolean lc3 = false;
        if (in.btLog != null) {
            for (String line : in.btLog.split("\n")) {
                String low = line.toLowerCase(Locale.US);
                if (low.contains("ase state") && low.contains("streaming")) {
                    streaming = true;
                }
                if (low.contains("active configuration name") && low.contains("lc3")) {
                    lc3 = true;
                }
            }
        }
        boolean classicOk = classicConnected(r, in);

        r.add("LE Audio 组已激活（currentlyActiveGroupId ≠ -1）", groupOk,
                "currentlyActiveGroupId = " + or(group),
                "回第 4 步确认 -LEA 已配对；然后在蓝牙设置里点一下该耳机，或去「操作」页用"
                        + "「触发 LE 连接」。");
        r.add("媒体音频走 ble_headset（dumpsys audio）", ble,
                music == null ? "没取到 AUDIO_STREAM_MUSIC 的 Devices 行" : music,
                "先播放音乐并确认音量；仍是 bt_a2dp 说明耳机还在 AAC，回第 3 步重新切 LC3。");
        r.add("蓝牙日志 ASE = Streaming 且配置名含 Lc3", streaming && lc3,
                (streaming ? "Streaming" : "没看到 Streaming")
                        + " / " + (lc3 ? "配置含 Lc3" : "配置名不含 Lc3"),
                "播放音乐后等 3 秒再检测；日志里一直没 Streaming，说明 LE Audio 没真正建立。");
        r.add("经典链路仍在线（A2dpStateMachine = CONNECTED）", classicOk,
                classicOk ? "CONNECTED" : "经典链路不在",
                "回第 2 步重新连「ROSE OpenFeel MK2」（不带 -LEA）；双模要两条都在。");

        if (r.failCount() == 0) {
            r.status = Status.PASS;
            r.headline = "LC3 已生效：媒体走 ble_headset，ASE 在 Streaming。";
        } else {
            r.status = Status.FAIL;
            r.headline = r.failCount() + " 项没满足";
            StringBuilder sb = new StringBuilder();
            for (Check c : r.checks) {
                if (c.failed() && c.fix != null) {
                    sb.append("· ").append(c.fix).append('\n');
                }
            }
            r.advice = sb.toString().trim();
        }
    }

    private static void evalFinish(Result r, Inputs in) {
        loadBonded(r, in);
        int n = r.leDevices.size();
        if (n > 0) {
            r.desiredAutoMac = r.leDevices.get(0).mac;
            for (Parsers.Bonded b : r.leDevices) {
                r.desiredTargetMacs.add(b.mac);
            }
        }
        boolean autoOk = n > 0 && sameText(in.autoMac, r.desiredAutoMac);
        boolean targetsOk = n > 0 && sameMacSet(in.targetMacs, r.desiredTargetMacs);

        r.add("找到 -LEA 设备", n > 0, n == 0 ? "没有可写入的 -LEA 身份" : (n + " 条"));
        r.add(P_AUTO_MAC + " 已写入", autoOk, "当前 = " + or(in.autoMac));
        r.add(P_TARGET_MACS + " 已写入全部 " + Math.max(n, 1) + " 个", targetsOk,
                "当前 = " + or(in.targetMacs));

        if (n == 0) {
            r.status = Status.FAIL;
            r.headline = "没有 -LEA 身份，无法收尾";
            r.advice = "回第 4 步先配对 -LEA 身份。";
        } else if (autoOk && targetsOk) {
            r.status = Status.PASS;
            r.headline = "已写入：" + r.desiredAutoMac + "（以后开蓝牙自动连）";
        } else {
            r.status = Status.READY;
            r.headline = "准备写入 " + n + " 条 -LEA 地址…";
        }
    }

    // ------------------------------------------------------------ 公共小工具

    private static void loadBonded(Result r, Inputs in) {
        r.bonded.addAll(Parsers.bondedDevices(in.btDump));
        Parsers.markConnected(r.bonded, Parsers.connectedMacs(in.btDump));
        String kw = in.keyword == null ? "" : in.keyword;
        for (Parsers.Bonded b : r.bonded) {
            if (!b.nameContains(kw)) {
                continue;
            }
            if (b.isClassic()) {
                r.classicDevices.add(b);
            }
            if (b.isLe()) {
                r.leDevices.add(b);
            }
        }
    }

    private static boolean classicConnected(Result r, Inputs in) {
        List<String> conn = Parsers.a2dpConnectedMacs(in.btDump);
        for (Parsers.Bonded b : r.classicDevices) {
            for (String c : conn) {
                if (Parsers.sameDevice(b.mac, c)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String zombieReason(Inputs in, boolean tooMany) {
        List<String> why = new ArrayList<String>();
        if (in.pairTimedOut) {
            why.add("等待配对超过 30 秒");
        }
        if (Parsers.bondAuthFailed(in.btLog)) {
            why.add("日志里出现配对认证失败（status: 9）");
        }
        if (tooMany) {
            why.add("Bonded 里有多条同名 -LEA，疑似僵尸配对");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < why.size(); i++) {
            if (i > 0) {
                sb.append("；");
            }
            sb.append(why.get(i));
        }
        return sb.toString();
    }

    /**
     * 日志里的 LE Audio 服务 UUID：ASCS(0x184E) / CSIS(0x1846)。
     *
     * <p><b>必须只认搜索事件</b>：
     * <pre>
     *   [btif_dm.cc:2102] btif_dm_search_devices_evt: 0000184e-0000-1000-8000-00805f9b34fb
     *   [btm_inq.cc:2453] BTM_GetEirUuidList: 0x184E
     * </pre>
     * 而 {@code bta_gattc_search_service: found service 0000184e-...} 是连上之后的 GATT
     * 服务发现，<b>AAC 模式下也会打</b>（实测一份日志 38 处 184e 绝大多数是它）。
     * 用后者当证据会让第 3 步永远通过，所以这里明确排除。
     */
    private static final Pattern LEA_UUID_128 =
            Pattern.compile("(?i)0000184[6e]-0000-1000-8000-00805f9b34fb");
    private static final Pattern LEA_UUID_EIR =
            Pattern.compile("(?i)BTM_GetEirUuidList:\\s*0x184[6e]\\b");

    /** 命中返回整行（给 UI 留证据），没命中返回 null。 */
    static String findLeAudioUuid(String log) {
        if (log == null || log.isEmpty()) {
            return null;
        }
        for (String line : log.split("\n")) {
            // GATT 服务发现不是「耳机在 LC3 模式」的证据，直接排除
            if (line.contains("bta_gattc_search_service")) {
                continue;
            }
            boolean search = line.contains("btif_dm_search_devices_evt")
                    || line.contains("BTM_GetEirUuidList");
            if (!search) {
                continue;
            }
            if (LEA_UUID_128.matcher(line).find() || LEA_UUID_EIR.matcher(line).find()) {
                return line.trim();
            }
        }
        return null;
    }

    private static final Pattern ADV_BDA =
            Pattern.compile("(?i)BDA=([0-9a-fx:]{5,17})");

    /** 一条日志证据：原始行、日志里的地址（可能被打码/截断）、对上的已配对设备。 */
    static final class AdvHit {
        final String line;
        final String bda;
        /** 对上的已配对 -LEA 地址；null = 只靠 OUI 前缀认出来的。 */
        final String bondedMac;

        AdvHit(String line, String bda, String bondedMac) {
            this.line = line == null ? "" : line;
            this.bda = bda == null ? "?" : bda;
            this.bondedMac = bondedMac;
        }
    }

    /**
     * 在蓝牙日志里找「耳机在广播」的证据。
     *
     * <p>ColorOS 会把 MAC 前 4 段打码成 {@code xx:xx:xx:xx:74:04}，有的栈只打
     * {@code bda=74:04}，所以这里不能拿 OUI 去 startsWith，而是：
     * <ol>
     *   <li>地址完整（无 X）→ 与已配对 -LEA 相等，或命中 OUI 前缀</li>
     *   <li>地址被打码 / 只有尾两段 → 与已配对 -LEA 的**尾两段**比对</li>
     * </ol>
     * 返回命中的整行（给 UI 显示），没命中返回 null。
     */
    static AdvHit findAdvLine(String log, List<Parsers.Bonded> bonded, String oui) {
        if (log == null || log.isEmpty()) {
            return null;
        }
        String ouiPlain = oui == null ? "" : oui.toUpperCase(Locale.US).replace(":", "").trim();
        for (String line : log.split("\n")) {
            if (!line.contains("btm_ble_process_adv_addr")) {
                continue;
            }
            String up = line.toUpperCase(Locale.US);
            String bda = extractBda(line);
            if (bda != null) {
                if (bonded != null) {
                    for (Parsers.Bonded b : bonded) {
                        if (advMatches(bda, b.mac, ouiPlain)) {
                            return new AdvHit(line.trim(), bda, b.mac);
                        }
                    }
                }
                String plain = bda.replace(":", "");
                if (!plain.contains("X") && plain.length() >= 8
                        && !ouiPlain.isEmpty() && plain.startsWith(ouiPlain)) {
                    return new AdvHit(line.trim(), bda, null);
                }
            }
            // 兜底：整行任意位置出现 OUI（老日志格式 / 地址没写在 bda= 后面）
            if (!ouiPlain.isEmpty()
                    && up.replace(":", "").replace(" ", "").contains(ouiPlain)) {
                return new AdvHit(line.trim(), bda, null);
            }
        }
        return null;
    }

    /** 兼容旧调用（不传已配对设备，只能靠完整地址 / OUI）。 */
    static String findAdvLine(String log, String oui) {
        AdvHit hit = findAdvLine(log, null, oui);
        return hit == null ? null : hit.line;
    }

    private static String extractBda(String line) {
        Matcher m = ADV_BDA.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    /** 日志地址 vs 已配对 MAC：完整地址比相等/OUI，打码或短地址比尾两段。 */
    private static boolean advMatches(String bda, String mac, String ouiPlain) {
        if (bda == null || mac == null) {
            return false;
        }
        String b = bda.toUpperCase(Locale.US).replace(":", "");
        String m = mac.toUpperCase(Locale.US).replace(":", "");
        if (b.isEmpty() || m.isEmpty()) {
            return false;
        }
        if (b.contains("X")) {
            return b.length() >= 4 && m.endsWith(b.substring(b.length() - 4));
        }
        if (b.length() >= 12) {
            return b.equals(m) || (!ouiPlain.isEmpty() && b.startsWith(ouiPlain));
        }
        // 短地址（bda=74:04）
        return b.length() >= 4 && m.endsWith(b);
    }

    /** 广播返回的 msg 是否算失败（failed / ERR / null 开头，或逐项 =ERR / =false）。 */
    static boolean isFailMsg(String msg) {
        if (msg == null) {
            return true;
        }
        String s = msg.trim();
        if (s.isEmpty()) {
            return false;
        }
        String low = s.toLowerCase(Locale.US);
        return low.startsWith("failed")
                || low.startsWith("err")
                || low.equals("null")
                || low.startsWith("null ")
                || low.startsWith("error")
                || low.startsWith("unknown cmd")
                || low.startsWith("adapter")
                || low.contains("=err(")
                || low.contains("=false");
    }

    static boolean sameText(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }

    /** target_macs 是否已经包含 desired 里的全部 MAC（逗号分隔，顺序无关）。 */
    static boolean sameMacSet(String current, List<String> desired) {
        if (current == null || desired == null || desired.isEmpty()) {
            return false;
        }
        String norm = Parsers.normalizeMacList(current);
        if (norm == null) {
            return false;
        }
        List<String> have = new ArrayList<String>();
        for (String s : norm.split(",")) {
            have.add(s.trim().toUpperCase(Locale.US));
        }
        for (String d : desired) {
            if (d == null || !have.contains(d.trim().toUpperCase(Locale.US))) {
                return false;
            }
        }
        return true;
    }

    /** 期望写入的 target_macs 字符串。 */
    static String joinMacs(List<String> macs) {
        StringBuilder sb = new StringBuilder();
        for (String m : macs) {
            if (m == null || m.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(m.trim().toUpperCase(Locale.US));
        }
        return sb.toString();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String nz(String s) {
        return s == null || s.trim().isEmpty() ? "—" : s.trim();
    }

    private static String or(String s) {
        return s == null || s.trim().isEmpty() ? "—" : s.trim();
    }
}
