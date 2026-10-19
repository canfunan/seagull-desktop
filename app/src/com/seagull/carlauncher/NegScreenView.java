package com.seagull.carlauncher;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * J1 负一屏：桌面最左页的聚合卡容器（复用 PagerView 滑动基建，本页挂第 0 页）。
 *
 * 卡片装配（BACKLOG #7）：
 *   左列 —— J2 天气卡（点按刷新）、限行卡（本地城市尾号规则，周一..周五）；
 *   右列 —— J3 快捷开关（WiFi/蓝牙/飞行 2s 轮询状态，热点直开系统页）、
 *           J5 目的地卡（amapuri → baidumap 级联拉起，未装导航给提示）、
 *           J4 日志导出卡（RootLog + logcat → /sdcard/Seagull/，带版本 commit 号）。
 *
 * 刷新纪律：结构在构造器建一次；refreshSwitchState 只原位改 chip 颜色
 * （审计 M4 纪律），2s 轮询仅在负一屏可见时跑（setActive 由宿主翻页驱动），
 * 不在别的页白烧 root exec。
 */
public final class NegScreenView extends FrameLayout {

    private final LauncherModel model;
    private final LinearLayout switchBody, destBody;
    private final TextView weatherText, restrictText, logLabel;
    private final TextView chipWifi, chipBt, chipAp, chipHp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean tickOn;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!tickOn) return;
            refreshSwitchState();
            ui.postDelayed(this, 2000L);
        }
    };

    public NegScreenView(Context c, LauncherModel m) {
        super(c);
        model = m;
        setBackgroundColor(Color.TRANSPARENT);

        LinearLayout cols = new LinearLayout(c);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        addView(cols, new LayoutParams(-1, -1));

        LinearLayout colA = new LinearLayout(c);
        colA.setOrientation(LinearLayout.VERTICAL);
        LinearLayout colB = new LinearLayout(c);
        colB.setOrientation(LinearLayout.VERTICAL);
        cols.addView(colA, col());
        cols.addView(colB, col());

        /* ---- J2 天气卡 ---- */
        LinearLayout weatherBody = card(colA, "天气");
        weatherText = body(c);
        weatherText.setTextSize(15);
        weatherBody.addView(weatherText);
        weatherBody.setOnClickListener(v -> {
            weatherText.setText("刷新中…");
            Weather.refresh(getContext(), model, this::refreshWeather);
        });

        /* ---- J2 限行卡 ---- */
        LinearLayout restrictBody = card(colA, "限行");
        restrictText = body(c);
        restrictText.setTextSize(15);
        restrictBody.addView(restrictText);

        /* ---- J3 快捷开关 ---- */
        switchBody = card(colB, "快捷开关");
        LinearLayout row1 = new LinearLayout(c);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout row2 = new LinearLayout(c);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        chipWifi = chip(row1, "WiFi", v -> toggle(SW_WIFI));
        chipBt = chip(row1, "蓝牙", v -> toggle(SW_BT));
        chipAp = chip(row2, "飞行", v -> toggle(SW_AP));
        chipHp = chip(row2, "热点…", v -> openHotspot());
        switchBody.addView(row1);
        switchBody.addView(row2);

        /* ---- J5 目的地卡 ---- */
        destBody = card(colB, "目的地");
        refreshDests();

        /* ---- J4 日志导出卡 ---- */
        LinearLayout logBody = card(colB, "日志");
        logLabel = body(c);
        logLabel.setText("导出运行日志到 /sdcard/Seagull/");
        logBody.addView(logLabel);
        logBody.setOnClickListener(v -> exportLog());

        refreshWeather();
        refreshRestrict();
        refreshSwitchState();
    }

    private static LinearLayout.LayoutParams col() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        return lp;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Skin.round(Skin.c(R.color.panel), 14f));
        box.setPadding(dp(14), dp(10), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        parent.addView(box, lp);

        TextView t = new TextView(getContext());
        t.setText(title);
        t.setTextColor(Skin.c(R.color.leaf));
        t.setTextSize(12);
        box.addView(t);
        return box;
    }

    /** 往卡里追加一行（目的地/正文用），统一左边距。 */
    private TextView addLine(LinearLayout card, String text, float size, int color) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(Skin.c(color));
        t.setTextSize(size);
        t.setPadding(0, dp(2), 0, dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(3);
        card.addView(t, lp);
        return t;
    }

    private TextView body(Context c) {
        TextView t = new TextView(c);
        t.setTextColor(Skin.c(R.color.text));
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private TextView chip(LinearLayout row, String label, View.OnClickListener tap) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(7), dp(18), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(8);
        lp.topMargin = dp(6);
        row.addView(t, lp);
        t.setOnClickListener(tap);
        return t;
    }

    /* ------------------------- J2 天气 / 限行 ------------------------- */

    private void refreshWeather() {
        String s = Weather.summary(model);
        if (s == null || s.isEmpty()) s = "还没取到天气，点一下刷新";
        weatherText.setText(s + (model.weatherForecast.isEmpty() ? "" : "\n" + model.weatherForecast)
                + (model.weatherUpdatedAt > 0
                        ? "\n更新于 " + Weather.updatedLabel(model.weatherUpdatedAt) : ""));
    }

    /** 周一=1..周日=7；尾号串按 "一,一,二,二,..." 两个一组取当天组。 */
    private void refreshRestrict() {
        if (!model.restrictEnabled || model.restrictTails.isEmpty()) {
            restrictText.setText(model.restrictEnabled ? "尾号规则未配置" : "未开启限行提示");
            restrictText.setTextColor(Skin.c(R.color.text_dim));
            return;
        }
        String[] parts = model.restrictTails.split(",");
        Calendar c = Calendar.getInstance();
        int dow = c.get(Calendar.DAY_OF_WEEK);          // 周日=1
        int idx = (dow == Calendar.SUNDAY || dow == Calendar.SATURDAY) ? -1 : dow - 2;
        String head = (model.restrictCity.isEmpty() ? "" : model.restrictCity + " · ")
                + (idx < 0 ? "周末" : "周" + "一二三四五".charAt(idx));
        if (idx < 0 || idx * 2 + 1 >= parts.length) {
            restrictText.setText(head + "\n今日不限行");
            restrictText.setTextColor(Skin.c(R.color.leaf));
            return;
        }
        restrictText.setText(head + "\n限行 " + parts[idx * 2].trim() + " 和 " + parts[idx * 2 + 1].trim());
        restrictText.setTextColor(Skin.c(R.color.text));
    }

    /* ------------------------- J3 快捷开关 ------------------------- */

    private static final int SW_WIFI = 0, SW_BT = 1, SW_AP = 2;

    private void refreshSwitchState() {
        setChip(chipWifi, SysOps.isWifiOn(getContext()));
        setChip(chipBt, SysOps.isBluetoothOn(getContext()));
        setChip(chipAp, SysOps.isAirplaneOn(getContext()));
        chipHp.setTextColor(Skin.c(R.color.text));
        chipHp.setBackground(Skin.round(Skin.c(R.color.panel), 18f));
    }

    private void setChip(TextView chip, boolean on) {
        chip.setTextColor(Skin.c(on ? R.color.ground : R.color.text_dim));
        chip.setBackground(Skin.round(Skin.c(on ? R.color.leaf : R.color.panel), 18f));
    }

    private void toggle(int which) {
        boolean cur = which == SW_WIFI ? SysOps.isWifiOn(getContext())
                : which == SW_BT ? SysOps.isBluetoothOn(getContext())
                : SysOps.isAirplaneOn(getContext());
        SysOps.R r = which == SW_WIFI ? SysOps.setWifi(!cur)
                : which == SW_BT ? SysOps.setBluetooth(!cur)
                : SysOps.setAirplane(!cur);
        Toast.makeText(getContext(), r.toString(), Toast.LENGTH_SHORT).show();
        ui.postDelayed(this::refreshSwitchState, 800L);
    }

    /** 热点交易号随 ROM 漂移（RESEARCH A5）——直开系统热点设置页最稳。 */
    private void openHotspot() {
        try {
            getContext().startActivity(new Intent("android.settings.TETHER_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (Throwable ignore) { }
        try {
            getContext().startActivity(new Intent().setClassName("com.android.settings",
                    "com.android.settings.TetherSettings")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Toast.makeText(getContext(), "打不开热点设置页", Toast.LENGTH_SHORT).show();
        }
    }

    /* ------------------------- J5 目的地卡 ------------------------- */

    private void refreshDests() {
        // 结构重建只发生在配置变化/构造期，运行时零重建
        for (int i = destBody.getChildCount() - 1; i >= 1; i--) destBody.removeViewAt(i);
        List<LauncherModel.NegDest> ds = model.dests();
        boolean any = false;
        for (final LauncherModel.NegDest d : ds) {
            if (d.name == null || d.name.isEmpty()) continue;
            any = true;
            TextView t = addLine(destBody, "→ " + d.name, 15, R.color.text);
            t.setPadding(0, dp(6), 0, dp(6));
            t.setOnClickListener(v -> navigate(d));
            t.setOnLongClickListener(v -> { confirmRemoveDest(d); return true; });
        }
        if (!any) addLine(destBody, "还没有目的地，在设置里添加", 13, R.color.text_dim);
    }

    /** amapuri 优先（dev=0 即 GCJ02，RESEARCH A1），没有高德试百度，都没有给提示。 */
    private void navigate(LauncherModel.NegDest d) {
        String pos = d.lat + "," + d.lng;
        Intent amap = new Intent(Intent.ACTION_VIEW,
                Uri.parse("amapuri://route/plan/?dlat=" + d.lat + "&dlon=" + d.lng
                        + "&dev=0&t=0&navigate=yes"));
        if (resolves(amap)) { tryStart(amap, "高德"); return; }
        Intent baidu = new Intent(Intent.ACTION_VIEW,
                Uri.parse("baidumap://map/direction?destination=" + pos + "&coord_type=gcj02&mode=driving&src=seagull"));
        if (resolves(baidu)) { tryStart(baidu, "百度地图"); return; }
        Toast.makeText(getContext(), "没装高德/百度地图，装一个再点", Toast.LENGTH_SHORT).show();
    }

    private boolean resolves(Intent i) {
        return getContext().getPackageManager().resolveActivity(i, 0) != null;
    }

    private void tryStart(Intent i, String label) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            Toast.makeText(getContext(), "已交给 " + label, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(getContext(), "拉起 " + label + " 失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmRemoveDest(final LauncherModel.NegDest d) {
        new AlertDialog.Builder(getContext())
                .setTitle("删除目的地")
                .setMessage("删除「" + d.name + "」？也可以到设置里改。")
                .setPositiveButton("删除", (dlg, w) -> {
                    model.removeDest(d);
                    refreshDests();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ------------------------- J4 日志导出 ------------------------- */

    private void exportLog() {
        logLabel.setText("导出中…");
        ui.postDelayed(() -> {
            new Thread(() -> {
                final String path = doExport();
                uiPost(() -> {
                    logLabel.setText(path == null ? "导出失败（见 toast）"
                            : "已导出\n" + path);
                    Toast.makeText(getContext(), path == null ? "日志导出失败" : "已导出 " + path,
                            Toast.LENGTH_SHORT).show();
                });
            }, "SeagullLogExp").start();
        }, 60L);
    }

    private String doExport() {
        String ver = "unknown";
        try {
            ver = getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (Throwable ignore) { }
        StringBuilder sb = new StringBuilder();
        sb.append("== 海鸥桌面运行日志 ==\n版本 ").append(ver)
                .append("  导出于 ").append(SimpleDateFormat.getDateTimeInstance()
                .format(new Date())).append('\n');
        sb.append("-- root 日志（").append(RootLog.PATH).append("） --\n");
        String rootLog = Caps.hasRoot() ? Caps.exec("cat " + RootLog.PATH) : "(无 root 通道)";
        sb.append(rootLog == null || rootLog.isEmpty() ? "(空)" : rootLog).append('\n');
        sb.append("-- 应用 logcat（近 500 行） --\n");
        try {
            java.lang.Process proc = null;
            Process p = Runtime.getRuntime().exec(
                    new String[]{"logcat", "-d", "-t", "500", "-s",
                            "SeagullHome", "SeagullDiag", "SeagullCar", "SeagullLsp", "SeagullPip"});
            java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = in.readLine()) != null) sb.append(line).append('\n');
            in.close();
            p.destroy();
        } catch (Throwable t) {
            sb.append("(logcat 拉取失败: ").append(t).append(")\n");
        }
        String name = "seagull-log-" + ver.replaceAll("[^0-9A-Za-z.\\-]", "_") + ".txt";
        // 三级回落：公共目录 File API（旧 ROM 可直写）→ root cp → 应用私有目录
        try {
            File dir = new File("/sdcard/Seagull");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, name);
            try (FileWriter w = new FileWriter(f)) {
                w.write(sb.toString());
            }
            return f.getAbsolutePath();
        } catch (Throwable ignore) { }
        try {
            if (Caps.hasRoot()) {
                File tmp = File.createTempFile("sglog", ".txt", getContext().getCacheDir());
                try (FileWriter w = new FileWriter(tmp)) {
                    w.write(sb.toString());
                }
                Caps.exec("mkdir -p /sdcard/Seagull && cp " + tmp.getAbsolutePath()
                        + " /sdcard/Seagull/" + name);
                return "/sdcard/Seagull/" + name;
            }
        } catch (Throwable ignore) { }
        try {
            File dir = new File(getContext().getExternalFilesDir(null), "Seagull");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, name);
            try (FileWriter w = new FileWriter(f)) {
                w.write(sb.toString());
            }
            return f.getAbsolutePath();
        } catch (Throwable ignore) { }
        return null;
    }

    /* ------------------------- 生命周期 ------------------------- */

    /** 宿主翻到/离开负一屏时调：控制 2s 开关状态轮询。 */
    public void setActive(boolean on) {
        if (on && !tickOn) {
            tickOn = true;
            ui.removeCallbacks(tick);
            refreshSwitchState();
            refreshRestrict();
            refreshWeather();
            ui.postDelayed(tick, 2000L);
        } else if (!on) {
            tickOn = false;
            ui.removeCallbacks(tick);
        }
    }

    private void uiPost(Runnable r) { ui.post(r); }

    public void refreshAll() {
        refreshWeather();
        refreshRestrict();
        refreshSwitchState();
        refreshDests();
    }

    private int dp(float v) {
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
