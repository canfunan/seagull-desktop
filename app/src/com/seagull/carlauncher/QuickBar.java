package com.seagull.carlauncher;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 快捷栏：组件条里的一条小 Dock（TODO P0-6）。
 *
 * 每格放一个应用或一个功能按钮（亮度±/音量±/Wi-Fi/蓝牙/飞行模式）。
 * 功能按钮的取名与执行都在这里，设置页的「功能按钮」列表复用同一张表，
 * 避免两处各写一份 7 项清单。
 */
public final class QuickBar {

    /** 功能按钮 key（不带 @fn: 前缀）。 */
    public static final String[] FN_KEYS = {
            "brightness_up", "brightness_down", "volume_up", "volume_down",
            "wifi", "bluetooth", "airplane"
    };
    public static final String[] FN_LABELS = {
            "亮度 +", "亮度 −", "音量 +", "音量 −", "Wi-Fi", "蓝牙", "飞行模式"
    };

    private QuickBar() {}

    public static String fnLabel(String key) {
        for (int i = 0; i < FN_KEYS.length; i++) if (FN_KEYS[i].equals(key)) return FN_LABELS[i];
        return key;
    }

    /**
     * 执行功能按钮。开关类需要 root 通道，失败时把原因 toast 出来。
     *
     * 审计 H3：每条 SysOps 调用都是 su fork（Caps.exec 3s 超时），亮度还是
     * 两次串行 —— 全部丢后台执行，toast 回主线程。连续点击天然排队（每点
     * 一个线程），Caps.exec 有超时 + 强杀兜底，不会堆积成死循环。
     */
    public static void runFn(Context ctx, String key) {
        final Context app = ctx.getApplicationContext();
        final String k = key;
        new Thread(() -> {
            SysOps.R r;
            int b;
            switch (k) {
                case "brightness_up":     b = SysOps.getBrightness(); r = SysOps.setBrightness(b < 0 ? 128 : b + 20); break;
                case "brightness_down":   b = SysOps.getBrightness(); r = SysOps.setBrightness(b < 0 ? 128 : b - 20); break;
                case "volume_up":         r = SysOps.setVolume(currentVolume(app) + 1); break;
                case "volume_down":       r = SysOps.setVolume(currentVolume(app) - 1); break;
                case "wifi":              r = SysOps.setWifi(!SysOps.isWifiOn(app)); break;
                case "bluetooth":         r = SysOps.setBluetooth(!SysOps.isBluetoothOn(app)); break;
                case "airplane":          r = SysOps.setAirplane(!SysOps.isAirplaneOn(app)); break;
                default: r = new SysOps.R(false, "未知功能 " + k);
            }
            if (!r.ok) {
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> Toast.makeText(app,
                        fnLabel(k) + " 需要 root：" + r.out, Toast.LENGTH_SHORT).show());
            }
        }, "seagull-quickfn").start();
    }

    private static int currentVolume(Context ctx) {
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                    ctx.getSystemService(Context.AUDIO_SERVICE);
            int v = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            return v;
        } catch (Throwable t) { return 7; }
    }

    /* ==================== 视图 ==================== */

    public interface Host {
        LauncherModel model();
        /** 长按某格：换内容或清空。 */
        void onEditSlot(int idx);
    }

    /**
     * 造一条快捷栏。空格子显示「+」，点击直接进设置页对应分区去挑。
     * 末尾固定两个「快捷开关」格（对应 model.quickBtn1 / quickBtn2）。
     */
    public static View build(Context ctx, final Host host) {
        LauncherModel m = host.model();
        if (m == null) {
            // 桌面 Activity 已销毁 / 正在销毁，host.model() 会返回 null。
            // 旧实现直接 m.quickbar.size()，瞬间 NPE 把 HomeActivity 拖崩。
            return new View(ctx);
        }
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);

        for (int i = 0; i < LauncherModel.QUICKBAR_MAX; i++) {
            final int idx = i;
            String key = slotKey(m, i);
            String label = slotLabel(m, i);
            bar.addView(cell(ctx, host, key, label, () -> host.onEditSlot(idx)),
                    new LinearLayout.LayoutParams(0, -1, 1f));
        }
        // 两个常驻快捷开关
        bar.addView(cell(ctx, host, norm(m.quickBtn1), labelOf(m.quickBtn1), null),
                new LinearLayout.LayoutParams(0, -1, 1f));
        bar.addView(cell(ctx, host, norm(m.quickBtn2), labelOf(m.quickBtn2), null),
                new LinearLayout.LayoutParams(0, -1, 1f));
        return bar;
    }

    private static String norm(String k) { return k == null ? "" : k; }

    public static String slotKey(LauncherModel m, int idx) {
        if (m == null || idx < 0 || idx >= m.quickbar.size()) return "";
        LauncherModel.QuickSlot s = m.quickbar.get(idx);
        return s == null || s.key == null ? "" : s.key;
    }

    public static String slotLabel(LauncherModel m, int idx) {
        if (m == null || idx < 0 || idx >= m.quickbar.size()) return "";
        LauncherModel.QuickSlot s = m.quickbar.get(idx);
        if (s == null) return "";
        if (s.key != null && !s.key.isEmpty() && !s.isFunc()) return s.label == null ? "" : s.label;
        return "";
    }

    /** 某一格显示什么：功能按钮显示功能名，应用显示应用名。 */
    private static String labelOf(String key) {
        if (key == null || key.isEmpty()) return "";
        return key.startsWith("@fn:") ? fnLabel(key.substring(4)) : "";
    }

    private static View cell(Context ctx, Host host, String key, String label, Runnable edit) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        boolean empty = key == null || key.isEmpty();

        if (empty) {
            TextView plus = new TextView(ctx);
            plus.setText("+");
            plus.setTextColor(Skin.c(R.color.text_dim));
            plus.setTextSize(16);
            plus.setGravity(Gravity.CENTER);
            box.addView(plus, new LinearLayout.LayoutParams(-1, 0, 1f));
            if (label != null && !label.isEmpty()) {
                TextView t = new TextView(ctx);
                t.setText(label);
                t.setTextColor(Skin.c(R.color.text_dim));
                t.setTextSize(11);
                t.setGravity(Gravity.CENTER);
                box.addView(t);
            }
            box.setOnClickListener(v -> {
                if (edit != null) edit.run();
            });
            return box;
        }

        if (key.startsWith("@fn:")) {
            TextView t = new TextView(ctx);
            t.setText(key.substring(4).isEmpty() ? "" : fnLabel(key.substring(4)));
            t.setTextColor(Skin.c(R.color.leaf));
            t.setTextSize(12);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(2);
            box.addView(t, new LinearLayout.LayoutParams(-1, 0, 1f));
        } else {
            LauncherModel m = host.model();
            LauncherModel.App a = m == null ? null : m.find(key);
            ImageView iv = new ImageView(ctx);
            Drawable d = a == null ? null : m.icon(a);
            if (d != null) iv.setImageDrawable(d);
            box.addView(iv, new LinearLayout.LayoutParams(-1, 0, 1f));
            if (a != null) {
                TextView t = new TextView(ctx);
                t.setText(a.label);
                t.setTextColor(Skin.c(R.color.text_dim));
                t.setTextSize(11);
                t.setMaxLines(1);
                box.addView(t);
            }
        }

        box.setOnClickListener(v -> tap(host, key));
        if (edit != null) box.setOnLongClickListener(v -> { edit.run(); return true; });
        return box;
    }

    private static void tap(Host host, String key) {
        LauncherModel m = host.model();
        if (key.startsWith("@fn:")) {
            // 功能按钮分支也要判空：桌面已销毁时 host.model() 是 null，
            // 旧实现直接 .context() 拿上下文弹 toast，NPE（批次 T 复盘坐实）
            if (m == null) return;
            runFn(m.context(), key.substring(4));
            return;
        }
        if (m != null) {
            LauncherModel.App a = m.find(key);
            if (a != null) m.launch(a, false);
        }
    }

    /** 组件条里的快捷栏只需显示内容摘要（悬空状态用）。 */
    public static String summary(LauncherModel m) {
        if (m == null) return "未设置";
        int n = 0;
        for (int i = 0; i < LauncherModel.QUICKBAR_MAX; i++) {
            if (!slotKey(m, i).isEmpty()) n++;
        }
        return n == 0 ? "未设置" : (n + " 格");
    }
}
