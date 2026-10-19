package com.seagull.carlauncher;

import android.graphics.drawable.GradientDrawable;

/**
 * 运行时配色层（TODO P0-7）。
 *
 * 全部界面颜色都经 {@link #c(int)} 取，这里把「主题色 → 一整套深浅色」算好，
 * 换主题时调一次 {@link #apply(LauncherModel)}，整屏立刻变。
 *
 * colors.xml 里的值是编译期常量，所以这层放在代码里算 —— 不引新依赖，
 * 颜色混合自己按通道插值（androidx 的 ColorUtils 不在依赖里）。
 */
public final class Skin {

    private static int ground, panel, card, leaf, leafDim, text, textDim, bad, warn;
    /** 壁纸是亮图时，文字与顶/底栏要加深色底。 */
    public static boolean wallBright;
    /** 顶/底栏在亮壁纸上用半透明底（自动字底）。 */
    public static int barAlpha = 0xFF;

    /** N2 主题包三旋钮：圆角全局系数（50~150%）、玻璃透明度（卡片额外 alpha）、描边开关。 */
    public static float radiusScale = 1f;
    public static int glassAlpha = 0xFF;
    public static boolean outline = false;

    static {
        reset();
    }

    private Skin() {}

    /** 回到 colors.xml 的默认值（主题没加载时不会白屏）。默认 = 苹果互联深色。 */
    public static void reset() {
        ground = 0xFF000000; panel = 0xFF1C1C1E; card = 0xFF2C2C2E;
        leaf = 0xFF0A84FF; leafDim = 0xFF0060DF;
        text = 0xFFFFFFFF; textDim = 0xFF8E8E93;
        bad = 0xFFFF453A; warn = 0xFFFF9F0A;
        wallBright = false;
        barAlpha = 0xFF;
    }

    /** 取色：传 R.color.xxx，拿到当前主题下的实际颜色。 */
    public static int c(int resId) {
        if (resId == R.color.ground) return ground;
        if (resId == R.color.panel) return panel;
        if (resId == R.color.card) return card;
        if (resId == R.color.leaf) return leaf;
        if (resId == R.color.leaf_dim) return leafDim;
        if (resId == R.color.text) return text;
        if (resId == R.color.text_dim) return textDim;
        if (resId == R.color.bad) return bad;
        if (resId == R.color.warn) return warn;
        return resId;   // 不是调色板颜色就当它本身就是色值
    }

    /* ---------------- CarPlay 形语（批次 U） ---------------- */

    /** 屏幕密度：apply 时刷新，造形 helpers 直接换算 px，调用点不必传 Context。 */
    public static float density = 3f;

    /** 圆角矩形底：传已取好的色值 + 圆角 dp（受主题圆角系数缩放）。 */
    public static GradientDrawable round(int color, float dp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(Math.max(0f, dp) * radiusScale * density);
        if (outline && dp < 900f) {
            // N2 描边：文字/底色的中间调，淡到不抢内容；胶囊不描（太挤）
            g.setStroke(Math.max(1, Math.round(density)),
                    (0x40 << 24) | (mix(text, ground, 0.5f) & 0x00FFFFFF));
        }
        return g;
    }

    /** 胶囊底（按钮 / 把手 / 搜索框）：圆角大到半高以上即成胶囊。 */
    public static GradientDrawable pill(int color) { return round(color, 999f); }

    /** 同一层里带透明度的取色（顶/底栏用）。 */
    public static int bar(int resId) {
        int col = c(resId);
        return (barAlpha << 24) | (col & 0x00FFFFFF);
    }

