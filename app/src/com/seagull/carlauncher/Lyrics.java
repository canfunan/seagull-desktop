package com.seagull.carlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 歌词 —— 媒体会话取当前歌（MediaSessionManager 读别的应用的会话在 API21+ 无需权限，
 * 车机蓝牙播歌也走这条路，见 10.13）。
 *
 * 歌词文本三个来源（LauncherModel.LyricSource）：
 *   PLAYER 播放器自带：会话 metadata 里带 lyrics 字段的播放器（网易云/QQ 偶尔会塞）
 *           —— 拿不到就退化到在线（否则大部分播放器这一项永远是空的）
 *   LOCAL  本地 .lrc：在 Music 目录里找「歌手 - 歌名.lrc」
 *   ONLINE 在线（酷狗）：公开接口，免 key，先搜候选再按 id 下载 base64 的 lrc
 *
 * 一秒一跳：窗口行、状态栏通知、组件条/菜园都从同一份状态读（10.15 天然同步）。
 * 缓存：filesDir/lyrics/<key>.lrc。
 */
public final class Lyrics {

    private static final String TAG = "SeagullLyric";
    private static final String CH = "seagull_lyric";
    private static final int NOTI_ID = 0x4C59;
    private static final String KUGOU_SEARCH =
            "https://krcs.kugou.com/search?ver=1&man=yes&client=pc&keyword=%s&duration=%d";
    private static final String KUGOU_DL =
            "https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1"
            + "&id=%s&accesskey=%s&duration=%d";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final CopyOnWriteArrayList<Sink> SINKS = new CopyOnWriteArrayList<>();

    private static boolean running = false;
    private static Context appCtx;
    private static String title = "", artist = "", key = "";
    private static long pos = 0, duration = 0, lastTick = 0;
    private static boolean playing = false;
    private static Lrc cur_lyric = Lrc.EMPTY;
    private static int cur = -1;
    private static final List<String[]> CANDS = new ArrayList<>();   // 酷狗候选 {id, accesskey}
    private static int ver = 0;
    private static String lastPosted = "";
    /** 播放中每隔这么多跳用控制器快照重新对一次时（墙钟累加会漂移） */
    private static int anchorTick = 0;

    private Lyrics() {}

    /* ==================== R3 落雪直连（CARLINK） ==================== */

    /** 直连最新状态；ts=0 表示从未收到。广播频率=行变化，无轮询。 */
    private static final class Direct {
        volatile String line = "";
        volatile String lineT = "";
        volatile String cover = "";
        volatile long ts;
    }
    private static final Direct DIRECT = new Direct();
    private static android.content.BroadcastReceiver linkRx;

    private static void registerLink(Context app) {
        if (linkRx != null) return;
        linkRx = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, android.content.Intent i) {
                try {
                    DIRECT.line = i.getStringExtra(CarLink.EXTRA_LINE);
                    if (DIRECT.line == null) DIRECT.line = "";
                    String lt = i.getStringExtra(CarLink.EXTRA_LINE_T);
                    DIRECT.lineT = lt == null ? "" : lt;
                    String cv = i.getStringExtra(CarLink.EXTRA_COVER);
                    if (cv != null && !cv.isEmpty()) DIRECT.cover = cv;
                    DIRECT.ts = System.currentTimeMillis();
                } catch (Throwable t) {
                    Log.w(TAG, "直连广播解析失败", t);
                }
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter(CarLink.ACTION);
        try {
            app.registerReceiver(linkRx, f);
        } catch (Throwable t) {
            Log.w(TAG, "直连接收器注册失败", t);
            linkRx = null;
        }
    }

    /** 直连封面（新鲜时可用；调用方 CoverCache.fromUri 解码）。 */
    public static String directCover() { return DIRECT.cover; }

    /** 直连是否新鲜（UI 层问询封面/覆盖有效性）。 */
    public static boolean directLive() { return CarLink.fresh(DIRECT.ts, System.currentTimeMillis()); }

    /** 歌词展示方（组件条、菜园、媒体卡片）实现它，每跳收到一次当前窗口。 */
    public interface Sink { void onLyric(String[] window, String title, String artist); }

