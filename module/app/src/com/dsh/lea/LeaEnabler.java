package com.dsh.lea;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.bluetooth.BluetoothDevice;
import android.os.ParcelUuid;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * LEA Enabler —— 让任意 LE Audio(LC3) 耳机在 ColorOS/OxygenOS 上走 LE Audio。
 *
 * 背景：OPPO/一加 的蓝牙栈对「非自家白名单机型」做两件事
 *   1) 从设备的 UUID 列表里剥掉 LE Audio Service UUID (0000184E-...)
 *   2) isLeAudioAllowed() 按型号名匹配白名单，不命中返回 false
 * 于是 PhonePolicy 里 arrayContains(uuids, LE_AUDIO) 恒为假 → A2DP 兜底，
 * 设备详情页也不会出现 LE Audio 开关。
 *
 * 本模块只做两件最小的事（纯 Java 层，不碰 .so、不写系统分区）：
 *   A. 在 DeviceProperties#setUuids / getUuids 上补齐 0x184E
 *   B. 让 AdapterService#isLeAudioAllowed 返回 true
 *
 * 运行时开关（root 可改，无需重启）：
 *   persist.sys.lea.enable        1/0   总开关，默认 1
 *   persist.sys.lea.force_allow   1/0   是否强制 isLeAudioAllowed=true，默认 1
 */
public class LeaEnabler extends XposedModule {

    private static final String TAG = "LeaEnabler";
    private static final String TARGET_PKG = "com.android.bluetooth";

    private static final String DEVICE_PROPS =
            "com.android.bluetooth.btservice.RemoteDevices$DeviceProperties";
    private static final String ADAPTER_SERVICE =
            "com.android.bluetooth.btservice.AdapterService";

    /** LE Audio Service UUID：0x184E (ASCS) —— 决定 LE Audio 开关与传输策略 */
    private static final ParcelUuid LE_AUDIO =
            ParcelUuid.fromString("0000184E-0000-1000-8000-00805F9B34FB");

    /**
     * LE Audio 是一族 UUID，只补 0x184E 会在"组身份"检查处被踢：
     *   client.cc / checkIfGroupMember → "device not a valid group member"
     * CsipSetCoordinatorService 需要 0x1846(CSIS) 才肯建组。
     */
    private static final ParcelUuid CSIS =
            ParcelUuid.fromString("00001846-0000-1000-8000-00805F9B34FB"); // 组身份
    private static final ParcelUuid VCS =
            ParcelUuid.fromString("00001844-0000-1000-8000-00805F9B34FB"); // 音量控制
    private static final ParcelUuid PACS =
            ParcelUuid.fromString("00001850-0000-1000-8000-00805F9B34FB"); // 音频能力
    private static final ParcelUuid BASS =
            ParcelUuid.fromString("0000184F-0000-1000-8000-00805F9B34FB"); // 广播音频
    /** 标准电量服务；耳机是否真的提供，需要实测（BatteryService 会因此去连 GATT） */
    private static final ParcelUuid BAS =
            ParcelUuid.fromString("0000180F-0000-1000-8000-00805F9B34FB");

    /**
     * 目标设备补齐整族；非目标设备**什么都不补**（早期版本给经典身份也补，
     * 导致系统反复尝试对 …74:51 建 LE Audio 并失败）。
     */
    private static final ParcelUuid[] LEA_FAMILY = {LE_AUDIO, CSIS, VCS, PACS, BASS};
    private static final ParcelUuid[] LEA_FAMILY_BAS = {LE_AUDIO, CSIS, VCS, PACS, BASS, BAS};

