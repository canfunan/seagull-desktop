package com.seagull.carlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

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
    }

    private static volatile Snap snap = new Snap();
    private static volatile boolean connected;
    private static Context appCtx;
    private static MediaController current;

    @Override public void onCreate() {
        super.onCreate();
        appCtx = getApplicationContext();
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        Log.i(TAG, "通知监听已连接");
        refresh(appCtx);
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

    private static boolean snapshotPlaying() { return snap.playing; }
}
