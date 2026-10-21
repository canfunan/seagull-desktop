package com.seagull.carlauncher;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 桌面内嵌画中画（批次 W3 —— 像素级复刻车联助手 b1/c「PipUI」）。
 *
 * 车联助手的画中画是【自家 Activity 里的一个视图】（PIP1：横屏 60% 宽×全高、
 * 竖屏全屏），不是系统悬浮窗。这带来三个批次 W2 悬浮窗方案永远给不了的性质：
 *   ① 不需要 SYSTEM_ALERT_WINDOW 授权（悬浮窗授权没给 → 窗口 addView 直接抛
 *      异常 → 批次 W2 在用户设备上"一次都没出现过"的根因）；
 *   ② 部署（锚点 startActivity）从前台 Activity 上下文走，无后台 BAL 限制；
 *   ③ 生命周期 = 桌面 Activity：onResume 自愈天然跟着桌面走。
 *
 * 视图分区与交互（照抄车联 PIP1 + 我们的按钮条）：
 *   顶栏（返回/60%/拉伸/状态文字/关）—— 拖这里移动；
 *   SurfaceView 区 —— 触摸手势 input -d 转发进虚拟屏；
 *   右下角手柄 —— 自由拉伸。
 */
public class VdPipView extends LinearLayout {

    private static final int MIN_W = 280;
    private static final int MIN_H = 160;

    private final Activity act;
    private LinearLayout bar;
    private TextView status;
    private SurfaceView sv;
    private LayoutParams vlp;          // 在桌面 root 里的布局参数
    private int vdId = -1;
    private int inputFails;
    private boolean booted;

    public VdPipView(Activity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        setBackgroundColor(Color.BLACK);
        buildBar();
        buildStage();
        // 复刻 b1 PIP1 初始尺寸：横屏 = 60% 宽 × 全高；竖屏 = 全屏
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        vlp = sw >= sh
                ? new LayoutParams((int) (sw * 0.6f), LayoutParams.MATCH_PARENT)
                : new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        vlp.gravity = Gravity.CENTER;
    }

    public LayoutParams pipLayout() { return vlp; }

    /* ---------------- 视图构建 ---------------- */

