package com.seagull.carlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * M2 本地音乐播放服务：MediaPlayer + 前台服务 + MediaSession。
 *
 * 关键设计：媒体状态走标准 MediaSession —— MiniPlayer（MediaListenerService
 * getActiveSessions）和歌词组件（Lyrics.fromSession）都从会话读，本地播放
 * 一挂上就自动出现在桌面所有媒体 UI 里，零额外联动代码（清单 M2 的
 * 「MiniPlayer 自动联动」就是这么白拿的）。
 *
 * 队列在服务内存里（static）：M3 列表页点歌 = 置队列+跳 index；切歌/
 * 播完自动下一首（列表循环）。车机纪律：恒不抢成焦点独占之外的东西 ——
 * 正常 requestAudioFocus(GAIN)，壁纸视频本来就恒静音不会打架。
 */
public class MusicService extends Service {

    private static final String TAG = "SeagullMusic";
    private static final String CH = "seagull_music";

    /** 队列（内存态，UI 只读）；empty = 没在播本地音乐。 */
    private static final List<MusicLib.Track> queue = new ArrayList<>();
    private static volatile int idx = -1;
    private static volatile boolean playing;

    private MediaPlayer mp;
    private MediaSession session;
    private AudioManager am;
    private AudioFocusRequest afr;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public static List<MusicLib.Track> queue() { return queue; }
    public static int index() { return idx; }
    public static boolean isPlaying() { return playing; }

    /* ---------------- 静态控制口（UI 直接调） ---------------- */

    /** 置队列并从 at 开始播（M3 点歌入口）。 */
    public static void play(Context c, List<MusicLib.Track> list, int at) {
        queue.clear();
        queue.addAll(list);
        Intent i = new Intent(c, MusicService.class);
        i.setAction("play");
        i.putExtra("at", at);
        c.startService(i);
    }

    public static void toggle(Context c) { act(c, "toggle"); }
    public static void next(Context c) { act(c, "next"); }
    public static void prev(Context c) { act(c, "prev"); }
    public static void seek(Context c, int posMs) {
        Intent i = new Intent(c, MusicService.class);
        i.setAction("seek");
        i.putExtra("pos", posMs);
        c.startService(i);
    }

    private static void act(Context c, String a) {
        c.startService(new Intent(c, MusicService.class).setAction(a));
    }

    public static int position() {
        MediaPlayer p = instance;
        try { return p != null ? p.getCurrentPosition() : 0; } catch (Throwable t) { return 0; }
    }

    public static int duration() {
        MediaPlayer p = instance;
        try { return p != null ? p.getDuration() : 0; } catch (Throwable t) { return 0; }
    }

    private static volatile MediaPlayer instance;

    /* ---------------- 生命周期 ---------------- */

