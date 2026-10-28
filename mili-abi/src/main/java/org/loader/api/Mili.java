package org.loader.api;

import org.loader.api.VersionInfo;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Primary entry point to the Mili public API.
 * <p>
 * Provides static accessors for runtime version information and
 * mod context discovery.
 *
 * <pre>{@code
 *   // Get the API version
 *   String version = Mili.apiVersion();
 *
 *   // Get the current mod context (from within a mod)
 *   ModContext ctx = Mili.mod(MyMod.class).orElseThrow();
 * }</pre>
 */
public final class Mili {

    private Mili() {
        throw new UnsupportedOperationException("Mili is a facade class and cannot be instantiated.");
    }

    /**
     * Returns the Mili ABI version as an integer (the public contract mods bind against).
     * <p>
     * Mili uses integer-based ABI版本 (1, 2, 3, ...)。Platform version 使用语义化 ("0.1.0")。
     *
     * @return the current ABI version (e.g. 1)
     */
    public static int apiVersion() {
        return VersionInfo.ABI_VERSION;
    }

    /**
     * Returns the platform semantic version string.
     *
     * @return the platform version (e.g. "0.1.0")
     */
    public static String platformVersion() {
        return VersionInfo.CURRENT_VERSION;
    }

    /**
     * Returns the underlying runtime implementation version.
     * This may differ from the platform version during rapid development.
     *
     * @return the runtime version string (e.g. "0.1.0-SNAPSHOT")
     * @deprecated Use {@link #platformVersion()} instead.
     */
    @Deprecated
    public static String runtimeVersion() {
        return VersionInfo.CURRENT_VERSION;
    }

    /**
     * Looks up the {@link ModContext} for a given mod instance.
     * <p>
     * The actual resolution is delegated to a {@link MiliProvider}
     * that is registered by the loader during startup.
     *
     * @param modInstance the mod instance (must implement {@link Mod})
     * @return an {@link Optional} containing the mod's context if available
     */
    public static Optional<ModContext> mod(Object modInstance) {
        if (modInstance == null) {
            return Optional.empty();
        }
        MiliProvider provider = Holder.getProvider();
        if (provider == null) {
            return Optional.empty();
        }
        return provider.findMod(modInstance);
    }

    /**
     * Looks up the {@link ModContext} for a mod by its unique identifier.
     *
     * @param modId the mod's unique identifier
     * @return an {@link Optional} containing the mod's context if available
     */
    public static Optional<ModContext> mod(String modId) {
        if (modId == null || modId.isBlank()) {
            return Optional.empty();
        }
        MiliProvider provider = Holder.getProvider();
        if (provider == null) {
            return Optional.empty();
        }
        return provider.findMod(modId);
    }

    /**
     * Registers a {@link MiliProvider} for mod context resolution.
     * <p>
     * This is called by the loader during startup and should not be
     * called by mods.
     *
     * @param provider the provider implementation
     */
    public static void registerProvider(MiliProvider provider) {
        Holder.setProvider(provider);
    }

    /**
     * 平台总线 —— 全局事件入口，可在<b>任何</b>位置使用，不需要 ModContext。
     *
     * <h2>什么时候用它，什么时候用 {@code context.events()}</h2>
     * 二者是<b>同一条总线</b>（{@code context.events()} 是带归属跟踪的视图）。
     * 区别只在「拿不到 context 的时候」：
     * <ul>
     *   <li>Mod 的 {@code initialize(ctx)} 里 —— 用 {@code ctx.events()}，
     *       监听器会随 Mod 停止自动注销。</li>
     *   <li>静态工具类、{@code @MiliTransformer} 的静态回调、
     *       第三方库的适配层 —— 用本方法。这类位置拿不到 context，
     *       以前只能自己存一个静态引用（然后在热重载后指向已卸载的 Mod）。</li>
     * </ul>
     *
     * <p><b>通过本方法注册的监听器不会随 Mod 自动注销</b> —— 平台无从
     * 知道它属于哪个 Mod。需要自动清理时必须走 {@code ctx.events()}。
     *
     * @return 平台总线；平台尚未启动时返回一个空的只读总线（发布无效果，
     *         订阅会抛 {@link IllegalStateException}，而不是返回 null
     *         让调用方在几百行之后 NPE）
     */
    public static org.loader.api.event.EventBus events() {
        MiliProvider provider = Holder.getProvider();
        if (provider == null) {
            return NoOpEventBus.INSTANCE;
        }
        org.loader.api.event.EventBus bus = provider.events();
        return bus != null ? bus : NoOpEventBus.INSTANCE;
    }

