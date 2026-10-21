package com.seagull.carlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 画布跨包启动改道接收器（批次 PIP）。
 *
 * 背景（反编译 + 实测坐实）：百度汽车版 splash（com.baidu.naviauto）启动地图
 * （com.baidu.baidumaps.MapsActivity，launchMode=singleTask，affinity 按包名
 * = com.baidu.baidumaps）—— 跨包 + singleTask 让「进调用方任务」的改写必然失败
 * （系统按 affinity 找不到宿主任务，静默丢弃，splash 卡死），而带 displayId 的
 * 启动又撞 vivo 的归属检查（SafeActivityOptions.isCallerAllowedToLaunchOnDisplay：
 * 显式 displayId 指向调用方无归属的屏即拒；私有画布 FLAG_PRIVATE 非零直接拒）。
 *
 * 破局（实测）：模块（跑在百度进程内）发现跨包启动时**取消原启动**并把组件
 * 广播过来；本接收器以 **root 身份** `am start --display <画布> -n <组件>` ——
 * root(uid 0) 带 INTERNAL_SYSTEM_WINDOW 过归属检查，实测百度 MapsActivity
 * 直接落进画布上 splash 的任务（t2344 d35），0 拒绝、地图正常渲染。
 *
 * 同包启动（高德车机版等）不走这条路——C0 剥 flag 后进现有任务即可。
 */
public class CanvasLaunchReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.seagull.carlauncher.CANVAS_LAUNCH";
    private static final String TAG = "SeagullCanvasRx";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        String comp = intent.getStringExtra("component");
        int displayId = intent.getIntExtra("displayId", -1);
        if (comp == null || displayId <= 0) return;
        // 组件名合法性：只放行 包名/类名 形式，挡广播注入
        if (!comp.matches("[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+")) {
            Log.w(TAG, "组件名非法，拒绝: " + comp);
            return;
        }
        // Caps.exec 走 su -c（root 身份）：root(uid 0) 带 INTERNAL_SYSTEM_WINDOW
        // 过 vivo 归属检查，组件直接落进画布屏。
        String out = Caps.exec("am start --display " + displayId + " -n " + comp);
        boolean ok = out != null && !out.toLowerCase().contains("exception");
        Log.i(TAG, "root 直启到画布 d" + displayId + ": " + comp + " ok=" + ok
                + (out != null ? " | " + out.trim() : ""));
    }
}
