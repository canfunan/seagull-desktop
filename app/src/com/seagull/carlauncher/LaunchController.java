package com.seagull.carlauncher;

import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 全局启动拦截（批次 AM，OneStep4 思路自写实现）。
 *
 * 原理：root 进程反射注册系统的 IActivityController
 * （ActivityTaskManager.setActivityController）—— system_server 会把**全系统每一次
 * Activity 启动**先问一遍控制器，返回 false 即拦截。这是官方的进程间接口（Monkey
 * 等同款），不需要 hook 任何目标应用，也不依赖 LSPosed —— LSPosed 的 C0 总闸只
 * 覆盖勾了作用域的应用，这里一条管全部（未勾作用域的 QQ 音乐/百度地图一并生效）。
 *
 * 决策规则（照 OneStep 的豁免思路自写，宁放行不误拦）：
 *   武装路由源 = 桌面通过 ARM 登记的「画布 displayId ↔ 嵌入应用」，
 *              且该屏 5 秒内有过注入触摸（MOTION 事件自动刷新时间戳）。
 *   拦截 = 武装有效 + 目标 ≠ 嵌入应用自身 + 不是 HOME + 不带 result 回传链 +
 *          不在 bypass 窗口内（自己改道后的重启动要放行，防自锁）。
 *   拦截动作 = 原意图在武装 displayId 上重新启动（bypass 先记账），原启动放回 false。
 *   任何异常 = 放行 —— 拦截逻辑哪怕崩一次都不能把全系统的启动弄死。
 *
 * 回复字节纪律：activityStarting/activityResuming 返回 1=放行 0=拦；
 * appCrashed/appANR/systemNotResponding 返回 0=系统默认处理。
 * 所有非 activityStarting 的事务一律 writeNoException+writeInt(0)（崩溃/ANR 走默认）。
 */
public final class LaunchController {

    private static final String TAG = "SeagullCtrl";
    private static final String DESCRIPTOR = "android.app.IActivityController";
    /** FLAG_ACTIVITY_FORWARD_RESULT：result 回传链的启动不能拦，否则分享/登录被打碎。 */
    private static final int FLAG_FORWARD_RESULT = 0x04000000;
    /** 武装 TTL：画布最后一次被注入触摸后 5s 内的跨应用启动才算"画布里发起的"。 */
    private static final long ARMED_TTL_MS = 5000;
    /** bypass TTL：改道重启动放行窗口。 */
    private static final long BYPASS_TTL_MS = 3000;

    private static volatile Binder controller;
    private static volatile boolean installed;

    // ---- 武装路由源（桌面通过 ARM 维护，MOTION 刷新 touchAt）----
    private static volatile int armedDisplay = -1;
    private static volatile String armedPkg = "";
    private static volatile long armedTouchAt;
    private static volatile long bypassUntil;
    private static volatile String bypassPkg = "";
    private static volatile String lastRouted = "";

    private LaunchController() {}

    /* ---------------- 对 RootMain（协议层）的接口 ---------------- */

    /** arm(displayId<=0 → 解除)。 */
    public static void arm(int displayId, String pkg) {
        armedDisplay = displayId > 0 ? displayId : -1;
        armedPkg = pkg == null ? "" : pkg;
        armedTouchAt = displayId > 0 ? System.currentTimeMillis() : 0L;
        RootLog.i(TAG, "arm d=" + armedDisplay + " pkg=" + armedPkg);
    }

    /** 注入触摸后刷新：displayId 与武装屏一致才算"画布里的触摸"。 */
    public static void touchAt(int displayId) {
        if (displayId == armedDisplay) armedTouchAt = System.currentTimeMillis();
    }

    public static void setInstalled(boolean on) {
        installed = on;
    }

    public static boolean isInstalled() { return installed; }

