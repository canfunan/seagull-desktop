package com.seagull.carlauncher;

import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L1 导航通知解析骨架：导航应用（高德/百度）的 ongoing 通知 → 灵驹岛横幅。
 *
 * 纪律（批次 V + 本批 L1）：
 *   · 只读 TITLE/TEXT 两个字段，且解析结果只在内存里，正文绝不进 logcat
 *     （通知正文漏给 adb/bugreport 的教训见 MediaListenerService.onNotificationPosted）；
 *   · 模板全部是常量，真机把两类导航的 ongoing 通知格式探明后在这里填正则，
 *     解析不出就返回 null —— 岛上静默隐藏，绝不摆一段乱码给司机看；
 *   · 不设开关不进设置页：能解析出来才显示，失败=没有这个功能。
 *
 * 与 NavCard（高德车联服务，批次 AN）互不依赖：车联通了看卡片，
 * 车联没通但通知还在，灵驹岛兜底显示一条。
 */
public final class NavNotiParser {

    /** 参与解析的导航包名（百度地图正式包名 com.baidu.BaiduMap；车版另探）。 */
    private static final String[] NAV_PKGS = {
            "com.autonavi.minimap", "com.baidu.BaiduMap", "com.baidu.navi",
    };

    /* ==================== 正则模板（真机探明后在此填改） ==================== */

    /** 转向+距离：「500米后右转」「1.2公里后靠右」「前方500米右转」这类句式。 */
    private static final Pattern T_TURN = Pattern.compile(
            "(?:前方|前面)?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(公里|千米|km|m|米)\\s*(?:后|处)?\\s*"
                    + "(左转|右转|直行|掉头|靠左|靠右|并入|向左|向右)");
    /** 全程剩余：「剩余 12 公里 / 25分钟」「全程剩余…」这类句式。 */
    private static final Pattern T_REMAIN = Pattern.compile(
            "剩余\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(公里|km|分钟|min)");

    /* ==================== 状态与回调 ==================== */

    /** 当前解析结果：[0]=横幅主行，[1]=副行；null = 当前没有可用导航信息。 */
    private static volatile String[] now;
    /** 正在展示横幅的那条通知（导航应用会同时挂推广/地图更新等非 ongoing 通知，
     *  那类通知到达或撤回都别动横幅，否则导航中横幅闪断）。 */
    private static volatile String liveKey;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 宿主（桌面）监听：通知驱动的导航信息有变时回调（主线程）。 */
    public interface Listener { void onNavBannerChanged(); }
    private static volatile Listener listener;

    public static void setListener(Listener l) { listener = l; }

    public static String[] now() { return now; }

    private NavNotiParser() {}

    /** MediaListenerService.onNotificationPosted 转进来；解析不出即清态。 */
    public static void feed(StatusBarNotification sbn) {
        if (sbn == null || !isNavPkg(sbn.getPackageName())) return;
        if (sbn.isOngoing()) {
            String[] hit = parse(sbn.getNotification());
            if (hit != null) {
                liveKey = sbn.getKey();
                post(hit);
                return;
            }
            // ongoing 但模板没匹配上：只有当展示中的就是这条时才静默隐藏
            if (sbn.getKey() != null && sbn.getKey().equals(liveKey)) post(null);
        }
        // 非 ongoing：导航应用的推广/更新通知，忽略（不当导航结束处理）
    }

    /** 通知被撤（用户划掉/应用撤回）→ 只有撤的是展示中那条才清态。 */
    public static void retract(StatusBarNotification sbn) {
        if (sbn == null || !isNavPkg(sbn.getPackageName())) return;
        if (sbn.getKey() != null && sbn.getKey().equals(liveKey)) {
            liveKey = null;
            post(null);
        }
    }

    private static boolean isNavPkg(String pkg) {
        for (String p : NAV_PKGS) if (p.equals(pkg)) return true;
        return false;
    }

    private static void post(String[] s) {
        now = s;
        Listener l = listener;
        if (l != null) MAIN.post(l::onNavBannerChanged);
    }

    /** 模板命中 → [主行, 副行]；任一关键字段缺失都视为没解析出来。 */
    private static String[] parse(Notification n) {
        if (n == null) return null;
        CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence x = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        String title = t == null ? "" : t.toString().trim();
        String text = x == null ? "" : x.toString().trim();
        if (title.isEmpty() && text.isEmpty()) return null;

        String turn = null, remain = null;
        Matcher m = T_TURN.matcher(title + " " + text);
        if (m.find()) turn = m.group(3) + " · " + m.group(1) + m.group(2);        m = T_REMAIN.matcher(title + " " + text);
        if (m.find()) remain = "剩 " + m.group(1) + m.group(2);

        // 主行：转向+距离；副行：全程剩余 / 原样标题兜底
        if (turn != null) return new String[]{turn, remain != null ? remain : title};
        if (remain != null) return new String[]{title.isEmpty() ? "导航中" : title, remain};
        return null;
    }
}
