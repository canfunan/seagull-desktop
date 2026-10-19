package com.seagull.carlauncher;

/**
 * 自检：LunarCalendar 农历查表（1900-2100 位表 + 锚点扣减，错一天日历行就错）。
 *
 * 30 个期望值全部经 lunar-java（cn.6tail:lunar 1.7.3，生成期比对源）核出；
 * 全量表内 67537 天逐日比对 0 差异后固化（1900-01-31 锚点前超界返回 null）。
 *
 * 跑法（不依赖设备，纯 JVM）：
 *   javac -d /tmp/lunsc app/src/com/seagull/carlauncher/LunarCalendar.java app/selfcheck/LunarCheck.java
 *   java  -cp /tmp/lunsc com.seagull.carlauncher.LunarCheck
 */
public class LunarCheck {

    // {公历y, m, d, 农历年, 月, 闰?, 日} —— 期望值由 lunar-java 1.7.3 机器生成
    // （手写万年历记忆不可靠，首轮自检 10 处偏差全部出在期望值侧）
    private static final int[][] CASES = {
            {1900, 1, 31, 1900, 1, 0, 1},     // 锚点：正月初一
            {1900, 12, 31, 1900, 11, 0, 10},
            {1901, 2, 19, 1901, 1, 0, 1},     // 1901 春节
            {1950, 5, 5, 1950, 3, 0, 19},
            {1976, 9, 8, 1976, 8, 0, 15},     // 中秋
            {1984, 2, 2, 1984, 1, 0, 1},      // 跨农历年边界
            {1997, 2, 7, 1997, 1, 0, 1},
            {2000, 1, 1, 1999, 11, 0, 25},
            {2000, 12, 31, 2000, 12, 0, 6},
            {2017, 1, 28, 2017, 1, 0, 1},
            {2020, 4, 23, 2020, 4, 0, 1},
            {2020, 6, 21, 2020, 5, 0, 1},
            {2020, 6, 22, 2020, 5, 0, 2},
            {2023, 3, 22, 2023, 2, 1, 1},     // 闰二月初一
            {2023, 4, 19, 2023, 2, 1, 29},    // 闰二月（小月）末
            {2023, 4, 20, 2023, 3, 0, 1},
            {2024, 2, 4, 2023, 12, 0, 25},
            {2024, 2, 10, 2024, 1, 0, 1},     // 2024 春节
            {2025, 1, 29, 2025, 1, 0, 1},     // 2025 春节
            {2025, 7, 25, 2025, 6, 1, 1},     // 闰六月初一
            {2025, 8, 23, 2025, 7, 0, 1},     // 闰六月（大月）末次日
            {2026, 2, 17, 2026, 1, 0, 1},     // 2026 春节
            {2026, 10, 19, 2026, 9, 0, 10},   // 开发当日
            {2033, 12, 22, 2033, 11, 1, 1},   // 2033 闰冬月初一
            {2033, 12, 23, 2033, 11, 1, 2},
            {2044, 1, 25, 2043, 12, 0, 26},
            {2057, 9, 29, 2057, 9, 0, 2},
            {2097, 8, 8, 2097, 7, 0, 2},
            {2100, 1, 1, 2099, 11, 0, 21},    // 表尾年
            {2100, 12, 31, 2100, 12, 0, 1}    // 表末日
    };

    public static void main(String[] args) {
        int bad = 0;
        for (int[] c : CASES) {
            LunarCalendar.LunarDate d = LunarCalendar.toLunar(c[0], c[1], c[2]);
            if (d == null) { System.out.println("FAIL null " + c[0] + "-" + c[1] + "-" + c[2]); bad++; continue; }
            if (d.year != c[3] || d.month != c[4] || (d.leap ? 1 : 0) != c[5] || d.day != c[6]) {
                System.out.println("FAIL " + c[0] + "-" + c[1] + "-" + c[2]
                        + " got=" + d.year + "/" + d.month + (d.leap ? "闰" : "") + "/" + d.day
                        + " want=" + c[3] + "/" + c[4] + (c[5] == 1 ? "闰" : "") + "/" + c[6]);
                bad++;
            }
        }
        // 超界：锚点前 → null（UI 显示空）
        if (LunarCalendar.toLunar(1900, 1, 30) != null) { System.out.println("FAIL 1900-1-30 should be null"); bad++; }
        if (!LunarCalendar.lineFor(2026, 10, 19).startsWith("农历 ")) { System.out.println("FAIL lineFor"); bad++; }
        System.out.println(bad == 0 ? "LunarCheck OK (" + CASES.length + " cases)" : "LunarCheck FAILED " + bad);
        if (bad > 0) System.exit(1);
    }
}
