package com.seagull.carlauncher;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

/**
 * K1 天气联动壁纸：把当前 WMO 天气码映射到壁纸包目录里的一段循环视频。
 *
 * 目录约定（用户自备，SAF 选一次根目录即可，无存储权限）：
 *   <壁纸包>/
 *     sunny/   晴（WMO 0-1）
 *     cloudy/  多云、阴（2-3）
 *     fog/     雾（45、48）
 *     rain/    雨、毛毛雨、阵雨、雷阵雨（51-67、80-82、95+）
 *     snow/    雪、阵雪（71-77、85、86）
 *     night/   夜间主题（dayNight=DARK）优先于天气分类
 * 每个子目录放一段视频（mp4/webm/3gp/mkv 取第一个）；没匹配到就回落
 * 手动设置的动态壁纸（RESEARCH B6：匹配失败回落当前壁纸）。
 *
 * SAF 子目录列举可能跨 Binder，放后台线程跑，结果回主线程。
 */
public final class WeatherWall {

    private static final String[] VIDEO_EXT = {".mp4", ".webm", ".3gp", ".mkv"};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WeatherWall() {}

    /** WMO 码 → 包目录名；没取到天气/码不含糊时返回 null（保持手动壁纸）。 */
    public static String category(int code) {
        if (code < 0) return null;
        if (code <= 1) return "sunny";
        if (code <= 3) return "cloudy";
        if (code == 45 || code == 48) return "fog";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95) return "rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "snow";
        return null;
    }

    /**
     * 后台解析当前该播的视频 Uri（字符串形式，content://），结果回主线程。
     * 回调参数可能为 null = 联动关/没选目录/没取到天气/包里没匹配，宿主回落手动壁纸。
     */
    public static void resolve(Context c, LauncherModel m, java.util.function.Consumer<String> done) {
        if (m == null || !m.wallWeatherLink || m.wallWeatherDir.isEmpty()) {
            MAIN.post(() -> done.accept(null));
            return;
        }
        final int code = m.weatherCode;
        final boolean night = m.dayNight == LauncherModel.DayNight.DARK;
        final String tree = m.wallWeatherDir;
        new Thread(() -> {
            String hit = null;
            if (night) hit = firstVideo(c, tree, "night");
            if (hit == null) {
                String cat = category(code);
                if (cat != null) hit = firstVideo(c, tree, cat);
            }
            final String out = hit;
            MAIN.post(() -> done.accept(out));
        }, "wx-wall-resolve").start();
    }

    /** tree Uri 字符串 + 子目录名 → 子目录下第一个视频文件的 content Uri；没有 = null。 */
    private static String firstVideo(Context c, String treeUri, String dir) {
        ContentResolver cr = c.getContentResolver();
        try {
            Uri tree = Uri.parse(treeUri);
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            String dirId = childDocId(cr, tree, rootId, dir);
            if (dirId == null) return null;
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId);
            try (Cursor cur = cr.query(kids,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                    null, null, null)) {
                while (cur != null && cur.moveToNext()) {
                    String name = cur.getString(0) == null ? "" : cur.getString(0);
                    String low = name.toLowerCase(java.util.Locale.US);
                    for (String ext : VIDEO_EXT) {
                        if (low.endsWith(ext)) {
                            String id = cur.getString(1);
                            if (id == null) break;
                            return DocumentsContract.buildDocumentUriUsingTree(tree, id).toString();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("SeagullWxWall", "列壁纸包失败 " + dir + ": " + t);
        }
        return null;
    }

    /** 在 parent 的直接子项里找名为 name 的目录，返回其 documentId。 */
    private static String childDocId(ContentResolver cr, Uri tree, String parentId, String name) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor cur = cr.query(kids,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE},
                null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String n = cur.getString(1);
                String mime = cur.getString(2);
                if (name.equals(n)
                        && DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    return cur.getString(0);
                }
            }
        } catch (Throwable ignore) { }
        return null;
    }
}
