package com.seagull.carlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;

/**
 * P1 OBD2 引擎：SPP 连接（bonded 过滤）→ AT 初始化 → Mode01 PID 单命令轮询
 * → ObdParser 解析 → static 快照（UI 各块 1s tick 自取）。断线 5s 重连；
 * 连续 10 轮全失效判离线；电压低于阈值持续 60s 发本地通知（会话一次）。
 * 线程内全 catch：引擎自身异常只降级离线，进程存活。
 */
public final class ObdEngine {
    private static final String TAG = "Obd";
    private static final String SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB";
    private static final int RESP_TIMEOUT_MS = 2000;
    private static final int RECONNECT_MS = 5000;
    private static final int IDLE_MS = 3000;
    private static final int ALL_INVALID_ROUNDS = 10;
    private static final long LOW_VOLT_HOLD_MS = 60_000L;
    private static final String CH = "seagull_obd";

    /** 数据快照：volatile 字段逐个写（读取方 tick 撕裂可容忍，数值写原子）。 */
    public static final class State {
        public volatile boolean online;
        public volatile String status = "未启用";     // 设置页可读状态
        public volatile float speedKmh = Float.NaN;   // NaN = 本 PID 失效
        public volatile float rpm = Float.NaN;
        public volatile float coolantC = Float.NaN;
        public volatile float voltageV = Float.NaN;
        public volatile float fuelPct = Float.NaN;
        public volatile long lastValidTs;
        public volatile boolean speedFromObd;
    }

    public static final State STATE = new State();
    private static volatile Thread worker;
    private static volatile boolean running;
    private static long lowVoltFirstTs;
    private static boolean lowVoltWarned;

    private ObdEngine() { }

    /** HomeActivity.onCreate 按开关调用；重复调用幂等。 */
    public static void start(Context c) {
        if (running) return;
        final Context app = c.getApplicationContext();
        running = true;
        worker = new Thread(() -> loop(app), "seagull-obd");
        worker.setDaemon(true);
        worker.start();
    }

    public static void stop() {
        running = false;
        Thread t = worker;
        if (t != null) t.interrupt();
        STATE.online = false;
        STATE.status = "已停用";
    }

    /* ==================== 主循环 ==================== */

