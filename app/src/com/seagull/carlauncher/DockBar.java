package com.seagull.carlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

/**
 * 常驻底部导航栏 —— 苹果 CarPlay 风格（批次 CG 整条重画）。
 *
 * 用户原话「导航栏实在太丑了，想要苹果互联风格的图标和导航栏」。CarPlay 底栏的
 * 硬特征逐条落地：
 *   · 通栏贴底、无侧边距、无圆角，深色玻璃观感（车机没有 RenderEffect 毛玻璃，
 *     用 94% 不透明近黑 + 顶部 1px 高光模拟「玻璃上沿」）；
 *   · 只有图标没有文字、没有竖分隔线 —— 五个键等宽铺满（CarPlay dock 就是纯图标）；
 *   · 图标是白色细线几何：统一线宽 ~7%、圆头圆角，深浅主题都不换色
 *     （CarPlay 恒深色，浅色主题下这条深底栏同样成立）；
 *   · 选中态 = 纯白 + iOS 式小圆点，未选中 = 55% 白。
 *
 * 图标语义（对齐 CarPlay/苹果的既有认知）：
 *   首页 = 圆环（CarPlay 左下角 home 键就是这个白圈）；地图 = 导航箭头（实心）；
 *   音乐 = 连尾双音符 ♫（Apple Music 字形）；其他 = 四圆角方格（iOS 主屏网格）；
 *   退出 = 电源（弧 + 竖线）。
 */
public class DockBar extends LinearLayout {

    public static final int HOME = 0;
    public static final int MAP = 1;
    public static final int MUSIC = 2;
    public static final int APPS = 3;
    public static final int EXIT = 4;

    public interface Listener {
        void onDock(int which);
    }

    private static final int[] ICONS = {HOME, MAP, MUSIC, APPS, EXIT};

    /** CarPlay 恒深色：底条 94% 近黑，图标纯白 / 未选中 55% 白 / 圆点 60% 白。 */
    private static final int BAR_BG = 0xF0121214;
    private static final int BAR_EDGE = 0x26FFFFFF;   // 顶部玻璃上沿高光
    private static final int INK_ON = 0xFFFFFFFF;
    private static final int INK_OFF = 0x8CFFFFFF;
    private static final int DOT = 0x99FFFFFF;

    private final DockIconView[] icons = new DockIconView[ICONS.length];
    private final View[] dots = new View[ICONS.length];
    private int active = HOME;

    public DockBar(Context c, final Listener listener) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackground(new BarDrawable());
        setPadding(dp(6), 0, dp(6), 0);

