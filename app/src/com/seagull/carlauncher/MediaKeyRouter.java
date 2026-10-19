package com.seagull.carlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.view.KeyEvent;

import java.util.List;

/**
 * O1 方控媒体键兜底。
 * 车机方控的播放/暂停/上下曲以 KEYCODE_MEDIA_* 落到前台窗口时，这里把按键
 * 翻译成活跃媒体会话的 transportControls 调用：
 *   1) 本地 MusicService 会话且在播/暂停 → 优先（避免远端会话抢控制权）
 *   2) 否则取 getActiveSessions 列表第一个（框架按最近活跃排序）
 *   3) 一个会话都没有 → return false，按键走系统默认媒体路由
 * getActiveSessions 传通知监听服务的 ComponentName（Lyrics.activeController 同口径，
 * 传 null 在无 MEDIA_CONTENT_CONTROL 时直接 SecurityException）。
 */
public final class MediaKeyRouter {
    private static final String TAG = "MediaKey";
    /** 转发日志节流：方控长按会连发 down，1s 一条足够排障。 */
    private static long lastLog;

    private MediaKeyRouter() { }

    /** @return true 表示按键已被消费（DOWN 执行动作，UP 只吞掉防二次触发）。 */
    public static boolean handle(KeyEvent e, Context c) {
        final int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                break;
            default:
                return false;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) return true;   // 动作在 DOWN 已做
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        try {
            MediaController ctrl = pick(c);
            if (ctrl == null) return false;
            PlaybackState ps = ctrl.getPlaybackState();
            boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
            MediaController.TransportControls tc = ctrl.getTransportControls();
            switch (code) {
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    tc.play();
                    break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    tc.pause();
                    break;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (playing) tc.pause(); else tc.play();
                    break;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    tc.skipToNext();
                    break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    tc.skipToPrevious();
                    break;
                case KeyEvent.KEYCODE_MEDIA_STOP:
                    tc.stop();
                    break;
                default:
                    return false;
            }
            long now = SystemClock();
            if (now - lastLog > 1000L) {
                lastLog = now;
                RootLog.i(TAG, "方控键 " + code + " -> " + ctrl.getPackageName()
                        + (playing ? " (playing)" : ""));
            }
            return true;
        } catch (Throwable t) {
            // SecurityException（通知监听未授权）或框架异常：按键退回系统路由
            RootLog.w(TAG, "方控转发失败，走默认路由", t);
            return false;
        }
    }

    /** 本地会话（在播或暂停）优先，否则列表第一个；列表空返回 null。 */
    private static MediaController pick(Context c) {
        MediaSessionManager msm = (MediaSessionManager)
                c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm == null) return null;
        List<MediaController> list = msm.getActiveSessions(
                new ComponentName(c, MediaListenerService.class));
        if (list == null || list.isEmpty()) return null;
        String self = c.getPackageName();
        for (MediaController mc : list) {
            if (mc == null || !self.equals(mc.getPackageName())) continue;
            PlaybackState ps = mc.getPlaybackState();
            int s = ps == null ? -1 : ps.getState();
            if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_PAUSED) return mc;
        }
        return list.get(0);
    }

    private static long SystemClock() { return android.os.SystemClock.elapsedRealtime(); }
}
