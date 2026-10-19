package com.seagull.carlauncher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 天气 —— 走 Open-Meteo 公开接口：免 key、免注册、HTTPS，车机上不用额外交互。
 *
 * 城市来源三种（LauncherModel.CitySource）：
 *   MANUAL    手动填城市名 → 先查地名拿经纬度
 *   AUTO_GPS  取本机最后一次定位的经纬度
 *   AUTO_NET  按网络判断城市 —— 免 key 的 IP 定位接口不存在，
 *             所以退化���「沿用上次缓存的城市」（ponytail: 等有可用服务再接）
 *
 * 组件条、菜园、设置页都读同一份 model.weatherSummary/Detail/Forecast，
 * 20 分钟自动更新由 arm() 单例定时器驱动（HomeActivity resume 时 arm）。
 */
public final class Weather {

    private static final String TAG = "SeagullWeather";

    private static final String GEO_URL =
            "https://geocoding-api.open-meteo.com/v1/search?name=%s&count=1&language=zh&format=json";
    private static final String FC_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
            + "&current=temperature_2m,weather_code"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
            + "&forecast_days=4&timezone=auto";

    /** 9.8 每 20 分钟更新。 */
    public static final long INTERVAL = 20 * 60_000L;

    /** K1 天气联动：取数成功（或失败清态）后在主线程回调，宿主借它对齐联动壁纸。 */
    public interface OnUpdated { void onUpdated(); }
    public static volatile OnUpdated onUpdated;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static boolean armed = false;

    private Weather() {}

