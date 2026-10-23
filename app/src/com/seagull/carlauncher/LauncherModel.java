package com.seagull.carlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 桌面数据层：应用清单 + 全部配置的单一事实源。
 *
 * 对齐野菜桌面的设置结构（去掉账号与云端组后剩 5 组 / 17 分区）：
 *   桌面       → 布局 | Dock 栏 | 快捷栏 | 菜园 | 小白点
 *   外观       → 主题与壁纸 | 屏幕 | 野菜岛
 *   天气与歌词 → 天气 | 歌词
 *   高级功能   → 窗口 | 触摸 | 自动化任务 | 开机动画
 *   系统与工具 → 系统 | 车机工具 | 关于
 *
 * 所有配置落在 SharedPreferences（文件 HomeActivity.PREFS，主键 json_layout）。
 * UI 只读写这一处，不各自开 prefs 文件。
 */
public final class LauncherModel {

    private static final String TAG = "SeagullModel";
    private static final String PREFS = HomeActivity.PREFS;
    private static final String K_LAYOUT = "json_layout";

    /* ==================== 枚举 ==================== */

    /** 主屏分区模式（桌面自身的摆法，非窗口布局）。 */
    public enum Mode {
        GRID("标准网格"),
        DENSE("紧凑网格"),
        DOCK_ONLY("只留 Dock"),
        WIDGET_TOP("上面组件，下面应用"),
        WIDGET_BOTTOM("主屏下方一条组件"),
        WIDGET_RIGHT("左侧应用，右组件条"),
        TWO_ZONE("主副两栏");

        public final String label;
        Mode(String l) { this.label = l; }
    }

    /** Dock 位置。 */
    public enum DockPos {
        BOTTOM("底部"), LEFT("左侧"), RIGHT("右侧"), HIDDEN("不显示");
        public final String label;
        DockPos(String l) { this.label = l; }
    }

    /** 日夜模式。 */
    public enum DayNight {
        AUTO("跟随系统"), DARK("深色"), LIGHT("浅色");
        public final String label;
        DayNight(String l) { this.label = l; }
    }

    /** 屏幕方向。 */
    public enum Orientation {
        AUTO("跟随系统"), LANDSCAPE("固定横屏"), PORTRAIT("固定竖屏");
        public final String label;
        Orientation(String l) { this.label = l; }
    }

    /** 天气城市来源。 */
    public enum CitySource {
        AUTO_GPS("自动（本机定位）"), AUTO_NET("按网络判断"), MANUAL("手动指定");
        public final String label;
        CitySource(String l) { this.label = l; }
    }

    /** 歌词来源。 */
    public enum LyricSource {
        PLAYER("播放器自带"), LOCAL("只用本地歌词"), ONLINE("在线歌词（酷狗）");
        public final String label;
        LyricSource(String l) { this.label = l; }
    }

    /** 性能档位。 */
    public enum Perf {
        LOW("低配"), MID("中配"), HIGH("高配");
        public final String label;
        Perf(String l) { this.label = l; }
    }

    /** 菜园摆法。 */
    public enum GardenStyle {
        CENTER_CLOCK("居中大钟"),
        LEFT_CLOCK_BOTTOM_LYRIC("左上时钟 + 底部歌词"),
        BIG_CLOCK_DATE("大字时间与日期");
        public final String label;
        GardenStyle(String l) { this.label = l; }
    }

    /** 自动化任务触发条件。 */
    public enum TaskTrigger {
        DESKTOP_START("桌面启动"), SYSTEM_BOOT("系统启动"), TIMER("定时");
        public final String label;
        TaskTrigger(String l) { this.label = l; }
    }

    /** 自动化任务执行动作。 */
    public enum TaskAction {
        DELAY("延迟"), OPEN_PIP("在画中画打开应用"),
        REMOVE_FROM_HOME("移除主屏任务"), OPEN_APP("打开应用");
        public final String label;
        TaskAction(String l) { this.label = l; }
    }

    public static final int COLS_DEFAULT = 5;
    public static final int COLS_DENSE = 6;
    public static final int WALL_LIB_MAX = 24;      // 壁纸库上限（原文案：最多存 24 项）
    public static final int TASK_MAX = 20;          // 原文案：最多 20 条
    public static final int WIDGET_MAX = 4;         // 原文案：组件条最多放 4 个组件
    public static final int QUICKBAR_MAX = 6;       // 快捷栏格数
    public static final int DOCK_MAX = 8;

    /* ==================== 数据结构 ==================== */

    public static final class App {
        public final String pkg;
        public final String cls;      // 可为空（用包名启动）
        public final String label;
        public boolean dock;          // 是否在 Dock 栏

        public App(String pkg, String cls, String label) {
            this.pkg = pkg; this.cls = cls; this.label = label;
        }
        public String key() { return pkg + (cls == null || cls.isEmpty() ? "" : "/" + cls); }
    }

    /** 文件夹：一组应用 + 名字。 */
    public static final class Folder {
        public String name;
        public final List<String> keys = new ArrayList<>();
        public Folder(String name) { this.name = name; }
    }

    /** 快捷栏一格：放应用或功能按钮。 */
    public static final class QuickSlot {
        public String key;        // 应用 key；功能按钮用 @fn:<name>；场景用 @scene:<名字>
        public String label;      // 功能按钮显示名
        public QuickSlot(String key, String label) { this.key = key; this.label = label; }
        public boolean isFunc() { return key != null && key.startsWith("@fn:"); }
    }

    /** G2 场景一键：一键把两块画布布成预设应用组合。 */
    public static final class Scene {
        public String name;       // 展示名（同时是 @scene: key 的后缀）
        public String pkgA;       // 画布 1 应用包名，空=占位
        public String pkgB;       // 画布 2 应用包名，空=占位
        public Scene(String n, String a, String b) { name = n; pkgA = a; pkgB = b; }
    }

    /** 自动化任务：触发条件 → 动作。 */
    public static final class Task {
        public String name;
        public TaskTrigger trigger = TaskTrigger.DESKTOP_START;
        public TaskAction action = TaskAction.OPEN_PIP;
        public String pkg = "";       // 目标应用包名
        public int delayMs = 5000;    // 延迟毫秒（DELAY 及前置延迟）
        public int atMin = -1;         // TIMER 触发：当天第几分钟（0-1439），-1=没定
        public boolean enabled = true;

        public Task(String name) { this.name = name; }
        public String describe() {
            String when = trigger == TaskTrigger.TIMER && atMin >= 0
                    ? String.format(java.util.Locale.getDefault(), "每天 %02d:%02d",
                            atMin / 60, atMin % 60)
                    : trigger.label;
            return when + " → " + (delayMs > 0 ? "延迟 " + (delayMs / 1000) + " 秒 → " : "")
                    + action.label + (pkg.isEmpty() ? "" : "（" + shortName(pkg) + "）");
        }
    }

    /** 壁纸库一项。 */
    public static final class Wall {
        public String name;
        public String path;      // 本机文件路径
        public boolean used;     // 是否正在用
        public Wall(String name, String path) { this.name = name; this.path = path; }
    }

    /* ==================== 状态：应用与桌面 ==================== */

    public final List<App> allApps = new ArrayList<>();
    public final List<String> pinned = new ArrayList<>();     // 主屏固定顺序（key）
    public final List<Folder> folders = new ArrayList<>();
    public final List<String> dock = new ArrayList<>();       // Dock 固定顺序（key）

    /**
     * Dock 生效位次：用户没自定义过（dock 为空）时回默认推荐，与 DockBar 画的一致。
     * 长按替换/拿下来这类「按位次改」的操作必须基于生效位次，否则空列表 indexOf=-1 静默失败。
     */
    public List<String> dockKeys() {
        if (!dock.isEmpty()) return dock;
        String[] prefer = {"com.android.dialer", "com.android.mms", "com.android.chrome",
                "com.android.camera", "com.android.settings"};
        List<String> out = new ArrayList<>();
        for (String p : prefer) {
            for (App a : allApps) {
                if (a.pkg.equals(p)) { out.add(a.key()); break; }
            }
        }
        if (out.isEmpty()) {
            for (int i = 0; i < Math.min(6, allApps.size()); i++) out.add(allApps.get(i).key());
        }
        return out;
    }

    public Mode mode = Mode.GRID;
    public boolean showLabels = true;
    /** 组件条槽位：0=时钟 1=日期 2=电量 3=内存 4=天气 */
    public final List<Integer> widgets = new ArrayList<>();

    /* ==================== 状态：布局（设置→桌面→布局） ==================== */

    /** 窗口布局预设 id（0~14，见 LayoutPresets）。 */
    public int layoutPreset = 0;
    /** 组件条透明度 0~100（往右拉越透明）。 */
    public int widgetStripAlpha = 100;
    /** 自定义布局名（"自己摆的"）。 */
    public String customLayoutName = "";

    /* ==================== 状态：Dock 栏 ==================== */

