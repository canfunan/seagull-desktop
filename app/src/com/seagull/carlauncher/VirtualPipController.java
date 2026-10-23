package com.seagull.carlauncher;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.util.Log;
import android.view.Surface;

/**
 * 虚拟屏画中画（批次 W —— 一比一复刻车联助手 3.0.0 的 VD 画中画管线）。
 *
 * 对应关系（反编译源见 docs/CARWITH-ANALYSIS.md）：
 *   车联 b1/b.surfaceCreated   → create()：应用进程内 flags=0 私有屏，surface 建屏时直传
 *                                （同进程零协议 —— 批次 V 的跨进程行协议整条退场）
 *   车联 b1/b.surfaceChanged   → surfaceChanged()：vd.resize(视图宽高) —— 屏=窗口，
 *                                永不黑边、触摸零换算（车联无黑边的根本原因）
 *   车联 b1/b.surfaceDestroyed → surfaceDestroyed()：只摘 surface 不拆屏
 *   车联 c1/h.d                → anchorAndMove()：锚点占 root task + root am stack move-task
 *   车联 c1/h.b                → directStart() / amStartDisplay()
 *   车联 MainActivity.onResume → redeploy()：回桌面重跑锚点+搬任务（被拉回主屏的自愈）
 *   车联 b1/d + MainActivity   → 触摸/返回仍走 input -d（PipOverlayService.inject 不变）
 *
 * 黑屏三根因的对应修法：
 *   ① 落屏改「锚点+move-task」，绕开 Android 14 对第三方应用直启 display 的静默重定向；
 *   ② Surface 同进程直调，不再跨进程；
 *   ③ VD 尺寸跟随窗口 resize，不再固定 1280x720。
 */
public final class VirtualPipController {

    private static final String TAG = "SeagullVdPip";
    public static final String DEF_PKG = "com.autonavi.amapauto";

    /**
     * 推荐应用（复刻车联助手的「推荐应用」模型）。
     * 顺序即自动探测优先级：手机版高德优先 —— 车联助手的画中画目标就是手机版
     * 高德（其 xposed_scope 第一批就是 com.autonavi.minimap）；车机版 amapauto
     * 未必装（批次 W 教训：写死车机版，用户没装 → 每次必败「解析不到入口」）。
     */
    public static final String[] RECOMMEND = {
            "com.autonavi.minimap",     // 高德地图（手机版）
            "com.autonavi.amapauto",    // 高德地图（车机版）
            "com.netease.cloudmusic",   // 网易云音乐
    };

    private static String sTargetPkg;   // 用户在推荐应用里点选的（null = 自动探测）
    private static boolean sEnabled;    // 画中画开关（桌面里嵌不嵌 VdPipView）
    private static boolean sTargetLoaded;   // NB21：点选目标持久化懒加载标记

    /** 车联助手锚点启动 flags 原值 411041792 = NEW_TASK | NEW_DOCUMENT | EXCLUDE_FROM_RECENTS。 */
    private static final int ANCHOR_FLAGS = 411041792;
    /** 车联助手 h.b 的 am start flags：0x10104000 = NEW_TASK | RESET_TASK_IF_NEEDED | TASK_ON_HOME。 */
    private static final int AM_START_FLAGS = 0x10104000;

    private static VirtualDisplay sVd;
    private static int sDisplayId = -1;
    private static int sW;
    private static int sH;
    private static int sDpi;
    private static String sPkg;
    private static String sDeployPath = "未部署";

    private VirtualPipController() {}

    public static synchronized int displayId() { return sDisplayId; }

    public static synchronized int width() { return sW; }

    public static synchronized int height() { return sH; }

    public static synchronized String pkg() { return sPkg; }

    public static synchronized String deployPath() { return sDeployPath; }

    /** 桌面 onResume 自愈判据：屏活着才值得重搬。 */
    public static synchronized boolean isActive() { return sDisplayId >= 0; }