    /** 自检页一行快照。 */
    public static String stateLine() {
        long now = System.currentTimeMillis();
        boolean armed = armedDisplay > 0 && now - armedTouchAt < ARMED_TTL_MS;
        return "ctrl=" + (installed ? "on" : "off")
                + " armed=" + (armed ? armedPkg + "@" + armedDisplay : "-")
                + " routed=" + (lastRouted.isEmpty() ? "-" : lastRouted);
    }

    /* ---------------- 安装 / 卸载 ---------------- */

    /**
     * 注册控制器。注意：IActivityController 全系统唯一 —— 注册会顶掉现有的
     * （Monkey 之类），卸载置 null 同理。uid 0 直接过 SET_ACTIVITY_CONTROLLER 检查。
     */
    public static synchronized boolean install(boolean on) {
        IBinder b;
        Class<?> iatm;
        Object svc;
        try {
            b = RootMain.service("activity_task");
            iatm = Class.forName("android.app.IActivityTaskManager");
            svc = iatm.getMethod("asInterface", IBinder.class).invoke(null, b);
        } catch (Throwable t) {
            RootLog.w(TAG, "install: activity_task 服务不可达", t);
            return false;
        }
        try {
            if (on && controller == null) controller = new CtrlBinder();
            Method m = findSet(iatm);
            if (m == null) {
                RootLog.w(TAG, "install: setActivityController 重载未找到（ROM 裁剪？）", null);
                return false;
            }
            m.invoke(svc, on ? controller : null, false);
            installed = on;
            RootLog.i(TAG, "setActivityController " + (on ? "installed" : "removed"));
            return true;
        } catch (Throwable t) {
            RootLog.w(TAG, "install(" + on + ") 失败", t);
            return false;
        }
    }

    /** 优先 2 参（controller, monkey），老系统回落 1 参。 */
    private static Method findSet(Class<?> iatm) {
        for (Method m : iatm.getMethods()) {
            if (!"setActivityController".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == IBinder.class && p[1] == boolean.class) return m;
        }
        for (Method m : iatm.getMethods()) {
            if (!"setActivityController".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == IBinder.class) return m;
        }
        return null;
    }

    /* ---------------- 控制器 Binder ---------------- */

