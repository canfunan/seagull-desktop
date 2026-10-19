package com.seagull.carlauncher;

import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

/**
 * 动态壁纸层（批次 CI）：TextureView + MediaPlayer 无声循环播放，压在
 * HomeActivity root 的最底层（静态壁纸之上、暗度遮罩与三页之下）。
 *
 * 纪律：
 *  - 恒静音（setVolume 0,0）—— 车机声音归系统/应用管，壁纸绝不发声；
 *  - setLooping(true) 一直循环；线程里 prepare，卡顿不落在主线程；
 *  - Surface 销毁 / View 摘树 / Activity 暂停都 release 播放器，
 *    回前台由 HomeActivity.syncWallVideo() 重建 —— 播放器绝不跨句柄存活。
 */
public final class WallpaperVideoView extends FrameLayout
        implements TextureView.SurfaceTextureListener {

    private static final String TAG = "SeagullWallVideo";

    private final TextureView tv;
    private MediaPlayer player;
    private String path;
    private SurfaceTexture pendingSurface;

    public WallpaperVideoView(Context c, String videoPath) {
        super(c);
        path = videoPath;
        tv = new TextureView(c);
        tv.setSurfaceTextureListener(this);
        addView(tv, new LayoutParams(-1, -1, Gravity.CENTER));
        setTag("wallVideo");
    }

    /** 路径变了就换源（同路径跳过，避免循环里反复重建播放器）。 */
    public void setPath(String p) {
        if (p == null) p = "";
        if (p.equals(path) && player != null) return;
        releasePlayer();
        path = p;
        pendingSurface = null;
        if (tv.isAvailable()) {
            startPlayer(tv.getSurfaceTexture());
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        startPlayer(st);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        releasePlayer();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    private void startPlayer(SurfaceTexture st) {
        if (path == null || path.isEmpty()) return;
        releasePlayer();
        pendingSurface = st;
        new Thread(() -> {
            try {
                MediaPlayer p = new MediaPlayer();
                if (path.startsWith("content:")) {
                    // K1 天气联动：壁纸包走 SAF，播放源是 content Uri
                    p.setDataSource(getContext(), Uri.parse(path));
                } else {
                    p.setDataSource(path);
                }
                p.setSurface(new Surface(st));
                p.setLooping(true);
                p.setVolume(0f, 0f);           // 恒静音：壁纸不抢车机声音
                p.setAudioStreamType(AudioManager.STREAM_MUSIC);
                p.prepare();
                synchronized (this) {
                    if (pendingSurface != st) { p.release(); return; }
                    player = p;
                }
                ui(p);
            } catch (Throwable t) {
                android.util.Log.w(TAG, "动态壁纸起播失败: " + t);
            }
        }, "wall-video").start();
    }

    private void ui(MediaPlayer p) {
        post(() -> { try { p.start(); } catch (Throwable ignore) {} });
    }

    /** 停播但保留路径（Activity onPause 用，onResume 里 syncWallVideo 复播）。 */
    public void pauseForBackground() {
        releasePlayer();
    }

    private synchronized void releasePlayer() {
        pendingSurface = null;
        if (player != null) {
            try { player.release(); } catch (Throwable ignore) {}
            player = null;
        }
    }

    @Override protected void onDetachedFromWindow() {
        releasePlayer();
        super.onDetachedFromWindow();
    }
}
