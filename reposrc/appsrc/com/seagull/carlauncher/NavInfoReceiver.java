package com.seagull.carlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 批次 NB：三地图归一化导航信息桥。
 *
 * 数据源（反编译 base_net classes2/5 坐实）：vivo.car.networking 的
 * UCarMapService（action=ucar.service.MAP_SERVICE）只收三个手机版地图
 * （minimap / BaiduMap / tencent.map）经 showNavInfo 推来的 Bundle，由
 * g6.e（UCarMapInfo）解析成统一字段。XposedEntry.hookVivoNavInfo 在
 * vivo 进程 hook g6.e.a(Bundle) after，把字段广播过来（ACTION_NAV_INFO）。
 *
 * 本类做三件事：
 *   ① 静态注册广播（MediaListenerService.onCreate 调一次，常驻）；
 *   ② 持有 sink 列表（NavCard 注册/摘除，纪律同 Lyrics.Sink）；
 *   ③ 主线程分发（导航 hook 在 vivo 进程的 binder 线程，UI 更新必须回主线程）。
 *
 * 车机纪律：只有信息、没有按钮；正文不进 logcat。
 */
public final class NavInfoReceiver {

    private static final String TAG = "SeagullNavRx";
    private static volatile boolean registered;
    private static final CopyOnWriteArrayList<Listener> SINKS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 一条导航信息（字段与 Bundle key 一一对应）。批次 NB2：加 icon（高德原味 Icon）。 */
    public static final class NavInfo {
        public final int iconId;       // DIRECTION_ICON_ID（vivo 线才有）
        public final String dist;      // DISTANCE（如 "300"）
        public final String unit;      // DISTANCE_UNIT（米/公里）
        public final String op;        // OPERATION（如 "左转"）
        public final String where;     // WHERE（路口描述）
        public final String t1;        // TITLE_1（下一道路名）
        public final String t2;        // TITLE_2（再下一道路名）
        public final android.graphics.drawable.Icon icon;   // DIRECTION_ICON（高德线原味）

        public NavInfo(int iconId, String dist, String unit, String op,
                       String where, String t1, String t2) {
            this(iconId, dist, unit, op, where, t1, t2, null);
        }

        public NavInfo(int iconId, String dist, String unit, String op,
                       String where, String t1, String t2, android.graphics.drawable.Icon icon) {
            this.iconId = iconId;
            this.dist = dist == null ? "" : dist;
            this.unit = unit == null ? "" : unit;
            this.op = op == null ? "" : op;
            this.where = where == null ? "" : where;
            this.t1 = t1 == null ? "" : t1;
            this.t2 = t2 == null ? "" : t2;
            this.icon = icon;
        }

        public boolean isEmpty() {
            return dist.isEmpty() && t1.isEmpty() && op.isEmpty();
        }
    }

    public interface Listener {
        void onNavInfo(NavInfo info);
        /** 导航结束（地图退出导航）—— 卡片该隐藏了。 */
        void onNavClear();
    }

    private static final BroadcastReceiver RX = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !Constants.ACTION_NAV_INFO.equals(intent.getAction())) return;
            final NavInfo info = new NavInfo(
                    intent.getIntExtra("iconId", 0),
                    intent.getStringExtra("dist"),
                    intent.getStringExtra("unit"),
                    intent.getStringExtra("op"),
                    intent.getStringExtra("where"),
                    intent.getStringExtra("t1"),
                    intent.getStringExtra("t2"));
            if (Constants.ACTION_NAV_CLEAR.equals(intent.getAction())) {
                MAIN.post(() -> {
                    for (Listener l : SINKS) {
                        try {
                            l.onNavClear();
                        } catch (Throwable t) {
                            Log.w(TAG, "clear 分发失败", t);
                        }
                    }
                });
                return;
            }
            // 批次 NB2：高德原味 Bundle（bs6.a() 整个塞过来的）优先解析
            android.os.Bundle nb = intent.getBundleExtra("b");
            if (nb != null) {
                android.graphics.drawable.Icon ic = null;
                try {
                    ic = nb.getParcelable("ucar.map.bundle.DIRECTION_ICON");
                } catch (Throwable ignore) {
                }
                final NavInfo hi = new NavInfo(0,
                        nb.getString("ucar.map.bundle.DISTANCE"),
                        nb.getString("ucar.map.bundle.DISTANCE_UNIT"),
                        nb.getString("ucar.map.bundle.OPERATION"),
                        nb.getString("ucar.map.bundle.WHERE"),
                        nb.getString("ucar.map.bundle.TITLE_1"),
                        nb.getString("ucar.map.bundle.TITLE_2"), ic);
                MAIN.post(() -> {
                    for (Listener l : SINKS) {
                        try {
                            l.onNavInfo(hi);
                        } catch (Throwable t) {
                            Log.w(TAG, "sink 分发失败", t);
                        }
                    }
                });
                return;
            }
            if (info.isEmpty()) return;
            MAIN.post(() -> {
                for (Listener l : SINKS) {
                    try {
                        l.onNavInfo(info);
                    } catch (Throwable t) {
                        Log.w(TAG, "sink 分发失败", t);
                    }
                }
            });
        }
    };

    /** 注册广播（幂等；Context 用 applicationContext，别泄漏 Activity）。 */
    public static void register(Context ctx) {
        if (registered || ctx == null) return;
        try {
            Context app = ctx.getApplicationContext();
            IntentFilter f = new IntentFilter();
            f.addAction(Constants.ACTION_NAV_INFO);
            f.addAction(Constants.ACTION_NAV_CLEAR);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(RX, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(RX, f);
            }
            registered = true;
            Log.i(TAG, "导航信息广播已注册");
        } catch (Throwable t) {
            Log.w(TAG, "注册失败", t);
        }
    }

    public static void addSink(Listener l) {
        if (l != null) SINKS.addIfAbsent(l);
    }

    /** 摘 sink（HomeActivity.onDestroy 必须调——持死 Activity 的教训）。 */
    public static void removeSink(Listener l) {
        if (l != null) SINKS.remove(l);
    }

    /** DISTANCE + DISTANCE_UNIT → 米（解析不了回 -1）。 */
    public static int parseMeters(String dist, String unit) {
        if (TextUtils.isEmpty(dist)) return -1;
        try {
            float v = Float.parseFloat(dist.trim());
            if (unit != null && (unit.contains("公里") || unit.contains("km")
                    || unit.contains("千米"))) {
                v *= 1000f;
            }
            return Math.round(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
