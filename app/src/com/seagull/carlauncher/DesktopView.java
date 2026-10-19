package com.seagull.carlauncher;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌面本体：组件条 + 应用网格 + Dock，支持长按拖拽压合成文件夹。
 *
 * Dock 完整配置（TODO P0-5）：位置（底部/左/右/不显示）、数量、粗细、图标大小、
 * 间距、透明度、自动收起（带把手）、固定、Dock 时钟、每应用默认开在几号窗口。
 *
 * 全部纯 UI + 本地存储，不需要任何权限 —— 这是公开版的地基。
 */
public class DesktopView extends FrameLayout {

    private static final String TAG = "SeagullDesktop";

    public interface Host {
        LauncherModel model();
        void onOpenFolder(LauncherModel.Folder f);
        void onToast(String msg);
        void onRequestLayoutMode();
    }

    private final Host host;
    // 审计 L13：原 ui Handler 从未使用（死代码），连同 import 一起清掉

    private LinearLayout mainCol, widgetStrip;
    private GridLayout grid;
    private LinearLayout dockBar;
    private TextView dockHandle;
    /** 审计 M4：歌词组件文本位（makeWidget(5) 登记时有效），1Hz 原位刷新用 */
    private TextView lyricBig, lyricSmall;
    private String[] lyricLinesCache;
    private FrameLayout dragLayer;
    /* 拖拽高亮的复用对象：dispatchDraw 逐帧跑，旧实现每帧 new Rect/int[2]（批次 V） */
    private final android.graphics.Rect hiRect = new android.graphics.Rect();
    private final int[] hiLoc = new int[2];
    private float hiInset, hiRadius;

    /** 编辑模式：开着才能拖拽（避免误触），由外部按钮切换。 */
    public boolean editMode = false;

    /** Dock 收起态（自动收起时点把手展开） */
    private boolean dockCollapsed = false;

    /** 拖拽状态 */
    private View dragView;
    private float dragDx, dragDy;
    private String dragKey;
    private View dropTarget;
    private final Paint hiPaint = new Paint();

    /** 组件条槽位名（下标即 model 里的组件 id）。 */
    public static final String[] WIDGET_NAMES =
            {"时钟", "日期", "电量", "内存", "天气", "歌词", "快捷栏"};

    public DesktopView(Context c, Host host) {
        super(c);
        this.host = host;
        hiPaint.setStyle(Paint.Style.STROKE);
        hiPaint.setStrokeWidth(dp(2));
        hiPaint.setColor(0xFF8CC26A);
        build();
    }

