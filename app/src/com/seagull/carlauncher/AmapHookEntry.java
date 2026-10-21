package com.seagull.carlauncher;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 高德地图专用 hook（从零重写，替代 com.example.gaodehook.HookEntry 车联克隆）。
 *
 * 依据：对官方最新正式版高德 17.01.0.2012（versionCode 170100，2026-10-12 构建）的
 * 静态解剖结论——海鸥对高德的全部诉求都落在框架层 android.app.Activity 上，
 * 高德内部类怎么重构都碰不到 hook 面；其自带的 mPaaS FindHook 风控检测
 * （查 de.robv.android.xposed.installer 包名 + 调用栈找 XposedBridge 帧）
 * 只做风控上报、无功能封禁，LSPosed 管理器包名 org.lsposed.manager 不在检测名单。
 *
 * 与 XposedEntry 的分工：XposedEntry 的 H1~H3 面向 7 个地图/音乐包做窗口语义；
 * 本类只盯高德（手机版 com.autonavi.minimap + 车机版 com.autonavi.amapauto），
 * 做全生命周期横屏断言 + 沉浸式铺满，8 个 hook 各自独立 try/catch。
 *
 * 日志口径（排查用）：
 *   logcat:   一律打在 SeagullLsp 标签下（自检面板 logcat -s SeagullLsp 能抓到）；
 *   LSPosed:  XposedBridge.log 带 "SeagullAmap:" 前缀（modules_*.log 里
 *             grep SeagullAmap 即可判定注入是否存活，不再依赖车联的 GaodeHook 串）。
 *
 * 空气开关：persist.seagull.amap（1=开，默认；0=关，关掉后只留一条来访日志）。
 */
