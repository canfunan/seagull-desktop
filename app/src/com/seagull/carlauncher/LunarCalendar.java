package com.seagull.carlauncher;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * I1 农历：单文件查表法（1900-2100，经典 17 位编码）。
 *
 * 位表由 lunar-java（cn.6tail:lunar 1.7.3，Apache-2.0，仅生成期使用）逐项生成，
 * 与社区流传的 calendar.js 经典表逐位一致（1900=0x04bd8、1901=0x04ae0...）。
 * 编码约定：
 *   - bit0-3   闰月月份（0 = 当年无闰月）；
 *   - bit4-15  1-12 月大小月（0x8000 = 正月，向右递减；1 = 大月 30 天）；
 *   - bit16    闰月大小（1 = 30 天）。
 * 算法：以 1900-01-31（农历 1900 年正月初一）为锚点算天数差，逐年逐月扣减。
 * 校验：app/selfcheck LunarCheck 钉 30 个日期（含春节/闰月/表边界）。
 */
public final class LunarCalendar {

    private static final int[] LUNAR_INFO = {
0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 
0x09ad0, 0x055d2, 0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 
0x0d6a0, 0x0ada2, 0x095b0, 0x14977, 0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 
0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970, 0x06566, 0x0d4a0, 
0x0ea50, 0x16a95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950, 
0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 
0x0a950, 0x0b557, 0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 
0x052b0, 0x0a9a8, 0x0e950, 0x06aa0, 0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 
0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0, 0x096d0, 0x04dd5, 
0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6, 
0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 
0x0ab60, 0x09570, 0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 
0x05ac0, 0x0ab60, 0x096d5, 0x092e0, 0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 
0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5, 0x0a950, 0x0b4a0, 
0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930, 
0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 
0x0ea65, 0x0d530, 0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 
0x1d0b6, 0x0d250, 0x0d520, 0x0dd45, 0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 
0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0, 0x14b63, 0x09370, 
0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06aa0, 0x1a6c4, 0x0aae0, 
0x092e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 
0x0a6d0, 0x055d4, 0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 
0x055a0, 0x0aba4, 0x0a5b0, 0x052b0, 0x0b273, 0x06930, 0x07337, 0x06aa0, 
0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160, 0x0e968, 0x0d520, 
0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252, 
0x0d520,
    };

    private static final String[] MONTH_CN = {
            "正月", "二月", "三月", "四月", "五月", "六月",
            "七月", "八月", "九月", "十月", "冬月", "腊月"
    };
    private static final String[] DAY_CN = {
            "", "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    };
    private static final String[] STEMS = {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"};
    private static final String[] BRANCHES = {"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"};
    private static final String[] ANIMALS = {"鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪"};

    private LunarCalendar() { }

    /** 农历日结果：year 农历年、month 1-12、leap 是否闰月、day 1-30。 */
    public static final class LunarDate {
        public final int year, month, day;
        public final boolean leap;
        LunarDate(int y, int m, int d, boolean l) { year = y; month = m; day = d; leap = l; }
    }

    private static int leapMonth(int lunarYear) {
        return LUNAR_INFO[lunarYear - 1900] & 0xF;
    }

    /** 农历某月天数（leap=false 问 1-12 月；leap=true 问当年闰月）。 */
    private static int monthDays(int lunarYear, int month, boolean leap) {
        if (leap) return (LUNAR_INFO[lunarYear - 1900] & 0x10000) != 0 ? 30 : 29;
        return (LUNAR_INFO[lunarYear - 1900] & (0x10000 >> month)) != 0 ? 30 : 29;
    }

    private static int yearDays(int lunarYear) {
        int sum = 348;
        for (int m = 1; m <= 12; m++) if (monthDays(lunarYear, m, false) == 30) sum++;
        int leap = leapMonth(lunarYear);
        if (leap > 0) sum += monthDays(lunarYear, leap, true);
        return sum;
    }

    /** 公历转农历；支持范围外（1900-01-31 前 / 2100-12 月后）返回 null。 */
    public static LunarDate toLunar(int year, int month, int day) {
        long offset = daysFromAnchor(year, month, day);
        if (offset < 0) return null;
        int lunarYear = 1900;
        while (lunarYear < 2100 && offset >= yearDays(lunarYear)) {
            offset -= yearDays(lunarYear);
            lunarYear++;
        }
        if (offset >= yearDays(lunarYear)) return null;   // 2100 表末之后
        int leap = leapMonth(lunarYear);
        for (int m = 1; m <= 12; m++) {
            int len = monthDays(lunarYear, m, false);
            if (offset < len) return new LunarDate(lunarYear, m, (int) offset + 1, false);
            offset -= len;
            if (m == leap) {
                int llen = monthDays(lunarYear, m, true);
                if (offset < llen) return new LunarDate(lunarYear, m, (int) offset + 1, true);
                offset -= llen;
            }
        }
        return new LunarDate(lunarYear, 12, (int) offset + 1, false);
    }

    /** UTC 口径算距 1900-01-31 的天数差，规避时区把锚点挪一天。 */
    private static long daysFromAnchor(int year, int month, int day) {
        Calendar a = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        a.clear();
        a.set(1900, Calendar.JANUARY, 31);
        Calendar t = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        t.clear();
        t.set(year, month - 1, day);
        return (t.getTimeInMillis() - a.getTimeInMillis()) / 86_400_000L;
    }

    /** "腊月十九" / "闰六月初三"；初一显示月名（"八月"），传统日历口径。 */
    public static String monthDayCn(LunarDate d) {
        if (d == null) return "";
        String m = (d.leap ? "闰" : "") + MONTH_CN[d.month - 1];
        return d.day == 1 ? m : m + DAY_CN[d.day];
    }

    /** 干支 + 生肖，如 "乙巳(蛇)"。 */
    public static String yearGanZhi(LunarDate d) {
        if (d == null) return "";
        return STEMS[(d.year - 4) % 10] + BRANCHES[(d.year - 4) % 12]
                + "(" + ANIMALS[(d.year - 4) % 12] + ")";
    }

    /** 日历行："农历 腊月十九 · 乙巳(蛇)"；超表返回空串。 */
    public static String lineFor(int year, int month, int day) {
        LunarDate d = toLunar(year, month, day);
        if (d == null) return "";
        String s = monthDayCn(d) + " · " + yearGanZhi(d);
        return "农历 " + s;
    }
}
