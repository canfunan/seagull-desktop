package com.seagull.carlauncher;

import android.app.Activity;
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
import android.util.TypedValue;
import android.view.Gravity;
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
 *   + 常驻底部导航栏（DockBar：首页/地图/音乐/其他/退出），三页共用同一条
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

    private static final int PAGE_POSTER = 0;
    private static final int PAGE_MAIN = 1;
    private static final int PAGE_APPS = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private PagerView pager;
    private DockBar dock;
    private WallpaperPage poster;
    private AppGridPage appsPage;
    private PipBoard pip;
    private MiniPlayer mini;
    private IslandView island;
    private TextView pipBox;
    private String lastSkin = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lastSkin = skinSignature();
        selfReport();
        setContentView(buildUi());
        refreshMini();
        ui.postDelayed(tick, 30_000L);
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
            android.util.Log.i("SeagullDiag",
                    "dataDir=" + dir.getAbsolutePath()
                    + " | xml=" + xml.getAbsolutePath()
                    + " exists=" + xml.exists()
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

    @Override protected void onResume() {
        super.onResume();
        // 从设置页 / 壁纸选择回来：配置变了就整屏重画一次
        model.load();
        String sig = skinSignature();
        if (!sig.equals(lastSkin)) {
            lastSkin = sig;
            Skin.apply(model);
            model.loadApps();
            // 旧 pip 的待跑 redeploy/selfHeal 摘掉：setContentView 会造新的
            // PipBoard，旧的没人 onDestroy，回调会对已废弃的树自愈
            if (pip != null) pip.cancelPending();
            setContentView(buildUi());
        } else {
            if (pip != null) pip.onResume();
            if (appsPage != null) appsPage.setApps(loadApps(this));
            if (dock != null) dock.applySkin();
            if (poster != null) { poster.applySkin(); poster.tick(); }
        }
        if (island != null) {
            island.bind(model);
            island.setVisibility(model.islandEnabled ? View.VISIBLE : View.GONE);
        }
        if (model != null && model.keepScreenOn) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        Weather.arm(this);          // 20 分钟一次；数据过期时立刻取
        Lyrics.start(this);         // 一秒一跳，组件条/菜园的歌词都从它出
        Lyrics.removeSink(islandSink);
        Lyrics.addSink(islandSink); // sink 恒为同一实例：CopyOnWrite 集合会永久持死
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
                + "|" + SysOps.clockHHmm().substring(0, 2);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (pip != null) pip.onDestroy();   // 只断 Surface，VD 留给 MirrorHost 常驻
        Lyrics.removeSink(islandSink);      // sink 保活纪律：Activity 走了一定摘掉
        super.onDestroy();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (poster != null) poster.tick();
            if (island != null && model != null) island.bind(model);
            if (pip != null) pip.updateHintsPublic();
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
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    /** 底部导航栏尺寸对齐参考 carmode_dock_height：74dp 条 + 10dp 离屏留白（浮动圆角条）。 */
    private static final int DOCK_H = 74;
    private static final int DOCK_SIDE = 20;
    private static final int DOCK_BOTTOM = 10;

    private int dockH() { return dp(DOCK_H + DOCK_BOTTOM); }

    /* ------------------------- UI 构建 ------------------------- */

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        applyWallpaper(root);

        // 三页横向分页（复刻参考的 CustomViewPager：左滑壁纸 / 中主界面 / 右全部应用）
        pager = new PagerView(this);
        poster = new WallpaperPage(this, model);
        FrameLayout mainPage = buildMainPage();
        appsPage = new AppGridPage(this, this);
        appsPage.setApps(loadApps(this));
        pager.addView(poster);
        pager.addView(mainPage);
        pager.addView(appsPage);
        pager.setOnPageChanged(index -> onPageChanged(index));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, -1);
        plp.bottomMargin = dockH();   // 常驻导航栏不被翻页内容盖住
        root.addView(pager, plp);

        // 常驻底部导航栏（复刻 carmode_dock_view：首页｜地图/音乐/其他｜退出）
        dock = new DockBar(this, this::onDock);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-1, dp(DOCK_H), Gravity.BOTTOM);
        dlp.leftMargin = dp(DOCK_SIDE);
        dlp.rightMargin = dp(DOCK_SIDE);
        dlp.bottomMargin = dp(DOCK_BOTTOM);
        root.addView(dock, dlp);

        // 野菜岛：顶部居中的胶囊，浮在桌面上
        island = new IslandView(this);
        island.setOnClickListener(v -> startActivity(new Intent(this, SettingsSectionActivity.class)
                .putExtra(SettingsSectionActivity.EXTRA_SECTION, "island")));
        island.setVisibility(model != null && model.islandEnabled ? View.VISIBLE : View.GONE);
        root.addView(island, new FrameLayout.LayoutParams(dp(model == null ? 320 : model.islandWidth),
                dp(40), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

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
        syncDock(DockBar.HOME);
        return root;
    }

    /** 主界面（第 1 页）：PipBoard 双画布（地图 + 音乐）+ 底部常驻媒体条。 */
    private FrameLayout buildMainPage() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Skin.c(R.color.ground));

        pip = new PipBoard(this, slot -> PipBoard.showPicker(this, slot, pip), true, model);
        // 点画布 = 进入该应用：铺满内容区 + 底栏高亮跟随（导航栏原地不动）
        pip.setOnEnterSlot(this::expandSlot);
        // 批次 AD：画布直接铺到内容区底部（与底栏零缝隙）；只有媒体条显形时才让出 36dp
        page.addView(pip, new FrameLayout.LayoutParams(-1, -1));

        // 常驻媒体条：层级在画布之上，没媒体会话时整条隐藏
        mini = new MiniPlayer(this);
        mini.setOnInfoClick(this::focusMediaCanvas);
        page.addView(mini, new FrameLayout.LayoutParams(-1, dp(36), Gravity.BOTTOM));
        return page;
    }

    /**
     * MiniPlayer 空白区点击：媒体源正在画布里 → 导焦到那块画布（不新开页面）；
     * 媒体源在后台（没进画布）→ 什么都不做，不挤占当前应用。
     */
    private void focusMediaCanvas() {
        MediaListenerService.Snap s = MediaListenerService.snap();
        if (s == null || s.pkg == null || s.pkg.isEmpty()) return;
        if (pip == null) return;
        if (s.pkg.equals(PipBoard.loadPkg(this, 1))) pip.focusSlot(1);
        else if (s.pkg.equals(PipBoard.loadPkg(this, 2))) pip.focusSlot(2);
    }

    /** 刷媒体条：没会话 → 整条隐藏不占位。 */
    private void refreshMini() {
        if (mini == null) return;
        MediaListenerService.refresh(this);
        mini.bind(MediaListenerService.snap());
        applyMainInsets();
    }

    /**
     * 媒体条显形时画布让出 36dp，隐藏时归零 —— 这条底边距就是用户看到的
     * 「底栏和画中画之间那条大缝」。没媒体在放的时候必须为 0，画布贴到底栏。
     */
    private void applyMainInsets() {
        if (pip == null || mini == null) return;
        if (!(pip.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) pip.getLayoutParams();
        int want = mini.getVisibility() == View.VISIBLE ? dp(36) : 0;
        if (lp.bottomMargin != want) {
            lp.bottomMargin = want;
            pip.setLayoutParams(lp);
        }
    }

    /**
     * 壁纸铺底 + 背景遮罩。遮罩层加在 root 的最底层，三页内容透明地盖在上面，
     * 只有第 0 页（壁纸桌面）透出壁纸 —— 对齐参考「左滑是壁纸桌面」。
     */
    private void applyWallpaper(FrameLayout root) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        String path = currentWallPath();
        android.graphics.Bitmap bmp = Wallpaper.loadForScreen(this, path, w, h);
        if (bmp == null) {
            root.setBackgroundColor(Skin.c(R.color.ground));
            return;
        }
        android.graphics.drawable.BitmapDrawable bd =
                new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
        bd.setGravity(Gravity.FILL);
        root.setBackground(bd);
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

    /* ------------------------- 底部导航栏 ------------------------- */

    private void onDock(int which) {
        switch (which) {
            case DockBar.HOME:
                // 「首页」= 桌面主页：从单应用模式退回分屏桌面，导航栏一直在原位
                if (pip != null) pip.showSplit();
                setPage(PAGE_MAIN, true);
                enableSwipe(true);
                syncDock(DockBar.HOME);
                break;
            case DockBar.MAP:
                expandSlot(1);
                break;
            case DockBar.MUSIC:
                expandSlot(2);
                break;
            case DockBar.APPS:
                if (pip != null) pip.showSplit();
                setPage(PAGE_APPS, true);
                enableSwipe(true);
                syncDock(DockBar.APPS);
                break;
            case DockBar.EXIT:
            default:
                exitLauncher();
                break;
        }
    }

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
        syncDock(which == 1 ? DockBar.MAP : DockBar.MUSIC);
    }

    private void setPage(int index, boolean animate) {
        if (pager != null) pager.setCurrentItem(index, animate);
    }

    private void enableSwipe(boolean on) {
        if (pager != null) pager.setSwipeEnabled(on);
    }

    /** 翻页时同步导航栏高亮（第 2 页高亮「其他」，其余高亮「首页」）。 */
    private void onPageChanged(int index) {
        // 单应用模式锁了翻页，正常划不动；万一被程序切走，这里兜底退回分屏
        if (index != PAGE_MAIN && pip != null && pip.isSingle()) {
            pip.showSplit();
            enableSwipe(true);
        }
        if (index == PAGE_APPS) syncDock(DockBar.APPS);
        else syncDock(DockBar.HOME);
    }

    private void syncDock(int which) {
        if (dock != null) dock.setActive(which);
    }

    /** 退出桌面：把桌面退到后台，回到系统上一屏。 */
    private void exitLauncher() {
        try {
            moveTaskToBack(true);
        } catch (Throwable t) {
            finish();
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
            // 铺满内容区 = 「切到具体软件界面」，底部导航栏留在原位；应用进程不重启
            pip.showSingle(s);
            pip.deploySlotPublic(s);
            pip.focusSlot(s);
        }
        syncDock(s == 1 ? DockBar.MAP : DockBar.MUSIC);
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

    /** 静态加载器：调用方必须传 Context（未 attach 的 Activity 实例会 NPE 炸进程）。 */
    public static List<AppEntry> loadApps(Context ctx) {
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
        return list;
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