    @Override public void onCreate() {
        super.onCreate();
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        session = new MediaSession(this, "SeagullLocalMusic");
        session.setCallback(cb);
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "本地音乐",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        try {
            afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .build();
        } catch (Throwable ignore) { }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null || intent.getAction() == null ? "" : intent.getAction();
        switch (a) {
            case "play": playAt(intent.getIntExtra("at", 0)); break;
            case "toggle": if (playing) pause(); else resume(); break;
            case "next": step(1, true); break;
            case "prev": step(-1, true); break;
            case "seek": seekTo(intent.getIntExtra("pos", 0)); break;
            default: break;
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        playing = false;
        try { if (session != null) session.release(); } catch (Throwable ignore) {}
        releaseMp();
        try { if (afr != null) am.abandonAudioFocusRequest(afr); } catch (Throwable ignore) {}
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /* ---------------- 播放核心 ---------------- */

    private void playAt(int at) {
        if (queue.isEmpty()) { stopSelf(); return; }
        if (at < 0) at = 0;
        if (at >= queue.size()) at = 0;
        idx = at;
        try { if (am != null && afr != null) am.requestAudioFocus(afr); } catch (Throwable ignore) { }
        startForeground(0x5E71, notif(queue.get(at).title, queue.get(at).artist, true));
        step(0, false);
    }

    /** delta=0 重播当前；auto=true 表示自动续播（播完），到底回第一首。 */
    private void step(int delta, boolean auto) {
        if (queue.isEmpty() || idx < 0) return;
        int at = idx + delta;
        if (at >= queue.size()) at = 0;
        if (at < 0) at = queue.size() - 1;
        idx = at;
        MusicLib.Track t = queue.get(idx);
        updateNotif(t.title, t.artist, true);
        new Thread(() -> prepare(t), "seagull-music-prep").start();
    }

    private void prepare(MusicLib.Track t) {
        releaseMp();
        MediaPlayer p = new MediaPlayer();
        instance = p;
        try {
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            p.setDataSource(t.path);
            p.setOnCompletionListener(m -> step(1, true));
            p.setOnErrorListener((m, what, extra) -> {
                android.util.Log.w(TAG, "播放失败 what=" + what + " " + t.path);
                return false;
            });
            p.prepare();        // 本地文件头，后台线程同步 prepare
            p.start();
            playing = true;
            ui.post(() -> publishState(t, p.getDuration(), true));
        } catch (Throwable e) {
            android.util.Log.w(TAG, "起播失败: " + e);
            playing = false;
            ui.post(() -> publishState(t, 0, false));
        }
    };

    private void pause() {
        try { if (instance != null) instance.pause(); } catch (Throwable ignore) {}
        playing = false;
        pushPlayback();
    }

    private void resume() {
        if (idx < 0 || idx >= queue.size()) return;
        try {
            if (instance == null) {
                new Thread(() -> prepare(queue.get(idx)), "seagull-music-prep").start();
            } else {
                instance.start();
            }
            playing = true;
        } catch (Throwable ignore) {}
        pushPlayback();
    }

    private void seekTo(int pos) {
        try { if (instance != null) instance.seekTo(pos); } catch (Throwable ignore) {}
        pushPlayback();
    }

    /* ---------------- MediaSession 发布 ---------------- */

    private final MediaSession.Callback cb = new MediaSession.Callback() {
        @Override public void onPlay() { resume(); }
        @Override public void onPause() { pause(); }
        @Override public void onSkipToNext() { step(1, false); }
        @Override public void onSkipToPrevious() { step(-1, false); }
        @Override public void onSeekTo(long pos) { seekTo((int) pos); }
    };

    /** 元数据只带标准键（框架 MediaMetadata 无自定义歌词键）；.lrc 由
     *  Lyrics 侧按 title/artist 找同名文件 + MusicLib.lrcFor 兜底（M4）。 */
    private void publishState(MusicLib.Track t, int durMs, boolean nowPlaying) {
        if (session == null) return;
        MediaMetadata mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durMs)
                .build();
        try { session.setMetadata(mb); } catch (Throwable ignore) {}
        PlaybackState pb = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO)
                .setState(nowPlaying && playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        position(), 1f)
                .build();
        try { session.setPlaybackState(pb); } catch (Throwable ignore) {}
        session.setActive(true);
    }

    private void pushPlayback() {
        if (idx < 0 || idx >= queue.size()) return;
        publishState(queue.get(idx), duration(), true);
        updateNotif(queue.get(idx).title, artist(), playing);
    }

    private String artist() {
        if (idx < 0 || idx >= queue.size()) return "";
        return queue.get(idx).artist;
    }

    /* ---------------- 前台通知 ---------------- */

    private Notification notif(String title, String artist, boolean on) {
        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        return build(b, title, artist, on);
    }

    private void updateNotif(String title, String artist, boolean on) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            nm.notify(0x5E71, build(new Notification.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true), title, artist, on));
        } catch (Throwable ignore) {}
    }

    private Notification build(Notification.Builder b, String title, String artist, boolean on) {
        b.setContentTitle(title == null || title.isEmpty() ? "本地音乐" : title)
                .setContentText(artist == null || artist.isEmpty() ? (on ? "播放中" : "已暂停") : artist)
                .setOngoing(true);
        return b.build();
    }

    private void releaseMp() {
        MediaPlayer p = instance;
        instance = null;
        if (p != null) {
            try { p.stop(); } catch (Throwable ignore) {}
            try { p.release(); } catch (Throwable ignore) {}
        }
    }
}