    /** 按当前配置重算整套颜色。每个 Activity 创建时调一次。 */
    public static void apply(LauncherModel m) {
        if (m == null) { reset(); return; }
        int accent = Theme.accent(m);
        boolean light = isLight(m);
        try {
            float d = m.context().getResources().getDisplayMetrics().density;
            if (d > 0) density = d;
        } catch (Throwable ignore) {}

        // 苹果互联（批次 U）：中性灰阶底，不带强调色倾向——CarPlay 的视觉
        // 语言就是「黑底 + 灰卡片 + 系统蓝」。自定义强调色仍然生效（leaf）。
        if ("carplay".equals(m.themeId)) {
            if (light) {
                ground = 0xFFF2F2F7; panel = 0xFFFFFFFF; card = 0xFFFFFFFF;
                leaf = accent;
                leafDim = mix(accent, 0xFF000000, 0.20f);
                text = 0xFF000000;
                textDim = mix(0xFF3C3C43, 0xFFFFFFFF, 0.40f);
                bad = 0xFFFF3B30; warn = 0xFFFF9500;
            } else {
                ground = 0xFF000000; panel = 0xFF1C1C1E; card = 0xFF2C2C2E;
                leaf = accent;
                leafDim = mix(accent, 0xFF000000, 0.35f);
                text = 0xFFFFFFFF;
                textDim = 0xFF8E8E93;
                bad = 0xFFFF453A; warn = 0xFFFF9F0A;
            }
        } else if (light) {
            ground = mix(accent, 0xFFFFFFFF, 0.93f);
            panel = mix(accent, 0xFFFFFFFF, 0.84f);
            card = mix(accent, 0xFFFFFFFF, 0.74f);
            leaf = mix(accent, 0xFF000000, 0.18f);
            leafDim = mix(leaf, 0xFFFFFFFF, 0.40f);
            text = mix(accent, 0xFF1A1A1A, 0.80f);
            textDim = mix(text, 0xFFFFFFFF, 0.42f);
        } else {
            ground = mix(accent, 0xFF000000, 0.92f);
            panel = mix(accent, 0xFF000000, 0.85f);
            card = mix(accent, 0xFF000000, 0.77f);
            leaf = accent;
            leafDim = mix(accent, 0xFF000000, 0.36f);
            text = mix(accent, 0xFFFFFFFF, 0.90f);
            textDim = mix(text, ground, 0.45f);
        }

        // 亮色壁纸 + 深色主题 → 文字翻黑、顶底栏加半透明底（自动字底）
        wallBright = m.wallDim < 100 && Wallpaper.isBright(m.context(), currentWall(m));
        if (wallBright && !light) {
            text = 0xFF101410;
            textDim = mix(text, 0xFFFFFFFF, 0.35f);
        }
        barAlpha = wallBright ? 0xB0 : 0xFF;

        // N2 主题包旋钮：卡片玻璃（panel/card 吃额外透明度，压壁纸=毛玻璃观感）
        radiusScale = Math.max(0.4f, Math.min(2f, m.themeRadius / 100f));
        int ga = Math.max(0, Math.min(80, m.themeGlass));
        glassAlpha = 0xFF - (ga * 0xFF / 100);
        if (ga > 0) {
            panel = (glassAlpha << 24) | (panel & 0x00FFFFFF);
            card = (glassAlpha << 24) | (card & 0x00FFFFFF);
        }
        outline = m.themeOutline;
    }

    private static String currentWall(LauncherModel m) {
        if (isLight(m)) return m.wallDay;
        return m.wallNight != null && !m.wallNight.isEmpty() ? m.wallNight : m.wallDay;
    }

    /** 浅色 = 明确选浅色，或跟随系统且当前是白天（19:00~06:00 算夜间）。 */
    public static boolean isLight(LauncherModel m) {
        if (m == null) return false;
        if (m.dayNight == LauncherModel.DayNight.LIGHT) return true;
        if (m.dayNight == LauncherModel.DayNight.DARK) return false;
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 6 && h < 19;
    }

    /** 两色按 t 混合（t=0 全 a，t=1 全 b），alpha 取 0xFF。 */
    public static int mix(int a, int b, float t) {
        if (t < 0) t = 0; if (t > 1) t = 1;
        int aa = a & 0xFF000000, ab = b & 0xFF000000;
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (aa & ab) | (r << 16) | (g << 8) | bl;
    }
}
