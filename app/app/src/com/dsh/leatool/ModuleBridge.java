package com.dsh.leatool;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 与 LSPosed 模块 {@code com.dsh.lea} 的广播命令通道。
 *
 * <p>协议（模块侧 LeaEnabler#registerCommandReceiver 实现）：
 * <pre>
 * App  ->  com.dsh.lea.action.CMD    extras: cmd(ping|forget|connect|bond), mac, macs, req
 * 模块 ->  com.dsh.lea.action.RESULT extras: req(回显), msg
 * </pre>
 *
 * <p>2 秒超时：模块不在（未启用 / 蓝牙进程没重启）时不会回包，调用方拿到
 * {@code timeout=true}，退回写属性通道并提示「需要重启蓝牙生效」。
 *
 * <p>线程约束：init / send / onReceive / shutdown 都在主线程调用；回调也在主线程。
 */
final class ModuleBridge {

    static final String ACTION_CMD = "com.dsh.lea.action.CMD";
    static final String ACTION_RESULT = "com.dsh.lea.action.RESULT";
    static final String MODULE_PKG = "com.dsh.lea";
    static final long DEFAULT_TIMEOUT_MS = 2000L;

    interface Callback {
        /**
         * @param ok      模块返回且不是失败消息
         * @param msg     模块原始 msg（超时 / 未初始化时是说明文字）
         * @param timeout 超时没等到回包（此时应走属性降级）
         */
        void onResult(boolean ok, String msg, boolean timeout);
    }

    private static final class Pending {
        final String cmd;
        final Callback cb;
        Runnable timeout;

        Pending(String cmd, Callback cb) {
            this.cmd = cmd;
            this.cb = cb;
        }
    }

    private static final Map<String, Pending> PENDING = new LinkedHashMap<String, Pending>();
    private static Context app;
    private static Handler ui;

    private ModuleBridge() {
    }

    static void init(Context c) {
        try {
            app = c.getApplicationContext();
            ui = new Handler(Looper.getMainLooper());
        } catch (Throwable ignored) {
            app = null;
            ui = null;
        }
    }

    static boolean available() {
        return app != null;
    }

    /** 清空挂起请求（Activity 销毁 / 进程退出路径）。 */
    static void shutdown() {
        synchronized (PENDING) {
            for (Pending p : PENDING.values()) {
                if (ui != null && p.timeout != null) {
                    ui.removeCallbacks(p.timeout);
                }
            }
            PENDING.clear();
        }
        ui = null;
        app = null;
    }

    /** 是否有请求在等模块回应（UI 显示「等待模块响应…」）。 */
    static boolean waiting() {
        synchronized (PENDING) {
            return !PENDING.isEmpty();
        }
    }

    /**
     * 发一条命令。必须在主线程调用。
     *
     * @param cmd  ping | forget | connect | bond
     * @param mac  connect / bond 用
     * @param macs forget 用（逗号分隔）
     */
    static void send(final String cmd, final String mac, final String macs,
                     long timeoutMs, final Callback cb) {
        final long timeout = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
        Handler h = ui;
        Context ctx = app;
        if (h == null || ctx == null) {
            if (cb != null) {
                cb.onResult(false, "广播通道未初始化", true);
            }
            return;
        }
        final String req = UUID.randomUUID().toString();
        final Pending p = new Pending(cmd, cb);
        p.timeout = new Runnable() {
            @Override
            public void run() {
                boolean removed;
                synchronized (PENDING) {
                    removed = PENDING.remove(req) != null;
                }
                if (removed && cb != null) {
                    cb.onResult(false, "模块 2 秒内没有回应（cmd=" + cmd + "）", true);
                }
            }
        };
        synchronized (PENDING) {
            PENDING.put(req, p);
        }
        h.postDelayed(p.timeout, timeout);

        try {
            Intent i = new Intent(ACTION_CMD);
            i.setPackage(MODULE_PKG);
            i.putExtra("cmd", cmd);
            if (mac != null) {
                i.putExtra("mac", mac);
            }
            if (macs != null) {
                i.putExtra("macs", macs);
            }
            i.putExtra("req", req);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            // 发不出去 = 立即降级，别让 UI 干等 2 秒
            boolean removed;
            synchronized (PENDING) {
                removed = PENDING.remove(req) != null;
            }
            if (removed) {
                h.removeCallbacks(p.timeout);
                if (cb != null) {
                    cb.onResult(false, "发送广播失败：" + t.getClass().getSimpleName(), true);
                }
            }
        }
    }

    /** Activity 的 BroadcastReceiver 收到 RESULT 后转进来。 */
    static void onReceive(Intent intent) {
        if (intent == null) {
            return;
        }
        String req = intent.getStringExtra("req");
        String msg = intent.getStringExtra("msg");
        if (req == null) {
            return;
        }
        Pending p;
        synchronized (PENDING) {
            p = PENDING.remove(req);
        }
        if (p == null) {
            return;
        }
        Handler h = ui;
        if (h != null && p.timeout != null) {
            h.removeCallbacks(p.timeout);
        }
        if (p.cb != null) {
            p.cb.onResult(!WizardLogic.isFailMsg(msg), msg, false);
        }
    }
}
