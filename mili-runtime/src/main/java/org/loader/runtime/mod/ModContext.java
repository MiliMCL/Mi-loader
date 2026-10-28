package org.loader.runtime.mod;

import org.loader.api.service.ServiceBroker;
import org.loader.api.tick.TickService;
import org.loader.runtime.RuntimeEnvironment;
import org.loader.runtime.kernel.*;
// 显式导入：kernel.* 通配符会让 `Runtime` 在 java.lang.Runtime 与
// kernel.Runtime 之间产生歧义（"对Runtime的引用不明确"）。
import org.loader.runtime.kernel.Runtime;
import org.loader.runtime.scheduler.Scheduler;
import org.loader.runtime.scheduler.TaskHandle;
import org.loader.runtime.scheduler.TaskPriority;
import org.loader.runtime.service.*;
import org.loader.runtime.tick.NoTickService;
import org.loader.runtime.tick.TickEngineHolder;
import org.loader.runtime.tick.TickServiceImpl;
import org.loader.runtime.util.ModLogger;

import java.nio.file.Path;
import java.util.*;

/**
 * ModContext is the primary SDK interface between a Mod and the Runtime.
 * <p>
 * It provides access to the Mod's scope, capabilities, services, lifecycle,
 * and execution resources — all following MOD_SDK.md requirements:
 * <pre>
 * ModContext
 * ├── lifecycle    (scope / isActive)
 * ├── scheduler    (submit tasks)
 * ├── events       (platform EventBus)
 * ├── services     (inter-mod ServiceBroker)
 * ├── tick         (tick-counted scheduling)
 * ├── permissions  (permission queries)
 * ├── registry     (Minecraft content registration)
 * ├── resources    (ResourceManager)
 * ├── storage      (persistent KV)
 * ├── capabilities
 * ├── logger
 * └── environment  (RuntimeEnvironment)
 * </pre>
 *
 * <h2>两种构造方式</h2>
 * <ul>
 *   <li>{@link #ModContext(Mod)} —— <b>独立模式</b>：每个 Mod 拿一条自己的
 *       事件总线、没有 tick 源。测试与纯 JVM 场景用这个。</li>
 *   <li>{@link #ModContext(Mod, Runtime, TickEngine, Path)} —— <b>装配模式</b>：
 *       接平台总线、服务代理、权限、tick 引擎与数据目录。Loader 用这个。</li>
 * </ul>
 * 两者行为差异只在「跨 Mod 可见性」，不涉及任何单 Mod 语义 ——
 * 因此同一个 Mod 在两种模式下都能跑通自己的逻辑。
 */
public class ModContext {

    private final Mod mod;
    private final ModLogger logger;

    /** 装配模式下的平台组件；独立模式下为 {@code null}。 */
    private final Runtime runtime;
    /** tick 引擎迟绑定容器；独立模式下为 {@code null}。 */
    private final TickEngineHolder tickHolder;
    private final Path dataDir;

    // Lazily initialized SDK services
    private volatile Scheduler modScheduler;
    private volatile EventBus modEventBus;
    private volatile Registry<Object> modRegistry;
    private volatile ResourceManager modResourceManager;
    private volatile ServiceBroker modServiceBroker;
    private volatile TickService modTickService;
    private volatile org.loader.api.permission.PermissionManager modPermissions;
    private volatile RuntimeEnvironment environment;

    public ModContext(Mod mod) {
        this(mod, null, null, null);
    }

    /**
     * 装配模式构造。
     *
     * @param runtime    平台运行时；{@code null} 表示独立模式
     * @param tickEngine tick 引擎；{@code null} 表示本环境没有 tick 源
     * @param dataDir    Mod 的可写数据根目录；{@code null} 时用
     *                   {@code config/mili/data/<modId>}
     */
    public ModContext(Mod mod, Runtime runtime, TickEngineHolder tickHolder, Path dataDir) {
        this.mod = Objects.requireNonNull(mod, "mod");
        this.logger = new ModLogger(mod.id(), mod.scope());
        this.runtime = runtime;
        this.tickHolder = tickHolder != null
                ? tickHolder
                : (runtime != null ? runtime.tickEngine() : null);
        this.dataDir = dataDir;
    }

    /**
     * 便捷构造：从 Runtime 取 tick 容器。
     */
    public ModContext(Mod mod, Runtime runtime, Path dataDir) {
        this(mod, runtime, runtime != null ? runtime.tickEngine() : null, dataDir);
    }

