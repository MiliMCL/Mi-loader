package org.loader.runtime.mod;

import org.loader.api.Environment;
import org.loader.api.Logger;
import org.loader.api.ModMetadata;
import org.loader.api.lifecycle.Lifecycle;
import org.loader.api.lifecycle.LifecycleState;
import org.loader.api.registry.MinecraftRegistry;
import org.loader.api.scheduler.Scheduler;
import org.loader.api.scheduler.TaskHandle;
import org.loader.api.scheduler.TaskPriority;
import org.loader.api.world.WorldView;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@link org.loader.api.ModContext} 契约的<b>门面适配层</b>。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>契约接口自己的 Javadoc 写得很清楚：
 * <blockquote>
 * This interface delegates to the internal runtime implementation
 * ({@code org.loader.runtime.mod.ModContext}).
 * </blockquote>
 *
 * <p>也就是说<b>两套类型并存是契约设计的本意</b>，不是历史遗留的混乱：
 * Mod 面向 {@code org.loader.api.*} 这个稳定窄接口编程，
 * 平台内部用自己更宽的 {@link ModContext}（含 Scope、配置、能力等）。
 * 缺的只是一个把后者翻译成前者的类。
 *
 * <p><b>在此之前</b>，{@code LoaderMain} 直接把 runtime 的
 * {@link ModContext} 传给 Mod，于是
 * {@code getMethod("initialize", runtime.ModContext.class)} 在只声明了
 * {@code initialize(org.loader.api.ModContext)} 的 Mod 上必然抛
 * {@code NoSuchMethodException}。
 * 也就是说：<b>契约里那条唯一的入口，从未被真正接上</b>。
 *
 * <h2>映射原则</h2>
 *
 * <p>凡是契约有、runtime 没有对应物的（如 {@code registry()}），
 * 一律<b>如实反映「未接通」</b>而不是编一个假实现 ——
 * 返回一个空 Map 的注册表会让 Mod 以为注册成功了。
 *
 * @see ModContext runtime 侧的内部实现
 */
public final class ApiModContext implements org.loader.api.ModContext {

    private final ModContext delegate;

