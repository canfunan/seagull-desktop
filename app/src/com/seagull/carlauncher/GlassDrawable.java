package com.seagull.carlauncher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/**
 * 磨砂玻璃底（批次 CH，从 DockBar 抽出共用）：80% 透明近黑圆角矩形 +
 * 1px 玻璃描边 + 顶部高光线。画成 Drawable 而非叠 View，收起/唤出的
 * 位移动画不会把高光留在屏外。DockBar 与 PipBoard 键条共用一份实现。
 */
class GlassDrawable extends Drawable {
    private static final int BAR_BG = 0xCC121216;
    private static final int BAR_EDGE = 0x22FFFFFF;
    private static final int BAR_TOP = 0x30FFFFFF;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint top = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;

    GlassDrawable(float cornerDp) {
        radius = cornerDp * Skin.density;
        fill.setColor(BAR_BG);
        edge.setColor(BAR_EDGE);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2f);
        top.setColor(BAR_TOP);
        top.setStrokeWidth(2f);
    }

    @Override public void draw(Canvas cv) {
        float w = getBounds().width(), h = getBounds().height();
        if (w <= 0 || h <= 0) return;
        cv.drawRoundRect(0, 0, w, h, radius, radius, fill);
        cv.drawRoundRect(1f, 1f, w - 1f, h - 1f, radius - 1f, radius - 1f, edge);
        cv.drawLine(radius, 1.5f, w - radius, 1.5f, top);
    }

    /** 三支画笔一起淡（fill/edge/top 各按自身基色 alpha 等比缩放）。 */
    @Override public void setAlpha(int a) {
        fill.setAlpha(a);
        edge.setAlpha(Math.round(0x22 * a / 255f));
        top.setAlpha(Math.round(0x30 * a / 255f));
        invalidateSelf();
    }
    @Override public void setColorFilter(android.graphics.ColorFilter cf) {
        fill.setColorFilter(cf); invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
