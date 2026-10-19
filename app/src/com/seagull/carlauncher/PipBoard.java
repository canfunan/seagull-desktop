package com.seagull.carlauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 画中画面板（批次 N 重写）—— 左右两块画布，单焦点，卡片化，空态大按钮。
 *
 * 参照 CarPlay Dashboard 的三条纪律移植到 VD 双画中画：
 *   ① 多卡片并排，但只有一个主焦点 —— 触摸只进焦点画布；
 *   ② 扫视优先 —— 空态是「＋ 选择应用」大按钮，不是一行小字；
 *   ③ 常驻 chrome 不被 App 覆盖 —— 卡片是普通 View 层，SurfaceView 在卡内。
 *
 * 单焦点规则（车机安全红线）：
 *   · 非焦点画布的第一下触摸 = 只切焦点，不吃进 App；给刚失焦的画布补 CANCEL
 *     （掐掉残留笔画，多指场景下的鬼拖痕全靠这个）；
 *   · 空画布没有 App 可误触，第一下触摸直接弹选择器（可发现性优先）。
 *
 * 卡片化：SurfaceView 保持方角不裁剪（clipToOutline 在部分设备对 SurfaceView
 * 失效，圆角等真机验证）。卡底比画布大出 {@link #CARD_PAD}，将来开真圆角
 * 不用改布局。
 *
 * 遮罩亮度：非焦点画布盖一层黑，透明度跟环境光三档（SensorManager.TYPE_LIGHT，
 * 公开 API 不要权限）：夜间 0.45 / 常态 0.35 / 白天强光 0.28。
 *
 * 常驻语义（批次 L）：VD 归进程级 MirrorHost，切走只断 Surface 不拆屏。
 */
public final class PipBoard extends LinearLayout {

    /** 宿主：长按画布 / 点空态按钮时由它弹应用选择。 */
    public interface Host {
        void onPickApp(int slot);
    }

    private static final String TAG = "SeagullPipBoard";
    private static final int REQ_CONSENT = 0x5EA4;
    private static final String PREFS = "seagull";

    private static final int SPLIT_W = 16;        // 分隔把手热区宽（自绘细线居中，肉眼几乎不可见）
    private static final float SPLIT_MIN = 0.22f; // 单侧最小占比，防止把手拖到某块画布消失
    private static final int FADE_MS = 150;       // 焦点/遮罩淡入，不做缩放弹跳（防分心）
    // 批次 AD：去框感 —— 描边、卡底、内边距全删，遮罩也压到几乎看不出"另一块被罩住"
    private static final float MASK_LOW = 0.10f;  // 白天强光：遮罩最薄，画面要看得清
    private static final float MASK_MID = 0.16f;  // 常态
    private static final float MASK_HI = 0.24f;   // 夜间：遮罩最厚
    private static final long LUX_INTERVAL_MS = 1000;
    private static final float LUX_NIGHT = 20f;   // 低于它算夜间（带回差）
    private static final float LUX_DAY = 2000f;   // 高于它算白天强光
    /** 批次 AA：底部常驻键条高度。显示时挤压画布盒的底边距，应用窗口随 VD resize。 */
    private static final int KEYBAR_DP = 34;

    private final Activity act;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LauncherModel model;

    /**
     * 录屏会话：两个槽各持一份独立 MediaProjection（批次 Q 修"第二个画布里是
     * 第一个画布"）。一个 MediaProjection 只能建一块虚拟屏；两块画布共用一个
     * 会话时，第二块 createVirtualDisplay 出来的屏会把第一块画面直接复印过来
     * （用户实测的"递归套娃"）。用同一次授权结果分别取两份会话，各建各的 VD。
     * VD 归进程级 MirrorHost 常驻，退出页面只断 Surface 不收。
     */
    private final MediaProjection[] proj = new MediaProjection[3];
    private MediaProjection projection;   // 兼容旧字段：proj[1] 的别名，勿单独赋值

    private MediaProjection projFor(int which) {
        MediaProjection p = which >= 1 && which <= 2 ? proj[which] : null;
        return p != null ? p : projection;
    }
    private MirrorSlot slotA, slotB;
    private SurfaceView svA, svB;
    private FrameLayout colA, colB;       // 两块画布的容器（单应用模式下隐藏其一）
    private FrameLayout boxA, boxB;       // 画布盒：Surface/提示/空态/遮罩都在里面，键条挤压它的底边距
    private LinearLayout barA, barB;      // 批次 AA：底部常驻键条（返回/桌面），单应用铺满时才显示
    private View splitter;                // 中间可拖的分隔把手：拖它 = 改两块画布的占比
    private TextView hintA, hintB;        // 画布中央状态行（启动中…/需要录屏授权…）
    private View emptyA, emptyB;          // 空态大按钮（空画布才有）
    private View maskA, maskB;            // 非焦点遮罩
    /** true = 单应用模式：一块画布铺满内容区，另一块藏起来但**不停用**（导航/播放不中断）。 */
    private boolean singleMode;
    private int singleWhich = 1;
    private boolean draggingSplit;
    private float splitDownRaw, splitDownRatio;
    /**
     * 拖动中的预览占比（&lt;0 = 没在拖）。
     *
     * 这是「拖了会变大变小、松手又弹回 55 开」的根治点：拖动过程**只画一条预览线**，
     * 一次都不动虚拟屏 —— 松手才真正改两块画布的宽度。原来每帧改 weight 会连发
     * resize，应用来不及按新尺寸重绘，看到的画面和触摸坐标就会错位。
     */
    private float previewRatio = -1f;
    /** 点画布 = 进入该应用（由 HomeActivity 注入：铺满内容区 + 同步底栏高亮）。 */
    private EnterListener enterListener;
    private final android.graphics.Paint previewPaint = new android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG);

    public interface EnterListener { void onEnter(int which); }

    public void setOnEnterSlot(EnterListener l) { this.enterListener = l; }
    /** 批次 AA：键条「桌面」键 = 退回桌面分屏（宿主同步底栏高亮/翻页），没注入就本地 showSplit。 */
    public interface ExitListener { void onExit(int which); }
    private ExitListener exitListener;

    public void setOnExitSlot(ExitListener l) { this.exitListener = l; }

    /** 批次 BB：键条「导航栏」键 = 收起/唤出底部 Dock 导航栏（动作由宿主注入）。 */
    public interface DockToggleListener { void onToggleDock(); }
    private DockToggleListener dockToggleListener;

    public void setOnToggleDock(DockToggleListener l) { this.dockToggleListener = l; }
    private final boolean[] longFired = {false, false, false};
    /** armed[i] = 本手势（从 DOWN 起）发生在这块画布已是焦点的时候，才允许外注入。 */
    private final boolean[] armed = {false, false, false};
    /** 录屏授权去重标志（两个槽同时需要投影时只弹一次）。 */
    private boolean consentRequesting = false;

    /** 单焦点：只有它能收触摸。初始槽 1。 */
    private int focusSlot = 1;

    /** 环境光三档得出的遮罩透明度。 */
    private float maskAlpha = MASK_MID;

    public PipBoard(Activity act, Host host) {
        this(act, host, true);
    }

    /** autoDeploy=false 给自检页用：镜框照建，但不抢在自检前面部署。 */
    public PipBoard(Activity act, Host host, boolean autoDeploy) {
        this(act, host, autoDeploy, new LauncherModel(act));
    }

    public PipBoard(Activity act, Host host, boolean autoDeploy, LauncherModel model) {
        super(act);
        this.act = act;
        this.host = host;
        this.model = model != null ? model : new LauncherModel(act);
        setOrientation(HORIZONTAL);
        setBackgroundColor(Skin.c(R.color.ground));
        setPadding(0, 0, 0, 0);   // 批次 AD：画布铺满内容区，不留任何内边距（去框感）

        svA = new SurfaceView(act);
        svB = new SurfaceView(act);
        hintA = new TextView(act);
        hintB = new TextView(act);

        colA = column(1);
        colB = column(2);
        addView(colA, new LinearLayout.LayoutParams(0, -1, Math.max(1, this.model.pipWeightA)));
        splitter = buildSplitter();
        addView(splitter, new LinearLayout.LayoutParams(dp(SPLIT_W), -1));
        addView(colB, new LinearLayout.LayoutParams(0, -1, Math.max(1, this.model.pipWeightB)));

        // 进程级常驻槽：上次退出没拆屏，直接拿回来接着用
        slotA = MirrorHost.slot(act, "pip1");
        slotB = MirrorHost.slot(act, "pip2");
        updateHints();
        applyFocusVisuals(false);
        if (autoDeploy) deployIfBound();
    }

    /* ------------------------- 生命周期（宿主转发） ------------------------- */

    public void onResume() {
        updateHints();
        applyFocusVisuals(false);
        ui.postDelayed(this::redeployIfPossible, 300);
        ui.postDelayed(this::selfHeal, 600);
        startRepin();   // 批次 AF：跳主屏后自动搬回（监听在线 8s 兜底拍，离线 2.5s）
        startWatch();   // 批次 AK：root 事件订阅，BUMP 触发自愈（毫秒级，替代靠拍等）
    }

    /**
     * 宿主重建 UI（换肤 / 换壁纸等）时调：只摘待跑的 redeploy/selfHeal 回调。
     * Surface 不在这里处理——SurfaceView 随旧树 detach 时 surfaceDestroyed
     * 会自然走到 slot.detachSurface（见建卡处的 Callback）。
     */
    public void cancelPending() { ui.removeCallbacksAndMessages(null); }

    /** 退出只断 Surface —— 屏与应用留给 MirrorHost 常驻；没有活跃屏才收 token。 */
    public void onDestroy() {
        // postDelayed(redeployIfPossible/selfHeal) 必须摘：300ms 内 finish 时它们
        // 仍会对已销毁 Activity 的 SurfaceView 起后台线程
        ui.removeCallbacksAndMessages(null);
        if (slotA != null) slotA.detachSurface();
        if (slotB != null) slotB.detachSurface();
        watching = false;
        // 订阅连接挂着后台线程，页面销毁就退订（onResume 会重订）；fork su 不能在主线程
        new Thread(PrivClient::unsubscribe, "pip-unwatch").start();
        if (MirrorSlot.activeCount() == 0) {
            stopProjections();
            PipProjectionService.stop(act);
        }
    }

    private void stopProjections() {
        for (int i = 1; i <= 2; i++) {
            if (proj[i] != null) {
                try { proj[i].stop(); } catch (Throwable ignore) {}
                proj[i] = null;
            }
        }
        projection = null;
    }

    /** 录屏授权结果。返回 true 表示本面板消费了这次回调。 */
    public boolean onActivityResult(int req, int res, Intent data) {
        if (req != REQ_CONSENT) return false;
        if (res != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "录屏授权被拒 → 投影兜底路径不可用");
            return true;
        }
        final int code = res;
        final Intent d = data;
        PipProjectionService.start(act);
        pollToken(code, d, 0);
        return true;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        SensorManager sm = (SensorManager) act.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;
        Sensor light = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (light == null) return;   // 车机没环境光：遮罩固定在常态档
        try { sm.registerListener(lightListener, light, SensorManager.SENSOR_DELAY_NORMAL); }
        catch (Throwable t) { Log.w(TAG, "环境光注册失败: " + t); }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        SensorManager sm = (SensorManager) act.getSystemService(Context.SENSOR_SERVICE);
        if (sm != null) { try { sm.unregisterListener(lightListener); } catch (Throwable ignore) {} }
    }

    /** 环境光 → 遮罩透明度。1s 采样 + 低通 + 三档带回差，别每帧动 alpha。 */
    private final SensorEventListener lightListener = new SensorEventListener() {
        private float avg = -1f;
        private long lastAt;

        @Override public void onSensorChanged(SensorEvent e) {
            if (e == null || e.values == null || e.values.length == 0) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastAt < LUX_INTERVAL_MS) return;
            lastAt = now;
            float lux = e.values[0];
            avg = (avg < 0f) ? lux : avg * 0.8f + lux * 0.2f;
            float target;
            if (avg < LUX_NIGHT) target = MASK_HI;
            else if (avg > LUX_DAY) target = MASK_LOW;
            else target = MASK_MID;
            if (Math.abs(target - maskAlpha) > 0.01f) {
                maskAlpha = target;
                applyMaskAlpha();
            }
        }

        @Override public void onAccuracyChanged(Sensor s, int a) {}
    };

    /* ------------------------- 两张卡片（左右分割） ------------------------- */

    /**
     * 一块画布 = 一个容器：**没有卡底、没有描边、没有内边距**（批次 AD）。
     * 画布与画布、画布与底栏之间因此零缝隙，用户看到的"屏幕"直接拼在一起。
     * 画布上方依次叠：状态行 / 空态大按钮 / 非焦点遮罩。
     * 触摸统一走容器 OnTouchListener（覆盖层都不是点击目标）。
     */
    private FrameLayout column(int which) {
        FrameLayout card = new FrameLayout(act);
        card.setBackground(null);
        card.setClipChildren(false);

        // 批次 AA：画布盒 —— Surface/提示/空态/遮罩全收进来；键条显示时挤压盒底边距，
        // 应用窗口跟着 VD resize（attachTo/syncSize 按盒实际尺寸走），不用改任何坐标换算。
        FrameLayout box = new FrameLayout(act);
        if (which == 1) boxA = box; else boxB = box;
        box.setClipChildren(false);

        SurfaceView view = which == 1 ? svA : svB;
        box.addView(view, new FrameLayout.LayoutParams(-1, -1));

        TextView hint = which == 1 ? hintA : hintB;
        hint.setText("长按选择应用");
        hint.setTextColor(Skin.c(R.color.text_dim));
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(0x80000000);
        hint.setVisibility(View.GONE);
        box.addView(hint, new FrameLayout.LayoutParams(-1, -1));

        View empty = which == 1 ? (emptyA = emptyButton()) : (emptyB = emptyButton());
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(-2, -2);
        ep.gravity = Gravity.CENTER;
        box.addView(empty, ep);
        // 空态大按钮自己收点击（它是可点击的 child，触摸不经卡容器）。
        // 【坑】卡内 SurfaceView 绝不能 setClickable(true)：一旦可点击，它会吞掉
        // 整块画布的触摸（批次 N 因此两块画布都弹不出选择器），卡容器的长按/转发
        // 全死。SurfaceView 保持不可点击，触摸统一走卡容器 OnTouchListener。

        View mask = new View(act);
        mask.setBackgroundColor(Color.BLACK);
        mask.setAlpha(0f);
        mask.setVisibility(View.GONE);
        if (which == 1) maskA = mask; else maskB = mask;
        box.addView(mask, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout keybar = buildKeybar(which);
        if (which == 1) barA = keybar; else barB = keybar;
        card.addView(box, new FrameLayout.LayoutParams(-1, -1));
        card.addView(keybar, new FrameLayout.LayoutParams(-1, dp(KEYBAR_DP), Gravity.BOTTOM));
        keybar.setVisibility(View.GONE);   // 分屏（预览）模式没有键条；进入单应用才顶出来

        // 批次 AD：删掉焦点描边。两块画布之间那条亮线正是用户说的"框框 / 边框感"。

        // 单一触摸入口：焦点拦截 / 空态选择 / 手势转发全在这判
        final int w = which;
        empty.setOnClickListener(v -> openPick(w));   // 空态按钮是卡内可点击 child，自己收点击
        final GestureDetector gd = new GestureDetector(act,
                new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public void onLongPress(MotionEvent e) {
                longFired[w] = true;
                MirrorSlot s = slotOf(w);
                if (s != null && s.ready()) cancelStroke(s);   // 掐掉残留笔画再弹选择器
                openPick(w);
            }
        });
        card.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            MirrorSlot s = slotOf(w);
            if (singleMode) {
                // 单应用模式：整块画布就是应用界面本身，触摸全量转发（多指/长按都成立）。
                // 坐标按画布实际尺寸逐轴换算（批次 AE）——铺满/缩半都不跑偏。
                if (a == MotionEvent.ACTION_DOWN) {
                    if (w != focusSlot) switchFocus(w);
                    syncCanvasSize(s, view);
                }
                if (s != null && s.ready()) s.onTouch(e, view.getWidth(), view.getHeight());
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    armed[w] = false;
                    // 应用内跳页可能把任务拉回默认屏（singleTask 拉回，参考 xphook
                    // 用 startActivity 注入堵的就是这个）——抬手后补两次自愈
                    if (a == MotionEvent.ACTION_UP) healSlotSoon(w);
                }
                return true;
            }
            // 分屏模式：画布只当预览 —— 点一下 = 进入该应用（铺满内容区、底栏跟随），
            // 手势不再往应用里注。两层好处：小画布上不会误操作应用；也彻底避开
            // 「半个画布 + 虚拟屏 resize」期间的坐标错位。
            if (a == MotionEvent.ACTION_DOWN) {
                longFired[w] = false;
                if (w != focusSlot) switchFocus(w);
                syncCanvasSize(s, view);
                gd.onTouchEvent(e);
                return true;
            }
            gd.onTouchEvent(e);
            if (a == MotionEvent.ACTION_UP && !longFired[w]) {
                if (s == null || !s.ready() || pkgOf(w).isEmpty()) openPick(w);
                else enterSlot(w);
            }
            if (a == MotionEvent.ACTION_CANCEL) longFired[w] = false;
            return true;
        });

        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {}
            @Override public void surfaceChanged(SurfaceHolder h, int f, int wd, int ht) {
                MirrorSlot s = slotOf(w);
                if (s == null || wd <= 0 || ht <= 0) return;
                cvW[w] = wd;
                cvH[w] = ht;
                attachTo(s, h.getSurface(), wd, ht);
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                MirrorSlot s = slotOf(w);
                if (s != null) s.detachSurface();
                updateHints();
            }
        });
        // 注意：这里绝不能 view.setClickable(true) —— 见上面 openPick 处的说明。
        return card;
    }

    private MirrorSlot slotOf(int which) { return which == 1 ? slotA : slotB; }

    /**
     * 手势开始前把虚拟屏尺寸对齐到画布实际像素。
     *
     * 「点了选项却错位」的根因就在这：画布被拉伸/改宽之后，若虚拟屏没有跟着 resize
     * 到同一尺寸，应用仍按旧尺寸出画面、被合成器缩放到画布上，而触摸按新尺寸原样注入
     * —— 越往右下偏得越多。这里在每根手势的 DOWN 上校一次，尺寸漂了就 resize 一次。
     */
    private void syncCanvasSize(MirrorSlot s, SurfaceView sv) {
        if (s == null || sv == null) return;
        int wd = sv.getWidth();
        int ht = sv.getHeight();
        if (wd <= 0 || ht <= 0) return;
        int w = (s == slotA) ? 1 : 2;
        cvW[w] = wd;
        cvH[w] = ht;
        // 批次 CC：DOWN 校准只对齐像素尺寸，密度不动（对齐车联助手 —— 密度恒定，
        // 点击期间不触发应用 density 重配/重排；密度只在建屏/挂面时由 dpiFit 定）。
        s.syncSize(wd, ht);
    }

    /** 点画布进入应用：交给 HomeActivity（切单应用 + 同步底栏高亮），没注入就本地降级。 */
    private void enterSlot(int which) {
        if (enterListener != null) { enterListener.onEnter(which); return; }
        showSingle(which);
    }

    /* ------------------------- 画布占比：可拖的分隔把手 ------------------------- */

    /**
     * 分隔把手（批次 AD 新增）：两块画布中间那 16dp 热区，居中自绘一条几乎看不见的
     * 细线 + 三颗握持点，拖动它就改两块画布的宽度占比（例如让地图占得更宽）。
     *
     * 拖动时只改 layout weight（画布实时跟着变），**松手才落盘**：
     *   · 拖动过程不写 SharedPreferences，避免每帧 IO；
     *   · 落盘后下次进桌面沿用这个占比。
     * 只改布局尺寸、不禁用/重启任何应用 —— 地图导航、音乐播放都不会中断。
     */
    private View buildSplitter() {
        final View h = new View(act) {
            private final android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);

            @Override protected void onDraw(android.graphics.Canvas c) {
                if (singleMode || draggingSplit) return;
                float cx = getWidth() / 2f;
                float cy = getHeight() / 2f;
                p.setStyle(android.graphics.Paint.Style.FILL);
                p.setColor(0x1FFFFFFF);                 // 握持区底色，让人找得到这条把手
                c.drawRoundRect(cx - dp(9), cy - dp(36), cx + dp(9), cy + dp(36),
                        dp(9), dp(9), p);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                p.setStrokeWidth(Math.max(2f, dp(2)));
                p.setColor(0x66FFFFFF);
                c.drawLine(cx, cy - dp(22), cx, cy + dp(22), p);
            }
        };
        h.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (singleMode) return false;
                    draggingSplit = true;
                    splitDownRaw = e.getRawX();
                    splitDownRatio = currentRatio();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    PipBoard.this.invalidate();
                    h.invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!draggingSplit) return true;
                    int w = PipBoard.this.getWidth();
                    if (w <= 0) return true;
                    // 只更新预览线位置，绝不改 layout：一次 resize 都不发
                    previewRatio = clampRatio(splitDownRatio + (e.getRawX() - splitDownRaw) / w);
                    PipBoard.this.invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!draggingSplit) return true;
                    draggingSplit = false;
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    float r = previewRatio > 0f ? previewRatio : currentRatio();
                    previewRatio = -1f;
                    applySplitRatio(r, true);   // 松手才真正改宽度：整个拖动过程只 resize 一次
                    PipBoard.this.invalidate();
                    h.invalidate();
                    return true;
                }
                default:
                    return true;
            }
        });
        return h;
    }

    /** 拖动预览线：画在画布之上、底栏之下，松手前不碰任何虚拟屏。 */
    @Override protected void dispatchDraw(android.graphics.Canvas c) {
        super.dispatchDraw(c);
        if (previewRatio <= 0f) return;
        float x = getWidth() * previewRatio;
        previewPaint.setColor(0xFF0F77FF);              // 品牌蓝：拖动时唯一的高亮
        previewPaint.setStrokeWidth(Math.max(2f, dp(2)));
        previewPaint.setStyle(android.graphics.Paint.Style.STROKE);
        previewPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        c.drawLine(x, dp(8), x, getHeight() - dp(8), previewPaint);
    }

    private static float clampRatio(float r) {
        if (r < SPLIT_MIN) return SPLIT_MIN;
        if (r > 1f - SPLIT_MIN) return 1f - SPLIT_MIN;
        return r;
    }

    /** 当前左画布占比（0~1）。 */
    private float currentRatio() {
        float a = Math.max(1, model.pipWeightA);
        float b = Math.max(1, model.pipWeightB);
        return a / (a + b);
    }

    /** 设占比并立即改两块画布的 weight；persist=true 时落盘（松手那一下）。 */
    private void applySplitRatio(float ratio, boolean persist) {
        if (colA == null || colB == null) return;
        ratio = clampRatio(ratio);
        int a = Math.max(1, Math.round(ratio * 100f));
        int b = Math.max(1, 100 - a);
        LinearLayout.LayoutParams la = (LinearLayout.LayoutParams) colA.getLayoutParams();
        LinearLayout.LayoutParams lb = (LinearLayout.LayoutParams) colB.getLayoutParams();
        la.weight = a;
        lb.weight = b;
        colA.setLayoutParams(la);
        colB.setLayoutParams(lb);
        if (persist) {
            try {
                model.pipWeightA = a;
                model.pipWeightB = b;
                model.save();
                Log.i(TAG, "画布占比已落盘 A:B = " + a + ":" + b);
            } catch (Throwable t) {
                Log.w(TAG, "画布占比落盘失败", t);
            }
        }
    }

    /* ------------------------- 键条（批次 AA） ------------------------- */

    /**
     * 底部常驻键条【返回 | 桌面】—— 只在单应用铺满时显示。
     *
     * 为什么必须由桌面画：我们的画中画是 VirtualDisplay（flags=0 私有屏），
     * 系统装饰（导航栏）只属于带 system decorations 的屏 —— SystemUI 不会在
     * 私有 VD 上画导航栏，应用自己也没有「底部导航栏」这个形态（反编译：
     * 车机版横屏=左侧栏、竖屏=顶部 Tab）。键条是画布内导航的唯一可靠入口：
     *   · 返回 = input -d <id> keyevent 4（KEYCODE_BACK 注进应用）；
     *   · 桌面 = 退回分屏主页（与 DockBar.HOME 同一路径），应用留在后台不杀。
     * 键条是普通 View 层（PipBoard 纪律③：常驻 chrome 不被 App 覆盖），
     * 应用自己的沉浸 flag、全屏请求都影响不到它。
     */
    private LinearLayout buildKeybar(int which) {
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xF2101418);
        bar.setGravity(Gravity.CENTER);
        bar.setClickable(true);   // 吃掉键条区域的触摸，别穿透进应用（尤其左右两键之间）
        int vpad = dp(4);

        TextView back = keyButton("返回");
        back.setOnClickListener(v -> {
            MirrorSlot s = slotOf(which);
            final int d = s != null ? s.displayId() : -1;
            if (d <= 0) return;
            new Thread(() -> RootOps.injectKey(d, 4), "pip-key-back" + which).start();
        });

        View sep = new View(act);
        LinearLayout.LayoutParams seplp = new LinearLayout.LayoutParams(dp(1), -1);
        seplp.setMargins(dp(2), vpad, dp(2), vpad);
        sep.setBackgroundColor(0x33FFFFFF);

        TextView home = keyButton("桌面");
        home.setOnClickListener(v -> {
            if (exitListener != null) exitListener.onExit(which);
            else showSplit();
        });

        // 批次 BB：第三个键「导航栏」—— 收起/唤出底部 Dock 导航栏。
        // 单应用模式进来自动收起（应用拿满整屏），想用 Dock 点它唤出，再点收起。
        View sep2 = new View(act);
        LinearLayout.LayoutParams seplp2 = new LinearLayout.LayoutParams(dp(1), -1);
        seplp2.setMargins(dp(2), vpad, dp(2), vpad);
        sep2.setBackgroundColor(0x33FFFFFF);
        TextView dockKey = keyButton("导航栏");
        dockKey.setOnClickListener(v -> {
            if (dockToggleListener != null) dockToggleListener.onToggleDock();
        });

        bar.addView(back, new LinearLayout.LayoutParams(0, -1, 1f));
        bar.addView(sep, seplp);
        bar.addView(home, new LinearLayout.LayoutParams(0, -1, 1f));
        bar.addView(sep2, seplp2);
        bar.addView(dockKey, new LinearLayout.LayoutParams(0, -1, 1f));
        return bar;
    }

    /** 键条按钮：等宽、白字、车机可点的最小高度，按压出反馈色。 */
    private TextView keyButton(String text) {
        TextView b = new TextView(act);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(0xF0FFFFFF);
        b.setTextSize(13);
        b.setClickable(true);
        b.setHapticFeedbackEnabled(true);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(dp(8));
        b.setBackground(g);
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    b.setBackgroundColor(0x30FFFFFF);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    b.setBackground(null);
                    break;
            }
            return false;   // 继续走 click
        });
        return b;
    }

    /** 单应用模式：把某块画布铺满内容区。另一块只是隐藏，**不销毁、不杀进程**。 */
    public void showSingle(int which) {
        if (which != 1 && which != 2) return;
        singleMode = true;
        singleWhich = which;
        applySingleVisuals();
    }

    /** 回到分屏（桌面主页）。 */
    public void showSplit() {
        if (!singleMode) return;
        singleMode = false;
        applySingleVisuals();
    }

    public boolean isSingle() { return singleMode; }

    private void applySingleVisuals() {
        if (colA == null || colB == null) return;
        LinearLayout.LayoutParams la = (LinearLayout.LayoutParams) colA.getLayoutParams();
        LinearLayout.LayoutParams lb = (LinearLayout.LayoutParams) colB.getLayoutParams();
        if (singleMode) {
            boolean a = singleWhich == 1;
            colA.setVisibility(a ? View.VISIBLE : View.GONE);
            colB.setVisibility(a ? View.GONE : View.VISIBLE);
            la.weight = a ? 1 : 0;
            lb.weight = a ? 0 : 1;
            if (splitter != null) {
                splitter.setVisibility(View.GONE);
                splitter.requestLayout();
            }
        } else {
            colA.setVisibility(View.VISIBLE);
            colB.setVisibility(View.VISIBLE);
            la.weight = Math.max(1, model.pipWeightA);
            lb.weight = Math.max(1, model.pipWeightB);
            if (splitter != null) {
                splitter.setVisibility(View.VISIBLE);
                splitter.invalidate();
            }
        }
        colA.setLayoutParams(la);
        colB.setLayoutParams(lb);
        applyKeybar(singleMode ? singleWhich : 0);
        applyFocusVisuals(false);
        reattachAll();
    }

    /**
     * 批次 AA：键条显隐 + 画布盒底边距挤压。
     * which=0 是分屏 —— 两条键条都藏、盒边距归零；单应用只亮当前槽那条。
     * 挤压盒而不是留键条叠在画面上：SurfaceView 变小 → surfaceChanged →
     * attachTo 按 new 尺寸 resize VD，应用窗口天然不被键条盖住（私有 VD 没有
     * SystemUI 导航栏，应用布局不知道要留边，只能物理让位）。
     */
    private void applyKeybar(int which) {
        int squeeze = which != 0 ? dp(KEYBAR_DP) : 0;
        if (boxA != null) {
            FrameLayout.LayoutParams pa = (FrameLayout.LayoutParams) boxA.getLayoutParams();
            if (pa.bottomMargin != squeeze) {
                pa.bottomMargin = squeeze;
                boxA.setLayoutParams(pa);
            }
        }
        if (boxB != null) {
            FrameLayout.LayoutParams pb = (FrameLayout.LayoutParams) boxB.getLayoutParams();
            if (pb.bottomMargin != squeeze) {
                pb.bottomMargin = squeeze;
                boxB.setLayoutParams(pb);
            }
        }
        if (barA != null) barA.setVisibility(which == 1 ? View.VISIBLE : View.GONE);
        if (barB != null) barB.setVisibility(which == 2 ? View.VISIBLE : View.GONE);
    }

    /**
     * 单应用 ↔ 分屏切换后，把两块画布的 Surface 重新挂上。
     *
     * 「返回桌面后第二块画布黑屏」的根因：一列被 GONE 掉时 SurfaceView 会 surfaceDestroyed，
     * 此时 detachSurface 只断输出（VD 和任务栈都还在），但再显示时 surfaceChanged 未必
     * 会再来一次，或者来得太早（VD 刚做完 resize）。这里等布局稳定一帧后主动重挂 +
     * 幂等补部署（已在跑就只搬屏），应用不会重启，导航/播放不断。
     */
    public void reattachAll() {
        postDelayed(() -> {
            redeployIfPossible();
            deployIfBound();
        }, 180);
    }

    /** 空态大按钮：≥80dp 高、16dp 圆角、半透明白描边、图标+文字上下排布。 */
    private View emptyButton() {
        TextView b = new TextView(act);
        b.setText("＋\n选择应用");
        b.setGravity(Gravity.CENTER);
        b.setTextColor(0xF0FFFFFF);
        b.setTextSize(18);
        b.setMinHeight(dp(80));
        b.setMinWidth(dp(160));
        b.setPadding(dp(24), dp(16), dp(24), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x33FFFFFF);
        g.setStroke(dp(1), 0x80FFFFFF);
        g.setCornerRadius(dp(16));
        b.setBackground(g);
        return b;
    }

    /* ------------------------- 单焦点 ------------------------- */

    /**
     * 切焦点：给刚失焦的画布补一个 CANCEL —— 多指场景（一指在左画布拖着、
     * 二指点右画布）下，左 App 留着一道鬼拖痕全靠这个掐掉。守护中继通道
     * 会把 CANCEL 真的送进 App；命令通道本来就丢弃 CANCEL。
     */
    private void switchFocus(int to) {
        if (to == focusSlot) return;
        int from = focusSlot;
        focusSlot = to;
        Log.i(TAG, "焦点 槽" + from + " → 槽" + to);
        // 撤掉旧画布的在途手势武装：多指时一指正拖着旧画布，此后它的 MOVE/UP
        // 必须全部作废（只补 CANCEL 不够，armed 不撤事件照样注进旧 App）。
        armed[from] = false;
        longFired[from] = false;
        cancelStroke(slotOf(from));
        applyFocusVisuals(true);
    }

    /** 外部导焦（MiniPlayer 空白区点击用）。 */
    public void focusSlot(int which) {
        if (which == 1 || which == 2) switchFocus(which);
    }

    private void cancelStroke(MirrorSlot s) {
        if (s == null || !s.ready()) return;
        long now = SystemClock.uptimeMillis();
        MotionEvent c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
        try {
            int w = (s == slotA) ? 1 : 2;
            s.onTouch(c, cvW[w], cvH[w]);
        } catch (Throwable t) { Log.w(TAG, "失焦 CANCEL 失败: " + t); }
        c.recycle();
    }

    /** 非焦点画布只盖一层很薄的环境光遮罩（批次 AD 已删描边，不再有任何边框）。 */
    private void applyFocusVisuals(boolean animate) {
        for (int i = 1; i <= 2; i++) {
            View mask = i == 1 ? maskA : maskB;
            boolean focus = (i == focusSlot);
            boolean bound = !pkgOf(i).isEmpty();
            if (mask != null) {
                mask.setVisibility(!focus && bound ? View.VISIBLE : View.GONE);
                if (!focus && bound) {
                    if (animate) mask.animate().alpha(maskAlpha).setDuration(FADE_MS).start();
                    else mask.setAlpha(maskAlpha);
                }
            }
        }
    }

    private void applyMaskAlpha() {
        for (int i = 1; i <= 2; i++) {
            View mask = i == 1 ? maskA : maskB;
            if (mask != null && mask.getVisibility() == View.VISIBLE) {
                mask.animate().alpha(maskAlpha).setDuration(FADE_MS).start();
            }
        }
    }

    /* ------------------------- 提示 / 空态显隐 ------------------------- */

    private void updateHints() {
        setHint(1, slotA);
        setHint(2, slotB);
        applyFocusVisuals(false);
    }

    private void setHint(int which, MirrorSlot s) {
        TextView hint = which == 1 ? hintA : hintB;
        View empty = which == 1 ? emptyA : emptyB;
        if (hint == null) return;
        String pkg = pkgOf(which);
        if (pkg == null || pkg.isEmpty()) {
            // 空态：大按钮占位，状态行藏起
            hint.setVisibility(View.GONE);
            if (empty != null) empty.setVisibility(View.VISIBLE);
            return;
        }
        if (empty != null) empty.setVisibility(View.GONE);
        if (s != null && s.ready()) {
            // 批次 AE：刚挂上 900ms 内先报「画面就绪中…」，等应用出第一帧再撤
            // （学 Vivo MapSurfaceViewHelper 的封面延迟揭示，AMap 给 2 秒）
            if (SystemClock.uptimeMillis() < revealUntil[which]) {
                hint.setText("画面就绪中…");
                hint.setVisibility(View.VISIBLE);
            } else {
                hint.setVisibility(View.GONE);
            }
            return;
        }
        if (s != null && s.needsProjection()) hint.setText("需要录屏授权…");
        else if (s != null && s.displayId() > 0) hint.setText("挂载中…");
        else hint.setText("启动中…");
        hint.setVisibility(View.VISIBLE);
    }

    /* ------------------------- 授权链 ------------------------- */

    public void requestConsent() {
        // 去重：两个槽同时要投影时会连弹两次授权，旧 MediaProjection 被直接覆盖
        // （不 stop 不反注册回调，录屏会话泄漏到进程结束）
        if (consentRequesting) { Log.i(TAG, "录屏授权进行中，跳过重复申请"); return; }
        consentRequesting = true;
        // allowProjectMedia 是 su 命令（数百 ms），丢后台；startActivityForResult 回主线程
        new Thread(() -> {
            RootOps.allowProjectMedia(act);
            ui.post(() -> {
                try {
                    MediaProjectionManager mpm = (MediaProjectionManager)
                            act.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                    act.startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CONSENT);
                } catch (Throwable t) {
                    Log.w(TAG, "申请录屏失败: " + t);
                } finally {
                    consentRequesting = false;
                }
            });
        }, "pip-consent").start();
    }

    private void pollToken(int code, Intent data, int tries) {
        if (PipProjectionService.running) {
            try {
                MediaProjectionManager mpm =
                        (MediaProjectionManager) act.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                MediaProjection fresh = mpm.getMediaProjection(code, data);
                if (fresh == null) { Log.w(TAG, "getMediaProjection 返回 null"); return; }
                // 覆盖前先停旧的：否则旧录屏会话 + 回调泄漏到进程结束
                stopProjections();
                proj[1] = fresh;
                // 第二份会话（同一次授权结果再取一次）。拿不到就退而共用一份：
                // 那样槽 2 的 VD 会复印槽 1 的画面，日志留证，至少能定位。
                MediaProjection second = null;
                try {
                    second = mpm.getMediaProjection(code, data);
                } catch (Throwable t) {
                    Log.w(TAG, "第二份投影会话取得失败: " + t);
                }
                proj[2] = second;
                if (second == null) {
                    proj[2] = fresh;
                    Log.w(TAG, "拿不到第二份投影会话：槽 2 将复用槽 1 的（画面可能嵌套，"
                            + "看 dumpsys display | grep -i virtual 只有一块屏）");
                } else {
                    Log.i(TAG, "两份投影会话就绪：两槽各自建各自的虚拟屏");
                }
                projection = fresh;
                MediaProjection.Callback cb = new MediaProjection.Callback() {
                    @Override public void onStop() {
                        Log.w(TAG, "录屏被系统撤销");
                        MirrorHost.onProjectionStopped(act);
                    }
                };
                fresh.registerCallback(cb, ui);
                if (second != null) second.registerCallback(cb, ui);
                deployIfBound();
            } catch (Throwable t) {
                Log.w(TAG, "getMediaProjection 失败: " + t);
            }
            return;
        }
        if (tries > 10) { Log.w(TAG, "投屏前台服务未能启动（轮询超时）"); return; }
        ui.postDelayed(() -> pollToken(code, data, tries + 1), 200);
    }

    /* ------------------------- 部署 ------------------------- */

    /** 有绑定的画布就自动部署（进页面即画中画，不需要用户点任何按钮）。 */
    public void deployIfBound() {
        deploySlot(1);
        deploySlot(2);
    }

    private void deploySlot(int which) {
        MirrorSlot slot = slotOf(which);
        if (slot == null) return;
        String pkg = pkgOf(which);
        if (pkg == null || pkg.isEmpty()) { setHint(which, slot); return; }
        SurfaceView svView = which == 1 ? svA : svB;
        Surface surf = (svView != null && svView.getHolder().getSurface() != null
                && svView.getHolder().getSurface().isValid()) ? svView.getHolder().getSurface() : null;
        // 【批次 AE】画布还没 Surface 时绝不盲部署：
        //   · VD 已在（换应用/重挂场景）→ deploy 内部幂等处理；
        //   · VD 不在 → 首次部署一律等 surfaceChanged → attachTo 带真 surface 来，
        //     屏和任务都诞生在真实尺寸上。旧逻辑在这里用 designW 估的假尺寸 +
        //     null surface 建屏，正是「黑屏 + 应用被反复冷启动」的源头。
        if (surf == null) { setHint(which, slot); return; }
        final Surface f = surf;
        // 批次 CE：建屏用固定屏尺寸（单应用画布尺寸），不用当前分屏画布尺寸 ——
        // VD 尺寸终身不变，见 fixedVdSize() 注释。
        final int[] fs = fixedVdSize();
        final int fw = fs[0], fh = fs[1];
        final MirrorSlot s = slot;
        final MediaProjection mp = projFor(which);
        setHint(which, slot);
        new Thread(() -> {
            // 同一槽的部署/挂面串行：并行会双建 VD
            synchronized (s) {
                boolean ok = s.deploy(mp, pkg, f, fw, fh, dpiFit(fw, fh));
                final boolean needProj = s.needsProjection();
                ui.post(() -> {
                    setHint(which, s);
                    // 两块画布抢同一块 VD 的检测网：哪块屏被两个槽同时握着，
                    // 槽 2 直接拆掉并报错。静默共屏的后果就是"画布里套着画布"。
                    MirrorSlot other = slotOf(which == 1 ? 2 : 1);
                    if (s.displayId() > 0 && other != null && other.displayId() == s.displayId()) {
                        Log.e(TAG, "槽 " + which + " 与对槽共用 displayId=" + s.displayId()
                                + "（VD 独立失败，画面会嵌套）→ 拆掉本槽");
                        s.teardown();
                    }
                    Log.i(TAG, "槽 " + which + " → " + s.describe() + (ok ? "" : " [失败]"));
                    if (needProj && mp == null) requestConsent();
                });
            }
        }, "pip-deploy" + which).start();
        // 【批次 AI/AJ】部署 8 秒后铺满巡检：两轮抖动（2s/4.5s 起，各持续 ~2.2s）
        // 都结束后才判定。窗口帧 < 屏尺寸 92% → 冷启动修复（模块 D 段已装时，
        // 新进程 attachBaseContext 会被改写成横屏，新进程必然铺满）。
        // 解析失败绝不动进程（宁可漏修不可误杀）。
        ui.postDelayed(() -> new Thread(() -> {
            try {
                MirrorSlot s2 = slotOf(which);
                if (s2 == null || s2.displayId() <= 0) return;
                String out = RootOps.ensureFilled(act, pkg, s2.displayId(), s2.vdW(), s2.vdH());
                Log.i(TAG, "铺满巡检 槽 " + which + ": " + out);
            } catch (Throwable t) {
                Log.w(TAG, "铺满巡检异常: " + t);
            }
        }, "pip-fill" + which).start(), 8000);
    }

    /**
     * 画布像素 → VD 密度（批次 Q 修"高德四周黑边"）。
     *
     * 黑边的主因不是 VD 尺寸，是密度：直接把手机 densityDpi（440）传给一块
     * 366px 宽的小屏，应用看到的是 ~85dp 视口，多数 App（高德）把它当小屏，
     * 按自己声明的最小宽度渲染后居中留黑边（size compat 表现）。
     * 折成 280dp 基准再反算 dpi：画布 366px @ 209dpi ≈ 280dp 宽，与一台紧凑
     * 手机视口同量级，应用按正常手机版式铺满。触摸坐标不受 dpi 影响（仍 1:1）。
     */
    private int dpiFit(int w, int h) {
        int def = act.getResources().getDisplayMetrics().densityDpi;
        int targetDp = 280;                       // 紧凑手机视口宽
        int byW = Math.round(w * 160f / targetDp);
        int byH = Math.round(h * 160f / targetDp);
        int v = Math.max(byW, byH);               // 取大：宽高两侧视口都不超过目标 dp
        if (v < 160) v = 160;                      // 再小就 mdpi，低于它应用会按低密度锯齿渲染
        if (v > def) v = def;                      // 不超过设备默认，别让字大过设计稿
        return v;
    }

    /** 没量到画布时的兜底宽度：整屏扣掉中间分隔把手，再按权重比例切。 */
    private int designW(int which) {
        int avail = act.getResources().getDisplayMetrics().widthPixels - dp(SPLIT_W);
        int wa = Math.max(1, model.pipWeightA);
        int wb = Math.max(1, model.pipWeightB);
        int w = which == 1 ? wa : wb;
        return Math.max(1, (int) ((long) avail * w / (wa + wb)));
    }

    /** 给外部（选了新应用后）调：部署指定槽。 */
    public void deploySlotPublic(int which) { deploySlot(which); }

    /**
     * 批次 CE：固定屏尺寸 —— 单应用模式的画布尺寸（本容器全宽 × 高-键条）。
     * VD 建屏用这个尺寸，之后**终身不 resize**；分屏预览靠 SurfaceView 缩放显示，
     * 进单应用正好 1:1。依据（网易云 3.1.0 反编译坐实）：
     *   · manifest resizeableActivity="false" —— 系统把每次 VD resize 当"应用不支持
     *     的配置变化"处理；
     *   · IotPlayerActivity（播放/歌词页）没声明任何 configChanges → resize 即重建；
     *   · MainActivity.onCreate 主动 remove("android:support:fragments") → 重建必回首页。
     * 旧架构 VD 跟画布走（分屏↔单应用↔键条挤压全在 resize），每次「桌面」往返都是
     * 一场 Activity 重建风暴，歌词页必丢。车联助手同款不变量：屏尺寸终身不变。
     */
    private int[] fixedVdSize() {
        int w = getWidth() > 0 ? getWidth()
                : designW(1) + designW(2) + dp(SPLIT_W);
        int h = getHeight() > 0 ? getHeight() - dp(KEYBAR_DP)
                : Math.max(1, act.getResources().getDisplayMetrics().heightPixels
                        - dp(150) - dp(KEYBAR_DP));
        return new int[]{Math.max(1, w), Math.max(1, h)};
    }

    private void attachTo(MirrorSlot slot, Surface surface, int w, int h) {
        if (surface == null || !surface.isValid()) return;
        final int which = slot == slotA ? 1 : 2;
        final String pkg = pkgOf(which);
        if (pkg == null || pkg.isEmpty()) { setHint(which, slot); return; }
        final MirrorSlot s = slot;
        final MediaProjection mp = projFor(which);
        final int[] fs = fixedVdSize();
        final int fw = fs[0], fh = fs[1];
        final int dpi = dpiFit(fw, fh);
        new Thread(() -> {
            // 同槽串行，理由见 deploySlot
            synchronized (s) {
                // 批次 CE：传固定屏尺寸（fw/fh），不是当前画布尺寸 —— VD 建屏后终身不变
                boolean ok = s.attachSurface(surface, fw, fh, dpi);
                if (!ok) ok = s.deploy(mp, pkg, surface, fw, fh, dpi);
                final boolean needProj = s.needsProjection();
                final boolean fOk = ok;
                ui.post(() -> {
                    int w2 = (s == slotA) ? 1 : 2;
                    if (fOk && s.ready()) {
                        // 揭示延迟：应用出第一帧前 hint 不撤，黑帧不被看见
                        revealUntil[w2] = SystemClock.uptimeMillis() + 900;
                        ui.postDelayed(() -> setHint(w2, s), 950);
                    }
                    setHint(w2, s);
                    Log.i(TAG, "槽 " + w2 + " → " + s.describe()
                            + " 固定屏 " + fw + "x" + fh + "@" + dpi + "dpi"
                            + "（画布 " + w + "x" + h + "，缩放显示）");
                    if (needProj && mp == null) requestConsent();
                });
            }
        }, "pip-attach" + (slot == slotA ? "1" : "2")).start();
    }

    private void redeployIfPossible() {
        if (slotA != null && !slotA.ready() && svA != null && svA.getHolder().getSurface() != null
                && svA.getHolder().getSurface().isValid() && notEmpty(pkgOf(1))) {
            attachTo(slotA, svA.getHolder().getSurface(), svA.getWidth(), svA.getHeight());
        }
        if (slotB != null && !slotB.ready() && svB != null && svB.getHolder().getSurface() != null
                && svB.getHolder().getSurface().isValid() && notEmpty(pkgOf(2))) {
            attachTo(slotB, svB.getHolder().getSurface(), svB.getWidth(), svB.getHeight());
        }
    }

    /** 保守自愈：解析不出栈结构就跳过，绝不重拉（m12 教训）。应用真缺席（画中画里按了返回）才节流重拉。 */
    private void selfHeal() {
        for (int i = 1; i <= 2; i++) {
            MirrorSlot s = slotOf(i);
            if (s == null || s.displayId() <= 0 || !s.ready()) continue;
            String pkg = pkgOf(i);
            if (pkg == null || pkg.isEmpty()) continue;
            final int slotNo = i;
            new Thread(() -> {
                String out = RootOps.ensureOnDisplay(act, pkg, s.displayId());
                if (out != null && out.startsWith(RootOps.ABSENT)) {
                    MirrorHost.relaunchIfAbsent(act, slotNo, pkg, s.displayId());
                }
                if (out != null && !out.isEmpty()) Log.i(TAG, "自愈 槽 " + slotNo + ": " + out);
            }, "pip-heal" + i).start();
        }
    }

    /**
     * 点一下之后的兜底自愈（批次 Z）：网易云这类 singleTask 应用在虚拟屏里跳页时，
     * 新 Activity 常被系统按「默认屏」拉起，整块任务被拉回主屏 —— 画布黑掉、主屏
     * 反被它全屏盖住，用户描述就是「点一下跳主屏」。
     *
     * 为什么要有这条：模块的 setLaunchDisplayId 注入只在模块真被注进目标进程时才生效
     * （框架/作用域任一环没到位就静默失效）；这条走 root 的 am task move-task，不依赖
     * 任何框架，是模块路线的兜底。补两次延时（600ms / 1600ms）：跳页的开窗有几帧延迟，
     * 一次可能扑空。
     */
    /** 节流自愈入口：进入单应用后补两次（600/1600ms），兜住 singleTask 拉回默认屏。 */
    public void healSlotSoon(int which) {
        if (which != 1 && which != 2) return;
        ui.postDelayed(() -> healSlotNow(which), 600);
        ui.postDelayed(() -> healSlotNow(which), 1600);
    }

    private void healSlotNow(int which) {
        MirrorSlot s = slotOf(which);
        if (s == null || s.displayId() <= 0 || !s.ready()) return;
        final String pkg = pkgOf(which);
        if (pkg == null || pkg.isEmpty()) return;
        final int d = s.displayId();
        final int slotNo = which;
        new Thread(() -> {
            String out = RootOps.ensureOnDisplay(act, pkg, d);
            if (out != null && !out.isEmpty()) Log.i(TAG, "点后自愈 槽 " + slotNo + ": " + out);
            // 批次 CE：搬回后不再 nudgeLayout —— 抖动=触发重建，网易云正在看的
            // 页面（歌词页）会当场归零。铺满交给系统按 VD 固定尺寸重配。
        }, "pip-tap-heal" + which).start();
    }

    /* ---------------- 批次 AF：常驻巡检搬回 ---------------- */

    /**
     * 2.5s 一拍的巡检：singleTask 目标（网易云）在画布内跳页（歌词/封面页）时，
     * 整个任务被系统拉回默认屏 —— tap 自愈只补两次（600/1600ms）可能都扑空，
     * 「跳走」就永久化成「画布黑屏 + 主屏全屏应用」。常驻巡检把它变成
     * 「最多 2.5s 自动搬回」，应用与播放状态都不中断（move-task 不杀进程）。
     *
     * 只搬不拉（ensureOnDisplay 红线不变）：应用真退出走既有缺席重拉路径
     * （relaunchIfAbsent），这里绝不重拉，防虚拟屏里叠出第二个实例。
     */
    private static final long REPIN_MS = 2500;
    private final Runnable repinRun = new Runnable() {
        @Override public void run() {
            new Thread(() -> {
                for (int i = 1; i <= 2; i++) {
                    MirrorSlot s = slotOf(i);
                    if (s == null || s.displayId() <= 0 || !s.ready()) continue;
                    String pkg = pkgOf(i);
                    if (pkg == null || pkg.isEmpty()) continue;
                    try {
                        String out = RootOps.ensureOnDisplay(act, pkg, s.displayId());
                        if (out != null && out.contains("已搬回")) {
                            Log.i(TAG, "巡检搬回 槽 " + i + ": " + out);
                            // 批次 CE：搬回后不再 nudgeLayout（批次 AH 遗留）—— 抖动
                            // 会触发重建，网易云正在看的页面（歌词页）当场归零，正是
                            // 「桌面往返回首页」bug 的帮凶。铺满交给系统按 VD 固定
                            // 尺寸重配 + SurfaceView 缩放显示；只补挂面（redeploy）。
                            ui.post(() -> redeployIfPossible());
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "巡检 槽 " + i + " 异常: " + t);
                    }
                }
            }, "pip-repin").start();
            // R9（清单）：root 事件监听在线 → 自愈由 BUMP 毫秒级触发，巡检降为
            // 8s 纯兜底拍（防监听通道失联后无人搬回）；离线保持 2.5s 原状。
            ui.postDelayed(this, watching ? 8000 : REPIN_MS);
        }
    };

    /** onResume 启动（幂等）；onDestroy / cancelPending 的 removeCallbacksAndMessages 会摘掉。 */
    public void startRepin() {
        ui.removeCallbacks(repinRun);
        ui.postDelayed(repinRun, REPIN_MS);
    }

    /* ---------------- 批次 AK：root 事件订阅（BUMP 自愈 / ROUTED 刷新） ---------------- */

    /** true = 事件订阅连接活着（自愈由 BUMP 毫秒级触发，巡检降为兜底拍）。 */
    private volatile boolean watching;

    /**
     * 订阅守护进程事件（幂等）。订阅会 fork su，全程后台线程；回调在 watch 线程上，
     * 一律 ui.post 回主线程。空气开关关闭 = 整段跳过（行为与 AJ 一致）。
     */
    public void startWatch() {
        if (PrivClient.subscribed()) { watching = true; return; }
        new Thread(() -> {
            // rootBridgeOn 未命中缓存时 exec getprop（fork，秒级）—— 不能在 onResume
            // 主线程读，整段判断随订阅线程走
            if (!Caps.rootBridgeOn()) return;
            boolean ok = PrivClient.subscribe(PipBoard.this::onEvtLine);
            ui.post(() -> watching = ok);
            Log.i(TAG, "事件订阅" + (ok ? "已上线（BUMP 触发自愈）" : "不可用，巡检照旧 2.5s"));
        }, "pip-watch").start();
    }

    /** 事件行派发（watch 线程 → 主线程）。 */
    private void onEvtLine(String line) {
        ui.post(() -> {
            if (line.startsWith(PrivCodec.EVT + " " + PrivCodec.EVT_BUMP)) {
                // 任务栈有变化：复用既有自愈（只搬不拉的护栏全在里面），带节流
                selfHeal();
                return;
            }
            if (line.startsWith(PrivCodec.EVT + " " + PrivCodec.EVT_ROUTED)) {
                // 拦截改道完成：pkg 已被重新启动到画布屏 —— 刷新提示 + 补一次自愈
                String[] p = line.split(" ");
                String pkg = p.length > 2 ? p[2] : "";
                String dsp = p.length > 3 ? p[3] : "?";
                Log.i(TAG, "拦截改道 " + pkg + " → 画布 display " + dsp);
                for (int i = 1; i <= 2; i++) {
                    final int slot = i;   // lambda 捕获要求 effectively final
                    MirrorSlot s = slotOf(slot);
                    if (s != null && s.displayId() > 0 && s.displayId() == parseD(dsp)) {
                        healSlotSoon(slot);
                        ui.postDelayed(() -> setHint(slot, s), 1500);
                    }
                }
            }
        });
    }

    private static int parseD(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return -1; }
    }

    private boolean notEmpty(String s) { return s != null && !s.isEmpty(); }

    /** 画布实际尺寸缓存（cancelStroke 补 CANCEL 时用同一坐标系）。 */
    private final int[] cvW = new int[3], cvH = new int[3];
    /** 揭示延迟（批次 AE，学 Vivo MapSurfaceViewHelper 的封面时序）：
     *  surface 刚挂上时应用还没出第一帧，立即撤提示会看见黑/花帧；
     *  hint 多留 900ms 再撤。 */
    private final long[] revealUntil = new long[3];

    private String pkgOf(int which) { return loadPkg(act, which); }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics()));
    }

    /* ------------------------- 应用选择 ------------------------- */

    /** 宿主长按/点空态后调它。选完即部署；「清空该槽」拆屏并停掉画中画里的应用。 */
    public static void showPicker(Activity act, int which, PipBoard board) {
        List<HomeActivity.AppEntry> apps = HomeActivity.loadApps(act);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Skin.c(R.color.ground));
        int p = (int) (14 * act.getResources().getDisplayMetrics().density);
        col.setPadding(p, p - 2, p, p - 2);

        TextView title = new TextView(act);
        title.setText("槽 " + (which == 1 ? "A" : "B") + " 选应用");
        title.setTextColor(Skin.c(R.color.text));
        title.setTextSize(16);
        col.addView(title);

        for (HomeActivity.AppEntry e : apps) {
            TextView tv = new TextView(act);
            tv.setText(e.label + "   " + e.pkg);
            tv.setTextColor(Skin.c(R.color.text));
            tv.setTextSize(13);
            tv.setPadding(p, p - 4, p, p - 4);
            tv.setBackground(Skin.round(Skin.c(R.color.card), 12f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = p / 2;
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> {
                savePkg(act, which, e.pkg);
                Log.i(TAG, "槽 " + which + " → " + e.pkg);
                if (board != null) board.deploySlotPublic(which);
                ((Dialog) v.getTag()).dismiss();
            });
            col.addView(tv);
        }

        ScrollView sv = new ScrollView(act);
        sv.addView(col);
        Dialog dlg = new android.app.AlertDialog.Builder(act)
                .setView(sv)
                .setNeutralButton("清空该槽", (d, w) -> {
                    MirrorHost.clear(act, which);
                    if (board != null) board.updateHintsPublic();
                })
                .setNegativeButton("关闭", null)
                .create();
        // dialog 建好后挂到每一行上，点完即关
        for (int i = 1; i < col.getChildCount(); i++) col.getChildAt(i).setTag(dlg);
        dlg.show();
    }

    private void openPick(int which) {
        if (host != null) host.onPickApp(which);
    }

    /** 供 showPicker 的「清空该槽」回调刷新提示。 */
    public void updateHintsPublic() { updateHints(); }

    /** 周期自愈入口（HomeActivity tick 调）。 */
    public void selfHealPublic() { selfHeal(); }

    public static String loadPkg(Context ctx, int which) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("mirror_pkg" + which, "");
    }

    public static void savePkg(Context ctx, int which, String pkg) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("mirror_pkg" + which, pkg).apply();
    }
}
