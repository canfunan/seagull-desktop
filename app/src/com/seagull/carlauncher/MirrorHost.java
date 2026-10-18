package com.seagull.carlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * 画中画槽的进程级持有者（批次 L）。
 *
 * 为什么需要它：VD 一旦释放，系统会把屏上的任务倒回默认屏 —— 桌面立刻冒出
 * 全屏应用，这正是用户说的「每次进入桌面还是应用界面」。所以 VD 的生命周期
 * 挂在进程上而不是 MirrorActivity 上：退出画中画页只断 Surface，屏与目标
 * 应用都留着，再进来把 Surface 挂回同一块 VD，画面秒恢复。
 *
 * 清空某槽（长按 → 清空该槽）走 {@link #clear}：拆屏 + 停掉画中画里的应用
 * + 忘掉绑定，这是唯一的拆除入口。
 */
public final class MirrorHost {

    private static final String TAG = "SeagullMirrorHost";
    private static final String PREFS = "seagull";
    private static final long HEAL_MIN_INTERVAL_MS = 3000;

    private static final Map<String, MirrorSlot> SLOTS = new HashMap<>();
    private static long lastHeal;
    private static final long RELAUNCH_MIN_INTERVAL_MS = 60_000;
    /** 桌面自愈与画布自愈两条线程都会写：加锁，long 撕裂 + 重复重拉一起防。 */
    private static final Object RELAUNCH_LOCK = new Object();
    private static final long[] lastRelaunch = new long[3];

    private MirrorHost() {}

    /** 取（或建）进程级槽位；ctx 存 applicationContext，不泄漏 Activity。 */
    public static synchronized MirrorSlot slot(Context ctx, String name) {
        MirrorSlot s = SLOTS.get(name);
        if (s == null) {
            s = new MirrorSlot(ctx.getApplicationContext(), name);
            SLOTS.put(name, s);
        }
        return s;
    }

    static synchronized MirrorSlot slot(int which) {
        return SLOTS.get(which == 1 ? "pip1" : "pip2");
    }

    /** 清空某槽：拆屏 + 停掉画中画里的应用 + 忘掉绑定。 */
    public static void clear(Context ctx, int which) {
        if (which < 1 || which > 2) return;
        MirrorSlot s = slot(which);
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String pkg = sp.getString("mirror_pkg" + which, "");
        if (s != null) s.teardown();
        sp.edit().putString("mirror_pkg" + which, "").apply();
        if (pkg != null && !pkg.isEmpty()) {
            final String p = pkg;
            new Thread(() -> {
                Caps.exec("am force-stop " + p);
                RootOps.resetCompat(p);   // 清空槽 = 还原兼容设置，别污染主屏行为
            }, "pip-clear" + which).start();
        }
        Log.i(TAG, "清空槽 " + which + "（" + pkg + "）");
    }

    /**
     * 录屏被系统撤销：只拆真正由投影建出的屏（批次 R 起私有屏/TRUSTED 屏与
     * 投影无关，继续用）；投影屏的 VD 已死，拆掉免得屏上任务倒回主屏冒全屏。
     */
    public static synchronized void onProjectionStopped(Context ctx) {
        for (int i = 1; i <= 2; i++) {
            MirrorSlot s = slot(i);
            if (s == null || !s.projectionBased()) continue;
            if (s.displayId() > 0) {
                Log.w(TAG, "槽 " + i + " 是投影屏且投影已停，拆屏");
                s.teardown();
            }
        }
        PipProjectionService.stop(ctx);
    }

    /**
     * 桌面 onResume 自愈（批次 L）：常驻画中画被系统拉回主屏时搬回去。
     * 保守：解析不出栈结构就不动（见 RootOps.ensureOnDisplay）。带节流 ——
     * 桌面 resume 很频繁，别把 su 打爆。
     */
    public static void healHome(Context ctx) {
        if (MirrorSlot.activeCount() == 0) return;
        long now = System.currentTimeMillis();
        if (now - lastHeal < HEAL_MIN_INTERVAL_MS) return;
        lastHeal = now;
        for (int i = 1; i <= 2; i++) {
            final MirrorSlot s = slot(i);
            if (s == null || s.displayId() <= 0) continue;
            SharedPreferences sp =
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            final String pkg = sp.getString("mirror_pkg" + i, "");
            if (pkg == null || pkg.isEmpty()) continue;
            final int slotNo = i;
            new Thread(() -> {
                String out = RootOps.ensureOnDisplay(ctx.getApplicationContext(), pkg, s.displayId());
                if (out != null && out.startsWith(RootOps.ABSENT)) {
                    relaunchIfAbsent(ctx, slotNo, pkg, s.displayId());
                }
                Log.i(TAG, "桌面自愈 槽 " + slotNo + ": " + out);
            }, "pip-heal" + i).start();
        }
    }

    /**
     * 应用缺席的节流重拉（批次 R）：ensureOnDisplay 只搬不拉，应用在画中画里被
     * 用户按返回退出（或崩溃）后画布黑到天荒地老。这里补"拉"：
     * 60s 节流 —— 应用真崩了也别连环 force-stop（m12 重拉风暴的教训反过来用）。
     * RootOps.ABSENT 只在栈结构解析成功时返回，解析失败绝不会走到这（红线保持）。
     */
    public static void relaunchIfAbsent(Context ctx, int which, String pkg, int displayId) {
        relaunchIfAbsent(ctx, which, pkg, displayId, false);
    }

    /**
     * force=true 的重拉（批次 AA）：给「用户主动进入该槽」的路径用 —— 点画布/键条
     * 是显式意图，被 60s 节流挡掉就是用户报的「返回桌面后点不回去（不能重新挂载）」；
     * 而后台自愈（selfHeal/healHome）仍走节流，防连环 force-stop。
     */
    public static void relaunchIfAbsent(Context ctx, int which, String pkg, int displayId,
                                        boolean force) {
        if (pkg == null || pkg.isEmpty() || displayId <= 0 || which < 1 || which > 2) return;
        long now = SystemClock.elapsedRealtime();
        synchronized (RELAUNCH_LOCK) {
            if (!force && now - lastRelaunch[which] < RELAUNCH_MIN_INTERVAL_MS) {
                Log.i(TAG, "槽 " + which + " 应用缺席，重拉节流中");
                return;
            }
            lastRelaunch[which] = now;
        }
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            Log.w(TAG, "槽 " + which + " 应用缺席 → 重拉" + (force ? "(用户触发)" : "")
                    + " " + pkg + " -> d" + displayId);
            RootOps.launchOnDisplay(app, pkg, displayId);
        }, "pip-relaunch" + which).start();
    }
}
