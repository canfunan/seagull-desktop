package com.seagull.carlauncher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 极简屏（TODO P1-1）：整屏只留大时钟 + 歌词 + 一片叶子。
 *
 * 长按叶子进，再长按一次出（4.1 / 4.2）；点叶子出全部应用（4.9）。
 * 三种摆法（4.13）在 gardenStyle 里；压暗（4.5）与遮罩颜色（4.6）都走设置。
 * 应用继续在后台跑，不做任何保活动作 —— 极简屏只是个界面。
 */
public class GardenActivity extends BaseActivity {

    private LinearLayout col;
    private TextView leaf;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        applyWall(root);

        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        switch (model.gardenStyle) {
            case CENTER_CLOCK:
                col.setGravity(Gravity.CENTER);
                col.addView(clock(46));
                col.addView(lyricLine(16));
                break;
            case LEFT_CLOCK_BOTTOM_LYRIC:
                col.setGravity(Gravity.TOP | Gravity.START);
                col.addView(spacer(dp(40)));
                col.addView(clock(30));
                col.addView(lyricLine(14));
                break;
            case BIG_CLOCK_DATE:
            default:
                col.setGravity(Gravity.CENTER);
                col.addView(clock(56));
                dateView = sub(SysOps.dateCn(), 16);
                col.addView(dateView);
                col.addView(lyricLine(15));
                break;
        }

        leaf = leafView();
        int size = dp(model.ballSize);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        if (model.ballX < 0) {
            lp.gravity = Gravity.BOTTOM | Gravity.START;
            lp.leftMargin = dp(24);
            lp.bottomMargin = dp(32);
        } else {
            // ballX/ballY 由 BallService.snap 存的是像素（lp.x/lp.y），这里直接用；
            // 旧实现再过一遍 dp()，440dpi 机器上叶子会飞到 2.75 倍远处。
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = Math.max(0, Math.min(model.ballX, Math.max(0, w - size)));
            lp.topMargin = Math.max(0, Math.min(model.ballY, Math.max(0, h - size)));
        }
        root.addView(leaf, lp);
        return root;
    }

    /** 极简屏遮罩：壁纸在底下先铺一层，再按 gardenDim 压暗；浅色主题压黑。 */
    private void applyWall(FrameLayout root) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        String path = Skin.isLight(model) ? model.wallDay
                : (model.wallNight.isEmpty() ? model.wallDay : model.wallNight);
        android.graphics.Bitmap bmp = Wallpaper.loadForScreen(this, path, w, h);
        if (bmp == null) {
            root.setBackgroundColor(Skin.c(R.color.ground));
        } else {
            android.graphics.drawable.BitmapDrawable bd =
                    new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
            bd.setGravity(Gravity.FILL);
            root.setBackground(bd);
        }
        // 极简屏遮罩与外观遮罩分开：这里只按 gardenDim 压，不动 model.wallDim
        int a = model.gardenDim * 255 / 100;
        View dim = new View(this);
        dim.setBackgroundColor(Color.argb(a, 0, 0, 0));
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
    }

    private TextView clock(int sp) {
        TextView t = new TextView(this);
        t.setText(SysOps.clockHHmm());
        t.setTextSize(sp);
        t.setTextColor(Skin.c(R.color.text));
        t.setShadowLayer(dp(6), 0, 0, 0xAA000000);   // 4.11 字底：看不清时压一层影
        clockViews.add(t);
        return t;
    }

    private TextView sub(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Skin.c(R.color.text_dim));
        return t;
    }

    /** 歌词行：接 Lyrics 的一秒一跳，行数与字号都跟设置走（10.5 / 10.6 / 10.15）。 */
    private TextView lyricLine(int sp) {
        TextView t = new TextView(this);
        t.setText("（没有在放歌）");
        t.setTextSize(sp * model.lyricSize / 100f);
        t.setTextColor(Skin.c(R.color.text_dim));
        t.setShadowLayer(dp(4), 0, 0, 0xAA000000);
        t.setGravity(Gravity.CENTER);
        lyricView = t;
        return t;
    }

    private TextView lyricView;

    /* 时钟走字：build 时只画一帧，之后每秒对齐刷新（旧实现时钟不走字）。 */
    private final java.util.List<TextView> clockViews = new java.util.ArrayList<>();
    private TextView dateView;
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tickRun = new Runnable() {
        @Override public void run() {
            for (TextView t : clockViews) t.setText(SysOps.clockHHmm());
            if (dateView != null) dateView.setText(SysOps.dateCn());
            ticker.postDelayed(this, 1000);
        }
    };

    private TextView leafView() {
        TextView t = new TextView(this);
        t.setText("●");   // NB20：圆点（原「叶」字是参考实现语境，海鸥叫小白点）
        t.setTextSize(model.ballSize / 3f);
        t.setTextColor(Skin.c(R.color.leaf));
        t.setGravity(Gravity.CENTER);
        t.setBackground(leafBg());
        // NB20：小白点行为全局统一 —— 点一下回桌面（顺手退出极简屏）。
        // 原先「点一下出全部应用」违背用户需求「点击无论什么界面都回桌面主页」。
        t.setOnClickListener(v -> finish());
        return t;
    }

    private android.graphics.drawable.Drawable leafBg() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor((0x66 << 24) | (Skin.c(R.color.leaf) & 0x00FFFFFF));
        g.setStroke(dp(1), Skin.c(R.color.leaf));
        return g;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // android.app.Activity：API 33+ 起系统推荐 PredictiveBack，
    // 显式 @Deprecated 标记本类仍走旧回调（车机 ROM 普遍 API 33 以下，行为不变）
    @Deprecated
    @Override
    public void onBackPressed() {
        finish();   // 4.8 返回键回桌面，不留在极简屏
    }

    @Override protected void onResume() {
        super.onResume();
        ticker.removeCallbacks(tickRun);
        ticker.post(tickRun);
        Lyrics.start(this);
        Lyrics.addSink(gardenSink);
        paintLyric(Lyrics.window(this, model, model.lyricLines));
    }

    @Override protected void onPause() {
        ticker.removeCallbacks(tickRun);
        Lyrics.removeSink(gardenSink);
        super.onPause();
    }

    private final Lyrics.Sink gardenSink = new Lyrics.Sink() {
        @Override public void onLyric(String[] w, String t, String a) { paintLyric(w); }
    };

    /** 只改文字，不重建视图：一秒一跳，重建会闪。 */
    private void paintLyric(String[] w) {
        if (lyricView == null) return;
        if (w == null || w.length == 0 || (w.length == 1 && w[0].isEmpty())) {
            lyricView.setText("（没有在放歌）");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : w) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        lyricView.setText(sb.length() == 0 ? "（没有在放歌）" : sb.toString());
    }
}
