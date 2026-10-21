package com.seagull.carlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模块注入诊断 —— 让「LSPosed 到底注没注入高德/网易云」变成桌面上看得见的状态。
 *
 * 背景（批次 W3 复盘的死结）：海鸥的 hook 与车联助手逐条等价，用户侧却始终
 * “像没生效一样”——拿不到 logcat 时，「模块没被 LSPosed 注入」和「注入了但
 * hook 没起效」永远无法区分，而前者（LSPosed 里模块未启用/作用域没勾/更新后
 * 没重启目标应用）恰是最常见的形态：车联助手一直配着作用域所以一直正常，
 * 海鸥一更新作用域链就断。
 *
 * 机制：hook 进程（高德/网易云等）第一次 Activity onCreate 时向本包发一条
 * 显式心跳广播（无任何权限要求，动态注册即可收）。桌面诊断行据此显示：
 *   ✅ 已注入高德 12:30:45 —— LSPosed 配置正确，hook 在跑
 *   ❌ 未收到注入心跳 —— 去 LSPosed：启用海鸥 → 勾选作用域 → 重启高德
 *
 * 自包含：只被 HomeActivity（注册）与 RootPanelActivity（显示）使用。
 */
public final class HookDiagnostics {

    public static final String ACTION = "com.seagull.LSP_ALIVE";
    public static final String EXTRA_PKG = "pkg";

    /** 注入包名 → 最近一次心跳的 wall clock（毫秒）。 */
    private static final Map<String, Long> BEATS = new ConcurrentHashMap<>();
    private static BroadcastReceiver receiver;

    private HookDiagnostics() {}

    /** 桌面注册（幂等）：收 hook 进程的心跳。 */
    public static synchronized void register(Context ctx) {
        if (receiver != null) return;
        final Context app = ctx.getApplicationContext();
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (!ACTION.equals(i.getAction())) return;
                String pkg = i.getStringExtra(EXTRA_PKG);
                if (pkg != null && !pkg.isEmpty()) BEATS.put(pkg, System.currentTimeMillis());
            }
        };
        IntentFilter f = new IntentFilter(ACTION);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                app.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, f);
            }
        } catch (Throwable t) {
            try {
                app.registerReceiver(receiver, f);   // 4 参被拒时退两参（targetSdk 33 合法）
            } catch (Throwable ignored) {
                receiver = null;
            }
        }
    }

    public static synchronized void unregister(Context ctx) {
        if (receiver == null) return;
        try {
            ctx.getApplicationContext().unregisterReceiver(receiver);
        } catch (Throwable ignore) {}
        receiver = null;
    }

    /** 最近心跳时间（可空）。 */
    public static Long lastBeat(String pkg) {
        Long t = BEATS.get(pkg);
        return t == null ? null : t;
    }

    /**
     * 诊断行（RootPanel 顶部）：一眼判断注入链断在哪一环。
     * 线程要求：后台线程调用（含 getPackageInfo 包查询）。
     */
    public static String describe(Context c) {
        StringBuilder sb = new StringBuilder("模块注入：");
        int alive = 0;
        alive += row(sb, "高德", "com.autonavi.minimap");
        alive += row(sb, "网易云", "com.netease.cloudmusic");
        if (alive == 0) {
            sb.append("❌ 未收到任何注入心跳 → LSPosed 里启用海鸥、勾选高德/网易云作用域、重启对应应用");
        }
        if (!lsposedInstalled(c)) {
            sb.append("\n⚠ 未检测到 LSPosed 管理器（org.lsposed.manager）——模块框架本体缺失");
        }
        return sb.toString();
    }

    /** 拼“名字 ✓hh:mm:ss / ✗”，返回 1=活着。 */
    private static int row(StringBuilder sb, String label, String pkg) {
        Long t = BEATS.get(pkg);
        sb.append(label);
        if (t == null) {
            sb.append(" ✗  ");
            return 0;
        }
        String hhmmss = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(t));
        sb.append(" ✓").append(hhmmss).append("  ");
        return 1;
    }

    private static boolean lsposedInstalled(Context c) {
        for (String p : new String[]{"org.lsposed.manager"}) {
            try {
                c.getPackageManager().getPackageInfo(p, 0);
                return true;
            } catch (Throwable ignore) {}
        }
        return false;
    }
}
