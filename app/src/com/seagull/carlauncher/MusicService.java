package com.seagull.carlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
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

    /** O2 内嵌封面缓存：路径→位图，4 张 LRU。 */
    private static final android.util.LruCache<String, Bitmap> ART =
            new android.util.LruCache<String, Bitmap>(4);

    /** 当前曲封面（无当前曲/未提取返回 null）；音乐块与迷你播放器共用。 */
    public static Bitmap currentArt() {
        List<MusicLib.Track> q = queue;
        int i = idx;
        if (i < 0 || i >= q.size()) return null;
        return ART.get(q.get(i).path);
    }

    /** 提取 ID3/容器内嵌封面，采样到 256px 内（OOM 防护）；无图返回 null。 */
    private static Bitmap extractArt(String path) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            byte[] raw = mmr.getEmbeddedPicture();
            if (raw == null || raw.length == 0) return null;
            Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (src == null) return null;
            int m = Math.max(src.getWidth(), src.getHeight());
            if (m <= 256) return src;
            float f = 256f / m;
            return Bitmap.createScaledBitmap(src,
                    Math.max(1, (int) (src.getWidth() * f)),
                    Math.max(1, (int) (src.getHeight() * f)), true);
        } catch (Throwable e) {
            return null;
        } finally {
            try { mmr.release(); } catch (Throwable ignore) { }
        }
    }
    public static boolean isPlaying() { return playing; }

    /* ---------------- 静态控制口（UI 直接调） ---------------- */

    /** 置队列并从 at 开始播（M3 点歌入口）。 */
    public static void play(Context c, List<MusicLib.Track> list, int at) {
        queue.clear();
        queue.addAll(list);
        persistQueue(c.getApplicationContext(), at, 0);
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

    /* ---------------- 断点落盘/恢复（BACKLOG #5：重启后播放列表与进度保留） ---------------- */

    /** 换队列/切歌时把队列+曲序落档；断点秒数随 pause/销毁另存。 */
    private static void persistQueue(Context app, int at, int posSec) {
        try {
            LauncherModel m = new LauncherModel(app, false);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (MusicLib.Track t : queue) arr.put(t.path);
            m.musicQueueJson = arr.toString();
            m.musicIdx = at;
            m.musicPos = posSec;
            m.save();
        } catch (Throwable ignore) { }
    }

    /** 断点秒数落档（暂停/切歌/销毁时调）。构造器已 load()，只动 idx/pos 两个字段。 */
    private static void persistPos(Context app, int at) {
        try {
            LauncherModel m = new LauncherModel(app, false);
            m.musicIdx = at;
            m.musicPos = position() / 1000;
            m.save();
        } catch (Throwable ignore) { }
    }

    /**
     * 服务/列表页冷启时对档：按存档路径对 MusicLib 扫描结果重挂队列。
     * 路径已不存在的曲目跳过；一条都对不上返回 false（U 盘拔了就别硬撑）。
     * 返回 true 时 idx 已就位、pendingSeekMs 带着断点秒数等下一次 prepare。
     */
    private static volatile int pendingSeekMs = -1;

    public static boolean tryRestore(Context c) {
        if (!queue.isEmpty()) return true;
        try {
            LauncherModel m = new LauncherModel(c.getApplicationContext(), false);
            if (m.musicQueueJson == null || m.musicQueueJson.isEmpty() || m.musicIdx < 0) return false;
            org.json.JSONArray arr = new org.json.JSONArray(m.musicQueueJson);
            java.util.Map<String, MusicLib.Track> byPath = new java.util.HashMap<>();
            for (MusicLib.Track t : MusicLib.last()) byPath.put(t.path, t);
            queue.clear();
            for (int i = 0; i < arr.length(); i++) {
                MusicLib.Track t = byPath.get(arr.optString(i));
                if (t != null) queue.add(t);
            }
            if (queue.isEmpty()) return false;
            int at = Math.min(m.musicIdx, queue.size() - 1);
            idx = Math.max(0, at);
            pendingSeekMs = Math.max(0, m.musicPos) * 1000;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 切歌/续播 prepare 串行化：快速连点 next/prev 时两条线程交错会互踩静态
     *  instance（release 掉对方刚赋值的播放器 → 双路出声/泄漏/状态污染）。 */
    private static final java.util.concurrent.ExecutorService PREP =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** 抢焦点让行：导航播报/来电/其他媒体拿到焦点时本地音乐暂停。 */
    private final AudioManager.OnAudioFocusChangeListener focusL = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            ui.post(this::pause);
        }
    };

    /* ---------------- 生命周期 ---------------- */

    @Override public void onCreate() {
        super.onCreate();
        CoverCache.init(this);   // O2 封面管线 app context
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
                    .setOnAudioFocusChangeListener(focusL, new Handler(Looper.getMainLooper()))
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
            case "seek":
                seekTo(intent.getIntExtra("pos", 0));
                if (idx >= 0 && idx < queue.size()) persistPos(this, idx);
                break;
            default: break;
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        playing = false;
        if (idx >= 0 && idx < queue.size()) persistPos(this, idx);   // 断点落档再走
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
        persistQueue(this, at, 0);   // 断点曲序落档（新曲从头计）
        MusicLib.Track t = queue.get(idx);
        updateNotif(t.title, t.artist, true);
        final MusicLib.Track ft = t;
        PREP.execute(() -> prepare(ft));
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
            // 冷启恢复的断点秒数：起播后一次跳到位（只在恢复路径非负）
            int seek = pendingSeekMs;
            if (seek >= 0) {
                pendingSeekMs = -1;
                try { p.seekTo(seek); } catch (Throwable ignore) {}
            }
            playing = true;
            // O2 内嵌封面懒提取：首次播放该曲时提取一次入 LRU（PREP 线程，别挡 UI）
            Bitmap art = ART.get(t.path);
            if (art == null) {
                art = extractArt(t.path);
                if (art != null) ART.put(t.path, art);
            }
            ui.post(() -> publishState(t, p.getDuration(), true));
        } catch (Throwable e) {
            android.util.Log.w(TAG, "起播失败: " + e);
            playing = false;
            ui.post(() -> {
                publishState(t, 0, false);
                // 失败态不占媒体会话：免得把其他已暂停的真实播放器顶下去
                try { if (session != null) session.setActive(false); } catch (Throwable ignore) {}
            });
        }
    };

    private void pause() {
        try { if (instance != null) instance.pause(); } catch (Throwable ignore) {}
        playing = false;
        if (idx >= 0 && idx < queue.size()) persistPos(this, idx);   // 断点秒数落档
        pushPlayback();
    }

    private void resume() {
        // 冷启直接按播放键（MiniPlayer/方向盘）也能接上存档：扫描结果就位才挂得上
        if (queue.isEmpty() && !tryRestore(this)) return;
        if (idx < 0 || idx >= queue.size()) return;
        try {
            if (instance == null) {
                final MusicLib.Track t = queue.get(idx);
                PREP.execute(() -> prepare(t));
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
     *  Lyrics 侧按 title/artist 找同名文件 + MusicLib.lrcFor 兜底（M4）。
     *  O2：内嵌封面已提取则一并发布，蓝牙歌词/方控链路的读取方白拿。 */
    private void publishState(MusicLib.Track t, int durMs, boolean nowPlaying) {
        if (session == null) return;
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durMs);
        Bitmap art = ART.get(t.path);
        if (art != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        MediaMetadata mb = b.build();
        try { session.setMetadata(mb); } catch (Throwable ignore) {}
        PlaybackState pb = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO)
                .setState(nowPlaying && playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        position(), nowPlaying && playing ? 1f : 0f)
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
