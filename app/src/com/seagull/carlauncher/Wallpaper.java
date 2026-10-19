package com.seagull.carlauncher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 壁纸（TODO P0-7）：从相册选图 → 拷进本机 → 白天/夜间各一张 → 桌面当背景铺。
 *
 * 全部走系统标准 Intent（ACTION_OPEN_DOCUMENT / ACTION_GET_CONTENT），不新增权限，
 * 也不用第三方图片库。
 */
public final class Wallpaper {

    private Wallpaper() {}

    /** 系统相册选择器：需要拿回 URI。 */
    public static Intent chooser() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    /** 动态壁纸选择器（批次 CI）：只挑视频。 */
    public static Intent chooserVideo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }

    /** 导入动态壁纸视频到 app 私有目录，返回路径；失败返回 null（调用方 toast）。 */
    public static String importVideo(LauncherModel m, Uri uri) {
        File dir = new File(m.context().getFilesDir(), "walls");
        if (!dir.exists() && !dir.mkdirs()) return null;
        File f = new File(dir, System.currentTimeMillis() + ".vid");
        try (InputStream in = m.context().getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            try (FileOutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } catch (Throwable t) {
            android.util.Log.w("SeagullWall", "导入动态壁纸失败", t);
            f.delete();
            return null;
        }
        return f.getAbsolutePath();
    }

    /** 拷贝到 app 私有目录并入壁纸库，返回新条目；满了返回 null。 */
    public static LauncherModel.Wall importImage(LauncherModel m, String name, Uri uri) {
        File dir = new File(m.context().getFilesDir(), "walls");
        if (!dir.exists() && !dir.mkdirs()) return null;
        File f = new File(dir, System.currentTimeMillis() + ".img");
        try (InputStream in = m.context().getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            try (FileOutputStream out = new FileOutputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } catch (Throwable t) {
            android.util.Log.w("SeagullWall", "导入壁纸失败", t);
            f.delete();   // 半截文件不留盘（旧实现异常路径泄漏流 + 残留孤儿文件）
            return null;
        }
        if (!m.addWall(name == null || name.isEmpty() ? "壁纸 " + (m.wallLib.size() + 1) : name,
                f.getAbsolutePath())) {
            f.delete();   // 库满了进不了库，盘上也不能留孤儿
            return null;
        }
        return new LauncherModel.Wall(name, f.getAbsolutePath());
    }

    /** 铺满屏幕的位图；读不到或文件没了返回 null（调用方回落纯色）。 */
    public static Bitmap loadForScreen(Context ctx, String path, int w, int h) {
        if (path == null || path.isEmpty()) return null;
        File f = new File(path);
        if (!f.exists()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (o.outWidth / sample > w * 2 && o.outHeight / sample > h * 2) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, o);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 壁纸亮不亮：抽样 16x16 算平均亮度。
     * 亮 → 界面文字翻黑 + 顶底栏加半透明底。
     */
    public static boolean isBright(Context ctx, String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 16;
            Bitmap b = BitmapFactory.decodeFile(path, o);
            if (b == null) return false;
            long sum = 0;
            int stepX = Math.max(1, b.getWidth() / 16);
            int stepY = Math.max(1, b.getHeight() / 16);
            int n = 0;
            for (int y = 0; y < b.getHeight(); y += stepY) {
                for (int x = 0; x < b.getWidth(); x += stepX) {
                    int p = b.getPixel(x, y);
                    sum += (((p >> 16) & 0xFF) * 30 + ((p >> 8) & 0xFF) * 59 + (p & 0xFF) * 11) / 100;
                    n++;
                }
            }
            return n > 0 && (sum / n) > 140;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 导入 Intent 回调统一入口：成功返回条目，失败 toast 原因。 */
    public static LauncherModel.Wall onPicked(LauncherModel m, String name, Uri uri) {
        if (uri == null) return null;
        LauncherModel.Wall w = importImage(m, name, uri);
        if (w == null) {
            if (m.wallLib.size() >= LauncherModel.WALL_LIB_MAX) {
                toast(m, "壁纸库满了（最多 " + LauncherModel.WALL_LIB_MAX + " 张），先删一张");
            } else {
                toast(m, "这张图读不出来，换一张试试");
            }
        }
        return w;
    }

    private static void toast(LauncherModel m, String s) {
        android.widget.Toast.makeText(m.context(), s,
                android.widget.Toast.LENGTH_SHORT).show();
    }
}
