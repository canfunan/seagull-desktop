package com.example.wangyihook;

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
 * 车联助手 wangyihook.HookEntry 的逐指令一比一还原（批次 S-RESTORE）：与
 * gaodehook 同一套 7 hook 模式，目标包为网易云手机版+车机版两个，TAG=WangyiHook。
 * 反汇编核对：isPortrait 集合同为 1/7/9/12/14，setRequestedOrientation 同带
 * -1 强制分支，PIP 段同样仅记录，末尾同样打 all hooks installed。
 */
public class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "WangyiHook";
    private static final String TARGET_PKG = "com.netease.cloudmusic";
    private static final String TARGET_PKG_IOT = "com.netease.cloudmusic.iot";

    private static boolean isPortrait(int o) {
        return o == 1 || o == 7 || o == 9 || o == 12 || o == 14;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        String pkg = lpp.packageName;
        if (!TARGET_PKG.equals(pkg) && !TARGET_PKG_IOT.equals(pkg)) return;
        XposedBridge.log(TAG + ": hooked " + pkg + " process=" + lpp.processName);
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
