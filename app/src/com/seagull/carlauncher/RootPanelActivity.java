package com.seagull.carlauncher;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * root 功能面板 —— 每条操作都是真实执行（su -c），结果显示在下方日志里。
 * 没有 root 时全部显示为不可用并给出原因，不做假成功。
 */
public class RootPanelActivity extends BaseActivity {

    private TextView env, log;
    private final StringBuilder buf = new StringBuilder();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
        // 审计 L7：能力缓存重探（首次 su 被拒绝后给重试入口），顺带预热 hasRoot
        new Thread(() -> Caps.resetCapabilities(), "seagull-capreset").start();
        if (getIntent() != null && getIntent().getBooleanExtra("selftest", false)) SelfTest.run(this);
        if (getIntent() != null && getIntent().getBooleanExtra("l3test", false)) SelfTestL3.run(this);
    }

    @Override protected void onResume() {
        super.onResume();
        // envInfo 里有 2 次真实 su 落地（settings get + dumpsys focus，各带 3s 超时），
        // 主线程跑会卡帧；挪后台线程，回来只贴文本。
        env.setText("检测中…");
        final RootPanelActivity self = this;
        new Thread(() -> {
            String s;
            try { s = SysOps.envInfo(self); } catch (Throwable t) { s = "环境检测失败: " + t; }
            final String out = s;
            if (isFinishing() || isDestroyed()) return;
            runOnUiThread(() -> env.setText(out));
        }).start();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private View build() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Skin.c(R.color.ground));
        col.setPadding(dp(20), dp(16), dp(20), dp(24));

        TextView back = new TextView(this);
        back.setText("← 返回桌面");
        back.setTextColor(Skin.c(R.color.leaf));
        back.setTextSize(16);
        back.setOnClickListener(v -> finish());
        col.addView(back);

        TextView t = new TextView(this);
        t.setText("root 功能");
        t.setTextColor(Skin.c(R.color.text));
        t.setTextSize(22);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(8); tp.bottomMargin = dp(10);
        col.addView(t, tp);

        env = new TextView(this);
        env.setTextColor(Skin.c(R.color.text_dim));
        env.setTextSize(12);
        env.setTypeface(Typeface.MONOSPACE);
        env.setBackground(Skin.round(Skin.c(R.color.panel), 12f));
        env.setPadding(dp(12), dp(10), dp(12), dp(10));
        col.addView(env);

        // root 探测可能真起一次 su（进程内首调才落地，之后走缓存）；
        // 不在主线程赌这 3 秒，先画 UI，后台探完再补警示条。
        final TextView warn = new TextView(this);
        warn.setText("⚠ 未检测到 root 通道：以下操作不会真的执行。");
        warn.setTextColor(Skin.c(R.color.bad));
        warn.setTextSize(13);
        warn.setPadding(0, dp(10), 0, 0);
        warn.setVisibility(View.GONE);
        col.addView(warn);
        new Thread(() -> {
            boolean has = false;
            try { has = Caps.hasRoot(); } catch (Throwable ignore) {}
            final boolean root = has;
            if (isFinishing() || isDestroyed()) return;
            runOnUiThread(() -> warn.setVisibility(root ? View.GONE : View.VISIBLE));
        }).start();

        /* 亮度 —— 审计 H4：getBrightness 是 su fork，先画容器后台读回填 */
        col.addView(section("屏幕亮度（0-255）"));
        final LinearLayout brightSlot = new LinearLayout(this);
        brightSlot.setOrientation(LinearLayout.VERTICAL);
        col.addView(brightSlot);
        new Thread(() -> {
            int b = SysOps.getBrightness();
            final int v = b < 0 ? 128 : Math.max(1, Math.min(255, b));
            if (isFinishing() || isDestroyed()) return;
            runOnUiThread(() -> brightSlot.addView(slider(1, 255, v, val ->
                    op("亮度=" + val, () -> SysOps.setBrightness(val)))));
        }, "seagull-bright").start();

        /* 音量 */
        col.addView(section("媒体音量（0-15）"));
        col.addView(slider(0, 15, 7, v -> op("音量=" + v, () -> SysOps.setVolume(v))));

        /* 开关类 */
        col.addView(section("开关"));
        col.addView(btn("WiFi 开", () -> op("WiFi 开", () -> SysOps.setWifi(true))));
        col.addView(btn("WiFi 关", () -> op("WiFi 关", () -> SysOps.setWifi(false))));
        col.addView(btn("蓝牙 开", () -> op("蓝牙 开", () -> SysOps.setBluetooth(true))));
        col.addView(btn("蓝牙 关", () -> op("蓝牙 关", () -> SysOps.setBluetooth(false))));
        col.addView(btn("屏幕常亮（研发调试用）", () -> op("屏幕常亮（研发调试用）", () -> SysOps.keepScreenOn(true))));
        col.addView(btn("自动时间开", () -> op("自动时间开", () -> SysOps.setAutoTime(true))));

        /* 任务类 */
        col.addView(section("进程 / 任务"));
        col.addView(btn("清掉后台所有第三方应用（保留系统）", () -> op("force-stop 全部第三方", () -> {
            StringBuilder sb = new StringBuilder();
            for (HomeActivity.AppEntry e : HomeActivity.loadApps(this)) {
                SysOps.R r = SysOps.forceStop(e.pkg);
                sb.append(e.label).append(':').append(r.ok ? "ok" : "no").append(' ');
            }
            return new SysOps.R(true, sb.toString());
        })));
        col.addView(btn("清空本应用缓存数据（慎用）", () -> op("清空本应用缓存数据（慎用）", () -> SysOps.clearCache(getPackageName()))));

        /* 权限 / 安装 */
        col.addView(section("权限 / 安装"));
        col.addView(btn("给自己授予全部申请过的权限", () -> op("批量 pm grant", () -> {
            StringBuilder sb = new StringBuilder();
            String[] perms = {
                    "android.permission.SYSTEM_ALERT_WINDOW",
                    "android.permission.POST_NOTIFICATIONS",
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION",
                    "android.permission.READ_MEDIA_AUDIO",
                    "android.permission.BLUETOOTH_CONNECT",
            };
            for (String p : perms) {
                SysOps.R r = SysOps.grant(getPackageName(), p);
                sb.append(p.substring(p.lastIndexOf('.') + 1)).append(':')
                        .append(r.ok ? "ok" : "no").append(' ');
            }
            return new SysOps.R(true, sb.toString());
        })));

        /* LSPosed 注入自检（批次 S-DIAG）：直接读框架日志，回答三件事——
         * ① 框架在不在（/data/adb/lspd 与模块目录）；② 本模块有没有被注入
         * （SeagullLsp 的 handleLoadPackage 行）；③ 高德在不在注入名单。
         * 只读不动任何配置。 */
        col.addView(section("LSPosed 注入自检"));
        col.addView(btn("读取 LSPosed 日志（框架/模块/高德）", () -> op("LSPosed 注入自检", () -> {
            String cmd = "echo ==框架目录==; "
                    + "ls /data/adb/lspd 2>/dev/null | head -4; "
                    + "ls -d /data/adb/modules/*lsposed* /data/adb/ksu/modules/*lsposed* 2>/dev/null; "
                    + "echo ==本机地图进程==; "
                    + "pidof com.autonavi.minimap com.autonavi.amapauto 2>/dev/null; "
                    + "ps -A 2>/dev/null | grep -iE 'autonavi|carlinkit' | head -8; "
                    + "echo ==显示器列表==; "
                    + "dumpsys display 2>/dev/null | grep -m6 -E 'mDisplayId|Display\\{'; "
                    + "echo ==KSU模块清单==; "
                    + "ls /data/adb/modules/ 2>/dev/null; "
                    + "echo ==antitelemetry脚本内容==; "
                    + "cat /data/adb/modules/seagull_antitelemetry/post-fs-data.sh 2>/dev/null | head -40; "
                    + "echo ==日志文件==; "
                    + "ls /data/adb/lspd/log/ 2>/dev/null | head -8; "
                    + "echo ==本模块注入记录==; "
                    + "grep -ah SeagullLsp /data/adb/lspd/log/modules*.log 2>/dev/null | tail -15; "
                    + "grep -ah -iE 'seagull' /data/adb/lspd/log/verbose*.log 2>/dev/null | tail -10; "
                    + "echo ==高德注入记录==; "
                    + "grep -ah -E 'GaodeHook|AmapPipHook|handleLoadPackage: com.autonavi' /data/adb/lspd/log/modules*.log 2>/dev/null | tail -8; "
                    + "echo ==自检完==";
            String out = Caps.exec(cmd);
            if (out == null || out.trim().isEmpty()) {
                out = "（无输出：root 通道不通或日志路径不存在——把上面「框架目录」为空"
                        + "理解为 LSPosed 未装/未激活）";
            }
            return new SysOps.R(true, out);
        })));

        /* LSPosed 账本自检（批次 T）：日志只能证明"运行时注入过"，改不了配置。
         * 这里直接读框架自己的模块配置库——海鸥在不在模块表里、enabled 是不是
         * true、作用域有没有高德/SystemUI，全在这份配置里，是终审证据。 */
        col.addView(btn("读 LSPosed 模块配置（海鸥启用了吗/作用域里有谁）",
                () -> op("LSPosed 模块配置", () -> {
            String cmd = "echo ==配置目录==; "
                    + "ls -la /data/adb/lspd/config/ 2>/dev/null; "
                    + "echo ==modules_config.json==; "
                    + "cat /data/adb/lspd/config/modules_config.json 2>/dev/null | head -120; "
                    + "echo ==配置库里的海鸥与作用域==; "
                    + "for f in /data/adb/lspd/config/*.db; do "
                    + "[ -f \"$f\" ] && echo \"[db: $f]\" && strings \"$f\" | "
                    + "grep -aiE 'seagull|autonavi|systemui' | head -10; done; "
                    + "echo ==配置完==";
            String out = Caps.exec(cmd);
            if (out == null || out.trim().isEmpty()) {
                out = "（无输出：root 通道不通，或 /data/adb/lspd/config 不存在——"
                        + "框架可能未激活）";
            }
            return new SysOps.R(true, out);
        })));

        /* 一键拉起 LSPosed 管理器：海鸥是桌面，没有独立入口可进框架界面；
         * 车联当年多半是预配置好的，本模块的开关/作用域必须人来点。
         * 管理器无桌面图标（隐藏应用），root am start 可直拉。 */
        col.addView(btn("打开 LSPosed 管理器（点开关+勾作用域）", () -> op("LSPosed 管理器",
                () -> {
            String cmd = "am start -n org.lsposed.manager/"
                    + "org.lsposed.manager.ui.activity.MainActivity 2>&1 "
                    + "|| am start -n com.android.shell/.BogusActivity 2>/dev/null; "
                    + "pm list packages 2>/dev/null | grep -i lsposed";
            String out = Caps.exec(cmd);
            if (out == null || out.trim().isEmpty()) {
                out = "（拉起失败：管理器不在了。先确认 LSPosed 内核仍在运行）";
            }
            return new SysOps.R(true, out);
        })));

        /* 车联助手 root 能力对齐（批次 S-RESTORE）：leting 用 su 注入按键到指定
         * display（其 PIP 的返回键/触摸注入同款命令）。这里挂个可点测的等价项。 */
        col.addView(section("车联助手对齐"));
        col.addView(btn("向副屏(display 1)发返回键（root 注入）", () -> op("副屏返回键",
                () -> SysOps.inputKeyeventOnDisplay(1, 4))));

        /* 作用域应用三连（批次 T2）：LSPosed 只在进程 fork 时注入，勾完作用域后
         * 已经活着的高德进程永远等不到注入。这里一键杀干净两个高德，重开即触发
         * 新一轮 fork + 注入。杀完用户需手动重新打开高德。 */
        col.addView(btn("强杀高德（车机版+公版，杀完手动重开）", () -> op("强杀高德",
                () -> {
                    String cmd = "am force-stop com.autonavi.amapauto 2>&1; "
                            + "am force-stop com.autonavi.minimap 2>&1; "
                            + "echo 剩余高德进程:; "
                            + "pidof com.autonavi.amapauto com.autonavi.minimap 2>/dev/null || echo 无";
                    String out = Caps.exec(cmd);
                    return new SysOps.R(true, out == null ? "（无输出）" : out);
                })));

        /* 虚拟屏画中画（批次 U）：海鸥自建 VirtualDisplay 承载车机高德，
         * 悬浮窗呈现、自由拉伸、root input 转发触摸。零 hook、零 LSPosed 依赖，
         * 与注入链完全解耦。规格见 .monkeycode/specs/virtual-display-pip/。 */
        col.addView(section("虚拟屏画中画"));
        col.addView(btn("启动高德画中画（虚拟屏）", () -> op("虚拟屏画中画", () -> {
            try {
                startForegroundService(new android.content.Intent(this, PipOverlayService.class));
                return new SysOps.R(true, "服务已拉起：看悬浮窗顶栏状态行；"
                        + "SurfaceView 区手势直达高德，右下角手柄自由拉伸。");
            } catch (Throwable ex) {
                return new SysOps.R(false, "拉起失败: " + ex);
            }
        })));

        log = new TextView(this);
        log.setTextColor(Skin.c(R.color.warn));
        log.setTextSize(11);
        log.setTypeface(Typeface.MONOSPACE);
        log.setPadding(0, dp(16), 0, 0);
        col.addView(log);

        sv.addView(col);
        return sv;
    }

    private TextView section(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Skin.c(R.color.leaf));
        tv.setTextSize(14);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(20); p.bottomMargin = dp(8);
        tv.setLayoutParams(p);
        return tv;
    }

    interface Op { SysOps.R run(); }
    interface VoidOp { void run(); }

    private void doVoid(String label, VoidOp o) {
        op(label, () -> { o.run(); return new SysOps.R(true, "已下发"); });
    }

    private void op(String label, Op o) {
        new Thread(() -> {
            SysOps.R r;
            try { r = o.run(); } catch (Throwable t) { r = new SysOps.R(false, "异常: " + t); }
            final String line = label + " → " + r;
            runOnUiThread(() -> append(line));
        }).start();
    }

    private void append(String s) {
        buf.insert(0, s + "\n");
        if (buf.length() > 4000) buf.setLength(4000);
        if (log != null) log.setText(buf.toString());
    }

    private View btn(String label, VoidOp o) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Skin.c(R.color.text));
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(14), dp(13), dp(14), dp(13));
        tv.setBackground(Skin.round(Skin.c(R.color.card), 12f));
        tv.setOnClickListener(v -> o.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        tv.setLayoutParams(p);
        return tv;
    }

    private View slider(int min, int max, int cur, final IntCb cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Skin.round(Skin.c(R.color.card), 12f));
        box.setPadding(dp(14), dp(10), dp(14), dp(6));

        final TextView val = new TextView(this);
        val.setTextColor(Skin.c(R.color.text));
        val.setTextSize(14);
        val.setText("当前: " + cur);
        box.addView(val);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, cur - min)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                val.setText("当前: " + (p + min));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                cb.on(s.getProgress() + min);
            }
        });
        box.addView(sb);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        box.setLayoutParams(p);
        return box;
    }

    interface IntCb { void on(int v); }
}
