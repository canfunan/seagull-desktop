package com.seagull.carlauncher;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 壁纸桌面页（第 0 页）—— 复刻 Vivo 智能车载 CarLauncher 的
 * WallpaperDesktopFragment + TimeDateComponentView。
 *
 * 参考里这一页是「壁纸铺满 + 顶部时间日期组件」，没有图标网格、没有顶栏。
 * 时间用大字（参考 title_clock 64sp 量级），日期小字跟进，下面挂媒体/歌词行
 * （复刻参考里桌面的音乐组件位，数据直接取进程里的 Lyrics / MediaListener）。
 *
 * 本页背景透明：壁纸由 HomeActivity 铺在 root 上，这一页只叠时间文字，
 * 这样三页共底、翻页时壁纸连续不闪。
 */
public final class WallpaperPage extends FrameLayout {

    private final TextView clock, date, media, lyric;
    private LauncherModel model;

    public WallpaperPage(Context c, LauncherModel m) {
        super(c);
        this.model = m;
        setBackgroundColor(Color.TRANSPARENT);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        clock = tv(c, 62, R.color.text, true);
        date = tv(c, 16, R.color.text_dim, false);
        media = tv(c, 15, R.color.text, false);
        lyric = tv(c, 14, R.color.text_dim, false);

        col.addView(clock);
        col.addView(date, topMargin(c, 6));
        col.addView(media, topMargin(c, 30));
        col.addView(lyric, topMargin(c, 4));

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        clp.topMargin = Math.round(getResources().getDisplayMetrics().heightPixels * 0.13f);
        addView(col, clp);

        setOnClickListener(v -> { /* 单击桌面不做事，避免误触 */ });
        tick();
    }

    /** 跟着 HomeActivity 的 30s / Lyrics sink 刷新。 */
    public void tick() {
        if (clock != null) clock.setText(SysOps.clockHHmm());
        if (date != null) date.setText(SysOps.dateCn());

        String now = Lyrics.nowPlaying();
        if (media != null) {
            media.setText(now == null ? "" : now);
            media.setVisibility(now == null || now.isEmpty() ? View.GONE : View.VISIBLE);
        }
        String line = "";
        try {
            String[] w = Lyrics.window(getContext(), model, 1);
            if (w != null && w.length > 0 && w[0] != null) line = w[0];
        } catch (Throwable ignore) {}
        if (lyric != null) {
            lyric.setText(line);
            lyric.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    /** 换肤后重刷配色。 */
    public void applySkin() {
        if (clock != null) clock.setTextColor(Skin.c(R.color.text));
        if (date != null) date.setTextColor(Skin.c(R.color.text_dim));
        if (media != null) media.setTextColor(Skin.c(R.color.text));
        if (lyric != null) lyric.setTextColor(Skin.c(R.color.text_dim));
    }

    private static TextView tv(Context c, float size, int colorRes, boolean bold) {
        TextView t = new TextView(c);
        t.setTextColor(Skin.c(colorRes));
        t.setTextSize(size);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static LinearLayout.LayoutParams topMargin(Context c, float dpTop) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dpTop,
                c.getResources().getDisplayMetrics()));
        return lp;
    }
}
