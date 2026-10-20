package com.seagull.carlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.media.MediaMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * 常驻底部 Dock —— 车机风格悬浮磨砂玻璃底栏（批次 CH 整条重画）。
 *
 * 布局从左到右三段（用户给的车机底栏视觉稿）：
 *   · 左：四格应用菜单（进全部应用页）+ 数字时间（白色无衬线粗体）；
 *   · 中：Dock 应用真实图标（圆形裁剪、横向等距）+ 媒体横幅预览条 ——
 *     当前在放的歌直接显示「音乐 App 图标 + 歌名 + 暂停/播放 + 下一曲」，
 *     媒体控件嵌在 Dock 里，不再单独开大卡片（MiniPlayer 退役）；
 *   · 右：海鸥 Logo（点击回桌面）+ 网络标识（WiFi/5G/4G/3G/2G）+ 电池图标与电量；
 *     全部轻量化单色白，不抢视觉重心。退出键删除。
 *
 * 视觉：磨砂玻璃 = 车机没有 RenderEffect（API 31 才有），用 80% 透明近黑底
 * + 1px 玻璃描边 + 顶部高光线模拟；大圆角 + 四周留边悬浮在壁纸/内容之上。
 * 图标恒白细线几何，不跟主题换色（玻璃条恒深，亮壁纸下同样成立）。
 *
 * 音乐/地图用什么软件就显示什么图标：Dock 图标取自 LauncherModel.dock 的
 * 真实应用图标；媒体横幅图标取当前媒体会话包名的应用图标。
 */
public class DockBar extends LinearLayout {

    /** 回调：四格菜单 / Dock 应用（点按 + 长按管理）/ Logo 回桌面。 */
    public interface Listener {
        void onApps();
        void onApp(LauncherModel.App a);
        void onAppLongPress(LauncherModel.App a);
        void onHome();
    }

    private static final int MAX_ICONS = 6;

    /** 玻璃条恒深色（色值搬进 GlassDrawable 共用），圆角半径本类自留。 */
    private static final int BAR_CORNER_DP = 24;
    private static final int INK = 0xFFFFFFFF;
    private static final int INK_DIM = 0xB3FFFFFF;
    private static final int BANNER_BG = 0x2EFFFFFF;

    /** 白色细线几何图标：四格菜单 / 海鸥 Logo / 播放三键。 */
    static final int G_APPS = 0;
    static final int G_LOGO = 1;
    static final int G_PLAY = 2;
    static final int G_PAUSE = 3;
    static final int G_NEXT = 4;
    static final int G_PREV = 5;

    private final Listener listener;
    private LauncherModel model;

    private final LinearLayout apps;
    private final LinearLayout center;
    private final TextView clock;
    private final LinearLayout media;
    private final ImageView mediaIcon;
    private final TextView mediaTitle;
    private final GlyphIconView btnPlay;
    private final TextView netLabel;
    private final BatteryView battery;
    private final TextView batteryPct;

    private Runnable onBannerClick;

    /** 媒体 App 图标小缓存：横幅刷新很勤，别每次都走 Binder。 */
    private static final android.util.LruCache<String, Drawable> MEDIA_ICONS =
            new android.util.LruCache<>(8);