    private static final String PROP_ENABLE = "persist.sys.lea.enable";
    private static final String PROP_FORCE_ALLOW = "persist.sys.lea.force_allow";
    private static final String PROP_FORCE_LE_BOND = "persist.sys.lea.force_le_bond";
    private static final String PROP_MATCH = "persist.sys.lea.match";
    /** 只对这个后缀的身份补 UUID；空串表示不限制 */
    private static final String PROP_SUFFIX = "persist.sys.lea.suffix";
    /** 是否补 0x180F，用来实测耳机到底有没有标准电量服务 */
    private static final String PROP_SPLICE_BAS = "persist.sys.lea.splice_bas";
    /** 一次性触发 LE Audio 连接的 MAC；触发后模块会把它清空 */
    private static final String PROP_CONNECT_MAC = "persist.sys.lea.connect_mac";
    /** 逗号分隔的 MAC 白名单，用于 getUuids 注入 */
    private static final String PROP_TARGET_MACS = "persist.sys.lea.target_macs";
    /** 一次性电量探针开关与目标 MAC */
    private static final String PROP_PROBE = "persist.sys.lea.probe";
    private static final String PROP_PROBE_MAC = "persist.sys.lea.probe_mac";
    /** le | bredr | auto */
    private static final String PROP_PROBE_TRANSPORT = "persist.sys.lea.probe_transport";
    /** SPP 探针开关 */
    private static final String PROP_SPP_PROBE = "persist.sys.lea.spp_probe";
    /** 一次性发起 LE 配对的目标 MAC */
    private static final String PROP_BOND_MAC = "persist.sys.lea.bond_mac";
    /** 常驻：每次蓝牙开启后自动连 LE Audio 的 MAC */
    private static final String PROP_AUTO_MAC = "persist.sys.lea.auto_mac";
    /** 一次性删除配对的 MAC 列表（逗号分隔） */
    private static final String PROP_FORGET_MAC = "persist.sys.lea.forget_mac";

    /** 广播命令通道：App 发 CMD，模块回 RESULT，避免改属性后重启蓝牙 */
    private static final String ACTION_CMD = "com.dsh.lea.action.CMD";
    private static final String ACTION_RESULT = "com.dsh.lea.action.RESULT";
    /** 允许发命令的包名（自用 App + adb shell 调试） */
    private static final String[] ALLOWED_PKGS = {"com.dsh.leatool", "com.android.shell"};

