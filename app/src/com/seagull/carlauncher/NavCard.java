package com.seagull.carlauncher;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 导航卡片（批次 AN + NB5 双态常驻）。
 *
 * 双态（用户 NB5 拍板「没有导航的时候就显示回家和公司」+「常驻」）：
 *   信息态 —— 导航中：转向图标 + 本段剩余距离 + 路名 + 全程剩余。数据四路并存
 *     （任一来源 30s 内有更新即保持）：高德车机版 a80.k（NB4，字段最全）/
 *     高德手机版 Ucar bs6.a（NB2）/ vivo UCarMapService 归一化（NB）/ 高德车联
 *     官方服务（AN）。
 *   快捷态 —— 无导航（构造/清除/超时）：🏠 回家 / 🏢 公司 两个按钮，点即起高德
 *     POI 搜索（高德解析用户收藏的家/公司地址出卡片一键导航）。
 *
 * 常驻：构造即 VISIBLE（快捷态），不再「无导航 GONE」；setEnabled(false)/
 * forcedHidden（系统 PiP）才整条隐藏。
 */
public final class NavCard extends LinearLayout implements AmapNavClient.Listener,
        NavInfoReceiver.Listener {

    private static final String TAG = "SeagullNavCard";
    /** 信息态保鲜：任一来源超过这么久没更新就回快捷态（导航可能已结束但没收到清除）。 */
    private static final long INFO_STALE_MS = 30_000L;

    private final LinearLayout infoMode;   // 信息态容器（turn/mid/total）
    private final LinearLayout quickMode;  // 快捷态容器（回家/公司）
    private final TextView turn;
    private final TextView dist;
    private final TextView road;
    private final TextView total;
    private final android.os.Handler ui = new android.os.Handler();
    private final Runnable staleTask = this::showQuick;

    public NavCard(Activity act) {
        super(act);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        // 半透明黑胶囊：压在画布/壁纸上都读得清，不抢应用的戏
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6101418);
        bg.setCornerRadius(dp(act, 12));
        setBackground(bg);
        int p = dp(act, 10);
        setPadding(dp(act, 14), p, dp(act, 14), p);

        turn = new TextView(act);
        turn.setTextColor(0xFFF5C542);          // 转向提示黄：与地图应用的播报色一致
        turn.setTextSize(26);
        turn.setGravity(Gravity.CENTER);
        turn.setMinWidth(dp(act, 40));

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(VERTICAL);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        dist = new TextView(act);
        dist.setTextColor(Color.WHITE);
        dist.setTextSize(20);
        road = new TextView(act);
        road.setTextColor(0xCCFFFFFF);
        road.setTextSize(13);
        road.setMaxLines(1);
        mid.addView(dist);
        mid.addView(road);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -2, 1f);
        mlp.leftMargin = dp(act, 10);

        total = new TextView(act);
        total.setTextColor(0x99FFFFFF);
        total.setTextSize(13);
        total.setGravity(Gravity.RIGHT);

        // 信息态容器（整体显藏，NavCard 本体只做双态切换）
        infoMode = new LinearLayout(act);
        infoMode.setOrientation(HORIZONTAL);
        infoMode.setGravity(Gravity.CENTER_VERTICAL);
        infoMode.addView(turn);
        infoMode.addView(mid, mlp);
        infoMode.addView(total, new LinearLayout.LayoutParams(-2, -2));
        addView(infoMode, new LinearLayout.LayoutParams(-1, -2));

        // 快捷态容器（NB5：无导航时的回家/公司）
        quickMode = new LinearLayout(act);
        quickMode.setOrientation(HORIZONTAL);
        quickMode.setGravity(Gravity.CENTER_VERTICAL);
        quickMode.addView(quickBtn(act, "🏠 回家", "回家"));
        quickMode.addView(quickBtn(act, "🏢 公司", "公司"));
        addView(quickMode, new LinearLayout.LayoutParams(-1, -2));

        showQuick();   // 构造即快捷态（常驻）
        AmapNavClient client = AmapNavClient.get(act);
        client.addListener(this);
        AmapNavClient.NavState last = client.last();   // 页面重建时立即恢复上一次状态
        if (last != null) onNavState(last);
    }

    /** 快捷态按钮：起高德 POI 搜索（高德对「回家/公司」关键词解析用户收藏地址）。 */
    private TextView quickBtn(Activity act, String label, String keyword) {
        TextView b = new TextView(act);
        b.setText(label);
        b.setTextColor(0xFFF5C542);
        b.setTextSize(15);
        b.setPadding(dp(act, 14), dp(act, 8), dp(act, 14), dp(act, 8));
        b.setOnClickListener(v -> {
            try {
                android.content.Intent i = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(
                            "androidamap://poi?sourceApplication=seagull&keywords="
                            + keyword + "&dev=0"));
                i.setPackage("com.autonavi.minimap");
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
            } catch (Throwable t) {
                Log.w(TAG, "起高德搜索失败: " + keyword, t);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(act, 8);
        b.setLayoutParams(lp);
        return b;
    }

    /** 切信息态（重启 30s 保鲜）。 */
    private void showInfo() {
        if (enabled && !forcedHidden) setVisibility(VISIBLE);
        if (infoMode != null) infoMode.setVisibility(VISIBLE);
        if (quickMode != null) quickMode.setVisibility(GONE);
        ui.removeCallbacks(staleTask);
        ui.postDelayed(staleTask, INFO_STALE_MS);
    }

    /** 切快捷态（回家/公司）。 */
    private void showQuick() {
        if (enabled && !forcedHidden) setVisibility(VISIBLE);
        ui.removeCallbacks(staleTask);
        if (quickMode != null) quickMode.setVisibility(VISIBLE);
        if (infoMode != null) infoMode.setVisibility(GONE);
    }

    /** 总开关（设置页"导航信息卡片"）：关了即便高德在导航也不显示。 */
    private volatile boolean enabled = true;
    /** 外部强制隐藏（系统 PiP 模式）：为真时导航事件也保持 GONE。 */
    private volatile boolean forcedHidden = false;

    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) { setVisibility(GONE); return; }
        AmapNavClient.NavState s = AmapNavClient.get(getContext()).last();   // 打开瞬间立即恢复上一次状态
        if (s != null) onNavState(s); else showQuick();
    }

    /** 系统 PiP 进出时由宿主调用；退出后按最近一次导航状态恢复显隐。 */
    public void setForcedHidden(boolean hide) {
        forcedHidden = hide;
        if (hide) { setVisibility(GONE); return; }
        AmapNavClient.NavState s = AmapNavClient.get(getContext()).last();
        if (s != null) onNavState(s); else showQuick();
    }

    /** 页面销毁时摘订阅（AmapNavClient 单例活得比页面久）。 */
    public void detach() {
        AmapNavClient.get(getContext()).removeListener(this);
    }

    /**
     * 批次 NB：三地图归一化导航信息（vivo UCarMapService 通道）。
     * 与 onNavState（高德车联官方服务）两条线并存：车联通了看车联的（字段全），
     * 车联没通但地图在导航（vivo 白名单三手机版）看这条。直接渲染四个 TextView，
     * 不绕 NavState —— 这条线没有速度/全程剩余字段，total 行给 op+where 补充。
     */
    @Override public void onNavInfo(NavInfoReceiver.NavInfo info) {
        if (info == null || !enabled || forcedHidden) {
            return;   // 空信息不切态（可能车联线的 onNavState 正在显示）
        }
        showInfo();
        // 转向指示：高德原味 Icon 优先（NB2），回落 OPERATION 文本映射，再回落 id 映射
        boolean iconShown = false;
        if (info.icon != null) {
            try {
                android.graphics.drawable.Drawable d = info.icon.loadDrawable(getContext());
                if (d != null) {
                    d.setBounds(0, 0, dp(getContext(), 30), dp(getContext(), 30));
                    turn.setCompoundDrawables(null, d, null, null);
                    turn.setText("");
                    iconShown = true;
                }
            } catch (Throwable ignore) {
            }
        }
        if (!iconShown) {
            turn.setCompoundDrawables(null, null, null, null);
            turn.setText(!info.op.isEmpty() ? arrowOfOp(info.op) : arrowOf(info.iconId));
        }
        int m = NavInfoReceiver.parseMeters(info.dist, info.unit);
        dist.setText(m > 0 ? fmtDist(m) : info.dist + info.unit);
        // 路名：TITLE_1 优先；转向瞬间（当前路空）给 op + where
        String name = !info.t1.isEmpty() ? info.t1
                : (!info.op.isEmpty() ? info.op + " " + info.where : info.where);
        road.setText(name);
        String tail = !info.op.isEmpty() ? info.op : "";
        if (!info.where.isEmpty()) tail += (tail.isEmpty() ? "" : " · ") + info.where;
        total.setText(tail.isEmpty() ? "" : tail);
    }

    /** 批次 NB5：导航结束回快捷态（回家/公司），不再整条隐藏。 */
    @Override public void onNavClear() {
        showQuick();
    }

    @Override public void onNavState(AmapNavClient.NavState s) {
        if (s == null || !enabled || forcedHidden) {
            showQuick();   // NB5：导航结束/关开关回快捷态；关开关时 setEnabled 已 GONE
            return;
        }
        showInfo();
        turn.setText(arrowOf(s.maneuverId));
        dist.setText(fmtDist(s.segmentRemainM) + (s.speedKph > 0 ? "  " + s.speedKph + "km/h" : ""));
        // 路名优先当前路，转向瞬间（当前路空）给"进入 + 下一路"
        String name = !s.roadName.isEmpty() ? s.roadName
                : (!s.nextRoadName.isEmpty() ? "进入 " + s.nextRoadName : "");
        road.setText(name);
        total.setText("全程剩 " + fmtTime(s.routeRemainS) + " · " + fmtDist(s.routeRemainM));
    }

    /**
     * maneuverId → 箭头。高德转向 id 按"左 6/7/8、右 2/3/4、掉头 5"归类
     *（匝道/环岛等细分先归进左右大组），未知 id 一律直行并记日志（后续补全）。
     */
    /** OPERATION 文本 → 箭头（高德给中文操作词，比 id 映射准）。 */
    private static String arrowOfOp(String op) {
        if (op == null || op.isEmpty()) return "↑";
        if (op.contains("掉头")) return "⤿";
        if (op.contains("左") && op.contains("转")) return "↰";
        if (op.contains("右") && op.contains("转")) return "↱";
        if (op.contains("靠左")) return "↖";
        if (op.contains("靠右")) return "↗";
        if (op.contains("左")) return "↰";
        if (op.contains("右")) return "↱";
        return "↑";
    }

    private static String arrowOf(int id) {
        switch (id) {
            case 2: case 3: case 4: case 12: case 14: return "↗";   // 右转组
            case 5: case 9: return "⤿";                              // 掉头
            case 6: case 7: case 8: case 13: return "↖";             // 左转组
            case 10: return "⇀";                                     // 并入/靠右
            case 11: return "↰";                                     // 靠左
            default:
                if (id > 14) android.util.Log.i(TAG, "未映射转向 id=" + id + "，用直行箭头");
                return "↑";
        }
    }

    private static String fmtDist(int m) {
        if (m <= 0) return "现在";
        if (m < 1000) return m + "米";
        return String.format(java.util.Locale.US, "%.1f公里", m / 1000f);
    }

    private static String fmtTime(int sec) {
        if (sec <= 0) return "-";
        if (sec < 3600) return (sec + 59) / 60 + "分钟";
        return sec / 3600 + "小时" + (sec % 3600 + 59) / 60 + "分";
    }

    private static int dp(Context act, float v) {   // NB7: Activity→Context（onNavInfo 里 getContext() 要用）
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics()));
    }
}
