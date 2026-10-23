package com.seagull.carlauncher;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 布局模式选择器 + 组件条配置。
 * 对齐海鸥桌面的「布局」（一个窗口占满/主副/三等分…）与「快捷栏」设置组。
 * 这里的"布局"作用于桌面自身的分区，不依赖任何特权 —— 公开版可完整交付。
 */
public class LayoutModeActivity extends BaseActivity {

    private LinearLayout listCol;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
    }

    private View build() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Skin.c(R.color.ground));
        col.setPadding(dp(16), dp(12), dp(16), dp(18));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("← 返回桌面");
        back.setTextColor(Skin.c(R.color.leaf));
        back.setTextSize(15);
        back.setOnClickListener(v -> finish());
        head.addView(back);
        TextView t = new TextView(this);
        t.setText("  布局与组件");
        t.setTextColor(Skin.c(R.color.text));
        t.setTextSize(17);
        head.addView(t);
        col.addView(head);

        col.addView(section("桌面布局（点击切换，立即生效）"));

        listCol = new LinearLayout(this);
        listCol.setOrientation(LinearLayout.VERTICAL);
        col.addView(listCol);
        // 行必须在这里随 build 填：旧实现只在 rebuildRows 里填，且填完旧
        // listCol 后又 setContentView(build()) 换一棵全新的空树——
        // 「桌面布局」列表进页面永远是空的（批次 T 复盘坐实）。
        fillModeRows();

        col.addView(section("组件条（点一下开/关）"));
        for (int i = 0; i < DesktopView.WIDGET_NAMES.length; i++) col.addView(widgetRow(i));

        col.addView(section("其他"));
        col.addView(toggleRow("显示应用名称", model.showLabels, v -> {
            model.showLabels = !model.showLabels;
            model.save();
            rebuildRows();
        }));

        col.addView(btn("自动归类（按包名分文件夹）", v -> {
            int made = model.autoGroup();
            Toast.makeText(this, made > 0
                    ? "已分出 " + made + " 个分类文件夹"
                    : "没有需要归类的应用（已在文件夹里）", Toast.LENGTH_SHORT).show();
            setContentView(build());
        }));

        col.addView(btn("解散全部文件夹（" + model.folders.size() + " 个）", v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("解散全部文件夹？")
                        .setMessage("所有应用都会回到主屏。")
                        .setPositiveButton("解散", (d, w) -> {
                            model.dissolveAllFolders();
                            Toast.makeText(this, "已解散", Toast.LENGTH_SHORT).show();
                            setContentView(build());
                        })
                        .setNegativeButton("取消", null)
                        .show()));

        col.addView(btn("预览网格列数", v -> {
            model.switchMode(model.mode == LauncherModel.Mode.GRID
                    ? LauncherModel.Mode.DENSE : LauncherModel.Mode.GRID);
            rebuildRows();
            Toast.makeText(this, "已切到 " + model.mode.label, Toast.LENGTH_SHORT).show();
        }));

        return wrap(sv, col);
    }

    private View wrap(ScrollView sv, LinearLayout col) { sv.addView(col); return sv; }

    private void fillModeRows() {
        if (listCol == null) return;
        listCol.removeAllViews();
        for (LauncherModel.Mode m : LauncherModel.Mode.values()) {
            listCol.addView(modeRow(m));
        }
    }

    private void rebuildRows() {
        // 整树重建即可（build() 内已 fillModeRows）：
        // 旧实现先往旧 listCol 填行、再 setContentView(build()) 造一棵新树，
        // 刚填的行随旧树一起被丢弃，界面永远看不到布局列表
        setContentView(build());
    }

    private View modeRow(final LauncherModel.Mode m) {
        boolean on = model.mode == m;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(13), dp(12), dp(13));
        row.setBackgroundColor(on ? Skin.c(R.color.card) : Skin.c(R.color.panel));

        TextView tv = new TextView(this);
        tv.setText((on ? "● " : "○ ") + m.label);
        tv.setTextColor(on ? Skin.c(R.color.leaf) : Skin.c(R.color.text));
        tv.setTextSize(14);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

        row.setOnClickListener(v -> {
            model.switchMode(m);
            Toast.makeText(this, "布局：" + m.label, Toast.LENGTH_SHORT).show();
            setContentView(build());
        });

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(6);
        row.setLayoutParams(p);
        return row;
    }

    private View widgetRow(final int id) {
        String[] names = DesktopView.WIDGET_NAMES;
        boolean on = model.hasWidget(id);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackgroundColor(on ? Skin.c(R.color.card) : Skin.c(R.color.panel));
        TextView tv = new TextView(this);
        tv.setText((on ? "☑ " : "☐ ") + names[id]);
        tv.setTextColor(on ? Skin.c(R.color.leaf) : Skin.c(R.color.text));
        tv.setTextSize(14);
        row.addView(tv);
        row.setOnClickListener(v -> {
            model.toggleWidget(id);
            setContentView(build());
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(6);
        row.setLayoutParams(p);
        return row;
    }

    private View toggleRow(String label, boolean on, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackgroundColor(on ? Skin.c(R.color.card) : Skin.c(R.color.panel));
        TextView tv = new TextView(this);
        tv.setText((on ? "☑ " : "☐ ") + label);
        tv.setTextColor(on ? Skin.c(R.color.leaf) : Skin.c(R.color.text));
        tv.setTextSize(14);
        row.addView(tv);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(6);
        row.setLayoutParams(p);
        row.setOnClickListener(l);
        return row;
    }

    private View btn(String label, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Skin.c(R.color.text));
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(13), dp(10), dp(13));
        tv.setBackground(Skin.round(Skin.c(R.color.card), 12f));
        tv.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        tv.setLayoutParams(p);
        return tv;
    }

    private TextView section(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Skin.c(R.color.leaf));
        tv.setTextSize(13);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(20); p.bottomMargin = dp(6);
        tv.setLayoutParams(p);
        return tv;
    }
}
