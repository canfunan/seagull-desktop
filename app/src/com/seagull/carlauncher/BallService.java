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
import android.widget.LinearLayout;
import android.widget.TextView;

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
    private boolean longPressed;    // NB23：长按已触发，松手不再当点击
    private long downAt;
    private final int touchSlop = 8;
    private android.os.Handler ui;  // NB23：长按判定 + 菜单自动关闭
    private Runnable longPressJob, menuTimeout;
    private LinearLayout menuView;  // NB23：长按二级菜单 overlay
    private WindowManager.LayoutParams menuLp;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ui = new android.os.Handler(android.os.Looper.getMainLooper());
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
                        longPressed = false;
                        // NB23：500ms 未动 = 长按 → 弹自愈菜单（拖动会取消）
                        if (longPressJob != null) ui.removeCallbacks(longPressJob);
                        longPressJob = () -> {
                            if (!dragging && !longPressed) {
                                longPressed = true;
                                showMenu();
                            }
                        };
                        ui.postDelayed(longPressJob, 500L);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            dragging = true;
                            ui.removeCallbacks(longPressJob);   // 拖动取消长按
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
                        ui.removeCallbacks(longPressJob);
                        if (dragging) {
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
                        } else if (!longPressed) {
                            // NB20：点一下回海鸥桌面主页（NB23 修正为直启）
                            goHome();
                        } else {
                            longPressed = false;   // 长按已消费，重置
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

    /**
     * NB23 修正：直启海鸥 HomeActivity。之前发 CATEGORY_HOME —— 那回的是
     * **系统默认桌面**（车机原厂桌面），不是海鸥！海鸥 onResume 永远不触发，
     * 画中画自愈链根本没人跑（用户实测「点击回的不是海鸥桌面」）。
     */
    private void goHome() {
        try {
            Intent i = new Intent(this, HomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "直启海鸥桌面失败，回落 CATEGORY_HOME", t);
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /* ---------------- NB23：长按二级菜单（手动自愈画中画） ---------------- */

    /**
     * 长按小圆点弹菜单（贴着球弹出，8 秒无操作自动关）：
     *   ① 自愈所有画中画 —— 按 pickTarget（点选 > 手机版高德 > …）全链自愈
     *   ② 自愈画中画1 —— 已装推荐应用第 1 个
     *   ③ 自愈画中画2 —— 已装推荐应用第 2 个
     * 自愈在后台线程跑（探测/su/等待都要秒级）；屏未建时自动回海鸥桌面
     * 触发重建管线，结果用 Toast 报告。
     */
    private void showMenu() {
        hideMenu();
        menuView = new LinearLayout(this);
        menuView.setOrientation(LinearLayout.VERTICAL);
        menuView.setBackgroundResource(android.R.drawable.dialog_frame);
        menuView.setBackgroundColor(0xF0202022);
        menuView.setPadding(dp(this, 4), dp(this, 6), dp(this, 4), dp(this, 6));

        menuView.addView(menuItem("自愈所有画中画", () -> VirtualPipController.healAll(this)));
        // 动态列已装的推荐应用（最多 2 个）作为 画中画1/2
        int n = 1;
        for (String p : VirtualPipController.RECOMMEND) {
            if (n > 2) break;
            try {
                if (getPackageManager().getLaunchIntentForPackage(p) == null) continue;
            } catch (Throwable t) {
                continue;
            }
            CharSequence name = "?";
            try {
                name = getPackageManager().getApplicationLabel(
                        getPackageManager().getApplicationInfo(p, 0));
            } catch (Throwable ignore) {
            }
            final String pkg = p;
            menuView.addView(menuItem("自愈画中画" + n + "（" + name + "）",
                    () -> VirtualPipController.healTarget(this, pkg)));
            n++;
        }
        if (n == 1) {
            // 一个推荐应用都没装：只留「自愈所有」并提示
            TextView hint = new TextView(this);
            hint.setText("未装高德/网易云，无画中画可自愈");
            hint.setTextColor(0xFF9E9E9E);
            hint.setTextSize(12);
            hint.setPadding(dp(this, 14), dp(this, 6), dp(this, 14), dp(this, 6));
            menuView.addView(hint);
        }

        menuLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        menuLp.gravity = Gravity.TOP | Gravity.START;
        menuLp.x = lp.x + dp(this, 50);   // 贴着球右侧弹出
        menuLp.y = lp.y;
        try {
            wm.addView(menuView, menuLp);
        } catch (Throwable t) {
            Log.w(TAG, "自愈菜单挂起失败: " + t);
            menuView = null;
            return;
        }
        // 8 秒不选自动收起（重复长按由 hideMenu 先清）
        if (menuTimeout != null) ui.removeCallbacks(menuTimeout);
        menuTimeout = this::hideMenu;
        ui.postDelayed(menuTimeout, 8000L);
    }

    private TextView menuItem(String text, java.util.concurrent.Callable<String> action) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(14);
        t.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
        t.setOnClickListener(v -> {
            hideMenu();
            heal(action);
        });
        return t;
    }

    /**
     * 后台线程跑自愈（探测拉活 + su 搬任务是秒级操作），真实结果回主线程
     * Toast；无论成败都回海鸥桌面 —— NB24：画中画视图嵌在 HomeActivity 里，
     * 自愈「成功」时用户在第三方界面根本看不见（体感=失败）；回桌面后：
     * 成功 → 直接看见画中画；屏未建/失败 → onResume 被动自愈链二次兜底。
     */
    private void heal(java.util.concurrent.Callable<String> action) {
        new Thread(() -> {
            String out;
            try { out = action.call(); }
            catch (Throwable t) { out = "自愈异常: " + t; }
            final String r = out;
            ui.postDelayed(() -> {
                try {
                    android.widget.Toast.makeText(BallService.this, r,
                            android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignore) {
                }
                goHome();   // NB24：成功也回 —— 画中画只有回桌面才可见
            }, 400L);
        }, "seagull-ball-heal").start();
    }

    private void hideMenu() {
        if (menuTimeout != null) {
            ui.removeCallbacks(menuTimeout);
            menuTimeout = null;
        }
        if (menuView != null) {
            try {
                wm.removeView(menuView);
            } catch (Throwable ignore) {
            }
            menuView = null;
        }
    }

    @Override
    public void onDestroy() {
        if (ui != null) {
            if (longPressJob != null) ui.removeCallbacks(longPressJob);
            hideMenu();
        }
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
