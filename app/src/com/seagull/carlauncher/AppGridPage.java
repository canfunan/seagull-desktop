package com.seagull.carlauncher;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 全部应用页（第 2 页）—— 复刻 Vivo 智能车载 CarLauncher 的 AppListFragment。
 *
 * 参考里右侧是「横向滑动的多屏应用网格」（HorizontalSlipView 包 HorizontalSlipItemView），
 * 这里按同一视觉落成单页纵向滚动的图标网格：每格 = 46dp 图标 + 12sp 标签，
 * 5 列，无卡片底、无顶栏（参考的应用列表也没有标题栏）。
 *
 * 顶部只留一行轻量工具条（标题 + 搜索 + 设置）—— 参考把这两个入口放在列表页，
 * 不占主界面的垂直空间，正是用户要的「别再顶着一个大黑条」。
 *
 * 交互对齐参考：单击进应用、长按弹菜单。
 *   · 单击 → Host.onOpenApp(pkg, slot)：把应用投进桌面画布并导焦，
 *     应用画面渲染在桌面自己的窗口里，所以底部导航栏天然保留（导航栏不变），
 *     点导航栏「首页」即回桌面；
 *   · 长按 → 选投左/右画布、固定到底部 Dock、应用信息。
 */
public final class AppGridPage extends LinearLayout {

    public interface Host {
        /** 打开应用：slot=1 左画布（地图位）/ 2 右画布（音乐位）。 */
        void onOpenApp(String pkg, int slot);
        /** 固定到底部 Dock。 */
        void onPinApp(String pkg);
        void onSearch();
        void onSettings();
    }

    private static final int COLS = 5;

    private final Context ctx;
    private final Host host;
    private final LinearLayout grid;
    private final TextView count;

    public AppGridPage(Context c, Host host) {
        super(c);
        this.ctx = c;
        this.host = host;
        setOrientation(VERTICAL);
        // 透明：壁纸由 HomeActivity 铺在 root（批次 CI 反馈：应用列表也要透出壁纸）
        setBackgroundColor(Color.TRANSPARENT);

        /* ---------------- 轻量工具条 ---------------- */
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(12), dp(20), dp(6));

        TextView title = new TextView(c);
        title.setText("全部应用");
        title.setTextColor(Skin.c(R.color.text));
        title.setTextSize(18);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        bar.addView(title);

        count = new TextView(c);
        count.setTextColor(Skin.c(R.color.text_dim));
        count.setTextSize(12);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = dp(10);
        bar.addView(count, clp);

        View spacer = new View(c);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        bar.addView(chip("搜索", v -> { if (host != null) host.onSearch(); }));
        bar.addView(chip("设置", v -> { if (host != null) host.onSettings(); }));
        addView(bar, new LinearLayout.LayoutParams(-1, -2));

        /* ---------------- 网格 ---------------- */
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        grid = new LinearLayout(c);
        grid.setOrientation(VERTICAL);
        grid.setPadding(dp(16), dp(6), dp(16), dp(16));
        sv.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    /** 灌数据：HomeActivity 每次回桌面/全量变更后调用。 */
    public void setApps(List<HomeActivity.AppEntry> list) {
        grid.removeAllViews();
        if (list == null || list.isEmpty()) {
            count.setText("");
            return;
        }
        count.setText("共 " + list.size() + " 个");
        for (int i = 0; i < list.size(); i += COLS) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(HORIZONTAL);
            for (int j = 0; j < COLS; j++) {
                int idx = i + j;
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, dp(104), 1f);
                if (idx < list.size()) row.addView(cell(list.get(idx)), cellLp);
                else row.addView(new View(ctx), cellLp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.bottomMargin = dp(8);
            grid.addView(row, rlp);
        }
    }

    private View cell(final HomeActivity.AppEntry a) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(VERTICAL);
        cell.setGravity(Gravity.CENTER);

        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(a.icon);
        cell.addView(iv, new LinearLayout.LayoutParams(dp(46), dp(46)));

        TextView tv = new TextView(ctx);
        tv.setText(a.label);
        tv.setTextColor(Skin.c(R.color.text));
        tv.setTextSize(12);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(2), dp(6), dp(2), 0);
        cell.addView(tv, new LinearLayout.LayoutParams(-1, -2));

        cell.setOnClickListener(v -> { if (host != null) host.onOpenApp(a.pkg, 2); });
        cell.setOnLongClickListener(v -> { menu(a); return true; });
        return cell;
    }

    private void menu(final HomeActivity.AppEntry a) {
        final String[] items = {"放入左画布（地图位）", "放入右画布（音乐位）", "固定到底部 Dock", "应用信息"};
        new AlertDialog.Builder(ctx)
                .setTitle(a.label)
                .setItems(items, (d, which) -> {
                    if (which == 0) { if (host != null) host.onOpenApp(a.pkg, 1); }
                    else if (which == 1) { if (host != null) host.onOpenApp(a.pkg, 2); }
                    else if (which == 2) { if (host != null) host.onPinApp(a.pkg); }
                    else appInfo(a.pkg);
                })
                .show();
    }

    private void appInfo(String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            android.widget.Toast.makeText(ctx, "打不开应用信息：" + t,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private View chip(String label, View.OnClickListener l) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(Skin.c(R.color.text));
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(7), dp(14), dp(7));
        tv.setBackground(Skin.pill(Skin.c(R.color.card)));
        tv.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }
}