public class AmapHookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "SeagullLsp";
    private static final String PREFIX = "SeagullAmap";

    private static final String PKG_PHONE = "com.autonavi.minimap";
    private static final String PKG_CAR = "com.autonavi.amapauto";

    private static final String PROP_ENABLE = "persist.seagull.amap";

    /** 每类事件只详记前 3 次 + 之后每 100 次，防 modules 日志被刷爆。 */
    private static final int LOG_HEAD = 3;
    private static final int LOG_EVERY = 100;

    private static final int[] COUNTERS = new int[8];

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpp) {
        String pkg = lpp.packageName;
        // 无条件首行：本入口是否被 LSPosed 注入的唯一判据，放在一切过滤之前。
        log("handleLoadPackage: " + pkg);
        if (!PKG_PHONE.equals(pkg) && !PKG_CAR.equals(pkg)) return;
        if (!"1".equals(prop(PROP_ENABLE, "1"))) {
            log("disabled by " + PROP_ENABLE + ", skip " + pkg);
            return;
        }

        final ClassLoader cl = lpp.classLoader;
        int ok = 0;

        // 1. 方向请求劫持：高德要竖屏（1/7/9/12）一律改判横屏。
        //    这是铺满的根：高德在 onCreate/onResume/配置变化里反复把方向改回竖屏，
        //    单点 hook 会被它二次覆盖，所以必须全生命周期断言（3~7 号 hook）。
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl,
                    "setRequestedOrientation", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int req = (Integer) param.args[0];
                    if (isPortrait(req)) {
                        param.args[0] = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                        tick(0, "setRequestedOrientation " + req + " -> landscape");
                    }
                }
            });
            ok++;
        } catch (Throwable t) {
            log("hook1 setRequestedOrientation 失败: " + t);
        }

        // 2. 方向查询说谎：高德内部拿「当前方向」做 UI 分支，如实回竖屏会走竖屏布局。
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl,
                    "getRequestedOrientation", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    if (r instanceof Integer && isPortrait((Integer) r)) {
                        param.setResult(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                        tick(1, "getRequestedOrientation " + r + " -> landscape");
                    }
                }
            });
            ok++;
        } catch (Throwable t) {
            log("hook2 getRequestedOrientation 失败: " + t);
        }

        // 3~7. 生命周期断言：每个可能被高德改回竖屏/清掉全屏旗标的时点都补一刀。
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl,
                    "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    forceLandscape((Activity) param.thisObject, "onCreate");
                    beat((Activity) param.thisObject);
                    tick(2, "onCreate asserted " + who(param.thisObject));
                }
            });
            ok++;
        } catch (Throwable t) {
            log("hook3 onCreate 失败: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl,
                    "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    forceLandscape((Activity) param.thisObject, "onResume");
                    tick(3, "onResume asserted " + who(param.thisObject));
                }
            });
            ok++;
        } catch (Throwable t) {
            log("hook4 onResume 失败: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl,
                    "onConfigurationChanged", Configuration.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    forceLandscape((Activity) param.thisObject, "onConfigChanged");
                    tick(4, "onConfigurationChanged asserted " + who(param.thisObject));
                }
            });
            ok++;
        } catch (Throwable t) {
            log("hook5 onConfigurationChanged 失败: " + t);
        }

        // 多窗口回调在 API 26+ 是双参版本，老 ROM 只有一参；两个都挂，
        // 哪个平台都能接到「高德被塞进自由窗口」的时点。
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl,
                    "onMultiWindowModeChanged", boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    forceLandscape((Activity) param.thisObject, "onMultiWindow");
                    tick(5, "onMultiWindowModeChanged asserted " + who(param.thisObject));
                }
            });
            ok++;
        } catch (Throwable t) {
            log("hook6 onMultiWindowModeChanged(1) 失败: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl,
                    "onMultiWindowModeChanged", boolean.class, Configuration.class,
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    forceLandscape((Activity) param.thisObject, "onMultiWindowC");
                    tick(6, "onMultiWindowModeChanged(C) asserted " + who(param.thisObject));
                }
            });
            ok++;
        } catch (Throwable t) {
            log("hook7 onMultiWindowModeChanged(2) 失败(老 ROM 无此重载, 可忽略): " + t);
        }

        // 8. 拿到焦点时补全屏旗标：状态栏在这时最容易复现。
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl,
                    "onWindowFocusChanged", boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(Boolean) param.args[0]) return;
                    forceLandscape((Activity) param.thisObject, "onWindowFocus");
                    tick(7, "onWindowFocus asserted " + who(param.thisObject));
                }
            });
            ok++;
        } catch (Throwable t) {
            log("hook8 onWindowFocusChanged 失败: " + t);
        }

        // 装载总量一句话：modules_*.log / logcat 里 grep 这行就知道 8 个 hook 挂了几个。
        log("hooks installed " + ok + "/8 pkg=" + pkg);
    }

    private static void forceLandscape(Activity act, String why) {
        try {
            act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } catch (Throwable t) {
            log("forceLandscape[" + why + "] setOrientation 异常: " + t);
        }
        try {
            act.getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable t) {
            log("forceLandscape[" + why + "] addFlags 异常: " + t);
        }
    }

    /**
     * 注入心跳：向桌面发一条显式广播（无权限要求），让「模块到底被 LSPosed
     * 注入没有」在桌面 RootPanel 诊断行看得见 —— 不再依赖用户抓 logcat。
     * 60s 去重；hook 进程内任何异常一律吞掉，绝不影响宿主。
     */
    private static volatile long lastBeat;
    private static final String DESKTOP_PKG = "com.seagull.carlauncher";

    private static void beat(Activity act) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastBeat < 60_000L) return;
        lastBeat = now;
        try {
            android.content.Intent i = new android.content.Intent(HookDiagnostics.ACTION);
            i.setPackage(DESKTOP_PKG);
            i.putExtra(HookDiagnostics.EXTRA_PKG, PKG_PHONE);
            act.getApplicationContext().sendBroadcast(i);
        } catch (Throwable ignore) {}
    }

    private static boolean isPortrait(int o) {
        return o == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                || o == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                || o == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                || o == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
    }

    private static String who(Object act) {
        try {
            return act.getClass().getName();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void tick(int slot, String msg) {
        int n = ++COUNTERS[slot];
        if (n <= LOG_HEAD || n % LOG_EVERY == 0) {
            log("#" + slot + " " + msg + " (n=" + n + ")");
        }
    }

    private static String prop(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method m = sp.getDeclaredMethod("get", String.class, String.class);
            Object v = m.invoke(null, key, def);
            return v instanceof String ? (String) v : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /** 双写：logcat（SeagullLsp 标签）+ XposedBridge.log（SeagullAmap 前缀）。 */
    private static void log(String msg) {
        Log.i(TAG, PREFIX + ": " + msg);
        XposedBridge.log(PREFIX + ": " + msg);
    }
}
