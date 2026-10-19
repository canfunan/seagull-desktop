package com.seagull.carlauncher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * L1 悬浮镜像卡片 —— 不需要 root。
 *
 * 实现：MediaProjection 授权 → createVirtualDisplay(..., surface=卡片里的 SurfaceView)
 *      → 用 TYPE_APPLICATION_OVERLAY 悬浮窗承载，可拖动 / 缩放 / 关闭。
 *
 * 局限（如实说明）：这是「镜像」不是「搬窗口」——原应用仍在主屏运行，
 * 卡片里是它的实时画面；MediaProjection 的输入通道不向应用开放，
 * 因此卡片内不能像真窗口那样点击操作。要做到「应用真搬进卡片且可交互」，
 * 需要 uid=1000 的 L3 档（见 WindowTestActivity 的说明）。
 */
public final class WindowCard {

    private static final String TAG = "SeagullCard";

    private final Context ctx;
    private final MediaProjection projection;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WindowManager wm;

    private FrameLayout root;
    private SurfaceView surfaceView;
    private VirtualDisplay vd;
    private WindowManager.LayoutParams lp;

    private int widthPx, heightPx;
    private float downX, downY;
    private int startX, startY;
    private boolean resizing;
    private int startW, startH;

    public WindowCard(Context ctx, MediaProjection projection, int w, int h) {
        this.ctx = ctx;
        this.projection = projection;
        this.widthPx = w;
        this.heightPx = h;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    public void show() {
        root = new FrameLayout(ctx);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(ctx);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));

        // 顶部小标题条（可拖动）
        LinearLayout bar = new LinearLayout(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Skin.bar(Skin.c(R.color.card)));
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        TextView title = new TextView(ctx);
        title.setText("镜像卡片 · 拖动可移动 · 长按缩放 · 双击关闭");
        title.setTextColor(Skin.c(R.color.text_dim));
        title.setTextSize(11);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(-1, dp(22));
        barLp.gravity = Gravity.TOP;
        root.addView(bar, barLp);

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        lp = new WindowManager.LayoutParams(
                dp(320), dp(190),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(40);
        lp.y = dp(160);

        bar.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = lp.x; startY = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = startX + (int) (e.getRawX() - downX);
                        lp.y = startY + (int) (e.getRawY() - downY);
                        safeUpdate();
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (Math.abs(e.getRawX() - downX) < dp(6)
                                && Math.abs(e.getRawY() - downY) < dp(6)) {
                            // 单击标题条 = 置顶/无操作，双击 = 关闭
                            long now = System.currentTimeMillis();
                            if (now - lastTap < 400) { close(); return true; }
                            lastTap = now;
                        }
                        return true;
                }
                return false;
            }
        });

        bar.setOnLongClickListener(v -> {
            resizing = !resizing;
            return true;
        });

        // 缩放手柄（右下角）
        TextView handle = new TextView(ctx);
        handle.setText("◢");
        handle.setTextColor(0xCC8CC26A);
        handle.setTextSize(14);
        handle.setGravity(Gravity.CENTER);
        handle.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams hLp = new FrameLayout.LayoutParams(dp(30), dp(30));
        hLp.gravity = Gravity.BOTTOM | Gravity.END;
        root.addView(handle, hLp);

        handle.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startW = lp.width; startH = lp.height;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.width = Math.max(dp(160), startW + (int) (e.getRawX() - downX));
                        lp.height = Math.max(dp(96), startH + (int) (e.getRawY() - downY));
                        safeUpdate();
                        return true;
                }
                return false;
            }
        });

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                attach(h.getSurface(), lp.width, lp.height);
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
                attach(h.getSurface(), w, hh);
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                detach();
            }
        });

        try {
            wm.addView(root, lp);
            Log.i(TAG, "卡片已挂载");
        } catch (Throwable t) {
            Log.e(TAG, "addView 失败", t);
        }
    }

    private long lastTap;

    private void safeUpdate() {
        try { wm.updateViewLayout(root, lp); } catch (Throwable ignore) {}
    }

    /** 把虚拟屏的输出挂到卡片的 Surface 上（尺寸跟随卡片，等比铺满）。 */
    private void attach(Surface surface, int w, int h) {
        try {
            if (surface == null || !surface.isValid()) return;
            int nw = Math.max(160, w), nh = Math.max(96, h);
            // 审计 M10：拖拽改尺寸每帧触发 surfaceChanged —— 旧实现每次都
            // release + createVirtualDisplay（系统侧建屏开销大、画面闪烁）。
            // 先走 vd.resize 原地改尺寸，失败再整建兜底。
            if (vd != null) {
                try {
                    vd.resize(nw, nh, ctx.getResources().getDisplayMetrics().densityDpi);
                    return;
                } catch (Throwable t) {
                    Log.w(TAG, "vd.resize 失败，重建虚拟屏: " + t);
                    detach();
                }
            }
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            vd = dm.createVirtualDisplay("seagull-card", nw, nh,
                    ctx.getResources().getDisplayMetrics().densityDpi,
                    surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    null, null);
            Log.i(TAG, "镜像虚拟屏 id=" + (vd != null ? vd.getDisplay().getDisplayId() : -1)
                    + " " + nw + "x" + nh);
        } catch (Throwable t) {
            Log.e(TAG, "attach 失败", t);
        }
    }

    private void detach() {
        if (vd != null) {
            try { vd.release(); } catch (Throwable ignore) {}
            vd = null;
        }
    }

    public void close() {
        // 由 WindowService.onDestroy / 重复建卡时调：卡片一关，前台服务也没有
        // 画面可守，顺手停掉自己（旧实现从不 stopService，通知+服务常驻到进程死）
        ui.post(() -> {
            detach();
            try { wm.removeView(root); } catch (Throwable ignore) {}
            try { projection.stop(); } catch (Throwable ignore) {}
            try {
                if (root != null && root.getContext() != null) {
                    root.getContext().stopService(
                            new Intent(root.getContext(), WindowService.class));
                }
            } catch (Throwable ignore) {}
        });
    }
}
