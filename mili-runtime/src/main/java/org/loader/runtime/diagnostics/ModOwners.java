package org.loader.runtime.diagnostics;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Mod 归属注册表 —— 由类加载器反查 modId。
 *
 * <h2>为什么用类加载器，而不是让 Mod 自报家门</h2>
 * 归因最直接的做法是给每个 API 加一个 {@code modId} 参数，或者要求 Mod 在
 * 提交任务时传自己的身份。<b>两条都不能接受</b>：
 * <ul>
 *   <li>前者会污染整条 ABI —— 一个纯诊断需求换来所有调用点签名变更。</li>
 *   <li>后者要求 Mod 配合，而拒绝配合的 Mod 只是"归因不到"，
 *       平台却没有任何办法。这与"观测不拦截"的决策直接矛盾。</li>
 * </ul>
 *
 * <p>而 ClassLoader 已经是天然的归属边界：{@code ModClassLoader} 与 Mod
 * 一一对应（见 {@code docs/architecture/CLASSLOADER.md}）。一个对象属于哪个
 * Mod，等价于它的类由哪个 ModClassLoader 定义。<b>这个信息不需要 Mod 提供，
 * 平台在创建 ClassLoader 的那一刻就已经知道。</b>
 *
 * <h2>代价与为什么不贵</h2>
 * 反查要经过一次 {@code ClassValue} + 一次 map 查询。<b>ClassValue 是关键</b>：
 * 它以 {@code Class} 为键缓存结果，同一个类第二次访问时只是一次数组索引 ——
 * 而 tick 里反复执行的是同几个任务类，命中率接近 100%。
 *
 * <p>表用 {@link WeakHashMap}：Mod 卸载后它的 ClassLoader 应当被回收，
 * 这张表不能成为最后一个强引用。WeakHashMap 的同步代价只在
 * {@code computeValue} 首次解析时付出，不在热路径上。
 *
 * <h2>线程安全</h2>
 * {@link #register} 在启动期单线程调用；{@link #idOf(Class)} 可在任意线程调用。
 * 两者通过 {@code synchronized(BY_LOADER)} 串行化，且不在 tick 热路径上
 * （热路径走 ClassValue 缓存）。
 */
public final class ModOwners {

    /** 无法归因时返回的占位名。它不是错误 —— 见 {@link #idOf(Class)}。 */
    public static final String UNATTRIBUTED = "<unattributed>";

    private static final Map<ClassLoader, String> BY_LOADER = new WeakHashMap<>();

    /**
     * 类 → modId 的缓存。
     *
     * <p>缓存到 {@code Class} 而不是 {@code ClassLoader}，是因为热路径拿到的是
     * 一个具体的类（{@code task.getClass()}），再往下问加载器等于多一次调用；
     * 而且同一个类的结果永不改变，缓存永远有效。
     */
    private static final ClassValue<String> BY_TYPE = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            return idOf(type.getClassLoader());
        }
    };

    private ModOwners() {
    }

    /**
     * 登记一个 Mod 的类加载器。由 Loader 在创建 {@code ModClassLoader}
     * 之后立即调用 —— 早于任何 Mod 类的加载，因此 {@link #BY_TYPE} 的缓存
     * 不会把一个 Mod 类永久记成"未归因"。
     */
    public static void register(ClassLoader loader, String modId) {
        if (loader == null || modId == null || modId.isBlank()) {
            return;
        }
        synchronized (BY_LOADER) {
            BY_LOADER.put(loader, modId);
        }
    }

    /**
     * 反查某个类所属的 Mod。
     *
     * <p>返回 {@link #UNATTRIBUTED} 的情形有三种，都<b>不是错误</b>：
     * 平台自身的类、JDK 的类、以及绕过 {@code ModClassLoader} 由父加载器
     * 提供的共享库。把"归因不到"当成异常处理，会让每一个平台回调都变成
     * 一次告警。
     */
    public static String idOf(Class<?> type) {
        if (type == null) {
            return UNATTRIBUTED;
        }
        return BY_TYPE.get(type);
    }

    /** 反查某个类加载器所属的 Mod。 */
    public static String idOf(ClassLoader loader) {
        if (loader == null) {
            return UNATTRIBUTED;
        }
        synchronized (BY_LOADER) {
            String id = BY_LOADER.get(loader);
            return id != null ? id : UNATTRIBUTED;
        }
    }

    /** 当前已登记的数量（诊断用）。 */
    public static int registeredCount() {
        synchronized (BY_LOADER) {
            return BY_LOADER.size();
        }
    }

    /** 已登记的映射快照（诊断用）。 */
    public static Map<String, String> snapshot() {
        synchronized (BY_LOADER) {
            Map<String, String> out = new java.util.LinkedHashMap<>();
            BY_LOADER.forEach((cl, id) -> out.put(
                    cl.getClass().getSimpleName() + "@"
                            + Integer.toHexString(System.identityHashCode(cl)), id));
            return Collections.unmodifiableMap(out);
        }
    }

    /**
     * 清空登记表，仅用于测试。
     *
     * <p>不提供"注销单个"：注销需要精确的 ClassLoader 身份，而 ClassValue
     * 的缓存无法按类失效 —— 半清空的表比整表清空更容易产生错误结论。
     */
    static void clearForTests() {
        synchronized (BY_LOADER) {
            BY_LOADER.clear();
        }
    }
}
