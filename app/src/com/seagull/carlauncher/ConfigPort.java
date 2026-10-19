package com.seagull.carlauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * O3 配置导出/导入。配置存档 = SharedPreferences(HomeActivity.PREFS) 全键
 * （现值只有 String/Boolean，JSON round-trip 原样保真；StringSet 走标记对象，
 * 其他类型跳过并记日志——前向保护，未来新类型落档时这里补写回分支）。
 * 纯逻辑（buildExportJson/parseImport）抽静态供 JVM 自检（ConfigCheck）。
 */
public final class ConfigPort {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final String TAG = "ConfigPort";
    private static final String SET_MARK = "__set";
    private static final int KEEP = 5;

    private ConfigPort() { }

    /* ==================== 纯函数（ConfigCheck 覆盖） ==================== */

    /** keys JSON 包 meta 壳（ts/v/commit）。 */
    public static String buildExportJson(JSONObject keys, String commit) throws Exception {
        JSONObject meta = new JSONObject();
        meta.put("ts", System.currentTimeMillis());
        meta.put("v", 2);
        meta.put("commit", commit == null ? "" : commit);
        JSONObject root = new JSONObject();
        root.put("meta", meta);
        root.put("keys", keys);
        return root.toString(2);
    }

    /** 校验并拆壳返回 keys；非法抛 IllegalArgumentException（文案用户可读）。 */
    public static JSONObject parseImport(String raw) throws Exception {
        if (raw == null) throw new IllegalArgumentException("文件是空的");
        String s = raw.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("文件是空的");
        if (s.length() > MAX_BYTES) throw new IllegalArgumentException("文件超过 5MB，不像配置文件");
        JSONObject root;
        try {
            root = new JSONObject(s);
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException("文件内容不是合法的 JSON");
        }
        JSONObject meta = root.optJSONObject("meta");
        if (meta == null) throw new IllegalArgumentException("缺少 meta 段（不是海鸥桌面的配置文件？）");
        if (!meta.has("v")) throw new IllegalArgumentException("缺少版本号（不是海鸥桌面的配置文件？）");
        JSONObject keys = root.optJSONObject("keys");
        if (keys == null) throw new IllegalArgumentException("缺少 keys 段（文件内容不完整）");
        return keys;
    }

    /* ==================== Android 侧 ==================== */

    /** prefs 全键打包（String/Boolean 直存，StringSet 打标记，其余跳过记日志）。 */
    public static JSONObject collectKeys(Context c) throws Exception {
        SharedPreferences sp = c.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
        Map<String, ?> all = sp.getAll();
        JSONObject keys = new JSONObject();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String || v instanceof Boolean || v instanceof Number) {
                keys.put(e.getKey(), v);
            } else if (v instanceof java.util.Set) {
                JSONObject m = new JSONObject();
                m.put(SET_MARK, new JSONArray((java.util.Set<?>) v));
                keys.put(e.getKey(), m);
            } else {
                RootLog.w(TAG, "导出跳过不支持的类型 key=" + e.getKey()
                        + " " + (v == null ? "null" : v.getClass().getName()), null);
            }
        }
        return keys;
    }

    /** 逐键批量写回（单次 apply，中途异常不留半档）；返回写入键数。 */
    public static int restoreKeys(Context c, JSONObject keys) {
        SharedPreferences sp = c.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        int n = 0;
        java.util.Iterator<String> it = keys.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = keys.opt(k);
            try {
                if (v instanceof String) {
                    ed.putString(k, (String) v);
                    n++;
                } else if (v instanceof Boolean) {
                    ed.putBoolean(k, (Boolean) v);
                    n++;
                } else if (v instanceof JSONObject) {
                    JSONObject m = (JSONObject) v;
                    JSONArray arr = m.optJSONArray(SET_MARK);
                    if (arr != null) {
                        java.util.Set<String> set = new java.util.HashSet<>();
                        for (int i = 0; i < arr.length(); i++) {
                            String s = arr.optString(i, null);
                            if (s != null) set.add(s);
                        }
                        ed.putStringSet(k, set);
                        n++;
                    }
                }
            } catch (Throwable t) {
                RootLog.w(TAG, "还原跳过 key=" + k, t);
            }
        }
        ed.apply();
        return n;
    }

    /** 导出：写 files/export/（保留 KEEP 份轮转），返回文件或 null。 */
    public static File exportTo(Context c, String commit) throws Exception {
        File dir = new File(c.getFilesDir(), "export");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File f = new File(dir, "seagull-config-"
                + android.text.format.DateFormat.format("yyyyMMdd-HHmmss", System.currentTimeMillis())
                + ".json");
        String json = buildExportJson(collectKeys(c), commit);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        rotate(dir);
        return f;
    }

    private static void rotate(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null || fs.length <= KEEP) return;
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int over = fs.length - KEEP;
        for (int i = 0; i < fs.length && over > 0; i++) {
            if (fs[i].isFile() && fs[i].delete()) over--;
        }
    }

    /** 导入：读流（≤5MB）→ 校验拆壳 → 批写回 → bumpCfg。返回错误文案，null=成功。 */
    public static String importFrom(Context c, android.net.Uri uri) {
        try {
            StringBuilder sb = new StringBuilder();
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in == null) return "文件读不出来";
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) return "文件超过 5MB，不像配置文件";
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            }
            JSONObject keys = parseImport(sb.toString());
            int n = restoreKeys(c, keys);
            LauncherModel.bumpCfg();
            RootLog.i(TAG, "导入完成 keys=" + n);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Throwable t) {
            RootLog.w(TAG, "导入失败", t);
            return "导入失败：" + t.getMessage();
        }
    }

    /** 分享导出文件（content:// + FLAG_GRANT_READ，APK 更新同款链路）。 */
    public static void share(Context c, File f) {
        android.net.Uri u = SeagullFileProvider.uriFor(c, f);
        if (u == null) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.putExtra(Intent.EXTRA_SUBJECT, "海鸥桌面配置");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        c.startActivity(Intent.createChooser(i, "导出配置"));
    }
}