    public DockPos dockPos = DockPos.BOTTOM;
    public int dockCount = 5;          // 放几个
    public int dockWidth = 76;         // 底部时=高度(dp)
    public int dockGap = 8;            // 间距(dp)
    public int dockIconSize = 44;      // 图标大小(dp)
    public int dockAlpha = 100;        // 透明度 0~100
    public boolean dockAutoHide = false;
    public boolean dockFixed = false;  // 固定（侧栏满了也不收起）
    public boolean dockShowClock = true;
    /** 每个应用默认开在几号窗口（pkg → 1/2）；没有记录 = 全屏直接启动。 */
    public final Map<String, Integer> dockWindow = new LinkedHashMap<>();

    /* ==================== 状态：快捷栏 ==================== */

    public final List<QuickSlot> quickbar = new ArrayList<>();

    /** G2 场景列表（跨布局共享，全局一份）。 */
    public final List<Scene> scenes = new ArrayList<>();

    public Scene findScene(String name) {
        if (name == null) return null;
        for (Scene s : scenes) if (name.equals(s.name)) return s;
        return null;
    }
    /** 每个桌面布局各有一份快捷栏（TODO P0-6：换布局时快捷栏跟着换）。 */
    private final Map<Integer, List<QuickSlot>> quickbarByMode = new LinkedHashMap<>();
    public String quickBtn1 = "";     // 快捷开关 1（@fn:xxx）
    public String quickBtn2 = "";     // 快捷开关 2

    /* ==================== 状态：菜园 ==================== */

    public boolean gardenEnabled = true;
    public int gardenDim = 60;                 // 壁纸压暗 0~100
    public GardenStyle gardenStyle = GardenStyle.CENTER_CLOCK;

    /* ==================== 状态：小白点 ==================== */

    public boolean ballEnabled = false;
    public int ballX = 40;      // 位置记在本机（-1 = 默认左下角）
    public int ballY = -1;
    public int ballSize = 48;
    public int ballAlpha = 90;  // 悬浮球透明度 20~100

    /* ==================== 状态：外观 ==================== */

    public String themeId = Theme.DEFAULT_ID;   // 见 Theme（默认「苹果互联」）
    public String customAccent = "";           // 自定义主题色 #RRGGBB
    public int themeRadius = 100;              // N2 主题包：圆角全局系数 50~150%
    public int themeGlass = 0;                 // N2：卡片玻璃 0~80（额外透明度）
    public boolean themeOutline = false;       // N2：全局描边
    /* M2 本地音乐：重启后播放列表与进度保留（BACKLOG #5 验收项）。
     * musicQueue 存路径数组（恢复时对 MusicLib 扫描结果按路径重挂），musicIdx
     * 是断点曲序，musicPos 是断点秒数。 */
    public String musicQueueJson = "";         // JSON 数组，空 = 没存过
    public int musicIdx = -1;
    public int musicPos = 0;
    public DayNight dayNight = DayNight.DARK;
    public String wallDay = "";                // 白天壁纸路径
    public String wallNight = "";              // 夜间壁纸路径
    public int wallDim = 0;                    // 背景遮罩 0~100
    public String wallVideo = "";              // 动态壁纸视频路径（批次 CI，空 = 静态图）
    public boolean wallWeatherLink = false;    // K1 天气联动：随天气/昼夜自动换动态壁纸
    public String wallWeatherDir = "";         // K1 壁纸包根目录（SAF tree Uri，子目录约定见 WeatherWall）
    /** 壁纸页时钟块：位置比例（0~1，左上角）与字号倍率（批次 CI 可编辑）。 */
    public float wallClockX = 0.5f;
    public float wallClockY = 0.13f;
    public float wallClockScale = 1f;

    /** H 批次行车块（车速/海拔航向/行程）：位置比例 + 缩放 + 开关。默认左下。 */
    public float wallGpsX = 0.16f;
    public float wallGpsY = 0.74f;
    public float wallGpsScale = 1f;
    public boolean wallGpsEnabled = true;

    /** 批次 P OBD2：车辆数据。obdVoltLow 单位 V（电瓶静置正常 12.4+，12.0 以下偏亏）。 */
    public boolean obdEnabled = false;
    public String obdMac = "";
    public int obdIntervalMs = 2000;
    public float obdVoltLow = 12.0f;

    /** I3 倒计时块：目标日配置（yyyy-MM-dd）+ 摆位。未配置时块显示设置提示。 */
    public String cdLabel = "";
    public String cdTarget = "";
    public float wallCdX = 0.84f;
    public float wallCdY = 0.74f;
    public float wallCdScale = 1f;
    public boolean wallCdEnabled = true;

    /** I5 监控块（内存/CPU/存储）：默认关，时钟块菜单开。 */
    public float wallMonX = 0.84f;
    public float wallMonY = 0.13f;
    public float wallMonScale = 1f;
    public boolean wallMonEnabled = false;

    /** M5 桌面播放列表块（点歌/切歌），同块生态机制，默认关。 */
    public float wallMusX = 0.16f;
    public float wallMusY = 0.13f;
    public float wallMusScale = 1f;
    public boolean wallMusEnabled = false;
    /* S2 独立播放器块：壁纸页第七块，专注控制（歌曲/歌词/进度/控制键），默认关。
     * size = scale（捏合 0.5~2.4），color = PlayerSkin 预设下标。 */
    public float wallPlayerX = 0.22f;
    public float wallPlayerY = 0.62f;
    public float wallPlayerScale = 1f;
    public int wallPlayerColor = 0;
    public boolean wallPlayerEnabled = false;
    /* N3 G值表块：默认右上（x=84%，y=28%），跟监控块错开 */
    public float wallGx = 0.84f;
    public float wallGy = 0.28f;
    public float wallGScale = 1f;
    public boolean wallGEnabled = false;

    /** J2 限行卡：城市名 + 周一..周五尾号（"1,6,2,7,3,8,4,9,5,0"）。 */
    public boolean restrictEnabled = false;
    public String restrictCity = "";
    public String restrictTails = "";

    /** J5 目的地卡：[{"n":名称,"lat":..,"lng":..}]，最多 4 条。 */
    public String negDestsJson = "[]";

    /** 导航目的地（高德 GCJ-02 坐标），负一屏与设置页共用。 */
    public static class NegDest {
        public String name = "";
        public double lat, lng;
    }

