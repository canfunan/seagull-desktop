package android.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JVM 自检垫片（审计 L16）：android.jar 里的 LruCache 是 Stub 桩，
 * 自检在普通 JVM 上跑 LauncherModel 类初始化就炸 Stub!。
 * 这里给一个语义等价的真实现（get 访问计新鲜度 + LRU 逐出），
 * 编译后放在 classpath 最前面，优先于 android.jar 被加载。
 * 仅用于 selfcheck；设备上跑的是平台真类，与本文件无关。
 */
public class LruCache<K, V> {

    private final LinkedHashMap<K, V> map;
    private final int maxSize;

    public LruCache(int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize <= 0");
        this.maxSize = maxSize;
        this.map = new LinkedHashMap<K, V>(0, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > LruCache.this.maxSize;
            }
        };
    }

    public final V get(K key) {
        synchronized (map) { return map.get(key); }
    }

    public final V put(K key, V value) {
        if (value == null) throw new NullPointerException("key == null || value == null");
        synchronized (map) { return map.put(key, value); }
    }

    public final V remove(K key) {
        synchronized (map) { return map.remove(key); }
    }

    public final void evictAll() {
        synchronized (map) { map.clear(); }
    }

    public final int size() {
        synchronized (map) { return map.size(); }
    }
}