    /* ==================== 定时刷新 ==================== */

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            armed = false;   // 本轮开始；arm() 会重新上锁并排下一轮
            Context ctx = sCtx;
            if (ctx == null) return;
            LauncherModel m = new LauncherModel(ctx, false);
            if (!m.weatherAuto) return;   // 关了就停摆；arm() 在设置打开时被再次调用
            if (System.currentTimeMillis() - m.weatherUpdatedAt >= INTERVAL - 60_000L) {
                refresh(ctx, m, null);
            }
            // 上面 return 的两条路径都会把 armed 留在 true：没有这一句，
            // 20 分钟链在这里断掉，天气再也不会自动刷新（只有重启桌面才续上）
            if (!armed) arm(ctx);
        }
    };

    private static Context sCtx;

    /** 首页 onResume 调一次：起 20 分钟定时器，并在数据过期时立刻取一次。 */
    public static void arm(Context ctx) {
        if (ctx != null) sCtx = ctx.getApplicationContext();
        if (sCtx == null || armed) return;
        armed = true;
        MAIN.removeCallbacks(TICK);
        MAIN.postDelayed(TICK, INTERVAL);
    }

    public static void disarm() {
        armed = false;
        MAIN.removeCallbacks(TICK);
    }

    /* ==================== 取数 ==================== */

    /** 异步刷新；done 在主线程回调（失败也会调，weatherSummary 保持原值）。 */
    public static void refresh(final Context c, final LauncherModel m, final Runnable done) {
        POOL.execute(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    double[] xy = coords(c, m);
                    if (xy == null) {
                        err = "还没有城市：设置里填一个城市名，或给定位权限";
                    } else {
                        JSONObject o = new JSONObject(http(String.format(Locale.US, FC_URL, xy[0], xy[1])));
                        JSONObject cur = o.getJSONObject("current");
                        JSONObject day = o.getJSONObject("daily");
                        JSONArray dcode = day.getJSONArray("weather_code");
                        JSONArray dmax = day.getJSONArray("temperature_2m_max");
                        JSONArray dmin = day.getJSONArray("temperature_2m_min");

                        // 审计 M2：改模型字段 + save() 序列化集合都要持 IO_LOCK，
                        // 与主线程的图标拖动/文件夹操作互斥（否则 save 里的
                        // JSONArray(集合) 会撞 ConcurrentModificationException，
                        // 被 try 吞掉 = 静默丢档）
                        synchronized (m.ioLock()) {
                            m.weatherSummary = code(cur.optInt("weather_code")) + " "
                                    + Math.round(cur.optDouble("temperature_2m")) + "°";
                            m.weatherCode = cur.optInt("weather_code", -1);   // K1 联动用
                            StringBuilder sb = new StringBuilder();
                            for (int i = 1; i < Math.min(4, dcode.length()); i++) {
                                if (i > 1) sb.append("　");
                                sb.append(dayName(day.optJSONArray("time").optString(i)))
                                        .append(' ').append(code(dcode.optInt(i)))
                                        .append(' ').append(Math.round(dmin.optDouble(i)))
                                        .append('~').append(Math.round(dmax.optDouble(i))).append('°');
                            }
                            m.weatherForecast = sb.toString();
                            m.weatherUpdatedAt = System.currentTimeMillis();
                            m.weatherError = "";
                            m.save();
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "取天气失败", t);
                    err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                }
                if (err != null) {
                    synchronized (m.ioLock()) {
                        m.weatherError = err;
                        m.save();
                    }
                }
                if (done != null) MAIN.post(done);
                MAIN.post(() -> {
                    OnUpdated l = onUpdated;
                    if (l != null) l.onUpdated();
                });
            }
        });
    }

    /** 定位优先：GPS 取本机经纬度；否则按城市名查地名。 */
    private static double[] coords(Context c, LauncherModel m) throws Exception {
        if (m.citySource == LauncherModel.CitySource.AUTO_GPS) {
            Location l = lastKnown(c);
            if (l != null) {
                m.weatherLat = l.getLatitude();
                m.weatherLon = l.getLongitude();
                return new double[]{l.getLatitude(), l.getLongitude()};
            }
            // 没定位权限/没数据 → 退回手填城市
        }
        String city = m.weatherCity.trim();
        if (city.isEmpty()) {
            if (m.weatherLat != 0 || m.weatherLon != 0) return new double[]{m.weatherLat, m.weatherLon};
            return null;
        }
        if (m.weatherLat != 0 || m.weatherLon != 0) return new double[]{m.weatherLat, m.weatherLon};
        JSONObject o = new JSONObject(http(String.format(Locale.US, GEO_URL,
                URLEncoder.encode(city, "UTF-8"))));
        JSONArray rs = o.optJSONArray("results");
        if (rs == null || rs.length() == 0) throw new IllegalStateException("查不到城市：" + city);
        JSONObject r = rs.getJSONObject(0);
        m.weatherLat = r.optDouble("latitude");
        m.weatherLon = r.optDouble("longitude");
        return new double[]{m.weatherLat, m.weatherLon};
    }

    private static Location lastKnown(Context c) {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER}) {
                Location l = lm.getLastKnownLocation(p);
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
        } catch (Throwable ignore) {}
        return best;
    }

    /* ==================== 展示取值 ==================== */

    public static String summary(LauncherModel m) {
        if (m == null) return "—";
        if (!m.weatherSummary.isEmpty()) return m.weatherSummary;
        return m.weatherError.isEmpty() ? "未获取" : "取不到";
    }

    /** 组件条小字：没有 3 天预报时显示更新时间。 */
    public static String stamp(LauncherModel m) {
        if (m == null || m.weatherUpdatedAt == 0L) return "点一下获取";
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date(m.weatherUpdatedAt)) + " 更新";
    }

    public static String updatedLabel(long at) {
        if (at == 0L) return "尚未更新";
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date(at));
    }

    /** WMO 天气代码 → 中文，够用的粒度。 */
    static String code(int c) {
        if (c == 0) return "晴";
        if (c <= 2) return "多云";
        if (c == 3) return "阴";
        if (c == 45 || c == 48) return "雾";
        if (c >= 51 && c <= 57) return "毛毛雨";
        if (c >= 61 && c <= 67) return "雨";
        if (c >= 71 && c <= 77) return "雪";
        if (c >= 80 && c <= 82) return "阵雨";
        if (c == 85 || c == 86) return "阵雪";
        if (c >= 95) return "雷阵雨";
        return "—";
    }

    private static String dayName(String isoDate) {
        // 2026-10-02 → 今天 / 明天 / 后天 / 周四
        try {
            java.util.Calendar d = java.util.Calendar.getInstance();
            d.clear();
            d.set(Integer.parseInt(isoDate.substring(0, 4)),
                    Integer.parseInt(isoDate.substring(5, 7)) - 1,
                    Integer.parseInt(isoDate.substring(8, 10)));
            int diff = Math.round((d.getTimeInMillis() - startOfDay()) / 86400000f);
            if (diff == 0) return "今天";
            if (diff == 1) return "明天";
            if (diff == 2) return "后天";
            String[] w = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
            return w[d.get(java.util.Calendar.DAY_OF_WEEK) - 1];
        } catch (Throwable t) {
            return isoDate.length() >= 10 ? isoDate.substring(5, 10) : isoDate;
        }
    }

    private static long startOfDay() {
        java.util.Calendar d = java.util.Calendar.getInstance();
        d.set(java.util.Calendar.HOUR_OF_DAY, 0);
        d.set(java.util.Calendar.MINUTE, 0);
        d.set(java.util.Calendar.SECOND, 0);
        d.set(java.util.Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    /* ==================== HTTP ==================== */

    private static String http(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                // 审计 L3：响应体封顶 512KB —— 天气 JSON 正常十几 KB，异常
                // 回包（HTML 错误页/被劫持页）不该无限吃进内存
                if (total > 512 * 1024) {
                    throw new java.net.SocketTimeoutException("响应超过 512KB 上限");
                }
                bo.write(buf, 0, n);
            }
            String s = bo.toString("UTF-8");
            if (code >= 400) throw new IllegalStateException("HTTP " + code + " " + s);
            return s;
        } finally {
            try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }
}
