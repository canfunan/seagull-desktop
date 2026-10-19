/*
 * 零依赖 FileProvider 替代：把 files/ 下的文件包成 content:// URI。
 *
 * 背景（批次 P）：settings 里「下载新版本」把 APK 下到 getFilesDir() 后用
 * Uri.fromFile() 发安装 intent。targetSdk>=24 起这是 FileUriExposedException
 * （秒崩，装不上还只弹一个看不懂的对话框）。项目不许引 androidx，
 * 所以照 FileProvider 的最小面（query/insert 抛异常，open/openAsset 都要能
 * 读）自己写一个 100 行的。manifest 里已注册。
 *   .SeagullFileProvider  authorities=com.seagull.carlauncher.fileprovider
 */
package com.seagull.carlauncher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;

public final class SeagullFileProvider extends ContentProvider {

    private static final String TAG = "SeagullFp";
    public static final String AUTHORITY = "com.seagull.carlauncher.fileprovider";
    private static final String AUTHORITY_PKG = "com.seagull.carlauncher";
    private static volatile android.content.Context sCtx;

    @Override public boolean onCreate() {
        sCtx = getContext();
        // 注册确认写在这里而不是 static{}：static 在类加载就跑，那时
        // PackageManager 可能还没起来，误报 provider 未注册反而误导排查。
        try {
            if (sCtx != null) {
                ProviderInfo pi = sCtx.getPackageManager()
                        .resolveContentProvider(AUTHORITY, 0);
                if (pi == null) Log.w(TAG, "provider 未注册（看 AndroidManifest 的 <provider>）");
            }
        } catch (Throwable ignore) { }
        return true;
    }

    /** files/ 根目录：ContentProvider 自己就拿着 Context，不必引 Application 单例。 */
    private File filesDir() {
        java.io.File d = sCtx != null ? sCtx.getFilesDir() : null;
        return d != null ? d : new java.io.File("/data/data/" + AUTHORITY_PKG + "/files");
    }

    /** 权威字符串拿 files/ 下的文件；别名/大小写不符一律当不存在。 */
    private File fileFor(Uri uri) {
        if (uri == null) return null;
        String auth = uri.getAuthority();
        if (auth != null && !AUTHORITY.equals(auth)) return null;
        java.util.List<String> segs = uri.getPathSegments();
        if (segs == null || segs.isEmpty() || !"updates".equals(segs.get(0))) return null;
        StringBuilder rel = new StringBuilder();
        for (int i = 1; i < segs.size(); i++) {
            String s = segs.get(i);
            if (s == null || s.isEmpty() || ".".equals(s) || "..".equals(s)) return null;
            if (rel.length() > 0) rel.append(File.separatorChar);
            rel.append(s);
        }
        if (rel.length() == 0) return null;
        File f = new File(filesDir(), rel.toString());
        try {
            // 规范化后必须仍在 files/ 内：防 "updates/../databases/x.db" 穿越
            String base = filesDir().getCanonicalPath();
            String real = f.getCanonicalPath();
            if (!real.startsWith(base + File.separatorChar)) return null;
        } catch (Throwable t) {
            return null;
        }
        return f;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        File f = fileFor(uri);
        if (f == null || !f.isFile()) throw new FileNotFoundException("no file for " + uri);
        // 审计 L11：本 Provider 只供应用内共享只读文件（壁纸库/日志），
        // 统一强制 READ_ONLY —— 拿写模式 open 的外部组件改不了共享文件
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode)
            throws FileNotFoundException {
        File f = fileFor(uri);
        if (f == null || !f.isFile()) throw new FileNotFoundException("no file for " + uri);
        return new AssetFileDescriptor(
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), 0,
                AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        File f = fileFor(uri);
        if (f == null || !f.isFile()) return null;
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        MatrixCursor c = new MatrixCursor(cols, 1);
        c.addRow(row);
        return c;
    }

    @Override public String getType(Uri uri) {
        // PackageInstaller 只认这一种 MIME
        File f = fileFor(uri);
        return f != null && f.getName().endsWith(".apk")
                ? "application/vnd.android.package-archive"
                : "application/octet-stream";
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("只读 provider");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("只读 provider");
    }

    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("只读 provider");
    }

    /** 供外部拼安装 intent：file → content URI（不在 files/ 内返回 null）。 */
    public static Uri uriFor(android.content.Context ctx, File f) {
        if (f == null || ctx == null) return null;
        try {
            File dir = ctx.getFilesDir();
            if (dir == null) return null;
            String base = dir.getCanonicalPath();
            String real = f.getCanonicalPath();
            if (!real.startsWith(base + File.separatorChar)) return null;
            String rel = real.substring(base.length() + 1).replace(File.separatorChar, '/');
            return new Uri.Builder()
                    .scheme("content")
                    .authority(AUTHORITY)
                    .appendPath("updates")
                    .appendPath(rel)
                    .build();
        } catch (Throwable t) {
            Log.w(TAG, "uriFor 失败: " + t);
            return null;
        }
    }

    /* ---------------- 以下 API 本 provider 用不到，但接口要求 ---------------- */

    @Override public String[] getStreamTypes(Uri uri, String mimeTypeFilter) {
        return new String[]{"application/vnd.android.package-archive"};
    }

    @Override public Bundle call(String method, String arg, Bundle extras) { return null; }
}