    /**
     * 当前已加载的全部 Mod id（按加载顺序）。
     *
     * <p>供「依赖某个 Mod 才启用某功能」的场景使用。注意返回的是
     * <b>已加载</b>而非<b>已就绪</b>：一个 Mod 被列在这里时它的
     * {@code initialize()} 可能还没返回。要等就绪请订阅
     * {@link org.loader.api.event.ModLifecycleEvent}。
     */
    public static java.util.List<String> modIds() {
        MiliProvider provider = Holder.getProvider();
        return provider == null ? java.util.List.of() : provider.modIds();
    }

    /**
     * 指定 Mod 是否已加载。
     */
    public static boolean isLoaded(String modId) {
        if (modId == null || modId.isBlank()) {
            return false;
        }
        return modIds().contains(modId);
    }

    /**
     * Service provider interface used by the loader to bridge to the runtime.
     * <p>
     * This interface is implemented by the loader and is not intended
     * for mod authors to implement.
     */
    public interface MiliProvider {
        Optional<ModContext> findMod(Object modInstance);
        Optional<ModContext> findMod(String modId);

        /**
         * 平台总线。默认返回 null（旧实现无需改动）。
         */
        default org.loader.api.event.EventBus events() {
            return null;
        }

        /**
         * 已加载的 Mod id。默认返回空列表（旧实现无需改动）。
         */
        default java.util.List<String> modIds() {
            return java.util.List.of();
        }
    }

    /**
     * 平台未启动时的占位总线。
     *
     * <p>发布是<b>无操作</b>而不是抛异常：早期启动阶段的代码（静态初始化块、
     * 类加载器回调）可能合法地发布事件，此时没人订阅是正常的。
     * 而订阅抛异常 —— 因为「订阅成功了但永远收不到事件」是最坏的失效形态。
     */
    private enum NoOpEventBus implements org.loader.api.event.EventBus {
        INSTANCE;

        @Override
        public <T> void addListener(Class<T> eventType, org.loader.api.event.EventListener<T> listener) {
            throw new IllegalStateException(
                    "平台尚未启动，无法订阅事件。请在 Mod 的 initialize(ModContext) 中订阅，"
                            + "或改用 context.events()。");
        }

        @Override
        public <T> void removeListener(Class<T> eventType, org.loader.api.event.EventListener<T> listener) {
            // 从未注册成功，移除是空操作
        }

        @Override
        public <T> org.loader.api.event.Subscription subscribe(
                Class<T> eventType, org.loader.api.event.EventListener<T> listener) {
            addListener(eventType, listener);
            throw new IllegalStateException("unreachable");
        }

        @Override
        public <T> void post(T event) {
            // 平台未启动 —— 无人订阅，丢弃
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<Void> postAsync(T event) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean hasListeners(Class<?> eventType) {
            return false;
        }

        @Override
        public int listenerCount() {
            return 0;
        }

        @Override
        public void close() {
            // 无状态
        }
    }

    /**
     * Internal holder for the provider reference.
     */
    private static final class Holder {
        private static volatile MiliProvider provider;

        static MiliProvider getProvider() {
            return provider;
        }

        static void setProvider(MiliProvider p) {
            provider = p;
        }
    }
}
