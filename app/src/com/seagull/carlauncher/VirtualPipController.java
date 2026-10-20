package com.seagull.carlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 虚拟屏画中画（批次 U → 批次 V 修正）。
 *
 * 批次 V 修正（真机「还是不行」的两处根因）：
 *  ① 建屏主体。批次 U 在【应用进程内】用 PUBLIC 位建屏 —— 普通 uid 建的屏不受信，
 *     目标应用上不去（ActivityStarter 拒绝非属主上非信任屏）。本仓库已验证的路是
 *     【root 守护进程 uid 0】建 TRUSTED+PUBLIC 屏（RootMain CREATE，MirrorSlot 在用），
 *     这里改为：先走 PrivClient.create，失败再降回本地建屏（保留批次 U 行为）。
 *  ② Surface 挂接。守护进程建的屏没有 VirtualDisplay 对象可调 setSurface ——
 *     必须 PrivClient.surface(displayId, surface)（Parcel+Base64 行协议）。
 *     本地屏则照旧 vd.setSurface()。attachSurface() 按建屏路径自动分流。
 *
 * 分辨率取「非默认屏里最大那块」的物理分辨率（车机副屏），取不到回退 1280x720。
 * 拉伸发生在悬浮窗视图层，VD 分辨率全程不变，保证清晰度与坐标映射稳定。
 */
public final class VirtualPipController {

    private static final String TAG = "SeagullVdPip";
    public static final String DEF_PKG = "com.autonavi.amapauto";

    private static VirtualDisplay sVd;      // 本地建屏路径才有值
    private static int sDisplayId = -1;     // 两条路径统一用 displayId
    private static int sW;
    private static int sH;
    private static String sPkg;
    private static boolean sRooted;         // true = 守护进程 TRUSTED 屏

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile String sAttachErr;   // 最近一次 Surface 挂接结果（null=成功）

    private VirtualPipController() {}

    public static synchronized int displayId() { return sDisplayId; }

    public static synchronized int width() { return sW; }

    public static synchronized int height() { return sH; }

    public static synchronized String pkg() { return sPkg; }

    /** true = 走 root 守护进程 TRUSTED 屏（可用路）；false = 本地屏（部分 ROM 上不去应用）。 */
    public static synchronized boolean isRootDisplay() { return sRooted; }

