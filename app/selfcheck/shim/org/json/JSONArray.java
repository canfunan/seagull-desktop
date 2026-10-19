package org.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

/** 自检垫片：数组（Collection 构造 + 下标读 + 迭代）。 */
public class JSONArray implements Iterable<Object> {

    final ArrayList<Object> list = new ArrayList<>();

    public JSONArray() { }

    public JSONArray(Collection<?> c) {
        if (c != null) list.addAll(c);
    }

    public int length() { return list.size(); }

    public Object get(int i) { return list.get(i); }

    public String optString(int i, String fallback) {
        Object v = i >= 0 && i < list.size() ? list.get(i) : null;
        return v instanceof String ? (String) v : fallback;
    }

    public JSONObject optJSONObject(int i) {
        Object v = i >= 0 && i < list.size() ? list.get(i) : null;
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public Iterator<Object> iterator() { return list.iterator(); }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(JSONObject.val(list.get(i)));
        }
        return sb.append(']').toString();
    }
}
