package com.seagull.carlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * H 批次 GPS 管线：车速（#10）/ 海拔航向（#12）/ 本次行程（#14）三块共用。
 *
 * 设计要点：
 *  - 单例 fanout：acquire/release 配对，最后一个监听者退订即 removeUpdates
 *    （对齐歌词 sink / islandSink 的 attach-detach 纪律，杜绝挂空更新）；
 *  - 1s 节流（BACKLOG #10）：requestLocationUpdates(provider, 1000, 0)；
 *  - 回调统一切主线程（LocationManager 回调线程不保证是 UI 线程）；
 *  - 行程状态 = 进程级静态：点火时长取 elapsedRealtime（BACKLOG #14
 *    「重启归零重新计程」——进程被杀或重启即重新起算）；里程累计只吃
 *    speed>1.5 m/s 的相邻位移（过滤驻车漂移，RESEARCH A6 结论）。
 */
public final class LocationHelper {

    /** 主线程回调；loc 为最新一次定位（可能为 null = 尚无定位）。 */
    public interface Listener { void onGps(Location loc); }

    /** 行程清零入口的请求码（HomeActivity.onRequestPermissionsResult 转发用）。 */
    public static final int REQ_PERM = 0x5E70;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners = new ArrayList<>();
    private static LocationManager lm;
    private static Location last;

    /** 行程状态：类加载≈应用点火，本次进程内累计。 */
    private static long tripStartElapsed = SystemClock.elapsedRealtime();
    private static float tripMeters;

    private LocationHelper() { }

    public static boolean hasPermission(Context c) {
        if (c == null) return false;
        try {
            return c.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")
                    == PackageManager.PERMISSION_GRANTED
                    || c.checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 点按行车块授权；结果经 REQ_PERM 回 HomeActivity.onRequestPermissionsResult。 */
    public static void request(Activity a) {
        try {
            a.requestPermissions(new String[]{
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION"}, REQ_PERM);
        } catch (Throwable ignore) { }
    }

    /** 订阅定位；首个订阅者拉起 1s 更新。重复订阅同一实例无副作用。 */
    public static synchronized void acquire(Context c, Listener l) {
        if (c == null || l == null || listeners.contains(l)) return;
        listeners.add(l);
        if (listeners.size() == 1) start(c.getApplicationContext());
    }

    /** 退订；最后一个退订者负责关闭更新。 */
    public static synchronized void release(Listener l) {
        listeners.remove(l);
        if (listeners.isEmpty() && lm != null) {
            try { lm.removeUpdates(ll); } catch (Throwable ignore) { }
            lm = null;
        }
    }

    /** GPS 在位则用 GPS（有速度/海拔/航向），否则回落网络定位（只保证位置）。 */
    private static void start(Context app) {
        try {
            LocationManager m = (LocationManager)
                    app.getSystemService(Context.LOCATION_SERVICE);
            if (m == null) return;
            String best = null;
            if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                best = LocationManager.GPS_PROVIDER;
            } else if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                best = LocationManager.NETWORK_PROVIDER;
            }
            if (best == null) return;
            try {
                Location old = m.getLastKnownLocation(best);
                if (old != null) last = old;
            } catch (Throwable ignore) { }
            m.requestLocationUpdates(best, 1000L, 0f, ll);
            lm = m;
        } catch (Throwable t) {
            lm = null;
        }
    }

    /** 最近一次定位；超过 10s 未更新视为失联（隧道/车库场景显示 "--"）。 */
    public static Location last() {
        return last;
    }

    public static boolean fresh() {
        return last != null
                && SystemClock.elapsedRealtime() - last.getElapsedRealtimeNanos() / 1_000_000L < 10_000L;
    }

    /** 本次点火时长（秒）。 */
    public static long tripSeconds() {
        return Math.max(0L, (SystemClock.elapsedRealtime() - tripStartElapsed) / 1000L);
    }

    /** 本次里程（km），只累计 speed>1.5 m/s 的位移。 */
    public static float tripKm() {
        return tripMeters / 1000f;
    }

    public static void resetTrip() {
        tripMeters = 0f;
        tripStartElapsed = SystemClock.elapsedRealtime();
    }

    private static List<Listener> snapshot() {
        synchronized (LocationHelper.class) {
            return new ArrayList<>(listeners);
        }
    }

    private static final LocationListener ll = new LocationListener() {
        @Override public void onLocationChanged(Location loc) {
            if (loc == null) return;
            // 里程累计先于 last 覆盖：需要与前一次定位的位移
            float v = loc.getSpeed();
            Location prev = last;
            if (prev != null && v > 1.5f) {
                try { tripMeters += prev.distanceTo(loc); } catch (Throwable ignore) { }
            }
            last = loc;
            final Location f = loc;
            UI.post(() -> {
                for (Listener x : snapshot()) {
                    try { x.onGps(f); } catch (Throwable ignore) { }
                }
            });
        }

        @Override public void onStatusChanged(String p, int st, Bundle ex) { }
        @Override public void onProviderEnabled(String p) { }
        @Override public void onProviderDisabled(String p) { }
    };
}
