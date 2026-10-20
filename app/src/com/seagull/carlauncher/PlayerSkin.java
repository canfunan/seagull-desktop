package com.seagull.carlauncher;

/**
 * S2 独立播放器组件换色：6 预设（用户反馈"要能够设置大小颜色"）。
 * 纯静态，CarLinkCheck 同款无 UI 可测。
 */
public final class PlayerSkin {

    /** 预设名与色值——单色方案：文本/控制键/进度填充同色，进度槽恒 33% 透明白。 */
    public static final String[] NAMES =
            {"白", "绿", "蓝", "橙", "粉", "黄"};
    public static final int[] COLORS = {
            0xF2FFFFFF,   // 白（默认）
            0xFF4ADE80,   // 绿
            0xFF60A5FA,   // 蓝
            0xFFFB923C,   // 橙
            0xFFF472B6,   // 粉
            0xFFFACC15,   // 黄
    };

    private PlayerSkin() {}

    /** 越界（旧配置/手工改动）回落白色。 */
    public static int color(int idx) {
        if (idx < 0 || idx >= COLORS.length) return COLORS[0];
        return COLORS[idx];
    }

    /** 名称越界同色规则。 */
    public static String name(int idx) {
        if (idx < 0 || idx >= NAMES.length) return NAMES[0];
        return NAMES[idx];
    }

    /** 进度槽底色：恒 33% 透明白（深色壁纸兜底可读）。 */
    public static int trackBg() { return 0x33FFFFFF; }

    /** 毫秒 → m:ss（负值当 0）；超 1 小时 h:mm:ss。 */
    public static String fmtMs(long ms) {
        if (ms < 0) ms = 0;
        long sec = ms / 1000L;
        long h = sec / 3600L;
        long m = (sec % 3600L) / 60L;
        long s = sec % 60L;
        if (h > 0) return h + ":" + String.format(java.util.Locale.US, "%02d:%02d", m, s);
        return m + ":" + String.format(java.util.Locale.US, "%02d", s);
    }
}
