package com.seagull.carlauncher;

/**
 * 自检：PrivCodec 线协议编解码。
 * 协议层算错一个字符，对面守护进程就整条命令失效；这里是唯一不依赖设备就能钉住的地方。
 *
 * 跑法（不依赖设备，纯 JVM）：
 *   javac -d /tmp/privcsc app/src/com/seagull/carlauncher/PrivCodec.java app/selfcheck/PrivCodecCheck.java
 *   java  -ea -cp /tmp/privcsc com.seagull.carlauncher.PrivCodecCheck
 */
public class PrivCodecCheck {

    private static int n = 0;

    public static void main(String[] args) {
        // 1) 请求编码/解码往返
        String req = PrivCodec.encodeRequest(7, PrivCodec.OP_LAUNCH, "11",
                "com.android.settings/.Settings", "0x18000000");
        check(req.equals("7 LAUNCH 11 com.android.settings/.Settings 0x18000000\n"),
                "LAUNCH 编码: " + req.trim());
        PrivCodec.Request r = PrivCodec.decodeRequest(req);
        check(r != null && r.seq == 7 && r.op.equals("LAUNCH") && r.args.length == 3,
                "LAUNCH 解码");
        check(r.args[1].equals("com.android.settings/.Settings"), "arg1 组件名");
        check(r.args[2].equals("0x18000000"), "arg2 flags 原样");

        // 2) 应答往返（OK 带 payload / ERR）
        check(PrivCodec.decodeResponse("7 OK display=11\n").ok, "OK");
        check(PrivCodec.decodeResponse("7 OK display=11\n").payload.equals("display=11"),
                "OK payload");
        check(PrivCodec.encodeOk(7, "display=11").equals("7 OK display=11\n"), "OK 编码");
        check(!PrivCodec.decodeResponse("8 ERR SecurityException: boom\n").ok, "ERR 判定");
        check(PrivCodec.decodeResponse("8 ERR SecurityException: boom\n").payload
                .equals("SecurityException: boom"), "ERR payload");

        // 3) seq 必须接管（不要把 OK 当 seq）
        check(PrivCodec.decodeResponse("9 OK").seq == 9, "seq 解析");
        check(PrivCodec.decodeResponse("9 OK").payload.isEmpty(), "无 payload");
        check(PrivCodec.decodeResponse("junk") == null, "非 seq 行返回 null");
        check(PrivCodec.decodeResponse(null) == null, "null 行返回 null");
        check(PrivCodec.decodeRequest("") == null, "空行返回 null");
        check(PrivCodec.decodeRequest("no-seq OP") == null, "缺 seq 返回 null");

        // 4) MOTION：单指 DOWN
        float[] xs = {120f}, ys = {80f};
        String m = PrivCodec.encodeMotionRequest(10, 11, PrivCodec.ACTION_DOWN, 1000L, 1001L,
                xs, ys, 1);
        check(m.equals("10 MOTION 11 0 1000 1001 1 120 80\n"), "MOTION 单指编码: " + m.trim());
        PrivCodec.Request mr = PrivCodec.decodeRequest(m);
        check(mr.op.equals(PrivCodec.OP_MOTION) && mr.args.length == 7, "MOTION 解码");
        PrivCodec.MotionSpec ms = PrivCodec.parseMotion(mr.args);
        check(ms != null && ms.displayId == 11 && ms.action == PrivCodec.ACTION_DOWN
                && ms.count == 1 && ms.downTime == 1000L && ms.eventTime == 1001L
                && ms.xs[0] == 120f && ms.ys[0] == 80f, "MOTION 单指解析");

        // 5) MOTION：两指 MOVE（POINTER 事件的 action 高位带 index）
        float[] x2 = {50f, 60f}, y2 = {70f, 80.5f};
        String m2 = PrivCodec.encodeMotionRequest(11, 11, PrivCodec.ACTION_MOVE, 1000L, 1016L,
                x2, y2, 2);
        check(m2.equals("11 MOTION 11 2 1000 1016 2 50 70 60 80.5\n"),
                "MOTION 两指编码: " + m2.trim());
        PrivCodec.MotionSpec ms2 = PrivCodec.parseMotion(
                PrivCodec.decodeRequest(m2).args);
        check(ms2 != null && ms2.count == 2 && ms2.xs[1] == 60f && ms2.ys[1] == 80.5f,
                "MOTION 两指解析");

        // 6) action 人话（POINTER 带 index）
        int pd = PrivCodec.ACTION_POINTER_DOWN | (1 << 8);
        check(PrivCodec.describeAction(PrivCodec.ACTION_DOWN).equals("DOWN"), "DOWN 描述");
        check(PrivCodec.describeAction(pd).equals("POINTER_DOWN#1"), "POINTER_DOWN#1 描述");
        check(PrivCodec.describeAction(PrivCodec.ACTION_MOVE).equals("MOVE"), "MOVE 描述");
        check(PrivCodec.describeAction(PrivCodec.ACTION_CANCEL).equals("CANCEL"), "CANCEL 描述");

        // 7) 脏 motion 参数：丢事件不炸（含 displayId 段）
        check(PrivCodec.parseMotion(new String[]{"11", "0", "1", "2", "1"}) == null,
                "少坐标丢");
        check(PrivCodec.parseMotion(new String[]{"11", "0", "x", "2", "1", "1", "1"}) == null,
                "非数字丢");
        check(PrivCodec.parseMotion(new String[]{"11", "0", "1", "2", "0", "1", "1"}) == null,
                "count=0 丢");
        check(PrivCodec.parseMotion(new String[]{"11", "0", "1", "2", "99"}) == null,
                "count 越界丢");
        check(PrivCodec.parseMotion(new String[]{"11", "0", "1", "2", "1", "NaN", "1"}) == null,
                "NaN 坐标丢");

        // 8) 参数含空格必须拒绝（防注入式打乱协议）
        boolean threw = false;
        try {
            PrivCodec.encodeRequest(1, PrivCodec.OP_LAUNCH, "11", "evil arg");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "空格参数拒绝");

        // 9) 批次 AK~AM 新事务（OneStep 思路落地的守护进程扩展）往返
        String cr = PrivCodec.encodeRequest(12, PrivCodec.OP_CREATE, "800", "480", "160");
        check(cr.equals("12 CREATE 800 480 160\n"), "CREATE 编码: " + cr.trim());
        PrivCodec.Request crq = PrivCodec.decodeRequest(cr);
        check(crq != null && crq.op.equals(PrivCodec.OP_CREATE) && crq.args.length == 3,
                "CREATE 解码");
        check(PrivCodec.decodeResponse("12 OK created 5 trusted=1\n")
                .payload.equals("created 5 trusted=1"), "CREATE 应答");
        String ar = PrivCodec.encodeRequest(13, PrivCodec.OP_ARM, "5", "com.netease.cloudmusic");
        check(ar.equals("13 ARM 5 com.netease.cloudmusic\n"), "ARM 编码: " + ar.trim());
        check(PrivCodec.encodeRequest(14, PrivCodec.OP_ARM, "-1", "-")
                .equals("14 ARM -1 -\n"), "ARM 解除用 - 占位");
        String sr = PrivCodec.encodeRequest(15, PrivCodec.OP_RESIZE, "5", "800", "480", "160");
        PrivCodec.Request srq = PrivCodec.decodeRequest(sr);
        check(srq != null && srq.args.length == 4 && srq.args[0].equals("5"), "RESIZE 解码");
        // SURFACE 的 blob 是 Base64 NO_WRAP（无空白），行协议天然装得下
        String surf = "SEf////+AAAA";   // 任意无空白样例，只验协议承载不验内容
        check(PrivCodec.decodeRequest(
                PrivCodec.encodeRequest(16, PrivCodec.OP_SURFACE, "5", surf))
                .args[1].equals(surf), "SURFACE blob 原样往返");
        // EVT 行：守护进程推送（无 seq），前缀固定 "EVT <TYPE> [args]"
        check(PrivCodec.EVT.equals("EVT") && PrivCodec.EVT_BUMP.equals("BUMP")
                && PrivCodec.EVT_ROUTED.equals("ROUTED") && PrivCodec.EVT_WATCH.equals("WATCH"),
                "EVT 常量");
        check((PrivCodec.EVT + " " + PrivCodec.EVT_ROUTED + " com.x.y 5")
                .equals("EVT ROUTED com.x.y 5"), "EVT ROUTED 拼行");

        System.out.println("PrivCodecCheck OK (" + n + " 项)");
    }

    private static void check(boolean ok, String what) {
        n++;
        if (!ok) throw new AssertionError("失败：" + what);
    }
}
