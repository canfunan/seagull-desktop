package com.seagull.carlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.VirtualDisplay;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 虚拟屏画中画悬浮窗（批次 U）。
 *
 * 交互分区：
 *   顶栏（返回/60%/拉伸/关闭 + 状态文字）——拖这里移动窗口；
 *   SurfaceView 区——触摸手势原样转发进虚拟屏（input -d tap/swipe）；
 *   右下角手柄——拖这里自由拉伸窗口（"随意拉伸"规格）。
 *
 * VD 分辨率恒定，窗口尺寸随意变，拉伸由 SurfaceView 缩放完成，
 * 坐标映射 x' = x * vdW / viewW 保证点击命中率。
 */
public class PipOverlayService extends Service {

    private static final String CHANNEL = "seagull_pip";
    private static final int MIN_W = 280;
    private static final int MIN_H = 160;

    private WindowManager wm;
    private LinearLayout root;
    private TextView status;
    private WindowManager.LayoutParams lp;
    private VirtualDisplay vd;
    private int vdId = -1;
    private int inputFails;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(0x5ea9, buildNotification());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    /* ---------------- overlay 构建 ---------------- */

    private void buildOverlay() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int initW = Math.max(MIN_W, (int) (Math.min(screenW, screenH) * 0.6f));
        int initH = (int) (initW * 9f / 16f);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xEE1C1C1E);
        status = new TextView(this);
        status.setTextColor(0xFF9ADCFF);
        status.setTextSize(10);
        status.setPadding(dp(8), 0, dp(8), 0);
        status.setText("画中画初始化…");
        bar.addView(btn("返回", v -> inject("input -d " + vdId + " keyevent 4")));
        bar.addView(btn("60%", v -> resizeRel(0.6f)));
        bar.addView(btn("拉伸", v -> resizeFull()));
        bar.addView(status, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(btn("关", v -> close()));
        bar.setOnTouchListener(this::dragWindow);

        FrameLayout stage = new FrameLayout(this);
        SurfaceView sv = new SurfaceView(this);
        sv.getHolder().setFormat(PixelFormat.OPAQUE);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                if (vd != null) vd.setSurface(h.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
                if (vd != null) vd.setSurface(h.getSurface());
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
            }
        });
        sv.setOnTouchListener(this::onStageTouch);
        stage.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addView(resizeHandle(), new FrameLayout.LayoutParams(dp(26), dp(26),
                Gravity.BOTTOM | Gravity.END));

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        lp = new WindowManager.LayoutParams(initW, initH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        try {
            wm.addView(root, lp);
        } catch (Throwable t) {
            toast("悬浮窗权限被拒：" + t);
            stopSelf();
            return;
        }
        bootVirtualDisplay();
    }

    private TextView btn(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(11);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(l);
        return b;
    }

    /** 右下角拉伸手柄：按住拖动改窗口大小（随意拉伸规格）。 */
    private View resizeHandle() {
        View h = new View(this);
        h.setBackgroundColor(0x66FF9500);
        h.setOnTouchListener((v, e) -> {
            float x = e.getRawX();
            float y = e.getRawY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    h.setTag(new float[]{x, y, lp.width, lp.height});
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float[] s = (float[]) h.getTag();
                    lp.width = clamp((int) (s[2] + (x - s[0])), MIN_W, 4096);
                    lp.height = clamp((int) (s[3] + (y - s[1])), MIN_H, 4096);
                    try {
                        wm.updateViewLayout(root, lp);
                    } catch (Throwable ignored) {
                    }
                    return true;
                default:
                    return false;
            }
        });
        return h;
    }

    /* ---------------- 虚拟屏 + 高德 ---------------- */

    private void bootVirtualDisplay() {
        new Thread(() -> {
            vd = VirtualPipController.create(this, VirtualPipController.DEF_PKG);
            if (vd == null) {
                setStatus("虚拟屏创建失败", true);
                return;
            }
            vdId = vd.getDisplay().getDisplayId();
            if (sv() != null) vd.setSurface(sv().getHolder().getSurface());
            String err = VirtualPipController.launchAmap(this);
            setStatus(err != null ? err
                    : "VD#" + vdId + " " + VirtualPipController.width() + "x"
                    + VirtualPipController.height() + " 已拉起 " + VirtualPipController.pkg(),
                    err != null);
        }, "seagull-vd-boot").start();
    }

    private SurfaceView sv() {
        FrameLayout stage = (FrameLayout) root.getChildAt(1);
        return (SurfaceView) stage.getChildAt(0);
    }

    /* ---------------- 输入转发 ---------------- */

    /** SurfaceView 区手势：点击转 tap，滑动转 swipe，坐标映射进 VD。 */
    private boolean onStageTouch(View v, MotionEvent e) {
        if (vdId < 0) return true;
        float sx = VirtualPipController.width() / (float) v.getWidth();
        float sy = VirtualPipController.height() / (float) v.getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                v.setTag(new float[]{e.getX(), e.getY(), e.getX(), e.getY(),
                        System.currentTimeMillis(), 0});
                return true;
            case MotionEvent.ACTION_MOVE: {
                float[] s = (float[]) v.getTag();
                if (s == null) return true;
                s[2] = e.getX();
                s[3] = e.getY();
                if (Math.abs(s[2] - s[0]) + Math.abs(s[3] - s[1]) > 24) s[5] = 1;
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float[] s = (float[]) v.getTag();
                v.setTag(null);
                if (s == null) return true;
                int x1 = Math.round(s[0] * sx);
                int y1 = Math.round(s[1] * sy);
                long dt = System.currentTimeMillis() - (long) s[4];
                if (s[5] == 0 && dt < 400) {
                    inject("input -d " + vdId + " tap " + x1 + " " + y1);
                } else {
                    int x2 = Math.round(s[2] * sx);
                    int y2 = Math.round(s[3] * sy);
                    inject("input -d " + vdId + " swipe " + x1 + " " + y1 + " "
                            + x2 + " " + y2 + " " + Math.max(120, Math.min(dt, 1500)));
                }
                return true;
            }
            default:
                return true;
        }
    }

    private void inject(String cmd) {
        new Thread(() -> {
            String out = Caps.exec(cmd);
            if (out != null) {
                inputFails = 0;
            } else if (++inputFails >= 3) {
                setStatus("root 输入通道连续失败，转发已停", true);
            }
        }, "seagull-vd-input").start();
    }

    /* ---------------- 窗口管理 ---------------- */

    /** 顶栏空白区拖动 = 移动窗口。 */
    private boolean dragWindow(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                status.setTag(new float[]{e.getRawX(), e.getRawY(), lp.x, lp.y});
                return true;
            case MotionEvent.ACTION_MOVE: {
                float[] s = (float[]) status.getTag();
                if (s != null) {
                    lp.x = (int) (s[2] + (e.getRawX() - s[0]));
                    lp.y = (int) (s[3] + (e.getRawY() - s[1]));
                    try {
                        wm.updateViewLayout(root, lp);
                    } catch (Throwable ignored) {
                    }
                }
                return true;
            }
            default:
                return false;
        }
    }

    private void resizeRel(float ratio) {
        int shortEdge = Math.min(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        lp.width = (int) (shortEdge * ratio);
        lp.height = (int) (lp.width * 9f / 16f);
        relayout();
    }

    private void resizeFull() {
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        relayout();
    }

    private void relayout() {
        try {
            wm.updateViewLayout(root, lp);
        } catch (Throwable ignored) {
        }
    }

    private void close() {
        stopSelf();
    }

    private void setStatus(String s, boolean err) {
        if (status == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            status.setText(s);
            status.setTextColor(err ? 0xFFFF453A : 0xFF9ADCFF);
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /* ---------------- 生命周期 ---------------- */

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "高德画中画",
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("海鸥画中画运行中")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
    }

    @Override
    public void onDestroy() {
        VirtualPipController.release();
        if (root != null && wm != null) {
            try {
                wm.removeView(root);
            } catch (Throwable ignored) {
            }
            root = null;
        }
        super.onDestroy();
    }
}
