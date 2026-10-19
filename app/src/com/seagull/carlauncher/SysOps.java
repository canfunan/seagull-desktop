package com.seagull.carlauncher;

import android.content.Context;
import android.util.Log;

/**
 * 系统级操作通道（root 侧）。
 *
 * 原则：每一条都先探测再执行，探测不过直接返回失败原因，
 * 绝不让 UI 卡在一个永远不返回的 su 上（车机上最忌讳这个）。
 * 所有命令都走 {@link Caps#exec(String)}，只在 Caps.hasRoot() 为真时调用。
 */
public final class SysOps {

    private static final String TAG = "SeagullSysOps";

    private SysOps() {}

    /* ---------------- 参数校验（su -c 拼接防注入，批次 T 复盘坐实） ---------------- */

    /** 包名：至少两段，只含 [A-Za-z0-9_]。 */
    private static boolean safePkg(String p) {
        return p != null && p.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    }

    /** 路径：只含文件名字符；禁空格/;/&/|/$ 与 ..（防写出沙盒）。 */
    private static boolean safePath(String p) {
        return p != null && p.matches("[a-zA-Z0-9_./+-]+") && !p.contains("..");
    }

    /** 权限名：android.permission.XXX / pkg.perm.PERM 形态。 */
    private static boolean safePerm(String p) {
        return p != null && p.matches("[a-zA-Z][a-zA-Z0-9_.]*");
    }

    public static final class R {
        public final boolean ok;
        public final String out;
        R(boolean ok, String out) { this.ok = ok; this.out = out; }
        @Override public String toString() { return (ok ? "OK  " : "FAIL ") + out; }
    }

    private static R run(String cmd) {
        if (!Caps.hasRoot()) return new R(false, "无 root 通道");
        String out = Caps.exec(cmd);
        boolean ok = out != null && !out.toLowerCase().contains("exception")
                && !out.toLowerCase().contains("permission denied")
                && !out.toLowerCase().contains("not found");
        return new R(ok, out == null ? "无回显" : out.trim());
    }

    /* ---------------- 屏幕 ---------------- */

    /** 亮度 0..255（车机夜间常用）。 */
    public static R setBrightness(int v) {
        int b = Math.max(1, Math.min(255, v));
        return run("settings put system screen_brightness " + b);
    }