    /**
     * Returns the mod's unique identifier.
     */
    public String modId() {
        return mod.id();
    }

    /**
     * Returns the mod manifest.
     */
    public ModManifest manifest() {
        return mod.manifest();
    }

    /**
     * Returns the mod's scope.
     */
    public Scope scope() {
        return mod.scope();
    }

    /**
     * Returns the mod's logger.
     */
    public ModLogger logger() {
        return logger;
    }

    /**
     * Returns the mod's classloader.
     */
    public ClassLoader classLoader() {
        return mod.classLoader();
    }

    /**
     * Checks if the mod's scope is still active.
     */
    public boolean isActive() {
        return !scope().isStopped();
    }

    /**
     * Gets a capability if the mod has been granted one.
     */
    public <T> Optional<T> getCapability(Class<T> capabilityType) {
        return scope().getCapability(capabilityType)
                .filter(CapabilityToken::isActive)
                .map(CapabilityToken::get);
    }

    /**
     * Gets all active capabilities for this mod.
     *
     * <p>旧实现返回 {@code List.of()} —— 一个永远为空的 API。
     * 一个 Mod 无法知道平台给了自己什么，诊断面板也无法列出能力，
     * 而调用方看到的是「合法但空」的结果，不会怀疑实现。
     * 现在如实返回 Scope 上仍然有效的 token。
     */
    public Collection<CapabilityToken<?>> getAllCapabilities() {
        return scope().capabilities();
    }

    /**
     * Registers a config for this mod.
     */
    public Configuration createConfig(String configId) {
        Configuration config = new Configuration(mod.id() + ":" + configId, scope());
        scope().registerResource(config);
        return config;
    }

    /**
     * Gets a configuration if it exists.
     */
    public Optional<Configuration> getConfig(String configId) {
        String fullId = mod.id() + ":" + configId;
        return scope().getResources().stream()
                .filter(r -> r instanceof Configuration && r.id().equals(fullId))
                .map(r -> (Configuration) r)
                .findFirst();
    }

    /**
     * Returns whether this mod is a large mod with modules.
     */
    public boolean isLargeMod() {
        return mod.isLargeMod();
    }

    /**
     * Returns child module scopes.
     */
    public List<Scope> modules() {
        return mod.modules();
    }

    // ── SDK Expansion per MOD_SDK.md ──

    /**
     * Returns a scheduler bound to this mod's scope.
     * Mods should use this to submit async work instead of creating their own threads.
     *
     * @return a scope-bound Scheduler instance
     */
    public Scheduler scheduler() {
        if (modScheduler == null) {
            synchronized (this) {
                if (modScheduler == null) {
                    // 每个 Mod 的调度器只给 2 个线程，而不是 CPU 核数。
                    //
                    // 旧默认值是 availableProcessors()：10 个 Mod 就是
                    // 80 个池线程（每个都有栈与调度开销），在 8G 内存的
                    // 机器上会把 liveThreads 推到一个看起来像泄漏的数字。
                    // Mod 的异步工作通常是 IO / 短任务，2 个线程足够；
                    // 真要并行计算，应该由平台统一调度，而不是每个 Mod
                    // 各开一个与核数相等的池。
                    modScheduler = new Scheduler(mod.id() + "-scheduler", scope(),
                            Math.min(2, java.lang.Runtime.getRuntime().availableProcessors()));
                    scope().registerResource(modScheduler);
                }
            }
        }
        return modScheduler;
    }

    /**
     * Submits a task to the mod's scheduler.
     * Equivalent to {@code scheduler().submit(scope(), work, TaskPriority.NORMAL)}.
     */
    public TaskHandle submit(Runnable work) {
        return scheduler().submit(scope(), work, TaskPriority.NORMAL);
    }

    /**
     * Submits a task with specified priority.
     */
    public TaskHandle submit(Runnable work, TaskPriority priority) {
        return scheduler().submit(scope(), work, priority);
    }