    private void buildBar() {
        bar = new LinearLayout(getContext());
        bar.setOrientation(HORIZONTAL);
        bar.setBackgroundColor(0xEE1C1C1E);
        status = new TextView(getContext());
        status.setTextColor(0xFF9ADCFF);
        status.setTextSize(10);
        status.setPadding(dp(8), 0, dp(8), 0);
        status.setText("画中画初始化…");
        bar.addView(btn("返回", v -> inject("input -d " + vdId + " keyevent 4")));
        bar.addView(btn("60%", v -> resizeRel(0.6f)));
        bar.addView(btn("拉伸", v -> resizeFull()));
        bar.addView(status, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(btn("关", v -> close()));
        bar.setOnTouchListener(this::dragWindow);
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, dp(30)));
    }

    private void buildStage() {
        FrameLayout stage = new FrameLayout(getContext());
        sv = new SurfaceView(getContext());
        sv.getHolder().setFormat(PixelFormat.OPAQUE);
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                if (booted) {
                    VirtualPipController.surfaceChanged(h.getSurface(),
                            sv.getWidth(), sv.getHeight());
                    return;
                }
                booted = true;
                bootFromSurface(h, sv);
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
                // 复刻车联 b1/b：窗口尺寸变了屏跟着 resize —— 触摸零换算的根基
                VirtualPipController.surfaceChanged(h.getSurface(), w, hh);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                VirtualPipController.surfaceDestroyed();
            }
        });
        sv.setOnTouchListener(this::onStageTouch);
        stage.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        stage.addView(resizeHandle(), new FrameLayout.LayoutParams(dp(26), dp(26),
                Gravity.BOTTOM | Gravity.END));
        addView(stage, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private TextView btn(String text, OnClickListener l) {
        TextView b = new TextView(getContext());
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(11);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(l);
        return b;
    }

    /** 右下角拉伸手柄：按住拖动改窗口大小。 */
    private View resizeHandle() {
        View h = new View(getContext());
        h.setBackgroundColor(0x66FF9500);
        h.setOnTouchListener((v, e) -> {
            float x = e.getRawX();
            float y = e.getRawY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    h.setTag(new float[]{x, y, vlp.width, vlp.height});
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float[] s = (float[]) h.getTag();
                    vlp.width = clamp((int) (s[2] + (x - s[0])), MIN_W, 4096);
                    vlp.height = clamp((int) (s[3] + (y - s[1])), MIN_H, 4096);
                    vlp.gravity = Gravity.CENTER;
                    requestLayout();
                    return true;
                default:
                    return false;
            }
        });
        return h;
    }

    /* ---------------- 部署（与车联 b1/b.surfaceCreated → c1/h 同管线） ---------------- */

    private void bootFromSurface(SurfaceHolder h, SurfaceView v) {
        final Surface sf = h.getSurface();
        new Thread(() -> {
            // 目标应用：推荐应用点选 > 自动探测（手机版高德 > 车机版 > 网易云）
            String pkg = VirtualPipController.pickTarget(getContext());
            if (pkg == null) {
                setStatus("没装推荐应用：先装高德/网易云", true);
                return;
            }
            int dpi = getResources().getDisplayMetrics().densityDpi;
            int w = v.getWidth(), hh = v.getHeight();
            if (w <= 0 || hh <= 0) { w = 1280; hh = 720; }
            int id = VirtualPipController.create(getContext(), pkg, sf, w, hh, dpi);
            if (id < 0) {
                setStatus("虚拟屏创建失败", true);
                return;
            }
            vdId = id;
            setStatus("VD#" + id + " " + pkg + " · 部署中…", false);
            String r = VirtualPipController.deploy(getContext());
            boolean ok = r.equals("锚点+move-task") || r.equals("直启")
                    || r.equals("root am start") || r.equals("daemon直启");
            setStatus("VD#" + id + " · " + r + (ok ? " · 探测中…" : ""), !ok);
            if (ok) {
                android.os.Handler hd = new android.os.Handler(
                        getContext().getMainLooper());
                hd.postDelayed(this::probe, 2500);
                hd.postDelayed(this::probe, 7000);
            }
        }, "seagull-vd-boot").start();
    }

    /** 黑屏二分探针，结论直接进状态行。 */
    private void probe() {
        new Thread(() -> {
            String r = VirtualPipController.probeContent();
            boolean err = r.startsWith("屏黑") || r.startsWith("截屏") || r.equals("屏未建");
            setStatus(r, err);
        }, "seagull-vd-probe").start();
    }

    /* ---------------- 输入转发 ---------------- */

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

    /** 顶栏空白区拖动 = 移动画中画（在桌面 root 里挪 margin）。 */
    private boolean dragWindow(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                status.setTag(new float[]{e.getRawX(), e.getRawY(), vlp.leftMargin, vlp.topMargin});
                return true;
            case MotionEvent.ACTION_MOVE: {
                float[] s = (float[]) status.getTag();
                if (s != null) {
                    vlp.leftMargin = (int) (s[2] + (e.getRawX() - s[0]));
                    vlp.topMargin = (int) (s[3] + (e.getRawY() - s[1]));
                    requestLayout();
                }
                return true;
            }
            default:
                return false;
        }
    }

    private void resizeRel(float ratio) {
        int sw = getResources().getDisplayMetrics().widthPixels;
        vlp.width = (int) (sw * ratio);
        vlp.height = LayoutParams.MATCH_PARENT;
        vlp.gravity = Gravity.CENTER;
        requestLayout();
    }

    private void resizeFull() {
        vlp.width = LayoutParams.MATCH_PARENT;
        vlp.height = LayoutParams.MATCH_PARENT;
        requestLayout();
    }

    /** 关闭：摘视图 + 拆屏 + 摘开关，桌面 onResume 同步干净。 */
    private void close() {
        VirtualPipController.setEnabled(false);
        VirtualPipController.release();
        android.view.ViewGroup p = (android.view.ViewGroup) getParent();
        if (p != null) p.removeView(this);
    }

    private void setStatus(String s, boolean err) {
        if (status == null) return;
        new android.os.Handler(getContext().getMainLooper()).post(() -> {
            status.setText(s);
            status.setTextColor(err ? 0xFFFF453A : 0xFF9ADCFF);
        });
    }

    private void toast(String s) {
        Toast.makeText(getContext(), s, Toast.LENGTH_LONG).show();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
