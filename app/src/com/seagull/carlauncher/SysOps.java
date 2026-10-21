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

    /* ---------------- 车联助手 root 能力对齐（批次 S-RESTORE）----------------
     * leting（com.leting 3.0.0）dex 里共 6 处 su -c，归成两类能力：
     * 指定 display 启动组件（am start --display N -f 0x10104000 -n，API 被拒时的
     * 兜底）与向指定 display 注入按键（input -d N keyevent，其 CarWithX-PIP 的
     * 触摸/返回注入）。这里同款补齐，供桌面在 API 被拒时走同一条兜底路。 */

    /** 组件名：包名 + '/' + 类名，类名允许点。 */
    private static boolean safeComponent(String p) {
        if (p == null) return false;
        int s = p.indexOf('/');
        if (s <= 0 || s == p.length() - 1) return false;
        return safePkg(p.substring(0, s)) && p.substring(s + 1).matches("[a-zA-Z0-9_./]+");
    }

    /** 指定 display 启动组件（leting 同款兜底）。 */
    public static R amStartOnDisplay(int displayId, String component) {
        if (displayId < 0 || !safeComponent(component)) return new R(false, "参数不合法");
        return run("am start --display " + displayId + " -f 0x10104000 -n " + component);
    }

    /** 向指定 display 注入按键事件（leting PIP 同款）。 */
    public static R inputKeyeventOnDisplay(int displayId, int keycode) {
        if (displayId < 0 || keycode < 0) return new R(false, "参数不合法");
        return run("input -d " + displayId + " keyevent " + keycode);
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

    /**
     * LSPosed 配置直查（批次 W5，root）—— 注入失败时的决定性证据，不再猜。
     *
     * 心跳 ✗ 时只有两种可能：模块没被 LSPosed 注入 / 注入了 hook 全挂。
     * 本方法 root 直读 LSPosed 的配置文件把前者拆成三问：
     *   ① 模块启用了吗（modules.json 里海鸥条目 enabled）
     *   ② 作用域勾了吗（scope 里有没有高德/SystemUI/网易云）
     *   ③ 空气开关挡了吗（getprop persist.seagull.* —— 排查残留的 0 会
     *      静默跳过全部 hook，症状与「没注入」一模一样）
     * 三问全绿仍 ✗ 才是「注入了但 hook 失败」，届时才需要 LSPosed 日志。
     *
     * 线程要求：后台线程（每条 su 上限 3s）。无 root 返回提示不抛错。
     */
    public static String lspDiag(Context ctx) {
        StringBuilder sb = new StringBuilder("LSPosed 直查（root）:");
        if (!Caps.hasRoot()) return sb.append("\n  需 root（当前 su 不可用，改在 LSPosed 管理器里人工确认）").toString();

        // ① 海鸥属性：排除空气开关残留（persist.seagull.amap/wf=0 时 hook 全跳过）
        String props = Caps.exec("getprop | grep -i seagull");
        sb.append("\n  海鸥属性: ");
        if (props == null || props.trim().isEmpty()) {
            sb.append("(无 —— 开关全为默认值 ✓)");
        } else {
            sb.append(props.trim().replace("\n", "\n            "));
        }

        // ② LSPosed 配置目录
        String ls = Caps.exec("ls /data/adb/lspd/config/ 2>/dev/null");
        if (ls == null || ls.trim().isEmpty()) {
            return sb.append("\n  配置目录: /data/adb/lspd/config/ 读不到（SELinux 挡了或 LSPosed 未装）\n"
                    + "    → 在 LSPosed 管理器里人工确认：海鸥已启用 + 作用域含高德").toString();
        }
        sb.append("\n  配置文件: ").append(ls.trim().replace("\n", " "));

        // ③ 逐个 cat json，Java 侧解析海鸥条目（不同版本文件名/结构有差异，容错遍历）
        boolean found = false, enabled = false;
        String scopeText = null;
        for (String f : ls.trim().split("\n")) {
            if (!f.endsWith(".json")) continue;
            String body = Caps.exec("cat /data/adb/lspd/config/" + f.trim() + " 2>/dev/null");
            if (body == null || !body.contains("seagull")) continue;
            String[] r = parseModuleEntry(body);
            if (r != null) {
                found = true;
                enabled = Boolean.parseBoolean(r[0]);
                scopeText = r[1];
                break;
            }
        }
        if (!found) {
            return sb.append("\n  模块条目: 配置里没找到海鸥 → LSPosed 管理器模块列表里没有/未启用本模块\n"
                    + "    → 打开 LSPosed → 模块 → 启用海鸥桌面").toString();
        }
        sb.append("\n  模块启用: ").append(enabled ? "✓" : "✗（LSPosed 里开关没开）");
        sb.append("\n  作用域  : ").append(scopeText == null ? "(空)" : scopeText);
        String need = "";
        for (String[] p : new String[][]{
                {"高德", "com.autonavi.minimap"}, {"SystemUI", "com.android.systemui"},
                {"网易云", "com.netease.cloudmusic"}}) {
            if (scopeText != null && !scopeText.contains(p[1])) need += (need.isEmpty() ? "" : " ") + p[0];
        }
        if (!need.isEmpty()) sb.append("\n  缺作用域: ").append(need).append(" → LSPosed 海鸥详情页勾上");
        if (enabled && (need.isEmpty() && scopeText != null && scopeText.contains("com.autonavi.minimap"))) {
            sb.append("\n  结论    : 配置全绿 → 强杀高德重开；仍竖屏再看 LSPosed 日志搜 SeagullLsp");
        }
        return sb.toString();
    }

    /**
     * 在 LSPosed 配置 JSON 里找海鸥条目。结构随版本漂移，递归遍历所有对象：
     * 取 package/moduleName == 海鸥 的节点，返回 {enabled, scope 拼接}；找不到 null。
     */
    private static String[] parseModuleEntry(String body) {
        try {
            org.json.JSONTokener tk = new org.json.JSONTokener(body.trim());
            Object root = tk.nextValue();
            StringBuilder scope = new StringBuilder();
            Object[] hit = {null};
            walk(root, hit, scope);
            if (hit[0] == null) return null;
            Object en = hit[0];
            String enabled = String.valueOf(
                    en instanceof org.json.JSONObject ? ((org.json.JSONObject) en).optBoolean("enabled", false) : false);
            return new String[]{enabled, scope.length() == 0 ? null : scope.toString()};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 深度遍历 JSON：找到 package 为海鸥的 JSONObject 记下来，同节点读 scope 数组。 */
    private static void walk(Object o, Object[] hit, StringBuilder scope) {
        try {
            if (o instanceof org.json.JSONObject) {
                org.json.JSONObject jo = (org.json.JSONObject) o;
                String pkg = jo.optString("package", jo.optString("moduleName", ""));
                if (hit[0] == null && Constants.SELF_PACKAGE.equals(pkg)) {
                    hit[0] = jo;
                    Object sc = jo.opt("scope");
                    if (sc instanceof org.json.JSONArray) {
                        org.json.JSONArray arr = (org.json.JSONArray) sc;
                        for (int i = 0; i < arr.length(); i++) {
                            String s = arr.optString(i, "");
                            if (!s.isEmpty()) scope.append(s).append(' ');
                        }
                    }
                }
                java.util.Iterator<String> it = jo.keys();
                while (it.hasNext()) walk(jo.opt(it.next()), hit, scope);
            } else if (o instanceof org.json.JSONArray) {
                org.json.JSONArray ja = (org.json.JSONArray) o;
                for (int i = 0; i < ja.length(); i++) walk(ja.opt(i), hit, scope);
            }
        } catch (Throwable ignore) {}
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
