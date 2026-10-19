package com.seagull.carlauncher;

/**
 * 自检：CrashGuard 崩溃签名与恢复阈值（O4）。
 * 签名稳定性（同因同签）、24h 窗口推进、阈值置位、跨窗清零。
 *
 * 跑法（纯 JVM）：java -cp <批次 classpath> com.seagull.carlauncher.CrashSigCheck
 */
public class CrashSigCheck {

    private static int n = 0;
    private static final long H = 3600_000L;

    public static void main(String[] args) {
        // 1) 签名：同因同签，异因异签
        RuntimeException a1 = mk("boom-1", "tick");
        RuntimeException a2 = mk("boom-different-msg-same-site", "tick");
        RuntimeException b = mk("boom-2", "onResume");
        String s1 = CrashGuard.signature(a1);
        check(s1.contains("RuntimeException"), "签名含异常类名: " + s1);
        check(s1.equals(CrashGuard.signature(a2)), "同因同签");
        check(!s1.equals(CrashGuard.signature(b)), "异因异签");
        check("?".equals(CrashGuard.signature(null)), "null 签名兜底 ?");

        // 2) 计数推进：新签从 1 起
        long now = 1_700_000_000_000L;
        check(CrashGuard.nextCount(0, -1, now) == 1, "无历史起 1");
        check(CrashGuard.nextCount(3, -1, now) == 1, "换签清零重计");

        // 3) 窗口内累加
        check(CrashGuard.nextCount(1, now - 10_000, now) == 2, "窗内 1→2");
        check(CrashGuard.nextCount(2, now - 23 * H, now) == 3, "23h 后 2→3");

        // 4) 跨 24h 窗清零（即使同签）
        check(CrashGuard.nextCount(2, now - 25 * H, now) == 1, "跨 25h 清零重计");
        check(CrashGuard.nextCount(5, now - 24 * H - 1, now) == 1, "窗口边界外清零");
        check(CrashGuard.nextCount(2, now - 24 * H, now) == 3, "窗口边界恰 24h 算窗内");

        // 5) 阈值：3 次触发，2 次不触发
        check(!CrashGuard.shouldRecover(0) && !CrashGuard.shouldRecover(1)
                && !CrashGuard.shouldRecover(2), "低于 3 不触发");
        check(CrashGuard.shouldRecover(3) && CrashGuard.shouldRecover(4), "达 3 触发");

        // 6) 演练完整链：同签连 3 次 → 第 3 次该置 recover
        int c = 0;
        c = CrashGuard.nextCount(c, -1, now);
        boolean r1 = CrashGuard.shouldRecover(c);
        c = CrashGuard.nextCount(c, now, now + H);
        boolean r2 = CrashGuard.shouldRecover(c);
        c = CrashGuard.nextCount(c, now, now + 2 * H);
        boolean r3 = CrashGuard.shouldRecover(c);
        check(!r1 && !r2 && r3, "演练链 1→2→3 只在第 3 次触发");

        System.out.println("CrashSigCheck OK (" + n + " 项)");
    }

    private static RuntimeException mk(String msg, String method) {
        RuntimeException e = new RuntimeException(msg);
        StackTraceElement[] st = {
                new StackTraceElement("com.seagull.carlauncher.HomeActivity", method,
                        "HomeActivity.java", 42),
                new StackTraceElement("android.os.Handler", "run", "Handler.java", 1),
        };
        e.setStackTrace(st);
        return e;
    }

    private static void check(boolean ok, String what) {
        n++;
        if (!ok) throw new AssertionError("CrashSigCheck 失败: " + what);
    }
}
