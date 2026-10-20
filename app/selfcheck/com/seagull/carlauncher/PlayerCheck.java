package com.seagull.carlauncher;

/**
 * S2 独立播放器组件自检（纯 JVM）。覆盖换色预设（越界回落）、预设一致性、
 * 进度槽底色、时长格式化向量，共 14 项。
 */
public final class PlayerCheck {
    private static int pass, fail;

    public static void main(String[] args) {
        presets();
        format();
        System.out.println("PlayerCheck: " + pass + " 通过, " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }

    private static void ok(boolean cond, String name) {
        if (cond) pass++; else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void presets() {
        ok(PlayerSkin.NAMES.length == 6 && PlayerSkin.COLORS.length == 6, "六预设等长");
        ok(PlayerSkin.color(0) == 0xF2FFFFFF, "白=默认色");
        ok(PlayerSkin.color(1) == 0xFF4ADE80, "绿色值");
        ok(PlayerSkin.color(5) == 0xFFFACC15, "黄色值");
        ok(PlayerSkin.color(-1) == 0xF2FFFFFF, "负下标回落白");
        ok(PlayerSkin.color(99) == 0xF2FFFFFF, "越界回落白");
        ok("白".equals(PlayerSkin.name(-3)), "名称越界回落白名");
        ok("黄".equals(PlayerSkin.name(5)), "名称取黄");
        ok(PlayerSkin.trackBg() == 0x33FFFFFF, "进度槽恒透明白");
        for (int i = 0; i < PlayerSkin.COLORS.length; i++) {
            if (PlayerSkin.color(i) != PlayerSkin.COLORS[i]) {
                ok(false, "预设循环一致性 " + i);
                return;
            }
        }
        ok(true, "预设循环一致性");
    }

    private static void format() {
        ok("0:00".equals(PlayerSkin.fmtMs(0)), "零=0:00");
        ok("0:00".equals(PlayerSkin.fmtMs(-5)), "负值当零");
        ok("1:05".equals(PlayerSkin.fmtMs(65000)), "65s=1:05");
        ok("9:59".equals(PlayerSkin.fmtMs(599999)), "599999ms=9:59");
        ok("59:59".equals(PlayerSkin.fmtMs(3599999)), "3599999ms=59:59");
        ok("1:00:00".equals(PlayerSkin.fmtMs(3600000)), "一小时进位");
        ok("1:01:01".equals(PlayerSkin.fmtMs(3661000)), "1:01:01 向量");
    }
}
