package com.seagull.carlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 野菜岛 —— 顶部居中的胶囊。
 *
 * 内容按设置走：宽 islandWidth、距顶 islandOffsetY（挖孔/圆角留位置）、
 * 岛上显示什么（当前实现：时钟；开了 islandLyric 就换成歌词）。
 * 长文本单行 + 尾部省略。
 *
 * ponytail: 现在只浮在桌面自己这一层。浮到别的应用上面需要再开一个悬浮窗
 * （和 BallService 同一套 WindowManager 玩法），等要跨应用显示时再加。
 */
public class IslandView extends View {

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private String label = "";
    private String sub = "";

    public IslandView(Context c) {
        super(c);
        bg.setColor(0xCC101014);
        text.setColor(Skin.c(R.color.text));
        text.setTextAlign(Paint.Align.CENTER);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    public void setLabel(String s, String s2) {
        label = s == null ? "" : s;
        sub = s2 == null ? "" : s2;
        invalidate();
    }

    /** 按模型摆位置 + 取内容；桌面每次 refresh 时调一次。 */
    public void bind(LauncherModel m) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        // 审计 M5：refresh 高频走到这里，尺寸/位置没变就不重设
        // （setLayoutParams 每次都全链 requestLayout）
        int nw = dp(m.islandWidth), nTop = dp(m.islandOffsetY);
        if (lp.width != nw || lp.topMargin != nTop) {
            lp.width = nw;
            lp.topMargin = nTop;
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            setLayoutParams(lp);
        }
        if (m.islandLyric) {
            String[] w = Lyrics.window(getContext(), m, 1);
            setLabel(w.length == 0 ? "" : w[0], Lyrics.nowPlaying());
        } else {
            setLabel(SysOps.clockHHmm(), SysOps.dateCn());
        }
    }

    @Override protected void onDraw(Canvas c) {
        float h = getHeight();
        r.set(0, 0, getWidth(), h);
        c.drawRoundRect(r, h / 2f, h / 2f, bg);
        String main = label;
        if (sub.isEmpty()) {
            text.setTextSize(sp(14));
            c.drawText(fit(main, getWidth() - dp(24)), getWidth() / 2f, h / 2f + sp(5), text);
        } else {
            text.setTextSize(sp(12));
            c.drawText(fit(main, getWidth() - dp(24)), getWidth() / 2f, h / 2f - sp(2), text);
            text.setColor(Skin.c(R.color.text_dim));
            text.setTextSize(sp(9));
            c.drawText(fit(sub, getWidth() - dp(24)), getWidth() / 2f, h / 2f + sp(13), text);
            text.setColor(Skin.c(R.color.text));
        }
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                getResources().getDisplayMetrics());
    }

    /** 单行截断：超宽就画省略号，免得字压出胶囊。 */
    private String fit(String s, float maxWidth) {
        if (s == null || s.isEmpty()) return "";
        if (text.measureText(s) <= maxWidth) return s;
        while (s.length() > 1 && text.measureText(s + "…") > maxWidth) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "…";
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = getLayoutParams() == null ? dp(320) : getLayoutParams().width;
        if (w <= 0) w = dp(320);
        setMeasuredDimension(w, dp(40));
    }
}