    public DockBar(Context c, LauncherModel m, final Listener l) {
        super(c);
        model = m;
        listener = l;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackground(new GlassDrawable(BAR_CORNER_DP));
        int padX = dp(c, 14);
        setPadding(padX, 0, padX, 0);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        dp(view.getContext(), BAR_CORNER_DP));
            }
        });

        /* ---- 左段：四格菜单 + 时间 ---- */
        LinearLayout left = new LinearLayout(c);
        left.setOrientation(HORIZONTAL);
        left.setGravity(Gravity.CENTER_VERTICAL);

        GlyphIconView menu = new GlyphIconView(c, G_APPS).scale(0.75f);
        menu.setContentDescription("全部应用");
        menu.setOnClickListener(v -> { if (listener != null) listener.onApps(); });
        left.addView(menu, new LinearLayout.LayoutParams(dp(c, 32), dp(c, 32)));

        clock = new TextView(c);
        clock.setTextColor(INK);
        clock.setTextSize(16);
        clock.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        clock.setIncludeFontPadding(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = dp(c, 9);
        left.addView(clock, clp);
        addView(left, new LinearLayout.LayoutParams(-2, -2));

        /* ---- 中段：Dock 应用图标 + 媒体横幅（居中，两侧让位） ---- */
        center = new LinearLayout(c);
        center.setOrientation(HORIZONTAL);
        center.setGravity(Gravity.CENTER_VERTICAL);
        addView(center, new LinearLayout.LayoutParams(0, -2, 1f));

        apps = new LinearLayout(c);
        apps.setOrientation(HORIZONTAL);
        apps.setGravity(Gravity.CENTER_VERTICAL);
        center.addView(apps, new LinearLayout.LayoutParams(-2, -2));

        media = new LinearLayout(c);
        media.setOrientation(HORIZONTAL);
        media.setGravity(Gravity.CENTER_VERTICAL);
        media.setBackground(Skin.round(BANNER_BG, 18));
        media.setPadding(dp(c, 8), dp(c, 5), dp(c, 4), dp(c, 5));
        media.setVisibility(GONE);
        // 批次 S1：weight=1 占满中段剩余宽——横幅永不超宽（旧 fitPass「放不下整条藏」机制删除）
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -2, 1f);
        mlp.leftMargin = dp(c, 10);
        center.addView(media, mlp);
        media.setOnClickListener(v -> { if (onBannerClick != null) onBannerClick.run(); });

        mediaIcon = new ImageView(c);
        media.addView(mediaIcon, new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)));

        // R1：标题 + 细进度条（无时长隐藏）；批次 S1：标题列 weight 弹性收缩
        LinearLayout titleCol = new LinearLayout(c);
        titleCol.setOrientation(VERTICAL);
        titleCol.setGravity(Gravity.CENTER_VERTICAL);
        mediaTitle = new TextView(c);
        mediaTitle.setTextColor(INK);
        mediaTitle.setTextSize(13);
        mediaTitle.setSingleLine(true);
        mediaTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mediaTitle.setPadding(dp(c, 7), 0, dp(c, 4), 0);
        titleCol.addView(mediaTitle, new LinearLayout.LayoutParams(-2, -2));
        progTrack = new FrameLayout(c);
        progTrack.setBackground(Skin.round(0x33FFFFFF, 3));
        progFill = new View(c);
        progFill.setBackground(Skin.round(0xE6FFFFFF, 3));
        progTrack.addView(progFill, new FrameLayout.LayoutParams(0, dp(c, 3)));
        LinearLayout.LayoutParams tclp = new LinearLayout.LayoutParams(-1, dp(c, 3));
        tclp.topMargin = dp(c, 3);
        tclp.leftMargin = dp(c, 7);
        progTrack.setVisibility(GONE);
        titleCol.addView(progTrack, tclp);
        LinearLayout.LayoutParams mclp = new LinearLayout.LayoutParams(0, -2, 1f);
        mclp.leftMargin = dp(c, 2);
        media.addView(titleCol, mclp);

        GlyphIconView btnPrev = new GlyphIconView(c, G_PREV).scale(0.74f);
        btnPrev.setContentDescription("上一曲");
        btnPrev.setOnClickListener(v -> MediaListenerService.prev());
        media.addView(btnPrev, new LinearLayout.LayoutParams(dp(c, 34), dp(c, 34)));

        btnPlay = new GlyphIconView(c, G_PAUSE).scale(0.74f);
        btnPlay.setContentDescription("播放/暂停");
        btnPlay.setOnClickListener(v -> MediaListenerService.playPause());
        media.addView(btnPlay, new LinearLayout.LayoutParams(dp(c, 34), dp(c, 34)));

        GlyphIconView btnNext = new GlyphIconView(c, G_NEXT).scale(0.74f);
        btnNext.setContentDescription("下一曲");
        btnNext.setOnClickListener(v -> MediaListenerService.next());
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(c, 34), dp(c, 34));
        nlp.leftMargin = dp(c, 2);
        media.addView(btnNext, nlp);

        /* ---- 右段：Logo + 网络 + 电池 ---- */
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);

        GlyphIconView logo = new GlyphIconView(c, G_LOGO).scale(0.73f);
        logo.setContentDescription("回桌面");
        logo.setOnClickListener(v -> { if (listener != null) listener.onHome(); });
        right.addView(logo, new LinearLayout.LayoutParams(dp(c, 30), dp(c, 30)));

        netLabel = new TextView(c);
        netLabel.setTextColor(INK);
        netLabel.setTextSize(12);
        netLabel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        netLabel.setIncludeFontPadding(false);
        LinearLayout.LayoutParams nlpl = new LinearLayout.LayoutParams(-2, -2);
        nlpl.leftMargin = dp(c, 10);
        right.addView(netLabel, nlpl);

        battery = new BatteryView(c);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(c, 24), dp(c, 13));
        blp.leftMargin = dp(c, 10);
        right.addView(battery, blp);

        batteryPct = new TextView(c);
        batteryPct.setTextColor(INK);
        batteryPct.setTextSize(12);
        batteryPct.setIncludeFontPadding(false);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.leftMargin = dp(c, 4);
        right.addView(batteryPct, plp);
        addView(right, new LinearLayout.LayoutParams(-2, -2));

        bind(model);
        tick();
    }

    /** 媒体横幅空白区点击（宿主接「导焦到媒体画布」）。 */
    public void setOnBannerClick(Runnable r) { onBannerClick = r; }

    /** 重画 Dock 应用区（固定列表变了 / 换肤后调用）。 */
    public void bind(LauncherModel m) {
        model = m;
        apps.removeAllViews();
        if (m == null) return;
        List<String> keys = m.dockKeys();
        int n = Math.min(keys.size(), Math.max(1, Math.min(m.dockCount, MAX_ICONS)));
        int added = 0;
        Context c = getContext();
        for (String k : keys) {
            if (added >= n) break;
            // find 匹配不上时按包名兜底：历史存档里存过裸包名 key（替换应用的
            // 旧 bug），照常渲染；下次落档走全 key 自愈
            LauncherModel.App a = m.find(k);
            if (a == null) a = m.findByPkg(k);
            if (a == null) continue;
            final LauncherModel.App fa = a;
            Drawable d = m.icon(a);
            if (d == null) continue;
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(d);
            iv.setContentDescription(a.label);
            roundClip(iv, 20);
            iv.setOnClickListener(v -> { if (listener != null) listener.onApp(fa); });
            iv.setOnLongClickListener(v -> {
                if (listener != null) listener.onAppLongPress(fa);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40));
            if (added > 0) lp.leftMargin = dp(c, 10);
            apps.addView(iv, lp);
            added++;
        }
        apps.setVisibility(added > 0 ? VISIBLE : GONE);
    }

    /** 媒体横幅：没会话整条隐藏不占位；有则显示 App 图标 + 歌名 + 播放态。
     *  窄屏放不下时横幅自动让位（suppress），同一条媒体不再反复冒头；换歌或
     *  Dock 尺寸变化后重新尝试。 */
    public void bindMedia(MediaListenerService.Snap s) {
        if (s == null || s.pkg == null || s.pkg.isEmpty()) {
            lastSnap = null;
            media.setVisibility(GONE);
            removeCallbacks(mediaTick);
            return;
        }
        lastSnap = s;
        String key = s.pkg + "|" + s.title;
        currentKey = key;
        media.setVisibility(VISIBLE);
        if (!key.equals(coverKey)) {   // 换曲才复位图标；同曲保封面
            coverKey = null;
            mediaIcon.setTag(null);
            mediaIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mediaIcon.setImageDrawable(mediaAppIcon(s.pkg));
        }
        String line = s.title != null && !s.title.isEmpty()
                ? s.title + (s.artist != null && !s.artist.isEmpty() ? " · " + s.artist : "")
                : s.pkg;
        mediaTitle.setText(line);
        btnPlay.setKind(s.playing ? G_PAUSE : G_PLAY);
        removeCallbacks(mediaTick);
        post(mediaTick);
    }

    private MediaListenerService.Snap lastSnap;
    private String currentKey;
    private String coverKey;
    private final FrameLayout progTrack;
    private final View progFill;

    /** R1：横幅可见时 1s 一跳——进度推进 + 封面补位（异步解码下轮上屏）。 */
    private final Runnable mediaTick = new Runnable() {
        @Override public void run() {
            if (media.getVisibility() != VISIBLE) return;
            float f = MediaListenerService.mediaProgress();
            if (f < 0) {
                progTrack.setVisibility(GONE);
            } else {
                progTrack.setVisibility(VISIBLE);
                int tw = progTrack.getWidth() - progTrack.getPaddingLeft()
                        - progTrack.getPaddingRight();
                if (tw > 0) {
                    FrameLayout.LayoutParams flp =
                            (FrameLayout.LayoutParams) progFill.getLayoutParams();
                    int w = Math.max(dp(getContext(), 2), Math.round(tw * f));
                    if (flp.width != w) { flp.width = w; progFill.setLayoutParams(flp); }
                }
            }
            MediaListenerService.Snap s = MediaListenerService.snap();
            MediaMetadata md = MediaListenerService.meta();
            Bitmap art = md == null ? null : CoverCache.get(s.title, s.artist, md);
            if (art != null && !art.isRecycled()) {
                mediaIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                mediaIcon.setImageBitmap(art);
                mediaIcon.setTag("cover");
                coverKey = currentKey;
            } else if (mediaIcon.getTag() != null) {
                mediaIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                mediaIcon.setImageDrawable(mediaAppIcon(s.pkg));
                mediaIcon.setTag(null);
                coverKey = null;
            }
            postDelayed(this, 1000L);
        }
    };

    /** 时间 / 电量 / 网络一跳（30s 一次足够）。 */
    public void tick() {
        Context c = getContext();
        clock.setText(SysOps.clockHHmm());
        int lv = batteryLevel(c);
        battery.setLevel(lv);
        batteryPct.setText(lv >= 0 ? lv + "%" : "--%");
        netLabel.setText(networkLabel());
    }

    /** 换肤后调用：玻璃条恒深不跟主题，只需重画 Dock 应用与状态值。 */
    public void applySkin() {
        bind(model);
        tick();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(mediaTick);
        super.onDetachedFromWindow();
    }

    /* ---------------- 数据 ---------------- */

    /* 默认 Dock 位次收敛到 LauncherModel.dockKeys()（替换/拿下来与绘制共用同一套）。 */

    private Drawable mediaAppIcon(String pkg) {
        Drawable d = MEDIA_ICONS.get(pkg);
        if (d != null) {
            Drawable.ConstantState cs = d.getConstantState();
            return cs != null ? cs.newDrawable(getResources()) : d;
        }
        try {
            d = getContext().getPackageManager().getApplicationIcon(pkg);
            if (d != null) MEDIA_ICONS.put(pkg, d);
        } catch (Throwable t) {
            d = null;
        }
        return d;
    }

    private static int batteryLevel(Context c) {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager)
                    c.getSystemService(Context.BATTERY_SERVICE);
            int p = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return p < 0 ? -1 : Math.min(100, p);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 网络标识：WiFi 优先；有蜂窝链路但代际未知 → 5G（车机基本 4G/5G 在线，
     * 无 READ_PHONE_STATE 时 getNetworkType 拿 UNKNOWN，这是诚实的兜底）；
     * 完全没网 → "--"，不硬编一个假代际。
     */
    private String networkLabel() {
        boolean cellular = false;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network nw = cm == null ? null : cm.getActiveNetwork();
            android.net.NetworkCapabilities caps =
                    nw == null ? null : cm.getNetworkCapabilities(nw);
            if (caps == null) return "--";
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return "WiFi";
            cellular = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Throwable ignore) {}
        try {
            android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager)
                    getContext().getSystemService(Context.TELEPHONY_SERVICE);
            int t = tm == null ? android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN
                    : tm.getNetworkType();
            switch (t) {
                case android.telephony.TelephonyManager.NETWORK_TYPE_NR:
                    return "5G";
                case android.telephony.TelephonyManager.NETWORK_TYPE_LTE:
                    return "4G";
                case android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP:
                case android.telephony.TelephonyManager.NETWORK_TYPE_HSPA:
                case android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA:
                case android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA:
                case android.telephony.TelephonyManager.NETWORK_TYPE_UMTS:
                case android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_0:
                case android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_A:
                case android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_B:
                case android.telephony.TelephonyManager.NETWORK_TYPE_EHRPD:
                case android.telephony.TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                    return "3G";
                case android.telephony.TelephonyManager.NETWORK_TYPE_GPRS:
                case android.telephony.TelephonyManager.NETWORK_TYPE_EDGE:
                case android.telephony.TelephonyManager.NETWORK_TYPE_CDMA:
                case android.telephony.TelephonyManager.NETWORK_TYPE_1xRTT:
                case android.telephony.TelephonyManager.NETWORK_TYPE_IDEN:
                    return "2G";
                default:
                    return cellular ? "5G" : "--";
            }
        } catch (Throwable ignore) {}
        return cellular ? "5G" : "--";
    }

    /* ---------------- 形 ---------------- */

    /** 应用图标圆形裁剪：outline 圆角 + clipToOutline（API 21+，minSdk 29 稳）。 */
    private static void roundClip(final ImageView iv, float radiusDp) {
        final float r = dp(iv.getContext(), radiusDp);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        iv.setClipToOutline(true);
    }

    private static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    /** 单个白色矢量图标：细线几何，线宽 ~7%，圆头圆角。contentScale 把图形
     *  缩在小 View 里画，外面留出的整块面积全给触控热区（车机手指友好）。 */
    static final class GlyphIconView extends View {
        private int kind;
        private float contentScale = 1f;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF tmp = new RectF();

        GlyphIconView(Context c, int kind) {
            super(c);
            this.kind = kind;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(INK);
            fillP.setStyle(Paint.Style.FILL);
            fillP.setColor(INK);
        }

        /** 图形内容占比（0~1]：View 尺寸大、图形小 = 触控热区大。 */
        GlyphIconView scale(float sc) {
            contentScale = Math.max(0.4f, Math.min(1f, sc));
            return this;
        }

        /** 批次 S2：实例级着色（独立播放器组件换色用；不影响 DockBar 的 INK 默认）。 */
        GlyphIconView tint(int color) {
            stroke.setColor(color);
            fillP.setColor(color);
            invalidate();
            return this;
        }

        public void setKind(int k) {
            if (kind != k) { kind = k; invalidate(); }
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(w, h) * contentScale;
            float cx = w / 2f, cy = h / 2f;
            stroke.setStrokeWidth(s * 0.08f);
            path.reset();

            switch (kind) {
                case G_APPS: {
                    // 四格应用菜单：2x2 圆角方块，细线框（多任务入口的既有认知）
                    float half = s * 0.16f, off = s * 0.22f, rad = s * 0.05f;
                    for (int sx = -1; sx <= 1; sx += 2) {
                        for (int sy = -1; sy <= 1; sy += 2) {
                            tmp.set(cx + sx * off - half, cy + sy * off - half,
                                    cx + sx * off + half, cy + sy * off + half);
                            path.addRoundRect(tmp, rad, rad, Path.Direction.CW);
                        }
                    }
                    cv.drawPath(path, stroke);
                    break;
                }
                case G_LOGO: {
                    // 海鸥 Logo：两段弧线组成的 M 形飞鸟剪影（单色细线）
                    path.moveTo(cx - s * 0.44f, cy + s * 0.14f);
                    path.quadTo(cx - s * 0.20f, cy - s * 0.34f, cx, cy - s * 0.02f);
                    path.quadTo(cx + s * 0.20f, cy - s * 0.34f, cx + s * 0.44f, cy + s * 0.14f);
                    cv.drawPath(path, stroke);
                    break;
                }
                case G_PLAY: {
                    // 播放：实心三角
                    path.moveTo(cx - s * 0.22f, cy - s * 0.30f);
                    path.lineTo(cx + s * 0.30f, cy);
                    path.lineTo(cx - s * 0.22f, cy + s * 0.30f);
                    path.close();
                    cv.drawPath(path, fillP);
                    break;
                }
                case G_PAUSE: {
                    // 暂停：两根圆头竖条
                    float x1 = cx - s * 0.14f, x2 = cx + s * 0.14f;
                    cv.drawLine(x1, cy - s * 0.26f, x1, cy + s * 0.26f, stroke);
                    cv.drawLine(x2, cy - s * 0.26f, x2, cy + s * 0.26f, stroke);
                    break;
                }
                case G_PREV: {
                    // 上一曲：左竖条 + 实心三角（G_NEXT 镜像）
                    cv.drawLine(cx - s * 0.26f, cy - s * 0.26f, cx - s * 0.26f, cy + s * 0.26f, stroke);
                    path.moveTo(cx + s * 0.30f, cy - s * 0.24f);
                    path.lineTo(cx - s * 0.12f, cy);
                    path.lineTo(cx + s * 0.30f, cy + s * 0.24f);
                    path.close();
                    cv.drawPath(path, fillP);
                    break;
                }
                case G_NEXT:
                default: {
                    // 下一曲：实心三角 + 右侧竖条
                    path.moveTo(cx - s * 0.30f, cy - s * 0.24f);
                    path.lineTo(cx + s * 0.12f, cy);
                    path.lineTo(cx - s * 0.30f, cy + s * 0.24f);
                    path.close();
                    cv.drawPath(path, fillP);
                    cv.drawLine(cx + s * 0.26f, cy - s * 0.26f, cx + s * 0.26f, cy + s * 0.26f, stroke);
                    break;
                }
            }
        }
    }

    /** 电池图标：圆角外壳 + 顶端正极 + 按电量填充；<20% 转警示色。 */
    private static final class BatteryView extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int level = -1;

        BatteryView(Context c) {
            super(c);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(INK_DIM);
            fillP.setStyle(Paint.Style.FILL);
        }

        void setLevel(int lv) {
            if (level != lv) { level = lv; invalidate(); }
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float bodyR = h * 0.28f;
            float bodyW = w * 0.82f;
            stroke.setStrokeWidth(h * 0.11f);
            RectF body = new RectF(0.5f, 0.5f, bodyW, h - 0.5f);
            cv.drawRoundRect(body, bodyR, bodyR, stroke);
            // 正极帽
            float capX0 = bodyW + h * 0.16f;
            cv.drawLine(capX0, h * 0.32f, capX0, h * 0.68f, stroke);
            if (level < 0) return;
            boolean low = level <= 20;
            fillP.setColor(low ? 0xFFFF9F0A : INK);
            float inset = h * 0.30f;
            float fw = (bodyW - inset * 2f) * level / 100f;
            if (fw <= 0.5f) return;
            RectF f = new RectF(inset, inset, inset + fw, h - inset);
            cv.drawRoundRect(f, bodyR * 0.5f, bodyR * 0.5f, fillP);
        }
    }
}
