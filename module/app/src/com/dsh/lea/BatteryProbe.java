package com.dsh.lea;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * 一次性电量协议探针（LE GATT）。
 *
 * 流程：连上 → 枚举服务 → 订阅所有 notify 特征 → 顺序读取所有可读特征 →
 *       最后往第一个可写特征发握手。
 * 所有收发的字节都打进日志。
 *
 * 触发：persist.sys.lea.probe=1 + probe_mac + （可选）probe_transport。
 * 安全：只读 + 订阅 + 一条握手；不发 0x24/0x2F 之类危险命令。
 */
final class BatteryProbe {

    private static final String TAG = "LeaEnabler";
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** 厂商私有服务：F000 是实测里唯一带「读+写+通知」的服务 */
    private static final UUID[] VENDOR_SERVICES = {
            UUID.fromString("0000f000-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("000000fe-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb"),
    };

    private static final byte[] HANDSHAKE = {0x00, 0x27, 0x01, 0x00, 0x02, (byte) 0xFF, 0x00};

    private final String mac;
    private final int transport;
    private final Deque<BluetoothGattCharacteristic> readQueue = new ArrayDeque<>();
    private int readAttempts = 0;
    private BluetoothGatt gatt;

    private BatteryProbe(String mac, int transport) {
        this.mac = mac;
        this.transport = transport;
    }

    static void start(String mac, int transport) {
        if (mac == null || mac.isEmpty()) return;
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                new BatteryProbe(mac, transport).run();
            } catch (Throwable t) {
                Log.e(TAG, "probe start failed: " + t);
            }
        }, "lea-probe").start();
    }

    private void run() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.e(TAG, "probe: adapter not ready");
            return;
        }
        BluetoothDevice dev = adapter.getRemoteDevice(mac);
        Context ctx = appContext();
        if (ctx == null) {
            Log.e(TAG, "probe: no context");
            return;
        }
        Log.i(TAG, "probe: connecting GATT to " + mac + " transport=" + transport);
        try {
            gatt = dev.connectGatt(ctx, false, callback, transport);
        } catch (Throwable t) {
            Log.e(TAG, "probe: connectGatt failed: " + t);
        }
    }

    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            return (Context) m.invoke(null);
        } catch (Throwable t) {
            Log.e(TAG, "probe: currentApplication failed: " + t);
            return null;
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Log.i(TAG, "probe: state status=" + status + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "probe: discoverServices -> " + g.discoverServices());
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            Log.i(TAG, "probe: services discovered status=" + status);
            dumpServices(g);
            subscribeVendor(g);
            queueReads(g);
            // 留时间给 CCCD 写入完成，再发握手
            new Thread(() -> {
                try {
                    Thread.sleep(2500);
                    writeHandshake(g);
                } catch (Throwable ignored) {
                }
            }, "lea-probe-write").start();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            logValue("RX", c.getUuid(), c.getValue());
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            logValue("READ(" + status + ")", c.getUuid(), c.getValue());
            pump(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            Log.i(TAG, "probe: CCCD write " + d.getCharacteristic().getUuid() + " status=" + status);
            pump(g);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            Log.i(TAG, "probe: TX " + c.getUuid() + " status=" + status);
        }
    };

    private void dumpServices(BluetoothGatt g) {
        try {
            List<BluetoothGattService> services = g.getServices();
            Log.i(TAG, "probe: services count=" + services.size());
            for (BluetoothGattService s : services) {
                Log.i(TAG, "probe:  SVC " + s.getUuid());
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    Log.i(TAG, "probe:    CHR " + c.getUuid()
                            + " props=0x" + Integer.toHexString(c.getProperties()));
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "probe: dumpServices failed: " + t);
        }
    }

    private void subscribeVendor(BluetoothGatt g) {
        try {
            for (UUID su : VENDOR_SERVICES) {
                BluetoothGattService s = g.getService(su);
                if (s == null) continue;
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int p = c.getProperties();
                    boolean notify = (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                    boolean indicate = (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                    if (!notify && !indicate) continue;
                    boolean ok = g.setCharacteristicNotification(c, true);
                    BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                    if (d != null) {
                        d.setValue(notify
                                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                        try {
                            g.writeDescriptor(d);
                        } catch (Throwable t) {
                            Log.e(TAG, "probe: writeDescriptor failed: " + t);
                        }
                    }
                    Log.i(TAG, "probe: subscribe " + c.getUuid() + " -> " + ok);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "probe: subscribeVendor failed: " + t);
        }
    }

    /** 把所有厂商服务里的可读特征排进队列，逐个读（GATT 一次只能一个操作） */
    private void queueReads(BluetoothGatt g) {
        try {
            List<BluetoothGattCharacteristic> list = new ArrayList<>();
            for (UUID su : VENDOR_SERVICES) {
                BluetoothGattService s = g.getService(su);
                if (s == null) continue;
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                        list.add(c);
                    }
                }
            }
            readQueue.addAll(list);
            Log.i(TAG, "probe: queued reads=" + list.size());
            pump(g);
        } catch (Throwable t) {
            Log.e(TAG, "probe: queueReads failed: " + t);
        }
    }

    private void pump(BluetoothGatt g) {
        try {
            BluetoothGattCharacteristic c = readQueue.poll();
            if (c == null) return;
            boolean ok = g.readCharacteristic(c);
            Log.i(TAG, "probe: read " + c.getUuid() + " -> " + ok);
            if (!ok) {
                // GATT 忙：放回队列，稍后重试（最多 3 次）
                final int attempt = ++readAttempts;
                if (attempt <= 3) {
                    new Thread(() -> {
                        try {
                            Thread.sleep(400);
                            readQueue.addFirst(c);
                            pump(g);
                        } catch (Throwable ignored) {
                        }
                    }, "lea-probe-retry").start();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "probe: pump failed: " + t);
        }
    }

    private void writeHandshake(BluetoothGatt g) {
        try {
            for (UUID su : VENDOR_SERVICES) {
                BluetoothGattService s = g.getService(su);
                if (s == null) continue;
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int p = c.getProperties();
                    if ((p & (BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
                        continue;
                    }
                    int wt = (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                            ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
                    int rc = g.writeCharacteristic(c, HANDSHAKE, wt);
                    Log.i(TAG, "probe: handshake -> " + c.getUuid() + " rc=" + rc
                            + " data=" + hex(HANDSHAKE));
                    return;
                }
            }
            Log.i(TAG, "probe: no writable vendor characteristic found");
        } catch (Throwable t) {
            Log.e(TAG, "probe: writeHandshake failed: " + t);
        }
    }

    private static void logValue(String dir, UUID uuid, byte[] v) {
        if (v == null) {
            Log.i(TAG, "probe: " + dir + " " + uuid + " null");
            return;
        }
        Log.i(TAG, "probe: " + dir + " " + uuid + " len=" + v.length + " " + hex(v));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) {
            sb.append(String.format("%02X ", x));
        }
        return sb.toString().trim();
    }
}
