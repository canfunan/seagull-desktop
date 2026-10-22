package com.seagull.carlauncher;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 通知监听服务 —— 抓通知 + 供 MiniPlayer 读媒体会话（批次 N 扩展）。
 *
 * MiniPlayer 的数据源走 {@link MediaSessionManager#getActiveSessions}，而它要求
 * 调用方是「已启用的通知监听器」—— 正是本服务（RootOps.grantAll 已把它写进
 * enabled_notification_listeners）。不另起服务，不新增权限。
 *
 * 三件事：
 *   · {@link #refresh(Context)} 扫当前活跃会话，抓元数据/播放态进 Snap；
 *   · {@link #playPause()} / {@link #prev()} / {@link #next()} 直接下发 transport
 *     control（不经过画布，不碰焦点）；
 *   · {@link #snap()} 给 MiniPlayer 绑定；pkg 为空 = 没有媒体会话（条子整条隐藏）。
 */
public class MediaListenerService extends NotificationListenerService {

    private static final String TAG = "SeagullMediaListener";

    /** 当前媒体会话快照（pkg 为空 = 无会话）。 */
    public static final class Snap {
        public String pkg = "";
        public String title = "";
        public String artist = "";
        public boolean playing;
        /** 当前歌词行（通知文本里带歌词的音乐 app，如落雪；无则空）。 */
        public String lyric = "";
        /** 歌词更新时间（System.currentTimeMillis，UI 做渐隐/换行动画用）。 */
        public long lyricAt;
    }

    /** 底栏播放器要控制的音乐包（挂载哪个控制哪个 + 手机版同源）。 */
    private static final String[] MUSIC_PKGS = {
            "cn.toside.music.mobile",          // 落雪音乐
            "com.tencent.qqmusiccar",          // QQ音乐车机版
            "com.tencent.qqmusic",             // QQ音乐手机版
            "com.kugou.android.auto",          // 酷狗车机版
            "com.kugou.android",               // 酷狗手机版
            "com.netease.cloudmusic.iot",      // 网易云车机版
            "com.netease.cloudmusic",          // 网易云手机版
    };

    /**
     * 通知文本键位（野菜桌面普通版同款策略）：歌词行在音乐 app 的通知
     * text/bigText 里（落雪直接放当前歌词行；其余 app 放歌名/歌手 —— 与
     * title 相同则不算歌词）。按优先级取第一个非空且不等于 title 的。
     */
    private static final String[] LYRIC_KEYS = {
            "android.bigText", "android.text", "android.subText", "android.infoText"
    };

    private static volatile Snap snap = new Snap();
    private static volatile boolean connected;
    private static Context appCtx;
    private static MediaController current;

    @Override public void onCreate() {
        super.onCreate();
        appCtx = getApplicationContext();
        // 野菜同款自授权（批次 MB）：没授权就自己授 —— 不弹设置 UI、不重启、
        // 不动系统设置界面。授权后系统自动重绑本服务，onListenerConnected 跟着来。
        ensureAccess(appCtx);
    }

    /**
     * 通知监听自授权（野菜桌面普通版 LyricNotificationListener 同款机制）：
     * 反射调 NotificationManager 隐藏方法 setNotificationListenerAccessGranted
     * 直接授予本服务访问权 —— 系统内存态立即更新并自动重绑，全程无 UI、无重启。
     * 反射失败（个别 ROM 收紧隐藏 API）回落写 enabled_notification_listeners
     * 标准冒号格式（AOSP 原生格式，vivo 缓存需重启才刷的坑走不到这步）。
     */
    public static void ensureAccess(Context ctx) {
        if (ctx == null) return;
        ComponentName cn = new ComponentName(ctx, MediaListenerService.class);
        String flat = cn.flattenToString();
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            boolean granted = false;
            try {
                granted = nm.isNotificationListenerAccessGranted(cn);
            } catch (Throwable ignored) {
            }
            if (granted) {
                // 复盘·回归修复：access 在 ≠ binding 活 —— pm install / 杀进程后绑定
                // 会死，vivo 对死绑有限流（"a previous rebind attempt was made"，
                // 退避期内系统拒不重绑）→ 监听连不上 → 横幅无数据、控制打空。
                // disallow+allow 组合重置限流触发重绑（幂等，代价 2 条 root 命令）。
                Caps.exec("cmd notification disallow_listener " + flat);
                Caps.exec("cmd notification allow_listener " + flat);
                return;
            }
            try {
                Method m = NotificationManager.class.getMethod(
                        "setNotificationListenerAccessGranted", ComponentName.class, boolean.class);
                m.invoke(nm, cn, true);
                granted = true;
                Log.i(TAG, "通知监听自授权成功（反射）");
            } catch (Throwable t) {
                Log.w(TAG, "反射授权失败，回落 setting: " + t);
            }
            if (!granted) {
                // 回落（root 级）：cmd notification allow_listener —— 系统 shell 直通
                // NotificationManagerService 的服务端授权，一次性更新内存 Allowed 结构
                // （连 vivo 的 Has user set 花括号列表都同步），不重启、不碰设置 UI。
                // 反射在 Android 14 隐藏 API 拦截下必然 NoSuchMethodException，
                // 这条是实际生效路径；settings put 只是最后兜底（vivo 缓存不刷）。
                try {
                    String out = Caps.exec("cmd notification allow_listener " + flat);
                    Log.i(TAG, "通知监听自授权回落 cmd allow_listener: " + out);
                } catch (Throwable t) {
                    Log.w(TAG, "cmd allow_listener 失败，回落 setting: " + t);
                    try {
                        String cur = Caps.exec("settings get secure enabled_notification_listeners");
                        String nv = (cur == null || cur.trim().isEmpty()
                                || "null".equals(cur.trim())) ? flat
                                : (cur.contains(flat) ? cur.trim() : cur.trim() + ":" + flat);
                        Caps.exec("settings put secure enabled_notification_listeners '" + nv + "'");
                    } catch (Throwable ignore) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        Log.i(TAG, "通知监听已连接");
        refresh(appCtx);
        startCarWatch();   // 车机投屏守护：自动接线 + 掉了自愈
        // L1：重连后回放现有导航通知（不然服务重启后岛横幅要等下一次播报才回来）
        try {
            StatusBarNotification[] act = getActiveNotifications();
            if (act != null) for (StatusBarNotification sbn : act) NavNotiParser.feed(sbn);
        } catch (Throwable t) {
            Log.w(TAG, "回放活跃通知失败: " + t);
        }
    }

    @Override public void onListenerDisconnected() {
        connected = false;
        unregisterCurrent();   // 直接置 null 会留下挂在 controller 上的回调，重连后重复注册
        current = null;
        snap = new Snap();
        super.onListenerDisconnected();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        // 通知正文不进 logcat（批次 V 教训）；L1 导航通知解析也只取结果、不落日志
        NavNotiParser.feed(sbn);
        // 歌词提取（批次 MB）：音乐包的通知文本里捞当前歌词行 —— 只存结果不落日志
        try { feedLyric(sbn); } catch (Throwable ignored) {}
    }

    /** 音乐包通知 → 歌词行进 Snap。 */
    private static void feedLyric(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg == null || !isMusicPkg(pkg)) return;
        android.app.Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        CharSequence title = n.extras.getCharSequence("android.title");
        String titleStr = title == null ? null : title.toString().trim();
        for (String key : LYRIC_KEYS) {
            CharSequence v = n.extras.getCharSequence(key);
            if (v == null) continue;
            String line = v.toString().trim();
            if (line.isEmpty()) continue;
            // 通知文本=歌名/歌手（非歌词）时跳过：歌词行必须与标题不同
            if (titleStr != null && line.equals(titleStr)) continue;
            if (line.length() > 200) continue;   // 长文本多半是整段歌词，取首行
            int nl = line.indexOf('\n');
            if (nl > 0) line = line.substring(0, nl).trim();
            if (line.isEmpty() || line.equals(snap.lyric)) continue;
            Snap s = new Snap();
            s.pkg = snap.pkg; s.title = snap.title; s.artist = snap.artist;
            s.playing = snap.playing;
            s.lyric = line; s.lyricAt = System.currentTimeMillis();
            snap = s;
            return;
        }
    }

    public static boolean isMusicPkg(String pkg) {
        if (pkg == null) return false;
        for (String m : MUSIC_PKGS) if (m.equals(pkg)) return true;
        return false;
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        NavNotiParser.retract(sbn);
    }

    /* ---------------- MiniPlayer 数据源 ---------------- */

    public static Snap snap() { return snap; }

    /**
     * 扫活跃媒体会话：优先正在播放的，其次有元数据的。
     * 没启用通知监听 / 查询被拒时静默返回（MiniPlayer 保持隐藏，不挡画布）。
     */
    public static void refresh(Context ctx) {
        if (ctx == null || !connected) return;
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            List<MediaController> list =
                    msm.getActiveSessions(new ComponentName(ctx, MediaListenerService.class));
            MediaController pick = null;
            for (MediaController c : list) {
                PlaybackState ps = c.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
            }
            if (pick == null) {
                for (MediaController c : list) {
                    if (c.getMetadata() != null) { pick = c; break; }
                }
            }
            if (pick == null) {   // 没有媒体会话：条子整条隐藏
                if (current != null && current != pick) unregisterCurrent();
                current = null;
                snap = new Snap();
                return;
            }
            if (pick != current) {
                unregisterCurrent();
                current = pick;
                pick.registerCallback(cb, null);   // null handler → 主线程
            }
            readCurrent();
        } catch (Throwable t) {
            Log.w(TAG, "getActiveSessions 失败: " + t);
        }
    }

    private static void unregisterCurrent() {
        try { if (current != null) current.unregisterCallback(cb); }
        catch (Throwable ignore) {}
    }

    /** 元数据/播放态变化：只重读当前会话，不再扫列表（防回调递归）。 */
    private static final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { readCurrent(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { readCurrent(); }
        @Override public void onSessionDestroyed() {
            // 必须校验销毁的就是 current：A 会话在 B 上位后才销毁时，旧实现会把
            // B 的控制柄一起清空 → 媒体条直接失灵。先反注册再置空。
            unregisterCurrent();
            current = null;
            snap = new Snap();
            Log.i(TAG, "当前会话已销毁，清空媒体条");
        }
    };

    private static void readCurrent() {
        MediaController c = current;
        if (c == null) { snap = new Snap(); return; }
        Snap s = new Snap();
        try { s.pkg = c.getPackageName(); } catch (Throwable ignore) {}
        try {
            MediaMetadata md = c.getMetadata();
            if (md != null) {
                s.title = String.valueOf(md.getText(MediaMetadata.METADATA_KEY_TITLE));
                s.artist = String.valueOf(md.getText(MediaMetadata.METADATA_KEY_ARTIST));
            }
            PlaybackState ps = c.getPlaybackState();
            s.playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        } catch (Throwable t) {
            Log.w(TAG, "读媒体元数据失败: " + t);
        }
        snap = s;
    }

    /* ---------------- transport control（不经过画布） ---------------- */

    /** 按包播放/暂停（底栏/大卡：挂载哪个控制哪个）。 */
    public static void playPause(String pkg) {
        control(pkg, "playpause");
    }

    public static void playPause() {
        try {
            if (current == null) return;
            if (snapshotPlaying()) current.getTransportControls().pause();
            else current.getTransportControls().play();
        } catch (Throwable t) { Log.w(TAG, "playPause 失败: " + t); }
    }

    public static void prev() {
        try { if (current != null) current.getTransportControls().skipToPrevious(); }
        catch (Throwable t) { Log.w(TAG, "prev 失败: " + t); }
    }

    public static void next() {
        try { if (current != null) current.getTransportControls().skipToNext(); }
        catch (Throwable t) { Log.w(TAG, "next 失败: " + t); }
    }

    /**
     * 按包名找活跃媒体会话（批次 MB：画中画挂载哪个播放器就控制哪个）。
     * refresh() 的 current 是「正在播放的那个」—— 挂载 A 放 B 时 current 可能
     * 是 B，控制会打错人。这里按包精确定位；找不到回退 current。
     */
    public static MediaController controllerFor(String pkg) {
        if (pkg == null || pkg.isEmpty()) return current;
        if (appCtx == null || !connected) return current;
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) appCtx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return current;
            List<MediaController> list =
                    msm.getActiveSessions(new ComponentName(appCtx, MediaListenerService.class));
            for (MediaController c : list) {
                if (pkg.equals(c.getPackageName())) return c;
            }
        } catch (Throwable ignored) {
        }
        return current;
    }

    /**
     * 指定包的播放态（底栏/大卡的播放按钮图标用）—— 读目标控制器自身的
     * PlaybackState，不是全局 snap（snap 是「正在发声的那个」，挂载 A 放 B 时
     * 按钮会显示反）。拿不到（目标没会话）回落 snap.playing。
     */
    public static boolean playing(String pkg) {
        try {
            MediaController c = controllerFor(pkg);
            if (c != null) {
                PlaybackState ps = c.getPlaybackState();
                if (ps != null) {
                    return ps.getState() == PlaybackState.STATE_PLAYING;
                }
            }
        } catch (Throwable ignored) {
        }
        return snap.playing;
    }

    /** 底栏播放器按钮：对指定包（挂载的那个）下发传输控制。 */
    public static void control(String pkg, String action) {
        try {
            MediaController c = controllerFor(pkg);
            if (c == null) return;
            MediaController.TransportControls tc = c.getTransportControls();
            if (tc == null) return;
            switch (action) {
                case "play":  tc.play();  break;
                case "pause": tc.pause(); break;
                case "next":  tc.skipToNext(); break;
                case "prev":  tc.skipToPrevious(); break;
                case "playpause": {
                    // 按目标控制器自身的播放态翻转（不是全局 snap —— 挂载 A 放 B
                    // 时 snap.playing 可能是 B 的）
                    PlaybackState ps = null;
                    try { ps = c.getPlaybackState(); } catch (Throwable ignored) {}
                    boolean playing = ps != null
                            && ps.getState() == PlaybackState.STATE_PLAYING;
                    if (playing) tc.pause(); else tc.play();
                    break;
                }
                default:      break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "control(" + pkg + "," + action + ") 失败: " + t);
        }
    }

    /** R1：当前会话 metadata（封面/时长来源）；无会话返回 null。 */
    public static MediaMetadata meta() {
        try { return current == null ? null : current.getMetadata(); }
        catch (Throwable t) { return null; }
    }

    /**
     * R1：进度 0~1（位置快照 + 速度×流逝时间外推）；无时长/无会话返回 -1。
     * 底栏 1s tick 自取，不另建回调。
     */
    public static float mediaProgress() {
        try {
            if (current == null) return -1f;
            MediaMetadata md = current.getMetadata();
            PlaybackState ps = current.getPlaybackState();
            if (md == null || ps == null) return -1f;
            long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (dur <= 0) return -1f;
            long pos = ps.getPosition();
            if (ps.getState() == PlaybackState.STATE_PLAYING
                    && ps.getLastPositionUpdateTime() > 0) {
                float speed = Math.max(0f, ps.getPlaybackSpeed());
                pos += (long) ((System.currentTimeMillis()
                        - ps.getLastPositionUpdateTime()) * speed);
            }
            float f = pos / (float) dur;
            return Math.max(0f, Math.min(1f, f));
        } catch (Throwable t) {
            return -1f;
        }
    }

    /* ---------------- 车机投屏守护（自动接线 + 掉了自愈） ---------------- */

    private static volatile boolean carWatchRunning;
    private static Thread carWatchThread;

    /**
     * 车机投屏守护（vivo互联投屏·用户两大痛点的解药）：
     *   ① 「不可能每次都要你接线」—— 车机屏一出现（vivo互联连上）自动把
     *      海鸥桌面推上去，用户零操作；
     *   ② 「海鸥桌面也会掉线」—— 投影断 / app 被杀 / config change 掉了，
     *      5 秒内发现并 root 重推，自愈。
     * 本服务是系统级常驻（通知监听授权绑着，杀不掉），最适合当守护宿主。
     * 轮询用 root（su dumpsys）—— 代价可控（5s 一次，只读）。
     */
    public static void startCarWatch() {
        if (carWatchRunning) return;
        carWatchRunning = true;
        carWatchThread = new Thread(() -> {
            Log.i(TAG, "车机投屏守护启动（5s 轮询）");
            while (carWatchRunning) {
                try {
                    if (connected) guardCarScreen();
                } catch (Throwable t) {
                    Log.w(TAG, "守护轮询异常: " + t);
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
            Log.i(TAG, "车机投屏守护退出");
        }, "seagull-carwatch");
        carWatchThread.setDaemon(true);
        carWatchThread.start();
    }

    public static void stopCarWatch() {
        carWatchRunning = false;
        if (carWatchThread != null) carWatchThread.interrupt();
    }

    /** 一轮巡检：车机屏在而海鸥桌面不在 → root 重推。 */
    private static void guardCarScreen() {
        if (appCtx == null) return;
        // 1. 车机屏在不在（vivo_car_screen 的 displayId）
        String disp = Caps.exec("dumpsys display | grep -B8 vivo_car_screen | grep -oE 'displayId [0-9]+' | tail -1 | grep -oE '[0-9]+'");
        if (disp == null || disp.trim().isEmpty()) return;   // 车机没连，不用管
        final int did;
        try {
            did = Integer.parseInt(disp.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (did <= 0) return;
        // 2. 海鸥桌面在不在车机屏上
        String acts = Caps.exec("dumpsys activity activities | grep -c 'com.seagull.carlauncher/.HomeActivity.*d" + did + "'");
        boolean onScreen = acts != null && acts.trim().startsWith("0") == false
                && !"0".equals(acts.trim());
        if (onScreen) return;   // 在，不用管
        // 3. 不在 → root 重推（自动接线 / 掉了自愈）
        String out = Caps.exec("am start --display " + did
                + " -f 0x18800000 -n com.seagull.carlauncher/.HomeActivity");
        Log.i(TAG, "车机守护：桌面不在车机屏 d" + did + " → 已重推 | "
                + (out == null ? "null" : out.trim().replace('
', ' ')));
    }

    private static boolean snapshotPlaying() { return snap.playing; }

    /** 批次 S2：当前播放位置毫秒（外推同 mediaProgress）；无会话/无元数据返回 -1。 */
    public static long mediaPosMs() {
        try {
            if (current == null || current.getPlaybackState() == null) return -1L;
            PlaybackState ps = current.getPlaybackState();
            long pos = ps.getPosition();
            if (ps.getState() == PlaybackState.STATE_PLAYING
                    && ps.getLastPositionUpdateTime() > 0) {
                float speed = Math.max(0f, ps.getPlaybackSpeed());
                pos += (long) ((System.currentTimeMillis()
                        - ps.getLastPositionUpdateTime()) * speed);
            }
            return Math.max(0L, pos);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 批次 S2：当前曲目总时长毫秒；无会话/无时长返回 -1。 */
    public static long mediaDurMs() {
        try {
            if (current == null || current.getMetadata() == null) return -1L;
            long dur = current.getMetadata()
                    .getLong(MediaMetadata.METADATA_KEY_DURATION);
            return dur > 0 ? dur : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }
}
