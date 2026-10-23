package com.seagull.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 桌面主界面 —— 复刻 Vivo 智能车载 CarLauncher 的三页横向桌面。
 *
 * 结构（对齐参考 activity_launcher.xml + carmode_dock_view.xml）：
 *   [0] 壁纸桌面（WallpaperPage）         —— 左滑过来
 *   [1] 主界面（PipBoard 画布：地图 / 音乐）—— 默认页，进应用就是它
 *   [2] 全部应用（AppGridPage）            —— 右滑过来
 *   + 常驻底部导航栏（DockBar：车机玻璃 Dock，媒体控件内嵌其中），三页共用同一条
 *
 * 与参考一致的几条硬约束：
 *   · 没有原生顶栏（参考里主布局顶部只有状态栏留白，没有标题条）——
 *     用户原话「顶栏实在太丑了」，这里整条去掉，管理入口下沉到「全部应用」页；
 *   · 导航栏常驻、不随翻页消失（参考的 carmode dock 就是常驻 overlay）；
 *   · 打开应用后导航栏仍然在：应用画面渲染在桌面自己的画布窗口里，
 *     点导航栏「首页」即回主界面 —— 对应参考的「导航栏不变、点击返回桌面」。
 *
 * 静态 API（PREFS / AppEntry / loadApps / pinStatic）被另外几个文件依赖，保持兼容。
 */
public class HomeActivity extends BaseActivity implements AppGridPage.Host {

    public static final String PREFS = "seagull";

