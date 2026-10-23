package com.seagull.carlauncher;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 顶栏导航信息卡片（批次 AN，K4 R4）。
 *
 * 数据来自 {@link AmapNavClient}（高德官方车联服务，只读）。无导航时整条隐藏，
 * 出现导航随播报实时刷新：转向图标 + 本段剩余距离 + 路名 + 全程剩余。
 * 车机纪律：只有信息、没有按钮 —— 司机看的，不是让司机点的。
 *
 * 转向图标（R5）：自维护 maneuverId → 文本箭头映射，未知 id 一律给直行箭头
 *（缺图给通用箭头），并把 id 打进日志便于后续补全映射。
 */
public final class NavCard extends LinearLayout implements AmapNavClient.Listener,
        NavInfoReceiver.Listener {

    private static final String TAG = "SeagullNavCard";

    private final TextView turn;
    private final TextView dist;
    private final TextView road;
    private final TextView total;

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
        addView(turn);

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
        addView(mid, mlp);

        total = new TextView(act);
        total.setTextColor(0x99FFFFFF);
        total.setTextSize(13);
        total.setGravity(Gravity.RIGHT);
        addView(total, new LinearLayout.LayoutParams(-2, -2));

        setVisibility(GONE);   // 无导航不占位（清 R4：隐藏而非置灰）
        AmapNavClient client = AmapNavClient.get(act);
        client.addListener(this);
        AmapNavClient.NavState last = client.last();   // 页面重建时立即恢复上一次状态
        if (last != null) onNavState(last);
    }

    /** 总开关（设置页"导航信息卡片"）：关了即便高德在导航也不显示。 */
    private volatile boolean enabled = true;
    /** 外部强制隐藏（系统 PiP 模式）：为真时导航事件也保持 GONE。 */
    private volatile boolean forcedHidden = false;

    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) { setVisibility(GONE); return; }
        AmapNavClient.NavState s = AmapNavClient.get(getContext()).last();   // 打开瞬间立即恢复上一次状态
        if (s != null) onNavState(s);
    }

    /** 系统 PiP 进出时由宿主调用；退出后按最近一次导航状态恢复显隐。 */
    public void setForcedHidden(boolean hide) {
        forcedHidden = hide;
        if (hide) { setVisibility(GONE); return; }
        AmapNavClient.NavState s = AmapNavClient.get(getContext()).last();
        if (s != null) onNavState(s);
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
            return;   // 空信息不隐藏（可能车联线的 onNavState 正在显示）
        }
        setVisibility(VISIBLE);
        turn.setText(arrowOf(info.iconId));
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

    /** 批次 NB：导航结束隐藏（与 onNavState(null) 同效——整条 GONE 不占位）。 */
    @Override public void onNavClear() {
        setVisibility(GONE);
    }

    @Override public void onNavState(AmapNavClient.NavState s) {
        if (s == null || !enabled || forcedHidden) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
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

    private static int dp(Activity act, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics()));
    }
}
