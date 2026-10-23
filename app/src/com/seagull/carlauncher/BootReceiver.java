package com.seagull.carlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机 / 更新后把桌面拉回前台（车机场景常用）。 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "SeagullBoot";

    @Override public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        Log.i(TAG, "onReceive " + a);
        // 「开机回桌面」开关只管要不要拉回界面：开机与自更新都尊重它
        // （批次 V 判定：旧实现更新路径无视开关硬拉；开机任务又被这开关连带吞掉）
        boolean auto = new LauncherModel(ctx, false).autoHome;
        if (auto) toHome(ctx);
        if ("android.intent.action.MY_PACKAGE_REPLACED".equals(a)) return;
        // 开机任务与悬浮球自启只在真开机走：自更新重放会双触发（sticky 服务系统自己会拉起）
        try {
            TaskEngine.fireBoot(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "开机任务失败", t);
        }
        try {
            LauncherModel m = new LauncherModel(ctx, false);
            if (m.ballEnabled) BallService.setEnabled(ctx, true);
        } catch (Throwable t) {
            Log.w(TAG, "拉起悬浮球失败", t);
        }
        KeepAliveService.ensure(ctx);   // KB18：保活前台服务（常驻通知）
    }

    private void toHome(Context ctx) {
        try {
            Intent i = new Intent(ctx, HomeActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "拉回桌面失败", t);
        }
    }
}