    private static final class CtrlBinder extends Binder {
        CtrlBinder() { attachInterface(null, DESCRIPTOR); }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(DESCRIPTOR);
                return true;
            }
            // activityStarting = IActivityController 第一个方法（FIRST_CALL_TRANSACTION）。
            // 其余（activityResuming / appCrashed / appANR / systemNotResponding …）
            // 一律"无异常 + 0"= 放行 / 走系统默认，绝不干扰崩溃与 ANR 处理。
            boolean intercept = false;
            String verdict = "";
            if (code == Binder.FIRST_CALL_TRANSACTION) {
                try {
                    // 消费接口 token 但不 enforce（防 ROM 换 descriptor 误拦）。
                    // readInterfaceToken 是隐藏 API，SDK jar 里没有 —— root 进程走反射。
                    try {
                        Parcel.class.getMethod("readInterfaceToken").invoke(data);
                    } catch (NoSuchMethodException nsme) {
                        RootLog.w(TAG, "readInterfaceToken 反射不可用，尝试直接读 Intent", null);
                    }
                    Intent intent = data.readTypedObject(Intent.CREATOR);
                    String type = data.readString();
                    intercept = shouldIntercept(intent, type);
                    verdict = intent == null ? "null-intent"
                            : String.valueOf(intent.getComponent());
                } catch (Throwable t) {
                    intercept = false;           // 解析失败 = 放行（红线：绝不误拦）
                    verdict = "parse-err " + t;
                    RootLog.w(TAG, "activityStarting 解析失败", t);
                }
            }
            if (reply != null) {
                // 回复字节纪律（IActivityController 各方法的返回语义）：
                //   activityStarting（FIRST）   → 1=放行 / 0=拦
                //   activityResuming（FIRST+1） → 恒 1（恢复=放行，拦恢复等于卡死应用）
                //   其余 appCrashed/appANR/systemNotResponding → 恒 0（走系统默认处理，
                //     1 会误杀应用、-1 会卡死系统，绝不能写错）
                reply.writeNoException();
                if (code == Binder.FIRST_CALL_TRANSACTION) {
                    reply.writeInt(intercept ? 0 : 1);
                } else if (code == Binder.FIRST_CALL_TRANSACTION + 1) {
                    reply.writeInt(1);
                } else {
                    reply.writeInt(0);
                }
            }
            if (intercept) {
                RootLog.i(TAG, "拦截改道 " + verdict);
            }
            return true;
        }
    }

    /* ---------------- 决策 ---------------- */

    private static boolean shouldIntercept(Intent intent, String type) {
        if (intent == null) return false;
        long now = System.currentTimeMillis();
        // 武装必须新鲜：超过 TTL 视为"用户不在画布里操作"，放行
        if (armedDisplay <= 0 || now - armedTouchAt > ARMED_TTL_MS) return false;
        // bypass 窗口：自己刚改道重启的这次放行，防自锁
        if (now < bypassUntil
                && (bypassPkg.isEmpty() || bypassPkg.equals(pkgOf(intent)))) return false;
        // HOME（回桌面）绝不拦：桌面跳转错路是灾难
        if (intent.hasCategory(Intent.CATEGORY_HOME)
                || intent.hasCategory(Intent.CATEGORY_SECONDARY_HOME)) return false;
        // result 回传链不拦：分享/选择器/登录回调被拦 = 流程被打碎
        if ((intent.getFlags() & FLAG_FORWARD_RESULT) != 0) return false;
        String pkg = pkgOf(intent);
        if (pkg == null || pkg.isEmpty()) return false;
        // 嵌入应用自己的内部跳页不拦：它应该跟着自己任务走
        //（真被拉回主屏由 BUMP 事件触发的既有自愈搬回兜底）
        if (pkg.equals(armedPkg)) return false;
        // 到这里：画布里 5 秒内有过触摸 + 目标是第三方应用 → 拦下，改道到画布
        bypassPkg = pkg;
        bypassUntil = now + BYPASS_TTL_MS;
        String via = "跨应用启动 " + pkg + " → 改道画布 d=" + armedDisplay
                + (type == null ? "" : " (" + type + ")");
        RootLog.i(TAG, via);
        lastRouted = pkg + "@" + armedDisplay;
        relaunchOnDisplay(intent, armedDisplay);
        RootMain.pushEvent(PrivCodec.EVT + " " + PrivCodec.EVT_ROUTED + " " + pkg
                + " " + armedDisplay);
        return true;
    }

    private static String pkgOf(Intent i) {
        if (i.getComponent() != null) return i.getComponent().getPackageName();
        return i.getPackage() != null ? i.getPackage() : "";
    }

    /** 改道重启动：原意图原 flags 原包名，只换落点屏（复用 RootMain 的启动设施）。 */
    private static void relaunchOnDisplay(Intent intent, int displayId) {
        try {
            int flags = intent.getFlags();
            if ((flags & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
                flags |= Intent.FLAG_ACTIVITY_NEW_TASK;
                intent.setFlags(flags);
            }
            // 走独立线程：控制器回调在 system_server 的启动关键路径上，
            // 重启动（一次 binder 往返）绝不能串行在里面
            new Thread(() -> {
                try {
                    Object res = RootMain.startActivityAsUser(intent,
                            RootMain.launchOptions(displayId), intent.getFlags(), 0);
                    RootLog.i(TAG, "改道启动 result=" + res);
                } catch (Throwable t) {
                    RootLog.w(TAG, "改道启动失败（目标可能缺组件）", t);
                }
            }, "seagull-route").start();
        } catch (Throwable t) {
            RootLog.w(TAG, "relaunch 编排失败", t);
        }
    }
}
