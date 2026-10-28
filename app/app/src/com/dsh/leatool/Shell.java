package com.dsh.leatool;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 极简 shell 执行器。
 * <p>有 root 时通过 {@code su -c <cmd>} 执行；没有 root 时降级为 {@code /system/bin/sh -c <cmd>}，
 * 只读命令仍然可用，写命令会失败并由调用方提示。所有方法都不抛异常。
 */
final class Shell {

    static final class Result {
        final String cmd;
        final int code;
        final String out;
        final boolean timedOut;
        final String error;

        Result(String cmd, int code, String out, boolean timedOut, String error) {
            this.cmd = cmd == null ? "" : cmd;
            this.code = code;
            this.out = out == null ? "" : out;
            this.timedOut = timedOut;
            this.error = error;
        }

        boolean ok() {
            return error == null && !timedOut && code == 0;
        }

        String text() {
            return out.trim();
        }
    }

    /** -1 = 未知，0 = 无 root，1 = 有 root */
    private static volatile int sRoot = -1;

    private Shell() {
    }

    /** UI 线程可用：只读缓存，不阻塞。null 表示还没探测过。 */
    static Boolean rootCached() {
        int v = sRoot;
        if (v < 0) {
            return null;
        }
        return v == 1;
    }

    /** 忘记缓存的探测结果（用户在 su 弹窗里点了允许后可以手动重试）。 */
    static void resetRoot() {
        sRoot = -1;
    }

    /** 探测并缓存 root（可能需要几百毫秒，勿在主线程首次调用）。 */
    static boolean rootAvailable() {
        int v = sRoot;
        if (v >= 0) {
            return v == 1;
        }
        boolean ok;
        try {
            Result r = exec(new String[]{"su", "-c", "id"}, "su -c id", 8000);
            ok = r.out.contains("uid=0") && r.error == null;
        } catch (Throwable t) {
            ok = false;
        }
        sRoot = ok ? 1 : 0;
        return ok;
    }

    /** 优先 root，失败/不存在时退回普通 shell。 */
    static Result run(String cmd, long timeoutMs) {
        if (cmd == null || cmd.trim().isEmpty()) {
            return new Result("", 0, "", false, null);
        }
        if (rootAvailable()) {
            Result r = exec(new String[]{"su", "-c", cmd}, cmd, timeoutMs);
            if (r.error == null) {
                return r;
            }
            // su 存在但起不来（例如被拒绝），退回普通 shell 再试一次只读命令
            return exec(new String[]{"/system/bin/sh", "-c", cmd}, cmd, timeoutMs);
        }
        return exec(new String[]{"/system/bin/sh", "-c", cmd}, cmd, timeoutMs);
    }

    /** 强制走普通 shell（只读、不触发 su 授权弹窗）。 */
    static Result runUnprivileged(String cmd, long timeoutMs) {
        return exec(new String[]{"/system/bin/sh", "-c", cmd}, cmd, timeoutMs);
    }

    private static Result exec(final String[] argv, String display, long timeoutMs) {
        Process proc;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            proc = pb.start();
        } catch (Throwable t) {
            return new Result(display, -1, "", false, "无法执行: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : (": " + t.getMessage())));
        }

        final Process p = proc;
        final StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[8192];
                try (InputStream in = p.getInputStream()) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                } catch (Throwable ignored) {
                    // 进程被 destroy 时读流会抛异常，属正常
                }
            }
        }, "shell-reader");
        reader.setDaemon(true);
        reader.start();

        boolean done = false;
        try {
            done = p.waitFor(Math.max(500L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            // 老 API / 被中断
        }

        if (!done) {
            try {
                p.destroy();
            } catch (Throwable ignored) {
            }
            try {
                reader.join(400);
            } catch (Throwable ignored) {
            }
            return new Result(display, -1, sb.toString(), true,
                    "超时 " + timeoutMs + "ms");
        }

        try {
            reader.join(2000);
        } catch (Throwable ignored) {
        }
        int code;
        try {
            code = p.exitValue();
        } catch (Throwable t) {
            code = -1;
        }
        return new Result(display, code, sb.toString(), false, null);
    }

    /** 单引号包裹，供 su -c 里的路径使用。 */
    static String quote(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
