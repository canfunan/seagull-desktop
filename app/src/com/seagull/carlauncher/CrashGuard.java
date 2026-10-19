package com.seagull.carlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * O4 崩溃自恢复。uncaught 处理链：拼报告 → RootLog → files/crash/ 落档（10 份
 * 轮转）→ 同签名 24h 内累计 3 次置恢复标记 → 委托原处理器（保留系统终止语义）。
 * 下次启动 HomeActivity.onCreate 消费标记：清画布布屏键回落默认布局 + 一次性提示。
 * 处理链自身全程 try-catch：兜底器二次崩溃 = 什么都留不下。
 */
public final class CrashGuard {
    static final String K_SIG = "crash_sig";
    static final String K_RECOVER = "crash_recover";
    /** 触发阈值：24h 内同签名 3 次。 */
    static final int THRESHOLD = 3;
    static final long WINDOW_MS = 24L * 3600_000L;
    private static final int KEEP = 10;
    private static final String TAG = "CrashGuard";

    private CrashGuard() { }

    public static void install(Context c) {
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                String sig = signature(e);
                int count = bump(app, sig);
                String report = buildReport(app, t, e, sig, count);
                RootLog.w(TAG, report, e);
                writeCrashFile(app, report);
            } catch (Throwable ignore) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /* ==================== 纯函数（CrashSigCheck 覆盖） ==================== */

    /** 崩溃签名：异常类名 + 首帧（同因崩溃在车机上堆栈首帧稳定）。 */
    public static String signature(Throwable e) {
        if (e == null) return "?";
        StackTraceElement[] st = e.getStackTrace();
        String head = (st == null || st.length == 0) ? "?" : st[0].toString();
        return e.getClass().getName() + "#" + head;
    }

    /**
     * 窗口内计数推进：firstTs 超窗（24h）则清零重计；返回新计数。
     * firstTs<0 表示没有历史记录（新起计数）。
     */
    public static int nextCount(int count, long firstTs, long now) {
        if (firstTs < 0 || now - firstTs > WINDOW_MS || count <= 0) return 1;
        return count + 1;
    }

    /** 是否触发恢复标记（计数达标即置位；置位后启动侧消费）。 */
    public static boolean shouldRecover(int count) { return count >= THRESHOLD; }

    /* ==================== 落档与恢复 ==================== */

    /** 计数进 prefs，返回推进后的计数；达标置 crash_recover。 */
    static int bump(Context c, String sig) {
        try {
            SharedPreferences sp = c.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
            JSONObject o = new JSONObject(sp.getString(K_SIG, "{}"));
            long now = System.currentTimeMillis();
            int count = nextCount(o.optInt("count", 0),
                    o.optString("sig", "").equals(sig) ? o.optLong("firstTs", -1) : -1, now);
            JSONObject n = new JSONObject();
            n.put("sig", sig);
            n.put("count", count);
            n.put("firstTs", now);
            sp.edit().putString(K_SIG, n.toString()).apply();
            if (shouldRecover(count)) sp.edit().putBoolean(K_RECOVER, true).apply();
            return count;
        } catch (Throwable t) {
            return 0;
        }
    }

    static String buildReport(Context c, Thread t, Throwable e, String sig, int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("time=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        sb.append("thread=").append(t == null ? "?" : t.getName()).append('\n');
        sb.append("version=").append(version(c)).append('\n');
        sb.append("count24h=").append(count).append('\n');
        sb.append("sig=").append(sig).append('\n');
        sb.append(android.util.Log.getStackTraceString(e == null ? new Throwable("?") : e));
        return sb.toString();
    }

    private static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** files/crash/crash-<ts>.txt，10 份轮转；失败静默（RootLog 已有底）。 */
    private static void writeCrashFile(Context c, String report) {
        try {
            File dir = new File(c.getFilesDir(), "crash");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File f = new File(dir, "crash-" + System.currentTimeMillis() + ".txt");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(report.getBytes(StandardCharsets.UTF_8));
            }
            File[] fs = dir.listFiles();
            if (fs != null && fs.length > KEEP) {
                java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                int over = fs.length - KEEP;
                for (int i = 0; i < fs.length && over > 0; i++) {
                    if (fs[i].isFile() && fs[i].delete()) over--;
                }
            }
        } catch (Throwable ignore) { }
    }

    /**
     * 启动侧消费恢复标记（HomeActivity.onCreate 调，UI 建立前）。
     * 命中：清标记 + 摘画布布屏键（mirror_pkg1/2，PipBoard.loadPkg 的数据源）
     * → 画布回落默认占位布局。返回是否命中（给一次性 toast）。
     */
    public static boolean consumeRecovery(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
            if (!sp.getBoolean(K_RECOVER, false)) return false;
            sp.edit()
                    .putBoolean(K_RECOVER, false)
                    .putString("mirror_pkg1", "")
                    .putString("mirror_pkg2", "")
                    .apply();
            RootLog.i(TAG, "连续崩溃恢复：画布已回落默认布局");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