    /** 创建（幂等）。返回 displayId，-1 = 失败，调用方负责提示。 */
    public static synchronized int create(Context ctx, String pkg) {
        if (sDisplayId >= 0) return sDisplayId;
        int[] wh = pickSize(ctx);

        // ① 首选：root 守护进程建 TRUSTED+PUBLIC 屏（与 MirrorSlot 同一条已验证路）
        try {
            PrivClient.init(ctx.getApplicationContext());
            PrivClient.Created c = PrivClient.create(wh[0], wh[1], wh[2]);
            if (c != null && c.displayId >= 0) {
                sDisplayId = c.displayId;
                sW = wh[0];
                sH = wh[1];
                sPkg = pkg;
                sRooted = true;
                Log.i(TAG, "root 屏 VD#" + sDisplayId + " " + sW + "x" + sH
                        + " trusted=" + c.trusted + " for " + pkg);
                return sDisplayId;
            }
        } catch (Throwable t) {
            Log.w(TAG, "root 建屏失败，降回本地", t);
        }

        // ② 降级：本地 PUBLIC 屏（保留批次 U 行为；部分 ROM 可用）
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            VirtualDisplay vd = dm.createVirtualDisplay("seagull_pip", wh[0], wh[1], wh[2],
                    null, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC);
            if (vd == null) return -1;
            sVd = vd;
            sDisplayId = vd.getDisplay().getDisplayId();
            sW = wh[0];
            sH = wh[1];
            sPkg = pkg;
            sRooted = false;
            Log.i(TAG, "本地屏 VD#" + sDisplayId + " " + sW + "x" + sH + " for " + pkg);
            return sDisplayId;
        } catch (Throwable t) {
            Log.w(TAG, "createVirtualDisplay 失败", t);
            return -1;
        }
    }

    /** 最近一次 Surface 挂接错误（null=成功）。黑屏二分用：屏内容有 ↔ Surface 没接上。 */
    public static synchronized String attachErr() { return sAttachErr; }

    /**
     * Surface 挂接（两条路径自动分流）。
     * root 屏走 PrivClient.surface —— 会过线协议，必须放后台线程；结果记入 sAttachErr，
     * 静默失败是黑屏排查的大坑（批次 V）。
     * 本地屏直接 setSurface，主线程安全。
     */
    public static synchronized void attachSurface(final Surface s) {
        if (sDisplayId < 0) return;
        if (sRooted) {
            IO.execute(() -> {
                try {
                    boolean ok = PrivClient.surface(sDisplayId, s);
                    sAttachErr = ok ? null : "Surface 挂接被守护进程拒绝";
                    Log.i(TAG, "surface attach ok=" + ok + " s=" + (s != null));
                } catch (Throwable t) {
                    sAttachErr = "Surface 挂接异常: " + t;
                    Log.w(TAG, "surface attach 异常", t);
                }
            });
        } else if (sVd != null) {
            try {
                sVd.setSurface(s);
                sAttachErr = null;
            } catch (Throwable t) {
                sAttachErr = "setSurface 失败: " + t;
                Log.w(TAG, "setSurface 失败", t);
            }
        }
    }

    /**
     * 把高德拉进虚拟屏。先 force-stop 保证落屏（活着的进程不会自己换屏），
     * 再启动：root 屏优先 PrivClient.launch（daemon setLaunchDisplayId，
     * MirrorSlot 同款），失败退回 am start --display。
     * launchable 组件名由 PackageManager 解析，
     * 车机版没有 LAUNCHER 类目时取清单里第一个 activity 兜底。
     */
    public static synchronized String launchAmap(Context ctx) {
        if (sDisplayId < 0) return "虚拟屏未创建";
        String pkg = sPkg != null ? sPkg : DEF_PKG;
        String comp = resolveComponent(ctx, pkg);
        if (comp == null) return "解析不到 " + pkg + " 的入口 Activity";

        Caps.exec("am force-stop " + pkg + " 2>&1");

        if (sRooted) {
            try {
                if (PrivClient.launch(sDisplayId, comp, RootOps.LAUNCH_FLAGS_INT)) {
                    Log.i(TAG, "daemon launch VD#" + sDisplayId + " -> " + comp);
                    return null;
                }
                Log.w(TAG, "daemon launch 失败，退回 am start");
            } catch (Throwable t) {
                Log.w(TAG, "daemon launch 异常", t);
            }
        }

        String out = Caps.exec("am start --display " + sDisplayId + " -n " + comp + " 2>&1");
        if (out == null) return "am start 失败（root 通道超时）";
        Log.i(TAG, "am start out=" + out.replace('\n', ' '));
        String bad = amStartError(out);
        return bad != null ? "am start: " + bad : null;
    }

    /** am start 输出校验：不再把 "Error" 当成功（黑屏排查时这种假成功最坑）。 */
    private static String amStartError(String out) {
        String[] lines = out.split("\n");
        for (String l : lines) {
            String t = l.trim();
            String low = t.toLowerCase(java.util.Locale.ROOT);
            if (low.startsWith("error") || low.contains("exception")
                    || low.contains("permission denial") || low.contains("not started")) {
                return t.length() > 120 ? t.substring(0, 120) : t;
            }
        }
        return null;
    }

    /**
     * 黑屏二分探针：对 VD 直接 root 截屏，量 PNG 体积。
     * 黑屏图高度可压缩（1280x720 纯黑约几 KB），有真实内容在 50KB 以上。
     * 返回一句话结论，直接进悬浮窗状态行 —— 不用抓日志就能分清
     * 「任务没落屏」（屏黑）还是「落了屏但 Surface 没接上」（屏有内容、窗口黑）。
     */
    public static String probeContent() {
        int id;
        synchronized (VirtualPipController.class) {
            id = sDisplayId;
        }
        if (id < 0) return "屏未建";
        String out = Caps.exec("screencap -d " + id
                + " -p /data/local/tmp/seagull_vd.png 2>&1; wc -c < /data/local/tmp/seagull_vd.png 2>/dev/null; rm -f /data/local/tmp/seagull_vd.png");
        if (out == null) return "探测超时";
        String[] lines = out.trim().split("\n");
        String num = lines[lines.length - 1].trim();
        try {
            long bytes = Long.parseLong(num);
            if (bytes <= 0) return "截屏失败(" + (lines.length > 1 ? lines[0].trim() : "空文件") + ")";
            return bytes < 50_000L
                    ? "屏黑(" + (bytes / 1024) + "KB)→高德没落屏"
                    : "屏有内容(" + (bytes / 1024) + "KB)";
        } catch (NumberFormatException e) {
            String first = lines[0].trim();
            return "截屏不支持: " + (first.length() > 50 ? first.substring(0, 50) : first);
        }
    }

    private static String resolveComponent(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent li = pm.getLaunchIntentForPackage(pkg);
            if (li != null && li.getComponent() != null) {
                return li.getComponent().flattenToString();
            }
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            if (pi.activities != null && pi.activities.length > 0) {
                return pi.activities[0].name.startsWith(".")
                        ? pkg + pi.activities[0].name : pi.activities[0].name;
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolveComponent 失败", t);
        }
        return null;
    }

    /** 副屏物理分辨率：非默认屏里面积最大的一块；没有就 1280x720@160。 */
    private static int[] pickSize(Context ctx) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display best = null;
            long area = 0;
            for (Display d : dm.getDisplays()) {
                if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
                DisplayMetrics m = new DisplayMetrics();
                d.getRealMetrics(m);
                long a = (long) m.widthPixels * m.heightPixels;
                if (a > area) {
                    area = a;
                    best = d;
                }
            }
            if (best != null) {
                DisplayMetrics m = new DisplayMetrics();
                best.getRealMetrics(m);
                if (m.widthPixels > 0 && m.heightPixels > 0) {
                    return new int[]{m.widthPixels, m.heightPixels, m.densityDpi};
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "pickSize 回退", t);
        }
        return new int[]{1280, 720, 160};
    }

    public static synchronized void release() {
        if (sDisplayId < 0) return;
        if (sRooted) {
            final int id = sDisplayId;
            IO.execute(() -> PrivClient.release(id));
        } else if (sVd != null) {
            try {
                sVd.release();
            } catch (Throwable ignored) {
            }
        }
        sVd = null;
        sDisplayId = -1;
        Log.i(TAG, "VD released");
    }
}