    public ApiModContext(ModContext delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    /** 底层 runtime 实现 —— 平台内部使用，Mod 不应触碰。 */
    public ModContext delegate() {
        return delegate;
    }

    @Override
    public String modId() {
        return delegate.modId();
    }

    /**
     * 把 runtime 的 {@link ModManifest} 投影成契约的只读视图。
     *
     * <p>刻意做成视图而非副本：清单在加载后不再变，投影没有过期风险；
     * 而每 Mod 存一份副本会在 Mod 数量上去后变成纯浪费。
     */
    @Override
    public ModMetadata metadata() {
        return new MetadataView(delegate.manifest());
    }

    @Override
    public Lifecycle lifecycle() {
        return new LifecycleView(delegate.scope());
    }

    @Override
    public Scheduler scheduler() {
        return new SchedulerView(delegate);
    }

    @Override
    public org.loader.api.event.EventBus events() {
        return new EventBusView(delegate);
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>当前如实返回 {@code null}</b>：runtime 侧尚未把 Minecraft 注册表
     * 接到这条路径上。与其给一个「能调但不写进游戏」的注册表，
     * 不如让 Mod 在首次调用处就撞到明确的空值 —— 契约本身也要求
     * Mod 对可能为 null 的能力做判空。
     *
     * <p>接通方式见 {@code mili-minecraft-integration} 的
     * {@code ApiMinecraftRegistry}：由平台在注册表可用时装配进
     * runtime 的 ModContext，而不是由 Mod 自己去 new。
     */
    @Override
    public MinecraftRegistry registry() {
        return delegate.apiRegistry();
    }

    @Override
    public WorldView world() {
        return delegate.apiWorld();
    }

    @Override
    public org.loader.api.resource.ResourceManager resources() {
        return new ResourceManagerView(delegate.resources());
    }

    @Override
    public Logger logger() {
        return new LoggerView(delegate.logger());
    }

    @Override
    public Environment environment() {
        return switch (delegate.environment()) {
            case CLIENT -> Environment.CLIENT;
            case SERVER -> Environment.SERVER;
            case DEDICATED_SERVER -> Environment.DEDICATED_SERVER;
        };
    }

    @Override
    public boolean isActive() {
        return delegate.isActive();
    }

    @Override
    public <T> Optional<T> getCapability(Class<T> capabilityType) {
        return delegate.getCapability(capabilityType);
    }

    @Override
    public String toString() {
        return "ApiModContext[" + modId() + "]";
    }

    // ── 视图实现 ────────────────────────────────────────────────────────────

    /** {@link ModManifest} → {@link ModMetadata} 只读投影。 */
    private record MetadataView(ModManifest manifest) implements ModMetadata {
        @Override
        public String id() {
            return manifest.id();
        }

        @Override
        public String name() {
            return manifest.name();
        }

        @Override
        public String version() {
            return manifest.version();
        }

        @Override
        public String author() {
            return manifest.author();
        }

        @Override
        public String description() {
            return manifest.description();
        }

        @Override
        public List<org.loader.api.ModDependency> dependencies() {
            return manifest.dependencies().stream()
                    .map(d -> (org.loader.api.ModDependency) new DependencyView(
                            d.modId(), d.versionRange(), d.required()))
                    .toList();
        }

        @Override
        public String entrypoint() {
            return manifest.entrypoint();
        }
    }

    private record DependencyView(String modId, String versionRange, boolean required)
            implements org.loader.api.ModDependency {
    }

    /**
     * runtime {@link org.loader.runtime.kernel.LifecycleState} →
     * 契约 {@link LifecycleState} 的映射。
     *
     * <p>两侧枚举<b>不是</b>一一对应（runtime 有 CREATED，契约没有；
     * 契约有 INITIALIZED/REGISTERED，runtime 的对应名不同）。
     * 用显式 switch 而不是 {@code name()} 硬转，是为了让「改名即编译失败」——
     * 枚举加值时 switch 会漏编译，字符串硬转则静默走 else 分支。
     */
    private static LifecycleState toApiState(org.loader.runtime.kernel.LifecycleState state) {
        return switch (state) {
            case DISCOVERED -> LifecycleState.DISCOVERED;
            case RESOLVED -> LifecycleState.RESOLVED;
            case LOADED -> LifecycleState.LOADED;
            case INITIALIZED -> LifecycleState.INITIALIZED;
            case REGISTERED -> LifecycleState.REGISTERED;
            case RUNNING -> LifecycleState.RUNNING;
            case STOPPING -> LifecycleState.STOPPING;
            case STOPPED -> LifecycleState.STOPPED;
            case FAILED -> LifecycleState.FAILED;
        };
    }

    /** 把 runtime 的 Scope 状态变化翻译成契约的 {@link Lifecycle}。 */
    private static final class LifecycleView implements Lifecycle {

        private final org.loader.runtime.kernel.Scope scope;

        LifecycleView(org.loader.runtime.kernel.Scope scope) {
            this.scope = scope;
        }

        @Override
        public LifecycleState state() {
            return toApiState(scope.state());
        }

        @Override
        public void addListener(LifecycleListener listener) {
            Objects.requireNonNull(listener, "listener");
            // runtime 的 ScopeListener 是三参（scope, from, to），
            // 契约只要两参 —— 丢弃 scope 即可，Mod 关心的是自己的 Scope。
            scope.addListener((changed, from, to) ->
                    listener.onStateChange(toApiState(from), toApiState(to)));
        }

        @Override
        public void removeListener(LifecycleListener listener) {
            // runtime 的 Scope 只支持 removeListener(ScopeListener)，
            // 而 ScopeListener 没有身份可比，无法精确移除。
            // 如实说明限制，好过提供一个「看起来删了其实没删」的方法 ——
            // 那会让 Mod 以为自己解绑干净了。
            throw new UnsupportedOperationException(
                    "removeListener is not supported: the runtime Scope identifies"
                            + " listeners by position, not by identity, so removing one"
                            + " specific listener cannot be done reliably."
                            + "\n  Listeners are detached automatically when the mod stops;"
                            + " use isActive() to guard long-lived listeners instead.");
        }
    }

    /** runtime {@link org.loader.runtime.scheduler.Scheduler} → 契约视图。 */
    private static final class SchedulerView implements Scheduler {

        private final ModContext ctx;

        SchedulerView(ModContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public TaskHandle submit(Runnable work) {
            return new TaskHandleView(ctx.submit(work));
        }

        @Override
        public TaskHandle submit(Runnable work, TaskPriority priority) {
            return new TaskHandleView(
                    ctx.submit(work, org.loader.runtime.scheduler.TaskPriority.valueOf(priority.name())));
        }

        @Override
        public TaskHandle submitRepeating(Runnable work, long delayMs, long periodMs,
                                          TaskPriority priority) {
            return new TaskHandleView(ctx.scheduler().submitRepeating(
                    ctx.scope(), work, delayMs, periodMs,
                    org.loader.runtime.scheduler.TaskPriority.valueOf(priority.name())));
        }

        @Override
        public boolean cancel(TaskHandle handle) {
            return handle instanceof TaskHandleView v
                    && ctx.scheduler().cancel(v.delegate());
        }

        @Override
        public void close() {
            // 不关闭 Mod 的 scheduler —— 它是 scope 的资源，由 scope 生命周期
            // 统一释放。Mod 调close() 只应停止提交新任务。
        }
    }

    private static final class TaskHandleView implements TaskHandle {

        private final org.loader.runtime.scheduler.TaskHandle delegate;

        TaskHandleView(org.loader.runtime.scheduler.TaskHandle delegate) {
            this.delegate = delegate;
        }

        org.loader.runtime.scheduler.TaskHandle delegate() {
            return delegate;
        }

        @Override
        public String taskId() {
            return delegate.taskId();
        }

        @Override
        public org.loader.api.scheduler.TaskState state() {
            return org.loader.api.scheduler.TaskState.valueOf(delegate.state().name());
        }

        @Override
        public TaskPriority priority() {
            return TaskPriority.valueOf(delegate.priority().name());
        }

        @Override
        public boolean cancel() {
            return delegate.cancel();
        }

        @Override
        public CompletableFuture<Void> future() {
            return delegate.future();
        }

        @Override
        public void await() {
            try {
                delegate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while awaiting task", e);
            }
        }
    }

    /**
     * runtime {@link org.loader.runtime.service.EventBus} → 契约视图。
     *
     * <p>契约的 {@code EventListener<T>} 与 runtime 的同名嵌套接口是两个类型，
     * 这里做一次 lambda 转发；契约的 {@code Subscription} 在 runtime 侧没有
     * 对应物，用一个本地句柄实现（runtime 的 removeListener 也不支持按身份移除，
     * 语义上本来就不是订阅式）。
     */
    private static final class EventBusView implements org.loader.api.event.EventBus {

        private final ModContext ctx;
        private final org.loader.runtime.service.EventBus bus;

        /**
         * 契约监听器 → runtime 监听器的映射表。
         *
         * <p><b>为什么必须有这张表</b>：runtime 的
         * {@code removeListener(type, listener)} 按<b>身份</b>移除
         * （{@code List.remove(Object)}），而契约要求
         * {@code addListener(Class, EventListener)} 传进来的那个对象之后能
         * 被 {@code removeListener} / {@code unsubscribe} 精确撤掉。
         *
         * <p>但 runtime 侧存的是转发用的包装 lambda，与 Mod 传入的
         * {@code EventListener} 不是同一个对象，直接转发 remove 会永远匹配不上 ——
         * 退订静默失效，Mod 停掉后监听器还在跑。
         *
         * <p>所以在注册时把「契约监听器 → runtime 监听器」记下来，
         * 退订时用表里的实例去移除，才能真正对上。
         */
        private final java.util.Map<org.loader.api.event.EventListener<?>,
                org.loader.runtime.service.EventBus.EventListener<?>> forwarding =
                new java.util.concurrent.ConcurrentHashMap<>();

        EventBusView(ModContext ctx) {
            this.ctx = ctx;
            this.bus = ctx.events();
        }

        @Override
        public <T> void addListener(Class<T> eventType,
                                    org.loader.api.event.EventListener<T> listener) {
            org.loader.runtime.service.EventBus.EventListener<T> forward = listener::onEvent;
            forwarding.put(listener, forward);
            bus.addListener(eventType, forward);
        }

        @Override
        public <T> void removeListener(Class<T> eventType,
                                       org.loader.api.event.EventListener<T> listener) {
            org.loader.runtime.service.EventBus.EventListener<?> forward =
                    forwarding.remove(listener);
            if (forward == null) {
                throw new IllegalArgumentException(
                        "Listener was never added through this EventBus: " + listener);
            }
            @SuppressWarnings("unchecked")
            org.loader.runtime.service.EventBus.EventListener<T> typed =
                    (org.loader.runtime.service.EventBus.EventListener<T>) forward;
            bus.removeListener(eventType, typed);
        }

        @Override
        public <T> org.loader.api.event.Subscription subscribe(
                Class<T> eventType, org.loader.api.event.EventListener<T> listener) {
            addListener(eventType, listener);
            return new org.loader.api.event.Subscription() {
                private volatile boolean active = true;

                @Override
                public void unsubscribe() {
                    if (!active) {
                        return;
                    }
                    active = false;
                    // 通过上面的映射表退订真正注册的转发实例，
                    // 而不是另造一个 no-op 去 remove（那样只是移走一个从未注册的对象，
                    // 原监听器会继续跑 —— 看起来退订成功了，实际没有）。
                    EventBusView.this.removeListener(eventType, listener);
                }

                @Override
                public boolean isActive() {
                    return active;
                }
            };
        }

        @Override
        public <T> void post(T event) {
            bus.post(event);
        }

        @Override
        public <T> CompletableFuture<Void> postAsync(T event) {
            return bus.postAsync(event);
        }

        @Override
        public void close() {
            // 同 SchedulerView.close()：生命周期归 scope。
            // 这里不清 forwarding —— 它随 EventBusView 一起被 GC。
        }
    }

    /** runtime {@link org.loader.runtime.service.ResourceManager} → 契约视图。 */
    private static final class ResourceManagerView
            implements org.loader.api.resource.ResourceManager {

        private final org.loader.runtime.service.ResourceManager delegate;

        ResourceManagerView(org.loader.runtime.service.ResourceManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public java.nio.file.Path basePath() {
            return delegate.basePath();
        }

        @Override
        public java.nio.file.Path resolve(String resourcePath) {
            return delegate.resolve(resourcePath);
        }

        @Override
        public byte[] readAllBytes(String resourcePath) throws java.io.IOException {
            return delegate.readAllBytes(resourcePath);
        }

        @Override
        public String readString(String resourcePath) throws java.io.IOException {
            return delegate.readString(resourcePath);
        }

        @Override
        public boolean exists(String resourcePath) {
            return delegate.exists(resourcePath);
        }
    }

    /** {@link org.loader.runtime.util.ModLogger} → 契约 {@link Logger}。 */
    private static final class LoggerView implements Logger {

        private final org.loader.runtime.util.ModLogger delegate;

        LoggerView(org.loader.runtime.util.ModLogger delegate) {
            this.delegate = delegate;
        }

        @Override
        public void info(String message) {
            delegate.info(message);
        }

        @Override
        public void warning(String message) {
            delegate.warning(message);
        }

        @Override
        public void severe(String message) {
            delegate.severe(message);
        }

        @Override
        public void severe(String message, Throwable throwable) {
            delegate.severe(message, throwable);
        }

        @Override
        public void fine(String message) {
            delegate.fine(message);
        }

        @Override
        public void finer(String message) {
            delegate.finer(message);
        }

        @Override
        public void finest(String message) {
            delegate.finest(message);
        }
    }
}