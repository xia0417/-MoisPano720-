package com.xyh.gimbal;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.UUID;

/**
 * Moispano720 / TL-6A 蓝牙云台最小控制库（无第三方依赖）。
 *
 * 协议要点（细节见仓库 PROTOCOL.md）：
 *   17 字节帧：11 01 55 01 02 08 | VH VL | DIR | MODE | 88 88 88 88 | 00 0D 0A
 *   V = round(|deg| / 360 * 4096)；DIR 1=右 0=左；MODE 1..4（3 最快 2 最慢）
 *   动作 = 唤醒帧(deg=0) → 600ms → 执行帧；单发执行帧不动
 *
 * 用法：
 *   GimbalBle g = new GimbalBle(this, listener);
 *   g.connect();            // 扫描 + 连接（异步）
 *   g.turn(45, 3);          // 右转 45°，mode 3
 *   g.home();               // 按累计角度回中
 *   g.startHeartbeat();     // 防休眠，每 20s 一帧零偏移
 *   g.setAutoHome(true, 10000);  // 转完 10s 无新动作自动回中
 */
@SuppressLint("MissingPermission")
public class GimbalBle {

    private static final String TAG = "GimbalBle";

    /** 广播名前缀；后缀是序列号，各机不同，不要硬编码 MAC */
    public static final String NAME_PREFIX = "TL-6A";

    public static final UUID SVC  = UUID.fromString("00001910-0000-1000-8000-00805f9b34fb");
    public static final UUID CH_W = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    public static final UUID CH_R = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb");

    /** 实测 毫秒/度：1=105, 2=164, 3=31(最快), 4=80 */
    public static final int[] MS_PER_DEG = {105, 164, 31, 80};

    /** 唤醒帧到执行帧的固定间隔 */
    public static final int WAKE_DELAY_MS = 600;
    /** 心跳周期 */
    public static final int BEAT_PERIOD_MS = 20000;
    /** 心跳与动作帧的最小间隔，避免插进两帧之间 */
    public static final long BEAT_GUARD_MS = 1500;
    /** 连接看门狗：多久没有 onServicesDiscovered 就重连 */
    private static final long CONNECT_TIMEOUT_MS = 6000;

    public interface Listener {
        void onLog(String msg);
        /** "idle" | "scanning" | "connecting" | "ready" | "lost" | "beating" */
        void onState(String state);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic chW;

    private volatile boolean ready = false;
    private boolean wantConnection = false;
    private int connectAttempt = 0;

    private double accumulated = 0.0;
    private int mode = 3;
    private long lastWriteAt = 0;

    private boolean autoHome = false;
    private int autoHomeDelayMs = 10000;
    private Runnable autoHomeRunnable;
    private int autoHomes = 0;

    private boolean beating = false;
    private Runnable beatRunnable;

    public GimbalBle(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        BluetoothManager bm = (BluetoothManager) this.ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = (bm == null) ? null : bm.getAdapter();
    }

    // ------------------------------------------------------------------ 对外

    public boolean isBluetoothReady() {
        return adapter != null && adapter.isEnabled();
    }

    public boolean isReady() {
        return ready;
    }

    public double getAccumulated() {
        return accumulated;
    }

    public int getMode() {
        return mode;
    }

    public void setMode(int m) {
        mode = Math.max(1, Math.min(4, m));
        log("速度档 → mode " + mode + "（" + MS_PER_DEG[mode - 1] + " ms/度）");
    }

    public void connect() {
        if (!isBluetoothReady()) {
            log("蓝牙不可用或未开启");
            return;
        }
        wantConnection = true;
        startScan();
    }

    public void disconnect() {
        wantConnection = false;
        stopScan();
        stopHeartbeat();
        closeGatt();
        setState("idle");
    }

    /** 转向：正=右，负=左。会累加角度并（若开启）重排自动回中。 */
    public void turn(final double deg) {
        turn(deg, mode, true);
    }

    public void turn(final double deg, final int m) {
        turn(deg, m, true);
    }

