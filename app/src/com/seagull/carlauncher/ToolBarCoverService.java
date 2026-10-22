package com.seagull.carlauncher;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 底栏遮盖层（vivo互联投屏·收尾）：vivo.car.networking 往车机屏加的
 * VivoCarToolBar（TYPE_NAVIGATION_BAR 2019，gr=BOTTOM 1280x104）盖在
 * 海鸥桌面上碍事。那货是 sharedUserId=android.uid.system 的系统级应用，
 * Zygisk 注不进（hook 路线死），service call 摘窗事务码太脆。
 *
 * 解法：本服务往车机屏加一个 TYPE_APPLICATION_OVERLAY(2038) 的全宽 104dp
 * 不透明 overlay —— overlay 层级高于 NAVIGATION_BAR(2019)，直接把底栏
 * 盖死。海鸥有 SYSTEM_ALERT_WINDOW + appops 已授权（BallService 同款），
 * 零额外权限。
 *
 * 开关：prop persist.seagull.toolbar_cover（1=开 0=关，默认开）。
 * 用法：海鸥桌面上车机屏后 root 拉起本服务（带 displayId extra）。
 */
public class ToolBarCoverService extends Service {

    private static final String TAG = "SeagullTbCover";
    public static final String PROP_COVER = "persist.seagull.toolbar_cover";

    private WindowManager wm;
    private View cover;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!isOn(this)) {
            stopSelf();
            return;
        }
        try {
            int displayId = 90003;   // 车机屏（vivo_car_screen 实测 ID）
            wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            cover = new View(this);
            cover.setBackgroundColor(0xFF101418);   // 与桌面底色一致的纯色
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    dp(this, 104),   // 与 VivoCarToolBar 等高：1280x104
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            lp.displayId = displayId;
            wm.addView(cover, lp);
            Log.i(TAG, "底栏遮盖层已挂起 d" + displayId + " 104dp");
        } catch (Throwable t) {
            Log.w(TAG, "遮盖层挂起失败: " + t);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        try {
            if (cover != null && wm != null) wm.removeView(cover);
        } catch (Throwable ignore) {
        }
        super.onDestroy();
    }

    public static boolean isOn(Context ctx) {
        try {
            String v = SysOps.getProp(PROP_COVER);
            if (v == null || v.trim().isEmpty()) return true;
            return "1".equals(v.trim());
        } catch (Throwable t) {
            return true;
        }
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