    public static int getBrightness() {
        String s = Caps.exec("settings get system screen_brightness");
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return -1; }
    }

    /**
     * 设置屏幕常亮（车机导航时用）。
     * 审计 L6：旧实现关常亮时写死 60000 —— 用户原本设的 30s/2min/永不
     * 全被覆盖。开常亮前先把当前值存下来，关闭时还原。
     */
    private static volatile String savedScreenOffTimeout;

    public static R keepScreenOn(boolean on) {
        if (on) {
            if (savedScreenOffTimeout == null) {
                String cur = Caps.exec("settings get system screen_off_timeout");
                savedScreenOffTimeout = (cur == null || cur.trim().isEmpty())
                        ? "60000" : cur.trim();
            }
            return run("settings put system screen_off_timeout 2147483647");
        }
        String restore = savedScreenOffTimeout != null ? savedScreenOffTimeout : "60000";
        return run("settings put system screen_off_timeout " + restore);
    }

    /* ---------------- 音频 ---------------- */

    /** 媒体音量 0..15。 */
    public static R setVolume(int v) {
        int x = Math.max(0, Math.min(15, v));
        R r1 = run("media volume --stream 3 --set " + x);
        if (r1.ok) return r1;
        return run("cmd media_session volume --stream 3 --set " + x);
    }

    /* ---------------- 连接 ---------------- */

    public static R setWifi(boolean on) {
        R r = run("svc wifi " + (on ? "enable" : "disable"));
        if (r.ok) return r;
        return run("cmd wifi set-wifi-enabled " + (on ? "enabled" : "disabled"));
    }

    public static R setBluetooth(boolean on) {
        R r = run("svc bluetooth " + (on ? "enable" : "disable"));
        if (r.ok) return r;
        return run("cmd bluetooth_manager " + (on ? "enable" : "disable"));
    }

    public static R setAirplane(boolean on) {
        return run("settings put global airplane_mode_on " + (on ? 1 : 0)
                + " && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state " + on);
    }

    /** 读开关当前状态（读不到就当作开，让第一次点击变成「关」）。 */
    public static boolean isOn(Context ctx, String key) {
        try {
            return android.provider.Settings.Global.getInt(
                    ctx.getContentResolver(), key) == 1;
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean isWifiOn(Context ctx)      { return isOn(ctx, "wifi_on"); }
    public static boolean isBluetoothOn(Context ctx) { return isOn(ctx, "bluetooth_on"); }
    public static boolean isAirplaneOn(Context ctx)  { return isOn(ctx, "airplane_mode_on"); }

    /* ---------------- 时间 ---------------- */

    public static R setAutoTime(boolean on) {
        return run("settings put global auto_time " + (on ? 1 : 0));
    }

    /* ---------------- 进程 / 任务 ---------------- */

    public static R killBackground(String pkg) {
        if (!safePkg(pkg)) return new R(false, "包名不合法");
        return run("am force-stop " + pkg);
    }

    public static R clearCache(String pkg) {
        if (!safePkg(pkg)) return new R(false, "包名不合法");
        return run("pm clear " + pkg);
    }

    /* ---------------- 自身 ---------------- */

    /** 静默安装（自身更新）。uid=1000 时才是真静默，否则系统会要求确认。 */
    public static R installApk(String path) {
        if (!safePath(path)) return new R(false, "路径不合法");
        R r = run("pm install -r -t " + path);
        if (!r.ok) r = run("pm install -r -t " + path);   // 部分 ROM 首次返回 session 提示
        return r;
    }

    /** 运行时权限直接授予（省掉用户点授权框）。 */
    public static R grant(String pkg, String perm) {
        if (!safePkg(pkg) || !safePerm(perm)) return new R(false, "参数不合法");
        return run("pm grant " + pkg + " " + perm);
    }

    public static R forceStop(String pkg) {
        if (!safePkg(pkg)) return new R(false, "包名不合法");
        return run("am force-stop " + pkg);
    }

    /* ---------------- 信息 ---------------- */

    public static String envInfo(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("su 通道   : ").append(Caps.rootWho()).append('\n');
        sb.append("uid       : ").append(android.os.Process.myUid()).append('\n');
        sb.append("brightness: ").append(getBrightness()).append('\n');
        String fw = Caps.exec("dumpsys window | grep -m1 mCurrentFocus");
        sb.append("焦点窗口  : ").append(fw == null ? "-" : fw.trim()).append('\n');
        return sb.toString();
    }

    /* ---------------- 桌面组件取值（纯读取，无权限） ---------------- */

    public static String clockHHmm() {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date());
    }

    public static String dateCn() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        String[] wk = {"日","一","二","三","四","五","六"};
        return (c.get(java.util.Calendar.MONTH) + 1) + "月"
                + c.get(java.util.Calendar.DAY_OF_MONTH) + "日 周" + wk[c.get(java.util.Calendar.DAY_OF_WEEK) - 1];
    }

    public static String batteryPct(Context ctx) {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager)
                    ctx.getSystemService(Context.BATTERY_SERVICE);
            int p = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return p + "%";
        } catch (Throwable t) { return "--%"; }
    }

    public static String memFree(Context ctx) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long freeMb = mi.availMem / (1024 * 1024);
            long totMb = mi.totalMem / (1024 * 1024);
            return freeMb + "M\n/ " + totMb + "M";
        } catch (Throwable t) { return "—"; }
    }

    /**
     * 15.10 CPU 占用：读 /proc/stat 的两次采样差。
     * 免 root、免依赖，两行就够；拿不到（个别 ROM 禁读）就回 "—"。
     * 审计 L2：内部 sleep(120) 采样 —— 结果 2s 缓存，顶栏/监控行高频轮询
     * 不再每次都付 120ms 阻塞（也提示调用方放后台线程）。
     */
    private static volatile long cpuAt;
    private static volatile String cpuVal;

    public static String cpuPct() {
        long now = System.currentTimeMillis();
        String cached = cpuVal;
        if (cached != null && now - cpuAt < 2000) return cached;
        long[] a = cpuStat();
        if (a == null) return "—";
        try { Thread.sleep(120); } catch (Throwable ignore) {}   // 采样间隔，太短算出来全是 0
        long[] b = cpuStat();
        if (b == null) return "—";
        long idle = (b[0] - a[0]) + (b[1] - a[1]);
        long total = 0;
        for (int i = 0; i < b.length; i++) total += b[i] - a[i];
        if (total <= 0) return "—";
        String v = Math.round(100f * (total - idle) / total) + "%";
        cpuVal = v;
        cpuAt = now;
        return v;
    }

    /** 返回 [idle, iowait, user, nice, system, idle, irq, softirq, steal...] */
    private static long[] cpuStat() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/stat"));
            String line = r.readLine();
            r.close();
            if (line == null || !line.startsWith("cpu ")) return null;
            String[] parts = line.trim().split("\\s+");
            long[] v = new long[parts.length - 1];
            for (int i = 1; i < parts.length; i++) v[i - 1] = Long.parseLong(parts[i]);
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 15.10 一行监控：CPU / 温度 / 内存。 */
    public static String monitorLine(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("CPU ").append(cpuPct());
        String t = tempC();
        if (!"—".equals(t)) sb.append(" · ").append(t);
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            sb.append(" · 内存 ").append(((mi.totalMem - mi.availMem) >> 20)).append('/')
                    .append((mi.totalMem >> 20)).append('M');
        } catch (Throwable ignore) {}
        return sb.toString();
    }

    public static String tempC() {
        // 无网络下拉系统热区温度，保证组件不空
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(
                    "/sys/class/thermal/thermal_zone0/temp"));
            String l = r.readLine(); r.close();
            if (l != null) return (Integer.parseInt(l.trim()) / 1000) + "°C";
        } catch (Throwable ignore) {}
        return "—";
    }
}
