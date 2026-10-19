package com.seagull.carlauncher;

/**
 * 自检：ConfigPort 配置导出/导入 round-trip + 拒绝用例（O3）。
 * 导出→导入逐键相等（含 Boolean/String/Set 标记），破损 JSON/缺 meta/缺 v/缺 keys 全拒绝。
 *
 * 跑法（纯 JVM）：
 *   javac -d /tmp/cfgc app/src/com/seagull/carlauncher/ConfigPort.java \
 *        app/src/com/seagull/carlauncher/HomeActivity.java ... （实际按批次跑法全量）
 *   java -cp /tmp/cfgc com.seagull.carlauncher.ConfigCheck
 */
public class ConfigCheck {

    private static int n = 0;

    public static void main(String[] args) throws Exception {
        // 1) 样例 keys：真实 prefs 形态（String 主体 + Boolean + StringSet 标记）
        org.json.JSONObject keys = new org.json.JSONObject();
        keys.put("json_layout", "{\"v\":2,\"mode\":\"TWO\",\"dock\":[\"a/b\",\"c/d\"]}");
        keys.put("mirror_pkg1", "com.amap.android.autonavi/com.amap.api.drive.Main");
        keys.put("mirror_pkg2", "");
        keys.put("mirror_bool", true);
        keys.put("wall_video", "/sdcard/walls/a.mp4");
        keys.put("lrc_offset", "120");
        keys.put("obd_enabled", false);
        keys.put("theme_glass", "");
        for (int i = 0; i < 24; i++) keys.put("k" + i, "v" + i);
        org.json.JSONObject setMark = new org.json.JSONObject();
        setMark.put("__set", new org.json.JSONArray(java.util.Arrays.asList("a", "b", "c")));
        keys.put("some_string_set", setMark);
        check(keys.length() >= 30, "样例键数 " + keys.length());

        // 2) 导出→解析 round-trip：逐键相等
        String exported = ConfigPort.buildExportJson(keys, "test-commit-1");
        org.json.JSONObject back = ConfigPort.parseImport(exported);
        check(back.length() == keys.length(),
                "键数 round-trip " + back.length() + " vs " + keys.length());
        java.util.Iterator<String> it = keys.keys();
        int same = 0;
        while (it.hasNext()) {
            String k = it.next();
            if (String.valueOf(keys.get(k)).equals(String.valueOf(back.opt(k)))) same++;
        }
        check(same == keys.length(), "逐键相等 " + same + "/" + keys.length());

        // 3) meta 完整性
        org.json.JSONObject root = new org.json.JSONObject(exported);
        org.json.JSONObject meta = root.getJSONObject("meta");
        check(meta.has("v") && meta.getLong("ts") > 0
                && "test-commit-1".equals(meta.getString("commit")), "meta 段字段齐");

        // 4) 拒绝用例
        reject(null, "null 输入");
        reject("", "空串");
        reject("not-json{", "非 JSON");
        reject("{\"keys\":{}}", "缺 meta");
        reject("{\"meta\":{\"ts\":1}}", "缺 v");
        reject("{\"meta\":{\"v\":2}}", "缺 keys");
        reject("{\"meta\":{\"v\":2},\"keys\":{}}".substring(0, 20), "截断 JSON");

        // 5) 超长输入拒绝（>5MB 文本）
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6_000_000; i++) big.append("x");
        reject("{\"meta\":{\"v\":2},\"keys\":{\"a\":\"" + big + "\"}}", "超 5MB");

        // 6) 边界：空 keys 合法（全新机器备份）
        org.json.JSONObject emptyKeys = new org.json.JSONObject();
        String ej = ConfigPort.buildExportJson(emptyKeys, null);
        check(ConfigPort.parseImport(ej).length() == 0, "空 keys round-trip");
        org.json.JSONObject metaNull = new org.json.JSONObject(ej).getJSONObject("meta");
        check("".equals(metaNull.getString("commit")), "commit null 兜底空串");

        System.out.println("ConfigCheck OK (" + n + " 项)");
    }

    private static void reject(String raw, String label) {
        try {
            ConfigPort.parseImport(raw);
            check(false, label + " 未被拒绝");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage() != null && !expected.getMessage().isEmpty(),
                    label + " 拒绝且文案可读: " + expected.getMessage());
        } catch (Exception other) {
            check(false, label + " 抛了非 IAE: " + other);
        }
    }

    private static void check(boolean ok, String what) {
        n++;
        if (!ok) throw new AssertionError("ConfigCheck 失败: " + what);
    }
}
