package com.seagull.carlauncher;

import java.util.Arrays;

/**
 * R3 CARLINK 直连协议（纯静态，JVM 自检覆盖）。
 * 播放器把当前歌词行经广播推给桌面；桌面 5s 内新鲜则行级覆盖歌词窗口，
 * 断流/关闭/空行自动回落现有管线。协议文档：docs/CARLINK.md。
 */
public final class CarLink {
    /** 广播 action（桌面持有；播放器按文档发隐式广播）。 */
    public static final String ACTION = "com.seagull.carlauncher.CAR_LYRIC";
    public static final String EXTRA_LINE = "line";        // 当前行（空=间奏/无歌词清显）
    public static final String EXTRA_LINE_T = "line_t";    // 翻译行（可选）
    public static final String EXTRA_POS = "pos";          // 播放位置 ms（long，可选）
    public static final String EXTRA_ST = "st";            // 1=播放中 0=暂停（int，可选）
    public static final String EXTRA_TITLE = "title";      // 可选
    public static final String EXTRA_ARTIST = "artist";    // 可选
    public static final String EXTRA_COVER = "cover";      // 封面 URI/路径（可选）

    /** 广播新鲜窗：超过视为断流，回落现管线。 */
    public static final long FRESH_MS = 5000L;

    private CarLink() { }

    public static boolean fresh(long lastTs, long now) {
        return lastTs > 0 && now - lastTs <= FRESH_MS;
    }

    /**
     * 歌词窗口替换。on=设置开关、fresh=广播新鲜；两者都成立才覆盖：
     * n 槽全空、翻译行（非空且 n≥2）占倒数第二、当前行占最后——消费方
     * 统一把最后一槽当当前行。line 为空 = 全空（间奏清显）。
     */
    public static String[] override(String[] fallback, int n, String line, String lineT,
                                    boolean on, boolean fresh) {
        if (!on || !fresh || n <= 0) return fallback;
        String[] out = new String[n];
        Arrays.fill(out, "");
        if (line != null && !line.isEmpty()) {
            if (lineT != null && !lineT.isEmpty() && n >= 2) out[n - 2] = lineT;
            out[n - 1] = line;
        }
        return out;
    }

    /** 直连封面是否值得尝试解码。 */
    public static boolean coverUsable(String cover, boolean fresh) {
        return fresh && cover != null && !cover.isEmpty();
    }
}
