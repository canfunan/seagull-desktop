package com.seagull.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 壁纸桌面页（第 0 页）—— 复刻 Vivo 智能车载 CarLauncher 的
 * WallpaperDesktopFragment + TimeDateComponentView。
 *
 * 批次 CI 起，这一页从「只叠时间文字」升级成可编辑的桌面：
 *  - 时间日期块（含媒体/歌词行）可拖动、可调字号，位置/倍率存进 model.wallClock*；
 *  - 支持添加系统小组件（AppWidgetHost 承载，长按页面空白 → 添加），
 *    组件可拖动摆位、长按可移除，记录存 model.wallWidgetsJson。
 *
 * 本页背景透明：壁纸（静态图或动态视频层）由 HomeActivity 铺在 root 上。
 */

/** 时钟块/组件拖动回调（dx/dy 为手指位移）。CI 用 --release 11，内部类不允许声明接口，故提为文件级。 */
interface Move { void run(float dx, float dy); }

/** 双指捏合缩放回调（factor 自捏合起点累计）。 */
interface Zoom { void run(float factor); }

public final class WallpaperPage extends FrameLayout {

    /** 小组件 bind 授权回执（HomeActivity.onActivityResult 原样转发进来）。 */
    public static final int REQ_BIND = 0x5E01;

    /** AppWidgetHost 实例 id：本应用专用，与系统其他 host 不冲突即可。 */
    private static final int HOST_ID = 0x5E59;

    private final TextView clock, date, media, lyric;
    /** I2 农历行：日期行下方的 "农历 腊月十九 · 乙巳(蛇)"。 */
    private final TextView lunarRow;
    private final LinearLayout clockBox;
    private final FrameLayout widgetLayer;
    private LauncherModel model;
    private AppWidgetHost host;
    private int pendingBindId;

    /** 选中框 + 右下角缩放把手（选中组件时出现，点空白取消）。 */
    private final View selFrame, selGrip;
    private int selWidgetId = -1;

    /** 双指缩放起始倍率快照。 */
    private float zoomBase = 1f;

    /** H 批次行车块：车速 / 海拔航向 / 行程三行，可拖动缩放，摆位存 model.wallGps*。 */
    private final LinearLayout gpsBox;
    private final TextView gpsBig, gpsMid, gpsTrip;
    private final TextView gpsObd;
    private boolean gpsAttached;

    /** I3 倒计时块：距目标日 N 天；配置（名称/日期）经点按菜单。 */
    private final LinearLayout cdBox;
    private final TextView cdBig, cdSub;

    /** I5 监控块：内存/CPU/存储三行小字，后台线程采样。 */
    private final LinearLayout monBox;
    private final LinearLayout musBox;
    private final ImageView musArt;
    private final LinearLayout gBox;
    private final TextView musL0, musL1, musL2, musL3;
    private final TextView monL1, monL2, monL3;
    private final TextView monL4;
    private final TextView gBig, gSub;

    /** I4 指南针兜底：GPS 航向失联时用加速度计+磁力计方位角补显。 */
    private SensorManager sm;
    private final float[] accV = new float[3], magV = new float[3];
    private boolean accOk, magOk;
    private float sensAz = Float.NaN;
    private long lastSensAt;

    /** 已挂小组件的位置表：id → {x 中点比例, y 顶边比例, s 缩放倍率}。 */
    private final Map<Integer, float[]> widgetPos = new HashMap<>();

