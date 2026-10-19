package com.seagull.carlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.List;

/**
 * 特权操作层（root）。
 *
 * 技术来源：用户自己的仓库 caogenfunan123/carlink-desktop 的 svc/RootAuth.kt（Kotlin 移植为 Java），
 * 以及 carplay-reverse-engineering 报告 §6/§11 的核验结论：
 *   · 建虚拟屏走【公开的 MediaProjection.createVirtualDisplay】，不需要 ADD_TRUSTED_DISPLAY
 *   · 把目标应用拉进虚拟屏走 root：守护进程进程内反射 startActivityAsUser（批次 J 新增），
 *     失败回退 root `am start --display <id> -f <flags> -n <comp>`
 *   · 触摸回注走 root：守护进程 injectInputEvent（真多指），失败回退 `input -d <id> ...`
 * 全程不依赖目标进程内的任何 hook。
 *
 * 降级链是硬规则：PrivClient 每一条失败都如实返回 false，命令照旧走 shell，
 * 调用方永远拿得到「成功」或「明确的失败」，不存在第三条路。
 */
public final class RootOps {

    private static final String TAG = "SeagullRootOps";

    /**
     * 拉起 flags（批次 K 修正，依据用户仓库 carlink-desktop-private m8 真机复盘）：
     *   0x10000000 NEW_TASK           —— 必须有，root 启动无 Activity 上下文
     *   0x08000000 MULTIPLE_TASK      —— 强制在虚拟屏新建独立任务（否则 singleTask 目标
     *                                  会把主屏已有任务拉到前台，"点了却跳全屏"就是它）
     *   0x00800000 EXCLUDE_FROM_RECENTS —— 镜像实例不进最近任务（参考版 0x18800000 同款）
     * 组合 = 0x18800000。LAUNCH_FLAGS（字符串）给 am 命令用，LAUNCH_FLAGS_INT 给守护进程用。
     */
    public static final String LAUNCH_FLAGS = "0x18800000";
    public static final int LAUNCH_FLAGS_INT = 0x18800000;

    private RootOps() {}

    /* ---------------- 权限预备 ---------------- */

