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

/**
 * 虚拟屏画中画（批次 U）：海鸥自建 VirtualDisplay，root 把高德拉进来渲染。
 * 不 hook、不依赖 LSPosed，高德升级零影响。规格见
 * .monkeycode/specs/virtual-display-pip/。
 *
 * 分辨率取「非默认屏里最大那块」的物理分辨率（车机副屏），取不到回退 1280x720。
 * 拉伸发生在悬浮窗视图层，VD 分辨率全程不变，保证清晰度与坐标映射稳定。
 */
public final class VirtualPipController {

    private static final String TAG = "SeagullVdPip";
    public static final String DEF_PKG = "com.autonavi.amapauto";

    private static VirtualDisplay sVd;
    private static int sW;
    private static int sH;
    private static String sPkg;

    private VirtualPipController() {}

    public static synchronized VirtualDisplay get() { return sVd; }

    public static synchronized int width() { return sW; }

    public static synchronized int height() { return sH; }

    public static synchronized String pkg() { return sPkg; }

    /** 创建（幂等）。返回 null = 失败，调用方负责提示。 */
    public static synchronized VirtualDisplay create(Context ctx, String pkg) {
        if (sVd != null) return sVd;
        int[] wh = pickSize(ctx);
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            sVd = dm.createVirtualDisplay("seagull_pip", wh[0], wh[1], wh[2], null,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC);
        } catch (Throwable t) {
            Log.w(TAG, "createVirtualDisplay 失败", t);
            return null;
        }
        if (sVd == null) return null;
        sW = wh[0];
        sH = wh[1];
        sPkg = pkg;
        Log.i(TAG, "VD#" + sVd.getDisplay().getDisplayId() + " " + sW + "x" + sH + " for " + pkg);
        return sVd;
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

    /**
     * 把高德拉进虚拟屏。先 force-stop 保证落屏（活着的进程不会自己换屏），
     * 再 am start --display。launchable 组件名由 PackageManager 解析，
     * 车机版没有 LAUNCHER 类目时取清单里第一个 activity 兜底。
     */
    public static synchronized String launchAmap(Context ctx) {
        if (sVd == null) return "虚拟屏未创建";
        String pkg = sPkg != null ? sPkg : DEF_PKG;
        String comp = resolveComponent(ctx, pkg);
        if (comp == null) return "解析不到 " + pkg + " 的入口 Activity";
        int id = sVd.getDisplay().getDisplayId();
        String out = Caps.exec("am force-stop " + pkg + " 2>&1; am start --display " + id
                + " -n " + comp + " 2>&1");
        if (out == null) return "am start 失败（root 通道超时）";
        Log.i(TAG, "launchAmap out=" + out.replace('\n', ' '));
        return null;
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

    public static synchronized void release() {
        if (sVd != null) {
            try {
                sVd.release();
            } catch (Throwable ignored) {
            }
            sVd = null;
            Log.i(TAG, "VD released");
        }
    }
}
