package org.loader.runtime.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.runtime.transform.cache.TransformationCache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转换缓存测试。
 *
 * <h2>为什么缓存正确性值得单独测试</h2>
 * 缓存的一个 bug 就有两种表现，且都极难定位：
 * <ul>
 *   <li><b>该缓存的没缓存</b> → 同一个类被转换两次 →
 *       注入的回调执行两次 → <b>tick 数翻倍</b>，游戏完全正常；</li>
 *   <li><b>不该缓存的缓存了</b> → 另一个 ClassLoader 拿到
 *       指向已卸载类的字节码 → {@code NoClassDefFoundError}。</li>
 * </ul>
 */
class TransformationCacheTest {

    private static final String CLASS_NAME = "net/minecraft/server/MinecraftServer";

    @Test
    @DisplayName("put 之后能取回同一字节码")
    void storesAndRetrieves() {
        TransformationCache cache = new TransformationCache();
        ClassLoader cl = getClass().getClassLoader();
        byte[] bytes = {1, 2, 3};

        cache.put(cl, CLASS_NAME, bytes);

        assertSame(bytes, cache.get(cl, CLASS_NAME),
                "缓存不应复制数组 —— 1 万个类的启动开销会因此翻倍。"
                        + "契约是：写入后调用方不得再修改该数组。");
        assertTrue(cache.contains(cl, CLASS_NAME));
    }

    @Test
    @DisplayName("缓存键包含 ClassLoader 身份")
    void cacheIsKeyedByClassLoaderIdentity() {
        TransformationCache cache = new TransformationCache();
        ClassLoader cl1 = new ClassLoader(null) { };
        ClassLoader cl2 = new ClassLoader(null) { };

        byte[] first = {1, 2, 3};
        byte[] second = {4, 5, 6};

        cache.put(cl1, CLASS_NAME, first);

        assertSame(first, cache.get(cl1, CLASS_NAME));
        assertNull(cache.get(cl2, CLASS_NAME),
                "不同 ClassLoader 必须有独立的缓存 —— "
                        + "否则第二个 CL 会拿到第一个 CL 的字节码，"
                        + "其中的引用指向已卸载的类 → NoClassDefFoundError。");

        cache.put(cl2, CLASS_NAME, second);
        assertSame(second, cache.get(cl2, CLASS_NAME));
        assertSame(first, cache.get(cl1, CLASS_NAME),
                "写入第二个 CL 的缓存不应影响第一个 CL 的缓存");
    }

    @Test
    @DisplayName("同类名的不同类互不干扰")
    void differentClassNamesAreIndependent() {
        TransformationCache cache = new TransformationCache();
        ClassLoader cl = getClass().getClassLoader();

        byte[] a = {1};
        byte[] b = {2};
        cache.put(cl, "some/A", a);
        cache.put(cl, "some/B", b);

        assertSame(a, cache.get(cl, "some/A"));
        assertSame(b, cache.get(cl, "some/B"));
        assertEquals(2, cache.size());
    }

    @Test
    @DisplayName("invalidate 只清除指定类的缓存")
    void invalidateRemovesSingleEntry() {
        TransformationCache cache = new TransformationCache();
        ClassLoader cl = getClass().getClassLoader();
        cache.put(cl, "some/A", new byte[]{1});
        cache.put(cl, "some/B", new byte[]{2});

        assertArrayEquals(new byte[]{1},
                cache.invalidate(cl, "some/A"));

        assertFalse(cache.contains(cl, "some/A"));
        assertTrue(cache.contains(cl, "some/B"));
    }

    @Test
    @DisplayName("invalidateAll 清空指定 ClassLoader 的全部缓存 —— 热重载必须调用它")
    void invalidateAllClearsOneClassLoader() {
        TransformationCache cache = new TransformationCache();
        ClassLoader cl1 = new ClassLoader(null) { };
        ClassLoader cl2 = new ClassLoader(null) { };

        cache.put(cl1, "some/A", new byte[]{1});
        cache.put(cl1, "some/B", new byte[]{2});
        cache.put(cl2, "some/A", new byte[]{3});

        assertEquals(2, cache.invalidateAll(cl1));
        assertEquals(1, cache.size(),
                "只应剩下 cl2 的那一条");
        assertTrue(cache.contains(cl2, "some/A"),
                "invalidateAll 不得影响其他 ClassLoader");
    }

    @Test
    @DisplayName("缓存持有字节码，因此必须能显式失效以免泄漏 ClassLoader")
    void cacheSupportsClassLoaderRelease() {
        TransformationCache cache = new TransformationCache();
        ClassLoader cl = new ClassLoader(null) { };

        cache.put(cl, "some/A", new byte[]{1});
        assertEquals(1, cache.invalidateAll(cl));

        assertEquals(0, cache.size(),
                "缓存持有字节码数组，会阻止 ClassLoader 被回收。"
                        + "Mod 热重载时必须调用 invalidateAll，否则内存泄漏。");
    }

    @Test
    @DisplayName("拒绝缓存空字节码")
    void rejectsEmptyBytecode() {
        TransformationCache cache = new TransformationCache();
        ClassLoader cl = getClass().getClassLoader();

        assertThrows(IllegalArgumentException.class,
                () -> cache.put(cl, "some/A", null));
        assertThrows(IllegalArgumentException.class,
                () -> cache.put(cl, "some/A", new byte[0]));
    }

    @Test
    @DisplayName("命中与未命中分别计数，命中率可用于诊断")
    void tracksHitAndMissCounts() {
        TransformationCache cache = new TransformationCache();
        ClassLoader cl = getClass().getClassLoader();
        cache.put(cl, "some/A", new byte[]{1});

        cache.get(cl, "some/A");        // hit
        cache.get(cl, "some/A");        // hit
        cache.get(cl, "some/B");        // miss

        assertEquals(2, cache.hitCount());
        assertEquals(1, cache.missCount());
        assertTrue(cache.diagnostics().contains("hitRate"));
    }

    @Test
    @DisplayName("clear 清空全部条目")
    void clearRemovesEverything() {
        TransformationCache cache = new TransformationCache();
        ClassLoader cl = getClass().getClassLoader();
        cache.put(cl, "some/A", new byte[]{1});
        cache.put(cl, "some/B", new byte[]{2});

        cache.clear();
        assertEquals(0, cache.size());
    }
}