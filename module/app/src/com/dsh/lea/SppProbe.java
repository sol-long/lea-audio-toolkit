package com.dsh.lea;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * 一次性 SPP 探针：连 RoseLink 用的那条 RFCOMM 通道，按蓝讯 AB-Mate 协议发握手，
 * 把收到的字节原样打到日志。
 *
 * 触发：persist.sys.lea.spp_probe=1 + persist.sys.lea.probe_mac=<MAC>
 *
 * 安全：只发 0x27（查询）；0x24/0x2F 这类危险命令一律不发。
 */
final class SppProbe {

    private static final String TAG = "LeaEnabler";

    /** RoseLink 自己的 SPP 服务 UUID（SDP 记录里 channel 6） */
    private static final UUID SPP_UUID =
            UUID.fromString("0cf12d31-fac3-4553-bd80-d6832e7b3931");

    /** 蓝讯 AB-Mate：问 MTU 的握手帧 */
    private static final byte[] HANDSHAKE = {0x00, 0x27, 0x01, 0x00, 0x02, (byte) 0xFF, 0x00};
    /** 查询电量：info id 0x01 */
    private static final byte[] QUERY_BATTERY = {0x00, 0x27, 0x01, 0x00, 0x02, 0x01, 0x00};

    private final String mac;

    private SppProbe(String mac) {
        this.mac = mac;
    }

    static void start(String mac) {
        if (mac == null || mac.isEmpty()) {
            return;
        }
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                new SppProbe(mac).run();
            } catch (Throwable t) {
                Log.e(TAG, "spp probe start failed: " + t);
            }
        }, "lea-spp-probe").start();
    }

    private void run() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.e(TAG, "spp: adapter not ready");
            return;
        }
        BluetoothDevice dev = adapter.getRemoteDevice(mac);

        BluetoothSocket socket = null;
        try {
            Log.i(TAG, "spp: connecting to " + mac + " uuid=" + SPP_UUID);
            socket = dev.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
            Log.i(TAG, "spp: connected (uuid lookup)");
            exchange(socket);
            return;
        } catch (Throwable t) {
            Log.e(TAG, "spp: uuid connect failed: " + t);
        } finally {
            closeQuietly(socket);
        }

        socket = null;
        try {
            Log.i(TAG, "spp: fallback to channel 6");
            socket = channelSocket(dev, 6);
            socket.connect();
            Log.i(TAG, "spp: connected (channel 6)");
            exchange(socket);
        } catch (Throwable t) {
            Log.e(TAG, "spp: channel 6 failed: " + t);
        } finally {
            closeQuietly(socket);
        }
    }

    /** createInsecureRfcommSocket(int) 是 hidden API，用反射调用 */
    private static BluetoothSocket channelSocket(BluetoothDevice dev, int channel) throws Exception {
        java.lang.reflect.Method m = BluetoothDevice.class.getMethod(
                "createInsecureRfcommSocket", int.class);
        m.setAccessible(true);
        return (BluetoothSocket) m.invoke(dev, channel);
    }

    private void exchange(BluetoothSocket socket) throws Exception {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        send(out, "handshake", HANDSHAKE);
        Log.i(TAG, "spp: rx handshake -> " + hex(readWithTimeout(in, 4000)));

        send(out, "queryBattery", QUERY_BATTERY);
        for (int i = 0; i < 4; i++) {
            byte[] b = readWithTimeout(in, 3000);
            Log.i(TAG, "spp: rx[" + i + "] -> " + hex(b));
            if (b == null || b.length == 0) {
                break;
            }
        }
    }

    private static void send(OutputStream out, String what, byte[] data) throws Exception {
        out.write(data);
        out.flush();
        Log.i(TAG, "spp: tx " + what + " -> " + hex(data));
    }

    /** 带超时的读取：起一个线程读，主线程最多等 ms 毫秒 */
    private static byte[] readWithTimeout(InputStream in, long ms) {
        final byte[][] holder = new byte[1][];
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[256];
                int n = in.read(buf);
                if (n > 0) {
                    byte[] o = new byte[n];
                    System.arraycopy(buf, 0, o, 0, n);
                    holder[0] = o;
                }
            } catch (Throwable ignored) {
            }
        }, "lea-spp-read");
        t.setDaemon(true);
        t.start();
        try {
            t.join(ms);
        } catch (InterruptedException ignored) {
        }
        return holder[0];
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (Throwable ignored) {
        }
    }

    private static String hex(byte[] b) {
        if (b == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) {
            sb.append(String.format("%02X ", x));
        }
        return sb.toString().trim();
    }
}
