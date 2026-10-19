package com.seagull.carlauncher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 设置中心：5 组 / 17 分区入口 + 全局搜索（设置项名 + 应用名）。
 *
 * 分区结构与文案逐字对齐野菜桌面（去掉「账号与云端」组）：
 *   桌面       → 布局 | Dock 栏 | 快捷栏 | 菜园 | 小白点
 *   外观       → 主题与壁纸 | 屏幕 | 野菜岛
 *   天气与歌词 → 天气 | 歌词
 *   高级功能   → 窗口 | 触摸 | 自动化任务 | 开机动画
 *   系统与工具 → 系统 | 车机工具 | 关于
 *
 * 每行副标题显示当前值摘要；点行进 SettingsSectionActivity（布局走现成 LayoutModeActivity）。
 */
public class SettingsHubActivity extends BaseActivity {

    private static final class Sec {
        final String id;
        final String name;
        final String kw;
        Sec(String id, String name, String kw) { this.id = id; this.name = name; this.kw = kw; }
    }

    private static final String[] GROUPS = {"桌面", "外观", "天气与歌词", "高级功能", "系统与工具"};

    private static final Sec[] SECTIONS = {
            new Sec("layout", "布局", "桌面分区 组件条 显示名称 网格 模式 紧凑"),
            new Sec("dock", "Dock 栏", "位置 数量 宽度 图标大小 间距 透明度 自动收起 固定 时间 槽位 替换应用"),
            new Sec("quickbar", "快捷栏", "快捷开关 功能按钮 组件条 小dock 场景一键 场景 布屏"),
            new Sec("garden", "菜园", "叶子 大时钟 歌词 压暗 摆法 遮罩"),
            new Sec("ball", "小白点", "悬浮球 悬浮键 野菜键 大小 位置 悬浮窗权限"),
            new Sec("blocks", "桌面组件块", "行车块 车速 海拔 航向 行程 倒数 倒计时 监控 内存 CPU 存储 音乐 本地音乐 G值 加速度 壁纸页 块"),
            new Sec("theme", "主题与壁纸", "主题色 颜色 日夜模式 深色 浅色 壁纸 遮罩 毛玻璃 字号 主题包 圆角 玻璃 描边 动态壁纸 视频壁纸 天气联动 壁纸库"),
            new Sec("neg", "负一屏", "负一屏 天气卡 限行 尾号 城市 快捷开关 日志导出 常用目的地 回家 公司 一键导航"),
            new Sec("screen", "屏幕", "外边距 缝 方向 横屏 竖屏 保持亮屏 顶部信息栏 校时"),
            new Sec("island", "野菜岛", "胶囊 顶部偏移 宽度 挖孔 圆角 岛 导航横幅"),
            new Sec("weather", "天气", "城市 定位 和风 气温 未来3天"),
            new Sec("lyrics", "歌词", "lrc 行数 字号 对时 偏移 媒体卡片 蓝牙 通知"),
            new Sec("window", "窗口", "画中画 虚拟屏 性能档位 兼容模式 后台保留 显示大小 自检 镜像"),
            new Sec("touch", "触摸", "防误滑 长按判定 跟手 无障碍 root注入 桌面手势 双指锁屏"),
            new Sec("tasks", "自动化任务", "任务 触发条件 定时 延迟 开机"),
            new Sec("bootanim", "开机动画", "开机动画 素材 安装器 magisk 生成"),
            new Sec("system", "系统", "开机自启 默认桌面 重启桌面 恢复出厂 监控 root"),
            new Sec("market", "车机工具", "亮度 音量 wifi 蓝牙 飞行模式 强制停止 root面板 本地音乐 音乐播放"),
            new Sec("about", "关于", "版本 作者 检查更新 体检报告 指纹")
    };

