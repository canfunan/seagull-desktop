package com.seagull.carlauncher;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * 小白点（悬浮球，批次 KB，复刻参考桌面普通版 BallService；NB20 按用户需求简化）：
 * 桌面上常驻的可拖拽圆球 —— **只有一个手势：点一下，无论在什么界面都回桌面主页**。
 * 拖动仅用于挪位置（松手自动贴边记忆）。原左滑返回/上滑最近任务已按需求移除。
 * WindowManager TYPE_APPLICATION_OVERLAY 悬浮窗 + 手势分发。
 *
 * 授权（root 级）：overlay 需要 SYSTEM_ALERT_WINDOW（appops），
 * ensureAccess() 用 su appops set 直接放行 —— 不弹设置 UI 不重启。
 * 开关：设置页「显示小白点」（model.ballEnabled，默认开）。
 */
public class BallService extends Service {

    private static final String TAG = "SeagullBall";
    public static final String PROP_BALL = "persist.seagull.ball";
    private static final int BALL_DP = 46;

    private WindowManager wm;
    private FrameLayout ballView;
    private ImageView ballImg;
    private WindowManager.LayoutParams lp;

    private float downX, downY;
    private int startX, startY;
    private boolean dragging;
    private long downAt;
    private final int touchSlop = 8;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureAccess(this);
        if (!isOn(this)) {
            stopSelf();
            return;
        }
        try {
            buildBall();
            Log.i(TAG, "悬浮球已挂起");
        } catch (Throwable t) {
            Log.w(TAG, "悬浮球挂起失败（overlay 权限？）: " + t);
            stopSelf();
        }
    }

    private void buildBall() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        LauncherModel m = cfg(this);
        int size = dp(this, m != null && m.ballSize >= 32 && m.ballSize <= 96
                ? m.ballSize : BALL_DP);
        int alpha = m != null && m.ballAlpha >= 20 && m.ballAlpha <= 100
                ? m.ballAlpha : 90;
        int alpha255 = (int) (255 * alpha / 100f);
        ballView = new FrameLayout(this);
        ballImg = new ImageView(this);
        ballImg.setImageDrawable(Skin.pill((alpha255 << 24) | 0x1B5E20));
        ballImg.setPadding(dp(this, 13), dp(this, 13), dp(this, 13), dp(this, 13));
        ballView.addView(ballImg, new FrameLayout.LayoutParams(size, size));
        ballView.setContentDescription("小白点：点一下回桌面，拖动挪位置");

        lp = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // 位置：model.ballX/Y（-1 = 默认左上角偏下；设置页「回到默认位置」重置）
        if (m != null && m.ballX >= 0 && m.ballY >= 0) {
            lp.x = startX = m.ballX;
            lp.y = startY = m.ballY;
        } else {
            lp.x = startX = dp(this, 12);
            lp.y = startY = dp(this, 240);
        }

        ballView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        downAt = System.currentTimeMillis();
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            dragging = true;
                        }
                        if (dragging) {
                            lp.x = startX + (int) dx;
                            lp.y = startY + (int) dy;
                            try {
                                wm.updateViewLayout(ballView, lp);
                            } catch (Throwable ignore) {
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        if (!dragging) {
                            // NB20：唯一手势 —— 点一下回桌面主页（任何界面都生效）
                            goHome();
                        } else {
                            // 拖动只挪位置：写回 model（设置页「回到默认位置」可重置）
                            startX = lp.x;
                            startY = lp.y;
                            try {
                                LauncherModel mm = cfg(BallService.this);
                                if (mm != null) {
                                    mm.ballX = startX;
                                    mm.ballY = startY;
                                    mm.save();
                                }
                            } catch (Throwable ignore) {
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });
        wm.addView(ballView, lp);
    }

    private void goHome() {
        startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    public void onDestroy() {
        try {
            if (ballView != null && wm != null) wm.removeView(ballView);
        } catch (Throwable ignore) {
        }
        super.onDestroy();
    }

    /* ---------------- model 缓存（Lyrics 同款代际号模式） ---------------- */

    private static LauncherModel cfgM;
    private static int cfgVer = -1;

    private static LauncherModel cfg(Context ctx) {
        int v = LauncherModel.cfgVersion();
        LauncherModel m = cfgM;
        if (m == null || v != cfgVer) {
            m = new LauncherModel(ctx, false);
            cfgM = m;
            cfgVer = v;
        }
        return m;
    }

    /* ---------------- root 级自授权 + 开关 ---------------- */

    /** overlay 是否已放行（SYSTEM_ALERT_WINDOW appops）。 */
    public static boolean hasOverlay(Context ctx) {
        try {
            String out = Caps.exec("appops get " + ctx.getPackageName()
                    + " SYSTEM_ALERT_WINDOW");
            return out != null && out.toLowerCase().contains("allow");
        } catch (Throwable t) {
            return false;
        }
    }

    /** root 直放 overlay：su appops set —— 不弹设置 UI 不重启。 */
    public static void ensureAccess(Context ctx) {
        if (ctx == null || hasOverlay(ctx)) return;
        try {
            Caps.exec("appops set " + ctx.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
            Log.i(TAG, "悬浮球 overlay root 授权完成");
        } catch (Throwable t) {
            Log.w(TAG, "overlay root 授权失败: " + t);
        }
    }

    /** 开关读 model.ballEnabled（设置页「显示小白点」同源）。 */
    public static boolean isOn(Context ctx) {
        try {
            return cfg(ctx).ballEnabled;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 开关切换（设置页 SettingsSectionActivity.ballBody 调用）：写 model + 起停服务。 */
    public static void setEnabled(Context ctx, boolean on) {
        try {
            LauncherModel m = cfg(ctx);
            m.ballEnabled = on;
            m.save();
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent(ctx, BallService.class);
            if (on) ctx.startService(i);
            else ctx.stopService(i);
        } catch (Throwable ignored) {
        }
    }

    /** prop 兼容入口（RootPanel 旧调用名）：等同 setEnabled。 */
    public static void setOn(Context ctx, boolean on) {
        setEnabled(ctx, on);
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
