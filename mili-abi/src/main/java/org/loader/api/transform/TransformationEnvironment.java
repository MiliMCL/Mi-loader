package org.loader.api.transform;

/**
 * Pipeline 级不可变环境 —— 一次类加载过程中所有转换器共享。
 *
 * <p><b>为什么要与 {@link TransformationContext} 分开</b>：需求中
 * {@code InjectionContext} 要携带 {@code className} / {@code methodName} /
 * {@code tickContext} / {@code executionContext} —— 这些是<b>运行时</b>数据，
 * 与转换过程无关。若把它们塞进转换期上下文，会导致：
 * <ul>
 *   <li>转换上下文随 tick 变化，不再可缓存；</li>
 *   <li>{@code InjectionContext} 无法为未来的 Region Scheduler 独立复用。</li>
 * </ul>
 *
 * <p>分离后：转换期数据（{@link TransformationContext}）在一次类加载内恒定，
 * 运行时数据（{@code InjectionContext}）在每次回调时新建。
 */
public final class TransformationEnvironment {

    private final ClassLoader classLoader;
    private final String minecraftVersion;
    private final RuntimeEnvironmentValue runtimeEnvironment;
    private final String platformVersion;

    /**
     * Minecraft 运行环境的抽象。
     *
     * <p>ABI 不能引用 {@code org.loader.api.Environment} 之外的实现类型，
     * 因此这里用平台无关的枚举，由 runtime 侧适配。
     */
    public enum RuntimeEnvironmentValue {
        CLIENT,
        DEDICATED_SERVER,
        UNKNOWN
    }

    public TransformationEnvironment(
            ClassLoader classLoader,
            String minecraftVersion,
            RuntimeEnvironmentValue runtimeEnvironment,
            String platformVersion) {
        this.classLoader = classLoader;
        this.minecraftVersion = minecraftVersion;
        this.runtimeEnvironment = runtimeEnvironment != null
                ? runtimeEnvironment : RuntimeEnvironmentValue.UNKNOWN;
        this.platformVersion = platformVersion;
    }

    /** 定义目标类的 ClassLoader —— 通常是 MinecraftClassLoader。 */
    public ClassLoader classLoader() {
        return classLoader;
    }

    /** 实际加载的 Minecraft 版本（严格字符串，如 {@code "26.2"}）。 */
    public String minecraftVersion() {
        return minecraftVersion;
    }

    public RuntimeEnvironmentValue runtimeEnvironment() {
        return runtimeEnvironment;
    }

    public String platformVersion() {
        return platformVersion;
    }

    /**
     * 本环境是否为给定版本 —— 转换器应在转换前自查。
     *
     * <p>注意：版本匹配<b>不等于</b>目标存在。Mojang 快照仍可能改名，
     * 因此目标解析失败必须独立处理，见
     * {@link TransformationTargetNotFoundException}。
     */
    public boolean isMinecraftVersion(String version) {
        return minecraftVersion.equals(version);
    }
}