    /** count=false 用于回中，不重复记账 */
    private void turn(final double deg, final int m, final boolean count) {
        if (!ready || gatt == null) {
            log("还没连上云台");
            return;
        }
        if (Math.abs(deg) < 0.5) {
            log("角度过小，忽略");
            return;
        }
        // 新动作作废尚未触发的自动回中
        cancelAutoHome();

        final int md = Math.max(1, Math.min(4, m));
        log(String.format("转向 %+.1f°（mode %d，约 %.1fs）", deg, md, estimateSec(deg, md)));

        // 第一帧：唤醒，角度 0，不动
        writeFrame(frame(0, true, md));

        ui.postDelayed(new Runnable() {
            @Override public void run() {
                // 第二帧：执行
                writeFrame(frame(Math.abs(deg), deg >= 0, md));
                if (count) {
                    accumulated += deg;
                    if (autoHome) scheduleAutoHome(md);
                }
            }
        }, WAKE_DELAY_MS);
    }

    /** 按累计角度回中（最快档 3）。累计 <3° 不发帧。 */
    public void home() {
        if (Math.abs(accumulated) < 3.0) {
            log(String.format("累计 %.1f°，无需回中", accumulated));
            return;
        }
        double back = -accumulated;
        accumulated = 0.0;
        log(String.format("回中：反向 %+.1f°", back));
        turn(back, 3, false);
    }

    public void setAutoHome(boolean on, int delayMs) {
        autoHome = on;
        autoHomeDelayMs = Math.max(1000, delayMs);
        if (!on) cancelAutoHome();
        log("自动回中 " + (on ? "开，" + (autoHomeDelayMs / 1000) + "s" : "关"));
    }

    // ------------------------------------------------------------------ 心跳

    public void startHeartbeat() {
        if (beating) return;
        beating = true;
        beatRunnable = new Runnable() {
            @Override public void run() {
                if (!beating) return;
                if (ready && System.currentTimeMillis() - lastWriteAt > BEAT_GUARD_MS) {
                    writeFrame(frame(0, true, 1)); // 零偏移，不会动
                }
                ui.postDelayed(this, BEAT_PERIOD_MS);
            }
        };
        ui.postDelayed(beatRunnable, BEAT_PERIOD_MS);
        log("心跳已开：每 " + (BEAT_PERIOD_MS / 1000) + "s 一帧");
    }

    public void stopHeartbeat() {
        beating = false;
        if (beatRunnable != null) ui.removeCallbacks(beatRunnable);
        beatRunnable = null;
    }

    // ------------------------------------------------------------------ 扫描 / 连接

    private void startScan() {
        setState("scanning");
        log("扫描 " + NAME_PREFIX + " …");
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            log("扫描器不可用");
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanner.startScan(null, settings, scanCb);
        ui.postDelayed(scanTimeout, 10000);
    }

    private final Runnable scanTimeout = new Runnable() {
        @Override public void run() {
            log("10s 没扫到。检查云台开关 / 是否已休眠 / 别的 App 是否占着");
        }
    };

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String name = (result.getScanRecord() != null) ? result.getScanRecord().getDeviceName() : null;
            if (name == null) name = d.getName();
            if (name != null && name.startsWith(NAME_PREFIX)) {
                log("找到 " + name + "  " + d.getAddress());
                scanner.stopScan(this);
                ui.removeCallbacks(scanTimeout);
                connectDevice(d);
            }
        }

