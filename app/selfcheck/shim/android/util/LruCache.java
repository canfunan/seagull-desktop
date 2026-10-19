package android.util;

/**
 * 纯 JVM 自检垫片：桩 android.jar 的 LruCache 只是空壳（一碰就 Stub!），
 * QuickbarCheck 等 selfcheck 走 LauncherModel 类初始化时会被炸。
 * 运行时把本垫片目录放在 classpath 最前，覆盖桩实现；API 面按 LauncherModel/
 * DockBar 的实际用量（构造/get/put）实现 LinkedHashMap LRU 语义。
 * 仅限本地自检用，打进 APK 的仍是框架真实现。
 */
public class LruCache<K, V> {
    private final java.util.LinkedHashMap<K, V> map;
    private final int maxSize;

    public LruCache(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
        map = new java.util.LinkedHashMap<K, V>(16, 0.75f, true);
    }

    public final V get(K key) {
        V v = map.get(key);
        if (v == null) {
            v = create(key);
            if (v != null) put(key, v);
        }
        return v;
    }

    public final V put(K key, V value) {
        V old = map.remove(key);
        if (value != null) {
            map.put(key, value);
            trim();
        }
        return old;
    }

    public final void evictAll() { map.clear(); }

    public final int maxSize() { return maxSize; }

    protected V create(K key) { return null; }

    protected int sizeOf(K key, V value) { return 1; }

    protected void entryRemoved(boolean evicted, K key, V oldValue, V newValue) { }

    private void trim() {
        while (map.size() > maxSize) {
            K eldest = null;
            V evicted = null;
            java.util.Iterator<java.util.Map.Entry<K, V>> it = map.entrySet().iterator();
            if (!it.hasNext()) break;
            java.util.Map.Entry<K, V> e = it.next();
            eldest = e.getKey();
            evicted = e.getValue();
            it.remove();
            entryRemoved(false, eldest, evicted, null);
        }
    }
}