    private static void loop(Context app) {
        while (running) {
            try {
                LauncherModel model = new LauncherModel(app, false);   // 构造器自带 load
                if (!model.obdEnabled) {
                    STATE.online = false;
                    STATE.status = "未启用";
                    sleep(IDLE_MS);
                    continue;
                }
                STATE.status = "连接中…";
                BluetoothSocket sock = connect(app, model.obdMac);
                if (sock == null) {
                    sleep(RECONNECT_MS);
                    continue;
                }
                try {
                    runSession(app, model, sock);
                } finally {
                    try { sock.close(); } catch (Throwable ignore) { }
                }
                STATE.online = false;
                STATE.status = "连接断开，5s 后重连";
                sleep(RECONNECT_MS);
            } catch (Throwable t) {
                RootLog.w(TAG, "引擎循环异常", t);
                STATE.online = false;
                sleep(RECONNECT_MS);
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { /* running 检查兜底 */ }
    }

    /** 已配对设备里找 obdMac，建 SPP 连接；失败返回 null（原因写 status）。 */
    private static BluetoothSocket connect(Context app, String mac) {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled()) {
                STATE.status = "蓝牙未开启";
                return null;
            }
            if (mac == null || mac.isEmpty()) {
                STATE.status = "还没选适配器（设置→车辆数据）";
                return null;
            }
            Set<BluetoothDevice> bonded = ad.getBondedDevices();
            BluetoothDevice dev = null;
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    if (mac.equals(d.getAddress())) { dev = d; break; }
                }
            }
            if (dev == null) {
                STATE.status = "适配器未配对（先到系统蓝牙配对）";
                return null;
            }
            BluetoothSocket s = dev.createRfcommSocketToServiceRecord(
                    java.util.UUID.fromString(SPP_UUID));
            s.connect();
            return s;
        } catch (SecurityException e) {
            STATE.status = "缺蓝牙权限（设置→车辆数据里申请）";
            return null;
        } catch (Throwable t) {
            STATE.status = "连接失败：" + t.getMessage();
            return null;
        }
    }

    /* ==================== 会话：初始化 + 轮询 ==================== */

    private static void runSession(Context app, LauncherModel model, BluetoothSocket sock) {
        OutputStream out = null;
        InputStream in = null;
        try {
            out = sock.getOutputStream();
            in = sock.getInputStream();
        } catch (Throwable t) {
            STATE.status = "流打开失败";
            return;
        }
        // AT 初始化：逐条等 '>'，任何一条超时判失败
        String[] init = {"ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100"};
        for (String cmd : init) {
            String r = query(out, in, cmd);
            if (r == null) {
                STATE.status = "初始化无响应（" + cmd + "）";
                return;
            }
        }
        STATE.online = true;
        STATE.status = "在线";
        RootLog.i(TAG, "OBD 会话开始");

        int allInvalid = 0;
        int[] pids = {ObdParser.PID_SPEED, ObdParser.PID_RPM,
                ObdParser.PID_COOLANT, ObdParser.PID_VOLT, ObdParser.PID_FUEL};
        while (running) {
            LauncherModel m = new LauncherModel(app, false);   // 间隔/阈值热生效
            boolean anyValid = false;
            for (int pid : pids) {
                if (!running) return;
                String resp = query(out, in, "01" + ObdParser.hex2(pid));
                double v = ObdParser.parse(pid, resp);
                if (pid == ObdParser.PID_VOLT && Double.isNaN(v)) {
                    v = ObdParser.parseVoltageAtrv(query(out, in, "ATRV"));
                }
                apply(pid, v);
                if (!Double.isNaN(v)) anyValid = true;
            }
            lowVoltCheck(app, m);
            if (anyValid) {
                allInvalid = 0;
                STATE.lastValidTs = System.currentTimeMillis();
            } else if (++allInvalid >= ALL_INVALID_ROUNDS) {
                STATE.status = "总线无数据，重连";
                return;
            }
            long interval = m.obdIntervalMs <= 0 ? 2000L : m.obdIntervalMs;
            long spend = System.currentTimeMillis() - STATE.lastValidTs;
            sleep(Math.max(200L, interval - Math.min(interval, spend % interval)));
        }
    }

    private static void apply(int pid, double v) {
        float f = (float) v;
        switch (pid) {
            case ObdParser.PID_SPEED:
                STATE.speedKmh = f;
                STATE.speedFromObd = !Float.isNaN(f);
                break;
            case ObdParser.PID_RPM: STATE.rpm = f; break;
            case ObdParser.PID_COOLANT: STATE.coolantC = f; break;
            case ObdParser.PID_VOLT: STATE.voltageV = f; break;
            case ObdParser.PID_FUEL: STATE.fuelPct = f; break;
            default: break;
        }
    }

    /* ==================== 亏电提醒 ==================== */

    private static void lowVoltCheck(Context app, LauncherModel m) {
        try {
            float v = STATE.voltageV;
            if (Float.isNaN(v) || v <= 0 || v >= m.obdVoltLow) {
                lowVoltFirstTs = 0;
                lowVoltWarned = false;   // 恢复复位（R4.2）
                return;
            }
            long now = System.currentTimeMillis();
            if (lowVoltFirstTs == 0) { lowVoltFirstTs = now; return; }
            if (!lowVoltWarned && now - lowVoltFirstTs >= LOW_VOLT_HOLD_MS) {
                lowVoltWarned = true;
                notifyLow(app, m, v);
            }
        } catch (Throwable t) {
            RootLog.w(TAG, "电压提醒失败", t);
        }
    }

    private static void notifyLow(Context app, LauncherModel m, float v) {
        NotificationManager nm = (NotificationManager)
                app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "车辆数据",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(app, 0x0BD1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(app, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("电瓶电压偏低")
                .setContentText(String.format(java.util.Locale.US,
                        "%.1fV，低于 %.1fV 阈值，注意驻车用电", v, m.obdVoltLow))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(0x0BD1, n);
        RootLog.i(TAG, "亏电提醒已发 v=" + v);
    }

    /* ==================== 串口读写 ==================== */

    /** 发一条命令读到 '>' 或超时；失败返回 null（触发重连）。 */
    private static String query(OutputStream out, InputStream in, String cmd) {
        try {
            out.write((cmd + "\r").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.flush();
            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + RESP_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                while (in.available() > 0) {
                    int b = in.read();
                    if (b < 0) return null;
                    char ch = (char) b;
                    if (ch == '>') return sb.toString();
                    sb.append(ch);
                }
                Thread.sleep(10);
            }
            return null;   // 超时
        } catch (InterruptedException e) {
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
