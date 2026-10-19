package com.seagull.carlauncher;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * O2 专辑封面缓存。键=title|artist；LRU 容量 2（当前曲+预解码余量）。
 * 位图直取（本地 MusicService 已把内嵌封面塞进 metadata）；URI 走后台单线程
 * 解码（蓝牙播放器的封面多为 content:// 或 http(s)），完成即入缓存，UI 下一秒
 * tick 自取（与歌词管线同节奏，零回调风暴）。同键失败 60s 负缓存防刷。
 */
public final class CoverCache {
    private static final String TAG = "CoverCache";
    private static final long NEG_MS = 60_000L;
    private static final int MAX_PX = 256;

    private static final ExecutorService EX = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "seagull-cover");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(2);
    private static final Map<String, Long> NEG = new HashMap<>();
    private static android.content.Context sCtx;

    /** MusicService.onCreate 喂 app context（项目惯例：各自持有，不引全局单例）。 */
    public static void init(android.content.Context c) {
        if (c != null) sCtx = c.getApplicationContext();
    }

    private CoverCache() { }

    /**
     * 取封面；命中返回位图，未命中返回 null 并异步解码（同键去重）。
     * 本地路径走 MusicService.currentArt()，别进这里。
     */
    public static Bitmap get(String title, String artist, MediaMetadata md) {
        if (md == null) return null;
        final String key = (title == null ? "" : title) + "|" + (artist == null ? "" : artist);
        Bitmap hit = CACHE.get(key);
        if (hit != null) return hit;
        Long neg = NEG.get(key);
        if (neg != null && System.currentTimeMillis() - neg < NEG_MS) return null;

        Bitmap direct = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (direct != null) {
            Bitmap s = sample(direct);
            if (s != null) { CACHE.put(key, s); return s; }
        }
        final String uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        if (uri == null || uri.isEmpty()) return null;
        EX.execute(() -> {
            try {
                ContentResolver cr = sCtx.getContentResolver();
                InputStream in = cr.openInputStream(Uri.parse(uri));
                Bitmap b = null;
                if (in != null) {
                    try { b = sample(BitmapFactory.decodeStream(in)); }
                    finally { try { in.close(); } catch (Throwable ignore) { } }
                }
                if (b != null) CACHE.put(key, b);
                else NEG.put(key, System.currentTimeMillis());
            } catch (Throwable t) {
                NEG.put(key, System.currentTimeMillis());
                RootLog.w(TAG, "封面解码失败 " + key, t);
            }
        });
        return null;
    }

    /** 边长超 MAX_PX 等比采样，OOM 防护；null/失败返回 null。 */
    private static Bitmap sample(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return null;
        int m = Math.max(w, h);
        if (m <= MAX_PX) return src;
        float f = MAX_PX / (float) m;
        try {
            return Bitmap.createScaledBitmap(src,
                    Math.max(1, (int) (w * f)), Math.max(1, (int) (h * f)), true);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 主题切换/内存回收时清负缓存即可，LRU 自己淘汰。 */
    public static void clearNegative() { NEG.clear(); }
}
