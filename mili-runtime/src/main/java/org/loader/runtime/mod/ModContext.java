package org.loader.runtime.mod;

import org.loader.runtime.kernel.*;
import org.loader.runtime.RuntimeEnvironment;
import org.loader.runtime.scheduler.Scheduler;
import org.loader.runtime.scheduler.TaskHandle;
import org.loader.runtime.scheduler.TaskPriority;
import org.loader.runtime.service.*;
import org.loader.runtime.util.ModLogger;

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
 * ├── events       (EventBus)
 * ├── registry     (typed Registry)
 * ├── configuration
 * ├── resources    (ResourceManager)
 * ├── capabilities
 * ├── logger
 * └── environment  (RuntimeEnvironment)
 * </pre>
 */
public class ModContext {

    private final Mod mod;
    private final ModLogger logger;

    // Lazily initialized SDK services
    private volatile Scheduler modScheduler;
    private volatile EventBus modEventBus;
    private volatile Registry<Object> modRegistry;
    private volatile ResourceManager modResourceManager;
    private volatile RuntimeEnvironment environment;

    public ModContext(Mod mod) {
        this.mod = mod;
        this.logger = new ModLogger(mod.id(), mod.scope());
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
        return scope().getCapability(capabilityType).filter(CapabilityToken::isActive).map(CapabilityToken::get);
    }

    /**
     * Gets all active capabilities for this mod.
     */
    public Collection<CapabilityToken<?>> getAllCapabilities() {
        return List.of();
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
                    modScheduler = new Scheduler(mod.id() + "-scheduler", scope());
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
     * Returns an EventBus bound to this mod's scope.
     * Events are automatically cleaned up when the mod stops.
     *
     * @return a scope-bound EventBus instance
     */
    public EventBus events() {
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
     * Provides path-based resource loading with security checks.
     *
     * @return a scope-bound ResourceManager instance
     */
    public ResourceManager resources() {
        if (modResourceManager == null) {
            synchronized (this) {
                if (modResourceManager == null) {
                    modResourceManager = new ResourceManager(mod.id() + "-resources", scope(), java.nio.file.Path.of("mods/" + mod.id()));
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
     * @return the RuntimeEnvironment, defaults to DEDICATED_SERVER
     */
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
            environment = RuntimeEnvironment.DEDICATED_SERVER;
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
