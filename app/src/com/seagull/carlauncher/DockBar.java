package com.seagull.carlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 常驻底部导航栏 —— 复刻 Vivo 智能车载 CarLauncher 的 carmode_dock_view。
 *
 * 图标顺序与参考一致：首页 / 地图 / 音乐 / 其他 / 退出。
 * 参考布局里每一项是「42dp 图标 + 下方 24x3dp 指示条」，指示条只在选中项显示
 * （CarModeDockItemView 的 indicator_view）。图标本身是矢量，这里直接用 Path
 * 画出来，不引任何 drawable 资源，保证构建不依赖 aapt2 之外的额外素材。
 *
 * 导航栏不随页面切换消失：三页共用同一条，这正是参考里「导航栏不变」的落地方式。
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
    private static final String[] NAMES = {"首页", "地图", "音乐", "其他", "退出"};

    private final DockIconView[] icons = new DockIconView[ICONS.length];
    private final View[] indicators = new View[ICONS.length];
    private final TextView[] labels = new TextView[ICONS.length];
    private int active = HOME;

    public DockBar(Context c, final Listener listener) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        // 参考 card_corner_radius_dock = 18dp：导航栏是一条浮起来的圆角条，不是通栏底色块
        setBackground(Skin.round(Skin.c(R.color.panel), 18f));
        setPadding(dp(10), dp(4), dp(10), dp(4));

        for (int i = 0; i < ICONS.length; i++) {
            // 参考 carmode_dock_view 的 2dp×22dp 竖分隔：首页 ｜ 地图/音乐/其他 ｜ 退出
            if (i == 1 || i == 4) addView(divider(c));
            final int which = ICONS[i];
            LinearLayout item = new LinearLayout(c);
            item.setOrientation(VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(8), dp(3), dp(8), dp(1));

            DockIconView icon = new DockIconView(c, which);
            icons[i] = icon;
            item.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

            TextView label = new TextView(c);
            label.setText(NAMES[i]);
            label.setTextSize(12);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            labels[i] = label;
            item.addView(label, new LinearLayout.LayoutParams(-2, -2));

            View ind = new View(c);
            ind.setBackground(Skin.round(Skin.c(R.color.leaf), 1.5f));
            ind.setVisibility(INVISIBLE);
            indicators[i] = ind;
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(3));
            ilp.topMargin = dp(1);
            item.addView(ind, ilp);

            item.setOnClickListener(v -> { if (listener != null) listener.onDock(which); });
            addView(item, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        syncActive();
    }

    /** 参考里的 2dp×22dp 竖分隔。 */
    private View divider(Context c) {
        View v = new View(c);
        v.setBackground(Skin.round(0x1FFFFFFF, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(2), dp(22));
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        v.setLayoutParams(lp);
        return v;
    }

    /** 高亮某一项（由页面切换 / 点击驱动）。 */
    public void setActive(int which) {
        active = which;
        syncActive();
    }

    /** 换肤后重刷配色（Skin.apply 之后再调）。 */
    public void applySkin() {
        setBackground(Skin.round(Skin.c(R.color.panel), 18f));
        for (DockIconView ic : icons) if (ic != null) ic.refresh();
        for (View ind : indicators) if (ind != null) {
            ind.setBackground(Skin.round(Skin.c(R.color.leaf), 1.5f));
        }
        syncActive();
    }

    private void syncActive() {
        for (int i = 0; i < ICONS.length; i++) {
            boolean on = ICONS[i] == active;
            if (icons[i] != null) icons[i].setActive(on);
            if (indicators[i] != null) indicators[i].setVisibility(on ? VISIBLE : INVISIBLE);
            if (labels[i] != null) {
                labels[i].setTextColor(Skin.c(on ? R.color.leaf : R.color.text_dim));
                labels[i].setTypeface(labels[i].getTypeface(),
                        on ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    /** 单个矢量图标：线宽统一、圆头圆角，深浅主题共用一套几何。 */
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

        void setActive(boolean on) { active = on; invalidate(); }

        void refresh() {
            int col = active ? Skin.c(R.color.leaf) : Skin.c(R.color.text_dim);
            stroke.setColor(col);
            fill.setColor(col);
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(w, h);
            float cx = w / 2f, cy = h / 2f;
            stroke.setStrokeWidth(s * 0.075f);
            path.reset();

            switch (kind) {
                case HOME: {
                    float bw = s * 0.60f, bh = s * 0.46f;
                    path.moveTo(cx - bw / 2f, cy - bh * 0.10f);
                    path.lineTo(cx, cy - bh * 0.72f);
                    path.lineTo(cx + bw / 2f, cy - bh * 0.10f);
                    path.moveTo(cx - bw * 0.36f, cy - bh * 0.18f);
                    path.lineTo(cx - bw * 0.36f, cy + bh * 0.58f);
                    path.lineTo(cx + bw * 0.36f, cy + bh * 0.58f);
                    path.lineTo(cx + bw * 0.36f, cy - bh * 0.18f);
                    cv.drawPath(path, stroke);
                    break;
                }
                case MAP: {
                    float r = s * 0.22f;
                    path.addCircle(cx, cy - r * 0.55f, r, Path.Direction.CW);
                    path.moveTo(cx - r * 0.80f, cy - r * 0.05f);
                    path.lineTo(cx, cy + s * 0.40f);
                    path.lineTo(cx + r * 0.80f, cy - r * 0.05f);
                    cv.drawPath(path, stroke);
                    fill.setStyle(Paint.Style.FILL);
                    cv.drawCircle(cx, cy - r * 0.55f, r * 0.34f, fill);
                    break;
                }
                case MUSIC: {
                    float x1 = cx - s * 0.14f, x2 = cx + s * 0.20f;
                    float top = cy - s * 0.40f, bot = cy + s * 0.18f;
                    path.moveTo(x1, top);
                    path.lineTo(x1, bot);
                    path.moveTo(x2, top - s * 0.10f);
                    path.lineTo(x2, bot - s * 0.10f);
                    path.moveTo(x1, top);
                    path.lineTo(x2, top - s * 0.10f);
                    cv.drawPath(path, stroke);
                    float hr = s * 0.105f;
                    fill.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.addCircle(x1 - hr, bot, hr, Path.Direction.CW);
                    path.addCircle(x2 - hr, bot - s * 0.10f, hr, Path.Direction.CW);
                    cv.drawPath(path, fill);
                    break;
                }
                case APPS: {
                    float half = s * 0.15f, off = s * 0.245f, rad = s * 0.05f;
                    fill.setStyle(Paint.Style.FILL);
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
                    float r = s * 0.32f;
                    tmp.set(cx - r, cy - r * 0.85f, cx + r, cy + r * 1.15f);
                    path.addArc(tmp, -55f, 290f);
                    cv.drawPath(path, stroke);
                    path.reset();
                    path.moveTo(cx, cy - s * 0.48f);
                    path.lineTo(cx, cy - s * 0.04f);
                    cv.drawPath(path, stroke);
                    break;
                }
            }
        }
    }
}