        for (int i = 0; i < ICONS.length; i++) {
            final int which = ICONS[i];
            LinearLayout item = new LinearLayout(c);
            item.setOrientation(VERTICAL);
            item.setGravity(Gravity.CENTER);

            DockIconView icon = new DockIconView(c, which);
            icons[i] = icon;
            item.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));

            // iOS 式选中圆点：不认识的图标也能看出「现在在哪一页」
            View dot = new View(c);
            dot.setBackground(Skin.round(DOT, 2f));
            dot.setVisibility(INVISIBLE);
            dots[i] = dot;
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(4), dp(4));
            dl.topMargin = dp(4);
            item.addView(dot, dl);

            item.setPadding(dp(4), dp(4), dp(4), dp(2));
            item.setOnClickListener(v -> { if (listener != null) listener.onDock(which); });
            addView(item, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        syncActive();
    }

    /** 高亮某一项（由页面切换 / 点击驱动）。 */
    public void setActive(int which) {
        active = which;
        syncActive();
    }

    /** 换肤后调用：CarPlay 风格恒深色，不跟主题换色，只需重算选中态。 */
    public void applySkin() {
        syncActive();
    }

    private void syncActive() {
        for (int i = 0; i < ICONS.length; i++) {
            boolean on = ICONS[i] == active;
            if (icons[i] != null) icons[i].setActive(on);
            if (dots[i] != null) dots[i].setVisibility(on ? VISIBLE : INVISIBLE);
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    /**
     * 底条背景：94% 近黑 + 顶部 1px 高光。画成 Drawable 而不是叠 View，
     * 收起/唤出的位移动画不会把高光留在屏外。
     */
    private static final class BarDrawable extends android.graphics.drawable.Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);

        BarDrawable() {
            fill.setColor(BAR_BG);
            edge.setColor(BAR_EDGE);
            edge.setStrokeWidth(2f);
        }

        @Override public void draw(Canvas cv) {
            cv.drawRect(0, 0, getBounds().width(), getBounds().height(), fill);
            cv.drawLine(0, 1f, getBounds().width(), 1f, edge);
        }

        @Override public void setAlpha(int a) { fill.setAlpha(a); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {
            fill.setColorFilter(cf); invalidateSelf();
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** 单个矢量图标：白色细线几何，线宽统一 ~7%、圆头圆角；选中纯白、未选中 55% 白。 */
    static final class DockIconView extends View {
        private final int kind;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF tmp = new RectF();
        private boolean active;

        DockIconView(Context c, int kind) {
            super(c);
            this.kind = kind;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            fill.setStyle(Paint.Style.FILL);
            refresh();
        }

        void setActive(boolean on) { active = on; refresh(); }

        void refresh() {
            int col = active ? INK_ON : INK_OFF;
            stroke.setColor(col);
            fill.setColor(col);
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(w, h);
            float cx = w / 2f, cy = h / 2f;
            stroke.setStrokeWidth(s * 0.07f);
            path.reset();

            switch (kind) {
                case HOME: {
                    // CarPlay home 键：一枚白圈，圈内留空
                    float r = s * 0.36f;
                    cv.drawCircle(cx, cy, r, stroke);
                    break;
                }
                case MAP: {
                    // 导航箭头（实心，指向上）：tip / 右翼 / 底部凹口 / 左翼
                    float k = s * 0.04f;
                    path.moveTo(cx, cy - 9f * k);
                    path.lineTo(cx + 8.3f * k, cy + 8.3f * k);
                    path.lineTo(cx, cy + 4.6f * k);
                    path.lineTo(cx - 8.3f * k, cy + 8.3f * k);
                    path.close();
                    cv.drawPath(path, fill);
                    break;
                }
                case MUSIC: {
                    // 连尾双音符 ♫：斜置粗横梁 + 两根竖干 + 两个实心符头
                    float bx1 = cx - s * 0.20f, by1 = cy - s * 0.30f;
                    float bx2 = cx + s * 0.28f, by2 = cy - s * 0.38f;
                    stroke.setStrokeWidth(s * 0.11f);
                    cv.drawLine(bx1, by1, bx2, by2, stroke);
                    stroke.setStrokeWidth(s * 0.07f);
                    cv.drawLine(bx1, by1 + s * 0.02f, bx1, cy + s * 0.22f, stroke);
                    cv.drawLine(bx2, by2 + s * 0.02f, bx2, cy + s * 0.14f, stroke);
                    float hr = s * 0.105f;
                    path.reset();
                    path.addCircle(bx1 - hr * 0.6f, cy + s * 0.235f, hr, Path.Direction.CW);
                    path.addCircle(bx2 - hr * 0.6f, cy + s * 0.155f, hr, Path.Direction.CW);
                    cv.drawPath(path, fill);
                    break;
                }
                case APPS: {
                    // iOS 主屏网格：四枚圆角方格（实心）
                    float half = s * 0.125f, off = s * 0.21f, rad = s * 0.06f;
                    path.reset();
                    for (int sx = -1; sx <= 1; sx += 2) {
                        for (int sy = -1; sy <= 1; sy += 2) {
                            tmp.set(cx + sx * off - half, cy + sy * off - half,
                                    cx + sx * off + half, cy + sy * off + half);
                            path.addRoundRect(tmp, rad, rad, Path.Direction.CW);
                        }
                    }
                    cv.drawPath(path, fill);
                    break;
                }
                case EXIT:
                default: {
                    // 电源：缺口圆弧 + 顶部竖线
                    float r = s * 0.30f;
                    tmp.set(cx - r, cy - r * 0.9f, cx + r, cy + r * 1.1f);
                    path.addArc(tmp, -55f, 290f);
                    cv.drawPath(path, stroke);
                    path.reset();
                    path.moveTo(cx, cy - s * 0.46f);
                    path.lineTo(cx, cy - s * 0.04f);
                    cv.drawPath(path, stroke);
                    break;
                }
            }
        }
    }
}
