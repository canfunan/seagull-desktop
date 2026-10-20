package com.seagull.carlauncher;

/**
 * R3 CARLINK 直连协议自检（纯 JVM）。覆盖新鲜窗、窗口覆盖、空行清显、
 * 翻译行槽位、开关/新鲜度门控、封面可用性，共 22 项。
 */
public final class CarLinkCheck {
    private static int pass, fail;

    public static void main(String[] args) {
        freshWindow();
        overrideLogic();
        coverGate();
        System.out.println("CarLinkCheck: " + pass + " 通过, " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }

    private static void ok(boolean cond, String name) {
        if (cond) pass++; else { fail++; System.out.println("  FAIL " + name); }
    }

    private static void freshWindow() {
        ok(!CarLink.fresh(0, 1000), "ts=0 视为从未收到");
        ok(CarLink.fresh(1000, 2000), "1s 前收到=新鲜");
        ok(CarLink.fresh(1000, 1000 + CarLink.FRESH_MS), "边界=新鲜");
        ok(!CarLink.fresh(1000, 1000 + CarLink.FRESH_MS + 1), "超窗 1ms=不新鲜");
        ok(CarLink.fresh(2000, 1000), "时钟倒退=刚收到视为新鲜");
    }

    private static void overrideLogic() {
        String[] fb = {"上上句", "上一句", "当前句"};
        // 开关关：原样回落
        ok(CarLink.override(fb, 3, "直连行", "译", false, true) == fb, "开关关回落原窗口");
        // 不新鲜：原样回落
        ok(CarLink.override(fb, 3, "直连行", "译", true, false) == fb, "不新鲜回落原窗口");
        // n<=0：原样回落
        ok(CarLink.override(fb, 0, "直连行", "译", true, true) == fb, "n=0 回落");
        // n=1：单槽=当前行
        String[] w1 = CarLink.override(fb, 1, "直连行", "译", true, true);
        ok(w1.length == 1 && "直连行".equals(w1[0]), "n=1 当前行占槽");
        // n=3：翻译占倒数第二、当前行占最后
        String[] w3 = CarLink.override(fb, 3, "直连行", "译", true, true);
        ok(w3.length == 3 && "".equals(w3[0]) && "译".equals(w3[1]) && "直连行".equals(w3[2]),
                "n=3 槽位布局");
        // 无翻译：只占最后一槽
        String[] w3b = CarLink.override(fb, 3, "直连行", "", true, true);
        ok("".equals(w3b[0]) && "".equals(w3b[1]) && "直连行".equals(w3b[2]), "无翻译其余空");
        // 翻译在 n=2：占第一槽
        String[] w2 = CarLink.override(fb, 2, "直连行", "译", true, true);
        ok("译".equals(w2[0]) && "直连行".equals(w2[1]), "n=2 翻译占首槽");
        // 空行=清显（间奏）
        String[] w3c = CarLink.override(fb, 3, "", "译", true, true);
        ok(w3c[0].isEmpty() && w3c[1].isEmpty() && w3c[2].isEmpty(), "空行全清");
        // null 行=清显
        String[] w3d = CarLink.override(fb, 3, null, null, true, true);
        ok(w3d[0].isEmpty() && w3d[1].isEmpty() && w3d[2].isEmpty(), "null 行全清");
        // 不污染 fallback（原数组未被改写）
        ok("当前句".equals(fb[2]) && "上上句".equals(fb[0]), "fallback 原数组不变");
        // 返回新数组：消费方改写不穿透
        String[] w3e = CarLink.override(fb, 3, "A", "", true, true);
        w3e[2] = "改";
        ok("A".equals(CarLink.override(fb, 3, "A", "", true, true)[2]), "每次新数组");
        // 长窗口 n=5：布局仍正确
        String[] w5 = CarLink.override(fb, 5, "行", "译", true, true);
        ok(w5.length == 5 && "译".equals(w5[3]) && "行".equals(w5[4])
                && w5[0].isEmpty() && w5[1].isEmpty() && w5[2].isEmpty(), "n=5 槽位布局");
        // 首尾空格行保留原样（播放器侧 trim，协议不越权）
        String[] w1b = CarLink.override(fb, 1, " 空格 ", "", true, true);
        ok(" 空格 ".equals(w1b[0]), "行文本原样透传");
        // 翻译存在但 n=1：翻译被丢弃
        ok("直连行".equals(CarLink.override(fb, 1, "直连行", "译", true, true)[0]), "n=1 丢弃翻译");
    }

    private static void coverGate() {
        ok(!CarLink.coverUsable(null, true), "null 封面不可用");
        ok(!CarLink.coverUsable("", true), "空封面不可用");
        ok(!CarLink.coverUsable("content://x", false), "断流封面不可用");
        ok(CarLink.coverUsable("content://x", true), "新鲜 URI 封面可用");
        ok(CarLink.coverUsable("/sdcard/c.jpg", true), "本地路径封面可用");
    }
}