    /**
     * 歌词 sink：单实例 + attach/detach 配套。旧实现在构造里匿名注册，
     * 换肤 / 重建时旧 DesktopView 连同 sink 被 Lyrics 永久持有，
     * 每次重建泄漏一整棵旧视图树（批次 T 复盘坐实，对比 HomeActivity
     * islandSink 的 removeSink/addSink 纪律）。
     */
    private final Lyrics.Sink lyricSink = new Lyrics.Sink() {
        @Override public void onLyric(String[] w, String t, String a) {
            // 审计 M4：1Hz 歌词帧只原位 setText；旧实现每次整条组件条
            // removeAllViews 重建（构造器路径、手势回调、时钟基线全打断）。
            // 结构变化（加/拿组件）仍走 renderWidgets。
            if (lyricBig != null && lyricBig.isAttachedToWindow()) {
                if (lyricLinesCache == null || lyricLinesCache.length != w.length
                        || !java.util.Arrays.equals(lyricLinesCache, w)) {
                    lyricLinesCache = w;
                    lyricBig.setText(w.length == 0 ? "未在播放" : w[0]);
                    if (lyricSmall != null) {
                        lyricSmall.setText(w.length > 1 && !w[1].isEmpty()
                                ? w[1] : Lyrics.nowPlaying());
                    }
                }
                return;
            }
            LauncherModel m = model();
            if (m != null && m.hasWidget(5)) renderWidgets(m);
        }
    };

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Lyrics.addSink(lyricSink);
    }

    @Override protected void onDetachedFromWindow() {
        Lyrics.removeSink(lyricSink);
        super.onDetachedFromWindow();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private void build() {
        setBackgroundColor(Skin.c(R.color.ground));

        mainCol = new LinearLayout(getContext());
        mainCol.setOrientation(LinearLayout.VERTICAL);
        addView(mainCol, new FrameLayout.LayoutParams(-1, -1));

        widgetStrip = new LinearLayout(getContext());
        widgetStrip.setOrientation(LinearLayout.HORIZONTAL);
        widgetStrip.setVisibility(GONE);
        widgetStrip.setOnLongClickListener(v -> { widgetAddMenu(); return true; });
        mainCol.addView(widgetStrip, new LinearLayout.LayoutParams(-1, dp(96)));

        grid = new GridLayout(getContext());
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        mainCol.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        dockBar = new LinearLayout(getContext());
        dockBar.setOrientation(LinearLayout.HORIZONTAL);
        dockBar.setGravity(Gravity.CENTER);
        dockBar.setBackgroundColor(Skin.c(R.color.panel));
        dockBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        addView(dockBar, new FrameLayout.LayoutParams(-1, dp(76), Gravity.BOTTOM));

        // 自动收起的把手：贴在 Dock 所在的那条边
        dockHandle = new TextView(getContext());
        dockHandle.setTextSize(13);
        dockHandle.setTextColor(Skin.c(R.color.text_dim));
        dockHandle.setGravity(Gravity.CENTER);
        dockHandle.setBackground(Skin.pill(Skin.c(R.color.panel)));
        dockHandle.setVisibility(GONE);
        dockHandle.setOnClickListener(v -> {
            dockCollapsed = false;
            applyCollapse(model());
        });
        addView(dockHandle, new FrameLayout.LayoutParams(dp(30), dp(76), Gravity.LEFT));

        // 拖拽层：浮在最上面画拖拽态与高亮
        hiInset = dp(3); hiRadius = dp(10);
        dragLayer = new FrameLayout(getContext()) {
            @Override protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                if (dropTarget != null && dropTarget != dragView) {
                    dropTarget.getGlobalVisibleRect(hiRect);
                    getLocationOnScreen(hiLoc);
                    hiRect.offset(-hiLoc[0], -hiLoc[1]);
                    hiRect.inset(Math.round(hiInset), Math.round(hiInset));
                    canvas.drawRoundRect(hiRect.left, hiRect.top, hiRect.right, hiRect.bottom,
                            hiRadius, hiRadius, hiPaint);
                }
            }
        };
        dragLayer.setVisibility(GONE);
        addView(dragLayer, new FrameLayout.LayoutParams(-1, -1));

        setClipChildren(false);

        // 桌面空白处长按 = 加组件（和长按组件条同一条路）
        setOnLongClickListener(v -> {
            if (dragView == null) widgetAddMenu();
            return true;
        });
    }

    /* ==================== 渲染 ==================== */

    public void refresh() {
        LauncherModel m = model();
        if (m == null) return;

        // 屏幕外边距与缝跟着设置走，横竖屏各一份（TODO P0-8 7.1~7.3 / 7.7）
        boolean portrait = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        mainCol.setPadding(dp(m.marginHOf(portrait)), dp(m.marginVOf(portrait)),
                dp(m.marginHOf(portrait)), dp(m.marginVOf(portrait) / 2));
        cellGap = dp(m.gapOf(portrait));

        renderWidgets(m);
        widgetStrip.setAlpha(m.widgetStripAlpha / 100f);

        grid.removeAllViews();
        int cols = m.mode == LauncherModel.Mode.DENSE ? LauncherModel.COLS_DENSE
                : LauncherModel.COLS_DEFAULT;
        grid.setColumnCount(cols);
        boolean showGrid = m.mode != LauncherModel.Mode.DOCK_ONLY;
        grid.setVisibility(showGrid ? VISIBLE : GONE);

        if (showGrid) {
            List<String> keys = m.homeKeys();
            List<String> shown = new ArrayList<>();   // 文件夹占位只出现一次
            for (String k : keys) {
                LauncherModel.Folder f = m.folderOf(k);
                if (f != null) {
                    String fk = "@folder:" + f.name + ":" + f.keys.get(0);
                    if (shown.contains(fk)) continue;
                    shown.add(fk);
                    grid.addView(folderView(f), cellParams(cols));
                } else {
                    LauncherModel.App a = m.find(k);
                    if (a == null) continue;
                    grid.addView(appView(a, dp(44), m.showLabels, false), cellParams(cols));
                }
            }
        }

        applyDockLayout(m);
        renderDock(m);
    }

    /* ==================== Dock（TODO P0-5） ==================== */

    /** 位置 / 粗细 / 透明度 / 自动收起，都在这一处落到布局参数上。 */
    private void applyDockLayout(LauncherModel m) {
        boolean vertical = m.dockPos == LauncherModel.DockPos.LEFT
                || m.dockPos == LauncherModel.DockPos.RIGHT;
        dockBar.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        dockBar.setVisibility(m.dockPos == LauncherModel.DockPos.HIDDEN ? GONE : VISIBLE);
        dockBar.setAlpha(Math.max(0.15f, m.dockAlpha / 100f));

        int thick = dp(m.dockWidth);
        FrameLayout.LayoutParams dock = (FrameLayout.LayoutParams) dockBar.getLayoutParams();
        FrameLayout.LayoutParams main = (FrameLayout.LayoutParams) mainCol.getLayoutParams();
        main.leftMargin = 0; main.rightMargin = 0; main.topMargin = 0; main.bottomMargin = 0;
        dock.leftMargin = 0; dock.rightMargin = 0; dock.topMargin = 0; dock.bottomMargin = 0;
        if (m.dockPos == LauncherModel.DockPos.LEFT) {
            dock.width = thick; dock.height = -1; dock.gravity = Gravity.LEFT;
            main.leftMargin = thick;
        } else if (m.dockPos == LauncherModel.DockPos.RIGHT) {
            dock.width = thick; dock.height = -1; dock.gravity = Gravity.RIGHT;
            main.rightMargin = thick;
        } else {
            dock.width = -1; dock.height = thick; dock.gravity = Gravity.BOTTOM;
            main.bottomMargin = thick;
        }

        FrameLayout.LayoutParams handle = (FrameLayout.LayoutParams) dockHandle.getLayoutParams();
        if (m.dockPos == LauncherModel.DockPos.LEFT) {
            handle.width = dp(30); handle.height = -1; handle.gravity = Gravity.LEFT;
        } else if (m.dockPos == LauncherModel.DockPos.RIGHT) {
            handle.width = dp(30); handle.height = -1; handle.gravity = Gravity.RIGHT;
        } else {
            handle.width = -1; handle.height = dp(24); handle.gravity = Gravity.BOTTOM;
        }
        if (!m.dockAutoHide || m.dockFixed) dockCollapsed = false;
        applyCollapse(m);
    }

    private void applyCollapse(LauncherModel m) {
        if (m == null) return;
        boolean collapsible = m.dockAutoHide && !m.dockFixed
                && m.dockPos != LauncherModel.DockPos.HIDDEN;
        if (!collapsible) {
            dockBar.setTranslationX(0f);
            dockBar.setTranslationY(0f);
            dockHandle.setVisibility(GONE);
            return;
        }
        if (!dockCollapsed) {
            dockBar.setTranslationX(0f);
            dockBar.setTranslationY(0f);
            dockHandle.setVisibility(GONE);
            return;
        }
        int thick = dp(m.dockWidth);
        dockBar.setTranslationY(m.dockPos == LauncherModel.DockPos.BOTTOM ? thick - dp(10) : 0f);
        dockBar.setTranslationX(m.dockPos == LauncherModel.DockPos.LEFT ? -(thick - dp(10))
                : (m.dockPos == LauncherModel.DockPos.RIGHT ? thick - dp(10) : 0f));
        dockHandle.setText(m.dockPos == LauncherModel.DockPos.BOTTOM ? "≡"
                : (m.dockPos == LauncherModel.DockPos.LEFT ? "›" : "‹"));
        dockHandle.setVisibility(VISIBLE);
    }

    private void renderDock(LauncherModel m) {
        if (m.dockPos == LauncherModel.DockPos.HIDDEN) return;
        dockBar.removeAllViews();
        boolean vertical = m.dockPos == LauncherModel.DockPos.LEFT
                || m.dockPos == LauncherModel.DockPos.RIGHT;
        List<String> keys = m.dock.isEmpty() ? defaultDockKeys(m) : m.dock;
        // 数量改小时只显示前 N 个，多出的应用仍留在 model.dock 里
        int n = Math.min(keys.size(), Math.max(1, m.dockCount));
        for (int i = 0; i < n; i++) {
            LauncherModel.App a = m.find(keys.get(i));
            if (a == null) continue;
            dockBar.addView(appView(a, dp(m.dockIconSize), false, true), dockParams(m, vertical));
        }
        if (m.dockShowClock) {
            TextView clk = new TextView(getContext());
            clk.setText(SysOps.clockHHmm());
            clk.setTextColor(Skin.c(R.color.text));
            clk.setTextSize(12);
            clk.setGravity(Gravity.CENTER);
            clk.setOnClickListener(v -> {
                dockCollapsed = !dockCollapsed;
                applyCollapse(m);
            });
            dockBar.addView(clk, dockParams(m, vertical));
        }
    }

    private List<String> defaultDockKeys(LauncherModel m) {
        // 默认 Dock 放最常见的电话/信息/浏览器/相机，找不到就取前 5 个
        String[] prefer = {"com.android.dialer", "com.android.mms", "com.android.chrome",
                "com.android.camera", "com.android.settings"};
        List<String> out = new ArrayList<>();
        for (String p : prefer) {
            for (LauncherModel.App a : m.allApps) {
                if (a.pkg.equals(p)) { out.add(a.key()); break; }
            }
        }
        if (out.isEmpty()) for (int i = 0; i < Math.min(5, m.allApps.size()); i++) out.add(m.allApps.get(i).key());
        return out;
    }

    /** 桌面格子之间的缝（设置→屏幕，跟横竖屏各一份）。 */
    private int cellGap = 6;

    private GridLayout.LayoutParams cellParams(int cols) {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = 0; p.height = dp(84);
        p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        p.setMargins(cellGap, cellGap, cellGap, cellGap);
        return p;
    }

    private LinearLayout.LayoutParams dockParams(LauncherModel m, boolean vertical) {
        int g = dp(m.dockGap);
        LinearLayout.LayoutParams p;
        if (vertical) {
            p = new LinearLayout.LayoutParams(-1, 0, 1f);
        } else {
            p = new LinearLayout.LayoutParams(0, -1, 1f);
        }
        p.setMargins(g, g, g, g);
        return p;
    }

    /* ==================== 组件条 ==================== */

    private void renderWidgets(LauncherModel m) {
        widgetStrip.removeAllViews();
        if (m.widgets.isEmpty() || m.mode == LauncherModel.Mode.DOCK_ONLY) {
            widgetStrip.setVisibility(GONE);
            return;
        }
        widgetStrip.setVisibility(VISIBLE);
        for (int id : m.widgets) {
            widgetStrip.addView(makeWidget(id, m), new LinearLayout.LayoutParams(0, -1, 1f));
        }
    }

    /** 长按组件条：挑要加的组件（空位即已放满时全列出来）。 */
    private void widgetAddMenu() {
        LauncherModel m = model();
        List<String> names = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (int id = 0; id < WIDGET_NAMES.length; id++) {
            if (m != null && m.hasWidget(id)) continue;
            names.add("+ " + WIDGET_NAMES[id]);
            ids.add(id);
        }
        if (names.isEmpty()) { host.onToast("组件都放满了（最多 " + LauncherModel.WIDGET_MAX + " 个）"); return; }
        final String[] opts = names.toArray(new String[0]);
        new AlertDialog.Builder(getContext())
                .setTitle("加组件")
                .setItems(opts, (d, which) -> {
                    LauncherModel mm = model();
                    if (mm == null) return;
                    // false = 已满 WIDGET_MAX 格：旧实现静默丢弃，用户以为点了没反应
                    if (!mm.toggleWidget(ids.get(which))) {
                        host.onToast("组件都放满了（最多 " + LauncherModel.WIDGET_MAX + " 个）");
                        return;
                    }
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 长按组件条上某个组件：换位置 / 拿下来。 */
    private void widgetMenu(final int id) {
        final LauncherModel m = model();
        if (m == null) return;
        int i = m.widgets.indexOf(id);
        List<String> opts = new ArrayList<>();
        opts.add("拿下来");
        if (i > 0) opts.add("往前挪");
        if (i >= 0 && i < m.widgets.size() - 1) opts.add("往后挪");
        new AlertDialog.Builder(getContext())
                .setTitle(WIDGET_NAMES[id])
                .setItems(opts.toArray(new String[0]), (d, which) -> {
                    int at = m.widgets.indexOf(id);
                    if (at < 0) return;
                    if (which == 0) m.widgets.remove(at);
                    else if (which == 1 && at > 0) { m.widgets.remove(at); m.widgets.add(at - 1, id); }
                    else if (which == 2) { m.widgets.remove(at); m.widgets.add(at + 1, id); }
                    m.save();
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 长按快捷栏某格：换内容 / 清空。 */
    private void quickSlotMenu(final int idx) {
        final LauncherModel m = model();
        if (m == null) return;
        String cur = QuickBar.slotKey(m, idx);
        String[] opts = {"清空", "选择应用…", "功能按钮…", "场景…"};
        new AlertDialog.Builder(getContext())
                .setTitle("快捷栏第 " + (idx + 1) + " 格")
                .setItems(opts, (d, which) -> {
                    if (which == 0) { m.clearQuickSlot(idx); refresh(); return; }
                    if (which == 1) {
                        appPickDialog("选应用", a -> { m.setQuickSlot(idx, a.key(), a.label); refresh(); });
                    } else if (which == 3) {
                        scenePickDialog(name -> {
                            m.setQuickSlot(idx, "@scene:" + name, name);
                            refresh();
                        });
                    } else {
                        new AlertDialog.Builder(getContext())
                                .setTitle("功能按钮")
                                .setItems(QuickBar.FN_LABELS, (dd, j) -> {
                                    m.setQuickSlot(idx, "@fn:" + QuickBar.FN_KEYS[j], QuickBar.FN_LABELS[j]);
                                    refresh();
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        if (cur == null || cur.isEmpty()) host.onToast("空格点一下就能选");
    }

    private void appPickDialog(String title, final java.util.function.Consumer<LauncherModel.App> pick) {
        final LauncherModel m = model();
        if (m == null) return;
        String[] labels = new String[m.allApps.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = m.allApps.get(i).label;
        new AlertDialog.Builder(getContext())
                .setTitle(title)
                .setItems(labels, (d, which) -> pick.accept(m.allApps.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    /** G2：从场景列表挑一个放进快捷栏；没建过场景时提示去设置页。 */
    private void scenePickDialog(final java.util.function.Consumer<String> pick) {
        final LauncherModel m = model();
        if (m == null) return;
        if (m.scenes.isEmpty()) { host.onToast("还没有场景：设置 → 快捷栏 → 新建场景"); return; }
        String[] names = new String[m.scenes.size()];
        for (int i = 0; i < names.length; i++) names[i] = m.scenes.get(i).name;
        new AlertDialog.Builder(getContext())
                .setTitle("选场景")
                .setItems(names, (d, which) -> pick.accept(m.scenes.get(which).name))
                .setNegativeButton("取消", null)
                .show();
    }

    private View makeWidget(int id, LauncherModel m) {
        if (id == 6) return quickBarWidget(m);
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(Skin.round(Skin.c(R.color.panel), 14f));
        box.setOnLongClickListener(v -> { widgetMenu(id); return true; });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        box.setLayoutParams(lp);

        TextView big = new TextView(getContext());
        big.setTextColor(Skin.c(R.color.text));
        big.setTextSize(id == 0 ? 30 : 17);
        big.setGravity(Gravity.CENTER);

        TextView small = new TextView(getContext());
        small.setTextColor(Skin.c(R.color.text_dim));
        small.setTextSize(12);
        small.setGravity(Gravity.CENTER);

        switch (id) {
            case 0: big.setText(SysOps.clockHHmm()); small.setText("时钟"); break;
            case 1: big.setText(SysOps.dateCn());     small.setText("日期"); break;
            case 2: big.setText(SysOps.batteryPct(getContext())); small.setText("电量"); break;
            case 3: big.setText(SysOps.memFree(getContext()));    small.setText("内存"); break;
            case 4:
                big.setText(Weather.summary(m));
                big.setTextSize(15);
                small.setText(m.weatherForecast.isEmpty() ? Weather.stamp(m) : m.weatherForecast);
                small.setTextSize(11);
                // 9.9 点按获取天气：现在数据取一次，不等 20 分钟
                box.setOnClickListener(v -> Weather.refresh(getContext(), m, () -> {
                    LauncherModel mm = model();
                    if (mm != null && host != null) host.onToast(
                            mm.weatherSummary.isEmpty() ? "天气没取到" : mm.weatherSummary);
                    refresh();
                }));
                break;
            case 5:
                String[] w = Lyrics.window(getContext(), m, m.lyricLines > 0 ? Math.min(m.lyricLines, 2) : 2);
                big.setText(w.length == 0 ? "未在播放" : w[0]);
                big.setTextSize(13);
                small.setText(w.length > 1 && !w[1].isEmpty() ? w[1] : Lyrics.nowPlaying());
                small.setTextSize(11);
                // 审计 M4：登记歌词组件的两个文本位，1Hz 歌词帧原位刷新
                lyricBig = big;
                lyricSmall = small;
                lyricLinesCache = w;
                break;
            default: big.setText("—"); small.setText("空"); break;
        }
        box.addView(big);
        box.addView(small);
        return box;
    }

    /** 组件条里的快捷栏：整条展开成可点的格子。 */
    private View quickBarWidget(LauncherModel m) {
        LinearLayout wrap = new LinearLayout(getContext());
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER);
        wrap.setBackground(Skin.round(Skin.c(R.color.panel), 14f));
        wrap.setOnLongClickListener(v -> { widgetMenu(6); return true; });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        wrap.setLayoutParams(lp);

        QuickBar.Host qh = new QuickBar.Host() {
            @Override public LauncherModel model() { return DesktopView.this.model(); }
            @Override public void onEditSlot(int idx) { quickSlotMenu(idx); }
            @Override public void onScene(String name) {
                if (getContext() instanceof HomeActivity) {
                    ((HomeActivity) getContext()).applyScene(name);
                }
            }
        };
        wrap.addView(QuickBar.build(getContext(), qh), new LinearLayout.LayoutParams(-1, 0, 1f));
        return wrap;
    }

    /* ==================== 图标 ==================== */

    private View appView(final LauncherModel.App a, int iconPx, boolean label, final boolean isDock) {
        final LauncherModel m = model();
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setTag(a.key());

        ImageView iv = new ImageView(getContext());
        Drawable d = m == null ? null : m.icon(a);
        if (d != null) iv.setImageDrawable(d);
        box.addView(iv, new LinearLayout.LayoutParams(iconPx, iconPx));

        if (label) {
            TextView tv = new TextView(getContext());
            tv.setText(a.label);
            tv.setTextColor(Skin.c(R.color.text_dim));
            tv.setTextSize(12);
            tv.setMaxLines(1);
            tv.setGravity(Gravity.CENTER);
            box.addView(tv);
        }

        box.setOnClickListener(v -> onAppTap(a, isDock));
        box.setOnLongClickListener(v -> {
            if (isDock) { dockMenu(a); return true; }
            if (editMode) { startDrag(box, a.key()); return true; }
            appMenu(a);
            return true;
        });
        return box;
    }

    /**
     * 长按桌面图标（非整理模式）：自己摆布局 —— 固定/拿下来 + 主屏里挪顺序。
     * 拖拽压合在整理模式里做（避免误触），这里只管顺序与归属。
     */
    private void appMenu(final LauncherModel.App a) {
        final LauncherModel m = model();
        if (m == null) return;
        boolean pinned = m.isPinned(a.key());
        int idx = m.pinnedIndex(a.key());
        List<String> opts = new ArrayList<>();
        if (pinned) {
            if (idx > 0) opts.add("主屏：上移一位");
            if (idx >= 0 && idx < m.pinned.size() - 1) opts.add("主屏：下移一位");
            if (idx > 0) opts.add("主屏：置顶");
            opts.add("从主屏拿下来");
            opts.add("固定到 Dock");
        } else {
            opts.add("固定到主屏");
            opts.add("固定到 Dock");
        }
        opts.add("应用信息");
        new AlertDialog.Builder(getContext())
                .setTitle(a.label)
                .setItems(opts.toArray(new String[0]), (d, which) -> {
                    String o = opts.get(which);
                    if (o.startsWith("主屏：上移")) m.movePinned(a.key(), -1);
                    else if (o.startsWith("主屏：下移")) m.movePinned(a.key(), 1);
                    else if (o.equals("主屏：置顶")) m.pinTop(a.key());
                    else if (o.equals("从主屏拿下来")) m.unpin(a.key());
                    else if (o.equals("固定到主屏")) m.pin(a.key());
                    else if (o.equals("固定到 Dock")) { m.toggleDock(a.key()); host.onToast("已固定到 Dock"); }
                    else {
                        try {
                            getContext().startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + a.pkg)));
                        } catch (Throwable t) { host.onToast("打不开应用信息"); }
                        return;
                    }
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 点图标：Dock 上按「默认开在几号窗口」路由，桌面图标直接启动。 */
    private void onAppTap(LauncherModel.App a, boolean isDock) {
        LauncherModel m = model();
        if (m == null) return;
        if (editMode && !isDock) { host.onToast("编辑模式：长按拖动，拖到另一个图标上合并成文件夹"); return; }
        if (isDock) {
            int slot = m.windowOf(a.pkg);
            if (slot > 0 && a.pkg != null) {
                try { getContext().startActivity(MirrorActivity.intentFor(getContext(), a.pkg, slot)); }
                catch (Throwable t) { host.onToast("打不开窗口页：" + t); }
                return;
            }
        }
        m.launch(a, false);
    }

    /** 长按 Dock 图标：换默认窗口 / 全屏打开 / 拿下来（TODO 2.10 / 2.11 / 2.13）。 */
    private void dockMenu(final LauncherModel.App a) {
        final LauncherModel m = model();
        if (m == null) return;
        int cur = m.windowOf(a.pkg);
        String[] opts = {
                (cur == 1 ? "● " : "○ ") + "默认开在 1 号窗口",
                (cur == 2 ? "● " : "○ ") + "默认开在 2 号窗口",
                "全屏打开（当前窗口留着）",
                "从 Dock 拿下来"
        };
        new AlertDialog.Builder(getContext())
                .setTitle(a.label)
                .setItems(opts, (d, which) -> {
                    switch (which) {
                        case 0: m.setDockWindow(a.pkg, cur == 1 ? 0 : 1); break;
                        case 1: m.setDockWindow(a.pkg, cur == 2 ? 0 : 2); break;
                        case 2: m.setDockWindow(a.pkg, 0); m.launch(a, false); break;
                        default: m.toggleDock(a.key()); host.onToast("已从 Dock 拿下来"); break;
                    }
                    refresh();
                })
                .show();
    }

    private View folderView(final LauncherModel.Folder f) {
        LauncherModel m = model();
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setTag("@folder:" + f.name + ":" + f.keys.get(0));

        // 2x2 迷你预览
        GridLayout pre = new GridLayout(getContext());
        pre.setColumnCount(2);
        int n = Math.min(4, f.keys.size());
        for (int i = 0; i < n; i++) {
            LauncherModel.App a = m.find(f.keys.get(i));
            ImageView iv = new ImageView(getContext());
            Drawable d = a == null ? null : m.icon(a);
            if (d != null) iv.setImageDrawable(d);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = dp(20); p.height = dp(20);
            p.setMargins(dp(1), dp(1), dp(1), dp(1));
            pre.addView(iv, p);
        }
        box.addView(pre, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView tv = new TextView(getContext());
        tv.setText(f.name + " (" + f.keys.size() + ")");
        tv.setTextColor(Skin.c(R.color.text_dim));
        tv.setTextSize(12);
        tv.setMaxLines(1);
        box.addView(tv);

        box.setOnClickListener(v -> host.onOpenFolder(f));
        box.setOnLongClickListener(v -> {
            if (editMode) { startDrag(box, box.getTag().toString()); return true; }
            folderMenu(f);
            return true;
        });
        return box;
    }

    /** 长按文件夹（整理模式外）：改名 / 解散。 */
    private void folderMenu(final LauncherModel.Folder f) {
        final LauncherModel m = model();
        if (m == null) return;
        new AlertDialog.Builder(getContext())
                .setTitle(f.name + "（" + f.keys.size() + "）")
                .setItems(new String[]{"打开", "解散文件夹"}, (d, which) -> {
                    if (which == 0) { host.onOpenFolder(f); return; }
                    m.dissolveFolder(f);
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ==================== 拖拽压合 ==================== */

    private void startDrag(View v, String key) {
        dragKey = key;
        dragView = v;
        dragLayer.setVisibility(VISIBLE);
        dragView.setAlpha(0.35f);
        host.onToast("拖到目标图标上松手即合并成文件夹");
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if (dragView == null) return super.onInterceptTouchEvent(e);
        handleDrag(e);
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (dragView != null) { handleDrag(e); return true; }
        return super.onTouchEvent(e);
    }

    private void handleDrag(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                dropTarget = hitTest(e.getRawX(), e.getRawY());
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                View t = dropTarget;
                String src = dragKey;
                endDrag();
                if (t != null && src != null) {
                    Object tag = t.getTag();
                    if (tag != null && !tag.equals(src)) {
                        String dst = tag.toString();
                        LauncherModel m = model();
                        // 落到文件夹上 → 并入。
                        // 旧实现按文件夹名 splits 手工定位，并发两次列表变更是
                        // 常见路径（拖到 A 文件夹，名字相同就串），统一走
                        // model.addToFolder / mergeInto 这两个有 save 与
                        // folderOf 迁移处理的入口；同时判掉 m == null（Activity 已销毁）。
                        if (m == null) return;
                        if (dst.startsWith("@folder:")) {
                            String fname = dst.substring("@folder:".length());
                            LauncherModel.Folder f = null;
                            for (LauncherModel.Folder x : m.folders) {
                                if (x.name.equals(fname)) { f = x; break; }
                            }
                            if (f == null) return;
                            if (f.keys.contains(src)) return;
                            m.addToFolder(f, src);
                            m.pinned.remove(src);
                            m.save();
                            host.onToast("已加入「" + f.name + "」");
                            refresh();
                        } else {
                            LauncherModel.Folder f = m.mergeInto(dst, src);
                            if (f != null) {
                                host.onToast("已合并为文件夹，可点进去改名");
                                refresh();
                            }
                        }
                    }
                }
                break;
            }
        }
    }

    private void endDrag() {
        if (dragView != null) dragView.setAlpha(1f);
        dragView = null; dragKey = null; dropTarget = null;
        dragLayer.setVisibility(GONE);
        invalidate();
    }

    /** 用全局坐标找落在哪个图标上。 */
    private View hitTest(float rawX, float rawY) {
        View found = null;
        found = scan(grid, rawX, rawY, found);
        found = scan(dockBar, rawX, rawY, found);
        return found;
    }

    private View scan(ViewGroup g, float rawX, float rawY, View cur) {
        if (g == null || g.getVisibility() != VISIBLE) return cur;
        // 审计 L13：拖拽 MOVE 每帧遍历全部子视图，int[2] 提到循环外复用
        int[] loc = new int[2];
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c == dragView || c.getTag() == null) continue;
            c.getLocationOnScreen(loc);
            if (rawX >= loc[0] && rawX <= loc[0] + c.getWidth()
                    && rawY >= loc[1] && rawY <= loc[1] + c.getHeight()) {
                return c;
            }
        }
        return cur;
    }

    private LauncherModel model() { return host == null ? null : host.model(); }
}
