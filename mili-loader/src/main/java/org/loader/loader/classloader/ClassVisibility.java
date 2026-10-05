package org.loader.loader.classloader;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClassLoader 可见性契约 —— Mili 平台唯一的包级访问规则来源。
 *
 * <p><b>设计原则</b>：Mod 只能看到平台明确开放的包。任何未被显式开放的包
 * 对 Mod 一律不可见。
 *
 * <p><b>Mod 可见（PARENT_FIRST，交给平台/Minecraft 定义）</b>：
 * <ul>
 *   <li>{@code org.loader.api.**} —— Mili ABI，稳定的 Mod 编程接口</li>
 *   <li>{@code org.loader.runtime.tick.**} 等白名单 Runtime 子包 —— 见下方白名单</li>
 *   <li>{@code org.loader.runtime.RuntimeEnvironment} —— 见 {@link #RUNTIME_ROOT_SPI}</li>
 *   <li>{@code net.minecraft.**} —— Minecraft 本体（由 MinecraftClassLoader 定义）</li>
 * </ul>
 *
 * <p><b>Mod 不可见（HIDDEN，直接拒绝加载）</b>：
 * <ul>
 *   <li>{@code org.loader.loader.**} —— Loader 实现内部</li>
 *   <li>{@code org.loader.runtime.kernel.**} —— Runtime 内部实现</li>
 *   <li>{@code org.loader.runtime.error.**}、{@code org.loader.runtime.util.**} 等</li>
 *   <li>{@code org.loader.runtime.minecraft.**} —— 平台与 MC 的<b>绑定实现</b>，
 *       Mod 只能经 ABI 的 {@code WorldView} / {@code BlockHandle} 用世界</li>
 *   <li>{@code org.loader.installer.**} —— 安装器内部</li>
 *   <li>{@code jdk.internal.**}、{@code sun.**}、{@code com.sun.**} —— JDK 内部 API</li>
 * </ul>
 *
 * <p><b>例外</b>：{@code org.loader.runtime.kernel} 里少数几个 SPI
 * （{@code Resource} / {@code ScopeListener} / {@code LifecycleState}）
 * 仍然可见 —— Mod 必须能实现它们才能使用平台。见 {@link #KERNEL_SPI}。
 *
 * <p><b>JDK 公共 API 特例</b>：{@code java.**} 与 {@code javax.**} 既不隐藏
 * 也不当作 Mod 私有 —— 一律 PARENT_FIRST 委派给平台 CL。理由见
 * {@link #FORBIDDEN_PLATFORM}：把它们判为 FORBIDDEN 会让任何 Mod 类在
 * {@code defineClass} 时抛 {@code NoClassDefFoundError: java/lang/Object}。
 * *
 * <p><b>Mod 私有（SELF_FIRST，Mod 自己定义）</b>：Mod JAR 内的一切，
 * 除非被其他 Mod 显式声明依赖，否则不跨 Mod 可见。
 */
public final class ClassVisibility {

    private ClassVisibility() {
    }

    // ── Mod 可以访问的包前缀（parent-first 委派） ───────────────────────────

    /** Mili ABI：Mod 的稳定编程接口。 */
    private static final List<String> ABI_PACKAGES = List.of(
            "org.loader.api."
    );

    /**
     * Runtime 中允许公开的 API 包。刻意白名单而非包前缀通配 ——
     * kernel / scheduler 实现细节不构成 Mod 契约。
     *
     * <p><b>刻意不含 {@code org.loader.runtime.minecraft.}。</b>
     * 那个包是平台与 Minecraft 的<b>绑定实现</b>（反射、字节码生成、
     * 类查找器），不是给 Mod 编程的接口。Mod 拿到的是 ABI 侧的
     * {@code org.loader.api.world.WorldView} 实例，调用其接口方法由父
     * ClassLoader 完成方法解析，<b>Mod 无需、也不应能加载实现类</b>。
     * 把它留在白名单里会让 Mod 直接 {@code BehaviourDispatch} 绕过封装，
     * 详见 {@link #RUNTIME_INTERNALS}。
     */
    private static final List<String> PUBLIC_RUNTIME_PACKAGES = List.of(
            "org.loader.runtime.tick.",
            "org.loader.runtime.mod.",
            "org.loader.runtime.client.",
            "org.loader.runtime.service."
    );

    /**
     * Runtime 包根下的公开类型白名单。
     *
     * <p><b>为什么需要显式列出来</b>：包根下没有 {@code "."} 前缀可匹配，
     * {@code RuntimeEnvironment} 会落到 {@code SELF_FIRST}。而它是
     * {@code ModContext.environment()} 的返回类型 —— Mod 写
     * {@code RuntimeEnvironment.CLIENT} 时会先在 Mod 自己的 classpath 上
     * 找一遍、失败、再回退 parent。能跑通，但：多一次无谓查找；更糟的是
     * 若某个 Mod jar 里恰好有同名类，就会<b>分裂成两份</b>
     * {@code RuntimeEnvironment}，{@code ==} 判定随之失效。
     *
     * <p>收录判据与 {@link #KERNEL_SPI} 一致：<b>Mod 要用平台，就必须能解析
     * 这个类型</b>。它虽是具体枚举（看着像实现），但出现在 Mod 必然要解析
     * 的公开签名里。
     */
    private static final List<String> RUNTIME_ROOT_SPI = List.of(
            "org.loader.runtime.RuntimeEnvironment"
    );

    /** Minecraft 本体包。Mod 允许访问（这是模组的意义所在）。 */
    private static final List<String> MINECRAFT_PACKAGES = List.of(
            "net.minecraft."
    );

    /** JDK 公共 API。委派给平台 CL —— 只存在一份定义，无版本分裂。 */
    private static final List<String> JDK_PUBLIC = List.of(
            "java.",
            "javax."
    );

    // ── Mod 绝对不可见的包前缀 ─────────────────────────────────────────────

    /** Loader 实现内部：Mod 不得触碰启动、发现、ClassLoader 机制本身。 */
    private static final List<String> LOADER_INTERNALS = List.of(
            "org.loader.loader."
    );

    /**
 * Runtime 内部实现（Scope / Scheduler / Resource 的具体类）。
 *
 * <p><b>例外</b>：{@link #KERNEL_SPI} 里列出的少数类型是 Mod <b>必须</b>
 * 实现的 SPI —— 把它们藏起来等于让 Mod 无法注册自己的资源。这是实测踩到的：
 * 一个 Mod 想 {@code registerResource} 就得 {@code implements Resource}，
 * 而 {@code Resource} 一旦被隐藏，加载它的瞬间就抛
 * {@code NoClassDefFoundError: org/loader/runtime/kernel/Resource}。
 */
    private static final List<String> RUNTIME_INTERNALS = List.of(
            "org.loader.runtime.kernel.",
            "org.loader.runtime.error.",
            "org.loader.runtime.util.",
            "org.loader.runtime.observability.",
            "org.loader.runtime.security.",
            "org.loader.runtime.jvm.",
            "org.loader.runtime.reference.",
            "org.loader.runtime.instance.",
            "org.loader.runtime.scheduler.",
            "org.loader.runtime.transform.",
            // 平台与 Minecraft 的绑定实现。Mod 只能通过 ABI 里的
            // WorldView / BlockHandle / BlockEntityView 使用世界，
            // 碰这一层就等于绕过封装去调反射和字节码生成器。
            //
            // 这里曾经是白名单里的一项，是个真实的契约漏洞：
            // BehaviourDispatch 曾因此对 Mod 可见，Mod 可以直接调它
            // 派发行为、注册生成方块，而那条路径不经过 ModContext
            // 的任何生命周期与合规检查。
            //
            // 隐藏它不构成回归：ABI 与公开 Runtime 包对该包零 import
            // （已用 grep 核验），Mod 拿到的是 ABI 接口实例，接口方法
            // 的实现类由父 CL 解析，Mod 从不需要自己加载它。
            "org.loader.runtime.minecraft."
    );

    /**
     * {@code org.loader.runtime.kernel} 中对 Mod 开放的 SPI 白名单。
     *
     * <p><b>收录判据是「Mod 要用平台，就必须能解析这个类型」，而不是
     * 「它是接口还是类」。</b> 这条判据被 {@code Scope} 印证过：它是具体类，
     * 看着像实现细节，但 {@code ModContext} 的公开签名
     * （{@code scope()} 返回值、{@code getCapability} 参数、
     * {@code Scheduler}/{@code Configuration} 构造入参）全都出现它 ——
     * 隐藏它等于 Mod 一调 {@code ctx.scope().id()} 就
     * {@code NoClassDefFoundError: org/loader/runtime/kernel/Scope}。
     *
     * <ul>
     *   <li>{@code Resource} —— Mod 想注册自己的资源就得 {@code implements Resource}。
     *       同样因为 {@code owner()} 返回 {@code Scope}，它与 {@code Scope}
     *       必须成对开放。</li>
     *   <li>{@code Scope} / {@code LifecycleState} / {@code ScopeListener}
     *       —— 生命周期三件套，Mod 读状态、挂监听都绕不开。</li>
     * </ul>
     *
     * <p>保持隐藏的是<b>纯实现</b>：{@code Runtime}、{@code ScopeRegistry}、
     * {@code ResourceRegistry}、{@code LifecycleManager}、{@code ScopeShutdownException}。
     * 它们不出现在任何 Mod 需要解析的签名里，放开只会让 Mod 能直接操纵
     * 平台内部对象。
     */
    private static final List<String> KERNEL_SPI = List.of(
            "org.loader.runtime.kernel.Resource",
            "org.loader.runtime.kernel.Scope",
            "org.loader.runtime.kernel.ScopeListener",
            "org.loader.runtime.kernel.LifecycleState"
    );

    /** 安装器内部。 */
    private static final List<String> INSTALLER_INTERNALS = List.of(
            "org.loader.installer."
    );

    /**
     * JDK 与未导出内部 API。
     *
     * <p><b>注意：只有 {@code jdk.internal} / {@code sun} / {@code com.sun} 在此列。</b>
     * {@code java.*} 与 {@code javax.*} 刻意<b>不在</b>这里 —— JVM 在定义任何
     * 类时都必须能解析 {@code java.lang.Object}，若把 {@code java.*} 判为
     * FORBIDDEN，则 Mod 的每一个类在 {@code defineClass} 时都会抛
     * {@code NoClassDefFoundError: java/lang/Object}。这不是理论风险：
     * 早期版本确实这么写，结果任何 Mod 都无法被加载。
     *
     * <p>{@code java.*} 走 PARENT_FIRST 委派给平台 CL 依然是安全的 ——
     * 委派意味着只有一份定义，不存在版本分裂。
     */
    private static final List<String> FORBIDDEN_PLATFORM = List.of(
            "jdk.internal.",
            "sun.",
            "com.sun.",
            "com.mojang.",
            "net.minecraftforge.",
            "org.spongepowered.",
            "org.bouncycastle."
    );

    // ── 判定 ───────────────────────────────────────────────────────────────

    /** 是否为 Mod 不可见的包（含 JDK 内部与平台实现细节）。 */
    public static boolean isHidden(String className) {
        String n = normalize(className);
        if (n == null) {
            return true; // null / 非法类名 —— 一律拒绝
        }
        if (isKernelSpi(n)) {
            return false;
        }
        return startsWithAny(n, LOADER_INTERNALS)
                || startsWithAny(n, RUNTIME_INTERNALS)
                || startsWithAny(n, INSTALLER_INTERNALS)
                || startsWithAny(n, FORBIDDEN_PLATFORM);
    }

    private static boolean isKernelSpi(String n) {
        for (String spi : KERNEL_SPI) {
            if (n.equals(spi) || n.startsWith(spi + "$")) {
                return true;
            }
        }
        return false;
    }

    /** 是否为 Mod 可访问的 ABI / Runtime / Minecraft / JDK 公共 API 包。 */
    public static boolean isVisible(String className) {
        String n = normalize(className);
        if (n == null) {
            return false;
        }
        return startsWithAny(n, ABI_PACKAGES)
                || startsWithAny(n, PUBLIC_RUNTIME_PACKAGES)
                || startsWithAny(n, RUNTIME_ROOT_SPI)
                || startsWithAny(n, MINECRAFT_PACKAGES)
                || startsWithAny(n, JDK_PUBLIC)
                || isKernelSpi(n);
    }

    /**
     * 决定该类应由哪一侧加载。
     *
     * @return {@link Resolution#PARENT_FIRST} 委派给平台/Minecraft；
     *         {@link Resolution#SELF_FIRST} 由 Mod 自己定义；
     *         {@link Resolution#FORBIDDEN} 禁止加载。
     */
    public static Resolution resolve(String className) {
        if (isHidden(className)) {
            return Resolution.FORBIDDEN;
        }
        if (isVisible(className)) {
            return Resolution.PARENT_FIRST;
        }
        // 未在白名单内的包：由 Mod 自己定义（典型的 Mod 私有类）。
        return Resolution.SELF_FIRST;
    }

    /** 类解析归属。 */
    public enum Resolution {
        /** 委派给 parent（平台 / Minecraft）加载。 */
        PARENT_FIRST,
        /** Mod 自身 classpath 优先加载。 */
        SELF_FIRST,
        /** 禁止加载 —— 抛出 ClassNotFoundException。 */
        FORBIDDEN
    }

    private static String normalize(String className) {
        if (className == null) return null;
        String n = className.trim();
        if (n.isEmpty()) return null;
        return n;
    }

    private static boolean startsWithAny(String className, List<String> prefixes) {
        for (String p : prefixes) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    // ── 跨 Mod 访问登记表 ──────────────────────────────────────────────────

    private static final Map<String, Set<String>> EXPORTED = new ConcurrentHashMap<>();

    /**
     * 记录 mod A 显式导出（允许被依赖它的 Mod 访问）的包前缀。
     * 只有在 A 声明 B 依赖 A 时，B 才能访问这些前缀。
     */
    public static void export(String modId, List<String> packagePrefixes) {
        EXPORTED.computeIfAbsent(modId, k -> ConcurrentHashMap.newKeySet())
                .addAll(packagePrefixes);
    }

    /** mod A 是否对 mod B 导出了该包。 */
    public static boolean isExportedTo(String ownerModId, String packagePrefix, String consumerModId) {
        if (ownerModId.equals(consumerModId)) {
            return true; // 自己当然可见
        }
        Set<String> prefixes = EXPORTED.get(ownerModId);
        return prefixes != null && prefixes.stream().anyMatch(packagePrefix::startsWith);
    }

    /** 仅供测试清理登记表。 */
    public static void resetRegistryForTesting() {
        EXPORTED.clear();
    }
}