    /**
     * Returns the event bus bound to this mod.
     *
     * <p><b>装配模式下返回平台总线</b>（而不是每个 Mod 一条私有总线）：
     * 这是 Mod 之间、Mod 与平台之间能互相收到事件的唯一前提。
     * 私有总线时期，Mod 订阅 {@code TickEvent} 永远收不到 ——
     * 因为发布方在另一条总线上。
     *
     * <p>隔离性由<b>类型</b>保证：一个 Mod 发布的 {@code MyEvent}
     * 只会到达订阅了 {@code MyEvent}（或其超类型）的监听器。
     * 这与 Fabric / Forge 的共享事件总线语义一致。
     *
     * <p>装配模式下<b>不</b>把总线注册为本 Mod 的资源 —— 它属于平台，
     * 由平台在关闭时释放。Mod 的监听器由 {@code ApiModContext}
     * 的视图负责在 Mod 停止时注销。
     *
     * @return 事件总线
     */
    public EventBus events() {
        if (runtime != null) {
            return runtime.events();
        }
        if (modEventBus == null) {
            synchronized (this) {
                if (modEventBus == null) {
                    modEventBus = new EventBus(mod.id() + "-events", scope());
                    scope().registerResource(modEventBus);
                }
            }
        }
        return modEventBus;
    }

    /**
     * 跨 Mod 服务代理。
     *
     * <p>装配模式下绑定到本 Mod 的 Scope（发布归属、停止自动失效）；
     * 独立模式下给一个只属于本 Mod 的空代理（发现永远为空）。
     */
    public ServiceBroker services() {
        if (modServiceBroker == null) {
            synchronized (this) {
                if (modServiceBroker == null) {
                    modServiceBroker = runtime != null
                            ? runtime.services().forScope(scope())
                            : new ServiceBrokerImpl(scope());
                }
            }
        }
        return modServiceBroker;
    }

    /**
     * Tick 服务 —— 按 tick 计数调度。
     *
     * <p>没有 tick 源时（专用服务器未启动世界、纯 JVM 测试）返回一个
     * {@code isAvailable() == false} 的实现：提交会抛
     * {@link IllegalStateException} 并说明原因，而不是静默排队等一个
     * 永远不会到来的 tick。
     */
    public TickService tick() {
        if (modTickService == null) {
            synchronized (this) {
                if (modTickService == null) {
                    modTickService = tickHolder != null
                            ? new TickServiceImpl(tickHolder, scope())
                            : new NoTickService(scope());
                }
            }
        }
        return modTickService;
    }

    /**
     * 权限查询视图（绑定到本 Mod 的 Scope）。
     *
     * <p>独立模式下没有任何策略，全部返回 {@code false} ——
     * 与「拒绝即默认」一致。
     */
    public org.loader.api.permission.PermissionManager permissions() {
        if (modPermissions == null) {
            synchronized (this) {
                if (modPermissions == null) {
                    modPermissions = runtime != null
                            ? runtime.permissionManager().viewFor(scope())
                            : new org.loader.api.permission.PermissionManager() {
                        @Override
                        public void registerPolicy(String permission,
                                                   org.loader.api.permission.PermissionManager.PermissionPolicy policy) {
                            throw new UnsupportedOperationException("独立模式下没有权限系统");
                        }

                        @Override
                        public boolean hasPermission(String permission) {
                            return false;
                        }

                        @Override
                        public boolean isClosed() {
                            return scope().isStopped();
                        }
                    };
                }
            }
        }
        return modPermissions;
    }

    /**
     * Returns a typed Registry bound to this mod's scope.
     * Used for publishing and discovering services/configs within the mod.
     *
     * @return a scope-bound Registry instance
     */
    public Registry<Object> registry() {
        if (modRegistry == null) {
            synchronized (this) {
                if (modRegistry == null) {
                    modRegistry = new Registry<>(mod.id() + "-registry", scope(), mod.id() + "-registry");
                    scope().registerResource(modRegistry);
                }
            }
        }
        return modRegistry;
    }

    /**
     * Returns a ResourceManager bound to this mod's scope.
     *
     * <p>来源顺序：<b>Mod JAR 优先，可写数据目录兜底</b>。
     * 旧实现只给了一个相对路径 {@code mods/<modId>} —— 在分发包布局下
     * 根本不存在，于是 Mod 读自己打进 JAR 的贴图也会失败。
     */
    public ResourceManager resources() {
        if (modResourceManager == null) {
            synchronized (this) {
                if (modResourceManager == null) {
                    Path base = dataDir != null
                            ? dataDir.resolve(mod.id())
                            : Path.of("config", "mili", "data", mod.id());
                    modResourceManager = new ResourceManager(
                            mod.id() + "-resources", scope(), base, mod.classLoader());
                    scope().registerResource(modResourceManager);
                }
            }
        }
        return modResourceManager;
    }

