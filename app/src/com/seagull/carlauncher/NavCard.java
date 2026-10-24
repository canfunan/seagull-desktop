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
import android.widget.Toast;

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
        NavInfoReceiver.Listener, AmapProtocolClient.Listener {

    private static final String TAG = "SeagullNavCard";
    /** 信息态保鲜：任一来源超过这么久没更新就回快捷态（导航可能已结束但没收到清除）。 */
    private static final long INFO_STALE_MS = 30_000L;

    private final LinearLayout infoMode;   // 信息态容器（turn/mid/total）
    private final LinearLayout quickMode;  // 快捷态容器（回家/公司）
    private final TextView turn;
    private final TextView dist;
    private final TextView road;
    private final TextView total;
    private final TextView growBtn;        // NB15：⤢ 拉大/还原（两档字号，持久化）
    private final TextView quickHome;      // NB15：引用以随 scale 缩放
    private final TextView quickComp;
    /** NB15：卡片字号档（1.0 标准 / 1.45 大）——用户原话「不能拉伸变大」。 */
    private float scale = 1f;
    /** NB15：长按移除回调（HomeActivity 注入：写 model.navEnabled=false + save）。 */
    public interface OnRemoveListener { void onRemove(); }
    private OnRemoveListener removeListener;
    /** 注入画中画当前导航包提供者（HomeActivity 注入 pip::currentNavPkg）。 */
    private java.util.concurrent.Callable<String> navPkgProvider;
    /** NB25：回家/公司绑定应用（auto/amapauto/minimap），HomeActivity 从 model 同步。 */
    private String boundTarget = "auto";
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
        // NB9 复盘：View 没有 setMaxHeight（TextView 才有）——且内容约 97dp
        // 本就在 navHost 的 30% 占比内，WRAP 自适应即可，不硬限。防撑爆靠
        // pipWeightNav=30 + 宽度 MATCH_PARENT（横不溢→不盖 splitter）。

        // NB15：恢复字号档（用户拉大后翻页/重建不回弹）
        try {
            scale = act.getSharedPreferences("seagull", Context.MODE_PRIVATE)
                    .getFloat("navCardScale", 1f);
        } catch (Throwable ignore) {
        }

        turn = new TextView(act);
        turn.setTextColor(0xFFF5C542);          // 转向提示黄：与地图应用的播报色一致
        turn.setGravity(Gravity.CENTER);
        turn.setMinWidth(dp(act, 40));

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(VERTICAL);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        dist = new TextView(act);
        dist.setTextColor(Color.WHITE);
        road = new TextView(act);
        road.setTextColor(0xCCFFFFFF);
        road.setMaxLines(1);
        mid.addView(dist);
        mid.addView(road);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -2, 1f);
        mlp.leftMargin = dp(act, 10);

        total = new TextView(act);
        total.setTextColor(0x99FFFFFF);
        total.setGravity(Gravity.RIGHT);

        // 信息态容器（整体显藏，NavCard 本体只做双态切换）
        infoMode = new LinearLayout(act);
        infoMode.setOrientation(HORIZONTAL);
        infoMode.setGravity(Gravity.CENTER_VERTICAL);
        infoMode.addView(turn);
        infoMode.addView(mid, mlp);
        infoMode.addView(total, new LinearLayout.LayoutParams(-2, -2));
        // NB15：⤢ 拉大/还原 —— 点一下在 标准/大 两档之间切（像画中画拖拽的轻量替身）
        growBtn = new TextView(act);
        growBtn.setTextColor(0x88FFFFFF);
        growBtn.setGravity(Gravity.CENTER);
        growBtn.setPadding(dp(act, 6), 0, dp(act, 2), 0);
        growBtn.setOnClickListener(v -> {
            scale = scale > 1.2f ? 1f : 1.45f;
            try {
                getContext().getSharedPreferences("seagull", Context.MODE_PRIVATE)
                        .edit().putFloat("navCardScale", scale).apply();
            } catch (Throwable ignore) {
            }
            applyScale();
            Toast.makeText(getContext(), scale > 1.2f ? "卡片已拉大" : "卡片已还原", Toast.LENGTH_SHORT).show();
        });
        infoMode.addView(growBtn, new LinearLayout.LayoutParams(-2, -2));
        addView(infoMode, new LinearLayout.LayoutParams(-1, -2));

        // 快捷态容器（NB5：无导航时的回家/公司）
        quickMode = new LinearLayout(act);
        quickMode.setOrientation(HORIZONTAL);
        quickMode.setGravity(Gravity.CENTER_VERTICAL);
        quickHome = quickBtn(act, "🏠 回家", "回家");
        quickComp = quickBtn(act, "🏢 公司", "公司");
        quickMode.addView(quickHome);
        quickMode.addView(quickComp);
        addView(quickMode, new LinearLayout.LayoutParams(-1, -2));

        // NB15：长按整卡 → 移除导航卡片（设置里「导航信息卡片」开关可随时加回）
        setOnLongClickListener(v -> {
            if (removeListener == null) return false;
            new android.app.AlertDialog.Builder(getContext())
                    .setTitle("导航卡片")
                    .setMessage("移除导航卡片？\n（可在 设置 → 区域功能 → 导航信息卡片 重新添加）")
                    .setPositiveButton("移除", (d, w) -> removeListener.onRemove())
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });

        applyScale();   // NB15：构造即按恢复的档位排版
        showQuick();   // 构造即快捷态（常驻）
        AmapNavClient client = AmapNavClient.get(act);
        client.addListener(this);
        AmapNavClient.NavState last = client.last();   // 页面重建时立即恢复上一次状态
        if (last != null) onNavState(last);
        // 批次 NC：高德车机版协议通道（主数据源——字段最全，跟车机屏同源）
        AmapProtocolClient.addSink(this);
    }

    /** NB15：按当前档位重排全部字号/间距（构造与点击切换共用）。 */
    private void applyScale() {
        float s = scale;
        turn.setTextSize(26 * s);
        dist.setTextSize(20 * s);
        road.setTextSize(13 * s);
        total.setTextSize(13 * s);
        growBtn.setTextSize(16 * s);
        growBtn.setText(scale > 1.2f ? "⤡" : "⤢");
        if (quickHome != null) quickHome.setTextSize(15 * s);
        if (quickComp != null) quickComp.setTextSize(15 * s);
        int p = dp(getContext(), 10 * s);
        setPadding(dp(getContext(), 14 * s), p, dp(getContext(), 14 * s), p);
        turn.setMinWidth(dp(getContext(), 40 * s));
    }

    /** NB15：HomeActivity 注入移除回调（写持久化 + GONE）。 */
    public void setOnRemoveListener(OnRemoveListener l) {
        this.removeListener = l;
    }

    /**
     * NB13：按包拼回家/公司的落地方式。
     * · 高德手机版（minimap）：androidamap://poi 实测能起（SplashActivity）
     * · 高德车机版（amapauto）：不认 poi URI（实测 unable to resolve）、
     *   AUTO_WIDGET_GO_HOME 是 vivo protected 广播——两条都死。改为起它的
     *   主界面（用户自己点车机版里的回家/公司按钮），见 launchHome() 特判
     * · 百度 baidumap://map/place/search / 腾讯 qqmap://map/search
     */
    private static String homeUri(String pkg, String keyword) {
        if (pkg == null) return null;
        if ("com.autonavi.amapauto".equals(pkg)) return null;   // 特判：走 launchHome
        if (pkg.startsWith("com.autonavi")) {
            return "androidamap://poi?sourceApplication=seagull&keywords=" + keyword + "&dev=0";
        }
        if (pkg.startsWith("com.baidu")) {
            return "baidumap://map/place/search?query=" + keyword + "&src=seagull";
        }
        if (pkg.startsWith("com.tencent")) {
            return "qqmap://map/search?keyword=" + keyword + "&referer=seagull";
        }
        return null;
    }

    /** NB13：车机版高德（amapauto）的回家/公司——
     * 批次 NB15 三通道（全面反编译 amapauto 9.5.0 坐实）：
     *   ① 官方小部件广播（AutoWidgetReceiver，manifest exported=true 零权限）：
     *      AUTO_WIDGET_GO_HOME / AUTO_WIDGET_GO_COMPANY —— 高德内部解析收藏
     *      地址直接起导航（v2.p/v2.o），和用户按车机屏「回家」小部件完全同款；
     *   ② 协议下发 30010 GoToHomeOrCopType（mGoToPageType 0=家 1=公司）；
     *   ③ 协议下发 30306 PageJumpModel pageType=14（回家/公司 PNAV 页）
     *      + displayID=画中画屏 —— 页面直接落在画中画（用户原话「跳转到
     *      画中画界面拉起导航回家」）。 */
    private static void launchNavHome(Activity act, boolean home) {
        try {
            android.content.Intent i = new android.content.Intent(home
                    ? "com.autonavi.amapauto.AUTO_WIDGET_GO_HOME"
                    : "com.autonavi.amapauto.AUTO_WIDGET_GO_COMPANY");
            i.setPackage("com.autonavi.amapauto");
            act.sendBroadcast(i);
        } catch (Throwable t) {
            Log.w(TAG, "回家广播失败", t);
        }
        int disp = VirtualPipController.isActive() ? VirtualPipController.displayId() : 0;
        boolean proto = AmapProtocolClient.goToHomeOrCompany(home);
        AmapProtocolClient.pageJump(14, disp);   // 落屏到画中画（0=主屏兜底）
        Log.i(TAG, "回家/公司 home=" + home + " 广播已发 协议=" + proto + " -> display " + disp);
    }

    /** NB13 老兜底：车机版高德起主界面（三通道全哑时才走）。 */
    private static void launchAmapAuto(Activity act) {
        try {
            android.content.Intent i = new android.content.Intent();
            i.setComponent(new android.content.ComponentName("com.autonavi.amapauto",
                    "com.autonavi.amapauto.MainMapActivity"));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            act.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "起高德车机版失败", t);
        }
    }

    /** 注入画中画当前导航包提供者（HomeActivity.buildUi 里调）。 */
    public void setNavPkgProvider(java.util.concurrent.Callable<String> p) {
        this.navPkgProvider = p;
    }

    /** NB25：设置里「回家/公司 绑定应用」的当前值（auto/amapauto/minimap），HomeActivity 同步。 */
    public void setBoundTarget(String t) {
        this.boundTarget = t == null ? "auto" : t;
    }

    /**
     * 解析回家/公司的目标应用：
     *   手动绑定（设置）> 画中画当前挂载 > 自动探测（已装车机版优先，NB25：
     *   用户实测「我装的车机版」—— 之前无挂载时兜底只试手机版，按钮直接死）。
     * 没有任何可用导航 app 返回 null。
     */
    private String resolveTarget() {
        if ("com.autonavi.amapauto".equals(boundTarget)) return "com.autonavi.amapauto";
        if ("com.autonavi.minimap".equals(boundTarget)) return "com.autonavi.minimap";
        String navPkg = null;
        if (navPkgProvider != null) {
            try {
                navPkg = navPkgProvider.call();
            } catch (Exception ignore) {
            }
        }
        if (navPkg != null) return navPkg;
        // 自动探测：车机版优先（车机屏幕场景），手机版兜底
        if (installed("com.autonavi.amapauto")) return "com.autonavi.amapauto";
        if (installed("com.autonavi.minimap")) return "com.autonavi.minimap";
        return null;
    }

    private boolean installed(String pkg) {
        try {
            return getContext().getPackageManager().getLaunchIntentForPackage(pkg) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** NB25：自愈式回海鸥桌面（直启 HomeActivity，触发 onResume 自愈链）。 */
    private void goHomeToSeagull(Activity act) {
        try {
            android.content.Intent i = new android.content.Intent(act, HomeActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            act.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "回海鸥桌面失败", t);
        }
    }

    /** NB25：后台起导航→搬画中画→回桌面（parkToPip 内部 sleep/exec）。 */
    private void parkAsync(final Activity act, final String target) {
        new Thread(() -> {
            String r = VirtualPipController.parkToPip(act, target);
            Log.i(TAG, "parkToPip(" + target + ") → " + r);
            // NB27：画中画没开时导航已起在主屏 —— 不回桌面（回桌面会盖住导航）。
            boolean stay = r.contains("画中画未开启");
            act.runOnUiThread(() -> {
                try {
                    android.widget.Toast.makeText(act, r, android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignore) {
                }
                if (!stay) goHomeToSeagull(act);   // 成败都回桌面：成=看见画中画，败=onResume 自愈链兜底
            });
        }, "seagull-navpark").start();
    }

    /** 快捷态按钮：起导航 app 的回家/公司搜索（各厂解析用户收藏地址）。 */
    private TextView quickBtn(Activity act, String label, String keyword) {
        TextView b = new TextView(act);
        b.setText(label);
        b.setTextColor(0xFFF5C542);
        b.setTextSize(15);
        b.setPadding(dp(act, 14), dp(act, 8), dp(act, 14), dp(act, 8));
        b.setOnClickListener(v -> {
            // NB25 重写：手动绑定（设置）> 画中画挂载 > 自动探测（车机版优先）。
            // 车机版：广播+协议起回家/公司导航 → 后台 parkToPip 搬进画中画 → 回桌面。
            //   （之前 pageJump 协议落屏依赖 hook，hook 没生效的设备导航永远
            //    落主屏全屏 —— 用户实测「跳转到应用界面没有进入画中画」）
            // 手机版/其它：URI 起搜索 → 同样 parkToPip。
            String target = resolveTarget();
            if (target == null) {
                Log.w(TAG, "回家/公司：没有任何可用导航应用");
                try {
                    android.widget.Toast.makeText(act, "未装高德（车机版/手机版）",
                            android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignore) {
                }
                return;
            }
            Log.i(TAG, "回家/公司 target=" + target + " bound=" + boundTarget);
            if ("com.autonavi.amapauto".equals(target)) {
                launchNavHome(act, "回家".equals(keyword));   // 广播 + 协议双保险（立即返回）
            } else {
                String uri = homeUri(target, keyword);
                if (uri == null) return;
                try {
                    android.content.Intent i = new android.content.Intent(
                            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri));
                    i.setPackage(target);
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    act.startActivity(i);
                } catch (Throwable t) {
                    Log.w(TAG, "起导航搜索失败: " + keyword, t);
                    return;
                }
            }
            parkAsync(act, target);   // 后台等任务注册 → 搬画中画 → 回桌面
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

    /** 页面销毁时摘订阅（单例活得比页面久）。 */
    public void detach() {
        AmapNavClient.get(getContext()).removeListener(this);
        AmapProtocolClient.removeSink(this);
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
        // NB16：空值不覆盖 —— vivo hook 通道部分帧（只有 icon 没有 dist/路名）不得
        // 擦掉协议源已填的字段（用户实测「只剩 18公里·19分钟一行」的另一个元凶）
        if (m > 0) {
            dist.setText(fmtDist(m));
        } else if (!info.dist.isEmpty() && !info.unit.isEmpty()) {
            dist.setText(info.dist + info.unit);
        }
        // 路名：TITLE_1 优先；转向瞬间（当前路空）给 op + where
        String name = !info.t1.isEmpty() ? info.t1
                : (!info.op.isEmpty() ? info.op + " " + info.where : info.where);
        if (!name.trim().isEmpty()) road.setText(name.trim());
        String tail = !info.op.isEmpty() ? info.op : "";
        if (!info.where.isEmpty()) tail += (tail.isEmpty() ? "" : " · ") + info.where;
        if (!tail.isEmpty()) total.setText(tail);
    }

    /** 批次 NB5：导航结束回快捷态（回家/公司），不再整条隐藏。 */
    @Override public void onNavClear() {
        showQuick();
    }

    /**
     * 批次 NC：高德车机版协议引导信息（AmapProtocolClient → GuideInfoModel
     * 60 字段）。正常两行导航卡片（模仿腾讯 guide.f 契约）：
     *   主行：转向图标 + 下一段距离（大字）+ 下一道路名
     *   次行：全程剩余距离/时间 · ETA · 限速 · 红绿灯 · 电子眼
     * 文本箭头映射（高德协议 icon 是资源名/url，本地没图——一期文本化，
     * 二期按 icon 名映射本地 drawable）。
     */
    @Override public void onGuide(AmapProtocolClient.Guide g) {
        if (g == null || !enabled || forcedHidden) return;
        showInfo();
        // 主行（NB15：对齐官方渲染 —— 反编译 GuideInfoModel_JsonLubeParser +
        // GuideInfoProtocolData 坐实，车机屏渲染的数字其实是 *Auto 现成文案）：
        //   转向箭头 | 距离文案 | 转向词 + 进入路名
        // NB16：三个源（协议/车联/hook）交替推帧、字段互补不全——谁空谁不覆盖，
        // 「空帧擦掉上一帧」就是用户实测「只剩 18公里·19分钟一行」的根因。
        int iconId = g.newIcon != 0 ? g.newIcon : g.icon;
        if (iconId > 0) {
            turn.setText(arrowOf(iconId));
        }
        String word = arrowTextOf(iconId);
        // 距离行（NB17：真实 key segRemainDis/segRemainDisAuto——本段剩余距离，
        // 官方 Parser 第 30/57 行。之前误读 nextSeg*（下下段，常态 0）导致
        // 「即将」和距离空白。官方文案「186米」优先，数字兜底）：
        if (!g.segRemainDisAuto.isEmpty()) {
            dist.setText(g.segRemainDisAuto);
        } else if (g.segRemainDis > 0) {
            dist.setText(fmtDist(g.segRemainDis));   // 官方格式纯距离，不加"后"
        }   // 双空不覆盖——保留上一帧距离
        // 路名回退链：下一道路名 → 当前路名 → 不覆盖
        String roadName = !g.nextRoadName.isEmpty() ? "进入 " + g.nextRoadName
                : (!g.curRoadName.isEmpty() ? g.curRoadName : "");
        if (!roadName.isEmpty() || !word.isEmpty()) {
            road.setText((word.isEmpty() ? "" : word + " ") + roadName);
        }
        // 次行：全程距离 · 时间 · ETA（官方 routeRemainDistanceAuto/routeRemainTimeAuto
        // 文案优先 —— 与车机屏同源，解决「手机 18公里/19分钟 vs 卡 18.1/20」不同步）。
        // NB16：总长超限丢 ETA —— 用户实测「预计01:」被硬截断。
        StringBuilder sb = new StringBuilder();
        if (!g.routeRemainDistanceAuto.isEmpty()) {
            sb.append(g.routeRemainDistanceAuto);
            if (!g.routeRemainTimeAuto.isEmpty()) sb.append(" · ").append(g.routeRemainTimeAuto);
        } else {
            sb.append(fmtDist(g.routeRemainDis));
            if (g.routeRemainTime > 0) sb.append(" · ").append(fmtTime(g.routeRemainTime));
        }
        if (!g.etaText.isEmpty() && sb.length() + g.etaText.length() <= 18) {
            sb.append(" · ").append(g.etaText);
        }
        if (sb.length() > 0) total.setText(sb.toString());
        total.setMaxLines(1);
        total.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }

    /**
     * icon id → 转向词。NB19 全面纠偏 —— 旧表（2/3/4=右转、6/7/8=左转、5/9=掉头）
     * 是 NB12 拍脑袋分的组，与高德真实枚举完全错位，用户实测「左转显示右转」。
     * 权威依据 = 高德官方 AMapNaviIconType（iOS 导航 SDK AMapNaviCommonObj.h
     * 公开枚举，车机版协议 icon 同源 —— 铁证：车机版 GuideInfoProtocolData
     * convertIcon() 把 21/22/24→11、23→12、25/26/28→17、27→18，与本表
     * 11 进入环岛 / 12 驶出环岛 / 17、18 左行环岛逐字吻合）：
     *   1 车图标 | 2 左转 | 3 右转 | 4 左前方 | 5 右前方 | 6 左后方 |
     *   7 右后方 | 8 左转掉头 | 9 直行 | 10 途经点 | 11 进环岛 | 12 出环岛 |
     *   13 服务区 | 14 收费站 | 15 目的地 | 16 隧道 | 17/18 左行环岛 |
     *   19 右转掉头(左行) | 20 顺行 | 29+ 行人通道类
     */
    private static String arrowTextOf(int id) {
        switch (id) {
            case 2: return "左转";
            case 3: return "右转";
            case 4: return "左前方";
            case 5: return "右前方";
            case 6: return "左后方";
            case 7: return "右后方";
            case 8: case 19: return "掉头";
            case 11: case 17: return "进入环岛";
            case 12: case 18: return "驶出环岛";
            case 13: return "服务区";
            case 14: return "收费站";
            case 15: return "到达目的地";
            case 16: return "进入隧道";
            case 1: case 9: case 20: return "直行";
            default: return "";   // 0/1/29+ 无转向词
        }
    }

    /**
     * NB11：协议空帧**不即时回快捷态**——实测协议帧字段不全会让 end 判据飘，
     * 信息态/快捷态来回抢画面（用户实测「一闪一闪」）。导航结束只靠 30s 无
     * 更新超时（showInfo 的 staleTask）回落，稳。
     */
    @Override public void onGuideEnd() {
        // 什么都不做——超时兜底
    }

    @Override public void onNavState(AmapNavClient.NavState s) {
        if (s == null || !enabled || forcedHidden) {
            showQuick();   // NB5：导航结束/关开关回快捷态；关开关时 setEnabled 已 GONE
            return;
        }
        showInfo();
        if (s.maneuverId > 0) turn.setText(arrowOf(s.maneuverId));
        // NB16：空值不覆盖（与 onGuide 同纪律——车联源部分帧不得擦掉协议源已填的字段）
        if (s.segmentRemainM > 0) {
            dist.setText(fmtDist(s.segmentRemainM) + (s.speedKph > 0 ? "  " + s.speedKph + "km/h" : ""));
        }
        // 路名优先当前路，转向瞬间（当前路空）给"进入 + 下一路"
        String name = !s.roadName.isEmpty() ? s.roadName
                : (!s.nextRoadName.isEmpty() ? "进入 " + s.nextRoadName : "");
        if (!name.isEmpty()) road.setText(name);
        if (s.routeRemainM > 0 || s.routeRemainS > 0) {
            total.setText(fmtDist(s.routeRemainM) + " · " + fmtTime(s.routeRemainS));
        }
    }

    /**
     * maneuverId → 箭头。NB19：按高德官方 AMapNaviIconType 全表重排
     * （依据与分组论证见 arrowTextOf 注释）——旧表左右完全颠倒。
     * 29+ 行人/通道类无转向语义，一律直行。
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
            case 2: return "↰";                          // 左转
            case 3: return "↱";                          // 右转
            case 4: return "↖";                          // 左前方
            case 5: return "↗";                          // 右前方
            case 6: return "↙";                          // 左后方
            case 7: return "↘";                          // 右后方
            case 8: case 19: return "⤿";                 // 掉头
            case 11: case 17: return "↻";                // 进入环岛
            case 12: case 18: return "↺";                // 驶出环岛
            case 13: return "P";                         // 服务区
            case 14: return "▣";                         // 收费站
            case 10: case 15: return "⚑";                // 途经点/目的地
            case 1: case 9: case 20: default: return "↑"; // 直行/顺行/未知
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