    public static void addSink(Sink s) { SINKS.addIfAbsent(s); }

    public static void removeSink(Sink s) { SINKS.remove(s); }

    /** 桌面 onResume 调；onPause 可以不管（1s 一跳开销可忽略）。 */
    public static void start(Context ctx) {
        if (ctx != null) {
            appCtx = ctx.getApplicationContext();
            registerLink(appCtx);   // R3：直连广播随管线启停注册一次
        }
        if (running) return;
        running = true;
        lastTick = System.currentTimeMillis();
        MAIN.postDelayed(TICK, 1000L);
    }

    public static void stop() {
        running = false;
        MAIN.removeCallbacks(TICK);
    }

    /** 给任意展示方取当前窗口（0=自动行数）。10.15 卡片与菜园共用这一个出口。 */
    public static String[] window(Context ctx, LauncherModel m, int wantLines) {
        int n = wantLines > 0 ? wantLines : linesOf(m, ctx);
        // R3 直连行级覆盖：开关开+广播新鲜才生效，其余原管线（单点出口，
        // push 的 sink 广播与时钟块等外部直调者全部自动吃到）
        boolean fresh = CarLink.fresh(DIRECT.ts, System.currentTimeMillis());
        if (m != null && m.lxLinkEnabled) {
            return CarLink.override(pipeline(n), n,
                    DIRECT.line, DIRECT.lineT, true, fresh);
        }
        return pipeline(n);
    }

    /** 原管线窗口（直接从当前 lrc 状态切 n 行，中心对齐当前句）。 */
    private static String[] pipeline(int n) {
        String[] src = cur_lyric.lines;
        if (src.length == 0) return new String[0];
        String[] out = new String[n];
        int start = Math.max(0, Math.min(Math.max(0, cur) - (n - 1) / 2, src.length - n));
        for (int i = 0; i < n; i++) {
            int idx = start + i;
            out[i] = idx < src.length ? src[idx] : "";
        }
        return out;
    }

    /** 0=自动：横屏 5 句、竖屏 3 句。 */
    public static int linesOf(LauncherModel m, Context ctx) {
        if (m == null) return 3;
        if (m.lyricLines > 0) return m.lyricLines;
        return portrait(ctx) ? 3 : 5;
    }

    private static boolean portrait(Context ctx) {
        return ctx.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT;
    }

    public static int sizeOf(LauncherModel m) {
        return m == null ? 100 : m.lyricSize;
    }

    public static String nowPlaying() {
        return title.isEmpty() ? "" : (artist.isEmpty() ? title : artist + " - " + title);
    }

    /** 当前歌的 key（歌手|歌名，小写）——「这首不显示」用它存进 lyricHidden。 */
    public static String currentKey() { return key; }

    /** 10.10 这首歌词不对（换版本）：在酷狗候选里轮换，取不到就重新搜。 */
    public static void nextVersion(final Context ctx) {
        if (CANDS.size() > 1) {
            ver = (ver + 1) % CANDS.size();
            fetchOnline(ctx, key, title, artist, ver);
            return;
        }
        title = "";                       // 清一下，下面 tick 会重新搜
        cur_lyric = Lrc.EMPTY;
    }

    /** 10.8 歌词缓存清空。 */
    public static int clearCache(Context ctx) {
        File dir = cacheDir(ctx);
        File[] fs = dir.listFiles();
        int n = 0;
        if (fs != null) for (File f : fs) if (f.delete()) n++;
        cur_lyric = Lrc.EMPTY;
        return n;
    }