    /** 推荐应用点选（RootPanel 用）。传 null = 回到自动探测。 */
    public static synchronized void setTarget(Context ctx, String pkg) {
        sTargetPkg = pkg;
        sTargetLoaded = true;
        // NB21：点选持久化 —— 之前是纯内存态，进程被杀（后台清理）后丢用户点选，
        // 冷启回落自动探测（变成手机版高德），画中画目标悄悄漂移。
        try {
            android.content.SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences("seagull", Context.MODE_PRIVATE);
            if (pkg == null) sp.edit().remove("pipTarget").apply();
            else sp.edit().putString("pipTarget", pkg).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 画中画开关：桌面按它决定嵌不嵌 VdPipView（车联同款：pip 是 Activity 内视图）。
     * NB18：持久化到 prefs —— 之前是纯内存态，进程被杀（后台清理/车机回收）后
     * 开关静默归零，桌面重开时 syncVdPip 判 false 不挂载，用户只能到 root 面板
     * 重新「删除再开启」才能挂回（用户实测原话）。现在冷启懒加载 prefs 自愈。
     */
    private static boolean sLoaded;

    public static synchronized boolean isEnabled(Context ctx) {
        if (!sLoaded) {
            try {
                sEnabled = ctx.getApplicationContext()
                        .getSharedPreferences("seagull", Context.MODE_PRIVATE)
                        .getBoolean("pipEnabled", false);
            } catch (Throwable ignored) {
            }
            sLoaded = true;
        }
        return sEnabled;
    }

    public static synchronized void setEnabled(Context ctx, boolean e) {
        sEnabled = e;
        sLoaded = true;
        try {
            ctx.getApplicationContext()
                    .getSharedPreferences("seagull", Context.MODE_PRIVATE)
                    .edit().putBoolean("pipEnabled", e).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 目标应用：用户点选 > 手机版高德 > 车机版高德；都没装返回 null。
     * （批次 W 教训：写死车机版 —— 用户没装 amapauto 时解析不到入口，每次必败。）
     */
    public static String pickTarget(Context ctx) {
        synchronized (VirtualPipController.class) {
            if (!sTargetLoaded) {
                // NB21：冷启懒加载持久化的用户点选（进程被杀后不失忆）
                try {
                    sTargetPkg = ctx.getApplicationContext()
                            .getSharedPreferences("seagull", Context.MODE_PRIVATE)
                            .getString("pipTarget", null);
                } catch (Throwable ignored) {
                }
                sTargetLoaded = true;
            }
            if (sTargetPkg != null) return sTargetPkg;
        }
        for (String p : RECOMMEND) {
            try {
                if (ctx.getPackageManager().getLaunchIntentForPackage(p) != null) return p;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /* ============ 建屏（复刻 b1/b.surfaceCreated） ============ */

    /**
     * 应用进程内建 flags=0 私有屏，surface 建屏时直接传入（车联原文如此）。
     * w/h = SurfaceView 视图尺寸，dpi = 主屏 densityDpi。
     * 返回 displayId，-1 = 失败。
     */
    public static synchronized int create(Context ctx, String pkg, Surface sf,
                                          int w, int h, int dpi) {
        release();
        if (sf == null || w <= 0 || h <= 0) return -1;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            VirtualDisplay vd = dm.createVirtualDisplay("seagull-pip", w, h, dpi, sf, 0, null, null);
            if (vd == null) return -1;
            sVd = vd;
            sDisplayId = vd.getDisplay().getDisplayId();
            sW = w;
            sH = h;
            sDpi = dpi;
            sPkg = pkg;
            sDeployPath = "未部署";
            Log.i(TAG, "VD#" + sDisplayId + " " + w + "x" + h + "@" + dpi
                    + " 私有屏 created for " + pkg);
            return sDisplayId;
        } catch (Throwable t) {
            Log.w(TAG, "createVirtualDisplay 失败", t);
            return -1;
        }
    }

    /** 复刻 b1/b.surfaceChanged：窗口尺寸变了屏直接跟着变。 */
    public static synchronized void surfaceChanged(Surface sf, int w, int h) {
        if (sVd == null || sf == null || w <= 0 || h <= 0) return;
        try {
            sVd.resize(w, h, sDpi);
            sVd.setSurface(sf);
            sW = w;
            sH = h;
        } catch (Throwable t) {
            Log.w(TAG, "resize/setSurface 失败", t);
        }
    }

    /** 复刻 b1/b.surfaceDestroyed：只摘 surface，屏保留（车联原文如此）。 */
    public static synchronized void surfaceDestroyed() {
        if (sVd == null) return;
        try {
            sVd.setSurface(null);
        } catch (Throwable ignored) {
        }
    }

    /* ============ 落屏（复刻 c1/h.d → h.e → h.b） ============ */

    /**
     * 落屏链（车联 deploy 顺序）：锚点+move-task → 直启 → root am start → daemon 直启。
     * 返回走通的那条路名；全败返回错误串。
     */
    public static String deploy(Context ctx) {
        final String pkg;
        final int id;
        synchronized (VirtualPipController.class) {
            pkg = sPkg;
            id = sDisplayId;
        }
        if (id < 0 || pkg == null) return "虚拟屏未创建";
        String comp = resolveComponent(ctx, pkg);
        if (comp == null) return "解析不到 " + pkg + " 的入口 Activity";
        // NB21：id/pkg 全程用开头的 final 快照传参，部署链不再直读静态字段
        // （redeploy 线程与 boot 线程并发时，读到被 release() 清零的 sDisplayId）。
        if (anchorAndMove(ctx, pkg, id)) return "锚点+move-task";
        if (directStart(ctx, pkg, id)) return "直启";
        if (amStartDisplay(id, comp)) return "root am start";
        // 车联没有的最后一层保险：daemon setLaunchDisplayId（批次 V 路径， MirrorSlot 同款）
        try {
            if (PrivClient.launch(id, comp, RootOps.LAUNCH_FLAGS_INT)) return "daemon直启";
        } catch (Throwable t) {
            Log.w(TAG, "daemon launch 异常", t);
        }
        return "落屏失败（四条路全败）";
    }

    /**
     * 复刻 c1/h.d，两步：
     * ① 自家锚点 Activity（独立 taskAffinity → 私有屏上必然成 root task）；
     * ② root 一行流：am stack list 找目标任务 taskId + 锚点所在 root task id → move-task。
     *
     * 脚本逐字取自车联助手 smali（仅 PipAnchorActivity → VdPipAnchorActivity）；
     * 其 ROOT 解析只认旧格式「^Stack id=」，Android 12+ 是「rootTask id=」，
     * 失败时用补丁版脚本再试一次（兼容两种格式 + 宽松 id 提取）。
     * 逐字保留「不杀目标应用」：搬的是活任务（车联原文如此）。
     */
    private static boolean anchorAndMove(Context ctx, String pkg, int displayId) {
        try {
            Intent i = new Intent(ctx, VdPipAnchorActivity.class);
            i.setFlags(ANCHOR_FLAGS);
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(displayId);
            ctx.startActivity(i, o.toBundle());
        } catch (Throwable t) {
            Log.w(TAG, "锚点创建失败", t);
        }
        int code = suExec(legacyScript(pkg));
        if (code == 0) return true;
        Log.w(TAG, "车联原版脚本 exit=" + code + "，试新格式补丁");
        return suExec(modernScript(pkg)) == 0;
    }

    /** 车联助手 h.d 脚本逐字（变量名不变；DID 车联里也是死变量，略）。 */
    private static String legacyScript(String pkg) {
        return "sleep 0.15; OUT=\"$(am stack list 2>/dev/null)\"; PKG='" + pkg + "'; "
                + "TASK=\"$(printf '%s\\n' \"$OUT\" | awk -v p=\"$PKG\" 'index($0,p) && /taskId=/"
                + "{x=$0; sub(/.*taskId=/,\"\",x); sub(/:.*/,\"\",x); print x; exit}')\"; "
                + "ROOT=\"$(printf '%s\\n' \"$OUT\" | awk '/^Stack id=/{s=$0; sub(/^Stack id=/,\"\",s); "
                + "sub(/ .*/,\"\",s)} /VdPipAnchorActivity/{print s; exit}')\"; "
                + "if [ -n \"$TASK\" ] && [ -n \"$ROOT\" ]; then "
                + "am stack move-task \"$TASK\" \"$ROOT\" true >/dev/null 2>&1; else exit 41; fi";
    }

    /** Android 12+ 补丁：rootTask id= 兼容 + taskId/task id 都能提取。 */
    private static String modernScript(String pkg) {
        return "sleep 0.15; OUT=\"$(am stack list 2>/dev/null)\"; PKG='" + pkg + "'; "
                + "TASK=\"$(printf '%s\\n' \"$OUT\" | awk -v p=\"$PKG\" 'index($0,p) && "
                + "match($0,/id=[0-9]+/){print substr($0,RSTART+3,RLENGTH-3); exit}')\"; "
                + "ROOT=\"$(printf '%s\\n' \"$OUT\" | awk '/(Stack|rootTask) id=/{s=$0; "
                + "sub(/.*rootTask id=/,\"\",s); sub(/.*Stack id=/,\"\",s); sub(/ .*/,\"\",s)} "
                + "/VdPipAnchorActivity/{print s; exit}')\"; "
                + "if [ -n \"$TASK\" ] && [ -n \"$ROOT\" ]; then "
                + "am stack move-task \"$TASK\" \"$ROOT\" true >/dev/null 2>&1; else exit 41; fi";
    }

    /** 复刻 c1/h.b 第一步：getLaunchIntentForPackage + setLaunchDisplayId 直启。 */
    private static boolean directStart(Context ctx, String pkg, int displayId) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.setFlags(269500416);   // NEW_TASK | RESET_TASK_IF_NEEDED（车联原值）
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(displayId);
            ctx.startActivity(i, o.toBundle());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "直启失败（Android API denied）", t);
            return false;
        }
    }

    /** 复刻 c1/h.b 第二步：root am start --display（车联 flags 原值）。 */
    private static boolean amStartDisplay(int id, String comp) {
        return suExec("am start --display " + id + " -f " + AM_START_FLAGS + " -n " + comp) == 0;
    }

    /** 车联同款：Runtime.exec 数组形式 su -c，waitFor 拿退出码（不经 sh 引号转义地狱）。
     *  NB21：加 8s 超时 —— 之前无超时，su 授权弹窗无人点时 waitFor 永远阻塞，
     *  boot/redeploy 线程卡死，画中画状态永远停在「部署中…」（体感就是"没生效"）。 */
    private static int suExec(String script) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", script});
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                Log.w(TAG, "su 超时（8s，多半是授权弹窗没人点）");
                return -1;
            }
            return p.exitValue();
        } catch (Throwable t) {
            Log.w(TAG, "su 执行失败", t);
            return -1;
        }
    }

    /* ============ 自愈（复刻车联 MainActivity.onResume → c.h()） ============ */

    /**
     * 桌面 onResume 调用：任务被系统拉回主屏后，回桌面即重跑锚点+move-task 搬回。
     * 幂等（搬成功的任务 id 不变）；屏没建时直接空转。
     */
    public static void redeploy(Context ctx) {
        if (!isActive()) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            String r = deploy(app);
            Log.i(TAG, "redeploy → " + r);
        }, "seagull-vd-redeploy").start();
    }

    /* ============ 黑屏二分探针（批次 V，保留） ============ */

    /**
     * 对 VD 直接 root 截屏，量 PNG 体积：纯黑几 KB，有真实内容 >50KB。
     * 结论直接进悬浮窗状态行 —— 分清「任务没落屏」还是「Surface 没接上」。
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

    public static synchronized void release() {
        if (sDisplayId < 0 && sVd == null) return;
        if (sVd != null) {
            try {
                sVd.release();
            } catch (Throwable ignored) {
            }
        }
        sVd = null;
        sDisplayId = -1;
        sDeployPath = "未部署";
        Log.i(TAG, "VD released");
    }
}
