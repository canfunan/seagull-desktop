package com.seagull.carlauncher;

import android.content.Context;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * M1 本地音乐扫描：三个来源按序合并去重。
 *
 *   1. /sdcard/Music 公开音乐目录 —— File API 直读（与 Lyrics.localFile 同一
 *      前例，车机 ROM 实测放行）；含一级/二级子目录；
 *   2. U 盘等可移动卷 —— StorageManager.getStorageVolumes() 里 isRemovable
 *      且 mounted 的卷，先走 getDirectory()（API 30+），不行再 root find
 *      /mnt/media_rw 兜底（本项目 Caps.exec 现成）；
 *   3. root find 兜底本身兜住所有怪挂载点。
 *
 * 元数据不解析音频帧（MediaMetadataRetriever 逐个开太慢），文件名约定
 * 「歌手 - 歌名.ext」优先，否则整个文件名当歌名 —— 与 Lyrics 找 .lrc 的
 * 「歌手 - 歌名.lrc」约定对齐。同目录同名 .lrc 记进 track.lrcPath（M4 用）。
 *
 * 扫描在后台线程，结果回主线程；上限 2000 首防 U 盘大库把内存吃穿。
 */
public final class MusicLib {

    public static final class Track {
        public String path = "";
        public String title = "";
        public String artist = "";
        public String lrcPath = "";     // 同目录同名 .lrc；空 = 没找到
    }

    private static final String[] AUDIO_EXT = {
            ".mp3", ".flac", ".wav", ".m4a", ".ogg", ".aac", ".wma", ".ape",
    };
    private static final int MAX_TRACKS = 2000;
    private static final int MAX_DEPTH = 3;

    /** 最近一次扫描结果（内存缓存，MusicActivity/桌面块共用）。 */
    private static volatile List<Track> last = new ArrayList<>();

    public static List<Track> last() {
        List<Track> l = last;
        return l == null ? new ArrayList<>() : l;
    }

    private MusicLib() {}

    /** 后台扫描；done 回主线程。扫描中重复调用直接并掉（都有各自回调）。 */
    public static void scan(Context c, java.util.function.Consumer<List<Track>> done) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            List<Track> out = new ArrayList<>();
            for (File root : publicRoots()) walk(root, 0, seen, out);
            for (File root : removableRoots(app, seen, out)) walk(root, 0, seen, out);
            rootFill(seen, out);
            last = out;
            final List<Track> frozen = out;
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.post(() -> done.accept(frozen));
        }, "seagull-music-scan").start();
    }

    /* ---------------- 来源 1：公开音乐目录 ---------------- */

    private static List<File> publicRoots() {
        List<File> out = new ArrayList<>();
        try {
            File m = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MUSIC);
            if (m != null) out.add(m);
        } catch (Throwable ignore) { }
        out.add(new File("/storage/emulated/0/Music"));
        return out;
    }

    /* ---------------- 来源 2：可移动卷（U盘/SD卡） ---------------- */

    /** getDirectory() 可读的卷进返回值走 File 遍历；不可读的卷当场 root find 塞进 out。 */
    private static List<File> removableRoots(Context c, Set<String> seen, List<Track> out) {
        List<File> files = new ArrayList<>();
        try {
            StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
            if (sm == null) return files;
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (v == null || !v.isRemovable()) continue;
                String state = v.getState();     // mounted / unmounted / checking…
                if (state == null || !state.equals("mounted")) continue;
                try {
                    // API 30+ 公开方法；29 上反射失败会进 catch 走 root 兜底
                    File dir = v.getDirectory();
                    if (dir != null && dir.isDirectory()) { files.add(dir); continue; }
                } catch (Throwable ignore) { }
                String uuid = v.getUuid();
                if (uuid != null) rootFind("/mnt/media_rw/" + uuid, seen, out);
            }
        } catch (Throwable t) {
            android.util.Log.w("SeagullMusic", "列可移动卷失败: " + t);
        }
        return files;
    }

    /* ---------------- 来源 3：root find 兜底 ---------------- */

    private static void rootFill(Set<String> seen, List<Track> out) {
        rootFind("/mnt/media_rw /storage", seen, out);
    }

    /** findPath 可为多根（空格分隔）；逐行收绝对路径，seen 去重。 */
    private static void rootFind(String findPath, Set<String> seen, List<Track> out) {
        String r = Caps.exec("find " + findPath + " -maxdepth 4 -type f \\( "
                + extNameExpr() + " \\) 2>/dev/null");
        if (r == null || r.trim().isEmpty()) return;
        for (String line : r.split("\n")) {
            String p = line.trim();
            if (p.isEmpty() || seen.contains(p)) continue;
            addTrack(p, seen, out);
        }
    }

    private static String extNameExpr() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AUDIO_EXT.length; i++) {
            if (i > 0) sb.append(" -o ");
            sb.append("-iname '*").append(AUDIO_EXT[i]).append("'");
        }
        return sb.toString();
    }

    /* ---------------- 遍历与命名约定 ---------------- */

    private static void walk(File dir, int depth, Set<String> seen, List<Track> out) {
        if (depth > MAX_DEPTH || out.size() >= MAX_TRACKS) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : fs) {
            if (out.size() >= MAX_TRACKS) return;
            if (f.isDirectory()) { walk(f, depth + 1, seen, out); continue; }
            if (!isAudio(f.getName())) continue;
            addTrack(f.getAbsolutePath(), seen, out);
        }
    }

    private static boolean isAudio(String name) {
        String low = name.toLowerCase(Locale.US);
        for (String ext : AUDIO_EXT) if (low.endsWith(ext)) return true;
        return false;
    }

    private static void addTrack(String path, Set<String> seen, List<Track> out) {
        if (seen.contains(path) || out.size() >= MAX_TRACKS) return;
        seen.add(path);
        Track t = new Track();
        t.path = path;
        File f = new File(path);
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        int sep = name.indexOf(" - ");
        if (sep > 0 && sep < name.length() - 3) {
            t.artist = name.substring(0, sep).trim();
            t.title = name.substring(sep + 3).trim();
        } else {
            t.title = name.trim();
        }
        // M4：同目录同名 .lrc 就记下，播放时塞进 MediaSession 元数据
        File lrc = new File(f.getParentFile(), f.getName().replaceAll("\\.[^.]+$", "") + ".lrc");
        if (lrc.isFile()) t.lrcPath = lrc.getAbsolutePath();
        out.add(t);
    }

    public static String label(Track t) {
        return t.artist.isEmpty() ? t.title : t.artist + " - " + t.title;
    }

    /** M4 歌词兜底：按 歌名+歌手 找扫描缓存里的同曲，返回其 .lrc 全文（无则 null）。 */
    public static String lrcFor(String title, String artist) {
        if (title == null || title.isEmpty()) return null;
        List<Track> l = last;
        for (Track t : l) {
            if (t.lrcPath.isEmpty()) continue;
            if (!t.title.equals(title)) continue;
            if (!artist.isEmpty() && !t.artist.isEmpty() && !t.artist.equals(artist)) continue;
            try {
                byte[] b = new byte[(int) Math.min(new File(t.lrcPath).length(), 512 * 1024L)];
                java.io.FileInputStream in = new java.io.FileInputStream(t.lrcPath);
                int n = in.read(b);
                in.close();
                if (n > 0) return new String(b, 0, n, "UTF-8");
            } catch (Throwable ignore) { }
        }
        return null;
    }
}
