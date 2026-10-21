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
        // v7 启动形态（按优先级）：
        // ① intent URI（模块 toUri 带过来）—— 完整 intent（extras/data 都在），
        //    am start 直接吃标准 URI 格式。内部导航带数据的场景（网易云点歌/开页）
        //    必须走这条，否则 Activity 拿不到数据 → 黑屏/挂载失效。
        // ② 组件 + flags 保真 —— 无 URI 时（老模块/序列化失败）的退化路径：
        //    -f 用原启动 flag（am 默认 NEW_TASK 会把内部导航变成新开任务，
        //    打断返回栈/UI 状态）。
        // flags < 0 = 没带，退回 am 默认行为。
        // v8：root 直启强制补 NEW_TASK —— shell 身份启动的硬性要求，
        // 原 flag 可能没有（网易云 IotRadioPlayerActivity 实测 0x20000 无 NEW_TASK，
        // am start 不带 NEW_TASK 直接拒）。补上不改变语义（root 直启本来就要建任务）。
        int flags = intent.getIntExtra("flags", -1);
        int effFlags = flags < 0 ? -1 : (flags | 0x10000000);
        String f = effFlags >= 0 ? " -f 0x" + Integer.toHexString(effFlags) : "";
        String uri = intent.getStringExtra("uri");
        String out;
        if (uri != null && !uri.isEmpty()) {
            // 安全（复盘 2）：URI 必须携带已校验的 component —— 本接收器 exported，
            // 恶意 app 伪造广播就能借 root 之手启动任意 Activity。module 同帧发
            // component extra + URI（URI 的 component 参数 = 同一组件），两边对不上拒绝。
            if (!uri.contains("component=" + comp)) {
                Log.w(TAG, "URI 与校验组件不符，拒绝: " + uri);
                return;
            }
            // shell 单引号包裹 URI（含 ; # = 空格）；URI 里的单引号先剥掉
            // （toUri 的值不做 URL 编码，字符串 extras 带撇号会破引号 —— 极少数，
            // 丢一个字符比整条命令崩掉好）
            String safe = uri.replace("'", "");
            out = Caps.exec("am start --display " + displayId + f + " '" + safe + "'");
            Log.i(TAG, "root 直启(URI)到画布 d" + displayId + " ok="
                    + (out != null && !out.toLowerCase().contains("exception"))
                    + (out != null ? " | " + out.trim() : ""));
            return;
        }
        out = Caps.exec("am start --display " + displayId + f + " -n " + comp);
        Log.i(TAG, "root 直启(组件)到画布 d" + displayId + ": " + comp + " ok="
                + (out != null && !out.toLowerCase().contains("exception"))
                + (out != null ? " | " + out.trim() : ""));
    }
}