    private static final int PAGE_NEG = 0;
    private static final int PAGE_POSTER = 1;
    private static final int PAGE_MAIN = 2;
    private static final int PAGE_APPS = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private PagerView pager;
    private DockBar dock;
    private WallpaperPage poster;
    private WallpaperVideoView videoLayer;   // 批次 CI：动态壁纸层（root 最底层）
    private AppGridPage appsPage;
    private NegScreenView negPage;
    private android.content.BroadcastReceiver pkgReceiver;
    private PipBoard pip;
    private IslandView island;
    private NavCard navCard;
    private TextView pipBox;
    private VdPipView vdPip;   // 批次 W3：桌面内嵌画中画（车联同款，Activity 内视图）
    private String lastSkin = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install(this);   // O4：兜底器装在一切之前
        if (CrashGuard.consumeRecovery(this)) {
            toast("检测到连续崩溃，画布已恢复默认布局");
        }
        lastSkin = skinSignature();
        carMode = getIntent() != null && getIntent().getBooleanExtra("car_mode", false);
        selfReport();
        // 通知监听自授权（参考实现同款）：底栏音乐条的数据源 —— 没授权系统只返回
        // 空会话列表、横幅整条隐藏。反射直授，系统随即重绑 MediaListenerService，
        // 全程无 UI 无重启。
        MediaListenerService.ensureAccess(this);
        // 授权→重绑是异步的（服务 onListenerConnected 才连上）：首次 bindMedia
        // 多半拿不到数据，2s 后补刷一次（回归修复：横幅不显示）
        ui.postDelayed(this::refreshMini, 2000L);
        KeyMapService.ensureAccess(this);   // 按键映射 root 授权（参考实现同款无障碍通道）
        BallService.ensureAccess(this);     // 悬浮球 overlay root 授权
        if (BallService.isOn(this)) {
            try { startService(new Intent(this, BallService.class)); } catch (Throwable ignore) {}
        }
        KeepAliveService.ensure(this);      // KB18：保活前台服务（常驻通知提进程优先级）
        // 底栏遮盖层（vivo互联车机屏专用）：车机模式（car_mode extra）才起——
        // 盖住 vivo.car.networking 加的 VivoCarToolBar（NAVIGATION_BAR 型 104dp）
        if (carMode && ToolBarCoverService.isOn(this)) {
            try { startService(new Intent(this, ToolBarCoverService.class)); } catch (Throwable ignore) {}
        }
        setContentView(buildUi());
        refreshMini();
        ui.postDelayed(tick, 30_000L);
        ObdEngine.start(this);   // P1：引擎按配置自动启停，进程内线程
        // 审计 M6：装卸/更新应用即时失效 loadApps 缓存（TTL 20s 是兜底）
        android.content.IntentFilter pf = new android.content.IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        pkgReceiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                invalidateAppsCache();
            }
        };
        try {
            registerReceiver(pkgReceiver, pf);
        } catch (Throwable t) {
            android.util.Log.w("SeagullHome", "包广播注册失败", t);
        }
        // 批次 W4：收 hook 进程（高德/网易云）的注入心跳，RootPanel 诊断行用
        HookDiagnostics.register(this);
    }

    /**
     * 自检上报：把持久化真实状态打到 logcat。
     * 用途：root shell 看到的是过滤后的挂载命名空间（/data/data 只有 2 项），
     * 无法从外部判断 SharedPreferences 是否落盘，只能让应用自己报。
     */
    private void selfReport() {
        try {
            java.io.File dir = getFilesDir().getParentFile();
            java.io.File xml = new java.io.File(dir, "shared_prefs/" + PREFS + ".xml");
            // 审计 L12：不再把 dataDir 绝对路径打进 logcat（设备指纹信息），
            // 存档存在性/尺寸 + 配置计数保留 —— 诊断价值不变
            android.util.Log.i("SeagullDiag",
                    "selfReport | xmlExists=" + xml.exists()
                    + (xml.exists() ? " size=" + xml.length() : "")
                    + " | mode=" + model.mode
                    + " dock=" + model.dock.size()
                    + " pinned=" + model.pinned.size()
                    + " widgets=" + model.widgets.size()
                    + " folders=" + model.folders.size()
                    + " apps=" + model.allApps.size());
        } catch (Throwable t) {
            android.util.Log.w("SeagullDiag", "自检上报失败", t);
        }
    }

    /** 批次 CI：退后台停动态壁纸（解码器与电池都不陪跑）。 */
    @Override protected void onPause() {
        super.onPause();
        if (videoLayer != null) videoLayer.pauseForBackground();
    }

    @Override protected void onResume() {
        super.onResume();
        // 批次 W3：画中画嵌在桌面里（车联同款 —— Activity 内视图，非悬浮窗）。
        // root 面板点推荐应用后回桌面，这里把 VdPipView 挂到最上层并开始部署；
        // 从前台 Activity 起锚点无 BAL 限制，也不需要悬浮窗授权。
        syncVdPip();
        // 批次 W：VdPip 自愈（复刻车联 MainActivity.onResume → c.h()）——
        // 任务被系统拉回主屏后，一回桌面就重跑锚点+move-task 搬回画布（幂等）
        VirtualPipController.redeploy(this);
        // 从设置页 / 壁纸选择回来：配置变了就整屏重画一次
        model.load();
        String sig = skinSignature();
        if (!sig.equals(lastSkin)) {
            lastSkin = sig;
            Skin.apply(model);
            model.loadApps();
            // 旧 pip 的待跑 redeploy/selfHeal 摘掉 + 退订 root 事件订阅：
            // setContentView 会造新的 PipBoard，旧订阅不摘的话新板 startWatch
            // 见 subscribed()=true 就挂空转，BUMP 事件全进旧板（持死旧树）
            if (pip != null) pip.detachWatch();
            // 旧 NavCard 的订阅摘掉：AmapNavClient 单例活得比页面久，
            // 不摘的话 buildUi 造的新卡片会跟废弃实例并存（双份回调、持死旧树）
            if (navCard != null) { navCard.detach(); navCard = null; }
            setContentView(buildUi());
            if (pip != null) pip.onResume();   // 新板接线（redeploy/selfHeal/repin/watch）
            syncWallVideo();   // 新视图树没有视频层：按当前 key 重建
        } else {
            if (pip != null) pip.onResume();
            if (appsPage != null) appsPage.setApps(loadApps(this));
            if (dock != null) dock.applySkin();
            if (poster != null) { poster.applySkin(); poster.tick(); poster.syncBlocks(); }
            syncWallVideo();   // 批次 CI：设置页换了动态壁纸，回来即换源
        }
        if (island != null) {
            island.bind(model);
            island.setVisibility(model.islandEnabled ? View.VISIBLE : View.GONE);
        }
        if (navCard != null) navCard.setEnabled(model.navEnabled);   // 设置页回来同步开关
        // K4：绑/解绑高德导航服务（幂等；bindService 异步不卡主线程）。
        // 绑定挂在进程级单例上（与 NavCard 同寿），桌面退后台仍保持连接 ——
        // 回桌面卡片立即有数据；开销只有一条 binder 连接。
        AmapNavClient nav = AmapNavClient.get(this);
        if (model.navEnabled) nav.start(model.navPkg); else nav.stop();
        if (model != null && model.keepScreenOn) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        Weather.arm(this);          // 20 分钟一次；数据过期时立刻取
        // Weather 的定时刷新写的是它自己的模型实例，桌面这份 model 得重读 JSON
        // 才能拿到新 weatherCode/summary（否则壁纸联动/负一屏天气卡一直旧数据）
        Weather.onUpdated = () -> { model.load(); syncWallVideo(); };
        // L1 导航通知横幅：岛内容随解析结果即时刷新
        NavNotiParser.setListener(() -> {
            if (island != null && model != null) island.bind(model);
        });
        Lyrics.start(this);         // 一秒一跳，组件条/极简屏的歌词都从它出
        Lyrics.removeSink(islandSink);
        Lyrics.addSink(islandSink); // sink 恒为同一实例：CopyOnWrite 集合会永久持死
        // 批次 NB：导航信息 sink（NavInfoReceiver 同款保活纪律——先摘后挂防重复）
        if (navCard != null) {
            NavInfoReceiver.removeSink(navCard);
            NavInfoReceiver.addSink(navCard);
            // 批次 NC：高德车机版协议通道 sink（同保活纪律）
            AmapProtocolClient.removeSink(navCard);
            AmapProtocolClient.addSink(navCard);
        }
        refreshMini();              // 媒体条跟着会话状态走（没会话整条隐藏）
        // 常驻画中画被系统拉回主屏时搬回去（节流在 MirrorHost 里）
        MirrorHost.healHome(this);
        TaskEngine.fireDesktop(this);   // 桌面启动触发（同进程只跑一次）
        TaskEngine.arm(this);           // 定时任务每分钟看一眼
    }

    /** 岛上的歌词一秒一跳。 */
    private final Lyrics.Sink islandSink = new Lyrics.Sink() {
        @Override public void onLyric(String[] w, String t, String a) {
            // 展示方（HomeActivity/岛）可能已被销毁：拿不住就别硬塞
            if (isFinishing() || isDestroyed()) return;
            if (model != null && model.islandLyric && island != null) island.bind(model);
            if (poster != null) poster.tick();
        }
    };

    /** 主题 / 壁纸 / 字号 / 画布权重变了才重画，避免每次回桌面都闪一下。 */
    private String skinSignature() {
        if (model == null) return "";
        return model.themeId + "|" + model.customAccent + "|" + model.dayNight
                + "|" + model.wallDay + "|" + model.wallNight + "|" + model.wallDim
                + "|" + model.fontScale + "|" + model.pipWeightA + ":" + model.pipWeightB
                + "|R" + model.themeRadius + "G" + model.themeGlass + "O" + (model.themeOutline ? 1 : 0)
                + "|" + SysOps.clockHHmm().substring(0, 2);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        ObdEngine.stop();                   // P1：桌面走了一定停轮询
        if (negPage != null) negPage.setActive(false);   // 2s root 轮询别带进坟墓
        if (pip != null) pip.onDestroy();   // 只断 Surface，VD 留给 MirrorHost 常驻
        if (navCard != null) navCard.detach();   // 摘导航订阅（AmapNavClient 单例存活）
        Lyrics.removeSink(islandSink);      // sink 保活纪律：Activity 走了一定摘掉
        if (navCard != null) NavInfoReceiver.removeSink(navCard);   // 批次 NB 同纪律
        if (navCard != null) AmapProtocolClient.removeSink(navCard);   // 批次 NC 同纪律
        Weather.onUpdated = null;           // K1：静态钩子别持死旧桌面
        NavNotiParser.setListener(null);    // L1：同上
        try { if (pkgReceiver != null) unregisterReceiver(pkgReceiver); } catch (Throwable ignore) {}
        HookDiagnostics.unregister(this);
        super.onDestroy();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (poster != null) poster.tick();
            if (island != null && model != null) island.bind(model);
            if (pip != null) pip.updateHintsPublic();
            if (dock != null) dock.tick();
            refreshMini();   // 30s 一次兜底扫描；播放态变化有 MediaController 回调即时刷
            // 周期自愈：应用中途把新 activity 落到默认屏（盖住桌面=返回也没用）时，
            // 30s 内搬回虚拟屏。onResume 也会立即跑一次。
            if (pip != null) pip.selfHealPublic();
            ui.postDelayed(this, 30_000L);
        }
    };

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (pip != null) pip.onActivityResult(req, res, data);   // 录屏授权只在投影兜底路径才弹
        if (poster != null) poster.onActivityResult(req, res, data);   // 批次 CI：小组件 bind 回执
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    /** 底部导航栏（批次 CH 车机玻璃 Dock）：悬浮贴底，四周留边 + 大圆角，66dp 条高。 */
    private static final int DOCK_H = 66;
    private static final int DOCK_SIDE = 10;
    private static final int DOCK_BOTTOM = 8;

    private int dockH() { return dp(DOCK_H + DOCK_BOTTOM); }

    /** 批次 BB：true = 底部导航栏已收起（单应用模式自动收，回桌面自动展开）。 */
    private boolean dockCollapsed;
    /** 车机投屏模式（vivo互联车机屏）：部署时带 car_mode=true —— 板上车机屏
     *  会做 VD 二次合成（每帧多一层全屏合成），叠加 vivo 的 ScreenRecord 采集
     *  就是双倍合成、掉帧卡顿。车机屏默认纯桌面（壁纸+底栏+音乐条，轻量），
     *  要挂车机 app 再手动开板。 */
    private boolean carMode;

    /* ------------------------- UI 构建 ------------------------- */

    /** 画中画开关 ↔ VdPipView 挂载同步。skin 重建后 getParent()==null 会重新挂。
     *  NB18：开关持久化 —— 进程被杀重开后这里从 prefs 恢复 true，视图重建、
     *  Surface 回调重新建屏+搬任务，画中画自愈（无需再删了重开）。 */
    private void syncVdPip() {
        boolean want = VirtualPipController.isEnabled(this);
        if (want && (vdPip == null || vdPip.getParent() == null)) {
            android.view.ViewGroup content = findViewById(android.R.id.content);
            if (content != null) {
                vdPip = new VdPipView(this);
                content.addView(vdPip, vdPip.pipLayout());
            }
        } else if (!want && vdPip != null) {
            android.view.ViewGroup p = (android.view.ViewGroup) vdPip.getParent();
            if (p != null) p.removeView(vdPip);
            vdPip = null;
        }
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        applyWallpaper(root);

        // 四页横向分页（复刻参考的 CustomViewPager）：左滑负一屏/壁纸 / 主界面 / 右全部应用
        // 批次 J：最左加负一屏（聚合卡），首屏仍是主界面
        pager = new PagerView(this);
        negPage = new NegScreenView(this, model);
        poster = new WallpaperPage(this, model);
        FrameLayout mainPage = buildMainPage();
        appsPage = new AppGridPage(this, this);
        appsPage.setApps(loadApps(this));
        pager.addView(negPage);
        pager.addView(poster);
        pager.addView(mainPage);
        pager.addView(appsPage);
        pager.setOnPageChanged(index -> onPageChanged(index));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, -1);
        plp.bottomMargin = dockH();   // 常驻导航栏不被翻页内容盖住
        root.addView(pager, plp);

        // 常驻底部导航栏（批次 CH：车机玻璃 Dock —— 四格菜单+时间 | 应用图标+媒体横幅 | Logo+网络+电量）
        dock = new DockBar(this, model, dockListener);
        dock.setOnBannerClick(this::focusMediaCanvas);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-1, dp(DOCK_H), Gravity.BOTTOM);
        dlp.leftMargin = dp(DOCK_SIDE);
        dlp.rightMargin = dp(DOCK_SIDE);
        dlp.bottomMargin = dp(DOCK_BOTTOM);
        root.addView(dock, dlp);

        // 海鸥岛：顶部居中的胶囊，浮在桌面上
        island = new IslandView(this);
        island.setOnClickListener(v -> startActivity(new Intent(this, SettingsSectionActivity.class)
                .putExtra(SettingsSectionActivity.EXTRA_SECTION, "island")));
        island.setVisibility(model != null && model.islandEnabled ? View.VISIBLE : View.GONE);
        root.addView(island, new FrameLayout.LayoutParams(dp(model == null ? 320 : model.islandWidth),
                dp(40), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        // 顶栏导航卡片（批次 AN）：高德在导航时才出现；无导航 GONE 不占位。
        // 车机纪律：只有信息、没有按钮 —— 司机看的，不是让司机点的。
        // 【批次 NB】位置从左上角搬进 PipBoard 右侧 40% 的上区（用户拍板的布局：
        // 左 60% 画中画1=导航 / 右上导航卡 / 右下画中画2，竖 splitter 可拖）。
        // pip 在 buildMainPage() 里先建（见上），此时 attach 即可；PipBoard 跟着
        // 主界面页翻页，壁纸页不看导航卡是一期已知边界。
        navCard = new NavCard(this);
        navCard.setEnabled(model == null || model.navEnabled);
        // NB15：长按移除卡片 —— 持久化关掉（设置里「导航信息卡片」随时加回）
        navCard.setOnRemoveListener(() -> {
            if (model != null) {
                model.navEnabled = false;
                model.save();
            }
            navCard.setEnabled(false);
            Toast.makeText(this, "导航卡片已移除（设置 → 导航信息卡片 可加回）", Toast.LENGTH_SHORT).show();
        });
        // NB8（回滚 NB6 的错误）：卡片只属于画中画板——用户原话「导航卡片在
        // 画中画2的上面」= PipBoard 右侧 40% 的上区（navHost）。NB6 我误判
        // 「翻页看不到是 bug」挂到 root 全局——那是全局糊脸，用户从没要过。
        // 板外（壁纸页/负一屏/应用页/不开板）不显示，开板才在右上区出现。
        if (pip != null) {
            pip.attachNavCard(navCard);
            // NB12：回家/公司「绑画中画当前挂的哪个导航 app 就起哪个」
            navCard.setNavPkgProvider(pip::currentNavPkg);
        }

        // 系统画中画形态下的精简视图（系统 PiP 里只显示这一层）
        pipBox = new TextView(this);
        pipBox.setText("海鸥桌面 · 画中画");
        pipBox.setTextColor(Skin.c(R.color.leaf));
        pipBox.setTextSize(14);
        pipBox.setGravity(Gravity.CENTER);
        pipBox.setBackgroundColor(Skin.c(R.color.ground));
        pipBox.setVisibility(View.GONE);
        root.addView(pipBox, new FrameLayout.LayoutParams(-1, -1));

        pager.setCurrentItem(PAGE_MAIN, false);   // 首屏 = 主界面（地图/音乐）
        return root;
    }

    /** 主界面（第 1 页）：PipBoard 双画布（地图 + 音乐）。媒体控件已内嵌进底部 Dock。 */
    private FrameLayout buildMainPage() {
        FrameLayout page = new FrameLayout(this);
        // 批次 CI：主页透明 —— 壁纸与暗度遮罩从 root 透出来，画中画不再压一块深底
        page.setBackgroundColor(0x00000000);

        pip = new PipBoard(this, slot -> PipBoard.showPicker(this, slot, pip), true, model);
        // 点画布 = 进入该应用：铺满内容区（导航栏原地不动）
        pip.setOnEnterSlot(this::expandSlot);
        // 批次 S：画布键条已移除（桌面底栏为统一底栏）——
        // 退回分屏走 DockBar logo 的 onHome（showSplit + 翻页 + 解锁横滑 + 导航栏展开，语义一致）
        // 批次 S：键条已移除（桌面底栏为统一底栏），onToggleDock 注入一并下线
        // 批次 AD：画布直接铺到内容区底部（与底栏零缝隙）
        // 批次 CI：整卡悬浮 —— 四周 10dp 边距露壁纸，配合 PipBoard 画布圆角
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, -1);
        int pm = dp(10);
        plp.setMargins(pm, pm, pm, pm);
        page.addView(pip, plp);
        return page;
    }

    /**
     * Dock 媒体横幅点击：媒体源正在画布里 → 导焦到那块画布（不新开页面）；
     * 媒体源在后台（没进画布）→ 什么都不做，不挤占当前应用。
     */
    private void focusMediaCanvas() {
        MediaListenerService.Snap s = MediaListenerService.snap();
        if (s == null || s.pkg == null || s.pkg.isEmpty()) return;
        if (pip == null) return;
        if (s.pkg.equals(PipBoard.loadPkg(this, 1))) pip.focusSlot(1);
        else if (s.pkg.equals(PipBoard.loadPkg(this, 2))) pip.focusSlot(2);
    }

    /** 刷媒体横幅（内嵌在底部 Dock 中段）：没会话 → 整条隐藏不占位。 */
    private void refreshMini() {
        if (dock == null) return;
        MediaListenerService.refresh(this);
        MediaListenerService.Snap s = MediaListenerService.snap();
        // 挂载哪个播放器就控制哪个（批次 MB）：优先画布里槽位上的音乐包，
        // 没有（音乐 app 没挂画布/从桌面图标开的）→ 当前会话兜底
        String target = mountedMusicPkg();
        dock.bindMedia(s, target);
    }

    /** 画布槽位（1/2）上正挂着的音乐包；没有返回 null。 */
    private String mountedMusicPkg() {
        for (int slot : new int[]{1, 2}) {
            try {
                String p = PipBoard.loadPkg(this, slot);
                if (p != null && MediaListenerService.isMusicPkg(p)) return p;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 壁纸铺底 + 背景遮罩。遮罩层加在 root 的最底层，页内容透明地盖在上面：
     * 第 0 页（壁纸桌面）与第 1 页（画中画，批次 CI 起透壁纸）都能看到壁纸。
     */
    private void applyWallpaper(FrameLayout root) {
        boolean hasVideo = model.wallVideo != null && !model.wallVideo.isEmpty();
        if (hasVideo) {
            // 动态壁纸在播时静态大图不再解码（盖住了还白占几十 MB 内存）
            root.setBackgroundColor(Skin.c(R.color.ground));
        } else {
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            String path = currentWallPath();
            android.graphics.Bitmap bmp = Wallpaper.loadForScreen(this, path, w, h);
            if (bmp == null) {
                root.setBackgroundColor(Skin.c(R.color.ground));
            } else {
                android.graphics.drawable.BitmapDrawable bd =
                        new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
                bd.setGravity(Gravity.FILL);
                root.setBackground(bd);
            }
        }
        // 批次 CI：动态壁纸层（视频循环静音）压在静态底之上、遮罩与三页之下
        videoLayer = null;
        if (hasVideo) {
            videoLayer = new WallpaperVideoView(this, model.wallVideo);
            root.addView(videoLayer, 0, new FrameLayout.LayoutParams(-1, -1));
        }
        if (model.wallDim > 0) {
            View dim = new View(this);
            dim.setBackgroundColor((model.wallDim * 255 / 100) << 24);
            dim.setClickable(true);      // 挡住壁纸，防止穿透点击
            root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    /** 当前该用哪张：浅色用白天图，深色优先夜间图。 */
    private String currentWallPath() {
        if (model == null) return "";
        if (Skin.isLight(model)) return model.wallDay;
        return model.wallNight.isEmpty() ? model.wallDay : model.wallNight;
    }

    /**
     * 动态壁纸层同步（批次 CI）：onResume 幂等对齐 —— 开了就挂上（root 最底层，
     * 遮罩之下）、换源就 setPath、关了就摘层。皮肤重建路径由 applyWallpaper 负责。
     * 批次 K1：联动开着时优先播天气包视频（后台解析，解析中不动现有层），
     * 包里没匹配再回落手动设置的动态壁纸。
     */
    private void syncWallVideo() {
        String want = model.wallVideo == null ? "" : model.wallVideo;
        if (model.wallWeatherLink) {
            final String key = model.weatherCode + "|" + model.dayNight + "|"
                    + model.wallWeatherDir + "|" + want;
            if (!key.equals(wallWxKey)) {
                wallWxKey = key;
                wallWxDone = false;
                WeatherWall.resolve(getApplicationContext(), model, hit -> {
                    // key 变了说明期间又发起了新一轮解析：旧回调的旧包别落层，
                    // 免得短暂显示旧天气视频（新回调回来才该动）
                    if (!key.equals(wallWxKey) || isDestroyed() || isFinishing()) return;
                    wallWxPath = hit;
                    wallWxDone = true;
                    syncWallVideo();       // 回主线程后重新对齐
                });
            }
            if (!wallWxDone) return;       // 解析中：保持现状，别闪
            if (wallWxPath != null) { alignVideoLayer(wallWxPath); return; }
        }
        alignVideoLayer(want);
    }

    /** videoLayer 幂等对齐（批次 CI 原逻辑抽平）：空源摘层，非空挂层/换源。 */
    private void alignVideoLayer(String want) {
        // 桌面已销毁/重建中：别再往已拆的根上挂解码器（Weather 回调可能在死后才到）
        if (isDestroyed() || isFinishing()) return;
        if (want.isEmpty()) {
            if (videoLayer != null) {
                FrameLayout p = (FrameLayout) videoLayer.getParent();
                if (p != null) p.removeView(videoLayer);
                videoLayer = null;
            }
            return;
        }
        if (videoLayer == null || videoLayer.getParent() == null) {
            if (poster == null) return;
            FrameLayout root = (FrameLayout) poster.getRootView();
            videoLayer = new WallpaperVideoView(this, want);
            root.addView(videoLayer, 0, new FrameLayout.LayoutParams(-1, -1));
        } else {
            videoLayer.setPath(want);
        }
    }

    /** K1 联动解析缓存：key=code|dayNight|dir|manual，path=null 表示包里没货。 */
    private String wallWxKey = null;
    private String wallWxPath = null;
    private boolean wallWxDone = true;

    /* ------------------------- 底部导航栏 ------------------------- */

    /**
     * 玻璃 Dock 回调（批次 CH）：四格菜单 = 全部应用页 ⇄ 桌面主页切换；
     * Dock 应用按「默认开在几号窗口」路由（1/2 号窗口走画布，其余直接启动）；
     * Logo = 回桌面主页。
     */
    private final DockBar.Listener dockListener = new DockBar.Listener() {
        @Override public void onApps() {
            if (pip != null) pip.showSplit();
            boolean toApps = pager == null || pager.getCurrentItem() != PAGE_APPS;
            enableSwipe(true);
            setDockCollapsed(false);
            setPage(toApps ? PAGE_APPS : PAGE_MAIN, true);
        }
        @Override public void onApp(LauncherModel.App a) {
            if (a == null || a.pkg == null || a.pkg.isEmpty()) return;
            int slot = model == null ? 0 : model.windowOf(a.pkg);
            if (slot > 0) {
                PipBoard.savePkg(HomeActivity.this, slot, a.pkg);
                expandSlot(slot);
            } else {
                launch(a.pkg);
            }
        }
        /** 长按 Dock 图标 = 管理入口（对齐 DesktopView dockMenu 口径）：
         *  换默认窗口 / 全屏打开 / 从 Dock 拿下来。 */
        @Override public void onAppLongPress(LauncherModel.App a) {
            if (a == null || a.pkg == null || model == null) return;
            int cur = model.windowOf(a.pkg);
            String[] opts = {
                    (cur == 1 ? "● " : "○ ") + "默认开在 1 号窗口",
                    (cur == 2 ? "● " : "○ ") + "默认开在 2 号窗口",
                    "全屏打开（当前窗口留着）",
                    "替换应用…",
                    "从 Dock 拿下来"
            };
            new AlertDialog.Builder(HomeActivity.this)
                    .setTitle(a.label)
                    .setItems(opts, (d, which) -> {
                        switch (which) {
                            case 0: model.setDockWindow(a.pkg, cur == 1 ? 0 : 1); break;
                            case 1: model.setDockWindow(a.pkg, cur == 2 ? 0 : 2); break;
                            case 2: model.setDockWindow(a.pkg, 0); launch(a.pkg); break;
                            case 3: replaceDockApp(a); break;
                            default: model.toggleDock(a.key()); break;
                        }
                        if (dock != null) dock.bind(model);
                    })
                    .show();
        }
        /** 长按 Dock 图标「替换应用」：原地换人，顺序位次不变（批次 CI）。
         *  位次按「生效列表」（没自定义过时是默认推荐），否则空 dock 里 indexOf=-1
         *  会静默失败 —— 用户反馈过替换点了没反应。 */
        private void replaceDockApp(final LauncherModel.App a) {
            final List<String> keys = new ArrayList<>(model.dockKeys());
            final int i = keys.indexOf(a.key());
            if (i < 0) return;
            final List<HomeActivity.AppEntry> apps = loadApps(HomeActivity.this);
            if (apps.isEmpty()) { toast("没有可用应用"); return; }
            String[] names = new String[apps.size()];
            for (int k = 0; k < apps.size(); k++) names[k] = apps.get(k).label;
            new AlertDialog.Builder(HomeActivity.this)
                    .setTitle("替换成哪个应用？")
                    .setItems(names, (d, w) -> {
                        // 存进 dock 的必须是 App.key()（包名/Activity 类名）：
                        // 早期版本存了裸包名，find() 匹配失败，Dock 重画时整格被跳过
                        //（用户看到的「替换完图标消失」）——按包名解析回全 key 再落
                        String key = null;
                        for (LauncherModel.App ap : model.allApps) {
                            if (ap.pkg.equals(apps.get(w).pkg)) { key = ap.key(); break; }
                        }
                        if (key == null) key = apps.get(w).pkg;
                        if (model.dock.isEmpty()) model.dock.addAll(keys);
                        if (key.equals(a.key()) || model.dock.contains(key)) {
                            toast("它已经在 Dock 里了");
                            return;
                        }
                        model.dock.remove(i);
                        model.dock.add(i, key);
                        model.save();
                        if (dock != null) dock.bind(model);
                    })
                    .show();
        }
        @Override public void onHome() {
            if (pip != null) pip.showSplit();
            setPage(PAGE_MAIN, true);
            enableSwipe(true);
            setDockCollapsed(false);
        }
    };

    /**
     * 切到具体应用界面（批次 AD）：把该槽的画布铺满内容区，底部导航栏原地不动。
     *
     * 关键在「只改布局、不重启应用」：画布本身是应用的虚拟屏镜像，铺满只是把
     * SurfaceView 变大，应用进程一直在跑 —— 所以导航不中断、音乐不暂停；
     * 点导航栏「首页」即 showSplit() 退回桌面主页。
     */
    private void expandSlot(int which) {
        if (pip == null) return;
        setPage(PAGE_MAIN, true);
        enableSwipe(false);              // 单应用模式锁住左右翻页，免得手一划把应用划走
        // 批次 S：导航栏不再收起——桌面底栏为统一底栏（用户反馈），应用画布 = Dock 上方内容区
        pip.showSingle(which);
        String pkg = PipBoard.loadPkg(this, which);
        if (pkg == null || pkg.isEmpty()) {
            PipBoard.showPicker(this, which, pip);   // 空槽：先让用户选应用
        } else {
            pip.focusSlot(which);
            // 【批次 AE】只部署进入的这一个槽。旧代码 deployIfBound() 会把隐藏槽
            // 也部署一遍 —— 隐藏槽没有 Surface，触发 VD 重建把另一个在跑的应用
            // 连屏带任务拆掉（进音乐杀高德 / 进高德杀音乐）。
            pip.deploySlotPublic(which);
            pip.healSlotSoon(which);     // singleTask 拉回默认屏的兜底（学参考 xphook 语义）
        }
    }

    private void setPage(int index, boolean animate) {
        if (pager != null) pager.setCurrentItem(index, animate);
    }

    private void enableSwipe(boolean on) {
        if (pager != null) pager.setSwipeEnabled(on);
    }

    /* ---------------- N1 桌面手势（壁纸页） ----------------
     *  双指下滑 = 熄屏（root input POWER；无 root 静默无效）
     *  单指快速上滑 = 进画中画分屏（PipBoard 长按拖拽调分屏已有，见 PipBoard 触摸区）
     *  只在壁纸页生效：主界面画布是应用自己领地，手势不掺和。 */

    private float gsX0, gsY0, gsX1, gsY1;   // 双指起点（按 pointerId 配对，防先抬一根错位）
    private int gsId0 = -1, gsId1 = -1;
    private float gsPinch0 = -1f;           // 双指初始间距：捏合缩放（间距大变）不触发熄屏
    private float gsSx, gsSy;               // 单指起点
    private long gsSt;
    private boolean gsTwoFired, gsUpFired;

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        if (pager != null && pager.getCurrentItem() == PAGE_POSTER) gestureWatch(ev);
        else { gsTwoFired = false; gsUpFired = false; }
        return super.dispatchTouchEvent(ev);
    }

    /** O1 方控媒体键兜底：media 键被路由到前台窗口（launcher）时转 transportControls。 */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (MediaKeyRouter.handle(event, this)) return true;
        return super.dispatchKeyEvent(event);
    }

    private void gestureWatch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                if (ev.getPointerCount() >= 2) {
                    gsId0 = ev.getPointerId(0);
                    gsId1 = ev.getPointerId(1);
                    int i0 = ev.findPointerIndex(gsId0), i1 = ev.findPointerIndex(gsId1);
                    if (i0 >= 0 && i1 >= 0) {
                        gsX0 = ev.getX(i0); gsY0 = ev.getY(i0);
                        gsX1 = ev.getX(i1); gsY1 = ev.getY(i1);
                        gsPinch0 = (float) Math.hypot(gsX1 - gsX0, gsY1 - gsY0);
                    }
                    gsTwoFired = false;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!gsTwoFired && gsId0 >= 0 && gsId1 >= 0) {
                    int i0 = ev.findPointerIndex(gsId0), i1 = ev.findPointerIndex(gsId1);
                    if (i0 >= 0 && i1 >= 0 && ev.getPointerCount() >= 2) {
                        // 两根手指都下滑超过 130px 才算：单指误触第二指点一下不算
                        // 捏合缩放时手指常整体下坠：间距变化大就当捏合，不熄屏
                        float d0 = ev.getY(i0) - gsY0, d1 = ev.getY(i1) - gsY1;
                        float pinch = (float) Math.hypot(
                                ev.getX(i1) - ev.getX(i0), ev.getY(i1) - ev.getY(i0));
                        boolean pinching = Math.abs(pinch - gsPinch0) > 60f;
                        if (d0 > 130f && d1 > 130f && !pinching) {
                            gsTwoFired = true;
                            lockScreen();
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_DOWN:
                gsId0 = -1; gsId1 = -1; gsPinch0 = -1f;
                gsSx = ev.getX(); gsSy = ev.getY();
                gsSt = SystemClock.elapsedRealtime();
                gsUpFired = false;
                break;
            case MotionEvent.ACTION_UP:
                if (!gsUpFired && SystemClock.elapsedRealtime() - gsSt < 420) {
                    float dy = ev.getY() - gsSy, dx = ev.getX() - gsSx;
                    // 快速上甩：竖向 160px+ 且横漂不超过竖程一半
                    if (dy < -160f && Math.abs(dx) < Math.abs(dy) * 0.5f) {
                        gsUpFired = true;
                        setPage(PAGE_MAIN, true);
                    }
                }
                break;
            default:
                break;
        }
    }

    /** 双指下滑熄屏：root input 按 POWER（屏幕亮→灭）。失败静默（无 root 车机）。 */
    private void lockScreen() {
        Toast.makeText(this, "熄屏", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try { Caps.exec("input keyevent KEYCODE_POWER"); } catch (Throwable ignore) { }
        }, "SeagullLock").start();
    }


    /** 翻页回调：单应用模式锁了翻页，万一被程序切走，这里兜底退回分屏。 */
    private void onPageChanged(int index) {
        if (index != PAGE_MAIN && pip != null && pip.isSingle()) {
            pip.showSplit();
            enableSwipe(true);
            setDockCollapsed(false);
        }
        // 批次 J：负一屏可见时才跑开关状态轮询（2s tick 在 setActive 里控）
        if (negPage != null) negPage.setActive(index == PAGE_NEG);
    }

    /* ---------------- 批次 BB：导航栏收起 / 唤出 ---------------- */

    /**
     * 收起 / 唤出底部导航栏（完整形态：边距 + 滑动）。
     *
     * 收起 = 两件事：① 导航栏滑出屏外；② pager 的底边距（dockH）归零 ——
     * 画布跟着长高 → surfaceChanged → VD resize，应用窗口拿到整屏高度，
     * 这正是「进应用要满屏」的落点。回桌面（首页/其他）时反向展开。
     * 不依赖 pip.isSingle()：expandSlot 里先收导航栏后 showSingle，时序无关。
     * 批次 S：唯一调用方是回桌面的展开路径（收起侧随键条一起下线）。
     */
    private void setDockCollapsed(boolean hide) {
        if (dock == null || pager == null) return;
        dockCollapsed = hide;
        FrameLayout.LayoutParams plp = (FrameLayout.LayoutParams) pager.getLayoutParams();
        int want = hide ? 0 : dockH();
        if (plp.bottomMargin != want) {
            plp.bottomMargin = want;
            pager.setLayoutParams(plp);   // 画布变高/回落 → VD resize，应用跟着重排
        }
        dock.setVisibility(View.VISIBLE);
        float ty = hide ? dockH() + dp(12) : 0f;
        if (Math.abs(dock.getTranslationY() - ty) > 1f) {
            dock.animate().translationY(ty).setDuration(180).start();
        }
    }

    /* ------------------------- AppGridPage.Host ------------------------- */

    @Override public void onOpenApp(String pkg, int slot) {
        if (pkg == null || pkg.isEmpty()) return;
        int s = slot == 1 ? 1 : 2;
        PipBoard.savePkg(this, s, pkg);
        setPage(PAGE_MAIN, true);
        enableSwipe(false);
        if (pip != null) {
            // 铺满内容区 = 「切到具体软件界面」；批次 S：导航栏保持常显（统一底栏）
            pip.showSingle(s);
            pip.deploySlotPublic(s);
            pip.focusSlot(s);
        }
    }

    @Override public void onPinApp(String pkg) {
        pinStatic(this, pkg);
        toast("已固定到底部 Dock");
    }

    @Override public void onSearch() {
        startActivity(new Intent(this, SearchActivity.class));
    }

    @Override public void onSettings() {
        startActivity(new Intent(this, SettingsHubActivity.class));
    }

    /** G2 场景一键：快捷栏 @scene: 点击入口。 */
    public void applyScene(String name) {
        LauncherModel.Scene s = model == null ? null : model.findScene(name);
        if (s == null) { toast("场景不存在，请重新选择"); return; }
        if (pip == null) return;
        pip.applyScene(s.pkgA, s.pkgB);
        toast("已应用场景：" + name);
    }

    /** H 批次：行车块定位授权回执 → 壁纸页重仲裁可见性并订阅更新。 */
    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == LocationHelper.REQ_PERM && poster != null) poster.refreshGpsBlock();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    /* ------------------------- 系统画中画（L2） ------------------------- */

    /** 进入系统原生画中画。全系统同时只允许一个 PiP 窗口。 */
    public void enterPip(View source) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            if (!getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_PICTURE_IN_PICTURE)) return;
            PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
            if (Build.VERSION.SDK_INT >= 26) {
                b.setAspectRatio(new android.util.Rational(16, 9));
            }
            if (source != null) {
                Rect r = new Rect();
                source.getGlobalVisibleRect(r);
                b.setSourceRectHint(r);
            }
            enterPictureInPictureMode(b.build());
        } catch (Throwable t) {
            android.util.Log.w("SeagullPip", "进画中画失败", t);
            toast("进画中画失败：" + t);
        }
    }

    @Override public void onPictureInPictureModeChanged(boolean inPip, Configuration cfg) {
        super.onPictureInPictureModeChanged(inPip, cfg);
        if (pager != null) pager.setVisibility(inPip ? View.GONE : View.VISIBLE);
        if (dock != null) dock.setVisibility(inPip ? View.GONE : View.VISIBLE);
        if (island != null) {
            island.setVisibility(inPip || model == null || !model.islandEnabled
                    ? View.GONE : View.VISIBLE);
        }
        if (navCard != null) navCard.setForcedHidden(inPip);
        if (pipBox != null) pipBox.setVisibility(inPip ? View.VISIBLE : View.GONE);
    }

    @Override public void onUserLeaveHint() {
        super.onUserLeaveHint();
        // 用户按 Home 离开桌面时自动进系统画中画。
        // 这与 XposedEntry 里目标应用的 onUserLeaveHint hook 配合：
        // 目标应用进 PiP（SystemUI hook 缩放到 60%），桌面自身也可进 PiP（pipBox 显示精简视图）。
        enterPip(null);
    }

    /* ------------------------- 兼容静态 API ------------------------- */

    /** 应用条目（被 AppListActivity / MirrorActivity / RootPanelActivity / VirtualDisplayActivity 复用）。 */
    public static class AppEntry {
        public final String label;
        public final String pkg;
        public final Drawable icon;
        AppEntry(String label, String pkg, Drawable icon) {
            this.label = label; this.pkg = pkg; this.icon = icon;
        }
    }

    /**
     * 审计 M6：应用列表进程级缓存。queryIntentActivities + 每个应用
     * loadLabel/loadIcon 在低端车机上一次几百毫秒 —— 桌面重建、AppListActivity、
     * VirtualDisplayActivity、PipBoard 抽屉反复全量重拉。缓存 + 20s TTL，
     * 装卸/更新广播即时失效。
     */
    private static volatile List<AppEntry> APPS_CACHE;
    private static volatile long APPS_CACHE_AT;
    private static final long APPS_TTL_MS = 20_000;

    /** 装卸应用后调（包广播 receiver 已挂），下次 loadApps 全量重扫。 */
    public static void invalidateAppsCache() { APPS_CACHE = null; }

    /** 静态加载器：调用方必须传 Context（未 attach 的 Activity 实例会 NPE 炸进程）。 */
    public static List<AppEntry> loadApps(Context ctx) {
        List<AppEntry> hit = APPS_CACHE;
        if (hit != null && System.currentTimeMillis() - APPS_CACHE_AT < APPS_TTL_MS) {
            return new ArrayList<>(hit);   // 拷贝出去：调用方改列表不动缓存
        }
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<android.content.pm.ResolveInfo> ris;
        try {
            ris = pm.queryIntentActivities(main, 0);
        } catch (Throwable t) {
            android.util.Log.w("SeagullApps", "queryIntentActivities 失败", t);
            return new ArrayList<>();
        }
        List<AppEntry> list = new ArrayList<>();
        if (ris == null) return list;
        String self = ctx.getPackageName();
        for (android.content.pm.ResolveInfo ri : ris) {
            if (ri == null || ri.activityInfo == null) continue;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            if (ai == null || self.equals(ai.packageName)) continue;
            try {
                list.add(new AppEntry(String.valueOf(ai.loadLabel(pm)), ai.packageName,
                        ai.loadIcon(pm)));
            } catch (Throwable ignore) {}
        }
        Collections.sort(list, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        APPS_CACHE = list;
        APPS_CACHE_AT = System.currentTimeMillis();
        return new ArrayList<>(list);
    }

    public void launch(String pkg) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Throwable ignore) {}
    }

    /** 静态入口：供其他 Activity 把应用固定到 Dock（走新的 LauncherModel，key = pkg/cls）。 */
    public static void pinStatic(Context ctx, String pkg) {
        try {
            LauncherModel m = new LauncherModel(ctx);
            for (LauncherModel.App a : m.allApps) {
                if (a.pkg.equals(pkg)) {
                    if (!m.dock.contains(a.key())) {
                        m.dock.add(a.key());
                        m.save();
                    }
                    return;
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("SeagullHome", "pinStatic 失败", t);
        }
    }
}
