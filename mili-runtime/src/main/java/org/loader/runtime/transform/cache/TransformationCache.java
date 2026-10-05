package org.loader.runtime.transform.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 转换结果缓存 —— 防止同一个类被重复转换。
 *
 * <h2>为什么需要它：重复转换会造成静默的语义破坏</h2>
 * 若同一个类的字节码被转换两次：
 * <pre>
 *   原始 → [Transform A] → 中间态 → [Transform A 再次] → 最终态
 * </pre>
 * 第二次注入会在第一次注入的<b>前面</b>再插一份回调，于是
 * {@code MiliTickEngine.beginTick()} 每 tick 被调用两次。
 *
 * <p>这类问题的表现形式极具迷惑性：游戏照常运行、tick 数却在翻倍、
 * 日志里没有任何错误。<b>重复注入比不注入更难查</b>，因为它看起来
 * 像是「平台工作得太努力了」。
 *
 * <h2>缓存键：ClassLoader 身份 + 类名</h2>
 * <p><b>必须包含 ClassLoader 身份，不能只用类名。</b>同一个类名会在
 * 不同 ClassLoader 中被定义多次（Mod 热重载、不同 Mod 各带一份同名依赖）。
 * 只用类名做键会导致第二个 ClassLoader 拿到第一个的缓存 ——
 * 那份字节码里的引用指向旧 ClassLoader 已卸载的类，
 * 表现为 {@code NoClassDefFoundError} 且极难定位。
 *
 * <p>使用 {@link ClassLoader} 的<b>引用相等</b>（默认 {@code equals}）
 * 而非 {@code ==}：ClassLoader 未重写 equals，两者等价，但用 equals
 * 更明确地表意。
 *
 * <h2>缓存生命周期</h2>
 * <p>缓存持有字节码数组，因此它本身会阻止 ClassLoader 被回收。
 * {@link #invalidate(ClassLoader)} 必须在 ClassLoader 关闭时调用，
 * 否则热重载会泄漏内存。本仓库已有
 * {@code ClassLoaderLeakTest} 专门守这条线。
 */
public final class TransformationCache {

    private final Map<CacheKey, byte[]> cache = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong puts = new AtomicLong();

    /**
     * 缓存键 —— ClassLoader 身份 + 类内部名。
     *
     * <p>使用嵌套类而非字符串拼接：嵌套类的 equals/hashCode 由身份派生，
     * 而字符串拼接会依赖 {@code ClassLoader.toString()}，
     * 那既慢又不保证唯一。
     */
    private static final class CacheKey {
        private final ClassLoader classLoader;
        private final String className;

        CacheKey(ClassLoader classLoader, String className) {
            this.classLoader = classLoader;
            this.className = className;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey other)) return false;
            return classLoader == other.classLoader
                    && className.equals(other.className);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(classLoader) * 31 + className.hashCode();
        }
    }

    /**
     * 查询缓存。
     *
     * @return 已转换的字节码；未缓存时返回 null
     */
    public byte[] get(ClassLoader classLoader, String className) {
        byte[] found = cache.get(new CacheKey(classLoader, className));
        if (found != null) {
            hits.incrementAndGet();
        } else {
            misses.incrementAndGet();
        }
        return found;
    }

    /** 是否已缓存。 */
    public boolean contains(ClassLoader classLoader, String className) {
        return cache.containsKey(new CacheKey(classLoader, className));
    }

    /**
     * 写入缓存。
     *
     * <p><b>不复制数组。</b>调用方（Pipeline）已持有自己生成的新数组，
     * 不会再修改它；额外复制会让 1 万个类的启动开销翻倍。
     * 契约：写入后调用方不得再修改该数组。
     */
    public void put(ClassLoader classLoader, String className, byte[] bytecode) {
        if (bytecode == null || bytecode.length == 0) {
            throw new IllegalArgumentException("不应缓存空字节码");
        }
        cache.put(new CacheKey(classLoader, className), bytecode);
        puts.incrementAndGet();
    }

    /**
     * 移除某个类的缓存 —— <b>ClassLoader 关闭时必须调用</b>。
     */
    public byte[] invalidate(ClassLoader classLoader, String className) {
        return cache.remove(new CacheKey(classLoader, className));
    }

    /**
     * 清空某个 ClassLoader 的全部缓存。
     *
     * <p>Mod 热重载 / 实例卸载时调用。
     *
     * @return 清除的条目数
     */
    public int invalidateAll(ClassLoader classLoader) {
        int removed = 0;
        // CacheKey 没有反向索引，遍历过滤。
        // 缓存规模等于已转换的类数（远小于全部类），遍历成本可接受，
        // 而维护反向索引会引入并发一致性问题 —— 不值得。
        for (CacheKey key : cache.keySet()) {
            if (key.classLoader == classLoader && cache.remove(key) != null) {
                removed++;
            }
        }
        return removed;
    }

    /** 清空全部缓存。 */
    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    public long hitCount() {
        return hits.get();
    }

    public long missCount() {
        return misses.get();
    }

    /** 诊断摘要。 */
    public String diagnostics() {
        long total = hits.get() + misses.get();
        String rate = total == 0 ? "n/a"
                : String.format("%.1f%%", 100.0 * hits.get() / total);
        return "TransformationCache[size=" + size()
                + ", hits=" + hits.get()
                + ", misses=" + misses.get()
                + ", hitRate=" + rate
                + ", puts=" + puts.get() + "]";
    }
}