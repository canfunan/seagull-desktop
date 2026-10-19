package com.seagull.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

public final class WallpaperPage extends FrameLayout {

    /** 小组件 bind 授权回执（HomeActivity.onActivityResult 原样转发进来）。 */
    public static final int REQ_BIND = 0x5E01;

    /** AppWidgetHost 实例 id：本应用专用，与系统其他 host 不冲突即可。 */
    private static final int HOST_ID = 0x5E59;

    private final TextView clock, date, media, lyric;
    private final LinearLayout clockBox;
    private final FrameLayout widgetLayer;
    private LauncherModel model;
    private AppWidgetHost host;
    private int pendingBindId;

    /** 已挂小组件的位置表：id → {x 中点比例, y 顶边比例}。 */
    private final Map<Integer, float[]> widgetPos = new HashMap<>();

    public WallpaperPage(Context c, LauncherModel m) {
        super(c);
        this.model = m;
        setBackgroundColor(Color.TRANSPARENT);

        widgetLayer = new FrameLayout(c);
        addView(widgetLayer, new LayoutParams(-1, -1));

        clockBox = new LinearLayout(c);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.CENTER_HORIZONTAL);

        clock = tv(c, 62, R.color.text, true);
        date = tv(c, 16, R.color.text_dim, false);
        media = tv(c, 15, R.color.text, false);
        lyric = tv(c, 14, R.color.text_dim, false);