    /** 开悬浮窗 + 追加通知监听 + 授通知权限。追加式写入，不覆盖别人的监听器。 */
    public static String grantAll(Context ctx) {
        String pkg = ctx.getPackageName();
        StringBuilder log = new StringBuilder();

        Caps.exec("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
        log.append("悬浮窗 appops: ok\n");

        String cur = Caps.exec("settings get secure enabled_notification_listeners");
        String mine = pkg + "/" + MediaListenerService.class.getName();
        if (cur != null) {
            cur = cur.trim();
            if (!cur.contains(pkg)) {
                String merged = cur.isEmpty() || "null".equals(cur) ? mine : cur + ":" + mine;
                Caps.exec("settings put secure enabled_notification_listeners " + merged);
                log.append("通知监听: 已追加\n");
            } else {
                log.append("通知监听: 已存在\n");
            }
        }
        Caps.exec("pm grant " + pkg + " android.permission.POST_NOTIFICATIONS");
        log.append("POST_NOTIFICATIONS: 已尝试\n");
        return log.toString();
    }

    /**
     * 放开录屏 appops（镜像前置）。
     * 注意：该 appop 是「一次性授权」，多数 ROM 仍会弹确认框，但置 allow 能减少被拒。
     */
    public static boolean allowProjectMedia(Context ctx) {
        return Caps.exec("appops set " + ctx.getPackageName() + " PROJECT_MEDIA allow") != null;
    }

    /* ---------------- 把应用拉进虚拟屏 ---------------- */

    /**
     * 三路启动，按可靠性排：
     *   ① root 守护进程进程内反射 startActivityAsUser（callingPackage=com.android.shell，
     *      ActivityOptions.setLaunchDisplayId）—— 与 am 同权，但没有进程启动开销，
     *      也不会被 am 的 argv 解析坑；
     *   ② 公开 API（部分 ROM 允许普通应用自己 startActivity 到虚拟屏）；
     *   ③ root `am start --display`（命令兜底，各 ROM 裁得最凶但普遍还在）。
     * flags 用 0x18800000（含 EXCLUDE_FROM_RECENTS）；--user 0 显式指定用户
     * （OneStep4 对标结论：工作资料/多用户下不带会落到错误用户）。
     * 返回 true 表示命令已下发成功（是否真的上去需另行 verify / ensureOnDisplay）。
     */
    public static boolean launchOnDisplay(Context ctx, String pkg, int displayId) {
        if (pkg == null || !pkg.matches("[a-z0-9_.]+")) {
            Log.w(TAG, "launchOnDisplay: 包名非法，拒绝执行: " + pkg);
            return false;
        }
        String comp = resolveLauncher(ctx, pkg);
        if (comp == null || !safeComponent(comp)) {
            Log.w(TAG, "launchOnDisplay: 找不到 " + pkg + " 的合法启动组件（" + comp + "）");
            return false;
        }
        // 【坑 #5，批次 AC 修正】am start 对已在运行的应用会静默投递到主屏实例：
        // singleTask 复用 display 0 上的老栈，画中画黑屏、手机屏反被应用盖住。
        // 旧实现无条件 `am force-stop` 砍掉老实例再冷启动 —— 这会杀掉正在导航 /
        // 放歌的进程，用户实测「从应用返回桌面导航和音乐都会暂停」正是它。
        //
        // 车联助手的 deploy 顺序是「先搬已有任务、搬不动才冷启动」，这里对齐：
        //   · 已有运行中的任务 → 只把它的任务搬到锚点栈（moveToAnchorRoot），
        //     进程与运行状态（导航中 / 播放中）不被打断，绝不 force-stop；
        //   · 确实没有任务 → 冷启动，本就没有进程，更不需要 force-stop。
        // 【批次 Z】兼容位必须在「起进程之前」写。am compat enable 只对之后新起的进程生效；
        // 旧顺序是「先起、起成功后才 relaxCompat」，于是当前这次部署根本不读新设置 ——
        // 高德照旧按 manifest 的 PORTRAIT 建窗，塞进横屏画布就是竖版 + 左右黑边，
        // 要等下一次部署才吃到配置（用户看到的"改了没用"正是这一条）。
        //
        // 【批次 BB】搬屏路径同样必须写。旧实现把 relaxCompat 放在 exists 判断之后，
        // 「已有任务 → moveToAnchorRoot」这条路整个跳过 —— 网易云带着主屏上的旧兼容态
        // 被 move-task 进画布，FORCE_RESIZE_APP 从来没写到它头上，letterbox 黑边
        // 一直在（用户截图：窗口只有 ~82% 宽，左右各 ~9% 黑边，对称居中 = 标准
        // size-compat letterbox 形态）。写完 override 对运行中的任务也会即时重算
        // （ActivityRecord onOverrideChanged → 重排），搬屏前写同样有效。
        relaxCompat(pkg);
        String stackOut = Caps.exec("am stack list");
        boolean exists = stackOut != null && StackScan.findTaskAnywhere(stackOut, pkg) != null;
        if (exists) {
            if (moveToAnchorRoot(ctx, pkg, displayId)) {
                Log.i(TAG, "launchOnDisplay[" + pkg + "] 已有任务，搬屏成功（不杀进程）-> " + displayId);
                return true;
            }
            Log.i(TAG, "launchOnDisplay[" + pkg + "] 已有任务但搬屏失败，走不强杀的投递路径");
        } else {
            Log.i(TAG, "launchOnDisplay[" + pkg + "] 无已存在任务 → 冷启动");
        }
        PrivClient.init(ctx);
        if (PrivClient.launch(displayId, comp, LAUNCH_FLAGS_INT)) {
            Log.i(TAG, "launchOnDisplay[" + pkg + "] 走守护进程成功 -> display " + displayId);
            return true;
        }
        if (launchViaApi(ctx, comp, displayId)) {
            Log.i(TAG, "launchOnDisplay[" + pkg + "] 走 API 成功 -> display " + displayId);
            return true;
        }
        String out = Caps.exec("am start --user 0 --display " + displayId
                + " -f " + LAUNCH_FLAGS + " -n " + comp);
        // 必须正向证据：am start 复用已存在任务时回显
        // "Warning: Activity not started, its current task has been brought to the front"
        // —— 里面既无 Error 也无 Exception，旧判据会把"没起来"当成功，
        // 画中画卡片照常 ready、应用其实停在主屏（批次 O 用户实测症状之一）。
        boolean ok = out != null
                && out.contains("Starting")
                && !out.contains("Error")
                && !out.contains("Exception")
                && !out.contains("Warning")
                && !out.contains("Abort");
        Log.i(TAG, "launchOnDisplay " + comp + " -> display " + displayId + " ok=" + ok
                + " " + (out == null ? "" : out.trim()));
        return ok;
    }

    /** 组件名白名单：只允许 包名/类名（可含 $），杜绝用户输入拼进 root 命令列表。 */
    public static boolean safeComponent(String comp) {
        return comp != null && comp.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+");
    }

    /**
     * 让应用在虚拟屏里铺满画布（高德地图这类）：
     *  · FORCE_RESIZE_APP —— 不吃 letterbox 兼容模式（小屏/怪宽高比下应用
     *    渲染成手机尺寸居中留黑边，就是"没办法铺满全屏幕"的直接原因）；
     *  · NEVER_FIX_ORIENTATION —— 别锁死 manifest 里的 screenOrientation，
     *    跟着虚拟屏方向转，否则竖屏应用塞横屏画布左右留黑边。
     * 命令缺省（老 ROM 没有 am compat）时只记日志，不影响部署。
     * 仅后台线程调用。
     */
    public static void relaxCompat(String pkg) {
        for (String change : new String[] {"FORCE_RESIZE_APP", "NEVER_FIX_ORIENTATION"}) {
            String out = Caps.exec("am compat enable " + change + " " + pkg);
            boolean ok = out != null && !out.toLowerCase().contains("error")
                    && !out.toLowerCase().contains("exception")
                    && !out.toLowerCase().contains("unknown");
            Log.i(TAG, "am compat enable " + change + " " + pkg + " ok=" + ok
                    + " " + (out == null ? "无回显" : out.trim()));
        }
    }

    /** 清空某槽时还原兼容设置，别把主屏上这个应用的行为也永久改掉。 */
    public static void resetCompat(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        String out = Caps.exec("am compat reset " + pkg);
        Log.i(TAG, "am compat reset " + pkg + " -> "
                + (out == null ? "无回显" : out.trim()));
    }

    /* ---------------- 批次 AI：窗口帧探测与「未铺满」自修复 ---------------- */

    /**
     * 解析 dumpsys window displays，取 pkg 在 displayId 上**最大**那个窗口的帧
     * （VD 坐标系）。返回 {l,t,r,b}；解析不出 / 应用不在这块屏 → null。
     *
     * 兼容两种 ROM 的 display 头格式（`Display: mDisplayId=5` / `Display #5`）。
     * 取最大窗口是因为主 Activity 常伴随弹窗/悬浮窗，铺满与否看主体。
     * 解析失败返回 null，调用方必须「不动进程」—— 宁可漏修不可误杀。
     */
    public static int[] windowFrameOnDisplay(int displayId, String pkg) {
        String out = Caps.exec("dumpsys window displays");
        if (out == null || out.isEmpty()) return null;
        java.util.regex.Pattern head = java.util.regex.Pattern.compile(
                "mDisplayId=" + displayId + "\\b|Display #" + displayId + "\\b");
        String[] lines = out.split("\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (head.matcher(lines[i]).find()) { start = i; break; }
        }
        if (start < 0) return null;
        String winKey = "u0 " + pkg + "/";
        int[] best = null;
        long bestArea = -1;
        boolean inWindow = false;
        for (int i = start + 1; i < lines.length; i++) {
            String l = lines[i];
            if (i > start && head.matcher(l).find()) break;   // 下一块屏
            if (l.contains("Window #") || l.contains("mSurfaceWindow{")) {
                inWindow = l.contains(winKey);
                continue;
            }
            if (!inWindow) continue;
            int[] f = parseFrame(l);
            if (f != null) {
                long area = (long) (f[2] - f[0]) * (f[3] - f[1]);
                if (area > bestArea) { bestArea = area; best = f; }
                inWindow = false;   // 一窗一帧，取第一个帧字段
            }
        }
        return best;
    }

    /** 解析一行里的 frame=[l,t][r,b]（兼容 mFrame= / frame=）。 */
    private static int[] parseFrame(String line) {
        int k = line.toLowerCase().indexOf("frame=[");
        if (k < 0) return null;
        try {
            String s = line.substring(k + 7);
            int mid = s.indexOf("][");
            if (mid < 0) return null;
            int end = s.indexOf(']', mid + 2);
            if (end < 0) return null;
            String[] p1 = s.substring(0, mid).split(",");
            String[] p2 = s.substring(mid + 2, end).split(",");
            return new int[] {
                    Integer.parseInt(p1[0].trim()), Integer.parseInt(p1[1].trim()),
                    Integer.parseInt(p2[0].trim()), Integer.parseInt(p2[1].trim())};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 某屏上全部带帧的应用窗口（体检页用）：String[n][5] = 标题, l, t, r, b。
     * 与 windowFrameOnDisplay 同一份解析，只是不按包名过滤、全部收集。
     */
    public static String[][] displayWindows(int displayId) {
        String out = Caps.exec("dumpsys window displays");
        if (out == null || out.isEmpty()) return new String[0][];
        java.util.regex.Pattern head = java.util.regex.Pattern.compile(
                "mDisplayId=" + displayId + "\\b|Display #" + displayId + "\\b");
        String[] lines = out.split("\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (head.matcher(lines[i]).find()) { start = i; break; }
        }
        if (start < 0) return new String[0][];
        java.util.List<String[]> got = new java.util.ArrayList<>();
        String curTitle = null;
        for (int i = start + 1; i < lines.length; i++) {
            String l = lines[i];
            if (head.matcher(l).find()) break;
            int wAt = l.indexOf("Window #");
            int sAt = l.indexOf("mSurfaceWindow{");
            if (wAt >= 0 || sAt >= 0) {
                curTitle = extractTitle(l);
                continue;
            }
            if (curTitle == null) continue;
            int[] f = parseFrame(l);
            if (f != null) {
                got.add(new String[]{curTitle,
                        String.valueOf(f[0]), String.valueOf(f[1]),
                        String.valueOf(f[2]), String.valueOf(f[3])});
                curTitle = null;   // 一窗一帧
            }
        }
        return got.toArray(new String[0][]);
    }

    /** 从窗口头行提取 `u0 pkg/cls`（失败返回原行截断）。 */
    private static String extractTitle(String line) {
        int u = line.indexOf("u0 ");
        if (u >= 0) {
            int e = line.indexOf('}', u);
            String t = line.substring(u + 3, e > u ? e : line.length()).trim();
            if (!t.isEmpty()) return t;
        }
        return line.trim().length() > 60 ? line.trim().substring(0, 60) : line.trim();
    }

    /**
     * 未铺满自修复（批次 AI）：探测 pkg 窗口帧 vs 屏尺寸，任一轴 < 92% 判未铺满
     * → force-stop + 在该屏冷启动（deploy 时已写好兼容位，新进程必然按屏尺寸布局）。
     * 返回一句结论（进日志 / 体检报告）；**解析失败时绝不动进程** —— 宁可漏修不可误杀。
     */
    public static String ensureFilled(Context ctx, String pkg, int displayId, int dispW, int dispH) {
        if (dispW <= 0 || dispH <= 0) return "屏尺寸未知，跳过";
        int[] f = windowFrameOnDisplay(displayId, pkg);
        if (f == null) return "探测不到窗口帧（应用可能不在这块屏），不动进程";
        int w = f[2] - f[0], h = f[3] - f[1];
        boolean full = w >= dispW * 92 / 100 && h >= dispH * 92 / 100;
        if (full) return "已铺满 " + w + "x" + h + " / " + dispW + "x" + dispH;
        Log.i(TAG, "未铺满 " + w + "x" + h + " / " + dispW + "x" + dispH + " → 冷启动修复 " + pkg);
        Caps.exec("am force-stop " + pkg);
        try { Thread.sleep(800); } catch (InterruptedException ignore) {}
        boolean ok = launchOnDisplay(ctx, pkg, displayId);
        return "未铺满(" + w + "x" + h + " / " + dispW + "x" + dispH + ") → 已冷启动修复 ok=" + ok;
    }

    private static boolean launchViaApi(Context ctx, String comp, int displayId) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(android.content.ComponentName.unflattenFromString(comp));
            if (i.getComponent() == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(displayId);
            ctx.startActivity(i, opts.toBundle());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "API 启动被拒，落 root: " + t);
            return false;
        }
    }

    /** 解析入口组件，失败再退 `cmd package resolve-activity --brief`。 */
    public static String resolveLauncher(Context ctx, String pkg) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null && i.getComponent() != null) {
                return i.getComponent().flattenToShortString();
            }
        } catch (Throwable ignore) {}
        String out = Caps.exec("cmd package resolve-activity --brief " + pkg);
        if (out != null) {
            for (String line : out.split("\n")) {
                String s = line.trim();
                if (s.contains("/") && !s.contains(" ")) return s;
            }
        }
        try {
            Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = ctx.getPackageManager()
                    .queryIntentActivities(probe, PackageManager.MATCH_ALL);
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo != null && pkg.equals(ri.activityInfo.packageName)) {
                    return ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /* ---------------- 触摸回注 ---------------- */

    /** 单条静默命令（触摸注入用）。 */
    public static boolean injectTouch(int displayId, String cmd) {
        if (displayId <= 0) return false;
        String out = Caps.exec("input -d " + displayId + " " + cmd);
        return out != null;
    }

    /**
     * 往虚拟屏注入按键（批次 AA：画中画卡片底部常驻键条用）。
     * 语法与 injectTouch 一致（input -d <id> keyevent <code>），back=4 home=3。
     * 私有 VD 没有系统装饰、SystemUI 不会给它画导航栏，键条是唯一可靠的返回/桌面入口。
     */
    public static boolean injectKey(int displayId, int keycode) {
        if (displayId <= 0) return false;
        String out = Caps.exec("input -d " + displayId + " keyevent " + keycode);
        boolean ok = out != null;
        Log.i(TAG, "injectKey d" + displayId + " code=" + keycode + " ok=" + ok);
        return ok;
    }

    /* ---------------- TRUSTED 虚拟屏（批次 K，carlink m13 同款） ---------------- */

    private static volatile Boolean trustedRoleGranted = null;

    /**
     * 给本包授 COMPANION_DEVICE_APP_STREAMING 角色（root `cmd role add-role-holder`）。
     * Android 14 的 RoleController 会给角色持有者授 ADD_TRUSTED_DISPLAY 等签名级权限，
     * 本进程才能用 VIRTUAL_DISPLAY_FLAG_TRUSTED 建屏 —— 受信屏才收留得住
     * home-affinity/TASK_ON_HOME 任务，这是"画中画任务被系统拉回主屏"的根治前提
     * （对标 OneStep4 RootVirtualDisplayHost.ensureTrustedDisplayRole）。
     * 幂等，进程内缓存；仅后台线程调用。
     */
    public static boolean grantTrustedDisplayRole(Context ctx) {
        Boolean cached = trustedRoleGranted;
        if (cached != null) return cached;
        String pkg = ctx.getPackageName();
        String out = Caps.exec("cmd role add-role-holder --user 0 "
                + "android.app.role.COMPANION_DEVICE_APP_STREAMING " + pkg + " 0");
        boolean ok = out != null && !out.toLowerCase().contains("error")
                && !out.toLowerCase().contains("exception");
        Log.i(TAG, "TRUSTED 角色授予(" + pkg + ") ok=" + ok
                + " " + (out == null ? "" : out.trim()));
        trustedRoleGranted = ok;
        return ok;
    }

    /** 角色授予状态（诊断用，不触发 su）。 */
    public static String roleState() {
        Boolean g = trustedRoleGranted;
        return g == null ? "未尝试" : (g ? "已授予 COMPANION_DEVICE_APP_STREAMING" : "授予失败");
    }

    /* ---------------- 画中画锚点（批次 R，复刻 CarWithX PipAnchorActivity） ---------------- */

    /**
     * 起锚点到指定虚拟屏：部署建屏成功后立即调，让 VD 上常驻一个我们自己的 root task。
     *   1. 重钉落点稳定（ensureOnDisplay/move-task 有 stack 可落）；
     *   2. VD 永远有活任务，系统不会当空屏清理；
     *   3. `am stack list` 里锚点行 = 这块屏活着的直接判据。
     * flags 只带 NEW_TASK（0x10000000）：锚点 task 已存在时复用（幂等）。
     * 【绝不能带 MULTIPLE_TASK】否则每次部署都叠一个新锚点 task，最终撑爆 VD。
     * 对目标应用那套"Warning=失败"的正向证据判据在这里放宽：
     * "Warning: ... brought to the front" = 锚点还活着 = 好结果。
     */
    public static boolean launchAnchor(Context ctx, int displayId) {
        // ① 公开 API：自家 Activity 起自家虚拟屏，多数 ROM 直接放行
        try {
            Intent i = new Intent(ctx, PipAnchorActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
            opts.setLaunchDisplayId(displayId);
            ctx.startActivity(i, opts.toBundle());
            Log.i(TAG, "锚点走 API -> display " + displayId);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "锚点 API 启动被拒，落 su: " + t);
        }
        String out = Caps.exec("am start --user 0 --display " + displayId
                + " -f 0x10000000 -n " + ctx.getPackageName()
                + "/" + PipAnchorActivity.class.getName());
        boolean ok = out != null && !out.contains("Error") && !out.contains("Exception")
                && !out.contains("Abort");
        Log.i(TAG, "锚点走 su -> display " + displayId + " ok=" + ok
                + (out == null ? "" : " " + out.trim()));
        return ok;
    }

    /* ---------------- 路①：锚点 + move-task 搬已有任务（批次 AB） ---------------- */

    /**
     * 车联助手路①（复刻蓝图 §2.2 `FakeStart.moveExistingTask`）：
     * 锚点 + move-task 把「**已经在跑**」的目标任务搬进虚拟屏。
     *
     * 为什么这是首选而不是冷启动：目标 App（高德最常见）通常用户已经开过、
     * 正在主屏跑着。`am start --display` 对 singleTask 目标常把它主屏的老任务
     * 拉到前台而不是挪屏（"点了却跳全屏"的根因）；而搬任务能保住它的运行状态
     * （导航中不被打断）。车联的 deploy 顺序就是「先搬、搬不动才冷启动」。
     *
     * 落点三条，按可靠性排：
     *   ① 守护进程反射 moveTaskToRootTask（CarWithX TaskMover 原逻辑，不解析文本）；
     *   ② shell `am stack list` 解出锚点栈 → `am stack move-task <task> <root> true`；
     *   ③ 都不可用 → 返回 false，调用方走冷启动。
     *
     * 返回 false = "没有已在跑的任务可搬"（也可能是两条通道都不可用），
     * 二者对调用方的决策一致：去冷启动。仅后台线程调用（内部 sleep + su）。
     */
    public static boolean moveExistingTask(Context ctx, String pkg, int displayId) {
        if (pkg == null || !pkg.matches("[a-z0-9_.]+")) {
            Log.w(TAG, "moveExistingTask: 包名非法 " + pkg);
            return false;
        }
        if (displayId <= 0) return false;
        // 锚点必须先落：它是 move-task 的落点（root task）；幂等（NEW_TASK 复用）
        launchAnchor(ctx, displayId);
        // 给锚点开窗留时间（复刻原实现的 sleep 0.15；这里略放宽）
        sleepQuiet(180);
        if (moveToAnchorRoot(ctx, pkg, displayId)) return true;
        Log.i(TAG, "moveExistingTask: 没搬成（无已在跑的任务，或通道不可用），交回调用方冷启动");
        return false;
    }

    /** 把 pkg 的任务搬进「锚点在 displayId 屏上的 root task」：守护进程反射优先，shell 兜底。 */
    private static boolean moveToAnchorRoot(Context ctx, String pkg, int displayId) {
        String anchorPkg = ctx.getPackageName();
        PrivClient.init(ctx);
        if (PrivClient.moveToRoot(pkg, displayId, anchorPkg)) return true;
        return shellMoveToAnchorRoot(pkg, displayId, anchorPkg);
    }

    /** shell 兜底：`am stack list` 找锚点栈与目标任务 → `am stack move-task <task> <root> true`。 */
    private static boolean shellMoveToAnchorRoot(String pkg, int displayId, String anchorPkg) {
        String out = Caps.exec("am stack list");
        if (out == null || StackScan.segments(out).isEmpty()) {
            Log.i(TAG, "shell 搬任务：am stack list 不可用/不可解析");
            return false;
        }
        if (StackScan.isRunningOnDisplay(out, pkg, displayId)) {
            Log.i(TAG, "shell 搬任务：" + pkg + " 已在 display " + displayId);
            return true;
        }
        // 落点：锚点所在栈；锚点已退则用该屏第一个 root task
        int root = StackScan.anchorStackOnDisplay(out, displayId, anchorPkg);
        if (root < 0) root = StackScan.firstStackOnDisplay(out, displayId);
        if (root < 0) { Log.i(TAG, "shell 搬任务：目标屏没有 root task"); return false; }
        StackScan.TaskRef src = StackScan.findTaskAnywhere(out, pkg);
        if (src == null) { Log.i(TAG, "shell 搬任务：" + pkg + " 没有已存在的任务"); return false; }
        String cmdOut = Caps.exec("am stack move-task " + src.taskId + " " + root + " true");
        boolean ok = shellOk(cmdOut);
        Log.i(TAG, "shell move-task " + src.taskId + " -> root " + root + " ok=" + ok
                + (cmdOut == null ? " 无回显" : " " + cmdOut.trim()));
        return ok;
    }

    /** shell 输出判成功：非 null 且不含 error/exception/unknown/usage/permission denied。 */
    private static boolean shellOk(String out) {
        if (out == null) return false;
        String s = out.toLowerCase();
        return !s.contains("error") && !s.contains("exception")
                && !s.contains("unknown") && !s.contains("usage:")
                && !s.contains("permission denied");
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /* ---------------- 目标自愈（批次 K，carlink m10/m12 同款） ---------------- */

    /**
     * ensureOnDisplay 返回该前缀 = 栈结构解析成功且目标应用在主屏、目标屏、
     * 其他虚拟屏上都不存在（在画中画里按返回退出了，或崩了）。
     * 调用方可节流重拉；解析失败类返回绝不带此前缀（m12 红线：解析不出绝不动）。
     */
    public static final String ABSENT = "absent:";

    /**
     * singleTask 应用（网易云这类）在虚拟屏内部跳转时，新 activity 会落到默认屏，
     * 任务整体被拉走 —— 主屏冒出全屏应用、画中画黑屏。这里检测并搬回。
     *
     * 保守策略（m12 真机教训）：检测不出栈结构就放弃，绝不主动重拉 ——
     * 无脑重拉会在虚拟屏里叠出第二个目标任务，两个画中画一起坏。
     *
     * 搬运链（批次 AB 对齐车联助手）：
     *   ① 守护进程反射 moveTaskToRootTask（落点 = 锚点在目标屏上的 root task），
     *      不解析 am 文本、不依赖 `am stack move-task` 子命令；
     *   ② shell：`am stack list` 解出锚点栈 → `am stack move-task <task> <root> true`；
     *   ③ 旧命令链 `am task move-task <taskId> <displayId> true` 兜底。
     * 仅后台线程调用。
     */
    public static String ensureOnDisplay(Context ctx, String pkg, int displayId) {
        if (pkg == null || pkg.isEmpty() || displayId <= 0) return "参数无效";
        String out = Caps.exec("am stack list");
        boolean parsed = out != null && !StackScan.segments(out).isEmpty();
        if (parsed) {
            if (StackScan.isRunningOnDisplay(out, pkg, displayId)) return "目标已在虚拟屏";
            // 双槽绑同一个应用时，另一个槽的屏上会有同名 task：那是别人的实例，绝不能动
            // （否则重拉会 force-stop 掉对槽正在跑的应用，两块画布一起死）。
            for (Integer d : StackScan.segments(out).keySet()) {
                if (d == null || d == 0 || d == displayId) continue;
                if (StackScan.findTaskOnDisplay(out, pkg, d) != null) {
                    return "目标任务在其他虚拟屏 d=" + d + "，不动";
                }
            }
            if (StackScan.findTaskAnywhere(out, pkg) == null) {
                return ABSENT + "目标任务不在任何屏（可能已退出）";
            }
        }
        String anchorPkg = ctx.getPackageName();
        PrivClient.init(ctx);
        // ① 守护进程反射（不依赖 am stack list 是否可解析）
        if (PrivClient.moveToRoot(pkg, displayId, anchorPkg)) {
            return "已搬回虚拟屏（守护进程 moveTaskToRootTask）";
        }
        if (parsed) {
            // ② shell 锚点 + move-task
            if (shellMoveToAnchorRoot(pkg, displayId, anchorPkg)) {
                return "已搬回虚拟屏（am stack move-task）";
            }
            // ③ 旧命令链：部分 ROM 只认 am task move-task
            StackScan.TaskRef src = StackScan.findTaskAnywhere(out, pkg);
            if (src != null) {
                String cmdOut = Caps.exec("am task move-task " + src.taskId + " " + displayId + " true");
                if (shellOk(cmdOut)) {
                    return "已搬回虚拟屏（am task move-task " + src.taskId + "）";
                }
            }
            return "自愈：栈结构可解析但所有搬运通道都失败";
        }
        // 【批次 AF】am stack list 解析不出 + 守护进程搬屏失败 ≠ 无路可走：
        // dumpsys activity activities 是完全独立的信息源（Display #N 段头归口、
        // TaskScan 解析，与 am stack list 是两套输出格式）。真机出现过
        // 「am stack list 空段、dumpsys 一切正常」的 ROM —— 此前到这一步就静默
        // 放弃，用户看到的就是「跳主屏后画中画永远黑屏」。
        // 只搬不拉（m12 红线不变）：dumpsys 里也找不到任务就如实返回，绝不重拉。
        return ensureViaDumpsys(ctx, pkg, displayId, anchorPkg);
    }

    private static final java.util.regex.Pattern DISPLAY_HDR =
            java.util.regex.Pattern.compile("Display\\s+#(\\d+)");

    /**
     * 第④搬运通道（批次 AF）：dumpsys 定位 + move-task。
     * 与解析路径同一条护栏：同名任务在其他虚拟屏上是别的槽的实例，绝不动。
     */
    private static String ensureViaDumpsys(Context ctx, String pkg, int displayId, String anchorPkg) {
        String dump = Caps.exec("dumpsys activity activities");
        if (dump == null || dump.isEmpty()) return "am stack list 与 dumpsys 都无回显，跳过自愈";
        if (TaskScan.findTaskId(dump, pkg, displayId) >= 0) {
            return "目标已在虚拟屏（dumpsys d=" + displayId + "）";
        }
        java.util.regex.Matcher m = DISPLAY_HDR.matcher(dump);
        while (m.find()) {
            int d;
            try { d = Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { continue; }
            if (d == 0 || d == displayId) continue;
            if (TaskScan.findTaskId(dump, pkg, d) >= 0) {
                return "目标任务在其他虚拟屏 d=" + d + "，不动";
            }
        }
        String r = moveTaskToDisplay(ctx, pkg, displayId, anchorPkg);
        return r.contains("找不到") ? ABSENT + r : r;
    }

    /* ---------------- 诊断 ---------------- */

    /** 当前存在的镜像虚拟屏（供诊断页显示"虚拟屏到底建没建"）。 */
    public static String readDisplays() {
        String out = Caps.exec("dumpsys display | grep -oE 'seagull-[a-zA-Z0-9]+'");
        if (out == null || out.trim().isEmpty()) return "未见虚拟屏";
        return out.trim().replace("\n", ", ");
    }

    /**
     * 确认目标是否真的落在虚拟屏上（TaskMover 同款判定：查 task 的 display 归属）。
     * 解析口径见 TaskScan（坑 #11：Task 行没有 displayId=，归属看段头）。
     * 返回 taskId，未找到 -1。
     */
    public static int findTaskId(String pkg, int displayId) {
        String out = Caps.exec("dumpsys activity activities");
        return out == null ? -1 : TaskScan.findTaskId(out, pkg, displayId);
    }

    /**
     * TaskMover：把任务迁移到目标 display。
     * 优先走守护进程的 moveRootTaskToDisplay 反射（Android 14+ 有这个隐藏方法，
     * root uid 调用直接过权限检查）；失败回退 `am task move-task` 命令链。
     * dumpsys 只拉一次：既定位 task，也确认它不在目标屏上（已在就是 no-op，
     * 报清楚，别让用户以为搬成功了）。
     */
    public static String moveTaskToDisplay(Context ctx, String pkg, int displayId, String anchorPkg) {
        if (!Caps.hasRoot()) return "无 root";
        String dump = Caps.exec("dumpsys activity activities");
        if (dump == null) return "dumpsys 无回显";
        int taskId = TaskScan.findTaskId(dump, pkg, -1);
        if (taskId < 0) return "找不到 " + pkg + " 的 task";
        if (TaskScan.findTaskId(dump, pkg, displayId) == taskId) {
            return "task=" + taskId + " 已在 display " + displayId + "，无需搬";
        }
        PrivClient.init(ctx);
        if (PrivClient.move(taskId, displayId)) {
            return "task=" + taskId + " -> display " + displayId + " : 守护进程反射成功";
        }
        String out = Caps.exec("am task move-task " + taskId + " " + displayId + " true");
        return "task=" + taskId + " -> display " + displayId + " : "
                + (out == null ? "无回显" : out.trim());
    }
}
