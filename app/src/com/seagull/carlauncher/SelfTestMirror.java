package com.seagull.carlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.Log;

/**
 * L3 完整链路自检 —— 走真实授权：MediaProjection 建屏 → root 搬应用 → 核对 → 触摸注入。
 *
 * 第一步必须用户在系统弹框点「立即开始」（录屏授权是 Android 硬规定，无法绕过）。
 * 之后全自动，逐条结果打 logcat（tag=SeagullSelfTest）。
 */
public final class SelfTestMirror {

    private static final String TAG = "SeagullSelfTest";
    private static final int REQ = 0x5EA9;
    private static final String TARGET = "com.android.deskclock";

    private static final String[] FLAG_CANDIDATES = {
            Constants.LAUNCH_FLAGS,   // EXCLUDE_FROM_RECENTS(0x00800000) | MULTIPLE_TASK | NEW_TASK —— 批次 K 口径
            "0x18000000",   // MULTIPLE_TASK | NEW_TASK —— 批次 J 口径（不带 singleTask 目标会被拉回主屏）
            "0x10104000",   // 报告 §6 提到的另一组
    };

    public static void begin(final Activity act) {
        line("===== L3 全链路自检 =====");
        line("uid=" + android.os.Process.myUid() + " root=" + Caps.hasRoot()
                + " 投影服务=" + PipProjectionService.running);
        // 前置：放开录屏 appops（部分 ROM 能省掉弹框）
        boolean pm = RootOps.allowProjectMedia(act);
        line("appops PROJECT_MEDIA allow → " + pm);
        MediaProjectionManager mpm =
                (MediaProjectionManager) act.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            act.startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
            line("已弹出录屏授权框，请在设备上点「立即开始」…");
        } catch (Throwable t) {
            line("拉起授权框失败: " + t);
        }
    }

    public static boolean isOurRequest(int req) { return req == REQ; }

    /** 授权回来后的全自动流程。 */
    public static void onConsent(final Activity act, int code, Intent data) {
        new Thread(() -> {
            try {
                line("✔ 录屏授权 OK，开始全自动流程");
                PipProjectionService.start(act);
                trustedProbe(act);
                int waited = 0;
                while (!PipProjectionService.running && waited < 3000) {
                    Thread.sleep(150); waited += 150;
                }
                line("投影前台服务 running=" + PipProjectionService.running + "（等了 " + waited + "ms）");

                MediaProjectionManager mpm = (MediaProjectionManager)
                        act.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                MediaProjection mp = mpm.getMediaProjection(code, data);
                line("getMediaProjection → " + (mp == null ? "null" : "OK"));
                if (mp == null) return;

                int w = 960, h = 540, dpi = 160;
                VirtualDisplay vd = null;
                try {
                    vd = mp.createVirtualDisplay("seagull-l3test", w, h, dpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                            null, null, null);
                } catch (Throwable t) {
                    line("建屏异常: " + t);
                }
                int id = vd == null ? -1 : vd.getDisplay().getDisplayId();
                line("① MediaProjection 建屏 → " + (vd == null ? "null" : "displayId=" + id));
                if (id <= 0) { safeStop(mp); return; }

                String comp = RootOps.resolveLauncher(act, TARGET);
                line("② 目标组件: " + comp);

                // 每次启动前彻底停掉目标，避免 intent 被打到主屏已有实例上
                Caps.exec("am force-stop " + TARGET);
                Thread.sleep(800);

                for (String flags : FLAG_CANDIDATES) {
                    line("── 试 flags=" + flags + " ──");
                    // 先停目标再启，保证是"冷启到指定屏"
                    Caps.exec("am force-stop " + TARGET);
                    Thread.sleep(600);
                    String out = Caps.exec("am start --user 0 --display " + id + " -f " + flags + " -n " + comp);
                    line("   am 输出: " + flat(out));
                    Thread.sleep(2500);
                    boolean ok = taskOnDisplay(id, TARGET);
                    line("   核对 → " + (ok ? "✔ 目标已在 display " + id : "✘ 没落上去"));
                    if (ok) {
                        String tap = Caps.exec("input -d " + id + " tap 480 270");
                        line("③ 触摸注入 input -d " + id + " tap 480 270 → "
                                + (tap == null || tap.trim().isEmpty() ? "已下发(静默成功)" : flat(tap)));
                        Thread.sleep(1000);
                        line("④ 注入后仍在屏上: " + taskOnDisplay(id, TARGET));
                        break;
                    }
                }

                line("⑤ 最终核对：目标的 task 落在哪个 display");
                line("   " + whereIsTask(TARGET));
                line("⑥ 系统是否认可这块屏: " + displayEvidence(id));
                line("⑦ 屏幕内 task 概览（按 Display 分节）:");
                String dd = Caps.exec("dumpsys activity activities");
                if (dd != null) {
                    String sec = null;
                    for (String l : dd.split("\n")) {
                        String t = l.trim();
                        if (t.startsWith("Display #")) { sec = t; line("   " + t); }
                        else if (sec != null && t.startsWith("* Task{") && t.contains("type=standard")) {
                            line("      " + t.substring(0, Math.min(95, t.length())));
                        }
                    }
                }
                try { vd.release(); } catch (Throwable ignore) {}
                line("已释放测试屏");
                // mp.stop() 是硬泄漏：MediaProjection 不 stop，前台服务
                // PipProjectionService 与投影授权一直活着，直到用户手动杀进程；
                // while 循环里剩下的 flags 试探也就全废了
                try { mp.stop(); } catch (Throwable ignore) {}
                line("已停投影");
            } catch (Throwable t) {
                line("异常: " + t);
            }
            line("===== 自检结束 =====");
        }, "l3-selftest").start();
    }

    /** 自检走人任何中途退出路径都要停投影：mp 留在前台等于把授权泄漏给系统。 */
    private static void safeStop(MediaProjection mp) {
        if (mp == null) return;
        try { mp.stop(); } catch (Throwable ignore) {}
    }

    /**
     * 批次 K 新增 ⓪：TRUSTED 屏探测 —— 用户抱怨③（"进去桌面还不是画中画界面"）的根因验证。
     * Android 14 的 ActivityStarter 只允许 home-affinity / TASK_ON_HOME 任务落【受信】虚拟屏，
     * 所以这一步逐组候选建屏、读回 Display flags 校验 TRUSTED 位（1<<7），全部失败才回落投影屏。
     * 探测屏不带 Surface，验完即 release，不影响后面的投影建屏。
     */
    private static void trustedProbe(Context ctx) {
        line("⓪ TRUSTED 屏探测（批次 K）");
        if (!TrustedFlags.supportsTrusted(android.os.Build.VERSION.SDK_INT)) {
            line("   SDK<30，无需 TRUSTED（Android 14 前投影屏也拉不走任务），跳过");
            return;
        }
        boolean granted = RootOps.grantTrustedDisplayRole(ctx);
        line("   角色授予 COMPANION_DEVICE_APP_STREAMING → " + granted + " state=" + RootOps.roleState());
        DisplayManager dm =
                (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        int sdk = android.os.Build.VERSION.SDK_INT;
        for (int raw : TrustedFlags.candidateFlags()) {
            int flags = TrustedFlags.normalize(raw, sdk);
            String desc = TrustedFlags.describe(flags);
            VirtualDisplay vd = null;
            try {
                vd = dm.createVirtualDisplay("seagull-trustprobe", 64, 64, 160, null, flags);
                if (vd == null) { line("   " + desc + " → null（被拒）"); continue; }
                int df = vd.getDisplay().getFlags();
                boolean trusted = TrustedFlags.hasTrusted(df);
                line("   " + desc + " → displayId=" + vd.getDisplay().getDisplayId()
                        + " displayFlags=0x" + Integer.toHexString(df)
                        + (trusted ? "  ✔ 受信（任务能落在虚拟屏上）" : "  ✘ 未受信（建出来了，任务仍会被拉回主屏）"));
                vd.release();
                if (trusted) {
                    line("   ⇒ TRUSTED 屏可用，修复「进去桌面而不是画中画」的前提成立");
                    return;
                }
            } catch (Throwable t) {
                line("   " + desc + " → 异常 " + t);
                if (vd != null) try { vd.release(); } catch (Throwable ignore) {}
            }
        }
        line("   ⇒ 全部候选未受信：回落投影屏 + ensureOnDisplay 自愈（真机需查 SELinux 是否放行角色授予）");
    }

    /**
     * 精确核对：目标 task 到底在哪个 display。
     * dumpsys activity activities 的真实结构是按 Display 分节：
     *     Display #0 (activities from top to bottom):
     *       * Task{24b3633 #27648 type=standard A=10238:com.android.deskclock ...}
     * 【坑】task 行本身不含 displayId，必须靠 Display #N 分节来归属。
     */
    private static boolean taskOnDisplay(int displayId, String pkg) {
        return whereIsTask(pkg).startsWith("display=" + displayId);
    }

    /** 返回 "display=N task=###" 或诊断串。 */
    private static String whereIsTask(String pkg) {
        String dump = Caps.exec("dumpsys activity activities");
        if (dump == null) return "dumpsys 无回显";
        int cur = -1;
        for (String l : dump.split("\n")) {
            String t = l.trim();
            if (t.startsWith("Display #")) {
                java.util.regex.Matcher dm =
                        java.util.regex.Pattern.compile("Display #(\\d+)").matcher(t);
                if (dm.find()) cur = Integer.parseInt(dm.group(1));
                continue;
            }
            if (t.startsWith("* Task{") && t.contains(pkg)) {
                java.util.regex.Matcher tm =
                        java.util.regex.Pattern.compile("#(\\d+)").matcher(t);
                String taskId = tm.find() ? tm.group(1) : "?";
                return "display=" + cur + " task=" + taskId;
            }
        }
        // 没找到 → 报出当前到底有几块 Display 分节，便于判断"屏建没建"
        StringBuilder sb = new StringBuilder("未找到该包 task；当前 Display 分节: ");
        for (String l : dump.split("\n")) {
            String t = l.trim();
            if (t.startsWith("Display #")) sb.append(t.replace(" (activities from top to bottom):", "")).append("  ");
        }
        return sb.toString().trim();
    }

    /** 独立证据：系统到底认不认这块屏。 */
    private static String displayEvidence(int id) {
        String d = Caps.exec("dumpsys display");
        if (d == null) return "dumpsys display 无回显";
        StringBuilder sb = new StringBuilder();
        for (String l : d.split("\n")) {
            if (l.contains("mDisplayId=" + id) || l.contains("seagull-l3test")) {
                sb.append(l.trim()).append(" | ");
            }
        }
        return sb.length() == 0 ? "系统里查不到 display " + id : sb.toString().trim();
    }

    private static String flat(String s) {
        if (s == null) return "null";
        String x = s.trim().replace("\n", " | ");
        return x.length() > 200 ? x.substring(0, 200) : x;
    }

    private static void line(String s) { Log.i(TAG, s); }
}
