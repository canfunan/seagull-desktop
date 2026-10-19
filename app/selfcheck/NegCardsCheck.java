package com.seagull.carlauncher;

import java.util.Arrays;
import java.util.List;

/**
 * 自检：NegCards 卡片序/显隐纯逻辑（Q 批次）。
 * 覆盖：归一（空表/未知 key/缺失追加/去重）、move 边界、隐藏清洗、列分配、标签。
 */
public class NegCardsCheck {

    private static int n = 0;

    public static void main(String[] args) {
        List<String> def = NegCards.normalize(null);
        check(def.equals(Arrays.asList(NegCards.DEFAULTS)), "空表回落默认序");

        List<String> saved = Arrays.asList("log", "weather");
        List<String> nm = NegCards.normalize(saved);
        check(nm.size() == 5, "缺失卡补齐 " + nm.size());
        check(nm.get(0).equals("log") && nm.get(1).equals("weather")
                && nm.get(2).equals("switches") && nm.get(3).equals("restrict")
                && nm.get(4).equals("dest"), "已存序在前缺失追加序尾");

        List<String> dirty = Arrays.asList("weather", "hack", "weather", "log");
        List<String> nd = NegCards.normalize(dirty);
        check(nd.size() == 5 && !nd.contains("hack") && !nd.contains("hack2"),
                "未知 key 丢弃 + 去重");
        check(nd.get(0).equals("weather") && nd.get(1).equals("log"), "去重保序");

        List<String> mv = NegCards.move(def, "switches", 1);
        check(mv.get(1).equals("restrict") && mv.get(2).equals("switches"),
                "中间位下移交换");
        check(NegCards.move(def, "weather", -1).get(0).equals("weather"),
                "首位上移无操作");
        check(NegCards.move(def, "log", 1).equals(
                Arrays.asList(NegCards.DEFAULTS)), "末位下移无操作");
        check(NegCards.move(def, "nokey", 1).equals(
                Arrays.asList(NegCards.DEFAULTS)), "未知 key 移动无操作");

        List<String> fh = NegCards.filterHidden(
                Arrays.asList("log", "hack", "log", "dest"));
        check(fh.size() == 2 && fh.contains("log") && fh.contains("dest"),
                "隐藏表清洗去重");
        check(NegCards.filterHidden(null).isEmpty(), "空隐藏表");

        check(NegCards.colOf(0, 5) == 0 && NegCards.colOf(1, 5) == 1
                && NegCards.colOf(2, 5) == 0 && NegCards.colOf(3, 5) == 1
                && NegCards.colOf(4, 5) == 1, "奇偶发牌+末位余数进右列");
        check(NegCards.colOf(0, 4) == 0 && NegCards.colOf(1, 4) == 1
                && NegCards.colOf(2, 4) == 0 && NegCards.colOf(3, 4) == 1,
                "偶数卡纯交替");
        check(NegCards.colOf(0, 2) == 0 && NegCards.colOf(1, 2) == 1, "两卡两列");

        // 默认序分列还原 J1 原版：左列 weather/restrict，右列 switches/dest/log
        int a = 0, b = 0;
        for (int i = 0; i < def.size(); i++) {
            if (NegCards.colOf(i, def.size()) == 0) a++;
            else b++;
        }
        check(a == 2 && b == 3, "默认序两列 2/3 与原版一致");
        check(NegCards.colOf(0, def.size()) == 0 && def.get(0).equals("weather"),
                "天气在左列首位");
        check(NegCards.colOf(1, def.size()) == 1 && def.get(1).equals("switches"),
                "快捷开关在右列首位");

        check(!NegCards.labelOf("weather").isEmpty()
                && !NegCards.labelOf("switches").isEmpty()
                && !NegCards.labelOf("restrict").isEmpty()
                && !NegCards.labelOf("dest").isEmpty()
                && !NegCards.labelOf("log").isEmpty(), "标签全 key 非空");
        check(!NegCards.valid("hack") && NegCards.valid("log"), "valid 判定");
        check(!NegCards.valid(null), "null key 无效");

        System.out.println("NegCardsCheck: " + n + " 项全绿");
    }

    private static void check(boolean ok, String what) {
        n++;
        if (!ok) throw new AssertionError("第 " + n + " 项失败: " + what);
    }
}