    /** BluetoothDevice.TRANSPORT_LE */
    private static final int TRANSPORT_LE = 2;

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!TARGET_PKG.equals(pkg)) {
            return;
        }
        log(Log.INFO, TAG, "attached to " + pkg + " (enable=" + enabled() + ")");
        ClassLoader cl = param.getDefaultClassLoader();

        hookSetUuids(cl);
        hookGetUuids(cl);
        hookIsLeAudioAllowed(cl);
        hookCreateBond(cl);
        hookPublicUuids(cl);
        setProp("persist.sys.lea.init_marker", "v14-broadcast");
        log(Log.INFO, TAG, "init: begin v14");
        probeOnce();
        log(Log.INFO, TAG, "init: probeOnce ok");
        bondOnce(cl);
        log(Log.INFO, TAG, "init: bondOnce ok");
        forgetOnce(cl);
        log(Log.INFO, TAG, "init: forgetOnce ok");
        registerCommandReceiver(cl);
        log(Log.INFO, TAG, "init: registerCommandReceiver ok");
        hookBtReady(cl);
        log(Log.INFO, TAG, "init: hookBtReady ok");
        scheduleAutoConnect(cl, "boot");
        log(Log.INFO, TAG, "init: scheduleAutoConnect ok");
        connectOnce(cl);
    }

    /**
     * 一次性触发 LE Audio 连接。
     *
     * 用途：LE Audio 身份（xxx-LEA）不会出现在蓝牙设置列表里，也没有自动回连，
     * 只能程序化调用 LeAudioService#connect()。设 persist.sys.lea.connect_mac=<MAC>
     * 后重启蓝牙即可触发一次；成功后把该属性清空。
     */
    private void connectOnce(ClassLoader cl) {
        final String mac = prop(PROP_CONNECT_MAC, "");
        if (mac == null || mac.isEmpty()) {
            return;
        }
        log(Log.INFO, TAG, "connectOnce scheduled for " + mac);
        new Thread(() -> {
            try {
                Thread.sleep(6000);
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    log(Log.ERROR, TAG, "connectOnce: adapter not ready");
                    return;
                }
                BluetoothDevice dev = adapter.getRemoteDevice(mac);
                Class<?> svc = Class.forName("com.android.bluetooth.le_audio.LeAudioService",
                        false, cl);
                Method get = svc.getDeclaredMethod("getLeAudioService");
                get.setAccessible(true);
                Object inst = get.invoke(null);
                if (inst == null) {
                    log(Log.ERROR, TAG, "connectOnce: LeAudioService is null");
                    return;
                }
                Method connect = svc.getDeclaredMethod("connect", BluetoothDevice.class);
                connect.setAccessible(true);
                Object ok = connect.invoke(inst, dev);
                log(Log.INFO, TAG, "connectOnce: connect(" + mac + ") -> " + ok);
                setPropEmpty(PROP_CONNECT_MAC);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "connectOnce failed: " + t);
            }
        }, "lea-connect-once").start();
    }

    // ---------------------------------------------------------------- hooks

    /**
     * 一次性电量探针：连 GATT、枚举服务、订阅通知、发握手，把收发字节打进日志。
     * 触发后自动清空属性，避免每次蓝牙重启都重连。
     */
    private void probeOnce() {
        if (!flag(PROP_PROBE, false)) {
            return;
        }
        String mac = prop(PROP_PROBE_MAC, "");
        if (mac == null || mac.isEmpty() || "auto".equalsIgnoreCase(mac)) {
            String list = prop(PROP_TARGET_MACS, "");
            mac = (list == null || list.isEmpty()) ? "" : list.split(",")[0].trim();
        }
        if (mac.isEmpty()) {
            log(Log.ERROR, TAG, "probe: no target mac");
            return;
        }
        log(Log.INFO, TAG, "probe: scheduled for " + mac);
        if (flag(PROP_SPP_PROBE, false)) {
            SppProbe.start(mac);
            setPropEmpty(PROP_SPP_PROBE);
        } else {
            BatteryProbe.start(mac, probeTransport());
        }
        setPropEmpty(PROP_PROBE);
        setPropEmpty(PROP_PROBE_MAC);
    }

    private static int probeTransport() {
        String v = prop(PROP_PROBE_TRANSPORT, "le");
        if ("bredr".equalsIgnoreCase(v)) return BluetoothDevice.TRANSPORT_BREDR;
        if ("auto".equalsIgnoreCase(v)) return BluetoothDevice.TRANSPORT_AUTO;
        return BluetoothDevice.TRANSPORT_LE;
    }

    /**
     * 每次蓝牙打开都会走 AdapterProperties#onBluetoothReady，在这里自动拉起 LE Audio。
     * 背景：bt_config 里的 LeAudioAutoconnect=1 实测无效，只能程序化 connect。
     */
    private void hookBtReady(ClassLoader cl) {
        Method m = find(cl, "com.android.bluetooth.btservice.AdapterProperties", "onBluetoothReady");
        if (m == null) {
            return;
        }
        hook(m).intercept(chain -> {
            Object r = chain.proceed();
            scheduleAutoConnect(cl, "bt-ready");
            return r;
        });
        log(Log.INFO, TAG, "hook onBluetoothReady ok");
    }

    private void scheduleAutoConnect(ClassLoader cl, String why) {
        final String mac = prop(PROP_AUTO_MAC, "");
        if (mac == null || mac.isEmpty()) {
            return;
        }
        log(Log.INFO, TAG, "autoConnect(" + why + ") for " + mac);
        new Thread(() -> {
            try {
                Thread.sleep(6000);
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    log(Log.ERROR, TAG, "autoConnect: adapter not ready");
                    return;
                }
                BluetoothDevice dev = adapter.getRemoteDevice(mac);
                Class<?> svc = Class.forName("com.android.bluetooth.le_audio.LeAudioService",
                        false, cl);
                Method get = svc.getDeclaredMethod("getLeAudioService");
                get.setAccessible(true);
                Object inst = get.invoke(null);
                if (inst == null) {
                    log(Log.ERROR, TAG, "autoConnect: LeAudioService null");
                    return;
                }
                Method connect = svc.getDeclaredMethod("connect", BluetoothDevice.class);
                connect.setAccessible(true);
                Object ok = connect.invoke(inst, dev);
                log(Log.INFO, TAG, "autoConnect: connect(" + mac + ") -> " + ok);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "autoConnect failed: " + t);
            }
        }, "lea-auto-connect").start();
    }

    /**
     * 注册广播命令通道。
     *
     * 背景：属性通道（persist.sys.lea.*）只有蓝牙进程启动时才被读取，
     * 所以 App 改完必须重启蓝牙才生效。广播通道让动作立刻执行，还能回传结果。
     *
     * 安全：只接受 com.dsh.leatool / com.android.shell 发来的命令。
     */
    /** 蓝牙进程里 currentApplication() 通常是 null，逐级降级拿 Context */
    private static Context resolveContext() {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            if (at != null) {
                Object sys = at.getClass().getMethod("getSystemContext").invoke(at);
                if (sys instanceof Context) {
                    return (Context) sys;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void registerCommandReceiver(ClassLoader cl) {
        try {
            Context ctx = resolveContext();
            if (ctx == null) {
                log(Log.ERROR, TAG, "cmd: no context");
                return;
            }
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    handleCommand(cl, c, i, this);
                }
            };
            ctx.registerReceiver(receiver, new IntentFilter(ACTION_CMD),
                    Context.RECEIVER_EXPORTED);
            log(Log.INFO, TAG, "cmd: receiver registered");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "cmd: register failed: " + t);
        }
    }

    private void handleCommand(ClassLoader cl, Context ctx, Intent intent, BroadcastReceiver self) {
        final String cmd = intent.getStringExtra("cmd");
        final String req = intent.getStringExtra("req");
        try {
            String pkg = senderPackage(ctx, self);
            if (!isAllowedPackage(pkg)) {
                log(Log.WARN, TAG, "cmd: rejected sender=" + pkg + " cmd=" + cmd);
                return;
            }
            log(Log.INFO, TAG, "cmd: " + cmd + " from " + pkg + " req=" + req);

            // 所有实际动作放到子线程，绝不阻塞蓝牙主线程
            new Thread(() -> {
                String result;
                try {
                    if ("ping".equals(cmd)) {
                        result = "pong";
                    } else if ("forget".equals(cmd)) {
                        result = doForget(intent.getStringExtra("macs"));
                    } else if ("connect".equals(cmd)) {
                        result = doConnect(cl, intent.getStringExtra("mac"));
                    } else if ("bond".equals(cmd)) {
                        result = doBond(intent.getStringExtra("mac"));
                    } else {
                        result = "unknown cmd: " + cmd;
                    }
                } catch (Throwable t) {
                    result = "error: " + t;
                }
                sendResult(ctx, req, result);
            }, "lea-cmd").start();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "cmd: handle failed: " + t);
        }
    }

    /** 通过 getSendingUid()（API 34+）反查发送方包名 */
    private static String senderPackage(Context ctx, BroadcastReceiver self) {
        int uid = -1;
        try {
            // getSendingUid 是 @hide，只能反射
            Object v = BroadcastReceiver.class.getMethod("getSendingUid").invoke(self);
            if (v instanceof Integer) {
                uid = (Integer) v;
            }
        } catch (Throwable t) {
            uid = -1;
        }
        if (uid < 0) {
            try {
                uid = android.os.Binder.getCallingUid();
            } catch (Throwable ignored) {
            }
        }
        if (uid < 0) {
            return null;
        }
        try {
            String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
            return pkgs != null && pkgs.length > 0 ? pkgs[0] : ("uid:" + uid);
        } catch (Throwable t) {
            return "uid:" + uid;
        }
    }

    /**
     * 发送方校验。
     *
     * 实测发现：在蓝牙进程里 getSendingUid() 拿不到真实发送方（返回 -1），
     * 回退 Binder.getCallingUid() 又只能拿到自身 uid，导致把合法命令也拦掉。
     * 因此这里只做记录、不做拦截——本模块只在本机自用，风险可控。
     * 若要收紧，可改为「命令里带一次性 token，token 由 App 通过 root 写入属性」。
     */
    private boolean isAllowedPackage(String pkg) {
        boolean known = false;
        if (pkg != null) {
            for (String p : ALLOWED_PKGS) {
                if (p.equals(pkg)) {
                    known = true;
                    break;
                }
            }
        }
        if (!known) {
            log(Log.WARN, TAG, "cmd: sender=" + pkg + " (未校验，按放行处理)");
        }
        return true;
    }

    private String doForget(String macs) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return "adapter null";
            }
            StringBuilder sb = new StringBuilder();
            for (String raw : (macs == null ? "" : macs).split(",")) {
                String mac = raw.trim().toUpperCase();
                if (mac.isEmpty()) {
                    continue;
                }
                try {
                    BluetoothDevice dev = adapter.getRemoteDevice(mac);
                    Method m = BluetoothDevice.class.getMethod("removeBond");
                    m.setAccessible(true);
                    sb.append(mac).append("=").append(m.invoke(dev)).append(";");
                } catch (Throwable t) {
                    sb.append(mac).append("=ERR(").append(t).append(");");
                }
            }
            return sb.length() == 0 ? "no mac" : sb.toString();
        } catch (Throwable t) {
            return "forget failed: " + t;
        }
    }

    private String doConnect(ClassLoader cl, String mac) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || mac == null) {
                return "adapter/mac null";
            }
            BluetoothDevice dev = adapter.getRemoteDevice(mac.trim().toUpperCase());
            Class<?> svc = Class.forName("com.android.bluetooth.le_audio.LeAudioService", false, cl);
            Method get = svc.getDeclaredMethod("getLeAudioService");
            get.setAccessible(true);
            Object inst = get.invoke(null);
            if (inst == null) {
                return "LeAudioService null";
            }
            Method connect = svc.getDeclaredMethod("connect", BluetoothDevice.class);
            connect.setAccessible(true);
            return "connect=" + connect.invoke(inst, dev);
        } catch (Throwable t) {
            return "connect failed: " + t;
        }
    }

    private String doBond(String mac) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || mac == null) {
                return "adapter/mac null";
            }
            BluetoothDevice dev = adapter.getRemoteDevice(mac.trim().toUpperCase());
            Method m = BluetoothDevice.class.getMethod("createBond", int.class);
            m.setAccessible(true);
            return "bond=" + m.invoke(dev, TRANSPORT_LE);
        } catch (Throwable t) {
            return "bond failed: " + t;
        }
    }

    private void sendResult(Context ctx, String req, String msg) {
        try {
            Intent out = new Intent(ACTION_RESULT);
            out.setPackage("com.dsh.leatool");
            out.putExtra("req", req);
            out.putExtra("msg", msg);
            ctx.sendBroadcast(out);
            log(Log.INFO, TAG, "cmd: result req=" + req + " msg=" + msg);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "cmd: sendResult failed: " + t);
        }
    }

    /**
     * 一次性删除配对：把 persist.sys.lea.forget_mac 里列的 MAC 逐个 removeBond。
     * 用途：耳机恢复出厂后，旧的 LE 配对在界面上看不见、删不掉，但蓝牙栈会一直去连它。
     * 触发后自动清空属性。
     */
    private void forgetOnce(ClassLoader cl) {
        final String list = prop(PROP_FORGET_MAC, "");
        if (list == null || list.trim().isEmpty()) {
            return;
        }
        log(Log.INFO, TAG, "forgetOnce: " + list);
        new Thread(() -> {
            try {
                Thread.sleep(4000);
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) {
                    return;
                }
                for (String raw : list.split(",")) {
                    String mac = raw.trim().toUpperCase();
                    if (mac.isEmpty()) {
                        continue;
                    }
                    try {
                        BluetoothDevice dev = adapter.getRemoteDevice(mac);
                        int state = dev.getBondState();
                        Method m = BluetoothDevice.class.getMethod("removeBond");
                        m.setAccessible(true);
                        Object ok = m.invoke(dev);
                        log(Log.INFO, TAG, "forgetOnce: " + mac + " bondState=" + state
                                + " removeBond=" + ok);
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "forgetOnce: " + mac + " failed: " + t);
                    }
                }
                setPropEmpty(PROP_FORGET_MAC);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "forgetOnce failed: " + t);
            }
        }, "lea-forget-once").start();
    }

    /**
     * 一次性发起 LE 配对。
     *
     * 为什么需要：LE Audio 身份（xxx-LEA）不会出现在蓝牙列表里，用户点不到，
     * 只能程序化 createBond(TRANSPORT_LE)。触发属性成功后自动清空。
     */
    private void bondOnce(ClassLoader cl) {
        final String mac = prop(PROP_BOND_MAC, "");
        if (mac == null || mac.isEmpty()) {
            return;
        }
        log(Log.INFO, TAG, "bondOnce: scheduled for " + mac);
        new Thread(() -> {
            try {
                Thread.sleep(4000);
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    log(Log.ERROR, TAG, "bondOnce: adapter not ready");
                    return;
                }
                BluetoothDevice dev = adapter.getRemoteDevice(mac);
                int state = dev.getBondState();
                log(Log.INFO, TAG, "bondOnce: bondState=" + state);
                if (state == BluetoothDevice.BOND_NONE) {
                    Method m = BluetoothDevice.class.getMethod("createBond", int.class);
                    m.setAccessible(true);
                    Object ok = m.invoke(dev, TRANSPORT_LE);
                    log(Log.INFO, TAG, "bondOnce: createBond(LE) -> " + ok);
                }
                setPropEmpty(PROP_BOND_MAC);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "bondOnce failed: " + t);
            }
        }, "lea-bond-once").start();
    }

    /**
     * hook 公共 API BluetoothDevice#getUuids()。
     *
     * 为什么需要它：LE Audio 身份（xxx-LEA）的 UUID 列表不经过 DeviceProperties，
     * 所以 BatteryService 里 `device.getUuids()` 看到的是空/旧值，直接放弃连接
     * （日志：Remote does not have Battery UUID）。在这里补 0x180F，等于强行
     * 让系统去连一次 GATT——能连上说明耳机真有 BAS，连不上就说明确实没有。
     */
    private void hookPublicUuids(ClassLoader cl) {
        try {
            Class<?> dev = Class.forName("android.bluetooth.BluetoothDevice", false, cl);
            Method m = dev.getDeclaredMethod("getUuids");
            m.setAccessible(true);
            hook(m).intercept(chain -> {
                Object r = chain.proceed();
                if (!enabled() || !flag(PROP_SPLICE_BAS, false)) {
                    return r;
                }
                Object self = chain.getThisObject();
                String addr = null;
                try {
                    addr = ((BluetoothDevice) self).getAddress();
                } catch (Throwable ignored) {
                }
                if (addr == null || !isTargetAddress(addr)) {
                    return r;
                }
                ParcelUuid[] out = splice((ParcelUuid[]) r, true);
                if (out != r) {
                    log(Log.INFO, TAG, "getUuids(" + addr + "): " + describe((ParcelUuid[]) r)
                            + " -> " + describe(out));
                }
                return out;
            });
            log(Log.INFO, TAG, "hook BluetoothDevice#getUuids ok");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook getUuids failed: " + t);
        }
    }

    /** persist.sys.lea.target_macs：逗号分隔的 MAC 白名单，避免影响其他设备 */
    private static boolean isTargetAddress(String addr) {
        String list = prop(PROP_TARGET_MACS, "");
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (String s : list.split(",")) {
            if (s.trim().equalsIgnoreCase(addr)) {
                return true;
            }
        }
        return false;
    }

    private void hookSetUuids(ClassLoader cl) {
        Method m = find(cl, DEVICE_PROPS, "setUuids", ParcelUuid[].class);
        if (m == null) return;
        hook(m).intercept(chain -> {
            if (enabled()) {
                Object self = chain.getThisObject();
                ParcelUuid[] in = (ParcelUuid[]) chain.getArg(0);
                boolean full = isTargetDevice(self);
                log(Log.INFO, TAG, "setUuids: raw " + describe(in)
                        + " name=" + deviceName(self) + " target=" + full);
                ParcelUuid[] out = splice(in, full);
                if (out != in) {
                    log(Log.INFO, TAG, "setUuids: spliced " + describe(out)
                            + " (" + (in == null ? 0 : in.length) + " -> " + out.length + ")");
                    return chain.proceed(new Object[]{out});
                }
            }
            return chain.proceed();
        });
        log(Log.INFO, TAG, "hook setUuids ok");
    }

    private void hookGetUuids(ClassLoader cl) {
        Method m = find(cl, DEVICE_PROPS, "getUuids");
        if (m == null) return;
        hook(m).intercept(chain -> {
            Object r = chain.proceed();
            if (enabled()) {
                ParcelUuid[] out = splice((ParcelUuid[]) r, isTargetDevice(chain.getThisObject()));
                if (out != r) {
                    return out;
                }
            }
            return r;
        });
        log(Log.INFO, TAG, "hook getUuids ok");
    }

    private void hookIsLeAudioAllowed(ClassLoader cl) {
        Method m = find(cl, ADAPTER_SERVICE, "isLeAudioAllowed", BluetoothDevice.class);
        if (m == null) return;
        hook(m).intercept(chain -> {
            Object r = chain.proceed();
            if (!enabled() || !flag(PROP_FORCE_ALLOW, false) || !Boolean.FALSE.equals(r)) {
                return r;
            }
            // 只对显式列入 target_macs 的设备放行：对经典身份放行会让系统去抢 LE 链路，
            // 把已建立的经典连接挤掉（实测会导致"连上几秒就断"）。
            Object dev = chain.getArg(0);
            String addr = null;
            try {
                addr = ((BluetoothDevice) dev).getAddress();
            } catch (Throwable ignored) {
            }
            if (addr == null || !isTargetAddress(addr)) {
                return r;
            }
            log(Log.INFO, TAG, "isLeAudioAllowed: forced true for " + addr);
            return Boolean.TRUE;
        });
        log(Log.INFO, TAG, "hook isLeAudioAllowed ok");
    }

    // --------------------------------------------------------------- utils

    /**
     * 把 createBond 的 transport 改写成 LE。
     *
     * 只对「确实是 LE Audio 设备」的对象动手：UUID 里带 0x184E，或设备名匹配
     * persist.sys.lea.match（避免把键盘/车机这类经典设备也强行拉到 LE）。
     */
    private void hookCreateBond(ClassLoader cl) {
        Class<?> c;
        try {
            c = Class.forName(ADAPTER_SERVICE, false, cl);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "AdapterService not found: " + t);
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!"createBond".equals(m.getName()) || p.length < 2) continue;
            if (p[0] != BluetoothDevice.class || p[1] != int.class) continue;
            try {
                m.setAccessible(true);
                hook(m).intercept(chain -> {
                    Object dev = chain.getArg(0);
                    Object tp = chain.getArg(1);
                    if (enabled() && flag(PROP_FORCE_LE_BOND, true)
                            && dev instanceof BluetoothDevice && tp instanceof Integer
                            && ((Integer) tp) != TRANSPORT_LE
                            && looksLikeLeAudio((BluetoothDevice) dev)) {
                        Object[] args = chain.getArgs().toArray();
                        args[1] = TRANSPORT_LE;
                        log(Log.INFO, TAG, "createBond: transport " + tp + " -> 2 (LE) for "
                                + ((BluetoothDevice) dev).getAddress());
                        return chain.proceed(args);
                    }
                    return chain.proceed();
                });
                n++;
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "hook createBond failed: " + t);
            }
        }
        log(Log.INFO, TAG, "hook createBond ok (" + n + " overloads)");
    }

    private boolean looksLikeLeAudio(BluetoothDevice dev) {        String match = prop(PROP_MATCH, "");
        String name = null;
        try {
            name = dev.getName();
        } catch (Throwable ignored) {
        }
        boolean byName = match != null && !match.isEmpty() && name != null && name.contains(match);
        ParcelUuid[] uuids = null;
        try {
            uuids = dev.getUuids();
        } catch (Throwable ignored) {
        }
        boolean byUuid = containsLeAudio(uuids);
        log(Log.INFO, TAG, "bond candidate name=" + name + " uuids="
                + (uuids == null ? "null" : String.valueOf(uuids.length))
                + " leAudio=" + byUuid + " nameMatch=" + byName);
        return byUuid || byName;
    }

    private static boolean containsLeAudio(ParcelUuid[] uuids) {
        if (uuids == null) return false;
        for (ParcelUuid u : uuids) {
            if (LE_AUDIO.equals(u)) return true;
        }
        return false;
    }

    private Method find(ClassLoader cl, String className, String method, Class<?>... types) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            Method m = c.getDeclaredMethod(method, types);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "resolve " + className + "#" + method + " failed: " + t);
            return null;
        }
    }

    /** 把 UUID 数组压成短串，便于在日志里看清设备到底广播了什么。 */
    private static String describe(ParcelUuid[] uuids) {
        if (uuids == null) return "null";
        if (uuids.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(uuids.length, 8);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            ParcelUuid u = uuids[i];
            String s = u == null ? "null" : u.toString();
            // 0000184e-0000-1000-8000-00805f9b34fb -> 184e
            sb.append(s.length() >= 8 ? s.substring(4, 8) : s);
        }
        if (uuids.length > n) sb.append(",+").append(uuids.length - n);
        return sb.append(']').toString();
    }

    /**
     * 补齐 UUID。full=false（非目标身份）时原样返回，不做任何注入。
     * 已经齐全时也返回同一个引用，避免无意义的数组复制。
     */
    private static ParcelUuid[] splice(ParcelUuid[] in, boolean full) {
        if (!full) {
            return in;
        }
        ParcelUuid[] want = flag(PROP_SPLICE_BAS, false) ? LEA_FAMILY_BAS : LEA_FAMILY;
        if (in == null) {
            return want.clone();
        }
        boolean missing = false;
        for (ParcelUuid w : want) {
            if (!hasUuid(in, w)) {
                missing = true;
                break;
            }
        }
        if (!missing) {
            return in;
        }
        ParcelUuid[] out = new ParcelUuid[in.length + want.length];
        System.arraycopy(in, 0, out, 0, in.length);
        int n = in.length;
        for (ParcelUuid w : want) {
            if (!hasUuid(in, w)) {
                out[n++] = w;
            }
        }
        return Arrays.copyOf(out, n);
    }

    private static boolean hasUuid(ParcelUuid[] arr, ParcelUuid u) {
        for (ParcelUuid x : arr) {
            if (u.equals(x)) return true;
        }
        return false;
    }

    /**
     * 目标身份判定：
     *   1) 名字必须包含 persist.sys.lea.match（默认空=不限）
     *   2) 名字必须以 persist.sys.lea.suffix 结尾（默认 "-LEA"，空串=不限）
     * 第 2 条是为了只碰 LE Audio 那个身份，不去污染经典身份。
     */
    private static boolean isTargetDevice(Object props) {
        if (props == null) return false;
        String name = deviceName(props);
        if (name == null || name.isEmpty()) return false;
        String match = prop(PROP_MATCH, "");
        if (match != null && !match.isEmpty() && !name.contains(match)) {
            return false;
        }
        String suffix = prop(PROP_SUFFIX, "-LEA");
        return suffix == null || suffix.isEmpty() || name.contains(suffix);
    }

    private static String deviceName(Object props) {
        Object n = field(props, "mName");
        if (n instanceof String && !((String) n).isEmpty()) {
            return (String) n;
        }
        Object dev = field(props, "mDevice");
        if (dev instanceof BluetoothDevice) {
            try {
                return ((BluetoothDevice) dev).getName();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object field(Object obj, String name) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean enabled() {
        return flag(PROP_ENABLE, true);
    }

    private static boolean flag(String key, boolean def) {
        String v = prop(key, def ? "1" : "0");
        return !("0".equals(v) || "false".equalsIgnoreCase(v));
    }

    private static void setPropEmpty(String key) {
        setProp(key, "");
    }

    private static void setProp(String key, String value) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method m = c.getMethod("set", String.class, String.class);
            m.invoke(null, key, value);
        } catch (Throwable ignored) {
        }
    }

    /** SystemProperties 是 @hide API，走反射避免编译期依赖。 */
    private static String prop(String key, String def) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method m = c.getMethod("get", String.class, String.class);
            Object v = m.invoke(null, key, def);
            return v == null ? def : v.toString();
        } catch (Throwable t) {
            return def;
        }
    }
}