    public java.util.List<NegDest> dests() {
        java.util.List<NegDest> out = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(negDestsJson);
            for (int i = 0; i < a.length() && i < 4; i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                NegDest d = new NegDest();
                d.name = o.optString("n", "");
                d.lat = o.optDouble("lat", 0);
                d.lng = o.optDouble("lng", 0);
                out.add(d);
            }
        } catch (Throwable ignore) { }
        return out;
    }

    public void setDests(java.util.List<NegDest> list) {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (NegDest d : list) {
                if (a.length() >= 4) break;
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("n", d.name == null ? "" : d.name);
                o.put("lat", d.lat);
                o.put("lng", d.lng);
                a.put(o);
            }
        } catch (Throwable ignore) { }
        negDestsJson = a.toString();
        save();
    }

    public void removeDest(NegDest d) {
        java.util.List<NegDest> list = dests();
        list.removeIf(x -> x.name.equals(d.name) && x.lat == d.lat && x.lng == d.lng);
        setDests(list);
    }
    /** 壁纸页小组件记录（批次 CI）：[{"id":appWidgetId,"x":0~1,"y":0~1}] */
    public String wallWidgetsJson = "[]";
    /** Q 批次：负一屏卡片序/显隐（key 见 NegCards.DEFAULTS；空表=默认序全显）。 */
    public final List<String> negOrder = new ArrayList<>();
    public final List<String> negHidden = new ArrayList<>();
    public boolean wallClockEnabled = true;   // Q 批次：时钟块显隐（组件管理可切换）
    public boolean lxLinkEnabled = true;      // R3：落雪直连广播歌词（默认开，升级零操作）
    public final List<Wall> wallLib = new ArrayList<>();
    public int fontScale = 100;                // 全局字号 50~150

    /* ==================== 状态：屏幕 ==================== */

    public int marginH = 12;        // 屏幕外边距（左右）
    public int marginV = 8;         // 屏幕外边距（上下）
    public int gap = 6;             // 窗口之间的缝
    /** 竖屏单独一份外边距与缝（TODO P0-8 7.7：横竖屏两套布局分开）。 */
    public int portMarginH = 12;
    public int portMarginV = 8;
    public int portGap = 6;
    /** 默认横屏（用户要求：桌面默认横屏；竖屏党可在 设置 → 显示 里改）。 */
    public Orientation orientation = Orientation.LANDSCAPE;
    /** 双画中画左右权重（批次 N：默认 1:1；下一批预设 7:3 / 3:7）。改它 VD 尺寸跟着变，保持 1:1。 */
    public int pipWeightA = 60;   // 批次 NB：默认 60/40（用户拍板——导航在左 60%）
    public int pipWeightB = 40;
    /** 批次 NB：右侧竖排上(导航卡)/下(槽2)占比（100 制，竖 splitter 拖拽持久化）。 */
    public int pipWeightNav = 38;   // NB12：30→38（用户实测：缝隙大、卡片空间小，「占比不到38」要加大）
    public boolean keepScreenOn = false;
    public boolean hideSystemBars = false;
    public boolean topInfoBar = true;          // 顶部信息栏
    public boolean autoNetworkTime = true;

    /** 画布权重钳制：0/负数是配置事故，回落 1（1:1）。 */
    private static int clampWeight(int w) { return w > 0 && w <= 100 ? w : 1; }

    /** 按当前屏幕方向取外边距 / 缝。 */
    public int marginHOf(boolean portrait) { return portrait ? portMarginH : marginH; }
    public int marginVOf(boolean portrait) { return portrait ? portMarginV : marginV; }
    public int gapOf(boolean portrait) { return portrait ? portGap : gap; }

    /* ==================== 状态：野菜岛 ==================== */

    public boolean islandEnabled = false;
    public int islandOffsetY = 0;      // 距顶部（挖孔/圆角）
    public int islandWidth = 320;
    public boolean islandLyric = false;

    /* ==================== 状态：导航卡片（批次 AN） ==================== */

    public boolean navEnabled = true;                  // 顶栏导航卡片开关
    public String navPkg = "com.autonavi.minimap";     // 绑定哪一版高德（手机版/车机版实测后可换）

    /* ==================== 状态：天气 ==================== */

    public CitySource citySource = CitySource.MANUAL;
    public String weatherCity = "";
    public long weatherUpdatedAt = 0L;
    public int weatherCode = -1;            // K1 联动用：当前 WMO 天气码，-1 = 还没取到
    public boolean weatherAuto = true;      // 9.8 每 20 分钟自动更新
    public String weatherSummary = "";      // 「晴 26°」
    public String weatherForecast = "";     // 「明天 多云 20~28°」
    public String weatherError = "";
    public double weatherLat = 0;           // 缓存经纬度，换城市时清 0
    public double weatherLon = 0;

    /* ==================== 状态：歌词 ==================== */

    public LyricSource lyricSource = LyricSource.PLAYER;
    public int lyricLines = 0;          // 0=自动（横屏5/竖屏3）
    public int lyricSize = 100;         // 字号 %（跟随 fontScale）
    public int lyricOffsetMs = 0;       // 对时偏移（提前为负）
    public boolean lyricStatusBar = false;
    public boolean lyricNotification = false;
    public boolean lyricBluetooth = true;
    public int mediaCardLines = 3;
    public final Set<String> lyricHidden = new HashSet<>();   // "这首不显示歌词"

    /* ==================== 状态：窗口 ==================== */

    public Perf perf = Perf.MID;
    public boolean compatMode = false;          // 强制应用可调整大小
    public int bgKeep = 1;                      // 后台保留几个窗口
    public int winDefaultScale = 100;           // 默认显示大小 %
    public final Map<String, Integer> appScale = new LinkedHashMap<>();  // 每应用独立缩放
    public boolean winLockMap = true;           // 地图窗口默认锁定
    public boolean releaseOnLock = false;       // 锁屏后释放画中画
    public boolean pauseOnScreenOff = false;    // 息屏暂停

    /* ==================== 状态：触摸 ==================== */

    public int touchThreshold = 10;      // 防误滑阈值 px
    public int longPressMs = 500;        // 长按判定（300/500）
    public boolean useAccessibility = true;   // 公开版主通道
    public boolean useRootInput = true;       // root 通道
    /** 批次 CD 弃用：命令通道跟手被坐实为「只能点不能滑」根因（su 分段排队），已删 UI。
     *  字段留着只为旧存档兼容，不再参与任何行为。 */
    public boolean touchFollow = true;

    /* ==================== 状态：自动化任务 ==================== */

    public final List<Task> tasks = new ArrayList<>();

    /* ==================== 状态：系统 ==================== */

    public boolean autoHome = true;
    /** 开机后自动恢复上次画布（slot1 恒恢复；slot2 仅开机窗口内放行，批次 CG 手动语义不变） */
    public boolean autoRestore = true;

    /* ==================== 运行时 ==================== */

    private final Context ctx;

    public LauncherModel(Context ctx) {
        this(ctx, true);
    }

    /**
     * @param withApps false = 只读存档，跳过 loadApps()（queryIntentActivities +
     *        每个应用 loadLabel IPC + 全量排序）。每秒一跳的歌词/天气 tick、
     *        只写存档的场景（悬浮球存位置）都用这个重载 —— 枚举一次全机应用
     *        在主线程要几十到几百 ms，HD 车机上直接卡一拍。
     */
    public LauncherModel(Context ctx, boolean withApps) {
        this.ctx = ctx.getApplicationContext();
        if (withApps) loadApps();
        load();
    }

    public Context context() { return ctx; }

    /* ==================== 读取已装应用 ==================== */

    public void loadApps() {
        allApps.clear();
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris;
        try {
            ris = pm.queryIntentActivities(main, PackageManager.MATCH_ALL);
        } catch (Throwable t) {
            Log.w(TAG, "枚举应用失败", t); return;
        }
        Map<String, App> uniq = new LinkedHashMap<>();
        for (ResolveInfo ri : ris) {
            if (ri == null || ri.activityInfo == null) continue;
            String p = ri.activityInfo.packageName;
            String c = ri.activityInfo.name;
            if (p == null || c == null) continue;
            CharSequence lbl = ri.loadLabel(pm);
            App a = new App(p, c, lbl == null ? p : lbl.toString());
            if (!uniq.containsKey(a.key())) uniq.put(a.key(), a);
        }
        allApps.addAll(uniq.values());
        Collections.sort(allApps, (x, y) -> x.label.compareToIgnoreCase(y.label));
    }

    public App find(String key) {
        if (key == null) return null;
        for (App a : allApps) if (a.key().equals(key)) return a;
        return null;
    }

    /** 按包名找应用（Dock 兼容自愈：历史存档里有裸包名 key，find() 匹配不上）。 */
    public App findByPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        for (App a : allApps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    /** 按包名找应用（任务里只存了包名）。找不到就退回包名最后一段。 */
    public String appNameOf(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        for (App a : allApps) if (pkg.equals(a.pkg)) return a.label;
        return shortName(pkg);
    }

    /** 静态版：拿不到应用列表时（Task.describe 在静态上下文里）只截包名尾巴。 */
    static String shortName(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        int i = pkg.lastIndexOf('.');
        return i < 0 ? pkg : pkg.substring(i + 1);
    }

    /**
     * 图标缓存：getApplicationIcon 每调一次都是一趟 Binder + 位图解码，
     * 桌面重画 / 长按拖动 / 组件条刷新全靠它，没缓存时反复吃。
     * 命中时从 ConstantState 取独立副本：共享底层位图（无解码），
     * 各 View 的 bounds 互不干扰（桌面 56dp 与 Dock 44dp 同屏时会打架）。
     */
    private static final android.util.LruCache<String, android.graphics.drawable.Drawable> ICONS =
            new android.util.LruCache<>(96);

    public Drawable icon(App a) {
        if (a == null) return null;
        android.graphics.drawable.Drawable d = ICONS.get(a.pkg);
        if (d != null) {
            android.graphics.drawable.Drawable.ConstantState cs = d.getConstantState();
            return cs != null ? cs.newDrawable(ctx.getResources()) : d;
        }
        try {
            d = ctx.getPackageManager().getApplicationIcon(a.pkg);
        } catch (Throwable t) {
            return null;
        }
        if (d != null) ICONS.put(a.pkg, d);
        return d;
    }

    /**
     * 启动应用。asNewTask 参数保留给调用方，但 NEW_TASK 恒加：ctx 构造时已
     * app 化（见构造），applicationContext 出发不带 NEW_TASK 时
     * startActivity 必抛 AndroidRuntimeException 且被下面的 catch 吞掉，
     * 桌面图标 / Dock / 文件夹点一下静默无响应（批次 T 复盘坐实）。
     */
    public void launch(App a, boolean asNewTask) {
        if (a == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(a.pkg, a.cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "启动失败 " + a.label, t);
        }
    }

    /* ==================== 主屏与文件夹 ==================== */

    /**
     * 主屏可见 key 列表：pinned ∪ 未被文件夹收走的应用（首次自动填满）。
     *
     * pinned 为空时（全在文件夹里或首次启动）自动枚举：文件夹只出一个占位 key，
     * 否则纯文件夹布局会渲染成空桌面。
     */
    public List<String> homeKeys() {
        List<String> out = new ArrayList<>();
        if (!pinned.isEmpty()) {
            for (String k : pinned) if (!out.contains(k)) out.add(k);
            return out;
        }
        // 按文件夹「身份」去重（Folder 未覆写 equals，Set 语义即同一实例）：
        // 旧实现按 f.name 去重，两个同名文件夹只有一个能在桌面占位
        Set<Folder> covered = new java.util.LinkedHashSet<>();
        for (App a : allApps) {
            Folder f = folderOf(a.key());
            if (f != null) {
                if (covered.add(f)) out.add(a.key());
            } else if (!out.contains(a.key())) {
                out.add(a.key());
            }
        }
        return out;
    }

    public Folder folderOf(String key) {
        for (Folder f : folders) if (f.keys.contains(key)) return f;
        return null;
    }

    /** 把 src 合并进 dst（拖拽压合）。dst 若已在某文件夹，则并入该文件夹。 */
    public Folder mergeInto(String dstKey, String srcKey) {
        if (dstKey == null || srcKey == null || dstKey.equals(srcKey)) return null;
        Folder df = folderOf(dstKey);
        if (df == null) {
            df = new Folder("文件夹");
            df.keys.add(dstKey);
            folders.add(df);
        }
        Folder sf = folderOf(srcKey);
        if (sf != null && sf != df) {
            df.keys.addAll(sf.keys);
            folders.remove(sf);
        } else if (!df.keys.contains(srcKey)) {
            df.keys.add(srcKey);
        }
        pinned.remove(srcKey);
        save();
        return df;
    }

    public void renameFolder(Folder f, String name) {
        if (f != null && name != null && !name.trim().isEmpty()) f.name = name.trim();
        save();
    }

    /** 解散文件夹：内容回到主屏，文件夹消失。 */
    public void dissolveFolder(Folder f) {
        if (f == null) return;
        for (String k : f.keys) if (!pinned.contains(k)) pinned.add(k);
        folders.remove(f);
        save();
    }

    public void removeFromFolder(Folder f, String key) {
        if (f == null) return;
        f.keys.remove(key);
        if (!pinned.contains(key)) pinned.add(key);
        if (f.keys.size() <= 1) dissolveFolder(f);
        else save();
    }

    /** 把列表里的一项移进文件夹。 */
    public void addToFolder(Folder f, String key) {
        if (f == null || key == null || f.keys.contains(key)) return;
        Folder old = folderOf(key);
        if (old != null && old != f) old.keys.remove(key);
        f.keys.add(key);
        pinned.remove(key);
        save();
    }

    /* ==================== 自动归类 ==================== */

    /** 分类前缀表：按包名开头归类，命中不了的一律进「其他应用」。 */
    private static final String[][] CATEGORIES = {
            {"系统", "com.android.", "android.", "com.google.android.", "com.android.chrome",
                    "com.mi.", "com.miui.", "com.huawei.", "com.hihonor.", "com.oppo.", "com.vivo.",
                    "com.coloros.", "com.oneplus.", "com.samsung.", "com.meizu.", "com.letv."},
            {"社交", "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.wework",
                    "com.sina.weibo", "com.xingin.xhs", "com.zhihu", "com.douban", "com.tieba"},
            {"影音", "com.tencent.qqlive", "com.qiyi.video", "com.youku.phone", "com.eg.android.AlipayGphone",
                    "com.netease.cloudmusic", "com.kugou", "com.ximalaya.ting.android",
                    "com.smile.gifmaker", "com.ss.android.ugc.aweme", "com.ss.android.article.news"},
            {"导航", "com.autonavi", "com.baidu.BaiduMap", "com.tencent.map", "com.amap.android"},
            {"购物", "com.taobao", "com.jingdong", "com.xunmeng", "com.sankuai", "com.ele.me",
                    "me.ele", "com.succot", "com.yy.mobile"},
            {"游戏", "com.tencent.tmgp", "com.netease.gl", "com.miHoYo", "com.hypergryph", "com.bilibili.game"},
            {"工具", "com.tencent.mm", "com.android.vending", "com.baidu.searchbox",
                    "com.autonavi.amapauto", "com.adobe.reader", "com.estrongs.android.pop",
                    "com.speedsoftware.rootexplorer", "org.videolan.vlc", "com.termux"}
    };

    /** 包名归到哪个分类名。 */
    public static String categoryOf(String pkg) {
        if (pkg == null) return "其他应用";
        for (String[] c : CATEGORIES) {
            for (int i = 1; i < c.length; i++) {
                if (pkg.startsWith(c[i])) return c[0];
            }
        }
        return "其他应用";
    }

    /**
     * 自动归类：按包名前缀把散落应用分成分类文件夹。
     * 已经在文件夹里的不动；只分到 1 个应用的分类自动解散（单应用文件夹没有意义）。
     * 返回本次新建的文件夹数。
     */
    public int autoGroup() {
        Set<String> inFolder = new HashSet<>();
        for (Folder f : folders) inFolder.addAll(f.keys);
        Map<String, Folder> byName = new LinkedHashMap<>();
        for (Folder f : folders) byName.put(f.name, f);

        List<Folder> made = new ArrayList<>();
        for (App a : allApps) {
            if (inFolder.contains(a.key())) continue;
            String name = categoryOf(a.pkg);
            Folder f = byName.get(name);
            if (f == null) {
                f = new Folder(name);
                byName.put(name, f);
                folders.add(f);
                made.add(f);
            }
            f.keys.add(a.key());
            pinned.remove(a.key());
        }

        for (Folder f : made) {
            if (f.keys.size() < 2) dissolveFolderQuiet(f);
        }
        save();
        return made.size();
    }

    /** dissolveFolder 的静默版（自动归类内部用，避免中途反复落盘）。 */
    private void dissolveFolderQuiet(Folder f) {
        for (String k : f.keys) if (!pinned.contains(k)) pinned.add(k);
        folders.remove(f);
    }

    /** 解散全部文件夹（整理页用）。 */
    public void dissolveAllFolders() {
        List<Folder> copy = new ArrayList<>(folders);
        for (Folder f : copy) dissolveFolderQuiet(f);
        save();
    }

    /* ==================== 固定 / 取消 ==================== */

    public void pin(String key)   { if (key != null && !pinned.contains(key)) { pinned.add(key); save(); } }
    public void unpin(String key) { pinned.remove(key); save(); }
    public boolean isPinned(String key) { return pinned.contains(key); }

    public int pinnedIndex(String key) { return pinned.indexOf(key); }

    /** 主屏里挪位置：delta = -1 上移 / +1 下移。到头不动。 */
    public void movePinned(String key, int delta) {
        int i = pinned.indexOf(key);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= pinned.size()) return;
        pinned.remove(i);
        pinned.add(j, key);
        save();
    }

    /** 置顶（自己摆的布局：长按图标就能调顺序）。 */
    public void pinTop(String key) {
        int i = pinned.indexOf(key);
        if (i <= 0) return;
        pinned.remove(i);
        pinned.add(0, key);
        save();
    }

    public void toggleDock(String key) {
        if (key == null) return;
        if (dock.isEmpty()) {
            // 从没自定义过 Dock 时，生效的是默认位次 —— 「拿下来」要从默认集合里减，
            // 直接 add 会变成反向操作（想移除结果塞进去了）
            List<String> eff = new ArrayList<>(dockKeys());
            eff.remove(key);
            dock.addAll(eff);
            save();
            return;
        }
        if (dock.contains(key)) dock.remove(key);
        else if (dock.size() < DOCK_MAX) dock.add(key);
        save();
    }

    public boolean inDock(String key) { return dock.contains(key); }

    /** 该应用默认开在几号窗口（0 = 全屏直接启动）。 */
    public int windowOf(String pkg) {
        Integer v = pkg == null ? null : dockWindow.get(pkg);
        return v == null ? 0 : v;
    }

    /** 设默认窗口；slot <= 0 表示改回全屏。 */
    public void setDockWindow(String pkg, int slot) {
        if (pkg == null) return;
        if (slot <= 0) dockWindow.remove(pkg);
        else dockWindow.put(pkg, slot);
        save();
    }

    /* ==================== 组件条 ==================== */

    /**
     * 组件条槽位：0=时钟 1=日期 2=电量 3=内存 4=天气 5=歌词 6=快捷栏。
     * 5/6 在 DesktopView.makeWidget 里有渲染器（歌词窗格 / QuickBar 展开），
     * 入口却卡在旧实现的 id>4 硬编码上，菜单里点了没反应 = 死按钮（批次 T 坐实）。
     * @return false = 越界 id，或已满 WIDGET_MAX 格放不下
     */
    public boolean toggleWidget(int id) {
        if (id < 0 || id > 6) return false;
        if (widgets.contains(id)) { widgets.remove(id); return true; }
        if (widgets.size() >= WIDGET_MAX) return false;
        widgets.add(id);
        return true;
    }

    public boolean hasWidget(int id) { return widgets.contains(id); }

    /** 换城市：清掉缓存经纬度与旧天气，下次取数重新查地名。 */
    public void setCity(String name) {
        weatherCity = name.trim();
        weatherLat = 0;
        weatherLon = 0;
        weatherUpdatedAt = 0L;
        weatherSummary = "";
        weatherForecast = "";
        weatherError = "";
        weatherCode = -1;
        save();
    }

    /* ==================== 快捷栏 ==================== */

    /** 切换桌面布局：快捷栏按布局各存一份，切回来还是原来那几格。 */
    public void switchMode(Mode m) {
        if (m == null || m == mode) return;
        swapQuickbar(quickbar, quickbarByMode, mode.ordinal());
        mode = m;
        List<QuickSlot> l = quickbarByMode.get(m.ordinal());
        quickbar.clear();
        if (l != null) quickbar.addAll(l);
        save();
    }

    /** 把当前这组快捷栏存进某个布局的抽屉（纯逻辑，见 selfcheck/QuickbarCheck.java）。 */
    static void swapQuickbar(List<QuickSlot> cur, Map<Integer, List<QuickSlot>> store, int key) {
        List<QuickSlot> l = store.get(key);
        if (l == null) {
            l = new ArrayList<>();
            store.put(key, l);
        }
        l.clear();
        l.addAll(cur);
    }

    private void stashQuickbar() { swapQuickbar(quickbar, quickbarByMode, mode.ordinal()); }

    /** G2：全部布局的快捷栏（含当前），场景重命名/删除时用来同步 @scene: key。 */
    public List<List<QuickSlot>> allQuickbars() {
        List<List<QuickSlot>> out = new ArrayList<>();
        out.add(quickbar);
        out.addAll(quickbarByMode.values());
        return out;
    }

    public void setQuickSlot(int idx, String key, String label) {
        while (quickbar.size() <= idx) quickbar.add(new QuickSlot("", ""));
        quickbar.set(idx, new QuickSlot(key == null ? "" : key, label == null ? "" : label));
        save();
    }

    public void clearQuickSlot(int idx) {
        if (idx >= 0 && idx < quickbar.size()) {
            quickbar.set(idx, new QuickSlot("", ""));
            save();
        }
    }

    /* ==================== 壁纸库 ==================== */

    public boolean addWall(String name, String path) {
        if (wallLib.size() >= WALL_LIB_MAX) return false;
        wallLib.add(new Wall(name, path));
        save();
        return true;
    }

    public void removeWall(Wall w) {
        wallLib.remove(w);
        String p = (w == null) ? null : w.path;
        if (p != null) {
            if (p.equals(wallDay)) wallDay = "";
            if (p.equals(wallNight)) wallNight = "";
        }
        save();
        // 库条目删了，盘上的图跟着删；只认本机 walls 目录，外部路径一律不碰
        if (p != null) {
            try {
                java.io.File f = new java.io.File(p);
                java.io.File walls = new java.io.File(context().getFilesDir(), "walls");
                if (f.exists() && walls.equals(f.getParentFile())) f.delete();
            } catch (Throwable ignore) {}
        }
    }

    public void useWall(Wall w, boolean night) {
        if (w == null) return;
        if (night) wallNight = w.path; else wallDay = w.path;
        for (Wall x : wallLib) x.used = false;
        w.used = true;
        save();
    }

    /* ==================== 自动化任务 ==================== */

    public boolean addTask(Task t) {
        if (t == null || tasks.size() >= TASK_MAX) return false;
        tasks.add(t);
        save();
        return true;
    }

    public void removeTask(Task t) { tasks.remove(t); save(); }
    public void updateTask() { save(); }

    /* ==================== 每应用独立缩放 ==================== */

    public int scaleOf(String pkg) {
        Integer v = appScale.get(pkg);
        return v == null ? winDefaultScale : v;
    }

    public void setScale(String pkg, int pct) {
        if (pkg == null) return;
        appScale.put(pkg, pct);
        save();
    }

    public void clearScale(String pkg) {
        appScale.remove(pkg);
        save();
    }

    /* ==================== 持久化 ==================== */

    private SharedPreferences sp() { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /**
     * 审计 M2：存取互斥锁。save() 在调用线程（含 Weather 的网络线程）序列化
     * pinned/dock/widgets/folders 集合，主线程同时拖图标/建文件夹改这些集合
     * 就是 ConcurrentModificationException —— save 被 try 吞掉 = 那次配置静默
     * 丢档。凡序列化集合、批量改集合的路径都持这把锁。
     */
    private final Object IO_LOCK = new Object();
    public Object ioLock() { return IO_LOCK; }

    /**
     * 审计 M3：歌词配置代际。Lyrics.step 每秒一跳，旧实现每跳整份
     * new LauncherModel（全量 SharedPreferences+JSON 解析）只为读几个歌词
     * 字段 —— 每秒一次的解析风暴。改为配置写入方（设置页/load）bumpCfg()，
     * Lyrics 按 cfgVersion() 缓存模型快照，配置没变就一克内存都不花。
     */
    private static final java.util.concurrent.atomic.AtomicInteger CFG_VER =
            new java.util.concurrent.atomic.AtomicInteger();
    public static int cfgVersion() { return CFG_VER.get(); }
    public static void bumpCfg() { CFG_VER.incrementAndGet(); }

    public void save() {
        try {
            String json;
            synchronized (IO_LOCK) {
                JSONObject o = new JSONObject();
                o.put("v", 2);
                /* 桌面 */
                o.put("mode", mode.name());
                o.put("labels", showLabels);
                o.put("pinned", new JSONArray(pinned));
                o.put("dock", new JSONArray(dock));
                o.put("widgets", new JSONArray(widgets));
                JSONArray fs = new JSONArray();
                for (Folder f : folders) {
                    JSONObject fo = new JSONObject();
                    fo.put("name", f.name);
                    fo.put("keys", new JSONArray(f.keys));
                    fs.put(fo);
                }
                o.put("folders", fs);
            /* 布局 */
            o.put("layoutPreset", layoutPreset);
            o.put("stripAlpha", widgetStripAlpha);
            o.put("customLayout", customLayoutName);
            /* Dock */
            o.put("dockPos", dockPos.name());
            o.put("dockCount", dockCount);
            o.put("dockWidth", dockWidth);
            o.put("dockGap", dockGap);
            o.put("dockIcon", dockIconSize);
            o.put("dockAlpha", dockAlpha);
            o.put("dockAutoHide", dockAutoHide);
            o.put("dockFixed", dockFixed);
            o.put("dockClock", dockShowClock);
            JSONArray dw = new JSONArray();
            for (Map.Entry<String, Integer> e : dockWindow.entrySet()) {
                JSONObject o1 = new JSONObject();
                o1.put("p", e.getKey()); o1.put("s", e.getValue());
                dw.put(o1);
            }
            o.put("dockWin", dw);
            /* 快捷栏：每个布局一份 */
            stashQuickbar();
            JSONObject qmaps = new JSONObject();
            for (Map.Entry<Integer, List<QuickSlot>> e : quickbarByMode.entrySet()) {
                JSONArray arr = new JSONArray();
                for (QuickSlot q : e.getValue()) {
                    JSONObject qo = new JSONObject();
                    qo.put("k", q.key == null ? "" : q.key);
                    qo.put("l", q.label == null ? "" : q.label);
                    arr.put(qo);
                }
                qmaps.put(String.valueOf(e.getKey()), arr);
            }
            o.put("quickbars", qmaps);
            o.put("qbtn1", quickBtn1);
            o.put("qbtn2", quickBtn2);
            /* 菜园 */
            o.put("garden", gardenEnabled);
            o.put("gardenDim", gardenDim);
            o.put("gardenStyle", gardenStyle.name());
            /* 小白点 */
            o.put("ball", ballEnabled);
            o.put("ballX", ballX);
            o.put("ballY", ballY);
            o.put("ballSize", ballSize);
            o.put("ballAlpha", ballAlpha);
            /* 外观 */
            o.put("theme", themeId);
            o.put("accent", customAccent);
            o.put("themeRadius", themeRadius);
            o.put("themeGlass", themeGlass);
            o.put("themeOutline", themeOutline);
            o.put("musicQueue", musicQueueJson);
            o.put("musicIdx", musicIdx);
            o.put("musicPos", musicPos);
            o.put("dayNight", dayNight.name());
            o.put("wallDay", wallDay);
            o.put("wallNight", wallNight);
            o.put("wallDim", wallDim);
            o.put("fontScale", fontScale);
            JSONArray wa = new JSONArray();
            for (Wall w : wallLib) {
                JSONObject wo = new JSONObject();
                wo.put("n", w.name); wo.put("p", w.path); wo.put("u", w.used);
                wa.put(wo);
            }
            o.put("wallLib", wa);
            o.put("wallVideo", wallVideo);
            o.put("wallWeatherLink", wallWeatherLink);
            o.put("wallWeatherDir", wallWeatherDir);
            o.put("weatherCode", weatherCode);
            JSONObject wck = new JSONObject();
            wck.put("x", wallClockX); wck.put("y", wallClockY); wck.put("s", wallClockScale);
            o.put("wallClock", wck);
            JSONObject wgp = new JSONObject();
            wgp.put("x", wallGpsX); wgp.put("y", wallGpsY); wgp.put("s", wallGpsScale);
            wgp.put("on", wallGpsEnabled);
            o.put("wallGps", wgp);
            o.put("obdEnabled", obdEnabled);
            o.put("obdMac", obdMac);
            o.put("obdIntervalMs", obdIntervalMs);
            o.put("obdVoltLow", obdVoltLow);
            JSONObject wcd = new JSONObject();
            wcd.put("label", cdLabel); wcd.put("target", cdTarget);
            wcd.put("x", wallCdX); wcd.put("y", wallCdY); wcd.put("s", wallCdScale);
            wcd.put("on", wallCdEnabled);
            o.put("wallCd", wcd);
            JSONObject wmo = new JSONObject();
            wmo.put("x", wallMonX); wmo.put("y", wallMonY); wmo.put("s", wallMonScale);
            wmo.put("on", wallMonEnabled);
            o.put("wallMon", wmo);
            JSONObject wmu = new JSONObject();
            wmu.put("x", wallMusX); wmu.put("y", wallMusY); wmu.put("s", wallMusScale);
            wmu.put("on", wallMusEnabled);
            o.put("wallMus", wmu);
            JSONObject wpl = new JSONObject();
            wpl.put("x", wallPlayerX); wpl.put("y", wallPlayerY);
            wpl.put("s", wallPlayerScale);
            wpl.put("c", wallPlayerColor);
            wpl.put("on", wallPlayerEnabled);
            o.put("wallPlayer", wpl);
            JSONObject wg = new JSONObject();
            wg.put("x", wallGx); wg.put("y", wallGy); wg.put("s", wallGScale);
            wg.put("on", wallGEnabled);
            o.put("wallG", wg);
            o.put("restrictOn", restrictEnabled);
            o.put("restrictCity", restrictCity);
            o.put("restrictTails", restrictTails);
            o.put("negDests", negDestsJson);
            o.put("wallWidgets", wallWidgetsJson);
            o.put("negOrder", new JSONArray(negOrder));
            o.put("negHidden", new JSONArray(negHidden));
            o.put("wallClockEnabled", wallClockEnabled);
            o.put("lxLinkEnabled", lxLinkEnabled);
            /* 屏幕 */
            o.put("marginH", marginH);
            o.put("marginV", marginV);
            o.put("gap", gap);
            o.put("portMarginH", portMarginH);
            o.put("portMarginV", portMarginV);
            o.put("portGap", portGap);
            o.put("orientation", orientation.name());
            o.put("pipWeightA", pipWeightA);
            o.put("pipWeightB", pipWeightB);
            o.put("pipWeightNav", pipWeightNav);
            o.put("keepOn", keepScreenOn);
            o.put("hideBars", hideSystemBars);
            o.put("infoBar", topInfoBar);
            o.put("autoTime", autoNetworkTime);
            /* 野菜岛 */
            o.put("island", islandEnabled);
            o.put("islandY", islandOffsetY);
            o.put("islandW", islandWidth);
            o.put("islandLyric", islandLyric);
            /* 导航卡片（批次 AN） */
            o.put("navOn", navEnabled);
            o.put("navPkg", navPkg);
            /* 天气 */
            o.put("citySource", citySource.name());
            o.put("city", weatherCity);
            o.put("cityAt", weatherUpdatedAt);
            o.put("cityAuto", weatherAuto);
            o.put("wSummary", weatherSummary);
            o.put("wForecast", weatherForecast);
            o.put("wError", weatherError);
            o.put("wLat", weatherLat);
            o.put("wLon", weatherLon);
            /* 歌词 */
            o.put("lyricSource", lyricSource.name());
            o.put("lyricLines", lyricLines);
            o.put("lyricSize", lyricSize);
            o.put("lyricOffset", lyricOffsetMs);
            o.put("lyricBar", lyricStatusBar);
            o.put("lyricNoti", lyricNotification);
            o.put("lyricBt", lyricBluetooth);
            o.put("mediaLines", mediaCardLines);
            o.put("lyricHidden", new JSONArray(lyricHidden));
            /* 窗口 */
            o.put("perf", perf.name());
            o.put("compat", compatMode);
            o.put("bgKeep", bgKeep);
            o.put("winScale", winDefaultScale);
            o.put("winLock", winLockMap);
            o.put("relLock", releaseOnLock);
            o.put("pauseOff", pauseOnScreenOff);
            JSONArray sa = new JSONArray();
            for (Map.Entry<String, Integer> e : appScale.entrySet()) {
                JSONObject so = new JSONObject();
                so.put("p", e.getKey()); so.put("s", e.getValue());
                sa.put(so);
            }
            o.put("appScale", sa);
            /* 触摸 */
            o.put("touchTh", touchThreshold);
            o.put("longPress", longPressMs);
            o.put("useA11y", useAccessibility);
            o.put("useRoot", useRootInput);
            o.put("touchFollow", touchFollow);
            /* 任务 */
            JSONArray ta = new JSONArray();
            for (Task t : tasks) {
                JSONObject to = new JSONObject();
                to.put("n", t.name);
                to.put("tr", t.trigger.name());
                to.put("ac", t.action.name());
                to.put("p", t.pkg);
                to.put("d", t.delayMs);
                to.put("at", t.atMin);
                to.put("e", t.enabled);
                ta.put(to);
            }
            o.put("tasks", ta);
            /* 系统 */
            o.put("autoHome", autoHome);
            o.put("autoRestore", autoRestore);
            JSONArray sc = new JSONArray();
            for (Scene s : scenes) {
                JSONObject so = new JSONObject();
                so.put("n", s.name);
                so.put("a", s.pkgA);
                so.put("b", s.pkgB);
                sc.put(so);
            }
            o.put("scenes", sc);
            json = o.toString();
            }

            // apply 异步落盘：配置保存全在主线程（图标拖动、开关切换都在 UI 上），
            // commit() 会同步 fsync，大存档时主线程能卡到几十毫秒（批次 T 坐实）
            sp().edit().putString(K_LAYOUT, json).apply();
        } catch (Throwable t) {
            Log.w(TAG, "保存布局失败", t);
        }
    }

    public void load() {
        String raw = sp().getString(K_LAYOUT, null);
        if (raw == null) { defaults(); return; }
        bumpCfg();   // 审计 M3：整份配置被盘上内容覆盖，歌词模型缓存作废
        // 重读前先清空列表字段，否则 widgets/folders/tasks 会越读越多
        reset();
        try {
            JSONObject o = new JSONObject(raw);
            mode = optEnum(o, "mode", Mode.class, mode);
            showLabels = o.optBoolean("labels", true);
            readInto(o.optJSONArray("pinned"), pinned);
            readInto(o.optJSONArray("dock"), dock);
            // 自愈：历史存档里存过裸包名 dock key（替换应用旧 bug），有全量应用
            // 列表时归一成 App.key()（包名/Activity 类名），各处 find() 才能命中；
            // withApps=false 的实例没有 allApps，跳过（下次带列表的 load 再归一）
            for (int i = 0; i < dock.size(); i++) {
                App a = find(dock.get(i));
                if (a == null) {
                    App b = findByPkg(dock.get(i));
                    if (b != null) dock.set(i, b.key());
                }
            }
            JSONArray ws = o.optJSONArray("widgets");
            // optInt 对缺省项/非数字项返回 0：脏存档里一个 null 就会把时钟
            // 悄悄塞回组件条。范围钳制之外只收 0~6（与 toggleWidget 对齐）
            if (ws != null) {
                for (int i = 0; i < ws.length(); i++) {
                    int id = ws.optInt(i, -1);
                    if (id >= 0 && id <= 6 && !widgets.contains(id)) widgets.add(id);
                }
            }
            JSONArray fs = o.optJSONArray("folders");
            if (fs != null) {
                for (int i = 0; i < fs.length(); i++) {
                    JSONObject fo = fs.optJSONObject(i);
                    if (fo == null) continue;
                    Folder f = new Folder(fo.optString("name", "文件夹"));
                    readInto(fo.optJSONArray("keys"), f.keys);
                    if (!f.keys.isEmpty()) folders.add(f);
                }
            }
            if (widgets.isEmpty()) { widgets.add(0); widgets.add(1); widgets.add(2); widgets.add(3); }

            layoutPreset = o.optInt("layoutPreset", 0);
            widgetStripAlpha = o.optInt("stripAlpha", 100);
            customLayoutName = o.optString("customLayout", "");

            dockPos = optEnum(o, "dockPos", DockPos.class, dockPos);
            dockCount = o.optInt("dockCount", 5);
            dockWidth = o.optInt("dockWidth", 76);
            dockGap = o.optInt("dockGap", 8);
            dockIconSize = o.optInt("dockIcon", 44);
            dockAlpha = o.optInt("dockAlpha", 100);
            dockAutoHide = o.optBoolean("dockAutoHide", false);
            dockFixed = o.optBoolean("dockFixed", false);
            dockShowClock = o.optBoolean("dockClock", true);
            JSONArray dw = o.optJSONArray("dockWin");
            if (dw != null) {
                for (int i = 0; i < dw.length(); i++) {
                    JSONObject o1 = dw.optJSONObject(i);
                    if (o1 == null) continue;
                    String p = o1.optString("p", "");
                    if (!p.isEmpty()) dockWindow.put(p, o1.optInt("s", 0));
                }
            }

            JSONObject qmaps = o.optJSONObject("quickbars");
            if (qmaps != null) {
                java.util.Iterator<String> it = qmaps.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONArray arr = qmaps.optJSONArray(k);
                    List<QuickSlot> l = new ArrayList<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject qo = arr.optJSONObject(i);
                            if (qo == null) continue;
                            l.add(new QuickSlot(qo.optString("k", ""), qo.optString("l", "")));
                        }
                    }
                    try { quickbarByMode.put(Integer.parseInt(k), l); } catch (Throwable ignore) {}
                }
                List<QuickSlot> cur = quickbarByMode.get(mode.ordinal());
                if (cur != null) quickbar.addAll(cur);
            } else {
                // 旧存档：只有一份快捷栏，归到当前布局
                JSONArray qa = o.optJSONArray("quickbar");
                if (qa != null) {
                    for (int i = 0; i < qa.length(); i++) {
                        JSONObject qo = qa.optJSONObject(i);
                        if (qo == null) continue;
                        quickbar.add(new QuickSlot(qo.optString("k", ""), qo.optString("l", "")));
                    }
                }
            }
            quickBtn1 = o.optString("qbtn1", "");
            quickBtn2 = o.optString("qbtn2", "");

            scenes.clear();
            JSONArray sc = o.optJSONArray("scenes");
            if (sc != null) {
                for (int i = 0; i < sc.length(); i++) {
                    JSONObject so = sc.optJSONObject(i);
                    if (so == null) continue;
                    String n = so.optString("n", "");
                    if (n.isEmpty()) continue;
                    scenes.add(new Scene(n, so.optString("a", ""), so.optString("b", "")));
                }
            }

            gardenEnabled = o.optBoolean("garden", true);
            gardenDim = o.optInt("gardenDim", 60);
            gardenStyle = optEnum(o, "gardenStyle", GardenStyle.class, gardenStyle);

            ballEnabled = o.optBoolean("ball", false);
            ballX = o.optInt("ballX", 40);
            ballY = o.optInt("ballY", -1);
            // 复盘 7：save 写 5 字段（含 size/alpha），旧 load 只读 3 个 ——
            // 球的大小/透明度在设置里改了保存，重载就丢回默认
            ballSize = o.optInt("ballSize", 48);
            ballAlpha = o.optInt("ballAlpha", 90);
            ballSize = o.optInt("ballSize", 48);
            ballAlpha = o.optInt("ballAlpha", 90);

            themeId = o.optString("theme", "leaf_shadow");
            customAccent = o.optString("accent", "");
            themeRadius = o.optInt("themeRadius", 100);
            themeGlass = o.optInt("themeGlass", 0);
            themeOutline = o.optBoolean("themeOutline", false);
            musicQueueJson = o.optString("musicQueue", "");
            musicIdx = o.optInt("musicIdx", -1);
            musicPos = o.optInt("musicPos", 0);
            dayNight = optEnum(o, "dayNight", DayNight.class, dayNight);
            wallDay = o.optString("wallDay", "");
            wallNight = o.optString("wallNight", "");
            wallDim = o.optInt("wallDim", 0);
            wallVideo = o.optString("wallVideo", "");
            wallWeatherLink = o.optBoolean("wallWeatherLink", false);
            wallWeatherDir = o.optString("wallWeatherDir", "");
            weatherCode = o.optInt("weatherCode", -1);
            JSONObject wco = o.optJSONObject("wallClock");
            if (wco != null) {
                wallClockX = (float) wco.optDouble("x", 0.5);
                wallClockY = (float) wco.optDouble("y", 0.13);
                wallClockScale = (float) wco.optDouble("s", 1.0);
            }
            JSONObject wgo = o.optJSONObject("wallGps");
            if (wgo != null) {
                wallGpsX = (float) wgo.optDouble("x", 0.16);
                wallGpsY = (float) wgo.optDouble("y", 0.74);
                wallGpsScale = (float) wgo.optDouble("s", 1.0);
                wallGpsEnabled = wgo.optBoolean("on", true);
            }
            obdEnabled = o.optBoolean("obdEnabled", false);
            obdMac = o.optString("obdMac", "");
            obdIntervalMs = o.optInt("obdIntervalMs", 2000);
            obdVoltLow = (float) o.optDouble("obdVoltLow", 12.0);
            JSONObject wco2 = o.optJSONObject("wallCd");
            if (wco2 != null) {
                cdLabel = wco2.optString("label", "");
                cdTarget = wco2.optString("target", "");
                wallCdX = (float) wco2.optDouble("x", 0.84);
                wallCdY = (float) wco2.optDouble("y", 0.74);
                wallCdScale = (float) wco2.optDouble("s", 1.0);
                wallCdEnabled = wco2.optBoolean("on", true);
            }
            JSONObject wmo2 = o.optJSONObject("wallMon");
            if (wmo2 != null) {
                wallMonX = (float) wmo2.optDouble("x", 0.84);
                wallMonY = (float) wmo2.optDouble("y", 0.13);
                wallMonScale = (float) wmo2.optDouble("s", 1.0);
                wallMonEnabled = wmo2.optBoolean("on", false);
            }
            JSONObject wmu2 = o.optJSONObject("wallMus");
            if (wmu2 != null) {
                wallMusX = (float) wmu2.optDouble("x", 0.16);
                wallMusY = (float) wmu2.optDouble("y", 0.13);
                wallMusScale = (float) wmu2.optDouble("s", 1.0);
                wallMusEnabled = wmu2.optBoolean("on", false);
            }
            JSONObject wpl2 = o.optJSONObject("wallPlayer");
            if (wpl2 != null) {
                wallPlayerX = (float) wpl2.optDouble("x", 0.22);
                wallPlayerY = (float) wpl2.optDouble("y", 0.62);
                wallPlayerScale = (float) wpl2.optDouble("s", 1.0);
                wallPlayerColor = wpl2.optInt("c", 0);
                wallPlayerEnabled = wpl2.optBoolean("on", false);
            }
            JSONObject wg2 = o.optJSONObject("wallG");
            if (wg2 != null) {
                wallGx = (float) wg2.optDouble("x", 0.84);
                wallGy = (float) wg2.optDouble("y", 0.28);
                wallGScale = (float) wg2.optDouble("s", 1.0);
                wallGEnabled = wg2.optBoolean("on", false);
            }
            restrictEnabled = o.optBoolean("restrictOn", false);
            restrictCity = o.optString("restrictCity", "");
            restrictTails = o.optString("restrictTails", "");
            negDestsJson = o.optString("negDests", "[]");
            wallWidgetsJson = o.optString("wallWidgets", "[]");
            negOrder.clear();
            JSONArray nOrd = o.optJSONArray("negOrder");
            if (nOrd != null) {
                for (int i = 0; i < nOrd.length(); i++) {
                    String k = nOrd.optString(i, "");
                    if (!k.isEmpty()) negOrder.add(k);
                }
            }
            negHidden.clear();
            JSONArray nHid = o.optJSONArray("negHidden");
            if (nHid != null) {
                for (int i = 0; i < nHid.length(); i++) {
                    String k = nHid.optString(i, "");
                    if (!k.isEmpty()) negHidden.add(k);
                }
            }
            wallClockEnabled = o.optBoolean("wallClockEnabled", true);
            lxLinkEnabled = o.optBoolean("lxLinkEnabled", true);
            fontScale = o.optInt("fontScale", 100);
            JSONArray wa = o.optJSONArray("wallLib");
            if (wa != null) {
                for (int i = 0; i < wa.length(); i++) {
                    JSONObject wo = wa.optJSONObject(i);
                    if (wo == null) continue;
                    Wall w = new Wall(wo.optString("n", ""), wo.optString("p", ""));
                    w.used = wo.optBoolean("u", false);
                    wallLib.add(w);
                }
            }

            marginH = o.optInt("marginH", 12);
            marginV = o.optInt("marginV", 8);
            gap = o.optInt("gap", 6);
            portMarginH = o.optInt("portMarginH", marginH);
            portMarginV = o.optInt("portMarginV", marginV);
            portGap = o.optInt("portGap", gap);
            orientation = optEnum(o, "orientation", Orientation.class, orientation);
            pipWeightA = clampWeight(o.optInt("pipWeightA", pipWeightA));
            pipWeightB = clampWeight(o.optInt("pipWeightB", pipWeightB));
            pipWeightNav = clampWeight(o.optInt("pipWeightNav", pipWeightNav));
            keepScreenOn = o.optBoolean("keepOn", false);
            hideSystemBars = o.optBoolean("hideBars", false);
            topInfoBar = o.optBoolean("infoBar", true);
            autoNetworkTime = o.optBoolean("autoTime", true);

            islandEnabled = o.optBoolean("island", false);
            islandOffsetY = o.optInt("islandY", 0);
            islandWidth = o.optInt("islandW", 320);
            islandLyric = o.optBoolean("islandLyric", false);

            navEnabled = o.optBoolean("navOn", true);
            navPkg = o.optString("navPkg", "com.autonavi.minimap");

            citySource = optEnum(o, "citySource", CitySource.class, citySource);
            weatherCity = o.optString("city", "");
            weatherUpdatedAt = o.optLong("cityAt", 0L);
            weatherAuto = o.optBoolean("cityAuto", true);
            weatherSummary = o.optString("wSummary", "");
            weatherForecast = o.optString("wForecast", "");
            weatherError = o.optString("wError", "");
            weatherLat = o.optDouble("wLat", 0);
            weatherLon = o.optDouble("wLon", 0);

            lyricSource = optEnum(o, "lyricSource", LyricSource.class, lyricSource);
            lyricLines = o.optInt("lyricLines", 0);
            lyricSize = o.optInt("lyricSize", 100);
            lyricOffsetMs = o.optInt("lyricOffset", 0);
            lyricStatusBar = o.optBoolean("lyricBar", false);
            lyricNotification = o.optBoolean("lyricNoti", false);
            lyricBluetooth = o.optBoolean("lyricBt", true);
            mediaCardLines = o.optInt("mediaLines", 3);
            readInto(o.optJSONArray("lyricHidden"), lyricHidden);

            perf = optEnum(o, "perf", Perf.class, perf);
            compatMode = o.optBoolean("compat", false);
            bgKeep = o.optInt("bgKeep", 1);
            winDefaultScale = o.optInt("winScale", 100);
            winLockMap = o.optBoolean("winLock", true);
            releaseOnLock = o.optBoolean("relLock", false);
            pauseOnScreenOff = o.optBoolean("pauseOff", false);
            JSONArray sa = o.optJSONArray("appScale");
            if (sa != null) {
                for (int i = 0; i < sa.length(); i++) {
                    JSONObject so = sa.optJSONObject(i);
                    if (so == null) continue;
                    appScale.put(so.optString("p", ""), so.optInt("s", 100));
                }
            }

            touchThreshold = o.optInt("touchTh", 10);
            longPressMs = o.optInt("longPress", 500);
            useAccessibility = o.optBoolean("useA11y", true);
            useRootInput = o.optBoolean("useRoot", true);
            touchFollow = o.optBoolean("touchFollow", true);

            JSONArray ta = o.optJSONArray("tasks");
            if (ta != null) {
                for (int i = 0; i < ta.length(); i++) {
                    JSONObject to = ta.optJSONObject(i);
                    if (to == null) continue;
                    Task t = new Task(to.optString("n", "任务"));
                    t.trigger = optEnum(to, "tr", TaskTrigger.class, t.trigger);
                    t.action = optEnum(to, "ac", TaskAction.class, t.action);
                    t.pkg = to.optString("p", "");
                    t.delayMs = to.optInt("d", 5000);
                    t.atMin = to.optInt("at", -1);
                    t.enabled = to.optBoolean("e", true);
                    tasks.add(t);
                }
            }

            autoHome = o.optBoolean("autoHome", true);
            autoRestore = o.optBoolean("autoRestore", true);
        } catch (Throwable t) {
            Log.w(TAG, "读取布局失败，回落默认", t);
            defaults();
        }
    }

    private void defaults() {
        widgets.clear();
        widgets.add(0); widgets.add(1); widgets.add(2); widgets.add(3);  // 时钟/日期/电量/内存
        mode = Mode.WIDGET_TOP;
        layoutPreset = 0;
        save();
    }

    /**
     * 只读全局字号。attachBaseContext 里要用，那时尚未构造 model，
     * 这里直接解析存档，不跑应用枚举（queryIntentActivities 很贵）。
     */
    public static int fontScaleOf(Context c) {
        try {
            String raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(K_LAYOUT, null);
            return raw == null ? 100 : new JSONObject(raw).optInt("fontScale", 100);
        } catch (Throwable t) {
            return 100;
        }
    }

    private static void readInto(JSONArray arr, List<String> dst) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String v = arr.optString(i, null);
            if (v != null && !v.isEmpty() && !dst.contains(v)) dst.add(v);
        }
    }

    private static void readInto(JSONArray arr, Set<String> dst) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String v = arr.optString(i, null);
            if (v != null && !v.isEmpty()) dst.add(v);
        }
    }

    /** 安全读枚举：名字不认识就保留默认值（旧存档兼容）。 */
    private static <E extends Enum<E>> E optEnum(JSONObject o, String key, Class<E> cls, E def) {
        try {
            String s = o.optString(key, null);
            if (s == null || s.isEmpty()) return def;
            return Enum.valueOf(cls, s);
        } catch (Throwable t) {
            return def;
        }
    }

    /* ==================== 导入导出（本地文件备份，不涉账号） ==================== */

    public String exportScheme() {
        return sp().getString(K_LAYOUT, "{}");
    }

    public boolean importScheme(String json) {
        String prev = sp().getString(K_LAYOUT, null);
        try {
            new JSONObject(json);   // 校验
            sp().edit().putString(K_LAYOUT, json).commit();
            reset();
            load();
            return true;
        } catch (Throwable t) {
            // 旧实现先写后读：语法对、语义错的存档会把 load 搞抛异常，
            // 方法返回 false，但存档已经被污染，重启后停在 defaults，
            // 用户以为「导入失败」，配置其实已经丢了。
            if (prev != null) {
                sp().edit().putString(K_LAYOUT, prev).commit();
                reset();
                load();
            }
            return false;
        }
    }

    /** 清空内存状态（重新 load 前调用，避免叠加）。 */
    private void reset() {
        pinned.clear(); dock.clear(); widgets.clear(); folders.clear();
        quickbar.clear(); quickbarByMode.clear(); wallLib.clear(); tasks.clear();
        appScale.clear(); lyricHidden.clear(); dockWindow.clear();
    }

    /** 恢复出厂配置 —— 布局、Dock、外观、任务全部换回刚装好的样子。 */
    public void factoryReset() {
        reset();
        defaults();
        // defaults() 是「布局」分区的默认值，不是全套；旧实现只 reset+defaults，
        // 字号/透明/外观/歌词/天气/画中画权重全留在用户的调校值上，
        // 「恢复出厂」名不副实（用户实测过：重置后字号还是 130%）。
        resetScalars();
        save();
        load();
    }

    /**
     * 标量配置回默认值（defaults() 只管列表类那一组，这里管剩下的）。
     * 必须覆盖全部标量字段：旧实现只重置 16 个，Dock/菜园/小白点/野菜岛/
     * 天气/歌词/窗口/触摸/快捷按钮全留在用户调校值上，「恢复出厂」名不副实
     * （用户实测过：重置后字号还是 130%）。
     */
    private void resetScalars() {
        showLabels = true;
        widgetStripAlpha = 100;
        layoutPreset = 0;
        customLayoutName = "";
        fontScale = 100;
        /* Dock */
        dockPos = DockPos.BOTTOM;
        dockCount = 5;
        dockWidth = 76;
        dockGap = 8;
        dockIconSize = 44;
        dockAlpha = 100;
        dockAutoHide = false;
        dockFixed = false;
        dockShowClock = true;
        quickBtn1 = "";
        quickBtn2 = "";
        /* 菜园 */
        gardenEnabled = true;
        gardenDim = 60;
        gardenStyle = GardenStyle.CENTER_CLOCK;
        /* 小白点 */
        ballEnabled = false;
        ballX = 40;
        ballY = -1;
        ballSize = 48;
        ballAlpha = 90;
        /* 外观 */
        themeId = Theme.DEFAULT_ID;
        customAccent = "";
        themeRadius = 100;
        themeGlass = 0;
        themeOutline = false;
        musicQueueJson = "";
        musicIdx = -1;
        musicPos = 0;
        dayNight = DayNight.DARK;
        wallDay = "";
        wallNight = "";
        wallDim = 0;
        /* 屏幕 */
        marginH = 12;
        marginV = 8;
        gap = 6;
        portMarginH = 12;
        portMarginV = 8;
        portGap = 6;
        orientation = Orientation.LANDSCAPE;
        pipWeightA = 60;
        pipWeightB = 40;
        keepScreenOn = false;
        hideSystemBars = false;
        topInfoBar = true;
        autoNetworkTime = true;
        /* 野菜岛 */
        islandEnabled = false;
        islandOffsetY = 0;
        islandWidth = 320;
        islandLyric = false;
        /* 导航卡片（批次 AN） */
        navEnabled = true;
        navPkg = "com.autonavi.minimap";
        /* 天气 */
        citySource = CitySource.MANUAL;
        weatherCity = "";
        weatherUpdatedAt = 0L;
        weatherCode = -1;
        weatherAuto = true;
        weatherSummary = "";
        weatherForecast = "";
        weatherError = "";
        weatherLat = 0;
        weatherLon = 0;
        /* 歌词 */
        lyricSource = LyricSource.PLAYER;
        lyricLines = 0;
        lyricSize = 100;
        lyricOffsetMs = 0;
        lyricStatusBar = false;
        lyricNotification = false;
        lyricBluetooth = true;
        mediaCardLines = 3;
        /* 窗口 */
        perf = Perf.MID;
        compatMode = false;
        bgKeep = 1;
        winDefaultScale = 100;
        winLockMap = true;
        releaseOnLock = false;
        pauseOnScreenOff = false;
        /* 触摸 */
        touchThreshold = 10;
        longPressMs = 500;
        useAccessibility = true;
        useRootInput = true;
        touchFollow = true;
    }
}
