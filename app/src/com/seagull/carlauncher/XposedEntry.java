package com.seagull.carlauncher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Rational;
import android.util.Size;
import android.view.Display;
import android.view.WindowManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口。按「车联助手 X / CarWithX」反编译结论逐条对齐，
 * 只碰框架层窗口语义，不碰任何第三方 App 的内部类（混淆一变就全废）。
 *
 * 三条职责：
 *
 * A) SystemUI：AmapPipHook —— 高德进真实系统 PiP 时，把窗口尺寸放大到屏短边的 60%。
 *    对齐 xphook.hookAmapPip（hook com.android.wm.shell.pip.PipBoundsAlgorithm）。
 *    只在包名 == 高德 时缩放（xphook.isAmapPip 的判定口径），不是所有 App 都缩。
 *
 * B) 目标应用（高德/地图/音乐）：H1~H3 窗口语义配合（对齐 WFreeHook）。
 *    H1 如实上报多窗口状态：不少 App 在「非多窗口」时拒绝按小尺寸重排，
 *       缩成卡片后就只剩原竖屏画面 + 左右黑边 —— 这是高德黑边的直接病根。
 *    H2 方向劫持：setRequestedOrientation + getRequestedOrientation 两面都改，
 *       只 hook set 会被 App 读回自己锁的方向后再次带跑。
 *    H3 修正 PiP 参数：sourceRectHint 铺满整屏（否则小窗只裁出一角），
 *       并把 onPictureInPictureRequested 直接放行。
 *
 * C) 网易云 IoT：注入 setLaunchDisplayId（对齐 xphook.hookNeteasePip 的 6 个
 *    startActivity hook）。不带这个，网易云点击跳转的新 Activity 会落到主屏，
 *    画中画2 一碰就跳走。参考实现那两条 `Context.startActivity` 挂在抽象基类上是
 *    死 hook（见 C 段注释），这里换成 android.app.ContextImpl。
 *
 * 与上一版的差异（复盘结论，见 docs/DEVLOG.md）：
 *   - 删掉 onUserLeaveHint → enterPictureInPictureMode：车联助手没有这条；
 *     多数 App 未声明 supportsPictureInPicture，调用必抛，被 catch 吞掉后静默失效。
 *   - H1 补回来：缺它高德不会重排布局，这正是「竖屏左右黑边」的根因。
 *   - H2 补回 onCreate 后主动 setRequestedOrientation（批次 W 的机制，被上版漏掉）：
 *     只 hook set/get 改不动 WM 侧的 ActivityRecord，manifest 里写死的 PORTRAIT
 *     仍会让首帧竖版；补这一条首帧即目标方向。
 *   - 方向 hook 补 getRequestedOrientation，并跳过 UNSPECIFIED（对齐参考）。
 *   - C 段：Context → ContextImpl，且每条 hook 各自 try/catch（见 C 段注释）。
 *   - SystemUI 缩放从「所有包都缩」收紧为「只缩高德」。
 *   - 日志改为 Log + XposedBridge.log 双写：XposedBridge.log 只进 LSPosed 日志，
 *     真机排查时 logcat 里看不到，双写后 `SeagullLsp` 一定能抓到。
 *
 * 本类自包含：跑在目标进程里，不引用主项目其他类。de.robv.* 由 LSPosed 运行时提供。
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String TAG = "SeagullLsp";

    private static final String HOST_PACKAGE = Constants.SELF_PACKAGE;
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String AMAP_PACKAGE = "com.autonavi.minimap";

    /**
     * 需要「跨屏 displayId 注入」的网易云包。
     * 参考实现 xphook 只写了 IoT 版（NETEASE_PACKAGE = com.netease.cloudmusic.iot），
     * 但画中画2 绑的是哪个包由用户选（本仓库 arrays.xml 与 LSPosed 作用域里
     * com.netease.cloudmusic 主版也在），所以两个都覆盖，否则绑主版时点一下照样跳主屏。
     */
    private static final String[] NETEASE_PACKAGES = {
            "com.netease.cloudmusic.iot",
            "com.netease.cloudmusic",
    };

    /** PiP 窗口缩放比例：屏短边的 60%（对齐 xphook.TARGET_SIZE_PERCENT）。 */
    private static final float TARGET_SIZE_PERCENT = 0.6f;

    /** H1~H3 的作用包（对齐 WFreeHook.SCOPES）。 */
    private static final String[] TARGETS = {
            "com.autonavi.minimap",          // 高德地图（手机版）
            "com.autonavi.amapauto",         // 高德车机版
            "com.baidu.BaiduMap",            // 百度地图
            "com.tencent.map",               // 腾讯地图
            "com.netease.cloudmusic",        // 网易云音乐
            "com.netease.cloudmusic.iot",    // 网易云音乐 IoT（画中画2）
            "com.tencent.qqmusic",           // QQ 音乐
    };

    /**
     * 车机版应用通用名单（批次 CAR）—— 加一行包名即完成适配，零 per-app 代码。
     * 命中即获得全套通用钩子：H1 分屏谎报 / H3+H5 PiP 挂载 / H4 配置吸收 /
     * C0 画布内启动不跳走 / system_server 侧 PiP 标志强制。
     * 方向劫持（H2）不在这里，见 CAR_LANDSCAPE —— 车机版方向声明五花八门
     * （百度汽车版 3 个 portrait 登录页、网易云车机版 8 个 portrait 页面），
     * 无脑全横会炸设置/登录流，必须按包单独决定。
     */
    private static final String[] CAR_PKGS = {
            "com.tencent.qqmusiccar",        // QQ音乐车机版
            "com.kugou.android.auto",        // 酷狗音乐车机版
            "cn.kuwo.kwmusiccarsnp",         // 酷我音乐车机版
            "com.baidu.naviauto",            // 百度地图汽车版
    };

    /**
     * 需要整体强制横屏的车机版（音乐类：全部 activity 都不声明方向，
     * 在手机竖屏上会整个竖过来）。导航/有 portrait 关键页面的不进这张表，
     * 其主界面方向由各自 manifest 的 USER 声明 + 系统方向锁决定。
     */
    private static final String[] CAR_LANDSCAPE = {
            "com.tencent.qqmusiccar",
            "com.kugou.android.auto",
            "cn.kuwo.kwmusiccarsnp",
    };

    /** 方向开关（对齐 WFreeHook 的 prop 口径）：0 跟随 / 1 横屏(默认) / 2 竖屏。 */
    private static final String PROP_ENABLE = "persist.seagull.wf";
    private static final String PROP_ORIENTATION = "persist.seagull.wf.orientation";
    /**
     * 车机显示适配：密度伪装值（批次 CAR·H6）。0 = 关闭（默认，不改任何行为）。
     * 车机版 UI 按车机屏密度设计（常见 160/213/240dpi），手机 480dpi 下元素尺寸
     * 全错。滑杆（RootPanel）经 root setprop 写入，模块在目标进程读后覆写
     * DisplayMetrics.density/densityDpi/scaledDensity + Display.getMetrics/getRealMetrics。
     */
    private static final String PROP_DENSITY = "persist.seagull.car.density";

    /** 空气开关关闭时不装任何 hook，方便真机对比排查。 */
    private static boolean enabled() {
        return "1".equals(prop(PROP_ENABLE, "1"));
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpp) {
        String pkg = lpp.packageName;
        // 第一行无条件打，且放在 enabled()/白名单之前：这是「模块到底有没有被 LSPosed
        // 注进来」的唯一判据。之前放在后面，作用域勾错时日志里连一条来访记录都没有，
        // 排查时分不清「没加载」和「加载了但被开关/名单挡掉」。
        log("handleLoadPackage: " + pkg);
        if (HOST_PACKAGE.equals(pkg)) return;   // 桌面自己不要被自己的 hook 波及
        if (!enabled()) {
            log("disabled by " + PROP_ENABLE + ", skip " + pkg);
            return;
        }

        // 下面三段各自 try/catch，绝不能再用一个 try 把 A/B/C 全包住：
        // 只要 H1 在某个 ROM 上抛一次，后面的 H2（方向劫持——高德铺满全靠它）
        // 和 H3 就一条都装不上，表现正是「模块像没生效一样」。这是批次 Y 修的主问题。
        if (SYSTEMUI_PACKAGE.equals(pkg)) {
            try {
                log("已挂载 SystemUI → AmapPip 尺寸缩放 " + TARGET_SIZE_PERCENT);
                hookAmapPip(lpp.classLoader);
            } catch (Throwable t) {
                log("A 段(SystemUI)失败: " + t);
            }
            return;
        }

        // 系统框架（批次 PIP）：system_server 侧强制目标应用的 ActivityInfo 支持 PiP。
        // manifest 的 supportsPictureInPicture 标志由 system_server 在装包时解析，
        // 应用进程内的钩子够不着它 —— ActivityRecord.supportsPictureInPicture()
        // = 设备支持 && 标准 activity 类型 && ActivityInfo.supportsPictureInPicture()，
        // 前两者恒真，唯一卡点就是这个标志（已从本机 services.jar 反汇编坐实）。
        // 高德两个版本都没声明该标志，所以 enterPictureInPictureMode 永远被拒。
        // 【包名坑】AOSP 的 system_server 包名是 "android"，但 vivo 等 ROM 叫
        // "system"（实测本机 logcat: handleLoadPackage: system）—— 三个名字全匹配，
        // 否则钩子静默不装、PiP 永远挂不上，重启也不管用（2026-10-21 实测踩实）。
        if ("android".equals(pkg) || "system".equals(pkg) || "system_server".equals(pkg)) {
            try {
                hookPipFlag(lpp.classLoader);
            } catch (Throwable t) {
                log("系统框架 PiP 标志钩子失败: " + t);
            }
            return;
        }

        if (isNetease(pkg)) {
            try {
                log("已挂载 " + pkg + " → 注入 setLaunchDisplayId");
                hookNeteaseDisplayId(lpp.classLoader);
            } catch (Throwable t) {
                log("C 段(网易云)失败: " + t);
            }
            // 网易云同样需要 H1~H3 的窗口语义配合，继续往下走
        }

        boolean curated = hit(pkg);
        // 车机包名启发式（批次 CAR）：auto/car/iot/carsnp —— 命中即按车机版对待，
        // 无需把每个新车机 app 加进编译名单。只给「安全子集」（PiP + H4 + 密度），
        // 不给 H1（谎报分屏）和 C0（displayId 注入）—— 那两个对未知应用有破坏风险。
        boolean auto = !curated && isCarName(pkg);
        if (!curated && !auto) return;
        log("已挂载 " + pkg + (curated ? " → 窗口语义 + PiP" : " → 车机自动识别")
                + (hitLandscape(pkg) ? " + 横屏" : ""));
        if (curated) {
            try {
                hookMultiWindowPretence();
            } catch (Throwable t) {
                log("H1 失败: " + t);
            }
        }
        // H2 方向劫持只对 hitLandscape 生效（TARGETS + CAR_LANDSCAPE）：
        // 车机版里百度汽车版/网易云车机版有显式 portrait 的登录设置页，
        // 强行横屏会把那些页面扯变形，让它们保持 manifest 自己的方向声明。
        if (hitLandscape(pkg)) {
            try {
                hookOrientationOverride();
            } catch (Throwable t) {
                log("H2 失败: " + t);
            }
        }
        try {
            hookPictureInPicture();
        } catch (Throwable t) {
            log("H3 失败: " + t);
        }
        // H4（批次 CD）：配置变化吸收。画布尺寸/密度随进出单应用、Dock 收展一直变，
        // 每次都是一次 configuration change；应用没声明 configChanges 时 Activity
        // 被销毁重建，页内状态全丢（网易云歌词页 → 回桌面再进来变首页）。补上
        // 尺寸/方向/密度各位后系统改走 onConfigurationChanged，窗口照常重排、状态保住。
        try {
            hookConfigChangesAbsorb();
        } catch (Throwable t) {
            log("H4 失败: " + t);
        }
        // C0 总闸（批次 AG）：Instrumentation.execStartActivity 是所有 Activity 启动
        // 的必经终点，一条 hook 覆盖全部入口。只对 curated 名单生效（curated = TARGETS
        // ∪ CAR_PKGS）—— 给未知应用注入 displayId 有破坏风险，启发式命中的不给。
        if (curated) {
            try {
                hookExecStartActivity();
            } catch (Throwable t) {
                log("C0 失败: " + t);
            }
        }
        // D 段（批次 AA）：应用进程内的「窄屏强制竖屏」配置改写（网易云车机版
        // IotActivityBase.attachBaseContext 宽高比 ≤1.3 时 applyOverrideConfiguration
        // 锁竖屏，反编译坐实）。必须独立 try/catch，别让它失败牵连 H1~H3。
        try {
            hookPortraitConfigOverride();
        } catch (Throwable t) {
            log("D 失败: " + t);
        }
        // H6（批次 CAR）：车机密度伪装。curated 与启发式命中都吃；
        // 滑杆值为 0（默认）时整体关闭，一行日志都不打。
        try {
            hookDensitySpoof(lpp.classLoader);
        } catch (Throwable t) {
            log("H6 失败: " + t);
        }
    }

    /**
     * H6：密度伪装（批次 CAR）。把目标应用进程内的 DisplayMetrics 密度改写为
     * persist.seagull.car.density（0 = 关闭）。车机版 UI 按车机屏密度设计，
     * 手机 480dpi 下元素尺寸全错；这一条通杀所有车机版，值由 RootPanel 滑杆
     * root setprop 写入，实时生效（下次取 metrics 即用新值）。
     *
     * 钩三类，覆盖绝大多数取密度的路径：
     *   ① Resources.getDisplayMetrics —— 布局 inflate 的主路径（返回共享实例，
     *      原地改写即全局生效）；
     *   ②/③ Display.getMetrics / getRealMetrics —— 应用自己量屏幕的路径。
     */
    private void hookDensitySpoof(ClassLoader cl) {
        final int dpi = carDensity();
        if (dpi <= 0) return;   // 滑杆在 0 = 关闭，与默认行为完全一致
        final float density = dpi / 160f;
        XposedHelpers.findAndHookMethod("android.content.res.Resources", cl,
                "getDisplayMetrics", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            applyDensity((DisplayMetrics) param.getResult(), density, dpi);
                        } catch (Throwable ignored) {
                        }
                    }
                });
        for (String m : new String[]{"getMetrics", "getRealMetrics"}) {
            try {
                XposedHelpers.findAndHookMethod("android.view.Display", cl, m,
                        DisplayMetrics.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    applyDensity((DisplayMetrics) param.args[0], density, dpi);
                                } catch (Throwable ignored) {
                                }
                            }
                        });
            } catch (Throwable ignored) {
                // 个别 ROM 少一个重载，跳过即可
            }
        }
        log("H6 密度伪装 dpi=" + dpi + " density=" + density);
    }

    private static void applyDensity(DisplayMetrics dm, float density, int dpi) {
        if (dm == null) return;
        // getDisplayMetrics 是热路径（每次布局 inflate 都走）：已经是目标值就别重复写，
        // 让钩子开销只剩一次字段比较。
        if (dm.densityDpi == dpi && dm.density == density) return;
        dm.density = density;
        dm.densityDpi = dpi;
        dm.scaledDensity = density;
    }

    /** 密度滑杆值（0 = 关闭）。读不到按关闭处理，绝不乱改。 */
    private static int carDensity() {
        try {
            String v = prop(PROP_DENSITY, "0");
            int dpi = Integer.parseInt(v.trim());
            return (dpi >= 120 && dpi <= 640) ? dpi : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 车机包名启发式（批次 CAR）：auto / car / iot / carsnp。
     * 本仓库 5 个车机版实测全部命中（qqmusiccar / kugou.android.auto /
     * kwmusiccarsnp / naviauto / cloudmusic.iot），商店目录里 com.mgtv.auto、
     * com.baidu.car.radio、com.autochips.carplayapp 等同名规律的一并覆盖。
     */
    private static boolean isCarName(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        return p.contains("auto") || p.contains(".car") || p.contains("car.")
                || p.contains("carsnp") || p.contains(".iot");
    }

    // ===================== A) SystemUI：AmapPip 尺寸缩放 =====================
    // 逐条对齐 xphook.hookAmapPip。原理：任意应用进 PiP 时 SystemUI 调
    // PipBoundsAlgorithm 算窗口大小，我们只在「当前 PiP 是高德」时把结果放大到
    // 屏短边的 60%。带锚点 Activity 的场景下，这就等价于「可设置具体大小」。

    private void hookAmapPip(ClassLoader cl) {
        hookSetBoundsStateForEntry(cl);
        hookGetSizeForAspectRatioFloat(cl);
        hookGetSizeForAspectRatioSize(cl);
        hookAdjustSizeToAspectRatio(cl);
        hookTransformBoundsToAspectRatio(cl);
    }

    /** 记录当前进 PiP 的包名，供 isAmapPip 判定。 */
    private void hookSetBoundsStateForEntry(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.wm.shell.pip.PipBoundsState", cl,
                    "setBoundsStateForEntry",
                    ComponentName.class, ActivityInfo.class,
                    PictureInPictureParams.class,
                    "com.android.wm.shell.pip.PipBoundsAlgorithm",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            ComponentName cn = (ComponentName) param.args[0];
                            if (cn != null) {
                                CurrentPip.set(cn.getPackageName());
                                log("PiP entry: " + cn.getPackageName());
                            }
                        }
                    });
            log("hookSetBoundsStateForEntry OK");
        } catch (Throwable t) {
            log("hookSetBoundsStateForEntry failed: " + t);
        }
    }

    /** getSizeForAspectRatio(float, float, int, int)：显示宽高做参数的那条重载。 */
    private void hookGetSizeForAspectRatioFloat(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.wm.shell.pip.PipBoundsAlgorithm", cl,
                    "getSizeForAspectRatio",
                    float.class, float.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isAmapPip(param.thisObject)) return;
                            Size orig = (Size) param.getResult();
                            if (orig == null) return;
                            int displayShort = Math.min(
                                    (Integer) param.args[2], (Integer) param.args[3]);
                            Size scaled = scaleToTarget(orig, displayShort);
                            if (scaled != null) {
                                param.setResult(scaled);
                                log("getSizeForAspectRatio(f) " + orig.getWidth() + "x"
                                        + orig.getHeight() + " -> "
                                        + scaled.getWidth() + "x" + scaled.getHeight());
                            }
                        }
                    });
            log("hookGetSizeForAspectRatioFloat OK");
        } catch (Throwable t) {
            log("hookGetSizeForAspectRatioFloat failed: " + t);
        }
    }

    /** getSizeForAspectRatio(Size, float, float)：最小尺寸做参数的那条重载。 */
    private void hookGetSizeForAspectRatioSize(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.wm.shell.pip.PipBoundsAlgorithm", cl,
                    "getSizeForAspectRatio",
                    Size.class, float.class, float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isAmapPip(param.thisObject)) return;
                            Size orig = (Size) param.getResult();
                            if (orig == null) return;
                            int displayShort = getDisplayShort(param.thisObject);
                            if (displayShort <= 0) return;
                            Size scaled = scaleToTarget(orig, displayShort);
                            if (scaled != null) {
                                param.setResult(scaled);
                                log("getSizeForAspectRatio(S) " + orig.getWidth() + "x"
                                        + orig.getHeight() + " -> "
                                        + scaled.getWidth() + "x" + scaled.getHeight());
                            }
                        }
                    });
            log("hookGetSizeForAspectRatioSize OK");
        } catch (Throwable t) {
            log("hookGetSizeForAspectRatioSize failed: " + t);
        }
    }

    private void hookAdjustSizeToAspectRatio(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.wm.shell.pip.PipBoundsAlgorithm", cl,
                    "adjustSizeToAspectRatio",
                    Size.class, float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isAmapPip(param.thisObject)) return;
                            Size orig = (Size) param.getResult();
                            if (orig == null) return;
                            int displayShort = getDisplayShort(param.thisObject);
                            if (displayShort <= 0) return;
                            Size scaled = scaleToTarget(orig, displayShort);
                            if (scaled != null) {
                                param.setResult(scaled);
                                log("adjustSizeToAspectRatio " + orig.getWidth() + "x"
                                        + orig.getHeight() + " -> "
                                        + scaled.getWidth() + "x" + scaled.getHeight());
                            }
                        }
                    });
            log("hookAdjustSizeToAspectRatio OK");
        } catch (Throwable t) {
            log("hookAdjustSizeToAspectRatio failed: " + t);
        }
    }

    /** 只记日志，便于比对系统最终落位（对齐 xphook，不改结果）。 */
    private void hookTransformBoundsToAspectRatio(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.wm.shell.pip.PipBoundsAlgorithm", cl,
                    "transformBoundsToAspectRatio",
                    Rect.class, float.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isAmapPip(param.thisObject)) {
                                log("transformBoundsToAspectRatio input: " + param.args[0]);
                            }
                        }
                    });
            log("hookTransformBoundsToAspectRatio OK");
        } catch (Throwable t) {
            log("hookTransformBoundsToAspectRatio failed: " + t);
        }
    }

    /** 当前 PiP 是不是高德。判定口径对齐 xphook.isAmapPip。 */
    private static boolean isAmapPip(Object obj) {
        if (CurrentPip.isAmap()) return true;
        try {
            Object state = XposedHelpers.getObjectField(obj, "mPipBoundsState");
            if (state == null) return false;
            Object cn = XposedHelpers.callMethod(state, "getLastPipComponentName");
            if (cn instanceof ComponentName) {
                String pkg = ((ComponentName) cn).getPackageName();
                CurrentPip.set(pkg);
                return AMAP_PACKAGE.equals(pkg);
            }
        } catch (Throwable t) {
            log("isAmapPip failed: " + t);
        }
        return false;
    }

    /** 从 PipBoundsState 取屏短边。 */
    private static int getDisplayShort(Object obj) {
        try {
            Object state = XposedHelpers.getObjectField(obj, "mPipBoundsState");
            if (state == null) return 0;
            Object layout = XposedHelpers.callMethod(state, "getDisplayLayout");
            if (layout == null) return 0;
            int w = (Integer) XposedHelpers.callMethod(layout, "width");
            int h = (Integer) XposedHelpers.callMethod(layout, "height");
            return Math.min(w, h);
        } catch (Throwable t) {
            log("getDisplayShort failed: " + t);
            return 0;
        }
    }

    /** 把系统算出的 PiP 尺寸放大到屏短边的 60%。 */
    private static Size scaleToTarget(Size orig, int displayShort) {
        if (orig == null || displayShort <= 0) return null;
        int target = Math.round(displayShort * TARGET_SIZE_PERCENT);
        int minSide = Math.min(orig.getWidth(), orig.getHeight());
        if (minSide <= 0 || target <= minSide) return null;
        float ratio = (float) target / minSide;
        return new Size(
                Math.round(orig.getWidth() * ratio),
                Math.round(orig.getHeight() * ratio));
    }

    // ===================== B) H1~H3 窗口语义配合 =====================

    /**
     * H1：谎报「不在多窗口」—— 对齐 CarWithX gaodehook（复刻蓝图 §4.2，
     * 全文最反直觉、也最容易做反的一处）。
     *
     * 目标 App 在我们的虚拟屏画布里，窗口实际小于物理屏。如实上报会让它判定
     * 「我在小窗里」，进而走简化布局并锁竖屏 —— 塞进横屏画布就是竖版 + 左右黑边
     * （用户长期反馈的直接病根）。车联的解法是**一律回 false**：告诉它「你就是全屏」，
     * 它才肯按当前窗口尺寸铺满整块画布。
     *
     * 与上一版的差异：上一版返回 windowIsSmallerThanScreen（小屏时为 true），
     * 方向语义与参考**正好相反**，铺满一定失败 —— 批次 AB 按参考改正为恒 false，
     * 并补上 onMultiWindowModeChanged 回调里的同一谎报。
     */
    private void hookMultiWindowPretence() {
        XposedHelpers.findAndHookMethod(Activity.class, "isInMultiWindowMode",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.FALSE);          // ★ 谎报"不在多窗口"
                    }
                });
        // 回调里也告诉它"不在多窗口"（对齐 gaodehook 的 onMultiWindowModeChanged hook）
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onMultiWindowModeChanged",
                    boolean.class, android.content.res.Configuration.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0) param.args[0] = Boolean.FALSE;
                        }
                    });
            log("H1 onMultiWindowModeChanged OK");
        } catch (Throwable t) {
            log("H1 onMultiWindowModeChanged failed: " + t);
        }
        log("H1 hookMultiWindowPretence OK (谎报 isInMultiWindowMode=false)");
    }

    /**
     * H4（批次 CD）：配置变化吸收 —— 把「画布尺寸/密度/方向变化」从
     * Activity 销毁重建降级为 onConfigurationChanged 回调。
     *
     * 问题链：进出单应用、收展 Dock、拖分隔把手都会 resize 画布 → VD 跟着 resize
     * → 对应用这是一次 configuration change（screenSize/density 都在默认变化集里）
     * → 应用没在 manifest 声明 configChanges 时 Activity 被销毁重建 → 页内状态
     * 全部归零。用户看到的就是「网易云点进歌词页 → 桌面键回桌面 → 再进来变回首页」。
     * 车联助手没这个问题：它的 PIP 窗口尺寸恒定，应用一辈子不经历 size change；
     * 我们画布必须 resize，等价解法就是在 ActivityInfo.configChanges 里补位。
     *
     * 实现点：ActivityThread.performLaunchActivity 的 ActivityClientRecord.info
     * 是本进程实际使用的 ActivityInfo 副本，launch 前补上吸收位即可（manifest
     * 里的声明会与系统计算的 diff 合并，这里直接把位拉满最稳）。
     *
     * 位值（android.content.pm.ActivityInfo，DENSITY 为隐藏常量但值稳定）：
     * KEYBOARD 0x10 | KEYBOARD_HIDDEN 0x20 | ORIENTATION 0x80 | SCREEN_LAYOUT 0x100
     * | UI_MODE 0x200 | SCREEN_SIZE 0x400 | SMALLEST_SCREEN_SIZE 0x800 | DENSITY 0x1000
     * | FONT_SCALE 0x40000000。
     * 不吸收 LOCALE/触屏/导航：系统语言变化仍走重建，符合直觉。
     */
    private static final int ABSORB_CONFIG = 0x10 | 0x20 | 0x80 | 0x100 | 0x200
            | 0x400 | 0x800 | 0x1000 | 0x40000000;

    private void hookConfigChangesAbsorb() {
        final Class<?> atr;
        final Class<?> record;
        try {
            atr = Class.forName("android.app.ActivityThread", false, XposedEntry.class.getClassLoader());
            record = Class.forName("android.app.ActivityThread$ActivityClientRecord", false,
                    XposedEntry.class.getClassLoader());
        } catch (Throwable t) {
            log("H4 ActivityThread 拿不到: " + t);
            return;
        }
        final java.lang.reflect.Field info = activityInfoField(record);
        if (info == null) {
            log("H4 ActivityClientRecord 里找不到 ActivityInfo 字段（info/activityInfo 均无），跳过");
            return;
        }
        XposedHelpers.findAndHookMethod(atr, "performLaunchActivity", record,
                android.content.Intent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object r = param.args != null && param.args.length > 0 ? param.args[0] : null;
                        if (r == null) return;
                        ActivityInfo ai = (ActivityInfo) info.get(r);
                        if (ai == null) return;
                        if ((ai.configChanges & ABSORB_CONFIG) != ABSORB_CONFIG) {
                            ai.configChanges |= ABSORB_CONFIG;
                        }
                    }
                });
        log("H4 hookConfigChangesAbsorb OK (吸收 size/orientation/density 重配置)");
    }

    /**
     * H4 兜底：不同 ROM 的 ActivityThread$ActivityClientRecord 里 ActivityInfo 字段名不同——
     * 老 ROM（<=13）叫 info，Android 14+ 叫 activityInfo（AOSP 改名）。
     * 先按名字逐个试，再按字段类型扫一遍兜底；都拿不到返回 null，让 H4 体面跳过而不是炸钩子。
     */
    private static java.lang.reflect.Field activityInfoField(Class<?> record) {
        for (String name : new String[] { "info", "activityInfo" }) {
            try {
                java.lang.reflect.Field f = record.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        for (java.lang.reflect.Field f : record.getDeclaredFields()) {
            if (ActivityInfo.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    /**
     * H2：方向劫持（对齐 CarWithX gaodehook：任何「可能落成竖屏」的方向值，
     * 以及 UNSPECIFIED(-1)，一律改成目标方向；set 与 get 两面都改）。
     *
     * 竖屏系列（ActivityInfo）：
     *   PORTRAIT(1) / SENSOR_PORTRAIT(7) / REVERSE_PORTRAIT(9) / USER_PORTRAIT(12) / LOCKED(14)
     * 车联的判据就是「这些值全部改 0(LANDSCAPE)」；未指定(-1)也一并改，
     * 否则 App 首帧按 manifest 写死的 PORTRAIT 建窗 → 竖版 + 左右黑边。
     */
    private void hookOrientationOverride() {
        final int target = desiredOrientation();
        if (target == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return;   // 跟随模式：不干预

        XposedHelpers.findAndHookMethod(Activity.class, "setRequestedOrientation",
                int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int req = (Integer) param.args[0];
                        // 批次 AA 扩写：竖屏系列之外，sensor(4)/fullSensor(10)/user(2)/
                        // fullUser(13) 也可能落成竖屏或跟随显示方向 —— 网易云车机版
                        // manifest 全家就是 screenOrientation="sensor"，旧判据放行后
                        // 首帧方向由 VD 自然方向决定，强制横屏就不成立。改写口径：
                        // 与 target 同侧的值放行，异侧与跟随类一律改 target。
                        if (shouldRewrite(req, target)) {
                            param.args[0] = target;
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(Activity.class, "getRequestedOrientation",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object cur = param.getResult();
                        if (!(cur instanceof Integer)) return;
                        if ((Integer) cur == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return;
                        param.setResult(target);
                    }
                });

        // H2b：创建后主动调一次 setRequestedOrientation —— 上面两条 hook 只改得动
        // 「应用自己调用/读回」的方向，改不动 WM 侧的 ActivityRecord。高德首屏 Activity
        // 的 screenOrientation=1(PORTRAIT) 写在 manifest 里，它不主动调 set 的那一刻，
        // WM 就按竖屏建窗，画中画第一帧必然是竖版 + 两侧黑边（批次 W 立项时真机截图坐实）。
        // 这里在 onCreate 后补一次显式请求，让首帧就是目标方向；
        // 这次调用会再进 setRequestedOrientation 的 hook，参数已是目标值，原样放行，无递归。
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity a = (Activity) param.thisObject;
                        try {
                            a.setRequestedOrientation(target);
                        } catch (Throwable ignored) {
                            // 个别 ROM 上此处窗口还没挂，忽略即可
                        }
                        beat(a);
                    }
                });

        // 对齐 gaodehook：每次 onResume 再补一次 —— 跳页回来后 manifest 方向可能又被
        // WM 按原值重建窗口，只在 onCreate 补会漏掉「回来那一次」。
        XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity a = (Activity) param.thisObject;
                        try {
                            a.setRequestedOrientation(target);
                        } catch (Throwable ignored) {
                        }
                    }
                });
        log("H2 hookOrientationOverride OK -> " + target);
    }

    /** 竖屏系列方向值（CarWithX gaodehook.isPortrait 同款判据）。 */
    private static boolean isPortraitSeries(int o) {
        return o == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT          // 1
                || o == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT   // 7
                || o == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT  // 9
                || o == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT     // 12
                || o == ActivityInfo.SCREEN_ORIENTATION_LOCKED;           // 14
    }

    /**
     * setRequestedOrientation 的改写判据（批次 AA，替换旧「竖屏系列 or UNSPECIFIED」）。
     * 旧判据放行了 sensor(4)/fullSensor(10)/user(2)/fullUser(13) —— 这些值语义是
     * 「跟随显示/传感器」，在物理屏上跟随用户习惯没毛病，但我们的目标是把应用
     * 钉死在一块方向固定的虚拟屏上，跟随类必须一并接管，否则：
     *   · 网易云车机版 manifest 全家 screenOrientation="sensor" → 旧判据一条不改；
     *   · 应用自己 setRequestedOrientation(SENSOR) 做横竖切换 → 把 WM 侧又带跑。
     * 口径：请求值与 target 已经同侧（都横 / 都竖）放行；异侧、竖/横系列、
     * 跟随类、LOCKED、UNSPECIFIED 一律改 target。
     */
    private static boolean shouldRewrite(int req, int target) {
        if (req == target) return false;
        boolean targetLandscape = target != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        boolean reqPortrait = isPortraitSeries(req)
                || req == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        boolean reqLandscape = req == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE            // 0
                || req == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE                 // 6
                || req == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;               // 8
        boolean reqFollow = req == ActivityInfo.SCREEN_ORIENTATION_SENSOR                  // 4
                || req == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR                      // 10
                || req == ActivityInfo.SCREEN_ORIENTATION_USER                             // 2
                || req == ActivityInfo.SCREEN_ORIENTATION_FULL_USER                        // 13
                || req == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED                      // -1
                || req == ActivityInfo.SCREEN_ORIENTATION_LOCKED                           // 14
                || req == ActivityInfo.SCREEN_ORIENTATION_NOSENSOR                         // 5
                || req == ActivityInfo.SCREEN_ORIENTATION_BEHIND;                          // 3
        if (targetLandscape) return reqPortrait || reqFollow;
        return reqLandscape || reqFollow;
    }

    /**
     * D 段：掐掉应用自带的「窄屏强制竖屏」配置改写（批次 AA，反编译坐实）。
     *
     * 网易云车机版所有 Activity 的基座 IotActivityBase.attachBaseContext 里：
     *   宽高比 ≤ 1.3 → new Configuration(); orientation = 1(竖屏);
     *                  applyOverrideConfiguration(configuration);
     * 这是在**应用进程内**把自己 Resources/Window 的配置按竖屏摆 —— 走的是
     * ContextThemeWrapper 层，WM 方向 hook（H2）与 ActivityInfo 根本够不着它。
     * 画布一不够宽（比例 ≤1.3，桌面分屏小卡经常如此），整个应用 UI 立刻切回
     * 竖屏形态（顶部 Tab、窄版布局），这就是「模块明明挂上了还是竖版」的另一条病根。
     *
     * hook 挂在框架类 android.view.ContextThemeWrapper 上（公开 API，不受混淆影响），
     * 只改「显式要求竖屏」的 override：目标方向是横屏时改成 2(横屏)，其余原样放行
     * （含 orientation=UNDEFINED 的普通调用，比如 AppCompat 内部的合法用法）。
     */
    private void hookPortraitConfigOverride() {
        XposedHelpers.findAndHookMethod(
                android.view.ContextThemeWrapper.class,
                "applyOverrideConfiguration",
                android.content.res.Configuration.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        android.content.res.Configuration c =
                                (android.content.res.Configuration) param.args[0];
                        if (c == null) return;
                        if (c.orientation
                                != android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                            return;   // 只拦显式竖屏，其余放行
                        }
                        int target = desiredOrientation();
                        if (target == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                || target == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                            return;   // 跟随模式 / 目标本就是竖屏：别动应用自己的选择
                        }
                        c.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                        log("D 改写 applyOverrideConfiguration: 竖屏 → 横屏 ("
                                + param.thisObject.getClass().getName() + ")");
                    }
                });
        log("D hookPortraitConfigOverride OK");
    }

    /**
     * H3：修正画中画参数。
     * sourceRectHint 铺满整屏，避免小窗只裁出一角；比例为整屏比例，
     * 交给系统按合法区间收敛。onPictureInPictureRequested 直接放行。
     */
    private void hookPictureInPicture() {
        XposedHelpers.findAndHookMethod(Activity.class, "setPictureInPictureParams",
                PictureInPictureParams.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Activity a = (Activity) param.thisObject;
                        PictureInPictureParams fixed = fullScreenParams(a);
                        if (fixed != null) param.args[0] = fixed;
                    }
                });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            XposedHelpers.findAndHookMethod(Activity.class, "onPictureInPictureRequested",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity a = (Activity) param.thisObject;
                            PictureInPictureParams p = fullScreenParams(a);
                            if (p != null) {
                                try {
                                    a.setPictureInPictureParams(p);
                                } catch (Throwable ignored) {
                                    // 少数 ROM 上此处校验未就绪，放行即可，不影响返回 true
                                }
                            }
                            param.setResult(Boolean.TRUE);
                        }
                    });
        }
        // H5（批次 PIP）：按 Home/切走 = 直接进 PiP。onUserLeaveHint 只在用户主动
        // 离开（Home/Recents/去别的 app）时触发，任务内跳 Activity 不触发 —— 正是
        // 「随时挂载画中画」的入口。应用自己从不调 enterPictureInPictureMode，只能由
        // 模块代劳；system_server 侧标志已强制打开（见 hookPipFlag），这里调用才会被放行。
        XposedHelpers.findAndHookMethod(Activity.class, "onUserLeaveHint",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity a = (Activity) param.thisObject;
                        try {
                            if (a.isInMultiWindowMode() || a.isInPictureInPictureMode()) return;
                            PictureInPictureParams p = fullScreenParams(a);
                            if (p == null) return;
                            boolean ok = a.enterPictureInPictureMode(p);
                            log("H5 onUserLeaveHint → enterPip=" + ok + " ("
                                    + a.getClass().getName() + ")");
                        } catch (Throwable t) {
                            log("H5 onUserLeaveHint 进 PiP 失败: " + t);
                        }
                    }
                });
        log("H3 hookPictureInPicture OK");
    }

    /**
     * 系统框架侧（批次 PIP）：把目标应用的 ActivityInfo.supportsPictureInPicture()
     * 强制返回 true。闸门链（本机 services.jar 反汇编确认）：
     *   ActivityRecord.supportsPictureInPicture() = 设备支持 && 标准 activity 类型
     *       && ActivityInfo.supportsPictureInPicture()
     * 前两者对高德恒真，唯一卡点是 manifest 标志 —— 高德手机版/车机版都没声明，
     * 导致 enterPictureInPictureMode 在 system_server 被静默拒绝（PiP 挂不上的根因）。
     * 钩子体保持最小：只按包名放行，其它应用一律不碰。
     */
    private void hookPipFlag(ClassLoader cl) {
        XposedHelpers.findAndHookMethod("android.content.pm.ActivityInfo", cl,
                "supportsPictureInPicture", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            ActivityInfo ai = (ActivityInfo) param.thisObject;
                            if (ai == null) return;
                            String p = ai.packageName;
                            // curated 名单 + 车机包名启发式一并放行 —— 车机版 5/5
                            // 都没声明 supportsPictureInPicture，这里补上。
                            if (p != null && (hit(p) || isCarName(p))) {
                                param.setResult(Boolean.TRUE);
                            }
                        } catch (Throwable ignored) {
                            // 钩子体内绝不外抛：system_server 里抛异常会连带拖垮调用方
                        }
                    }
                });
        log("系统框架已挂载 → 目标应用 PiP 标志强制开启（TARGETS）");
    }

    private static PictureInPictureParams fullScreenParams(Activity a) {
        try {
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            PictureInPictureParams.Builder b = new PictureInPictureParams.Builder()
                    .setSourceRectHint(new Rect(0, 0, dm.widthPixels, dm.heightPixels))
                    .setAspectRatio(new Rational(dm.widthPixels, dm.heightPixels));
            // Android 12+ 的 Home 自动进 PiP 要求参数里带 auto-enter（API 31 才有，
            // 低版本静默跳过 —— onUserLeaveHint 那条路不依赖它）
            if (Build.VERSION.SDK_INT >= 31) {
                b.setAutoEnterEnabled(true);
            }
            return b.build();
        } catch (Throwable t) {
            return null;
        }
    }

    // ===================== C) 网易云 IoT：跨屏 displayId 注入 =====================
    // 不带这套 hook，网易云在虚拟屏/画中画里点出的新 Activity 会落到主屏，
    // 画中画2 一碰就跳走（对齐 xphook.hookNeteasePip 的根因说明）。
    //
    // 【对参考实现的一处修正】xphook 里那两条 `Context.startActivity` 是死 hook：
    // Context 是抽象基类，startActivity 在 ContextWrapper / ContextImpl 里都被 override，
    // 虚拟分派永远不会走到 Context 上声明的那份实现，挂上去一次也不会触发。
    // 网易云 IoT 的播放页正是 Service/Application 侧拉起的，所以这里改挂
    // android.app.ContextImpl（所有非 Activity Context 的最终实现，ContextWrapper 的
    // mBase 就是它）—— 这样才能真的盖住 Service/Application 起的 Activity。
    //
    // 【批次 AG：C0 总闸】C1~C6 挂在 startActivity 的 6 个入口上，但它们不是全部
    // —— 所有 Activity 启动的必经终点是 Instrumentation.execStartActivity
    // （Activity.startActivityForResult 与 ContextImpl.startActivity 最终都调它）。
    // 总闸一条 = 全路径覆盖，而且对所有 TARGETS（高德/百度/QQ音乐…）都生效，
    // 不再只是网易云。挂了 C0 之后 C1~C6 变冗余，保留作双保险（hook 幂等）。
    //
    // 每条 hook 各自 try/catch：某台 ROM 上少了某个方法签名，不该连带把后面几条全吞掉
    // （参考实现就是每条一个 try/catch）。

    private void hookNeteaseDisplayId(ClassLoader cl) {
        // ① Activity.startActivity(Intent)
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "startActivity", Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity a = (Activity) param.thisObject;
                            Intent intent = (Intent) param.args[0];
                            int id = displayIdOf(a);
                            if (id < 0 || !shouldPin(intent)) return;
                            Bundle b = injectDisplayId(id, intent, null);
                            if (b != null) {
                                a.startActivity(intent, b);
                                param.setResult(null);
                                log("注入 displayId=" + id + " startActivity(Intent)");
                            }
                        }
                    });
            log("C1 Activity.startActivity(Intent) OK");
        } catch (Throwable t) {
            log("C1 Activity.startActivity(Intent) failed: " + t);
        }

        // ② Activity.startActivity(Intent, Bundle)
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "startActivity",
                    Intent.class, Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity a = (Activity) param.thisObject;
                            Intent intent = (Intent) param.args[0];
                            Bundle orig = (Bundle) param.args[1];
                            Bundle now = injectDisplayId(displayIdOf(a), intent, orig);
                            if (now != null && now != orig) {
                                param.args[1] = now;
                                log("注入 displayId startActivity(Intent, Bundle)");
                            }
                        }
                    });
            log("C2 Activity.startActivity(Intent, Bundle) OK");
        } catch (Throwable t) {
            log("C2 Activity.startActivity(Intent, Bundle) failed: " + t);
        }

        // ③ ContextImpl.startActivity(Intent) —— 非 Activity 的 Context（Service/Application）
        try {
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", cl,
                    "startActivity", Intent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context ctx = (Context) param.thisObject;
                            if (ctx instanceof Activity) return;   // Activity 那两条已接管
                            Intent intent = (Intent) param.args[0];
                            int id = displayIdOf(ctx);
                            if (id < 0 || !shouldPin(intent)) return;
                            Bundle b = injectDisplayId(id, intent, null);
                            if (b != null) {
                                ctx.startActivity(intent, b);
                                param.setResult(null);
                                log("注入 displayId=" + id + " ContextImpl.startActivity(Intent)");
                            }
                        }
                    });
            log("C3 ContextImpl.startActivity(Intent) OK");
        } catch (Throwable t) {
            log("C3 ContextImpl.startActivity(Intent) failed: " + t);
        }

        // ④ ContextImpl.startActivity(Intent, Bundle)
        try {
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", cl,
                    "startActivity", Intent.class, Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Context ctx = (Context) param.thisObject;
                            if (ctx instanceof Activity) return;
                            Intent intent = (Intent) param.args[0];
                            Bundle orig = (Bundle) param.args[1];
                            Bundle now = injectDisplayId(displayIdOf(ctx), intent, orig);
                            if (now != null && now != orig) {
                                param.args[1] = now;
                                log("注入 displayId ContextImpl.startActivity(Intent, Bundle)");
                            }
                        }
                    });
            log("C4 ContextImpl.startActivity(Intent, Bundle) OK");
        } catch (Throwable t) {
            log("C4 ContextImpl.startActivity(Intent, Bundle) failed: " + t);
        }

        // ⑤ Activity.startActivityForResult(Intent, int, Bundle)
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "startActivityForResult",
                    Intent.class, int.class, Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity a = (Activity) param.thisObject;
                            Intent intent = (Intent) param.args[0];
                            Bundle orig = (Bundle) param.args[2];
                            Bundle now = injectDisplayId(displayIdOf(a), intent, orig);
                            if (now != null && now != orig) {
                                param.args[2] = now;
                                log("注入 displayId startActivityForResult");
                            }
                        }
                    });
            log("C5 Activity.startActivityForResult(3) OK");
        } catch (Throwable t) {
            log("C5 Activity.startActivityForResult(3) failed: " + t);
        }

        // ⑥ Activity.startActivityForResult(Intent, int)
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "startActivityForResult",
                    Intent.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity a = (Activity) param.thisObject;
                            Intent intent = (Intent) param.args[0];
                            int id = displayIdOf(a);
                            if (id < 0 || !shouldPin(intent)) return;
                            Bundle b = injectDisplayId(id, intent, null);
                            if (b != null) {
                                int req = (Integer) param.args[1];
                                a.startActivityForResult(intent, req, b);
                                param.setResult(null);
                                log("注入 displayId=" + id + " startActivityForResult");
                            }
                        }
                    });
            log("C6 Activity.startActivityForResult(2) OK");
        } catch (Throwable t) {
            log("C6 Activity.startActivityForResult(2) failed: " + t);
        }

        log("C hookNeteaseDisplayId OK");
    }

    // ===================== C0 总闸：Instrumentation.execStartActivity =====================

    /**
     * ActivityOptions 里 launchDisplayId 的 Bundle 键。AOSP 常量是 @hide
     * （ActivityOptions.KEY_LAUNCH_DISPLAY_ID），但字符串自 API 26 引入该功能起
     * 一直稳定；系统侧 ActivityStarter 从 options Bundle 里按这个键读显示，
     * 未知键被忽略 —— 所以直接 putInt 进应用自带的 Bundle 也能生效，
     * 动画/共享元素等自有参数原样保留。
     */
    private static final String KEY_LAUNCH_DISPLAY_ID = "android.activity.launchDisplayId";

    /**
     * C0 总闸（批次 AG）：hook Instrumentation.execStartActivity —— 应用进程内
     * 所有 Activity 启动（Activity.startActivityForResult、ContextImpl.startActivity、
     * Service/Application 侧拉起）最终都经过它。一条 hook = 全路径覆盖，
     * C1~C6 那 6 个入口挂不全的漏网路径全部在这里收口。
     *
     * 规则：
     *   · 启动方（源 Activity / Context）不在虚拟屏上（displayId=0，用户正常在手机
     *     上用）→ 不干预，绝不能把主屏使用也钉到画布；
     *   · 目标是自己应用的组件（或解析不出组件）→ 注入 launchDisplayId 留在画布
     *     ——「画布内导航不出画布」；
     *   · 目标是别的应用（外跳浏览器/分享）→ 不注入，交给系统正常解析。
     */
    private void hookExecStartActivity() {
        // 【批次 AG】桩 jar（api-82）没有 XposedBridge.hookAllMethods 便捷 API，
        // 手动枚举同名重载逐个 hookMethod —— 顺带把「某条重载挂不上」的失败
        // 收敛到单条日志，不再牵连其余重载。
        int n = 0;
        for (java.lang.reflect.Method m
                : android.app.Instrumentation.class.getDeclaredMethods()) {
            if (!"execStartActivity".equals(m.getName())) continue;
            try {
                XposedBridge.hookMethod(m, mExecHook);
                n++;
            } catch (Throwable t) {
                log("C0 hook " + m.getName() + " 失败: " + t);
            }
        }
        if (n == 0) throw new IllegalStateException("execStartActivity 一条重载都没挂上");
        log("C0 hookExecStartActivity OK (" + n + " 个重载)");
    }

    /** C0 的 hook 体（在 hookExecStartActivity 里逐重载挂载）。 */
    private final XC_MethodHook mExecHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Activity srcAct = null;
                Context who = null;
                Intent intent = null;
                Bundle options = null;
                int optIdx = -1;
                Object[] args = param.args;
                // options 槽定位：已知签名里 options 都带 Bundle（值可能为 null
                // —— null instanceof 不成立，不能纯按类型扫）。从尾部扫 Bundle，
                // 没有且末参是 null 时认定末参就是 options 槽（options 恒为末参）。
                for (int i = args.length - 1; i >= 0; i--) {
                    if (args[i] instanceof Bundle) {
                        options = (Bundle) args[i];
                        optIdx = i;
                        break;
                    }
                }
                if (optIdx < 0 && args.length > 0 && args[args.length - 1] == null) {
                    optIdx = args.length - 1;
                }
                for (int i = 0; i < args.length; i++) {
                    Object a = args[i];
                    if (a instanceof Activity && srcAct == null) srcAct = (Activity) a;
                    if (a instanceof Context && who == null) who = (Context) a;
                    if (a instanceof Intent && intent == null) intent = (Intent) a;
                }
                if (intent == null || optIdx < 0) return;
                int id = srcAct != null ? displayIdOf(srcAct)
                        : (who != null ? displayIdOf(who) : -1);
                if (id < 0) return;   // 启动方在主屏：不干预
                // 只钉「应用自己内部跳转」：目标组件是本应用，或解析不出。
                // 外跳别的应用（浏览器/分享）不钉，否则外跳会被塞进私有画布被拒。
                ComponentName cn = intent.getComponent();
                String self = who != null ? who.getPackageName()
                        : (srcAct != null ? srcAct.getPackageName() : null);
                if (cn != null && self != null && !cn.getPackageName().equals(self)) {
                    return;
                }
                if (options != null) {
                    options.putInt(KEY_LAUNCH_DISPLAY_ID, id);
                    log("C0 注入 displayId=" + id + " execStartActivity "
                            + (cn != null ? cn.getClassName() : "(隐式)"));
                } else {
                    ActivityOptions o = ActivityOptions.makeBasic();
                    o.setLaunchDisplayId(id);
                    param.args[optIdx] = o.toBundle();
                    log("C0 注入 displayId=" + id + " execStartActivity (新建 options)");
                }
            } catch (Throwable t) {
                log("C0 execStartActivity 处理异常: " + t);
            }
        }
    };

    /** 当前 Activity 所在 displayId；在主屏(0)上返回 -1（无需注入）。 */
    private static int displayIdOf(Activity a) {
        // 优先 getDisplay()：Activity 在虚拟屏上时它比 WindowManager 更准（对齐参考）
        try {
            Display d = a.getDisplay();
            if (d != null && d.getDisplayId() > 0) return d.getDisplayId();
        } catch (Throwable ignored) {
            // API < 30 或异常时退回 WindowManager
        }
        return displayIdOf((Context) a);
    }

    /** 非 Activity Context（Service / Application）的 displayId。 */
    private static int displayIdOf(Context ctx) {
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return -1;
            Display d = wm.getDefaultDisplay();
            if (d == null) return -1;
            int id = d.getDisplayId();
            return id > 0 ? id : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 只钉「应该留在本屏」的 Activity，避免把外跳链接也钉死。 */
    private static boolean shouldPin(Intent intent) {
        if (intent == null) return false;
        ComponentName cn = intent.getComponent();
        if (cn == null) return true;   // 解析不出目标时按参考口径全钉
        String cls = cn.getClassName();
        if (cls == null) return false;
        if (cls.contains("Iot") || cls.contains("Player")) return true;
        return cls.startsWith("com.netease.cloudmusic.");
    }

    /**
     * 把 setLaunchDisplayId 写进 options Bundle。
     *
     * 【批次 AG 重写】旧实现对「已有 Bundle」走 ActivityOptions.fromBundle 反射 ——
     * fromBundle 是 @hide，API 28+ 被 hidden-api 拦截，反射在真机上**必失败**，
     * 返回原样 → C2/C4/C5（凡是应用自带 options 的路径）静默一条都没注入。
     * 这正是「模块挂上了还是跳」的代码级根因。
     *
     * 新实现直接往 Bundle 里 putInt(KEY_LAUNCH_DISPLAY_ID)：系统侧 ActivityStarter
     * 拿到 Bundle 按同一键读显示，未知键忽略、该键读取 —— 应用自带的动画/共享元素
     * 参数原样保留，零 hidden-api 依赖。
     */
    private static Bundle injectDisplayId(int id, Intent intent, Bundle existing) {
        if (id < 0 || !shouldPin(intent)) return existing;
        try {
            if (existing != null) {
                existing.putInt(KEY_LAUNCH_DISPLAY_ID, id);
                return existing;
            }
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(id);
            return options.toBundle();
        } catch (Throwable t) {
            log("injectDisplayId failed: " + t);
            return existing;
        }
    }

    // ===================== 配置 / 工具 =====================

    /**
     * 注入心跳（批次 W4）：hook 进程第一次 Activity onCreate 时向桌面发显式广播，
     * 桌面 RootPanel 诊断行显示「已注入 ✓」—— 用户不必抓 logcat 就能区分
     * 「模块没被 LSPosed 注入」（最常见：作用域没勾/更新后没重启目标应用）和
     * 「注入了但 hook 没起效」。60s 去重；任何异常吞掉，绝不影响宿主进程。
     */
    private static volatile long lastBeat;

    private static void beat(Activity a) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastBeat < 60_000L) return;
        lastBeat = now;
        try {
            android.content.Intent i = new android.content.Intent(HookDiagnostics.ACTION);
            i.setPackage("com.seagull.carlauncher");
            i.putExtra(HookDiagnostics.EXTRA_PKG, a.getPackageName());
            a.getApplicationContext().sendBroadcast(i);
        } catch (Throwable ignore) {}
    }

    private static int desiredOrientation() {
        String v = prop(PROP_ORIENTATION, "1");
        if ("2".equals(v)) return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if ("0".equals(v)) return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
    }

    /** SystemProperties 不在公开 SDK 里，只能反射；读失败按默认值走。
     * 审计 L5：Method 对象缓存 —— D 钩子在每个应用每次 Activity 创建时都读
     * prop（applyOverrideConfiguration 拦截器高频路径），旧实现每次
     * Class.forName + getMethod，反射查找本身就是几百微秒的税。 */
    private static volatile java.lang.reflect.Method SP_GET;
    private static volatile boolean spGetTried;

    private static String prop(String key, String def) {
        try {
            java.lang.reflect.Method m = SP_GET;
            if (m == null) {
                if (spGetTried) return def;   // 上次查找已失败，别反复付学费
                synchronized (XposedEntry.class) {
                    if (!spGetTried) {
                        spGetTried = true;
                        SP_GET = Class.forName("android.os.SystemProperties")
                                .getMethod("get", String.class, String.class);
                    }
                }
                m = SP_GET;
                if (m == null) return def;
            }
            Object v = m.invoke(null, key, def);
            return v instanceof String ? (String) v : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /** 双写：Log 进 logcat 能直接抓，XposedBridge.log 进 LSPosed 日志留档。 */
    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }

    /** 窗口语义 + PiP 的命中判定：TARGETS ∪ CAR_PKGS（PiP 标志钩子也用它）。 */
    private static boolean hit(String pkg) {
        for (String t : TARGETS) {
            if (t.equals(pkg)) return true;
        }
        for (String t : CAR_PKGS) {
            if (t.equals(pkg)) return true;
        }
        return false;
    }

    /** 方向劫持的命中判定：TARGETS ∪ CAR_LANDSCAPE（只横屏化确认安全的包）。 */
    private static boolean hitLandscape(String pkg) {
        for (String t : TARGETS) {
            if (t.equals(pkg)) return true;
        }
        for (String t : CAR_LANDSCAPE) {
            if (t.equals(pkg)) return true;
        }
        return false;
    }

    private static boolean isNetease(String pkg) {
        for (String t : NETEASE_PACKAGES) {
            if (t.equals(pkg)) return true;
        }
        return false;
    }

    /** 当前 PiP 包名追踪（volatile 保证跨线程可见）。 */
    private static class CurrentPip {
        private static volatile String sPackageName;

        static void set(String pkg) { sPackageName = pkg; }
        static String get() { return sPackageName; }
        static boolean isAmap() { return AMAP_PACKAGE.equals(sPackageName); }
    }
}