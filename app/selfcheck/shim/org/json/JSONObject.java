package org.json;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自检垫片：org.json 最小实现（android.jar 里是 Stub，JVM 跑不了）。
 * 单遍递归下降，只覆盖批次自检用到的 API 子集；插入序保持。
 */
public class JSONObject {

    private final Map<String, Object> map = new LinkedHashMap<>();

    public JSONObject() { }

    public JSONObject(String json) {
        int[] end = new int[1];
        parse(json.trim(), 0, end);
    }

    private void parse(String s, int start, int[] end) {
        int i = ws(s, start);
        if (i >= s.length() || s.charAt(i) != '{') throw new JSONException("not an object @" + i);
        i = ws(s, i + 1);
        if (i < s.length() && s.charAt(i) == '}') { end[0] = i + 1; return; }
        while (true) {
            if (i >= s.length() || s.charAt(i) != '"')
                throw new JSONException("expect key @" + i);
            String key = str(s, i, end);
            i = ws(s, end[0]);
            if (i >= s.length() || s.charAt(i) != ':')
                throw new JSONException("expect : @" + i);
            map.put(key, value(s, ws(s, i + 1), end));
            i = ws(s, end[0]);
            if (i < s.length() && s.charAt(i) == ',') { i = ws(s, i + 1); continue; }
            if (i < s.length() && s.charAt(i) == '}') { end[0] = i + 1; return; }
            throw new JSONException("expect , or } @" + i);
        }
    }

    private Object value(String s, int i, int[] end) {
        if (i >= s.length()) throw new JSONException("unexpected end");
        char c = s.charAt(i);
        if (c == '"') return str(s, i, end);
        if (c == '{') {
            JSONObject o = new JSONObject();
            o.parse(s, i, end);
            return o;
        }
        if (c == '[') {
            JSONArray a = new JSONArray();
            int j = ws(s, i + 1);
            if (j < s.length() && s.charAt(j) == ']') { end[0] = j + 1; return a; }
            while (true) {
                a.list.add(value(s, ws(s, j), end));
                j = ws(s, end[0]);
                if (j < s.length() && s.charAt(j) == ',') { j++; continue; }
                if (j < s.length() && s.charAt(j) == ']') { end[0] = j + 1; return a; }
                throw new JSONException("expect , or ] @" + j);
            }
        }
        int j = i;
        while (j < s.length() && ",}]".indexOf(s.charAt(j)) < 0) j++;
        String tok = s.substring(i, j).trim();
        end[0] = j;
        if ("true".equals(tok)) return Boolean.TRUE;
        if ("false".equals(tok)) return Boolean.FALSE;
        if ("null".equals(tok)) return null;
        try { return Long.valueOf(Long.parseLong(tok)); }
        catch (NumberFormatException ignore) { }
        try { return Double.valueOf(Double.parseDouble(tok)); }
        catch (NumberFormatException e2) { throw new JSONException("bad number " + tok); }
    }

    private String str(String s, int start, int[] end) {
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '"') { end[0] = i + 1; return sb.toString(); }
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n == 'r' ? '\r' : n);
            } else sb.append(ch);
            i++;
        }
        throw new JSONException("unterminated string @" + start);
    }

    private int ws(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    public JSONObject put(String name, Object value) {
        if (name == null) throw new JSONException("null key");
        map.put(name, value);
        return this;
    }

    public JSONObject put(String name, boolean value) { return put(name, (Object) value); }
    public JSONObject put(String name, int value) { return put(name, (Object) (long) value); }
    public JSONObject put(String name, long value) { return put(name, (Object) (Long) value); }
    public JSONObject put(String name, double value) { return put(name, (Object) (Double) value); }

    public Object get(String name) {
        Object v = map.get(name);
        if (v == null) throw new JSONException("no value for " + name);
        return v;
    }

    public Object opt(String name) { return map.get(name); }

    public boolean has(String name) { return map.containsKey(name); }

    public int length() { return map.size(); }

    public Iterator<String> keys() { return map.keySet().iterator(); }

    public JSONObject getJSONObject(String name) { return (JSONObject) get(name); }

    public JSONArray getJSONArray(String name) { return (JSONArray) get(name); }

    public String getString(String name) { return (String) get(name); }

    public long getLong(String name) {
        Object v = get(name);
        if (v instanceof Long) return (Long) v;
        if (v instanceof Double) return (long) (double) (Double) v;
        throw new JSONException("not a long: " + name);
    }

    public JSONObject optJSONObject(String name) {
        Object v = map.get(name);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public JSONArray optJSONArray(String name) {
        Object v = map.get(name);
        return v instanceof JSONArray ? (JSONArray) v : null;
    }

    public int optInt(String name, int fallback) {
        Object v = map.get(name);
        return v instanceof Long ? (int) (long) (Long) v : fallback;
    }

    public int optInt(String name) { return optInt(name, 0); }

    public long optLong(String name, long fallback) {
        Object v = map.get(name);
        return v instanceof Long ? (Long) v : fallback;
    }

    public String optString(String name, String fallback) {
        Object v = map.get(name);
        return v instanceof String ? (String) v : fallback;
    }

    public String optString(String name) { return optString(name, ""); }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(q(e.getKey())).append(':').append(val(e.getValue()));
        }
        return sb.append('}').toString();
    }

    public String toString(int indent) { return toString(); }

    static String val(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return q((String) v);
        if (v instanceof JSONObject || v instanceof JSONArray
                || v instanceof Boolean || v instanceof Number) return String.valueOf(v);
        throw new JSONException("unsupported type " + v.getClass());
    }

    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') sb.append('\\');
            sb.append(ch);
        }
        return sb.append('"').toString();
    }
}
