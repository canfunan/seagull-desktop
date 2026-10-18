package com.seagull.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 设置分区页：按 EXTRA_SECTION 渲染 17 个分区之一（文案逐字对齐野菜桌面）。
 *
 * 职责：把 LauncherModel 已有的配置字段接上 UI 与持久化。
 * 「有则增强，无则隐藏」：需要 root / 特殊权限的操作只在能力可用时出现。
 * 各分区的完整功能落地节点：布局(已有) Dock(P0-5) 快捷栏(P0-6) 主题(P0-7) 菜园(P1-1)
 * 小白点(P1-2) 天气(P1-3) 歌词(P1-4) 任务引擎(P1-5) 野菜岛(P1-6) 触摸通道(P2-3)。
 */
public class SettingsSectionActivity extends BaseActivity {

    public static final String EXTRA_SECTION = "section";

    private static final String[] FN_KEYS = QuickBar.FN_KEYS;
    private static final String[] FN_LABELS = QuickBar.FN_LABELS;

    private String section;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        section = getIntent().getStringExtra(EXTRA_SECTION);
        if (section == null) section = "system";
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        model.loadApps();
        render();
    }

    private void render() {
        // 主题/强调色在这页改，改完立即重取色板再重建视图；
        // 旧实现 Skin 一直是 onCreate 时的旧色，用户点了主题看不到变化（批次 V 坐实）
        Skin.apply(model);
        setContentView(build());
    }

    /* ==================== 页面骨架 ==================== */

    private LinearLayout col;

    private View build() {
        ScrollView sv = new ScrollView(this);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Skin.c(R.color.ground));
        col.setPadding(dp(16), dp(12), dp(16), dp(24));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("← 返回设置");
        back.setTextColor(Skin.c(R.color.leaf));
        back.setTextSize(15);
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView t = new TextView(this);
        t.setText("  " + titleOf(section));
        t.setTextColor(Skin.c(R.color.text));
        t.setTextSize(17);
        head.addView(t);
        col.addView(head);

        buildBody();

        sv.addView(col);
        return sv;
    }

    private static String titleOf(String id) {
        switch (id) {
            case "dock": return "Dock 栏";
            case "quickbar": return "快捷栏";
            case "garden": return "菜园";
            case "ball": return "小白点";
            case "theme": return "主题与壁纸";
            case "screen": return "屏幕";
            case "island": return "野菜岛";
            case "weather": return "天气";
            case "lyrics": return "歌词";
            case "window": return "窗口";
            case "touch": return "触摸";
            case "tasks": return "自动化任务";
            case "bootanim": return "开机动画";
            case "system": return "系统";
            case "market": return "车机工具";
            case "about": return "关于";
            default: return "布局";
        }
    }

    private void buildBody() {
        switch (section) {
            case "dock": dockBody(); break;
            case "quickbar": quickbarBody(); break;
            case "garden": gardenBody(); break;
            case "ball": ballBody(); break;
            case "theme": themeBody(); break;
            case "screen": screenBody(); break;
            case "island": islandBody(); break;
            case "weather": weatherBody(); break;
            case "lyrics": lyricsBody(); break;
            case "window": windowBody(); break;
            case "touch": touchBody(); break;
            case "tasks": tasksBody(); break;
            case "bootanim": bootanimBody(); break;
            case "system": systemBody(); break;
            case "market": marketBody(); break;
            case "about": aboutBody(); break;
            default: layoutBody(); break;
        }
    }

    /* ==================== 各分区 ==================== */

    private void layoutBody() {
        note("布局在「布局与组件」页配置。");
        actionRow("打开布局与组件", "桌面分区模式 · 组件条 · 显示应用名称",
                v -> startActivity(new Intent(this, LayoutModeActivity.class)));
    }

    private void dockBody() {
        sectionLabel("位置");
        choiceRow("Dock 位置", model.dockPos.label, () -> {
            String[] names = {"底部", "左侧", "右侧", "不显示"};
            choiceDialog("Dock 位置", names, model.dockPos.ordinal(), i -> {
                model.dockPos = LauncherModel.DockPos.values()[i];
                model.save(); render();
            });
        });

        sectionLabel("大小与透明度");
        sliderRow("放几个", 1, LauncherModel.DOCK_MAX, model.dockCount, " 个",
                v -> { model.dockCount = v; });
        sliderRow("宽度（底部时=高度）", 40, 140, model.dockWidth, " dp", v -> { model.dockWidth = v; });
        sliderRow("图标大小", 28, 72, model.dockIconSize, " dp", v -> { model.dockIconSize = v; });
        sliderRow("间距", 0, 24, model.dockGap, " dp", v -> { model.dockGap = v; });
        sliderRow("透明度", 0, 100, model.dockAlpha, " %", v -> { model.dockAlpha = v; });
        note("往右拉，Dock 越透明，背后的壁纸露得越多。");

        sectionLabel("行为");
        toggleRow("自动收起", model.dockAutoHide, () -> { model.dockAutoHide = !model.dockAutoHide; });
        toggleRow("固定（侧栏满了也不收起）", model.dockFixed, () -> { model.dockFixed = !model.dockFixed; });
        toggleRow("Dock 上的时间", model.dockShowClock, () -> { model.dockShowClock = !model.dockShowClock; });
        note("数量改小时，多出的应用会保留，改回来还在。");

        sectionLabel("Dock 应用（点击移除）");
        if (model.dock.isEmpty()) {
            note("Dock 为空 — 长按应用图标可固定");
        } else {
            for (int i = 0; i < model.dock.size(); i++) {
                final String key = model.dock.get(i);
                LauncherModel.App a = model.find(key);
                String label = a != null ? a.label : key;
                actionRow("槽位 " + (i + 1) + "：" + label, "点击移除", v -> {
                    model.toggleDock(key);
                    render();
                });
            }
        }

        sectionLabel("每个应用默认开在哪个窗口");
        note("设成 1 号 / 2 号后，点 Dock 图标直接进对应小窗；没设的应用点图标全屏打开。");
        List<String> dockKeys = new ArrayList<>(model.dock);
        if (dockKeys.isEmpty()) dockKeys.addAll(defaultDockKeys(model));
        for (int i = 0; i < dockKeys.size(); i++) {
            final LauncherModel.App a = model.find(dockKeys.get(i));
            if (a == null) continue;
            int w = model.windowOf(a.pkg);
            choiceRow(a.label, w == 0 ? "全屏" : (w == 1 ? "1 号窗口" : "2 号窗口"), () -> {
                String[] names = {"全屏", "1 号窗口", "2 号窗口"};
                choiceDialog(a.label, names, w, k -> {
                    model.setDockWindow(a.pkg, k);
                    render();
                });
            });
        }
    }

    /** Dock 为空时设置页也要能配窗口：沿用桌面那套默认候选。 */
    private List<String> defaultDockKeys(LauncherModel m) {
        String[] prefer = {"com.android.dialer", "com.android.mms", "com.android.chrome",
                "com.android.camera", "com.android.settings"};
        List<String> out = new ArrayList<>();
        for (String p : prefer) {
            for (LauncherModel.App a : m.allApps) if (a.pkg.equals(p)) { out.add(a.key()); break; }
        }
        if (out.isEmpty()) for (int i = 0; i < Math.min(5, m.allApps.size()); i++) out.add(m.allApps.get(i).key());
        return out;
    }

    private void quickbarBody() {
        note("放进组件条：长按组件条（或桌面空白处）→「+ 快捷栏」。"
                + "每个桌面布局各有一份快捷栏：在「布局与组件」切布局后回来改这里，改的是那一份。");
        actionRow("当前布局", model.mode.label, v ->
                startActivity(new Intent(this, LayoutModeActivity.class)));

        sectionLabel("快捷栏格子");
        for (int i = 0; i < LauncherModel.QUICKBAR_MAX; i++) {
            final int idx = i;
            String cur;
            if (i < model.quickbar.size()) {
                LauncherModel.QuickSlot q = model.quickbar.get(i);
                cur = q.key == null || q.key.isEmpty() ? "空"
                        : (q.isFunc() ? fnLabel(q.key.substring(4)) : q.label);
            } else {
                cur = "空";
            }
            choiceRow("快捷栏第 " + (i + 1) + " 格", cur, () -> slotDialog(idx));
        }

        sectionLabel("快捷开关");
        fnChoiceRow("快捷开关 1", model.quickBtn1, v -> { model.quickBtn1 = v; });
        fnChoiceRow("快捷开关 2", model.quickBtn2, v -> { model.quickBtn2 = v; });
    }

    private void slotDialog(final int idx) {
        String[] opts = {"清空", "选择应用…", "功能按钮…"};
        choiceDialog("快捷栏第 " + (idx + 1) + " 格", opts, -1, i -> {
            if (i == 0) {
                model.clearQuickSlot(idx);
                render();
            } else if (i == 1) {
                appPicker("选应用", a -> {
                    model.setQuickSlot(idx, a.key(), a.label);
                    render();
                });
            } else {
                choiceDialog("功能按钮", FN_LABELS, -1, j -> {
                    model.setQuickSlot(idx, "@fn:" + FN_KEYS[j], FN_LABELS[j]);
                    render();
                });
            }
        });
    }

    private void fnChoiceRow(String label, String cur, Consumer<String> set) {
        String shown = cur == null || cur.isEmpty() ? "无" : fnLabel(cur.startsWith("@fn:") ? cur.substring(4) : cur);
        choiceRow(label, shown, () -> {
            String[] opts = new String[FN_LABELS.length + 1];
            opts[0] = "无";
            System.arraycopy(FN_LABELS, 0, opts, 1, FN_LABELS.length);
            int curIdx = -1;
            if (cur != null && cur.startsWith("@fn:")) {
                for (int i = 0; i < FN_KEYS.length; i++) if (FN_KEYS[i].equals(cur.substring(4))) curIdx = i + 1;
            }
            final int def = curIdx;
            choiceDialog(label, opts, def, i -> {
                set.accept(i == 0 ? "" : "@fn:" + FN_KEYS[i - 1]);
                model.save(); render();
            });
        });
    }

    private static String fnLabel(String key) {
        for (int i = 0; i < FN_KEYS.length; i++) if (FN_KEYS[i].equals(key)) return FN_LABELS[i];
        return key;
    }

    private void gardenBody() {
        toggleRow("长按叶子进菜园", model.gardenEnabled, () -> { model.gardenEnabled = !model.gardenEnabled; });
        sliderRow("壁纸压暗", 0, 100, model.gardenDim, " %", v -> { model.gardenDim = v; });
        choiceRow("摆法", model.gardenStyle.label, () -> {
            LauncherModel.GardenStyle[] vals = LauncherModel.GardenStyle.values();
            String[] names = new String[vals.length];
            for (int i = 0; i < vals.length; i++) names[i] = vals[i].label;
            choiceDialog("摆法", names, model.gardenStyle.ordinal(), i -> {
                model.gardenStyle = vals[i];
                model.save(); render();
            });
        });
        actionRow("进一次菜园看看", "看看现在的样子", v ->
                startActivity(new Intent(this, GardenActivity.class)));
        note("点上面「进一次菜园看看」进菜园，菜园里再长按一次出来。"
                + "桌面右下角的小白点（野菜键）也能进菜园。菜园的压暗跟外观里的背景遮罩是两份，互不影响。");
    }

    private void ballBody() {
        toggleRow("显示小白点", model.ballEnabled, () -> {
            model.ballEnabled = !model.ballEnabled;
            model.save();
            if (model.ballEnabled && !Caps.canOverlay(this)) {
                toast("还没给悬浮窗权限，先点下面那一行");
            } else {
                BallService.setEnabled(this, model.ballEnabled);
            }
            render();
        });
        actionRow("悬浮窗权限", Caps.canOverlay(this) ? "已授权" : "未授权（点这里去开）", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignore) {}
        });
        sliderRow("大小", 32, 96, model.ballSize, " dp", v -> { model.ballSize = v; });
        sliderRow("透明度", 20, 100, model.ballAlpha, " %", v -> { model.ballAlpha = v; });
        actionRow("回到默认位置", model.ballX < 0 ? "已在左下角" : "重新贴边", v -> {
            model.ballX = -1; model.ballY = -1;
            model.save();
            BallService.setEnabled(this, false);
            if (model.ballEnabled) BallService.setEnabled(this, true);
            render();
        });
        note("点一下回桌面，长按进菜园，直接拖能挪位置，松手自动贴边。"
                + "Dock 关着时，点一下改成出全部应用。");
        note("菜园里那片叶子跟悬浮球是同一个野菜键，位置大小共用。");
    }

    private void themeBody() {
        sectionLabel("主题色");
        for (int i = 0; i < Theme.IDS.length; i++) {
            final int idx = i;
            themeRow(Theme.NAMES[i], Theme.ACCENTS[i], Theme.IDS[i].equals(model.themeId), () -> {
                model.themeId = Theme.IDS[idx];
                model.customAccent = "";
                model.save(); render();
            });
        }
        actionRow("自定义主题色", model.customAccent.isEmpty() ? "十六进制，如 #8CC26A" : model.customAccent, v ->
                inputDialog("自定义主题色", model.customAccent.isEmpty() ? "#" : model.customAccent, hex -> {
                    String s = hex.trim().toUpperCase(Locale.ROOT);
                    if (s.length() == 7 && s.startsWith("#")) {
                        try {
                            Long.parseLong(s.substring(1), 16);
                            model.customAccent = s;
                            model.themeId = "custom";
                            model.save(); render();
                            return;
                        } catch (Throwable ignore) {}
                    }
                    toast("格式不对，要 #RRGGBB");
                }));

        sectionLabel("日夜与文字");
        choiceRow("日夜模式", model.dayNight.label, () -> {
            LauncherModel.DayNight[] vals = LauncherModel.DayNight.values();
            String[] names = new String[vals.length];
            for (int i = 0; i < vals.length; i++) names[i] = vals[i].label;
            choiceDialog("日夜模式", names, model.dayNight.ordinal(), i -> {
                model.dayNight = vals[i];
                model.save(); render();
            });
        });
        note("跟随系统 = 按本机时间切：06:00~19:00 浅色，其余深色。");
        sliderRow("全局字号", 50, 150, model.fontScale, " %", v -> { model.fontScale = v; });
        sliderRow("背景遮罩", 0, 100, model.wallDim, " %", v -> { model.wallDim = v; });
        note("壁纸太亮就拉遮罩，文字颜色会跟着壁纸明暗自动翻色。");

        sectionLabel("壁纸");
        actionRow("白天壁纸", model.wallDay.isEmpty() ? "未设置" : model.wallDay, v -> pickWall(true));
        actionRow("夜间壁纸", model.wallNight.isEmpty() ? "未设置" : model.wallNight, v -> pickWall(false));
        actionRow("清空壁纸", "回到纯色底", v -> {
            model.wallDay = ""; model.wallNight = "";
            for (LauncherModel.Wall w : new ArrayList<>(model.wallLib)) model.removeWall(w);
            model.save(); render();
        });
        sectionLabel("壁纸库（" + model.wallLib.size() + " / " + LauncherModel.WALL_LIB_MAX + "）");
        if (model.wallLib.isEmpty()) {
            note("还没有壁纸。点「白天壁纸」从相册选一张。");
        } else {
            for (LauncherModel.Wall w : new ArrayList<>(model.wallLib)) {
                wallRow(w);
            }
        }
        note("视频壁纸与毛玻璃（RenderEffect）依赖 API 29+ 特性与额外解码开销，"
                + "公开版先只支持静态图；等有实测收益再加。");
    }

    private static final int REQ_WALL = 0x5A11;

    private void pickWall(final boolean day) {
        try {
            startActivityForResult(Wallpaper.chooser(), REQ_WALL);
            wallPickIsDay = day;
        } catch (Throwable t) {
            toast("这台设备没有相册选择器");
        }
    }

    private boolean wallPickIsDay = true;

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_WALL || data == null || data.getData() == null) return;
        LauncherModel.Wall w = Wallpaper.onPicked(model, "", data.getData());
        if (w == null) { render(); return; }
        if (wallPickIsDay) model.useWall(w, false);
        else model.useWall(w, true);
        render();
    }

    private void wallRow(final LauncherModel.Wall w) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setBackground(Skin.round(Skin.c(R.color.card), 12f));

        TextView tv = new TextView(this);
        tv.setText(w.name + (w.used ? "  · 正在用" : ""));
        tv.setTextColor(Skin.c(R.color.text));
        tv.setTextSize(13);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView day = miniBtn("白天", v -> { model.useWall(w, false); render(); });
        row.addView(day);
        TextView night = miniBtn("夜间", v -> { model.useWall(w, true); render(); });
        row.addView(night);
        TextView del = miniBtn("删除", v -> {
            model.removeWall(w);
            render();
        });
        row.addView(del);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(6);
        row.setLayoutParams(p);
        col.addView(row);
    }

    private TextView miniBtn(String label, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Skin.c(R.color.leaf));
        tv.setTextSize(12);
        tv.setPadding(dp(8), dp(4), dp(8), dp(4));
        tv.setOnClickListener(l);
        return tv;
    }

    private void themeRow(String name, int accent, boolean on, Runnable pick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setBackground(Skin.round(on ? Skin.c(R.color.card) : Skin.c(R.color.panel), 12f));

        View sw = new View(this);
        sw.setBackground(Skin.round(accent, 6f));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(20), dp(20));
        row.addView(sw, sp);

        TextView tv = new TextView(this);
        tv.setText((on ? "  ● " : "  ○ ") + name);
        tv.setTextColor(on ? Skin.c(R.color.leaf) : Skin.c(R.color.text));
        tv.setTextSize(14);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.leftMargin = dp(6);
        row.addView(tv, tp);

        row.setOnClickListener(v -> pick.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(6);
        row.setLayoutParams(p);
        col.addView(row);
    }

    private void screenBody() {
        sectionLabel("横屏");
        sliderRow("屏幕外边距（左右）", 0, 48, model.marginH, " dp", v -> { model.marginH = v; });
        sliderRow("屏幕外边距（上下）", 0, 48, model.marginV, " dp", v -> { model.marginV = v; });
        sliderRow("窗口之间的缝", 0, 24, model.gap, " dp", v -> { model.gap = v; });

        sectionLabel("竖屏（单独一份）");
        sliderRow("屏幕外边距（左右）", 0, 48, model.portMarginH, " dp", v -> { model.portMarginH = v; });
        sliderRow("屏幕外边距（上下）", 0, 48, model.portMarginV, " dp", v -> { model.portMarginV = v; });
        sliderRow("窗口之间的缝", 0, 24, model.portGap, " dp", v -> { model.portGap = v; });
        actionRow("竖屏复制横屏的值", "把上面横屏的三项搬过来", v -> {
            model.portMarginH = model.marginH;
            model.portMarginV = model.marginV;
            model.portGap = model.gap;
            model.save(); render();
        });

        sectionLabel("方向与显示");
        choiceRow("屏幕方向", model.orientation.label, () -> {
            LauncherModel.Orientation[] vals = LauncherModel.Orientation.values();
            String[] names = new String[vals.length];
            for (int i = 0; i < vals.length; i++) names[i] = vals[i].label;
            choiceDialog("屏幕方向", names, model.orientation.ordinal(), i -> {
                model.orientation = vals[i];
                model.save(); render();
            });
        });
        toggleRow("保持亮屏", model.keepScreenOn, () -> {
            model.keepScreenOn = !model.keepScreenOn;
            applyKeepScreenOn();
        });
        toggleRow("顶部信息栏（日期 / Wi-Fi / 电量）", model.topInfoBar, () -> {
            model.topInfoBar = !model.topInfoBar;
            model.save(); render();
        });
        note("顶部信息栏关掉后，时间自动挪到 Dock 上。");
        toggleRow("自动同步网络时间", model.autoNetworkTime, () -> {
            model.autoNetworkTime = !model.autoNetworkTime;
            model.save();
            SysOps.R r = SysOps.setAutoTime(model.autoNetworkTime);
            if (!r.ok) toast("需要 root 通道：" + r.out);
        });
        note("隐藏系统栏需要平台签名，公开版不可达，对应入口不显示。");
    }

    private void applyKeepScreenOn() {
        model.save();
        if (model.keepScreenOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        render();
    }

    private void islandBody() {
        toggleRow("显示野菜岛", model.islandEnabled, () -> { model.islandEnabled = !model.islandEnabled; });
        sliderRow("距顶部偏移（挖孔 / 圆角）", 0, 80, model.islandOffsetY, " dp", v -> { model.islandOffsetY = v; });
        sliderRow("宽度", 200, 480, model.islandWidth, " dp", v -> { model.islandWidth = v; });
        toggleRow("野菜岛显示歌词", model.islandLyric, () -> { model.islandLyric = !model.islandLyric; });
        note("歌词长时省略；状态栏隐藏时浮在窗口上。浮层在后续版本接入。");
    }

    private void weatherBody() {
        choiceRow("城市来源", model.citySource.label, () -> {
            LauncherModel.CitySource[] vals = LauncherModel.CitySource.values();
            String[] names = new String[vals.length];
            for (int i = 0; i < vals.length; i++) names[i] = vals[i].label;
            choiceDialog("城市来源", names, model.citySource.ordinal(), i -> {
                model.citySource = vals[i];
                model.save();
                fetchWeather();
                render();
            });
        });
        actionRow("当前城市", model.weatherCity.isEmpty() ? "未设置（例：杭州）" : model.weatherCity, v ->
                inputDialog("城市名", model.weatherCity, city -> {
                    model.setCity(city);
                    fetchWeather();
                    render();
                }));
        toggleRow("每 20 分钟自动更新", model.weatherAuto, () -> { model.weatherAuto = !model.weatherAuto; });
        actionRow("点按获取天气", Weather.summary(model), v -> fetchWeather());
        if (model.citySource == LauncherModel.CitySource.AUTO_GPS) {
            actionRow("定位权限", locGranted() ? "已授权" : "未授权（点这里去开）", v -> grantLocation());
        }
        if (!model.weatherForecast.isEmpty()) {
            sectionLabel("未来 3 天");
            note(model.weatherForecast);
        }
        actionRow("更新于", Weather.updatedLabel(model.weatherUpdatedAt), null);
        if (!model.weatherError.isEmpty()) note("上次没取到：" + model.weatherError);
        note("数据源 Open-Meteo 公开接口（免 key）。天气组件在组件条里，点一下就能手动取一次。");
    }

    private void fetchWeather() {
        Weather.refresh(this, model, () -> runOnUiThread(this::render));
    }

    private boolean locGranted() {
        try {
            return checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** root 机直接 pm grant，普通机走系统弹窗。 */
    private void grantLocation() {
        String perm = android.Manifest.permission.ACCESS_COARSE_LOCATION;
        if (Caps.hasRoot()) {
            Caps.exec("pm grant " + getPackageName() + " " + perm);
            runOnUiThread(this::render);
            return;
        }
        try {
            requestPermissions(new String[]{perm}, 9001);
        } catch (Throwable t) {
            toast("这台机器不给定位权限，按网络判断城市也一样能用");
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 9001) render();
    }

    private void lyricsBody() {
        choiceRow("歌词来源", model.lyricSource.label, () -> {
            LauncherModel.LyricSource[] vals = LauncherModel.LyricSource.values();
            String[] names = new String[vals.length];
            for (int i = 0; i < vals.length; i++) names[i] = vals[i].label;
            choiceDialog("歌词来源", names, model.lyricSource.ordinal(), i -> {
                model.lyricSource = vals[i];
                model.save(); render();
            });
        });
        choiceRow("歌词行数", model.lyricLines == 0 ? "自动（横屏 5 / 竖屏 3）" : model.lyricLines + " 行", () -> {
            String[] names = {"自动（横屏 5 / 竖屏 3）", "1 行", "2 行", "3 行", "4 行", "5 行"};
            choiceDialog("歌词行数", names, model.lyricLines, i -> {
                model.lyricLines = i;
                model.save(); render();
            });
        });
        sliderRow("歌词字号", 50, 200, model.lyricSize, " %", v -> { model.lyricSize = v; });
        choiceRow("对时偏移", offsetLabel(model.lyricOffsetMs), () -> {
            String[] names = {"-2 秒", "-1.5 秒", "-1 秒", "-0.5 秒", "0", "+0.5 秒", "+1 秒", "+1.5 秒", "+2 秒"};
            int cur = 4;
            for (int i = 0; i < names.length; i++) {
                int v = -2000 + i * 500;
                if (v == model.lyricOffsetMs) { cur = i; break; }
            }
            final int def = cur;
            choiceDialog("对时偏移", names, def, i -> {
                model.lyricOffsetMs = -2000 + i * 500;
                model.save(); render();
            });
        });
        sliderRow("媒体卡片行数", 1, 6, model.mediaCardLines, " 行", v -> { model.mediaCardLines = v; });
        toggleRow("状态栏歌词", model.lyricStatusBar, () -> { model.lyricStatusBar = !model.lyricStatusBar; });
        toggleRow("通知文字歌词", model.lyricNotification, () -> { model.lyricNotification = !model.lyricNotification; });
        toggleRow("车载蓝牙歌词（媒体信息）", model.lyricBluetooth, () -> { model.lyricBluetooth = !model.lyricBluetooth; });
        actionRow("这首歌词不对（换版本）", "在搜到的版本里轮换", v -> {
            Lyrics.nextVersion(this);
            toast("换了一版");
        });
        String ck = Lyrics.currentKey();
        toggleRow("当前这首不显示歌词", ck.isEmpty() ? false : model.lyricHidden.contains(ck), () -> {
            if (ck.isEmpty()) { toast("现在没有歌在放"); return; }
            if (model.lyricHidden.contains(ck)) model.lyricHidden.remove(ck);
            else model.lyricHidden.add(ck);
            model.save(); render();
        });
        if (ck.isEmpty()) note("10.9 这首不显示歌词、10.10 换版本都要先有歌在放。");
        actionRow("清空歌词缓存", "缓存的 lrc 文件", v -> toast("清了 " + Lyrics.clearCache(this) + " 个"));
        actionRow("通知使用权", notiListenerOn() ? "已授权" : "未授权（点这里去开）", v -> {
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
            catch (Throwable ignore) {}
        });
        note("车载蓝牙歌词走媒体信息，不需通知权限。歌词组件在组件条里，菜园里也会显示同一份。");
    }

    private String splitLabel() {
        if (model.pipWeightA == 1 && model.pipWeightB == 1) return "1:1 等分";
        if (model.pipWeightA == 3 && model.pipWeightB == 2) return "3:2 左大";
        if (model.pipWeightA == 2 && model.pipWeightB == 3) return "2:3 右大";
        return model.pipWeightA + ":" + model.pipWeightB;
    }

    private String offsetLabel(int ms) {
        if (ms == 0) return "0";
        return (ms > 0 ? "+" : "") + (ms / 1000f) + " 秒";
    }

    private boolean notiListenerOn() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            return s != null && s.contains(getPackageName());
        } catch (Throwable t) { return false; }
    }

    private void windowBody() {
        choiceRow("性能档位", model.perf.label, () -> {
            LauncherModel.Perf[] vals = LauncherModel.Perf.values();
            String[] names = new String[vals.length];
            for (int i = 0; i < vals.length; i++) names[i] = vals[i].label;
            choiceDialog("性能档位", names, model.perf.ordinal(), i -> {
                model.perf = vals[i];
                model.save(); render();
            });
        });
        toggleRow("兼容模式（强制应用可调整大小）", model.compatMode, () -> { model.compatMode = !model.compatMode; });
        sliderRow("后台保留几个窗口", 0, 3, model.bgKeep, " 个", v -> { model.bgKeep = v; });
        toggleRow("锁屏后释放画中画", model.releaseOnLock, () -> { model.releaseOnLock = !model.releaseOnLock; });
        toggleRow("息屏暂停", model.pauseOnScreenOff, () -> { model.pauseOnScreenOff = !model.pauseOnScreenOff; });
        choiceRow("画中画分割", splitLabel(), () -> {
            String[] names = {"1:1 等分", "3:2 左大", "2:3 右大"};
            int[] wa = {1, 3, 2}, wb = {1, 2, 3};
            int def = 0;
            for (int i = 0; i < wa.length; i++) {
                if (model.pipWeightA == wa[i] && model.pipWeightB == wb[i]) def = i;
            }
            choiceDialog("画中画分割", names, def, i -> {
                model.pipWeightA = wa[i];
                model.pipWeightB = wb[i];
                model.save(); render();
            });
        });
        note("默认显示大小与每应用独立缩放需要虚拟屏（L3），普通应用被系统拒绝，公开版不提供。");

        sectionLabel("窗口搬运（需 root）");
        actionRow("收回窗口", "把虚拟屏上的窗口搬回主屏", v -> rootOnly(
                () -> msgDialog("收回窗口", TaskMover.reclaim(this, MirrorSlot.newestDisplay()))));
        actionRow("从边缘小标签拉回", "把主屏上的窗口拉进虚拟屏", v -> rootOnly(
                () -> msgDialog("拉回窗口", TaskMover.pullBack(this, MirrorSlot.newestDisplay()))));
        actionRow("搬运排障", "dumpsys 里的 displayId / taskId", v -> rootOnly(
                () -> msgDialog("搬运排障", TaskMover.dump(this))));

        sectionLabel("入口");
        actionRow("窗口能力自检页", "能力探测与逐项验证",
                v -> startActivity(new Intent(this, WindowTestActivity.class)));
        actionRow(getString(R.string.mirror_title), getString(R.string.mirror_sub),
                v -> startActivity(new Intent(this, MirrorActivity.class)));
    }

    private void touchBody() {
        sliderRow("防误滑阈值", 0, 50, model.touchThreshold, " px", v -> { model.touchThreshold = v; });
        choiceRow("长按判定", (model.longPressMs / 1000f) + " 秒", () -> {
            String[] names = {"0.3 秒", "0.5 秒"};
            int def = model.longPressMs == 300 ? 0 : 1;
            choiceDialog("长按判定", names, def, i -> {
                model.longPressMs = i == 0 ? 300 : 500;
                model.save(); render();
            });
        });
        toggleRow("无障碍通道（公开版主通道）", model.useAccessibility, () -> { model.useAccessibility = !model.useAccessibility; });
        toggleRow("root 注入通道", model.useRootInput, () -> { model.useRootInput = !model.useRootInput; });
        toggleRow("跟手（滑动边走边发）", model.touchFollow, () -> { model.touchFollow = !model.touchFollow; });
        sectionLabel("12.2 触摸方式");
        choiceRow("注入方式", model.useRootInput ? "root input 命令" : "无障碍通道", () -> {
            String[] names = {"root input 命令", "无障碍通道"};
            int def = model.useRootInput ? 0 : 1;
            choiceDialog("注入方式", names, def, i -> {
                model.useRootInput = i == 0;
                model.useAccessibility = i == 1;
                model.save(); render();
            });
        });
        actionRow("注入排障", "最近几条命令与返回", v -> msgDialog("注入排障", touchLog()));
        actionRow("能力说明", "为什么没反应", v -> msgDialog("触摸通道", TouchForward.degradeNote(this)));
        note("跟手开着时滑动会拆成多条命令（每条 ≥16ms），关掉就是抬起时一次发完。");
    }

    private void tasksBody() {
        actionRow("新建任务", model.tasks.size() + " / " + LauncherModel.TASK_MAX + " 条",
                v -> taskCreateFlow());
        sectionLabel("任务列表（点击管理）");
        if (model.tasks.isEmpty()) {
            note("还没有任务。");
        } else {
            for (int i = 0; i < model.tasks.size(); i++) {
                final LauncherModel.Task t = model.tasks.get(i);
                actionRow((t.enabled ? "● " : "○ ") + t.name, t.describe(), v -> taskManage(t));
            }
        }
        note("任务随桌面配置一起导出。桌面启动 / 系统启动在打开桌面时执行，定时任务每分钟对一次表。");
        if (Caps.hasRoot()) actionRow("开机动画 · 生成一次", "本地素材合成（待开发）", null);
    }

    private void taskCreateFlow() {
        if (model.tasks.size() >= LauncherModel.TASK_MAX) {
            toast("最多 " + LauncherModel.TASK_MAX + " 条");
            return;
        }
        inputDialog("任务名称", "", name -> {
            if (name.trim().isEmpty()) { toast("名字不能为空"); return; }
            LauncherModel.Task t = new LauncherModel.Task(name.trim());
            LauncherModel.TaskTrigger[] trs = LauncherModel.TaskTrigger.values();
            String[] trNames = new String[trs.length];
            for (int i = 0; i < trs.length; i++) trNames[i] = trs[i].label;
            choiceDialog("触发条件", trNames, t.trigger.ordinal(), ti -> {
                t.trigger = trs[ti];
                if (t.trigger == LauncherModel.TaskTrigger.TIMER) { askAtMinThen(t, null); return; }
                askActionThenAdd(t);
            });
        });
    }

    /** 13.4 定时触发要问「每天几点」，问完再走原来的动作流程。 */
    private void askAtMinThen(final LauncherModel.Task t, final Runnable next) {
        inputDialog("每天几点几分（例：730 表示 07:30）", "-1", s -> {
            try {
                int v = Integer.parseInt(s.trim());
                if (v < 0) { t.atMin = -1; }
                else {
                    // 按提示词口径解析成 HHMM：730 → 07:30
                    // （旧实现当成"第 730 分钟"= 12:10，和提示词自相矛盾）
                    int h = v / 100, mnt = v % 100;
                    t.atMin = (h <= 23 && mnt <= 59) ? h * 60 + mnt : -1;
                }
            } catch (Throwable e) {
                t.atMin = -1;
            }
            if (next == null) askActionThenAdd(t);
            else next.run();
        });
    }

    private void askActionThenAdd(final LauncherModel.Task t) {
        LauncherModel.TaskAction[] acts = LauncherModel.TaskAction.values();
        String[] acNames = new String[acts.length];
        for (int i = 0; i < acts.length; i++) acNames[i] = acts[i].label;
        choiceDialog("执行动作", acNames, t.action.ordinal(), ai -> {
            t.action = acts[ai];
            if (t.action == LauncherModel.TaskAction.OPEN_PIP
                    || t.action == LauncherModel.TaskAction.OPEN_APP) {
                appPicker("选目标应用", a -> { t.pkg = a.pkg; askDelayThenAdd(t); });
            } else {
                askDelayThenAdd(t);
            }
        });
    }

    private void askDelayThenAdd(final LauncherModel.Task t) {
        inputDialog("延迟秒数（0 = 不延迟）", "0", s -> {
            try {
                long sec = Long.parseLong(s.trim());
                if (sec < 0) sec = 0;
                if (sec > 86400L * 7) sec = 86400L * 7;   // 上限 7 天：旧实现秒数>约24.8天时
                t.delayMs = (int) (sec * 1000);           // 乘 1000 会把 int 顶成负数，任务反而立即触发
            } catch (Throwable e) { t.delayMs = 0; }
            if (model.addTask(t)) render();
        });
    }

    private void taskManage(final LauncherModel.Task t) {
        String[] opts = {t.enabled ? "停用" : "启用", "删除"};
        choiceDialog(t.name, opts, -1, i -> {
            if (i == 0) {
                t.enabled = !t.enabled;
                model.updateTask();
                render();
            } else if (i == 1) {
                model.removeTask(t);
                render();
            }
        });
    }

    private void bootanimBody() {
        note("公开版保留本地素材管理：生成、格式校验、成品文件。安装进系统需要 Magisk（root），没有 root 时对应入口自动隐藏。");
        actionRow("开机动画 · 生成一次", "本地素材合成（待开发）", null);
        if (Caps.hasRoot()) {
            actionRow("开机动画安装器", "把生成的动画装进系统（需 Magisk）", null);
        }
    }

    private void systemBody() {
        toggleRow("开机自启", model.autoHome, () -> { model.autoHome = !model.autoHome; });
        sectionLabel("15.10 系统监控");
        actionRow("CPU / 温度 / 内存", SysOps.monitorLine(this), v -> {
            new Thread(() -> {
                final String out = SysOps.monitorLine(this);
                runOnUiThread(() -> msgDialog("系统监控", out));
            }).start();
        });
        actionRow("设为默认桌面", "把默认桌面改成海鸥桌面", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            } catch (Throwable e) {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Throwable ignore) {}
            }
        });
        actionRow("重启桌面", "关掉并重新打开海鸥桌面", v ->
                confirmDialog("重启桌面？", "桌面会立刻重启。", () -> {
                    Intent i = new Intent(this, HomeActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finishAffinity();
                }));
        actionRow("恢复出厂配置", "布局、Dock、外观、天气与歌词、自动化任务都会换成默认值。建议先导出备份。", v ->
                confirmDialog("恢复出厂配置？", "全部配置都会换成默认值。", () -> {
                    model.factoryReset();
                    toast("已恢复出厂配置");
                    render();
                }));

        sectionLabel("能力");
        actionRow("root 通道", Caps.rootWho(), v -> {
            new Thread(() -> {
                Caps.hasRoot();
                runOnUiThread(this::render);
            }).start();
        });
        actionRow("当前窗口档位", Caps.levelName(Caps.level(this)), null);
        actionRow("虚拟屏（L3）", Caps.canVirtualDisplay(this) ? "可用"
                : "不可用 —— 需 uid=1000。root 侧方案：把本应用装进 /system/priv-app", null);
        actionRow("LSPosed 模块自检", "不用抓日志：查模块有没有注入高德 / 网易云 / SystemUI",
                v -> runLspCheck());
        note("立即校时与系统自动时间需要平台签名，公开版不可达，对应入口不显示。");
    }

    private void marketBody() {
        actionRow(getString(R.string.root_panel_title), getString(R.string.root_panel_sub),
                v -> startActivity(new Intent(this, RootPanelActivity.class)));
        actionRow(getString(R.string.window_test_title), "能力探测与逐项验证",
                v -> startActivity(new Intent(this, WindowTestActivity.class)));
        actionRow(getString(R.string.mirror_title), getString(R.string.mirror_sub),
                v -> startActivity(new Intent(this, MirrorActivity.class)));
        actionRow("强制停止应用", "输入包名，root 通道执行", v ->
                inputDialog("包名", "", pkg -> {
                    if (pkg.trim().isEmpty()) return;
                    SysOps.R r = SysOps.forceStop(pkg.trim());
                    toast(r.toString());
                }));
        note("亮度 / 音量 / Wi-Fi / 蓝牙 / 飞行模式开关在 root 面板里。");
    }

    private void aboutBody() {
        String ver = "1.0";
        long code = 0;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
            code = pi.versionCode;
        } catch (Throwable ignore) {}
        actionRow("版本信息", "海鸥桌面 " + ver + " (" + code + ")", null);
        actionRow("作者信息", "公开版多窗口车机桌面 · 无账号 / 无会员 / 纯本地", null);
        actionRow("体检报告", "能力探测汇总", v ->
                msgDialog("体检报告", Caps.report(this)));
        actionRow("安装包指纹", "签名证书 SHA-256", v -> showFingerprint());
        actionRow("检查更新", "对比 GitHub 上最新的 release", v -> checkUpdate());
        note("不做登录、会员、云端存档、分享码。备份/恢复只走本地文件导入导出。");
    }

    /* ------------------------- LSPosed 模块自检 ------------------------- */

    /**
     * 「模块到底有没有被注入」的自检（批次 Z）。用户侧不便抓 logcat，就把这件事搬到
     * 应用内：root 跑一次 logcat 只看 SeagullLsp 标签，据此判断模块有没有进各目标进程，
     * 并给出下一步该点哪里。前提：本次开机后至少打开过一次目标应用 —— 模块在它进程里
     * 跑过才会有日志。
     */
    private void runLspCheck() {
        toast("正在自检…");
        new Thread(() -> {
            final String rep = lspCheckReport();
            runOnUiThread(() -> msgDialog("LSPosed 模块自检", rep));
        }).start();
    }

    private String lspCheckReport() {
        StringBuilder sb = new StringBuilder();

        String mgr = Caps.exec("pm list packages 2>/dev/null | grep -iE 'lsposed|edxposed|xposed'");
        boolean lspFound = mgr != null && !mgr.trim().isEmpty();
        sb.append("① 框架\t").append(lspFound
                        ? "已发现 " + mgr.trim().replace('\n', ' ')
                        : "未发现 LSPosed / Xposed —— 模块不可能生效，先装框架")
                .append('\n');

        String conf = Caps.exec("find /data/adb/lspd -iname '*seagull*' 2>/dev/null | head -5");
        sb.append("② 模块记录\t").append(conf != null && !conf.trim().isEmpty()
                        ? conf.trim().replace('\n', ' ')
                        : "未找到（模块可能没启用，或 LSPosed 没扫到本包）")
                .append('\n');

        String sw = Caps.exec("getprop persist.seagull.wf");
        String ori = Caps.exec("getprop persist.seagull.wf.orientation");
        sb.append("③ 空气开关\tpersist.seagull.wf=")
                .append(sw == null || sw.trim().isEmpty() ? "(空=默认开)" : sw.trim())
                .append("  wf.orientation=")
                .append(ori == null || ori.trim().isEmpty() ? "(空=默认横屏)" : ori.trim())
                .append('\n');

        String log = Caps.exec("logcat -d -s SeagullLsp 2>/dev/null | tail -n 40");
        String logTxt = log == null ? "" : log.trim();
        sb.append("\n④ 模块日志（logcat -s SeagullLsp 末 40 行）\n");
        sb.append(logTxt.isEmpty() ? "  <空 —— 一条模块日志都没有>" : logTxt).append('\n');

        sb.append("\n⑤ 结论\n");
        boolean hitAmap = logTxt.contains("handleLoadPackage: com.autonavi.minimap");
        boolean hitNet = logTxt.contains("handleLoadPackage: com.netease.cloudmusic");
        boolean hitSys = logTxt.contains("handleLoadPackage: com.android.systemui");
        if (!lspFound) {
            sb.append("  没检测到 LSPosed：先装框架（Zygisk / Riru 版），重启后再自检。\n");
        } else if (logTxt.isEmpty()) {
            sb.append("  模块没被注入任何进程。按顺序处理：\n");
            sb.append("   1) LSPosed 管理器 → 模块 → 启用「海鸥桌面」；\n");
            sb.append("   2) 进它的「作用域」，勾上：\n");
            sb.append("      com.autonavi.minimap（高德）\n");
            sb.append("      com.netease.cloudmusic 与 com.netease.cloudmusic.iot（网易云）\n");
            sb.append("      com.android.systemui\n");
            sb.append("   3) 保存后强杀高德 / 网易云再重开，回来再点一次自检。\n");
            sb.append("  注：作用域是「启用那一刻」快照的，之前没勾的包要手动补勾。\n");
        } else {
            sb.append("  模块已加载。\n");
            sb.append("   高德进程  : ").append(hitAmap
                    ? "已注入 ✓" : "未注入 ✗（作用域没勾 com.autonavi.minimap）").append('\n');
            sb.append("   网易云进程: ").append(hitNet
                    ? "已注入 ✓" : "未注入 ✗（补勾 com.netease.cloudmusic[.iot]）").append('\n');
            sb.append("   SystemUI : ").append(hitSys
                    ? "已注入 ✓" : "未注入 ✗（补勾 com.android.systemui）").append('\n');
            if (logTxt.contains("H1 失败") || logTxt.contains("H2 失败")
                    || logTxt.contains("H3 失败")) {
                sb.append("  日志里出现了「H1/H2/H3 失败」，按行尾异常信息处理。\n");
            } else if (hitAmap) {
                sb.append("  方向劫持就绪标志：日志里的「H2 hookOrientationOverride OK -> 0」（0=横屏）。\n");
            }
            // C0 总闸是否真的在干活（批次 AG）：出现「C0 注入」= 应用内跳转已被
            // 钉回虚拟屏；一次都没有 = 模块没注入目标进程，或跳转没发生在虚拟屏里。
            int c0 = countStr(logTxt, "C0 注入");
            sb.append("   displayId 注入: ").append(c0).append(" 次")
                    .append(c0 > 0 ? " ✓（画布内跳转已被钉住）"
                            : "（0 次：模块未注入目标进程，或还没在画布里点过页面）")
                    .append('\n');
        }
        sb.append("\n前提：本次开机后至少打开过一次目标应用，模块在它进程里跑过才有日志。\n");
        return sb.toString();
    }

    /** 子串计数（自检报告用：数「C0 注入」出现的次数）。 */
    private static int countStr(String s, String sub) {
        if (s == null || sub == null || sub.isEmpty()) return 0;
        int n = 0, from = 0;
        while ((from = s.indexOf(sub, from)) >= 0) { n++; from += sub.length(); }
        return n;
    }

    /** 17.3 检查更新：打 GitHub releases/latest，跟本机 versionName 比。 */
    private void checkUpdate() {
        final String cur = currentVersionName();
        toast("在问 GitHub…");
        new Thread(() -> {
            String tag = "", url = "", note2 = "";
            try {
                String json = httpText("https://api.github.com/repos/caogenfunan123/seagull-desktop/releases/latest");
                org.json.JSONObject o = new org.json.JSONObject(json);
                tag = o.optString("tag_name", "");
                url = "";
                org.json.JSONArray assets = o.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    org.json.JSONObject a = assets.optJSONObject(i);
                    if (a != null && a.optString("name", "").endsWith(".apk")) {
                        url = a.optString("browser_download_url", "");
                        break;
                    }
                }
            } catch (Throwable t) {
                note2 = "查不到：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
            final String fTag = tag, fUrl = url, fNote = note2;
            runOnUiThread(() -> {
                if (!fNote.isEmpty()) { msgDialog("检查更新", fNote); return; }
                if (fTag.isEmpty()) { msgDialog("检查更新", "这个仓库还没有 release"); return; }
                String latest = fTag.startsWith("v") ? fTag.substring(1) : fTag;
                if (latest.equals(cur)) { msgDialog("检查更新", "当前 " + cur + " 已是最新"); return; }
                String[] opts = fUrl.isEmpty() ? new String[]{"知道了"}
                        : new String[]{"下载安装包（" + latest + "）", "知道了"};
                choiceDialog("有新版本 " + latest, opts, -1, i -> {
                    if (i == 0 && !fUrl.isEmpty()) downloadApk(fUrl, latest);
                });
            });
        }).start();
    }

    /** tag_name 来自 GitHub release，只留版本号字符：挡 "../" 之类写出沙盒的文件名。 */
    private static String safeVer(String v) {
        if (v == null) return "update";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length() && sb.length() < 32; i++) {
            char ch = v.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '.' || ch == '-' || ch == '_') sb.append(ch);
        }
        return sb.length() == 0 ? "update" : sb.toString();
    }

    /** 17.4 自更新：下到 filesDir，用系统安装器装（清单已申请 REQUEST_INSTALL_PACKAGES）。 */
    private void downloadApk(String url, String ver) {
        toast("开始下载 " + ver);
        new Thread(() -> {
            java.io.File out = null;
            String err = "";
            try {
                // 与 res/xml/file_paths.xml 的 "updates" 映射对齐：下载件放
                // files/updates/ 子目录，别把 files/ 根目录混进暴露面
                java.io.File dir = new java.io.File(getFilesDir(), "updates");
                if (!dir.isDirectory()) dir.mkdirs();
                out = new java.io.File(dir, "seagull-" + safeVer(ver) + ".apk");
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                try {
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(60000);
                    try (java.io.InputStream in = c.getInputStream();
                         java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                    }
                } finally {
                    try { c.disconnect(); } catch (Throwable ignore) {}
                }
            } catch (Throwable t) {
                err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            final java.io.File f = out;
            final String e = err;
            runOnUiThread(() -> {
                if (f == null || !f.isFile()) { msgDialog("下载失败", e); return; }
                try {
                    // content:// + FileProvider：targetSdk 24 起 Uri.fromFile()
                    // 直接 FileUriExposedException（旧实现就是这样，装新版本必崩）
                    android.net.Uri u = SeagullFileProvider.uriFor(SettingsSectionActivity.this, f);
                    if (u == null) {
                        msgDialog("装不上", "拿不到 " + f.getAbsolutePath() + " 的分享地址");
                        return;
                    }
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(u, "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable t2) {
                    msgDialog("装不上", "这台机器不允许安装未知来源：" + t2.getMessage()
                            + "\n包在 " + f.getAbsolutePath());
                }
            });
        }).start();
    }

    private static String httpText(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            java.io.InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally {
            try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    private String currentVersionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.0";
        }
    }

    /** root 才给的动作：没 root 就说清楚，不做盲试。 */
    private void rootOnly(Runnable r) {
        if (!Caps.hasRoot()) { toast("这台机器没有 root，这个动作做不了"); return; }
        r.run();
    }

    private String touchLog() {
        java.util.List<String> lines = TouchForward.lastLines();
        if (lines.isEmpty()) return "还没有注入记录（窗口还没部署或没点过）";
        return android.text.TextUtils.join("\n", lines);
    }

    private void showFingerprint() {
        new Thread(() -> {
            String fp;
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] sig;
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                android.content.pm.SigningInfo si = pi.signingInfo;
                sig = si.hasMultipleSigners() ? si.getApkContentsSigners()[0].toByteArray()
                        : si.getSigningCertificateHistory()[0].toByteArray();
                StringBuilder sb = new StringBuilder();
                for (byte b : md.digest(sig)) sb.append(String.format("%02X", b)).append(':');
                fp = sb.substring(0, sb.length() - 1);
            } catch (Throwable t) {
                fp = "读取失败：" + t;
            }
            final String out = fp;
            runOnUiThread(() -> msgDialog("安装包指纹", out));
        }).start();
    }

    /* ==================== 通用控件 ==================== */

    private void sectionLabel(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Skin.c(R.color.leaf));
        tv.setTextSize(13);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(20); p.bottomMargin = dp(6);
        tv.setLayoutParams(p);
        col.addView(tv);
    }

    private void note(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Skin.c(R.color.text_dim));
        tv.setTextSize(12);
        tv.setPadding(dp(4), dp(10), dp(4), dp(4));
        col.addView(tv);
    }

    private void toggleRow(String label, boolean on, Runnable flip) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(Skin.round(on ? Skin.c(R.color.card) : Skin.c(R.color.panel), 12f));
        TextView tv = new TextView(this);
        tv.setText((on ? "☑ " : "☐ ") + label);
        tv.setTextColor(on ? Skin.c(R.color.leaf) : Skin.c(R.color.text));
        tv.setTextSize(14);
        row.addView(tv);
        row.setOnClickListener(v -> { flip.run(); model.save(); render(); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(6);
        row.setLayoutParams(p);
        col.addView(row);
    }

    private void choiceRow(String label, String current, Runnable open) {
        actionRow(label, current, open == null ? null : v -> open.run());
    }

    private void actionRow(String title, String sub, View.OnClickListener l) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(11), dp(14), dp(11));
        box.setBackground(Skin.round(l == null ? Skin.c(R.color.panel) : Skin.c(R.color.card), 12f));

        TextView a = new TextView(this);
        a.setText(title);
        a.setTextColor(Skin.c(R.color.text));
        a.setTextSize(14);
        box.addView(a);

        if (sub != null && !sub.isEmpty()) {
            TextView b = new TextView(this);
            b.setText(sub);
            b.setTextColor(Skin.c(R.color.text_dim));
            b.setTextSize(12);
            box.addView(b);
        }

        box.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(6);
        box.setLayoutParams(p);
        col.addView(box);
    }

    private void sliderRow(String label, int min, int max, int val, String unit, IntConsumer apply) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(12));
        box.setBackground(Skin.round(Skin.c(R.color.card), 12f));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(Skin.c(R.color.text));
        name.setTextSize(14);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView value = new TextView(this);
        value.setText((val) + unit);
        value.setTextColor(Skin.c(R.color.leaf));
        value.setTextSize(14);
        top.addView(value);
        box.addView(top);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max, val) - min));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    int v = p + min;
                    value.setText(v + unit);
                    apply.accept(v);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { model.save(); }
        });
        box.addView(sb, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(6);
        box.setLayoutParams(p);
        col.addView(box);
    }

    /* ==================== 对话框 ==================== */

    private void choiceDialog(String title, String[] options, int cur, IntConsumer pick) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options, cur, (DialogInterface d, int which) -> {
                    d.dismiss();
                    pick.accept(which);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void inputDialog(String title, String cur, Consumer<String> ok) {
        EditText et = new EditText(this);
        et.setText(cur);
        et.setSelection(et.getText().length());
        et.setTextColor(Skin.c(R.color.text));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", (DialogInterface d, int w) -> ok.accept(et.getText().toString()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDialog(String title, String msg, Runnable ok) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("确定", (DialogInterface d, int w) -> ok.run())
                .setNegativeButton("取消", null)
                .show();
    }

    private void msgDialog(String title, String msg) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("好", null)
                .show();
    }

    private void appPicker(String title, Consumer<LauncherModel.App> pick) {
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < model.allApps.size(); i++) {
            final LauncherModel.App a = model.allApps.get(i);
            TextView tv = new TextView(this);
            tv.setText(a.label + "\n" + a.pkg);
            tv.setTextColor(Skin.c(R.color.text));
            tv.setTextSize(13);
            tv.setPadding(dp(16), dp(10), dp(16), dp(10));
            tv.setOnClickListener(v -> {
                if (dialog != null) dialog.dismiss();
                pick.accept(a);
            });
            list.addView(tv);
        }
        sv.addView(list);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setNegativeButton("取消", null)
                .show();
        dialog = dlg;
    }

    private AlertDialog dialog;

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