    public WallpaperPage(Context c, LauncherModel m) {
        super(c);
        this.model = m;
        setBackgroundColor(Color.TRANSPARENT);

        widgetLayer = new FrameLayout(c);
        addView(widgetLayer, new LayoutParams(-1, -1));

        /* 选中框（描边）+ 缩放把手（圆点）：盖在组件上方，点空白处取消选中 */
        selFrame = new View(c);
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setStroke(dp(2), 0xFF8AB4F8);
        border.setCornerRadius(dp(10));
        selFrame.setBackground(border);
        selFrame.setVisibility(GONE);
        addView(selFrame, new LayoutParams(0, 0, Gravity.TOP | Gravity.LEFT));
        selGrip = new View(c);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFF8AB4F8);
        dot.setStroke(dp(2), 0xFFFFFFFF);
        selGrip.setBackground(dot);
        selGrip.setVisibility(GONE);
        addView(selGrip, new LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.LEFT));
        selGrip.setOnTouchListener(gripTouch);

        clockBox = new LinearLayout(c);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.CENTER_HORIZONTAL);

        clock = tv(c, 62, R.color.text, true);
        date = tv(c, 16, R.color.text_dim, false);
        lunarRow = tv(c, 15, R.color.text_dim, false);
        media = tv(c, 15, R.color.text, false);
        lyric = tv(c, 14, R.color.text_dim, false);

        clockBox.addView(clock);
        clockBox.addView(date, topMargin(c, 6));
        clockBox.addView(lunarRow, topMargin(c, 4));
        clockBox.addView(media, topMargin(c, 30));
        clockBox.addView(lyric, topMargin(c, 4));
        addView(clockBox, new LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));

        // 时钟块：单击弹字号菜单、双指捏合连续缩放、长按/拖动移位置
        clockBox.setOnTouchListener(new Drag(clockBox,
                (dx, dy) -> nudgeClock(dx, dy),
                this::showClockMenu,
                this::showClockMenu,
                this::saveClockPos,
                null,
                () -> zoomBase = model.wallClockScale <= 0 ? 1f : model.wallClockScale,
                f -> {
                    float s = clampf(zoomBase * f, 0.5f, 2.4f);
                    applyClockScale(s);
                    model.wallClockScale = s;
                },
                () -> model.save()));

        // H 批次行车块（车速/海拔航向/行程）：与时钟同套拖动/捏合摆位
        gpsBox = new LinearLayout(c);
        gpsBox.setOrientation(LinearLayout.VERTICAL);
        gpsBox.setGravity(Gravity.CENTER_HORIZONTAL);
        gpsBig = tv(c, 26, R.color.text, true);
        gpsMid = tv(c, 13, R.color.text_dim, false);
        gpsTrip = tv(c, 13, R.color.text_dim, false);
        gpsObd = tv(c, 13, R.color.text_dim, false);   // P3：OBD 转速/水温，离线收起
        gpsBox.addView(gpsBig);
        gpsBox.addView(gpsMid, topMargin(c, 6));
        gpsBox.addView(gpsTrip, topMargin(c, 3));
        gpsObd.setVisibility(GONE);
        gpsBox.addView(gpsObd, topMargin(c, 3));
        gpsBox.setVisibility(GONE);
        addView(gpsBox, new LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));
        gpsBox.setOnTouchListener(new Drag(gpsBox,
                (dx, dy) -> nudgeGps(dx, dy),
                this::onGpsTap,
                this::onGpsTap,
                this::saveGpsPos,
                null,
                () -> zoomBase = model.wallGpsScale <= 0 ? 1f : model.wallGpsScale,
                f -> {
                    float s = clampf(zoomBase * f, 0.5f, 2.4f);
                    applyGpsScale(s);
                    model.wallGpsScale = s;
                },
                () -> model.save()));

        // I3 倒计时块：与行车块同套拖动/缩放；配置经点按菜单
        cdBox = new LinearLayout(c);
        cdBox.setOrientation(LinearLayout.VERTICAL);
        cdBox.setGravity(Gravity.CENTER_HORIZONTAL);
        cdBig = tv(c, 26, R.color.text, true);
        cdSub = tv(c, 13, R.color.text_dim, false);
        cdBox.addView(cdBig);
        cdBox.addView(cdSub, topMargin(c, 4));
        cdBox.setVisibility(GONE);
        addView(cdBox, new LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));
        cdBox.setOnTouchListener(new Drag(cdBox,
                (dx, dy) -> nudgeBlock(cdBox, dx, dy),
                this::onCdTap,
                this::onCdTap,
                () -> saveBlockPos(cdBox, pos -> {
                    model.wallCdX = pos[0]; model.wallCdY = pos[1];
                }),
                null,
                () -> zoomBase = model.wallCdScale <= 0 ? 1f : model.wallCdScale,
                f -> {
                    float s = clampf(zoomBase * f, 0.5f, 2.4f);
                    applyCdScale(s);
                    model.wallCdScale = s;
                },
                () -> model.save()));

        // I5 监控块：内存/CPU/存储三行；默认关，时钟块菜单开
        monBox = new LinearLayout(c);
        monBox.setOrientation(LinearLayout.VERTICAL);
        monBox.setGravity(Gravity.CENTER_HORIZONTAL);
        monL1 = tv(c, 13, R.color.text_dim, false);
        monL2 = tv(c, 13, R.color.text_dim, false);
        monL3 = tv(c, 13, R.color.text_dim, false);
        monL4 = tv(c, 13, R.color.text_dim, false);   // P3：OBD 电压，离线收起
        monBox.addView(monL1);
        monBox.addView(monL2, topMargin(c, 3));
        monBox.addView(monL3, topMargin(c, 3));
        monL4.setVisibility(GONE);
        monBox.addView(monL4, topMargin(c, 3));
        monBox.setVisibility(GONE);
        addView(monBox, new LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));
        monBox.setOnTouchListener(new Drag(monBox,
                (dx, dy) -> nudgeBlock(monBox, dx, dy),
                this::showMonMenu,
                this::showMonMenu,
                () -> saveBlockPos(monBox, pos -> {
                    model.wallMonX = pos[0]; model.wallMonY = pos[1];
                }),
                null,
                () -> zoomBase = model.wallMonScale <= 0 ? 1f : model.wallMonScale,
                f -> {
                    float s = clampf(zoomBase * f, 0.5f, 2.4f);
                    applyMonScale(s);
                    model.wallMonScale = s;
                },
                () -> model.save()));

        // M5 播放列表块：当前曲 + 队列两行 + 提示行；点按开选歌页，长按菜单
        musBox = new LinearLayout(c);
        musBox.setOrientation(LinearLayout.VERTICAL);
        // O2 封面缩略图行：封面 + 当前曲行同行；无封面时缩略图 GONE
        LinearLayout musRow0 = new LinearLayout(c);
        musRow0.setOrientation(LinearLayout.HORIZONTAL);
        musRow0.setGravity(android.view.Gravity.CENTER_VERTICAL);
        musArt = new ImageView(c);
        musArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        musRow0.addView(musArt, new LayoutParams(dp(22), dp(22)));
        musL0 = tv(c, 15, R.color.leaf, true);          // 状态行：当前曲
        LinearLayout.LayoutParams mlp0 = new LinearLayout.LayoutParams(-2, -2);
        mlp0.leftMargin = dp(6);
        musRow0.addView(musL0, mlp0);
        musL1 = tv(c, 12, R.color.text_dim, true);      // 队列 +1
        musL2 = tv(c, 12, R.color.text_dim, true);      // 队列 +2
        musL3 = tv(c, 11, R.color.text_dim, true);      // 提示行
        musBox.addView(musRow0);
        musBox.addView(musL1, topMargin(c, 3));
        musBox.addView(musL2, topMargin(c, 3));
        musBox.addView(musL3, topMargin(c, 5));
        musBox.setVisibility(GONE);
        addView(musBox, new LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));
        musBox.setOnTouchListener(new Drag(musBox,
                (dx, dy) -> nudgeBlock(musBox, dx, dy),
                () -> getContext().startActivity(new android.content.Intent(
                        getContext(), MusicActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)),
                this::showMusMenu,
                () -> saveBlockPos(musBox, pos -> {
                    model.wallMusX = pos[0]; model.wallMusY = pos[1];
                }),
                null,
                () -> zoomBase = model.wallMusScale <= 0 ? 1f : model.wallMusScale,
                f -> {
                    float s = clampf(zoomBase * f, 0.5f, 2.4f);
                    applyMusScale(s);
                    model.wallMusScale = s;
                },
                () -> model.save()));

        // N3 G值表块：当前 G（低通）+ 峰值；点按菜单，长按总菜单
        gBox = new LinearLayout(c);
        gBox.setOrientation(LinearLayout.VERTICAL);
        gBox.setGravity(Gravity.CENTER_HORIZONTAL);
        gBig = tv(c, 26, R.color.leaf, true);
        gSub = tv(c, 13, R.color.text_dim, false);
        gBox.addView(gBig);
        gBox.addView(gSub, topMargin(c, 3));
        gBox.setVisibility(GONE);
        addView(gBox, new LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));
        gBox.setOnTouchListener(new Drag(gBox,
                (dx, dy) -> nudgeBlock(gBox, dx, dy),
                this::showGMenu,
                this::showMonMenu,
                () -> saveBlockPos(gBox, pos -> {
                    model.wallGx = pos[0]; model.wallGy = pos[1];
                }),
                null,
                () -> zoomBase = model.wallGScale <= 0 ? 1f : model.wallGScale,
                f -> {
                    float s = clampf(zoomBase * f, 0.5f, 2.4f);
                    applyGScale(s);
                    model.wallGScale = s;
                },
                () -> model.save()));

        // 点空白处取消组件选中（长按空白仍是添加小组件）
        setOnClickListener(v -> deselectWidget());
        setOnLongClickListener(v -> { showEditMenu(); return true; });
        applyClock();
        applyGps();
        applyCd();
        applyMon();
        applyMus();
        applyG();
        loadWidgets();
        tick();
    }

    /* ------------------------- 时钟块：位置与字号 ------------------------- */

    /** 时钟块默认：水平居中（x=中点比例），顶部 13%（y=顶边比例）。 */
    private void applyClock() {
        clockBox.setVisibility(model.wallClockEnabled ? VISIBLE : GONE);   // Q：组件管理可藏
        applyClockScale(model.wallClockScale <= 0 ? 1f : model.wallClockScale);
    }

    /** Q 批次：壁纸页空白长按 = 编辑入口菜单（添加小组件 / 组件管理）。 */
    private void showEditMenu() {
        new AlertDialog.Builder(getContext())
                .setTitle("编辑桌面")
                .setItems(new String[]{"添加小组件…", "组件管理…"}, (d, w) -> {
                    d.dismiss();
                    if (w == 0) showAddWidget();
                    else manageBlocksDialog();
                })
                .show();
    }

    /** Q 批次：六块显隐清单（含时钟块），点行切换（R3.2）。 */
    private void manageBlocksDialog() {
        String[] keys = {"clock", "gps", "cd", "mon", "mus", "g"};
        String[] labels = {"时钟块", "行车块", "倒数块", "监控块", "音乐块", "G值表块"};
        boolean[] on = {model.wallClockEnabled, model.wallGpsEnabled, model.wallCdEnabled,
                model.wallMonEnabled, model.wallMusEnabled, model.wallGEnabled};
        String[] items = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            items[i] = labels[i] + (on[i] ? "（已显示）" : "（已隐藏）");
        }
        new AlertDialog.Builder(getContext())
                .setTitle("组件管理（点行切换显隐）")
                .setItems(items, (d, w) -> {
                    d.dismiss();
                    if (keys[w].equals("clock")) {
                        model.wallClockEnabled = !model.wallClockEnabled;
                        model.save();
                        applyClock();
                        return;
                    }
                    switch (keys[w]) {
                        case "gps": model.wallGpsEnabled = !model.wallGpsEnabled; break;
                        case "cd": model.wallCdEnabled = !model.wallCdEnabled; break;
                        case "mon": model.wallMonEnabled = !model.wallMonEnabled; break;
                        case "mus": model.wallMusEnabled = !model.wallMusEnabled;
                            ensureMusicScanned(); break;
                        case "g": model.wallGEnabled = !model.wallGEnabled; break;
                        default: break;
                    }
                    model.save();
                    refreshGpsBlock();
                    refreshCdBlock();
                    refreshMonBlock();
                    refreshMusBlock();
                    refreshGBlock();
                })
                .setNegativeButton("完成", null)
                .show();
    }

    /** 应用倍率并摆位。摆位必须等重测后做：直接用旧 measuredWidth 会把放大后的
     *  右半截 clamp 出屏幕外（真机反馈「时间只显示前半段」就是它）。 */
    private void applyClockScale(float s) {
        clock.setTextSize(62 * s);
        date.setTextSize(16 * s);
        media.setTextSize(15 * s);
        lyric.setTextSize(14 * s);
        restackClockBox(s);
        clockBox.post(this::placeClockBox);
    }

    /** 字号变了行距跟着等比缩，视觉重心不变。 */
    private void restackClockBox(float s) {
        LinearLayout.LayoutParams lp;
        lp = (LinearLayout.LayoutParams) date.getLayoutParams(); lp.topMargin = dp(6 * s); date.setLayoutParams(lp);
        lp = (LinearLayout.LayoutParams) lunarRow.getLayoutParams(); lp.topMargin = dp(4 * s); lunarRow.setLayoutParams(lp);
        lp = (LinearLayout.LayoutParams) media.getLayoutParams(); lp.topMargin = dp(30 * s); media.setLayoutParams(lp);
        lp = (LinearLayout.LayoutParams) lyric.getLayoutParams(); lp.topMargin = dp(4 * s); lyric.setLayoutParams(lp);
    }

    /** 按比例摆位：x = 块中点在页宽里的比例，y = 块顶边在页高里的比例。 */
    private void placeClockBox() {
        int w = getWidth(), h = getHeight();
        LayoutParams lp = (LayoutParams) clockBox.getLayoutParams();
        int bw = Math.max(0, clockBox.getMeasuredWidth());
        int bh = Math.max(0, clockBox.getMeasuredHeight());
        int left = Math.round(w * model.wallClockX - bw / 2f);
        // 审计 M5：时钟每秒 tick 都走到这里。lp 就是当前生效的 LayoutParams，
        // 先比较后赋值：位置没变就不碰 setLayoutParams（省一整轮
        // requestLayout + measure + layout）
        int nl = clamp(left, dp(8), Math.max(dp(8), w - bw - dp(8)));
        int nt = clamp(Math.round(h * model.wallClockY), dp(8),
                Math.max(dp(8), h - bh - dp(8)));
        if (lp.leftMargin != nl || lp.topMargin != nt) {
            lp.leftMargin = nl;
            lp.topMargin = nt;
            clockBox.setLayoutParams(lp);
        }
    }

    private void nudgeClock(float dx, float dy) {
        LayoutParams lp = (LayoutParams) clockBox.getLayoutParams();
        int w = getWidth(), h = getHeight();
        int bw = clockBox.getMeasuredWidth();
        lp.leftMargin = clamp(lp.leftMargin + Math.round(dx), dp(8), Math.max(dp(8), w - bw - dp(8)));
        lp.topMargin = clamp(lp.topMargin + Math.round(dy), dp(8),
                Math.max(dp(8), h - clockBox.getMeasuredHeight() - dp(8)));
        clockBox.setLayoutParams(lp);
    }

    private void saveClockPos() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        LayoutParams lp = (LayoutParams) clockBox.getLayoutParams();
        model.wallClockX = (lp.leftMargin + clockBox.getMeasuredWidth() / 2f) / w;
        model.wallClockY = (float) lp.topMargin / h;
        model.save();
    }

    private void showClockMenu() {
        // 隐藏掉的块在这里找回（时钟块菜单 = 壁纸块的总开关位）
        java.util.List<String> show = new ArrayList<>();
        if (!model.wallGpsEnabled) show.add("显示行车块");
        if (!model.wallCdEnabled) show.add("显示倒数块");
        if (!model.wallMonEnabled) show.add("显示监控块");
        if (!model.wallMusEnabled) show.add("显示音乐块");
        if (!model.wallGEnabled) show.add("显示G值块");
        String[] sizes = {"小", "标准", "大", "特大"};
        String[] more = {"组件管理…", "隐藏时钟块"};
        String[] opts = new String[sizes.length + show.size() + more.length];
        for (int i = 0; i < sizes.length; i++) opts[i] = sizes[i];
        for (int i = 0; i < show.size(); i++) opts[sizes.length + i] = show.get(i);
        for (int i = 0; i < more.length; i++) {
            opts[sizes.length + show.size() + i] = more[i];
        }
        new AlertDialog.Builder(getContext())
                .setTitle("时间日期大小（按住拖动可移位置）")
                .setItems(opts, (d, w) -> {
                    String cmd = opts[w];
                    if (cmd.equals("组件管理…")) { manageBlocksDialog(); return; }
                    if (cmd.equals("隐藏时钟块")) {
                        model.wallClockEnabled = false;
                        model.save();
                        applyClock();
                        return;
                    }
                    if (w < sizes.length) {
                        model.wallClockScale = w == 0 ? 0.7f : w == 1 ? 1f : w == 2 ? 1.3f : 1.6f;
                        applyClock();
                    } else {
                        if (cmd.equals("显示行车块")) model.wallGpsEnabled = true;
                        else if (cmd.equals("显示倒数块")) model.wallCdEnabled = true;
                        else if (cmd.equals("显示监控块")) model.wallMonEnabled = true;
                        else if (cmd.equals("显示音乐块")) { model.wallMusEnabled = true; ensureMusicScanned(); }
                        else if (cmd.equals("显示G值块")) model.wallGEnabled = true;
                        refreshGpsBlock();
                        refreshCdBlock();
                        refreshMonBlock();
                        refreshMusBlock();
                        refreshGBlock();
                    }
                    model.save();
                })
                .show();
    }

    /* ------------------------- 行车块（H 批次 GPS 管线） ------------------------- */

    /** 行车块默认：左下（x=16%，y=74%），与时钟（顶部居中）错开。 */
    private void applyGps() {
        applyGpsScale(model.wallGpsScale <= 0 ? 1f : model.wallGpsScale);
    }

    private void applyGpsScale(float s) {
        gpsBig.setTextSize(26 * s);
        gpsMid.setTextSize(13 * s);
        gpsTrip.setTextSize(13 * s);
        LinearLayout.LayoutParams lp;
        lp = (LinearLayout.LayoutParams) gpsMid.getLayoutParams(); lp.topMargin = dp(6 * s); gpsMid.setLayoutParams(lp);
        lp = (LinearLayout.LayoutParams) gpsTrip.getLayoutParams(); lp.topMargin = dp(3 * s); gpsTrip.setLayoutParams(lp);
        gpsBox.post(this::placeGpsBox);
    }

    private void placeGpsBox() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        LayoutParams lp = (LayoutParams) gpsBox.getLayoutParams();
        int bw = Math.max(0, gpsBox.getMeasuredWidth());
        int bh = Math.max(0, gpsBox.getMeasuredHeight());
        int left = Math.round(w * model.wallGpsX - bw / 2f);
        int nl = clamp(left, dp(8), Math.max(dp(8), w - bw - dp(8)));
        int nt = clamp(Math.round(h * model.wallGpsY), dp(8),
                Math.max(dp(8), h - bh - dp(8)));
        if (lp.leftMargin != nl || lp.topMargin != nt) {
            lp.leftMargin = nl;
            lp.topMargin = nt;
            gpsBox.setLayoutParams(lp);
        }
    }

    private void nudgeGps(float dx, float dy) {
        LayoutParams lp = (LayoutParams) gpsBox.getLayoutParams();
        int w = getWidth(), h = getHeight();
        int bw = gpsBox.getMeasuredWidth();
        lp.leftMargin = clamp(lp.leftMargin + Math.round(dx), dp(8), Math.max(dp(8), w - bw - dp(8)));
        lp.topMargin = clamp(lp.topMargin + Math.round(dy), dp(8),
                Math.max(dp(8), h - gpsBox.getMeasuredHeight() - dp(8)));
        gpsBox.setLayoutParams(lp);
    }

    private void saveGpsPos() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        LayoutParams lp = (LayoutParams) gpsBox.getLayoutParams();
        model.wallGpsX = (lp.leftMargin + gpsBox.getMeasuredWidth() / 2f) / w;
        model.wallGpsY = (float) lp.topMargin / h;
        model.save();
    }

    /** 无权限时点按 = 申请权限；有权限时点按 = 大小/清零/隐藏菜单。 */
    private void onGpsTap() {
        if (!model.wallGpsEnabled) return;
        if (!LocationHelper.hasPermission(getContext())) {
            if (getContext() instanceof Activity) LocationHelper.request((Activity) getContext());
            return;
        }
        showGpsMenu();
    }

    private void showGpsMenu() {
        new AlertDialog.Builder(getContext())
                .setTitle("行车信息（按住拖动可移位置）")
                .setItems(new String[]{"小", "标准", "大", "特大", "清零本次行程", "隐藏行车块"},
                        (d, w) -> {
                            if (w <= 3) {
                                model.wallGpsScale = w == 0 ? 0.7f : w == 1 ? 1f : w == 2 ? 1.3f : 1.6f;
                                applyGpsScale(model.wallGpsScale);
                            } else if (w == 4) {
                                LocationHelper.resetTrip();
                                updateGpsRows(LocationHelper.last());
                            } else {
                                model.wallGpsEnabled = false;
                                refreshGpsBlock();
                            }
                            model.save();
                        })
                .show();
    }

    /**
     * 可见性仲裁（每次 attach / 权限回执 / 开关切换走这里）：
     *  关 = 整块 GONE 并退订；无权限 = 只留「点按开启定位」提示行；
     *  有权限 = VISIBLE 并订阅 1s 更新（首个定位事件前显示 "--"）。
     */
    public void refreshGpsBlock() {
        if (!model.wallGpsEnabled) {
            if (gpsAttached) { LocationHelper.release(gpsListener); gpsAttached = false; }
            gpsBox.setVisibility(GONE);
            return;
        }
        if (LocationHelper.hasPermission(getContext())) {
            gpsBox.setVisibility(VISIBLE);
            gpsMid.setVisibility(VISIBLE);
            gpsTrip.setVisibility(VISIBLE);
            if (!gpsAttached) {
                LocationHelper.acquire(getContext(), gpsListener);
                gpsAttached = true;
            }
            startSensors();
            updateGpsRows(LocationHelper.last());
        } else {
            if (gpsAttached) { LocationHelper.release(gpsListener); gpsAttached = false; }
            gpsBox.setVisibility(VISIBLE);
            gpsBig.setText("点按开启定位");
            gpsMid.setVisibility(GONE);
            gpsTrip.setVisibility(GONE);
        }
    }

    private final LocationHelper.Listener gpsListener = this::updateGpsRows;

    /** 1s 定位事件原位 setText（审计 M4 纪律：事件驱动单行刷新，不整树重建）。 */
    private void updateGpsRows(Location loc) {
        if (gpsBox == null || gpsBox.getVisibility() != VISIBLE) return;
        refreshObdRows();
        if (!LocationHelper.hasPermission(getContext())) return;
        if (loc == null || !LocationHelper.fresh()) {
            if (!obdBigOverridden()) {
                gpsBig.setText("-- km/h");
                gpsMid.setText("海拔 -- · 航向 --");
            }
        } else if (!obdBigOverridden()) {
            float kmh = Math.max(0f, loc.getSpeed() * 3.6f);
            gpsBig.setText(String.format(java.util.Locale.US, "%.0f km/h", kmh));
            float brg = loc.getBearing();
            gpsMid.setText(String.format(java.util.Locale.US, "海拔 %.0f m · 航向 %s",
                    loc.getAltitude(), bearingLabel(brg)));
        }
        long sec = LocationHelper.tripSeconds();
        String dur = sec >= 3600
                ? String.format(java.util.Locale.US, "%d小时%02d分钟", sec / 3600, (sec % 3600) / 60)
                : String.format(java.util.Locale.US, "%d分钟", sec / 60);
        gpsTrip.setText(String.format(java.util.Locale.US, "行程 %.1f km · %s",
                LocationHelper.tripKm(), dur));
    }

    /** OBD 车速有效时覆盖 gpsBig；无效回落 GPS 估算。 */
    private boolean obdBigOverridden() {
        ObdEngine.State s = ObdEngine.STATE;
        if (!s.speedFromObd || Float.isNaN(s.speedKmh)) return false;
        gpsBig.setText(String.format(java.util.Locale.US, "%.0f km/h OBD", s.speedKmh));
        return true;
    }

    /** OBD 转速/水温行：在线有值显示，离线/失效收起（行车块回落纯 GPS 三行）。 */
    private void refreshObdRows() {
        if (gpsObd == null) return;
        ObdEngine.State s = ObdEngine.STATE;
        if (s.online && (!Float.isNaN(s.rpm) || !Float.isNaN(s.coolantC))) {
            gpsObd.setVisibility(VISIBLE);
            gpsObd.setText(String.format(java.util.Locale.US, "%s  %s",
                    Float.isNaN(s.rpm) ? "转速 --"
                            : String.format(java.util.Locale.US, "%.0f rpm", s.rpm),
                    Float.isNaN(s.coolantC) ? "水温 --"
                            : String.format(java.util.Locale.US, "水温 %.0f°C", s.coolantC)));
        } else {
            gpsObd.setVisibility(GONE);
        }
        // 无定位权限分支也吃 OBD 数据：块缩着时只更新 gpsBig/obd 行
        if (!LocationHelper.hasPermission(getContext()) && s.online) {
            if (s.speedFromObd && !Float.isNaN(s.speedKmh)) {
                gpsBig.setText(String.format(java.util.Locale.US, "%.0f km/h OBD", s.speedKmh));
            }
            refreshObdRowsVisibility();
        }
    }

    private void refreshObdRowsVisibility() {
        if (gpsObd == null) return;
        ObdEngine.State s = ObdEngine.STATE;
        gpsObd.setVisibility(s.online && (!Float.isNaN(s.rpm) || !Float.isNaN(s.coolantC))
                ? VISIBLE : GONE);
    }

    /** P3 监控块电压行：OBD 在线且电压有效才出现，否则收起不留空行。 */
    private void refreshMonVolt() {
        if (monL4 == null) return;
        ObdEngine.State s = ObdEngine.STATE;
        if (s.online && !Float.isNaN(s.voltageV)) {
            monL4.setVisibility(VISIBLE);
            monL4.setText(String.format(java.util.Locale.US, "电瓶 %.1f V", s.voltageV));
        } else {
            monL4.setVisibility(GONE);
        }
    }

    /** 航向角转八方位（负角取模；NaN = 无航向）。 */
    private static String bearingLabel(float deg) {
        if (Float.isNaN(deg)) return "--";
        String[] dirs = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};
        return dirs[Math.round(deg / 45f) & 7];
    }

    /* ------------------------- 块级通用：摆位/拖动/落盘 ------------------------- */

    private void placeBlock(View box, float xRatio, float yRatio) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        LayoutParams lp = (LayoutParams) box.getLayoutParams();
        int bw = Math.max(0, box.getMeasuredWidth());
        int bh = Math.max(0, box.getMeasuredHeight());
        int nl = clamp(Math.round(w * xRatio - bw / 2f), dp(8), Math.max(dp(8), w - bw - dp(8)));
        int nt = clamp(Math.round(h * yRatio), dp(8), Math.max(dp(8), h - bh - dp(8)));
        if (lp.leftMargin != nl || lp.topMargin != nt) {
            lp.leftMargin = nl;
            lp.topMargin = nt;
            box.setLayoutParams(lp);
        }
    }

    private void nudgeBlock(View box, float dx, float dy) {
        LayoutParams lp = (LayoutParams) box.getLayoutParams();
        int w = getWidth(), h = getHeight();
        int bw = box.getMeasuredWidth();
        lp.leftMargin = clamp(lp.leftMargin + Math.round(dx), dp(8), Math.max(dp(8), w - bw - dp(8)));
        lp.topMargin = clamp(lp.topMargin + Math.round(dy), dp(8),
                Math.max(dp(8), h - box.getMeasuredHeight() - dp(8)));
        box.setLayoutParams(lp);
    }

    private void saveBlockPos(View box, java.util.function.Consumer<float[]> put) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        LayoutParams lp = (LayoutParams) box.getLayoutParams();
        put.accept(new float[]{
                (lp.leftMargin + box.getMeasuredWidth() / 2f) / w, (float) lp.topMargin / h});
        model.save();
    }

    /* ------------------------- I3 倒计时块 ------------------------- */

    private void applyCd() {
        applyCdScale(model.wallCdScale <= 0 ? 1f : model.wallCdScale);
    }

    private void applyCdScale(float s) {
        cdBig.setTextSize(26 * s);
        cdSub.setTextSize(13 * s);
        cdBox.post(() -> placeBlock(cdBox, model.wallCdX, model.wallCdY));
    }

    /** 无配置时块显示设置提示，点按直接进配置。 */
    public void refreshCdBlock() {
        if (!model.wallCdEnabled) { cdBox.setVisibility(GONE); return; }
        cdBox.setVisibility(VISIBLE);
        cdSub.setVisibility(VISIBLE);
        updateCdRows();
    }

    private void onCdTap() {
        if (!model.wallCdEnabled) return;
        if (model.cdTarget.isEmpty()) { editCdDate(); return; }
        showCdMenu();
    }

    private void showCdMenu() {
        new AlertDialog.Builder(getContext())
                .setTitle("倒数日（按住拖动可移位置）")
                .setItems(new String[]{"改名称", "改日期", "小", "标准", "大", "特大", "隐藏"},
                        (d, w) -> {
                            if (w == 0) editCdLabel();
                            else if (w == 1) editCdDate();
                            else if (w <= 5) {
                                model.wallCdScale = w == 2 ? 0.7f : w == 3 ? 1f : w == 4 ? 1.3f : 1.6f;
                                applyCdScale(model.wallCdScale);
                            } else {
                                model.wallCdEnabled = false;
                                refreshCdBlock();
                            }
                            model.save();
                        })
                .show();
    }

    private void editCdLabel() {
        askText("倒数日名称", model.cdLabel.isEmpty() ? "回家" : model.cdLabel, v -> {
            model.cdLabel = v;
            model.save();
            updateCdRows();
        });
    }

    private void editCdDate() {
        askText("目标日（yyyy-MM-dd）", model.cdTarget, v -> {
            long days = cdDays(v);
            if (days == Long.MIN_VALUE) {
                Toast.makeText(getContext(), "日期格式不对，示例 2026-10-01", Toast.LENGTH_SHORT).show();
                return;
            }
            model.cdTarget = v;
            if (model.cdLabel.isEmpty()) model.cdLabel = "目标日";
            model.save();
            updateCdRows();
        });
    }

    /** 目标日距今天数（按本地零点差）；解析失败返回 Long.MIN_VALUE。 */
    private static long cdDays(String ymd) {
        try {
            String[] p = ymd.trim().split("-");
            if (p.length != 3) return Long.MIN_VALUE;
            int y = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]), d = Integer.parseInt(p[2]);
            if (y < 1970 || y > 2200 || m < 1 || m > 12 || d < 1 || d > 31) return Long.MIN_VALUE;
            java.util.Calendar t = java.util.Calendar.getInstance();
            t.clear();
            t.set(y, m - 1, d, 0, 0, 0);
            java.util.Calendar now = java.util.Calendar.getInstance();
            now.set(java.util.Calendar.HOUR_OF_DAY, 0);
            now.set(java.util.Calendar.MINUTE, 0);
            now.set(java.util.Calendar.SECOND, 0);
            now.set(java.util.Calendar.MILLISECOND, 0);
            return (t.getTimeInMillis() - now.getTimeInMillis()) / 86_400_000L;
        } catch (Throwable t) {
            return Long.MIN_VALUE;
        }
    }

    private void updateCdRows() {
        if (cdBox.getVisibility() != VISIBLE) return;
        if (model.cdTarget.isEmpty()) {
            cdBig.setText("点按设置");
            cdSub.setText("倒数日");
            return;
        }
        long days = cdDays(model.cdTarget);
        if (days == Long.MIN_VALUE) {
            cdBig.setText("日期有误");
            cdSub.setText("点按重设");
            return;
        }
        if (days > 0) cdBig.setText(days + " 天");
        else if (days == 0) cdBig.setText("就是今天");
        else cdBig.setText("已过 " + (-days) + " 天");
        cdSub.setText(model.cdLabel.isEmpty() ? model.cdTarget : model.cdLabel + " · " + model.cdTarget);
    }

    /* ------------------------- I5 监控块 ------------------------- */

    private void applyMon() {
        applyMonScale(model.wallMonScale <= 0 ? 1f : model.wallMonScale);
    }

    private void applyMonScale(float s) {
        monL1.setTextSize(13 * s);
        monL2.setTextSize(13 * s);
        monL3.setTextSize(13 * s);
        monL4.setTextSize(13 * s);
        monBox.post(() -> placeBlock(monBox, model.wallMonX, model.wallMonY));
    }

    public void refreshMonBlock() {
        if (!model.wallMonEnabled) { monBox.setVisibility(GONE); return; }
        monBox.setVisibility(VISIBLE);
        refreshMonRows();
    }

    private void showMonMenu() {
        new AlertDialog.Builder(getContext())
                .setTitle("系统监控（按住拖动可移位置）")
                .setItems(new String[]{"小", "标准", "大", "特大", "隐藏"}, (d, w) -> {
                    if (w <= 3) {
                        model.wallMonScale = w == 0 ? 0.7f : w == 1 ? 1f : w == 2 ? 1.3f : 1.6f;
                        applyMonScale(model.wallMonScale);
                    } else {
                        model.wallMonEnabled = false;
                        refreshMonBlock();
                    }
                    model.save();
                })
                .show();
    }

    /* ---------------- M5 播放列表块 ---------------- */

    private void applyMus() {
        applyMusScale(model.wallMusScale <= 0 ? 1f : model.wallMusScale);
    }

    private void applyMusScale(float s) {
        musL0.setTextSize(15 * s);
        musL1.setTextSize(12 * s);
        musL2.setTextSize(12 * s);
        musL3.setTextSize(11 * s);
        musBox.post(() -> placeBlock(musBox, model.wallMusX, model.wallMusY));
    }

    /** tick/resume 驱动：可见时原位改文本（M4 审计纪律，无结构重建）。 */
    public void refreshMusBlock() {
        if (!model.wallMusEnabled) { musBox.setVisibility(GONE); return; }
        musBox.setVisibility(VISIBLE);
        java.util.List<MusicLib.Track> q = MusicService.queue();
        int i = MusicService.index();
        if (i >= 0 && i < q.size()) {
            MusicLib.Track cur = q.get(i);
            Bitmap art = MusicService.currentArt();
            if (art != null && !art.isRecycled()) {
                musArt.setImageBitmap(art);
                musArt.setVisibility(VISIBLE);
            } else {
                musArt.setImageBitmap(null);
                musArt.setVisibility(GONE);
            }
            musL0.setText((MusicService.isPlaying() ? "▶ " : "⏸ ")
                    + MusicLib.label(cur));
            musL1.setText(i + 1 < q.size() ? "下首 " + MusicLib.label(q.get(i + 1)) : "队列到头，播完循环");
            musL2.setText(i + 2 < q.size() ? "再下 " + MusicLib.label(q.get(i + 2)) : "");
            musL3.setText("点按选歌 · 长按菜单");
        } else {
            // 本地占位为默认渲染；有远端会话（手机音乐经车机出声）再整块覆盖
            List<MusicLib.Track> lib = MusicLib.last();
            musArt.setImageBitmap(null);
            musArt.setVisibility(GONE);
            musL0.setText("本地音乐");
            musL1.setText(lib.isEmpty() ? "还没扫到歌" : "共 " + lib.size() + " 首");
            musL2.setText("");
            musL3.setText("点按选歌 · 长按菜单");
            // O2 蓝牙兜底：曲名 + 封面（CoverCache 异步解码，下一秒 tick 补上）
            android.media.session.MediaController mc = Lyrics.activeController(getContext());
            if (mc != null) showRemoteMedia(mc);
        }
    }

    /** O2 远端会话渲染：曲名行 + 封面缩略图（有封面才露）。 */
    private void showRemoteMedia(android.media.session.MediaController mc) {
        android.media.MediaMetadata md = mc.getMetadata();
        if (md == null) return;
        String t = str(md, android.media.MediaMetadata.METADATA_KEY_TITLE);
        if (t == null || t.trim().isEmpty()) return;
        String a = str(md, android.media.MediaMetadata.METADATA_KEY_ARTIST);
        boolean playing = false;
        android.media.session.PlaybackState ps = mc.getPlaybackState();
        if (ps != null) playing = ps.getState() == android.media.session.PlaybackState.STATE_PLAYING;
        Bitmap art = CoverCache.get(t, a, md);
        if (art != null && !art.isRecycled()) {
            musArt.setImageBitmap(art);
            musArt.setVisibility(VISIBLE);
        } else {
            musArt.setImageBitmap(null);
            musArt.setVisibility(GONE);
        }
        musL0.setText((playing ? "▶ " : "⏸ ") + t + " · 蓝牙");
        musL1.setText(a == null || a.trim().isEmpty() ? "手机/蓝牙音乐播放中" : a + " · 蓝牙播放中");
        musL2.setText("");
        musL3.setText("本地选歌点按这里");
    }

    /** MediaMetadata 文本安全读（含 trim），null 返回 null。 */
    private static String str(android.media.MediaMetadata md, String key) {
        CharSequence cs = md == null ? null : md.getText(key);
        return cs == null ? null : cs.toString();
    }

    /** 桌面 tick 驱动扫描（没扫过才起一次）；结果回主线程刷块。 */
    private void ensureMusicScanned() {
        if (musScanned) return;
        musScanned = true;
        MusicLib.scan(getContext(), tracks -> refreshMusBlock());
    }

    private boolean musScanned;

    /** 音乐块自己的菜单：字号 + 隐藏（长按此前错挂 showMonMenu，改的是监控块）。 */
    private void showMusMenu() {
        new AlertDialog.Builder(getContext())
                .setTitle("本地音乐（按住拖动可移位置）")
                .setItems(new String[]{"小", "标准", "大", "特大", "隐藏"}, (d, w) -> {
                    if (w <= 3) {
                        model.wallMusScale = w == 0 ? 0.7f : w == 1 ? 1f : w == 2 ? 1.3f : 1.6f;
                        applyMusScale(model.wallMusScale);
                    } else {
                        model.wallMusEnabled = false;
                        refreshMusBlock();
                    }
                    model.save();
                })
                .show();
    }

    /* ---------------- N3 G值表块（加速度计，零权限） ---------------- */

    private void applyG() {
        applyGScale(model.wallGScale <= 0 ? 1f : model.wallGScale);
    }

    private void applyGScale(float s) {
        gBig.setTextSize(26 * s);
        gSub.setTextSize(13 * s);
        gBox.post(() -> placeBlock(gBox, model.wallGx, model.wallGy));
    }

    public void refreshGBlock() {
        if (!model.wallGEnabled) {
            gBox.setVisibility(GONE);
            // 传感器只有 G 块/行车块两个消费方：都不需要了就摘，别空转耗电
            if (!model.wallGpsEnabled || !LocationHelper.hasPermission(getContext())) stopSensors();
            return;
        }
        gBox.setVisibility(VISIBLE);
        startSensors();
        gBig.setText(gNow < 0 ? "-- G" : String.format(java.util.Locale.US, "%.2f G", gNow));
        gSub.setText(gPeak < 0 ? "等待数据 · 点按菜单" : String.format(java.util.Locale.US, "峰值 %.2f G · 点按菜单", gPeak));
    }

    private void showGMenu() {
        new AlertDialog.Builder(getContext())
                .setTitle("G值表（按住拖动可移位置）")
                .setItems(new String[]{"小", "标准", "大", "特大", "清零峰值", "隐藏"}, (d, w) -> {
                    if (w <= 3) {
                        model.wallGScale = w == 0 ? 0.7f : w == 1 ? 1f : w == 2 ? 1.3f : 1.6f;
                        applyGScale(model.wallGScale);
                    } else if (w == 4) {
                        gPeak = -1f;
                        refreshGBlock();
                    } else {
                        model.wallGEnabled = false;
                        refreshGBlock();
                    }
                    model.save();
                })
                .show();
    }

    /** 加速度计事件驱动（SENS 延迟 ~60Hz）；低通后写行，150ms 节流。 */
    private void updateG(float[] v, long now) {
        if (gBox.getVisibility() != VISIBLE) return;
        if (now - lastGAt < 150) return;
        lastGAt = now;
        float mag = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) / 9.80665f;
        gNow = gNow < 0 ? mag : gNow + (mag - gNow) * 0.2f;
        if (gNow > gPeak) gPeak = gNow;
        String big = String.format(java.util.Locale.US, "%.2f G", gNow);
        if (!big.equals(gBig.getText().toString())) gBig.setText(big);
        String sub = String.format(java.util.Locale.US, "峰值 %.2f G · 点按菜单", gPeak);
        if (!sub.equals(gSub.getText().toString())) gSub.setText(sub);
    }

    private float gNow = -1f;      // 低通后的当前 G，-1 = 尚无数据
    private float gPeak = -1f;     // 本次会话峰值
    private long lastGAt;

    /**
     * 采样走后台线程：cpuPct 内部有 120ms sleep（SysOps L2 注释明确提示
     * 调用方放后台线程），tick 在 UI 线程上，直接调会卡帧。
     */
    private void refreshMonRows() {
        final Context ctx = getContext();
        new Thread(() -> {
            String l1 = "--", l2 = "--", l3 = "--";
            try {
                android.app.ActivityManager am = (android.app.ActivityManager)
                        ctx.getSystemService(Context.ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi =
                        new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                l1 = String.format(java.util.Locale.US, "内存 %dM / %dM",
                        mi.availMem / (1024 * 1024), mi.totalMem / (1024 * 1024));
            } catch (Throwable ignore) { }
            try { l2 = "CPU " + SysOps.cpuPct(); } catch (Throwable ignore) { }
            try {
                StatFs st = new StatFs(Environment.getDataDirectory().getPath());
                l3 = String.format(java.util.Locale.US, "存储 %.0fG / %.0fG",
                        st.getAvailableBytes() / 1e9, st.getTotalBytes() / 1e9);
            } catch (Throwable ignore) { }
            final String f1 = l1, f2 = l2, f3 = l3;
            uiPost(() -> {
                if (monBox.getVisibility() != VISIBLE) return;
                monL1.setText(f1);
                monL2.setText(f2);
                monL3.setText(f3);
            });
        }, "SeagullMon").start();
    }

    /* ------------------------- I4 指南针兜底（传感器） ------------------------- */

    private void startSensors() {
        if (sm != null) return;
        try {
            SensorManager m = (SensorManager) getContext()
                    .getSystemService(Context.SENSOR_SERVICE);
            Sensor a = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (a == null) return;   // N3 起加速度计单独够用；没有就整组静默降级
            sm = m;
            sm.registerListener(sensL, a, SensorManager.SENSOR_DELAY_UI);
            Sensor g = m.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (g != null) sm.registerListener(sensL, g, SensorManager.SENSOR_DELAY_UI);
            // 车机无磁力计很常见：指南针兜底自动失效（accOk&&magOk 门），G值块照常工作
        } catch (Throwable t) {
            sm = null;
        }
    }

    private void stopSensors() {
        if (sm == null) return;
        try { sm.unregisterListener(sensL); } catch (Throwable ignore) { }
        sm = null;
        accOk = false;
        magOk = false;
        sensAz = Float.NaN;
    }

    private final SensorEventListener sensL = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            if (e.values == null || e.values.length < 3) return;
            if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(e.values, 0, accV, 0, 3);
                accOk = true;
                updateG(e.values, SystemClock.elapsedRealtime());
            } else if (e.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(e.values, 0, magV, 0, 3);
                magOk = true;
            }
            long now = SystemClock.elapsedRealtime();
            if (!accOk || !magOk || now - lastSensAt < 500) return;
            lastSensAt = now;
            float[] r = new float[9], out = new float[9], v = new float[3];
            if (!SensorManager.getRotationMatrix(r, null, accV, magV)) return;
            // 车机固定横屏：把屏幕坐标系转 90°，否则方位角偏 90°
            SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_Y,
                    SensorManager.AXIS_MINUS_X, out);
            SensorManager.getOrientation(out, v);
            float az = (float) Math.toDegrees(v[0]);
            if (az < 0) az += 360f;
            // 低通平滑：磁力计原始值抖得厉害
            sensAz = Float.isNaN(sensAz) ? az : sensAz + shortestArc(sensAz, az) * 0.35f;
            if (sensAz < 0) sensAz += 360f;
            if (sensAz >= 360f) sensAz -= 360f;
            // 只有 GPS 航向失联时才补显（块可见 + 有权限才写）
            Location loc = LocationHelper.last();
            if (gpsBox.getVisibility() != VISIBLE
                    || !LocationHelper.hasPermission(getContext())) return;
            if (loc == null || !LocationHelper.fresh()) {
                gpsMid.setText(String.format(java.util.Locale.US, "海拔 -- · 航向 %s %.0f°",
                        bearingLabel(sensAz), sensAz));
            }
        }

        @Override public void onAccuracyChanged(Sensor s, int accuracy) { }
    };

    /** a→b 的最短角差（±180 内），供指数平滑用。 */
    private static float shortestArc(float a, float b) {
        float d = (b - a) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /* ------------------------- 通用小件 ------------------------- */

    private void askText(String title, String cur, java.util.function.Consumer<String> ok) {
        final android.widget.EditText et = new android.widget.EditText(getContext());
        et.setText(cur);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(getContext())
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", (d, w) -> ok.accept(et.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void uiPost(Runnable r) { post(r); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        clockBox.post(this::placeClockBox);
        gpsBox.post(this::placeGpsBox);
        cdBox.post(() -> placeBlock(cdBox, model.wallCdX, model.wallCdY));
        monBox.post(() -> placeBlock(monBox, model.wallMonX, model.wallMonY));
        musBox.post(() -> placeBlock(musBox, model.wallMusX, model.wallMusY));
        gBox.post(() -> placeBlock(gBox, model.wallGx, model.wallGy));
        for (Integer wid : new ArrayList<>(widgetPos.keySet())) refitWidget(wid);
    }

    /**
     * 尺寸+位置一次校正：组件实际大小按 provider 的 minWidth/minHeight 重算
     *（首帧前页面没量完，曾按 80dp 下限兜底），摆位按记录比例居中回位。
     */
    private void refitWidget(int id) {
        View v = findWidget(id);
        float[] pos = widgetPos.get(id);
        if (v == null || pos == null || getWidth() <= 0 || getHeight() <= 0) return;
        try {
            AppWidgetProviderInfo info =
                    AppWidgetManager.getInstance(getContext()).getAppWidgetInfo(id);
            if (info != null) {
                float s = pos.length > 2 && pos[2] > 0 ? pos[2] : 1f;
                int w = sizeDim(info.minWidth, s, getWidth(), 80);
                int h = sizeDim(info.minHeight, s, getHeight(), 60);
                LayoutParams lp = (LayoutParams) v.getLayoutParams();
                if (lp.width != w || lp.height != h) {
                    lp.width = w;
                    lp.height = h;
                    v.setLayoutParams(lp);
                }
            }
        } catch (Throwable ignore) {}
        // 改尺寸要等重新量完再摆（否则 measuredWidth 还是旧值，居中会偏）
        v.post(() -> {
            float[] p2 = widgetPos.get(id);
            if (p2 != null) placeWidget(v, p2[0], p2[1]);
        });
    }

    /* ------------------------- 小组件（AppWidgetHost） ------------------------- */

    private AppWidgetHost host() {
        if (host == null) {
            host = new AppWidgetHost(getContext().getApplicationContext(), HOST_ID);
        }
        return host;
    }

    /** 长按桌面空白：列出本机全部小组件，挑一个绑进来。 */
    private void showAddWidget() {
        AppWidgetManager awm = AppWidgetManager.getInstance(getContext());
        List<AppWidgetProviderInfo> all = awm.getInstalledProviders();
        final List<AppWidgetProviderInfo> picks = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (AppWidgetProviderInfo p : all) {
            if (p.provider == null) continue;
            String label;
            try {
                // 用 label 字段：stub 的 loadLabel() 返回类型与真机 framework 不一致，
                // 调用点描述符对不上会在真机抛 NoSuchMethodError（字段签名两边一致）
                label = p.label;
            } catch (Throwable t) {
                label = p.provider.getShortClassName();
            }
            if (label == null || label.trim().isEmpty()) label = p.provider.flattenToShortString();
            picks.add(p);
            names.add(label);
        }
        if (picks.isEmpty()) {
            android.widget.Toast.makeText(getContext(), "这台设备没有可用的小组件",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(getContext())
                .setTitle("添加小组件")
                .setItems(names.toArray(new String[0]), (d, w) -> bindTo(picks.get(w)))
                .show();
    }

    /** 分配 id → 直接绑（默认桌面免弹窗）；要授权就丢给系统 bind 页。 */
    private void bindTo(AppWidgetProviderInfo info) {
        try {
            final int id = host().allocateAppWidgetId();
            AppWidgetManager awm = AppWidgetManager.getInstance(getContext());
            if (awm.bindAppWidgetIdIfAllowed(id, info.provider)) {
                attachWidget(id, 0.5f, 0.42f, 1f);
                return;
            }
            pendingBindId = id;
            Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
            ((Activity) getContext()).startActivityForResult(i, REQ_BIND);
        } catch (Throwable t) {
            android.widget.Toast.makeText(getContext(), "添加失败：" + t,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** HomeActivity.onActivityResult 转发进来的 bind 授权回执。 */
    public void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_BIND || pendingBindId <= 0) return;
        int id = pendingBindId;
        pendingBindId = 0;
        if (res != Activity.RESULT_OK) {
            host().deleteAppWidgetId(id);
            return;
        }
        attachWidget(id, 0.5f, 0.42f, 1f);
    }

    /** 建 host 视图挂上墙：位置/倍率按记录（或默认中上），点选后可直接拖、把手缩放。 */
    private void attachWidget(final int id, float x, float y, float s) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(getContext());
            AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
            if (info == null) { host().deleteAppWidgetId(id); return; }
            AppWidgetHostView hv = host().createView((Activity) getContext(), id, info);
            float sc = s <= 0 ? 1f : s;
            int w = sizeDim(info.minWidth, sc, getWidth(), 80);
            int h = sizeDim(info.minHeight, sc, getHeight(), 60);
            widgetLayer.addView(hv, new LayoutParams(w, h, Gravity.TOP | Gravity.LEFT));
            widgetPos.put(id, new float[]{x, y, sc});
            placeWidget(hv, x, y);
            hv.setOnTouchListener(new Drag(hv,
                    (dx, dy) -> nudgeWidget(hv, id, dx, dy),
                    () -> selectWidget(id),
                    () -> showWidgetMenu(id),
                    this::persistWidgets,
                    () -> selWidgetId == id));
            persistWidgets();
            // 首帧前尺寸/位置都是近似值（页面还没量完），布局完成后按 provider 重算一次
            hv.post(() -> refitWidget(id));
        } catch (Throwable t) {
            android.widget.Toast.makeText(getContext(), "小组件挂载失败：" + t,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** 组件边长：provider 基准 × 缩放倍率，夹在 [minDp, 页宽/高 - 16dp]。 */
    private int sizeDim(float base, float s, int page, float minDp) {
        float d = getResources().getDisplayMetrics().density;
        int v = Math.round(base * d * s);
        return Math.max(Math.round(minDp * d), Math.min(v, Math.max(0, page - dp(16))));
    }

    private void placeWidget(View v, float x, float y) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        LayoutParams lp = (LayoutParams) v.getLayoutParams();
        // 审计 M5：同 placeClockBox，先比较后赋值
        int nl = clamp(Math.round(w * x - v.getMeasuredWidth() / 2f),
                dp(8), Math.max(dp(8), w - v.getMeasuredWidth() - dp(8)));
        int nt = clamp(Math.round(h * y), dp(8),
                Math.max(dp(8), h - v.getMeasuredHeight() - dp(8)));
        if (lp.leftMargin != nl || lp.topMargin != nt) {
            lp.leftMargin = nl;
            lp.topMargin = nt;
            v.setLayoutParams(lp);
        }
    }

    private void nudgeWidget(View v, int id, float dx, float dy) {
        LayoutParams lp = (LayoutParams) v.getLayoutParams();
        int w = getWidth(), h = getHeight();
        lp.leftMargin = clamp(lp.leftMargin + Math.round(dx), dp(8),
                Math.max(dp(8), w - v.getMeasuredWidth() - dp(8)));
        lp.topMargin = clamp(lp.topMargin + Math.round(dy), dp(8),
                Math.max(dp(8), h - v.getMeasuredHeight() - dp(8)));
        v.setLayoutParams(lp);
        float[] pos = widgetPos.get(id);
        if (pos != null && w > 0 && h > 0) {
            pos[0] = (lp.leftMargin + v.getMeasuredWidth() / 2f) / w;
            pos[1] = (float) lp.topMargin / h;
        }
        if (selWidgetId == id) syncSelFrame(v);
    }

    private void showWidgetMenu(final int id) {
        new AlertDialog.Builder(getContext())
                .setTitle("小组件")
                .setItems(new String[]{"重置大小", "移除小组件"}, (d, w) -> {
                    if (w == 0) {
                        float[] pos = widgetPos.get(id);
                        if (pos != null && pos.length > 2) pos[2] = 1f;
                        refitWidget(id);
                        persistWidgets();
                    } else {
                        if (selWidgetId == id) deselectWidget();
                        View v = findWidget(id);
                        if (v != null) widgetLayer.removeView(v);
                        widgetPos.remove(id);
                        try { host().deleteAppWidgetId(id); } catch (Throwable ignore) {}
                        persistWidgets();
                    }
                })
                .show();
    }

    private View findWidget(int id) {
        for (int i = 0; i < widgetLayer.getChildCount(); i++) {
            View v = widgetLayer.getChildAt(i);
            if (v instanceof AppWidgetHostView
                    && ((AppWidgetHostView) v).getAppWidgetId() == id) return v;
        }
        return null;
    }

    /** 开机/回来时按记录重建。组件被卸载的顺手清记录 + 释放 id。 */
    private void loadWidgets() {
        try {
            JSONArray arr = new JSONArray(model.wallWidgetsJson == null
                    ? "[]" : model.wallWidgetsJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                int id = o.optInt("id");
                if (id <= 0 || widgetPos.containsKey(id)) continue;
                AppWidgetManager awm = AppWidgetManager.getInstance(getContext());
                if (awm.getAppWidgetInfo(id) == null) {
                    try { host().deleteAppWidgetId(id); } catch (Throwable ignore) {}
                    continue;
                }
                widgetPos.put(id, new float[]{(float) o.optDouble("x", 0.5),
                        (float) o.optDouble("y", 0.42), (float) o.optDouble("s", 1)});
                attachWidget(id, (float) o.optDouble("x", 0.5),
                        (float) o.optDouble("y", 0.42), (float) o.optDouble("s", 1));
            }
        } catch (Throwable t) {
            android.util.Log.w("SeagullWall", "小组件记录恢复失败", t);
        }
    }

    private void persistWidgets() {
        try {
            JSONArray arr = new JSONArray();
            for (Map.Entry<Integer, float[]> e : widgetPos.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("id", e.getKey());
                o.put("x", e.getValue()[0]);
                o.put("y", e.getValue()[1]);
                if (e.getValue().length > 2) o.put("s", e.getValue()[2]);
                arr.put(o);
            }
            model.wallWidgetsJson = arr.toString();
            model.save();
        } catch (Throwable ignore) {}
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try { host().startListening(); } catch (Throwable ignore) { }
        syncBlocks();
        if (model.wallGpsEnabled && LocationHelper.hasPermission(getContext())) startSensors();
    }

    /** 设置页/菜单改动后的显隐总同步（幂等）：五块各刷一遍可见性。 */
    public void syncBlocks() {
        refreshGpsBlock();
        refreshCdBlock();
        refreshMonBlock();
        refreshMusBlock();
        refreshGBlock();
    }

    @Override protected void onDetachedFromWindow() {
        if (gpsAttached) { LocationHelper.release(gpsListener); gpsAttached = false; }
        stopSensors();
        try { if (host != null) host.stopListening(); } catch (Throwable ignore) { }
        super.onDetachedFromWindow();
    }

    /* ------------------------- 通用：拖动 + 捏合 ------------------------- */

    /**
     * 触摸语义（批次 CI 反馈后重做）：
     *  - 单击 = tap（组件进选中态出框和把手；时钟弹字号菜单）；
     *  - 长按后松手 = longTap（弹菜单）；armed（已选中）时按下即进入拖动，免长按；
     *  - 拖动 = onMove，松手 = end 落盘；长按/armed 拦掉 pager 横翻；
     *  - 双指捏合 = zoom（时钟连续缩放），factor 自捏合起点累计。
     */
    private final class Drag implements View.OnTouchListener {
        private final View target;
        private final Move onMove;
        private final Runnable tap, longTap, end, zoomBegin, zoomEnd;
        private final Zoom zoomMove;
        private final BooleanSupplier armed;
        private final GestureDetector gd = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }
                    @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                        if (tap != null) tap.run();
                        return true;
                    }
                    @Override public void onLongPress(MotionEvent e) {
                        dragging = true;
                        longPressed = true;
                        moved = false;
                        target.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                });
        private boolean dragging, moved, pinch, longPressed;
        private float lx, ly, spanStart, spanLast;

        /** 不带缩放的构造（组件用）。 */
        Drag(View target, Move onMove, Runnable tapAction, Runnable longTapAction,
             Runnable endAction, BooleanSupplier armedSupplier) {
            this(target, onMove, tapAction, longTapAction, endAction, armedSupplier,
                    null, null, null);
        }

        Drag(View target, Move onMove, Runnable tapAction, Runnable longTapAction,
             Runnable endAction, BooleanSupplier armedSupplier,
             Runnable zoomBeginAction, Zoom zoomMoveAction, Runnable zoomEndAction) {
            this.target = target;
            this.onMove = onMove;
            this.tap = tapAction;
            this.longTap = longTapAction;
            this.end = endAction;
            this.armed = armedSupplier;
            this.zoomBegin = zoomBeginAction;
            this.zoomMove = zoomMoveAction;
            this.zoomEnd = zoomEndAction;
        }

        @Override public boolean onTouch(View v, MotionEvent e) {
            gd.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lx = e.getRawX();
                    ly = e.getRawY();
                    if (armed != null && armed.getAsBoolean()) {
                        dragging = true;
                        moved = false;
                        target.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (zoomMove != null && e.getPointerCount() == 2) {
                        pinch = true;
                        moved = true;
                        spanStart = spanLast = span(e);
                        if (zoomBegin != null) zoomBegin.run();
                        target.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (pinch) {
                        float s = span(e);
                        if (spanStart > 0 && s != spanLast) {
                            zoomMove.run(s / spanStart);
                            spanLast = s;
                        }
                        break;
                    }
                    if (!dragging) break;
                    float dx = e.getRawX() - lx, dy = e.getRawY() - ly;
                    if (Math.abs(dx) + Math.abs(dy) > 4) moved = true;
                    lx = e.getRawX();
                    ly = e.getRawY();
                    onMove.run(dx, dy);
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (pinch) {
                        pinch = false;
                        if (zoomEnd != null) zoomEnd.run();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (pinch) {
                        pinch = false;
                        if (zoomEnd != null) zoomEnd.run();
                    }
                    if (dragging) {
                        // 审计 M14：armed 组件快速点按 —— DOWN 即 dragging=true，
                        // 旧实现 UP 走 longTap 弹菜单 + 300ms 后 onSingleTapConfirmed
                        // 再触发 tap，一次点击双动作。现在 armed 快速点按只留给
                        // onSingleTapConfirmed（tap），长按菜单看 longPressed 标记。
                        if (moved && end != null) end.run();
                        else if (longPressed && longTap != null) longTap.run();
                        dragging = false;
                    }
                    longPressed = false;
                    break;
            }
            return true;
        }

        /** 两指间距（视图坐标系，间距比值与绝对位置无关）。 */
        private float span(MotionEvent e) {
            float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
            return (float) Math.sqrt(dx * dx + dy * dy);
        }
    }

    /* ------------------------- 组件选中态 ------------------------- */

    /** 单击组件：选中（出框 + 缩放把手），此后直接拖就能挪，免长按。 */
    private void selectWidget(int id) {
        View t = findWidget(id);
        if (t == null) { deselectWidget(); return; }
        selWidgetId = id;
        syncSelFrame(t);
        selFrame.setVisibility(VISIBLE);
        selGrip.setVisibility(VISIBLE);
    }

    private void deselectWidget() {
        selWidgetId = -1;
        selFrame.setVisibility(GONE);
        selGrip.setVisibility(GONE);
    }

    /** 选中框贴住组件（外扩 4dp），把手贴右下角。 */
    private void syncSelFrame(View t) {
        LayoutParams lp = (LayoutParams) t.getLayoutParams();
        LayoutParams fp = (LayoutParams) selFrame.getLayoutParams();
        fp.leftMargin = lp.leftMargin - dp(4);
        fp.topMargin = lp.topMargin - dp(4);
        fp.width = lp.width + dp(8);
        fp.height = lp.height + dp(8);
        selFrame.setLayoutParams(fp);
        LayoutParams gp = (LayoutParams) selGrip.getLayoutParams();
        gp.leftMargin = lp.leftMargin + lp.width - dp(13);
        gp.topMargin = lp.topMargin + lp.height - dp(13);
        selGrip.setLayoutParams(gp);
    }

    /** 组件缩放倍率 = 当前宽 / provider 基准宽（恢复时按它还原大小）。 */
    private float widgetScale(View v) {
        try {
            AppWidgetProviderInfo info = AppWidgetManager.getInstance(getContext())
                    .getAppWidgetInfo(((AppWidgetHostView) v).getAppWidgetId());
            if (info != null && info.minWidth > 0) {
                float d = getResources().getDisplayMetrics().density;
                return Math.max(0.3f, v.getLayoutParams().width / (info.minWidth * d));
            }
        } catch (Throwable ignore) {}
        return 1f;
    }

    /** 右下角把手：拖动改组件宽高，松手把倍率落盘。 */
    private final View.OnTouchListener gripTouch = new View.OnTouchListener() {
        private float sx, sy;
        private int sw, sh;

        @Override public boolean onTouch(View v, MotionEvent e) {
            final View t = findWidget(selWidgetId);
            if (t == null) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = e.getRawX();
                    sy = e.getRawY();
                    sw = t.getLayoutParams().width;
                    sh = t.getLayoutParams().height;
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    LayoutParams lp = (LayoutParams) t.getLayoutParams();
                    lp.width = clamp(sw + Math.round(e.getRawX() - sx), dp(80),
                            Math.max(dp(80), getWidth() - dp(16)));
                    lp.height = clamp(sh + Math.round(e.getRawY() - sy), dp(60),
                            Math.max(dp(60), getHeight() - dp(16)));
                    t.setLayoutParams(lp);
                    syncSelFrame(t);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float[] pos = widgetPos.get(selWidgetId);
                    if (pos != null) {
                        int w = getWidth(), h = getHeight();
                        if (w > 0 && h > 0) {
                            LayoutParams lp2 = (LayoutParams) t.getLayoutParams();
                            pos[0] = (lp2.leftMargin + t.getMeasuredWidth() / 2f) / w;
                            pos[1] = (float) lp2.topMargin / h;
                        }
                        pos[2] = widgetScale(t);
                    }
                    persistWidgets();
                    return true;
            }
            return false;
        }
    };

    private static float clampf(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /* ------------------------- 刷新与工具 ------------------------- */

    /** 跟着 HomeActivity 的 30s / Lyrics sink 刷新。 */
    public void tick() {
        if (clock != null) clock.setText(SysOps.clockHHmm());
        if (date != null) date.setText(SysOps.dateCn());
        if (clock != null) clockBox.post(this::placeClockBox);

        // I2 农历行：超表/异常 → 隐藏，不留空行
        if (lunarRow != null) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            String ln = LunarCalendar.lineFor(c.get(java.util.Calendar.YEAR),
                    c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH));
            lunarRow.setText(ln);
            lunarRow.setVisibility(ln.isEmpty() ? GONE : VISIBLE);
        }

        // I3 倒计时：跨天也要走（块可见时每 tick 重算）
        if (cdBox != null && cdBox.getVisibility() == VISIBLE) updateCdRows();
        // I5 监控：30s 一个采样周期，后台线程
        if (monBox != null && monBox.getVisibility() == VISIBLE) refreshMonRows();
        // P3 OBD：转速/水温行 + 电压行 30s 兜底刷（1s 精刷走定位事件流）
        if (gpsBox != null && gpsBox.getVisibility() == VISIBLE) refreshObdRows();
        if (monBox != null && monBox.getVisibility() == VISIBLE) refreshMonVolt();
        // M5 音乐块：可见时跟队列/播放态；开着但还没扫过库就先扫一次
        if (musBox != null && musBox.getVisibility() == VISIBLE) { ensureMusicScanned(); refreshMusBlock(); }

        String now = Lyrics.nowPlaying();
        if (media != null) {
            media.setText(now == null ? "" : now);
            media.setVisibility(now == null || now.isEmpty() ? View.GONE : View.VISIBLE);
        }
        String line = "";
        try {
            String[] w = Lyrics.window(getContext(), model, 1);
            if (w != null && w.length > 0 && w[0] != null) line = w[0];
        } catch (Throwable ignore) {}
        if (lyric != null) {
            lyric.setText(line);
            lyric.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    /** 换肤后重刷配色。 */
    public void applySkin() {
        if (clock != null) clock.setTextColor(Skin.c(R.color.text));
        if (date != null) date.setTextColor(Skin.c(R.color.text_dim));
        if (lunarRow != null) lunarRow.setTextColor(Skin.c(R.color.text_dim));
        if (media != null) media.setTextColor(Skin.c(R.color.text));
        if (lyric != null) lyric.setTextColor(Skin.c(R.color.text_dim));
        if (gpsBig != null) gpsBig.setTextColor(Skin.c(R.color.text));
        if (gpsMid != null) gpsMid.setTextColor(Skin.c(R.color.text_dim));
        if (gpsTrip != null) gpsTrip.setTextColor(Skin.c(R.color.text_dim));
        if (cdBig != null) cdBig.setTextColor(Skin.c(R.color.text));
        if (cdSub != null) cdSub.setTextColor(Skin.c(R.color.text_dim));
        if (monL1 != null) monL1.setTextColor(Skin.c(R.color.text_dim));
        if (monL2 != null) monL2.setTextColor(Skin.c(R.color.text_dim));
        if (monL3 != null) monL3.setTextColor(Skin.c(R.color.text_dim));
        if (musL0 != null) musL0.setTextColor(Skin.c(R.color.leaf));
        if (musL1 != null) musL1.setTextColor(Skin.c(R.color.text_dim));
        if (musL2 != null) musL2.setTextColor(Skin.c(R.color.text_dim));
        if (musL3 != null) musL3.setTextColor(Skin.c(R.color.text_dim));
        if (gBig != null) gBig.setTextColor(Skin.c(R.color.leaf));
        if (gSub != null) gSub.setTextColor(Skin.c(R.color.text_dim));
    }

    private static TextView tv(Context c, float size, int colorRes, boolean bold) {
        TextView t = new TextView(c);
        t.setTextColor(Skin.c(colorRes));
        t.setTextSize(size);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static LinearLayout.LayoutParams topMargin(Context c, float dpTop) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dpTop,
                c.getResources().getDisplayMetrics()));
        return lp;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