        @Override public void onScanFailed(int errorCode) {
            log("扫描失败 code=" + errorCode);
        }
    };

    private void stopScan() {
        try {
            if (scanner != null) scanner.stopScan(scanCb);
        } catch (Exception ignored) {
        }
        ui.removeCallbacks(scanTimeout);
    }

    private void connectDevice(BluetoothDevice d) {
        setState("connecting");
        closeGatt();
        // autoConnect 交替：false 直连更快，true 在后台等
        boolean auto = (connectAttempt % 2 == 1);
        connectAttempt++;
        log("连接中（autoConnect=" + auto + "）…");
        gatt = d.connectGatt(ctx, auto, gattCb);
        ui.postDelayed(connectWatchdog, CONNECT_TIMEOUT_MS);
    }

    private final Runnable connectWatchdog = new Runnable() {
        @Override public void run() {
            if (!ready && wantConnection) {
                log("连接超时，重连");
                if (gatt != null) {
                    try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
                    gatt = null;
                }
                startScan();
            }
        }
    };

    private void closeGatt() {
        ready = false;
        chW = null;
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                log("已连接，发现服务…");
                g.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                ready = false;
                setState("lost");
                log("连接断开");
                if (wantConnection) {
                    ui.postDelayed(new Runnable() {
                        @Override public void run() { startScan(); }
                    }, 1000);
                }
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            ui.removeCallbacks(connectWatchdog);
            BluetoothGattService s = g.getService(SVC);
            if (s == null) {
                log("没找到 service " + SVC + "，可能不是这个型号");
                return;
            }
            chW = s.getCharacteristic(CH_W);
            if (chW == null) {
                log("没找到写特征 " + CH_W);
                return;
            }
            ready = true;
            connectAttempt = 0;
            setState("ready");
            log("就绪：可以转向了");
        }
    };

    // ------------------------------------------------------------------ 帧与写

    /** 构造 17 字节帧。deg 取绝对值，方向由 right 决定。 */
    public static byte[] frame(double deg, boolean right, int m) {
        int v = (int) Math.round(Math.abs(deg) / 360.0 * 4096.0);
        v = Math.max(0, Math.min(4096, v));
        m = Math.max(1, Math.min(4, m));
        return new byte[]{
                0x11, 0x01, 0x55, 0x01, 0x02, 0x08,
                (byte) ((v >> 8) & 0xFF), (byte) (v & 0xFF),
                (byte) (right ? 0x01 : 0x00),
                (byte) m,
                (byte) 0x88, (byte) 0x88, (byte) 0x88, (byte) 0x88,
                0x00, 0x0D, 0x0A
        };
    }

    public static double estimateSec(double deg, int m) {
        return 0.6 + Math.abs(deg) * MS_PER_DEG[Math.max(1, Math.min(4, m)) - 1] / 1000.0;
    }

    private void writeFrame(byte[] data) {
        final BluetoothGatt g = gatt;
        final BluetoothGattCharacteristic c = chW;
        if (g == null || c == null || !ready) return;
        lastWriteAt = System.currentTimeMillis();
        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        c.setValue(data);
        boolean ok = g.writeCharacteristic(c);
        if (!ok) {
            // 偶发被底层拒绝，稍后重试一次
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    if (ready && chW != null) {
                        chW.setValue(data);
                        gatt.writeCharacteristic(chW);
                    }
                }
            }, 100);
        }
    }

    // ------------------------------------------------------------------ 自动回中

    private void scheduleAutoHome(int m) {
        cancelAutoHome();
        final int md = m;
        double mag = Math.abs(accumulated);
        if (mag < 3.0) return;
        long wait = 600 + (long) (mag * MS_PER_DEG[md - 1]) + autoHomeDelayMs;
        autoHomeRunnable = new Runnable() {
            @Override public void run() {
                autoHomes++;
                log("自动回中触发（累计 " + String.format("%.1f", accumulated) + "°）");
                home();
            }
        };
        ui.postDelayed(autoHomeRunnable, wait);
    }

    private void cancelAutoHome() {
        if (autoHomeRunnable != null) {
            ui.removeCallbacks(autoHomeRunnable);
            autoHomeRunnable = null;
        }
    }

    public int getAutoHomes() {
        return autoHomes;
    }

    // ------------------------------------------------------------------ 小工具

    private void setState(String s) {
        if (listener != null) listener.onState(s);
    }

    private void log(String s) {
        if (listener != null) listener.onLog(s);
    }
}