    /**
     * Returns the runtime environment (SERVER, CLIENT, DEDICATED_SERVER).
     * Walks up the scope tree to find the environment flag registered by MinecraftBootstrap.
     *
     * @return the RuntimeEnvironment
     */
    /**
     * 显式注入运行环境。
     *
     * <h2>为什么必须由 loader 注入</h2>
     * 旧实现的兜底是 {@code DEDICATED_SERVER}，而 {@code ModContext}
     * 构造时并没有人设置环境 —— 于是<b>每一个客户端 Mod 都被告知
     * 「你在专用服务器上」</b>，全部客户端功能被 Mod 自己跳过。
     * 症状是「Mod 加载成功、日志正常、但游戏里什么都没有」，
     * 排查方向会被彻底带偏。
     *
     * <p>环境是装载方（loader）才知道的事实：它刚刚决定加载客户端
     * 还是服务端 JAR。把它作为构造后的一次显式设置，比让 runtime
     * 猜一个默认值可靠得多。
     *
     * @throws IllegalStateException 已经取过环境值后再设置
     */
    public void setEnvironment(RuntimeEnvironment env) {
        if (env == null) {
            return;
        }
        RuntimeEnvironment current = environment;
        if (current != null && current != env) {
            throw new IllegalStateException(
                    "运行环境已经确定为 " + current + "，不能再改为 " + env);
        }
        environment = env;
    }

    public RuntimeEnvironment environment() {
        if (environment == null) {
            // Walk up the scope chain looking for a registered RuntimeEnvironment capability
            Scope current = scope();
            while (current != null) {
                var envCapability = current.getCapability(RuntimeEnvironment.class);
                if (envCapability.isPresent() && envCapability.get().isActive()) {
                    environment = envCapability.get().get();
                    return environment;
                }
                current = current.parent();
            }
            // 兜底：客户端是绝大多数运行场景，且两种误判的代价不对称 ——
            // 误判为客户端时，服务器上的 UI 调用会得到明确的
            // Unavailable 异常；误判为服务器时，客户端 Mod 会静默跳过
            // 全部客户端功能，玩家只看到「装了 Mod 但什么也没有」。
            environment = RuntimeEnvironment.CLIENT;
        }
        return environment;
    }

    // ── ABI 契约桥接 ──────────────────────────────────────────────────────

    /**
     * 供 {@link ApiModContext} 使用的 Minecraft 注册表，未接通时为 {@code null}。
     *
     * <p><b>为什么是「由外部装进来」而不是「这里 new 一个」</b>：
     * runtime 模块不能依赖 Minecraft（见 ADR 0005），真正能写进
     * {@code BuiltInRegistries} 的实现在 mili-minecraft-integration。
     * 这里只留一个挂载点，由平台在注册表可用时装配。
     *
     * <p>用 volatile 而非 final：装配发生在 Mod 加载之前、
     * ModContext 构造之后，两者不是同一个时刻。
     */
    private volatile org.loader.api.registry.MinecraftRegistry apiRegistry;

    private volatile org.loader.api.world.WorldView apiWorld;

    /**
     * 装入 Minecraft 注册表契约实现。由平台在启动流程中调用。
     *
     * @throws IllegalStateException 已装入过 —— 说明装配顺序错了，
     *         而静默覆盖会让两个 Mod 拿到不同视图，比早失败难查得多
     */
    public void bindApiRegistry(org.loader.api.registry.MinecraftRegistry registry) {
        if (this.apiRegistry != null) {
            throw new IllegalStateException(
                    "API registry already bound for mod '" + modId() + "'");
        }
        this.apiRegistry = registry;
    }

    /** 装入世界视图；世界未加载时可传 null，之后再补。 */
    public void bindApiWorld(org.loader.api.world.WorldView world) {
        this.apiWorld = world;
    }

    /**
     * Minecraft 注册表契约视图；未装入时返回 {@code null}。
     *
     * <p>注意与 {@link #registry()} 区分：后者是 runtime 内部的
     * 服务/配置注册表（{@code Registry<Object>}），与 Minecraft 无关。
     * 二者同名纯属历史巧合，但用途完全不同，不能合并。
     */
    public org.loader.api.registry.MinecraftRegistry apiRegistry() {
        return apiRegistry;
    }

    /** 世界视图；未装入时返回 {@code null}（契约要求 Mod 自行判空）。 */
    public org.loader.api.world.WorldView apiWorld() {
        return apiWorld;
    }

    @Override
    public String toString() {
        return "ModContext[" + mod.id() + ", state=" + scope().state() + "]";
    }
}