        clockBox.addView(clock);
        clockBox.addView(date, topMargin(c, 6));
        clockBox.addView(media, topMargin(c, 30));
        clockBox.addView(lyric, topMargin(c, 4));
        addView(clockBox, new LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));

        // 长按组件 = 移动；原地点按 = 调字号
        clockBox.setOnTouchListener(new Drag(clockBox,
                (dx, dy) -> nudgeClock(dx, dy),
                this::showClockMenu,
                this::saveClockPos));

        setOnClickListener(v -> { /* 单击桌面不做事，避免误触 */ });
        setOnLongClickListener(v -> { showAddWidget(); return true; });
        applyClock();
        loadWidgets();
        tick();
    }

    /* ------------------------- 时钟块：位置与字号 ------------------------- */

    /** 时钟块默认：水平居中（x=中点比例），顶部 13%（y=顶边比例）。 */
    private void applyClock() {
        float s = model.wallClockScale <= 0 ? 1f : model.wallClockScale;
        clock.setTextSize(62 * s);
        date.setTextSize(16 * s);
        media.setTextSize(15 * s);
        lyric.setTextSize(14 * s);
        restackClockBox(s);
        placeClockBox();
    }

    /** 字号变了行距跟着等比缩，视觉重心不变。 */
    private void restackClockBox(float s) {
        LinearLayout.LayoutParams lp;
        lp = (LinearLayout.LayoutParams) date.getLayoutParams(); lp.topMargin = dp(6 * s); date.setLayoutParams(lp);
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
        lp.leftMargin = clamp(left, dp(8), Math.max(dp(8), w - bw - dp(8)));
        lp.topMargin = clamp(Math.round(h * model.wallClockY), dp(8),
                Math.max(dp(8), h - bh - dp(8)));
        clockBox.setLayoutParams(lp);
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
        new AlertDialog.Builder(getContext())
                .setTitle("时间日期大小（按住拖动可移位置）")
                .setItems(new String[]{"小", "标准", "大", "特大"}, (d, w) -> {
                    model.wallClockScale = w == 0 ? 0.7f : w == 1 ? 1f : w == 2 ? 1.3f : 1.6f;
                    applyClock();
                    model.save();
                })
                .show();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        placeClockBox();
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
                float d = getResources().getDisplayMetrics().density;
                int w = Math.max(Math.round(80 * d),
                        Math.min((int) (info.minWidth * d), Math.max(0, getWidth() - dp(16))));
                int h = Math.max(Math.round(60 * d),
                        Math.min((int) (info.minHeight * d), Math.max(0, getHeight() - dp(16))));
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
                attachWidget(id, 0.5f, 0.42f);
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
        attachWidget(id, 0.5f, 0.42f);
    }

    /** 建 host 视图挂上墙：位置按记录（或默认中上），可拖动、长按出菜单。 */
    private void attachWidget(final int id, float x, float y) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(getContext());
            AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
            if (info == null) { host().deleteAppWidgetId(id); return; }
            AppWidgetHostView hv = host().createView((Activity) getContext(), id, info);
            float d = getResources().getDisplayMetrics().density;
            int w = Math.max(Math.round(80 * d),
                    Math.min((int) (info.minWidth * d), Math.max(0, getWidth() - dp(16))));
            int h = Math.max(Math.round(60 * d),
                    Math.min((int) (info.minHeight * d), Math.max(0, getHeight() - dp(16))));
            widgetLayer.addView(hv, new LayoutParams(w, h, Gravity.TOP | Gravity.LEFT));
            widgetPos.put(id, new float[]{x, y});
            placeWidget(hv, x, y);
            hv.setOnTouchListener(new Drag(hv,
                    (dx, dy) -> nudgeWidget(hv, id, dx, dy),
                    () -> showWidgetMenu(id),
                    this::persistWidgets));
            persistWidgets();
            // 首帧前尺寸/位置都是近似值（页面还没量完），布局完成后按 provider 重算一次
            hv.post(() -> refitWidget(id));
        } catch (Throwable t) {
            android.widget.Toast.makeText(getContext(), "小组件挂载失败：" + t,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void placeWidget(View v, float x, float y) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        LayoutParams lp = (LayoutParams) v.getLayoutParams();
        lp.leftMargin = clamp(Math.round(w * x - v.getMeasuredWidth() / 2f),
                dp(8), Math.max(dp(8), w - v.getMeasuredWidth() - dp(8)));
        lp.topMargin = clamp(Math.round(h * y), dp(8),
                Math.max(dp(8), h - v.getMeasuredHeight() - dp(8)));
        v.setLayoutParams(lp);
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
    }

    private void showWidgetMenu(final int id) {
        new AlertDialog.Builder(getContext())
                .setTitle("小组件")
                .setItems(new String[]{"移除小组件"}, (d, w) -> {
                    View v = findWidget(id);
                    if (v != null) widgetLayer.removeView(v);
                    widgetPos.remove(id);
                    try { host().deleteAppWidgetId(id); } catch (Throwable ignore) {}
                    persistWidgets();
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
                        (float) o.optDouble("y", 0.42)});
                attachWidget(id, (float) o.optDouble("x", 0.5),
                        (float) o.optDouble("y", 0.42));
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
                arr.put(o);
            }
            model.wallWidgetsJson = arr.toString();
            model.save();
        } catch (Throwable ignore) {}
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try { host().startListening(); } catch (Throwable ignore) {}
    }

    @Override protected void onDetachedFromWindow() {
        try { if (host != null) host.stopListening(); } catch (Throwable ignore) {}
        super.onDetachedFromWindow();
    }

    /* ------------------------- 通用：长按拖动 ------------------------- */

    /**
     * 长按后进入拖动（拦掉 pager 的横向翻页）；长按但没挪 = tapAction
     * （时钟弹字号菜单 / 组件弹移除菜单）；挪过 = endAction（落盘）。
     */
    private final class Drag implements View.OnTouchListener {
        private final View target;
        private final Move onMove;
        private final Runnable tap, end;
        private final GestureDetector gd = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }
                    @Override public void onLongPress(MotionEvent e) {
                        dragging = true;
                        moved = false;
                        target.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                });
        private boolean dragging, moved;
        private float lx, ly;

        Drag(View target, Move onMove, Runnable tapAction, Runnable endAction) {
            this.target = target;
            this.onMove = onMove;
            this.tap = tapAction;
            this.end = endAction;
        }

        @Override public boolean onTouch(View v, MotionEvent e) {
            gd.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lx = e.getRawX();
                    ly = e.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) break;
                    float dx = e.getRawX() - lx, dy = e.getRawY() - ly;
                    if (Math.abs(dx) + Math.abs(dy) > 4) moved = true;
                    lx = e.getRawX();
                    ly = e.getRawY();
                    onMove.run(dx, dy);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        if (!moved && tap != null) tap.run();
                        else if (moved && end != null) end.run();
                        dragging = false;
                    }
                    break;
            }
            return true;
        }
    }

    /* ------------------------- 刷新与工具 ------------------------- */

    /** 跟着 HomeActivity 的 30s / Lyrics sink 刷新。 */
    public void tick() {
        if (clock != null) clock.setText(SysOps.clockHHmm());
        if (date != null) date.setText(SysOps.dateCn());

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
        if (media != null) media.setTextColor(Skin.c(R.color.text));
        if (lyric != null) lyric.setTextColor(Skin.c(R.color.text_dim));
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
