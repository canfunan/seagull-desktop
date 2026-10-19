package com.seagull.carlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 投屏前台服务（mediaProjection 型）。
 *
 * Android 14 硬规定（参考实现踩实的坑）：MediaProjection.createVirtualDisplay 之前，
 * 应用必须已经有一个 foregroundServiceType=mediaProjection 的前台服务在跑，
 * 且该服务只能在用户授权【之后】启动。所以它和常驻服务分开：
 * Activity 拿到录屏 token 后 start 这里，镜像槽全部撤销后 stop。
 *
 * 【注意】一个 Service 不能同时声明 specialUse 与 mediaProjection，必须分开。
 */
public class PipProjectionService extends Service {

    private static final String TAG = "SeagullProj";
    private static final String CH = "seagull_proj";
    private static final int NOTI_ID = 0x5EA3;

    private static volatile boolean startedHere = false;
    /** startForeground(mediaProjection) 已完成的标志（取 token 的前置条件）。 */
    public static volatile boolean running = false;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, PipProjectionService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, PipProjectionService.class));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (startedHere) return START_NOT_STICKY;
        startedHere = true;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(new NotificationChannel(CH, "镜像投屏",
                        NotificationManager.IMPORTANCE_MIN));
            }
        }
        Notification n = null;
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(this, CH)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("镜像小窗运行中")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setOngoing(true)
                    .build();
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTI_ID, n);
            }
            running = true;   // Activity 轮询此标志，true 后才允许 createVirtualDisplay
            Log.i(TAG, "投影前台服务已启动");
        } catch (Throwable t) {
            // 审计 L13：前台通知起不来（通知权限被关/ROM 限制）= 5 秒后系统
            // 会 ANR-crash 这个服务 —— 与其等系统杀，不如自己体面退场
            Log.e(TAG, "startForeground 失败，服务自停", t);
            running = false;
            try { stopSelf(); } catch (Throwable ignore) {}
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        startedHere = false;
        running = false;
        Log.i(TAG, "投影前台服务已停止");
        super.onDestroy();
    }
}