    /* ==================== 每秒一跳 ==================== */

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            if (!running) return;
            MAIN.postDelayed(TICK, 1000L);
            try { step(); } catch (Throwable t) { Log.w(TAG, "跳一拍失败", t); }
        }
    };

    private static void step() {
        Context ctx = appCtx;
        if (ctx == null) return;
        long now = System.currentTimeMillis();
        MediaController ctrl = activeController(ctx);
        String t = "", a = "";
        boolean play = false;
        if (ctrl != null) {
            MediaMetadata md = ctrl.getMetadata();
            if (md != null) {
                t = str(md, MediaMetadata.METADATA_KEY_TITLE);
                a = str(md, MediaMetadata.METADATA_KEY_ARTIST);
                duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);   // getDuration() 是 @hide
            }
            PlaybackState ps = ctrl.getPlaybackState();
            play = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
            playing = play;
            if (play) {
                // getPosition() 是快照，不会自己走，所以自己按墙钟累加；
                // 但 postDelayed 的真实间隔与墙钟有误差，长播会漂移出秒级。
                // 每 15 秒（15 跳）用控制器快照重新锚一次，drift 收敛回百毫秒内。
                pos += Math.max(0, now - lastTick);
                if (++anchorTick >= 15 && ps != null && ps.getLastPositionUpdateTime() > 0) {
                    pos = Math.max(0, ps.getPosition()
                            + (now - ps.getLastPositionUpdateTime()));
                    anchorTick = 0;
                }
            } else {
                anchorTick = 0;
                if (ps != null) {
                    pos = Math.max(0, ps.getPosition());
                }
            }
        }
        lastTick = now;

        LauncherModel m = cfg(ctx);   // 审计 M3：配置快照缓存，配置没变不重建
        String k = (a + "|" + t).toLowerCase(Locale.ROOT);
        if (!t.equals(title) || !a.equals(artist)) {
            title = t; artist = a; key = k;
            cur = -1; CANDS.clear(); ver = 0;
            cur_lyric = Lrc.EMPTY;
            lookup(ctx, k);
        }
        advance(m);
        push(ctx, m);
    }

    /**
     * 审计 M3：歌词相关配置的模型快照。旧实现 step 每秒整份 new LauncherModel
     * （全量 SP+JSON 解析）。设置页写歌词字段时 LauncherModel.bumpCfg()，
     * 这里按代际号决定是否重读。
     */
    private static LauncherModel cfgM;
    private static int cfgVer = -1;

    private static LauncherModel cfg(Context ctx) {
        int v = LauncherModel.cfgVersion();
        LauncherModel m = cfgM;
        if (m == null || v != cfgVer) {
            m = new LauncherModel(ctx, false);
            cfgM = m;
            cfgVer = v;
        }
        return m;
    }

    private static void advance(LauncherModel m) {
        long[] t = cur_lyric.times;
        if (t.length == 0) return;
        cur = Lrc.indexAt(t, pos + m.lyricOffsetMs);   // 对时偏移在这里一次生效
    }

    private static void push(Context ctx, LauncherModel m) {
        if (!key.isEmpty() && m.lyricHidden.contains(key)) return;   // 10.9 这首不显示
        String[] w = window(ctx, m, m.lyricLines);
        for (Sink s : SINKS) s.onLyric(w, title, artist);
        if (m.lyricStatusBar || m.lyricNotification) notifyLine(ctx, w, m);
    }

    /* ==================== 取歌词 ==================== */

    private static void lookup(final Context ctx, final String k) {
        final LauncherModel m = cfg(ctx);   // 审计 M3：同走配置快照
        final String t = title, a = artist;
        POOL.execute(new Runnable() {
            @Override public void run() {
                try {
                    String lrc = null;
                    if (m.lyricSource == LauncherModel.LyricSource.PLAYER) {
                        lrc = fromSession(ctx);
                        if (lrc == null) lrc = cache(ctx, k);
                    } else if (m.lyricSource == LauncherModel.LyricSource.LOCAL) {
                        lrc = localFile(t, a);
                    } else {
                        lrc = cache(ctx, k);
                    }
                    // M4 本地播放器兜底：扫描缓存里同曲的同目录 .lrc（U盘/root 来源
                    // 不在 Music 目录约定里，localFile 摸不到）
                    if (lrc == null || lrc.trim().isEmpty()) lrc = MusicLib.lrcFor(t, a);
                    if (lrc == null || lrc.trim().isEmpty()) lrc = online(ctx, k, t, a);
                    if (lrc == null || lrc.trim().isEmpty()) lrc = fromSession(ctx);
                    final String text = lrc;
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            cur_lyric = text == null ? Lrc.EMPTY : Lrc.parse(text);
                            cur = -1;
                            advance(cfg(ctx));   // 审计 M3
                        }
                    });
                } catch (Throwable e) {
                    Log.w(TAG, "查歌词失败", e);
                }
            }
        });
    }

    /**
     * PLAYER 来源：播放器自己给的歌词。
     * MediaMetadata.getBundle() 是 @hide，公开 API 读不到任意键，
     * 所以只扫 description.extras（少数播放器会把歌词塞这里）——
     * 扫不到就走在线，这是唯一能拿到歌词的路（ponytail: 等系统开放 metadata 读取再加真·播放器自带）。
     */
    private static String fromSession(Context ctx) {
        MediaController ctrl = activeController(ctx);
        if (ctrl == null || ctrl.getMetadata() == null) return null;
        MediaDescription d = ctrl.getMetadata().getDescription();
        android.os.Bundle b = d == null ? null : d.getExtras();
        if (b == null) return null;
        for (String k : b.keySet()) {
            if (!k.toLowerCase(Locale.ROOT).contains("lyric")) continue;
            Object v = b.get(k);
            if (v instanceof String && !((String) v).trim().isEmpty()) return (String) v;
        }
        return null;
    }

    private static String str(MediaMetadata md, String key) {
        CharSequence c = md.getText(key);
        return c == null ? "" : c.toString().trim();
    }

    /** LOCAL：在 Music 目录里找同名 lrc。 */
    private static String localFile(String t, String a) {
        if (t.isEmpty()) return null;
        // 审计 L4：用标准 API 拿公开音乐目录；/sdcard 与 /storage/emulated/0
        // 是同一份文件系统（符号链接），旧写法把同一目录原样扫两遍
        java.util.LinkedHashSet<String> roots = new java.util.LinkedHashSet<>();
        try {
            File m = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MUSIC);
            if (m != null) roots.add(m.getAbsolutePath());
        } catch (Throwable ignore) {}
        roots.add("/storage/emulated/0/Music");
        roots.add(System.getProperty("user.home") + "/Music");
        for (String root : roots) {
            File dir = new File(root);
            if (!dir.isDirectory()) continue;
            File hit = find(dir, t, a, 0);
            if (hit != null) return read(hit);
        }
        return null;
    }

    private static File find(File dir, String t, String a, int depth) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.isDirectory() && depth < 2) {
                File hit = find(f, t, a, depth + 1);
                if (hit != null) return hit;
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".lrc")) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (n.contains(t.toLowerCase(Locale.ROOT))
                        || (a.isEmpty() && n.contains(f.getParentFile().getName().toLowerCase(Locale.ROOT)))) {
                    return f;
                }
            }
        }
        return null;
    }

    /** ONLINE：先搜候选（顺便存下来给「换版本」用），再按 id 下载 base64 内容。 */
    private static String online(Context ctx, String k, String t, String a) {
        try {
            String hit = cache(ctx, k);
            if (hit != null) return hit;
            if (t.isEmpty()) return null;
            String word = URLEncoder.encode(a.isEmpty() ? t : a + " " + t, "UTF-8");
            JSONObject r = new JSONObject(http(String.format(Locale.US, KUGOU_SEARCH, word,
                    Math.max(0, duration))));
            JSONArray cs = r.optJSONArray("candidates");
            CANDS.clear();
            if (cs != null) {
                for (int i = 0; i < cs.length(); i++) {
                    JSONObject c = cs.optJSONObject(i);
                    if (c == null) continue;
                    CANDS.add(new String[]{c.optString("id"), c.optString("accesskey")});
                }
            }
            if (CANDS.isEmpty()) return null;
            return download(ctx, CANDS.get(Math.min(ver, CANDS.size() - 1)), k);
        } catch (Throwable e) {
            Log.w(TAG, "在线歌词失败", e);
            return null;
        }
    }

    private static void fetchOnline(Context ctx, String k, String t, String a, int v) {
        POOL.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (v < CANDS.size()) {
                        String text = download(ctx, CANDS.get(v), k);
                        final Lrc lrc = text == null ? Lrc.EMPTY : Lrc.parse(text);
                        MAIN.post(new Runnable() {
                            @Override public void run() {
                                cur_lyric = lrc;
                                cur = -1;
                            }
                        });
                        return;
                    }
                    online(ctx, k, t, a);
                } catch (Throwable e) {
                    Log.w(TAG, "换版本失败", e);
                }
            }
        });
    }

    private static String download(Context ctx, String[] cand, String k) throws Exception {
        if (cand == null) return null;
        String url = String.format(Locale.US, KUGOU_DL, cand[0], cand[1], Math.max(0, duration));
        JSONObject r = new JSONObject(http(url));
        if (r.optInt("status") != 1) return null;
        String content = r.optString("content");
        if (content.isEmpty()) return null;
        String lrc = new String(Base64.decode(content, Base64.DEFAULT), "UTF-8");
        writeCache(ctx, k, lrc);
        return lrc;
    }

    /* ==================== 缓存 ==================== */

    private static File cacheDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "lyrics");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static File cacheFile(Context ctx, String k) {
        return new File(cacheDir(ctx), Integer.toHexString(k.hashCode()) + ".lrc");
    }

    private static String cache(Context ctx, String k) {
        if (k.isEmpty()) return null;
        return read(cacheFile(ctx, k));
    }

    private static void writeCache(Context ctx, String k, String lrc) {
        try (FileOutputStream out = new FileOutputStream(cacheFile(ctx, k))) {
            out.write(lrc.getBytes("UTF-8"));
        } catch (Throwable ignore) {}
    }

    private static String read(File f) {
        if (f == null || !f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /* ==================== 通知（10.11 / 10.12） ==================== */

    private static void notifyLine(Context ctx, String[] w, LauncherModel m) {
        String line = "";
        for (String s : w) if (s != null && !s.trim().isEmpty()) { line = s.trim(); break; }
        if (line.equals(lastPosted)) return;                 // 同一句不重复发通知
        lastPosted = line;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                if (nm.getNotificationChannel(CH) == null) {
                    nm.createNotificationChannel(new NotificationChannel(CH, "歌词",
                            NotificationManager.IMPORTANCE_MIN));
                }
                Notification n = new Notification.Builder(ctx, CH)
                        .setContentTitle(nowPlaying())
                        .setContentText(line)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setOngoing(true)
                        .setShowWhen(false)
                        .build();
                nm.notify(NOTI_ID, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "发歌词通知失败", t);
        }
    }

    /* ==================== 杂项 ==================== */

    /** O2 开放给 MediaKeyRouter/音乐块复用：本地播放中会话优先，否则最近活跃。 */
    public static MediaController activeController(Context ctx) {
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;
            // 必须传本通知监听服务的 ComponentName：传 null 无
            // MEDIA_CONTENT_CONTROL 权限时直接 SecurityException（被 catch 吞成无会话，
            // 歌词整条管线静默失效），与 MediaListenerService.refresh 同口径
            List<MediaController> list = msm.getActiveSessions(
                    new android.content.ComponentName(ctx, MediaListenerService.class));
            for (MediaController c : list) {
                PlaybackState ps = c.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) return c;
            }
            // 没有在播放的，就取第一个带歌名的会话（暂停时也显示歌词）
            for (MediaController c : list) {
                MediaMetadata md = c.getMetadata();
                if (md != null && md.getText(MediaMetadata.METADATA_KEY_TITLE) != null) return c;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String http(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                // 回包封顶 1MB：歌词接口异常/被劫持时不会把内存吃爆
                if (bo.size() > 1024 * 1024) throw new IllegalStateException("回包过大");
            }
            String s = bo.toString("UTF-8");
            if (code >= 400) throw new IllegalStateException("HTTP " + code);
            return s;
        } finally {
            try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    /** 当前行（媒体卡片用）。 */
    public static String current() {
        String[] src = cur_lyric.lines;
        return cur >= 0 && cur < src.length ? src[cur] : "";
    }

    public static boolean playing() { return playing; }
}
