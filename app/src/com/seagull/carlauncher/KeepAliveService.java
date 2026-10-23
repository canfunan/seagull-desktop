package com.seagull.carlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * 保活前台服务（批次 KB18）：常驻通知栏一条「海鸥桌面运行中」，
 * 把进程优先级提到 foreground —— 车机后台清理/省电策略不再轻易杀掉桌面，
 * 画中画开关、悬浮球、导航卡数据链随之稳定。
 *
 * - START_STICKY：即使被杀，系统会重建服务（通知重新挂上）；
 * - 通知 MIN 级：不响铃不横幅，折叠在通知栏底部不碍眼；
 * - 点击通知回桌面（车机上顺手）；
 * - targetSdk 33 不强制 foregroundServiceType，直接 startForeground。
 */
public class KeepAliveService extends Service {

    private static final String TAG = "SeagullKeepAlive";
    private static final String CH_ID = "seagull_keepalive";
    private static final int NOTI_ID = 0x5EA6;   // 海鸥

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTI_ID, buildNoti(this));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private static Notification buildNoti(Context ctx) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "海鸥桌面运行中",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("保持海鸥桌面在后台存活");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(ctx, CH_ID);
        } else {
            b = new Notification.Builder(ctx);
        }
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, HomeActivity.class)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | (android.os.Build.VERSION.SDK_INT >= 23
                        ? android.app.PendingIntent.FLAG_IMMUTABLE : 0));
        return b.setSmallIcon(android.R.drawable.ic_menu_myplaces)
                .setContentTitle("海鸥桌面")
                .setContentText("桌面运行中 · 画中画/悬浮球保活")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    /** 拉起保活（HomeActivity / BootReceiver 调）：幂等。 */
    public static void ensure(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, KeepAliveService.class));
        } catch (Throwable t) {
            android.util.Log.w(TAG, "保活服务拉起失败", t);
        }
    }
}
