package com.seagull.carlauncher;

import java.util.ArrayList;
import java.util.List;

/**
 * Q 批次：负一屏卡片序/显隐纯逻辑（JVM 自检覆盖，无 Android 依赖）。
 * 序约定：DEFAULTS 顺序即默认展示序；偶数位进左列、奇数位进右列——
 * 默认序 weather/switches/restrict/dest/log 分列后恰好还原 J1 原版两列布局。
 */
public final class NegCards {
    public static final String[] DEFAULTS = {
            "weather", "switches", "restrict", "dest", "log"};

    private NegCards() { }

    /** 归一：去未知 key、去重保序、缺失默认卡追加序尾；saved 空表回落默认序。 */
    public static List<String> normalize(List<String> saved) {
        List<String> out = new ArrayList<>();
        if (saved != null) {
            for (String k : saved) {
                if (valid(k) && !out.contains(k)) out.add(k);
            }
        }
        for (String d : DEFAULTS) {
            if (!out.contains(d)) out.add(d);
        }
        return out;
    }

    /** 相邻交换式移动：delta=+1 下移 / -1 上移；越界（顶上移/底下移）原样返回。 */
    public static List<String> move(List<String> order, String key, int delta) {
        List<String> out = normalize(order);
        int at = out.indexOf(key);
        if (at < 0) return out;
        int to = at + delta;
        if (to < 0 || to >= out.size()) return out;
        out.remove(at);
        out.add(to, key);
        return out;
    }

    /** 隐藏表清洗：只留合法 key、去重。 */
    public static List<String> filterHidden(List<String> hidden) {
        List<String> out = new ArrayList<>();
        if (hidden == null) return out;
        for (String k : hidden) {
            if (valid(k) && !out.contains(k)) out.add(k);
        }
        return out;
    }

    /**
     * 列分配：交替发牌（A,B,A,B…），左列容量 ⌊n/2⌋，发完剩余全进右列——
     * 默认序 5 卡分列 = 左[天气,限行] 右[快捷开关,目的地,日志]，与 J1 原版一致。
     */
    public static int colOf(int index, int total) {
        if (index == total - 1 && total % 2 == 1) return 1;
        return index % 2;
    }

    public static boolean valid(String key) {
        if (key == null) return false;
        for (String d : DEFAULTS) {
            if (d.equals(key)) return true;
        }
        return false;
    }

    public static String labelOf(String key) {
        switch (key) {
            case "weather": return "天气";
            case "switches": return "快捷开关";
            case "restrict": return "限行";
            case "dest": return "目的地";
            case "log": return "日志";
            default: return "";
        }
    }
}
