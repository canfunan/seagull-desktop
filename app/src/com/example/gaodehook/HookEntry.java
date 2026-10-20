package com.example.gaodehook;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 车联助手（com.leting 3.0.0，备份仓 canfunan/leting-carlink）gaodehook.HookEntry
 * 的逐指令一比一还原：类名、TAG、目标包、7 个 hook 点、日志字符串、try/catch
 * 分层全部对齐原实现（批次 S-RESTORE）。原 apk 源码已丢失，本文件由 dexdump
 * 反汇编逐条译回 Java，行为与原版严格等价：
 *   1) Activity.isInMultiWindowMode → 恒 false
 *   2) Activity.onMultiWindowModeChanged(boolean, Configuration) → 首参改 false
 *   3) Activity.setRequestedOrientation(int) → 竖屏系(1/7/9/12/14)或 -1 强改 0
 *   4) Activity.getRequestedOrientation → 恒 0
 *   5) Activity.onCreate(Bundle) after → setRequestedOrientation(0)
 *   6) Activity.onResume() after → setRequestedOrientation(0)
 *   7) Activity.setPictureInPictureParams(PictureInPictureParams) → 仅记录不改参
 * 日志 TAG=GaodeHook，与原版一致，LSPosed 日志里可直接对照历史。
 */
public class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "GaodeHook";
    private static final String TARGET_PKG = "com.autonavi.minimap";

    private static boolean isPortrait(int o) {
        return o == 1 || o == 7 || o == 9 || o == 12 || o == 14;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!TARGET_PKG.equals(lpp.packageName)) return;
        XposedBridge.log(TAG + ": hooked " + lpp.packageName + " process=" + lpp.processName);
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", lpp.classLoader,
                    "isInMultiWindowMode", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(false);
                }
            });
            XposedHelpers.findAndHookMethod("android.app.Activity", lpp.classLoader, "onMultiWindowModeChanged",
                    boolean.class, Configuration.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.args[0] = false;
                }
            });
            XposedHelpers.findAndHookMethod("android.app.Activity", lpp.classLoader, "setRequestedOrientation", int.class,
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int o = (Integer) param.args[0];
                    if (!isPortrait(o) && o != -1) return;
                    XposedBridge.log(TAG + ": force landscape from " + o + " for "
                            + param.thisObject.getClass().getName());
                    param.args[0] = 0;
                }
            });
            XposedHelpers.findAndHookMethod("android.app.Activity", lpp.classLoader, "getRequestedOrientation", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(0);
                }
            });
            XposedHelpers.findAndHookMethod("android.app.Activity", lpp.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity a = (Activity) param.thisObject;
                    XposedBridge.log(TAG + ": onCreate force landscape for "
                            + a.getClass().getName());
                    a.setRequestedOrientation(0);
                }
            });
            XposedHelpers.findAndHookMethod("android.app.Activity", lpp.classLoader, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ((Activity) param.thisObject).setRequestedOrientation(0);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hooks failed: " + Log.getStackTraceString(t));
            return;
        }
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", lpp.classLoader,
                    "setPictureInPictureParams",
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    XposedBridge.log(TAG + ": intercept setPictureInPictureParams for "
                            + param.thisObject.getClass().getName());
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": PIP hook skipped: " + t.getMessage());
        }
        XposedBridge.log(TAG + ": all hooks installed");
    }
}