    private LinearLayout listCol;
    private String query = "";

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
    }

    @Override protected void onResume() {
        super.onResume();
        model.loadApps();
        rebuildList();
    }

    private View build() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Skin.c(R.color.ground));
        col.setPadding(dp(16), dp(12), dp(16), dp(24));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("← 返回桌面");
        back.setTextColor(Skin.c(R.color.leaf));
        back.setTextSize(15);
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView t = new TextView(this);
        t.setText("  设置");
        t.setTextColor(Skin.c(R.color.text));
        t.setTextSize(17);
        head.addView(t);
        col.addView(head);

        EditText search = new EditText(this);
        search.setHint("搜设置项或应用");
        search.setHintTextColor(Skin.c(R.color.text_dim));
        search.setTextColor(Skin.c(R.color.text));
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.setBackground(Skin.pill(Skin.c(R.color.card)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(12);
        search.setLayoutParams(sp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void afterTextChanged(Editable s) { query = s.toString().trim(); rebuildList(); }
        });
        col.addView(search);

        listCol = new LinearLayout(this);
        listCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        listCol.setLayoutParams(lp);
        col.addView(listCol);

        sv.addView(col);
        return sv;
    }

    private void rebuildList() {
        if (listCol == null || model == null) return;
        listCol.removeAllViews();
        if (query.isEmpty()) {
            for (String g : GROUPS) {
                groupLabel(g);
                for (Sec s : SECTIONS) {
                    if (groupOf(s.id).equals(g)) listCol.addView(secRow(s));
                }
            }
            return;
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<Sec> hitSec = new ArrayList<>();
        for (Sec s : SECTIONS) {
            if (s.name.toLowerCase(Locale.ROOT).contains(q)
                    || s.kw.toLowerCase(Locale.ROOT).contains(q)) hitSec.add(s);
        }
        List<LauncherModel.App> hitApps = new ArrayList<>();
        for (LauncherModel.App a : model.allApps) {
            if (a.label.toLowerCase(Locale.ROOT).contains(q)
                    || a.pkg.toLowerCase(Locale.ROOT).contains(q)) hitApps.add(a);
        }
        if (hitSec.isEmpty() && hitApps.isEmpty()) {
            note("没有匹配的设置项或应用");
            return;
        }
        if (!hitSec.isEmpty()) {
            groupLabel("设置项");
            for (Sec s : hitSec) listCol.addView(secRow(s));
        }
        if (!hitApps.isEmpty()) {
            groupLabel("应用");
            for (LauncherModel.App a : hitApps) listCol.addView(appRow(a));
        }
    }

    private static String groupOf(String id) {
        switch (id) {
            case "layout": case "dock": case "quickbar": case "garden": case "ball":
            case "blocks": case "neg":
                return "桌面";
            case "theme": case "screen": case "island":
                return "外观";
            case "weather": case "lyrics":
                return "天气与歌词";
            case "window": case "touch": case "tasks": case "bootanim":
                return "高级功能";
            default:
                return "系统与工具";
        }
    }

    private String subtitle(String id) {
        LauncherModel m = model;
        switch (id) {
            case "dock": return m.dockPos.label + " · " + m.dockCount + " 个";
            case "quickbar": return m.quickbar.isEmpty() ? "未配置" : m.quickbar.size() + " 格";
            case "garden": return (m.gardenEnabled ? "开 · " : "关 · ") + m.gardenStyle.label;
            case "ball": return m.ballEnabled ? "开" : "关";
            case "theme": {
                String t = Theme.nameOf(m.themeId);
                if ("自定义".equals(t) && !m.customAccent.isEmpty()) t += " " + m.customAccent;
                return t + " · " + m.dayNight.label;
            }
            case "screen": return m.orientation.label + " · 边距 " + m.marginH + "/" + m.marginV;
            case "island": return m.islandEnabled ? "开 · 宽 " + m.islandWidth : "关";
            case "weather": return m.citySource.label + (m.weatherCity.isEmpty() ? "" : " · " + m.weatherCity);
            case "lyrics": return m.lyricSource.label;
            case "window": return m.perf.label + " · 后台保留 " + m.bgKeep;
            case "touch": return "防误滑 " + m.touchThreshold + "px · 长按 " + (m.longPressMs / 1000f) + "s";
            case "tasks": return m.tasks.size() + " / " + LauncherModel.TASK_MAX + " 条";
            case "bootanim": return "本地素材管理";
            case "system": return "开机自启 " + (m.autoHome ? "开" : "关");
            case "market": return "root 面板 · 自检 · 强停";
            case "about": return "版本 " + versionName();
            default: return "";
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) { return "1.0"; }
    }

    private View secRow(final Sec s) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(Skin.round(Skin.c(R.color.card), 12f));

        TextView a = new TextView(this);
        a.setText(s.name);
        a.setTextColor(Skin.c(R.color.text));
        a.setTextSize(15);
        row.addView(a);

        TextView b = new TextView(this);
        b.setText(subtitle(s.id));
        b.setTextColor(Skin.c(R.color.text_dim));
        b.setTextSize(12);
        row.addView(b);

        row.setOnClickListener(v -> openSection(s.id));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(8);
        row.setLayoutParams(p);
        return row;
    }

    private View appRow(final LauncherModel.App a) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setBackground(Skin.round(Skin.c(R.color.card), 12f));

        ImageView iv = new ImageView(this);
        Drawable d = model.icon(a);
        if (d != null) iv.setImageDrawable(d);
        row.addView(iv, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(a.label);
        name.setTextColor(Skin.c(R.color.text));
        name.setTextSize(14);
        txt.addView(name);
        TextView pkg = new TextView(this);
        pkg.setText(a.pkg);
        pkg.setTextColor(Skin.c(R.color.text_dim));
        pkg.setTextSize(11);
        txt.addView(pkg);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.leftMargin = dp(10);
        row.addView(txt, tp);

        row.setOnClickListener(v -> model.launch(a, true));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(8);
        row.setLayoutParams(p);
        return row;
    }

    private void openSection(String id) {
        if ("layout".equals(id)) {
            startActivity(new Intent(this, LayoutModeActivity.class));
            return;
        }
        Intent i = new Intent(this, SettingsSectionActivity.class);
        i.putExtra(SettingsSectionActivity.EXTRA_SECTION, id);
        startActivity(i);
    }

    private void groupLabel(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Skin.c(R.color.leaf));
        tv.setTextSize(13);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(16); p.bottomMargin = dp(8);
        tv.setLayoutParams(p);
        listCol.addView(tv);
    }

    private void note(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Skin.c(R.color.text_dim));
        tv.setTextSize(13);
        tv.setPadding(dp(4), dp(20), dp(4), dp(4));
        listCol.addView(tv);
    }
}
