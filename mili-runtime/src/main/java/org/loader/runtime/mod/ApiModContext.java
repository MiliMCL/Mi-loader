package org.loader.runtime.mod;

import org.loader.api.Environment;
import org.loader.api.Logger;
import org.loader.api.ModMetadata;
import org.loader.api.event.EventListener;
import org.loader.api.event.Subscription;
import org.loader.api.lifecycle.Lifecycle;
import org.loader.api.lifecycle.LifecycleState;
import org.loader.api.permission.PermissionManager;
import org.loader.api.registry.MinecraftRegistry;
import org.loader.api.scheduler.Scheduler;
import org.loader.api.scheduler.TaskHandle;
import org.loader.api.scheduler.TaskPriority;
import org.loader.api.service.ServiceBroker;
import org.loader.api.tick.TickService;
import org.loader.api.world.WorldView;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.service.EventBus;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link org.loader.api.ModContext} 契约的<b>门面适配层</b>。
 *
 * <h2>为什么需要它</h2>
 * 契约接口自己的 Javadoc 写得很清楚：Mod 面向 {@code org.loader.api.*}
 * 这个稳定窄接口编程，平台内部用自己更宽的 {@link ModContext}。
 * 缺的只是一个把后者翻译成前者的类。
 *
 * <p><b>在此之前</b>，{@code LoaderMain} 直接把 runtime 的
 * {@link ModContext} 传给 Mod，于是
 * {@code getMethod("initialize", runtime.ModContext.class)} 在只声明了
 * {@code initialize(org.loader.api.ModContext)} 的 Mod 上必然抛
 * {@code NoSuchMethodException} —— 契约里那条唯一的入口，从未被真正接上。
 *
 * <h2>视图复用</h2>
 * 所有视图对象在构造时创建一次。这些 getter 会被 Mod 在热路径上访问
 * （每 tick 一次 {@code ctx.events()} / {@code ctx.scheduler()}），
 * 每次 new 一个视图意味着每 tick 若干次分配 —— 一小时游戏下来是
 * 数百万个短命对象，表现为「明明没做什么却每隔几秒掉一帧」。
 *
 * <h2>监听器归属</h2>
 * 事件总线是<b>平台共享</b>的，因此「Mod 停止时注销它注册的监听器」
 * 必须由本类负责：{@link EventBusView} 记录自己注册过的每一对
 * (类型, 转发器)，并作为 Scope 资源在关闭时逐个摘除。
 * 漏掉这一步的后果是：Mod 卸载后监听器还在跑，抓着已释放的对象图 ——
 * 既泄漏内存，又会在下一次事件里抛 {@code NullPointerException}。
 */
public final class ApiModContext implements org.loader.api.ModContext {

    private final ModContext delegate;

    private final ModMetadata metadataView;
    private final Lifecycle lifecycleView;
    private final Scheduler schedulerView;
    private final EventBusView eventsView;
    private final ResourceManagerView resourcesView;
    private final LoggerView loggerView;
    private final ServiceBroker servicesView;
    private final TickService tickView;
    private final PermissionManager permissionsView;

    public ApiModContext(ModContext delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metadataView = new MetadataView(delegate.manifest());
        this.lifecycleView = new LifecycleView(delegate.scope());
        this.schedulerView = new SchedulerView(delegate);
        this.eventsView = new EventBusView(delegate);
        this.resourcesView = new ResourceManagerView(delegate.resources());
        this.loggerView = new LoggerView(delegate.logger());
        this.servicesView = delegate.services();
        this.tickView = delegate.tick();
        this.permissionsView = delegate.permissions();

        // 事件视图必须随 Mod 的 Scope 一起释放，否则共享总线上的
        // 监听器会永远指向一个已卸载的 Mod。
        delegate.scope().registerResource(eventsView);
    }

    /** 底层 runtime 实现 —— 平台内部使用，Mod 不应触碰。 */
    public ModContext delegate() {
        return delegate;
    }

    @Override
    public String modId() {
        return delegate.modId();
    }

    @Override
    public ModMetadata metadata() {
        return metadataView;
    }

    @Override
    public Lifecycle lifecycle() {
        return lifecycleView;
    }

    @Override
    public Scheduler scheduler() {
        return schedulerView;
    }

    @Override
    public org.loader.api.event.EventBus events() {
        return eventsView;
    }

    @Override
    public ServiceBroker services() {
        return servicesView;
    }

    @Override
    public TickService tick() {
        return tickView;
    }

    @Override
    public PermissionManager permissions() {
        return permissionsView;
    }

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
        return resourcesView;
    }

    @Override
    public Logger logger() {
        return loggerView;
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
     * <p>两侧枚举<b>不是</b>一一对应（runtime 有 DISCOVERED 之前的内部态）。
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

    /**
     * 把 runtime 的 Scope 状态变化翻译成契约的 {@link Lifecycle}。
     *
     * <h2>为什么必须维护「监听器 → 包装器」映射</h2>
     * runtime 的 {@code ScopeListener} 是三参（scope, from, to），契约是两参，
     * 因此注册时必然要包一层 lambda。而 {@code Scope.removeListener}
     * 按<b>身份</b>移除 —— 如果每次 {@code addListener} 都新建一个包装器，
     * 调用方拿着自己的监听器去 remove 永远匹配不上，退订静默失效。
     *
     * <p>旧实现的选择是直接抛 {@code UnsupportedOperationException}：
     * 诚实，但让契约里声明的方法<b>永远不可用</b>。现在用映射表实现它。
     */
    private static final class LifecycleView implements Lifecycle {

        private final Scope scope;
        private final Map<LifecycleListener, org.loader.runtime.kernel.ScopeListener> wrappers =
                new ConcurrentHashMap<>();

        LifecycleView(Scope scope) {
            this.scope = scope;
        }

        @Override
        public LifecycleState state() {
            return toApiState(scope.state());
        }

        @Override
        public void addListener(LifecycleListener listener) {
            Objects.requireNonNull(listener, "listener");
            // computeIfAbsent：同一个监听器注册两次只挂一个包装器，
            // 避免「注册两次、只移除一次」留下一个幽灵回调。
            org.loader.runtime.kernel.ScopeListener wrapper = wrappers.computeIfAbsent(
                    listener,
                    l -> (changed, from, to) -> l.onStateChange(toApiState(from), toApiState(to)));
            scope.addListener(wrapper);
        }

        @Override
        public void removeListener(LifecycleListener listener) {
            if (listener == null) {
                return;
            }
            org.loader.runtime.kernel.ScopeListener wrapper = wrappers.remove(listener);
            if (wrapper != null) {
                scope.removeListener(wrapper);
            }
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

        /**
         * 取消本 Mod 提交的全部任务。
         *
         * <p>旧实现是空操作 —— 一个 Mod 调 {@code close()} 期待「我的异步工作停了」，
         * 实际什么都不会发生，任务继续跑在已释放的资源上。
         * 调度器本身仍由 Scope 释放（这里不能 close 它）。
         */
        @Override
        public void close() {
            ctx.scheduler().cancelAll(ctx.scope());
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
     * 平台总线 → 契约视图。
     *
     * <h2>为什么不再是「每个 Mod 一条私有总线」</h2>
     * 私有总线时期，Mod 无法收到任何平台事件（tick、生命周期、平台阶段），
     * 也无法与另一个 Mod 通信 —— 发布方和订阅方永远不在同一条总线上。
     * 这不是配置错误，是架构断链。
     *
     * <p>现在监听器注册在<b>平台总线</b>上，本视图负责：
     * <ul>
     *   <li>把契约的 {@code EventListener} 包成 runtime 的（类型不同）；</li>
     *   <li>记录每一对 (类型, 包装器)，让退订能精确命中；</li>
     *   <li>作为 Scope 资源，在 Mod 停止时把<b>全部</b>注册项摘掉。</li>
     * </ul>
     *
     * <h2>为什么按 (类型, 监听器) 做键</h2>
     * 旧实现只按监听器做键：同一个监听器注册到两个事件类型时，第二次
     * {@code put} 会覆盖第一次的记录 —— 于是退订时只摘掉一个，
     * 另一个永久留在总线上。键里带类型就消除了这个歧义。
     */
    private static final class EventBusView implements org.loader.api.event.EventBus,
            org.loader.runtime.kernel.Resource {

        private final ModContext ctx;
        private final EventBus bus;

        private record Key(Class<?> type, EventListener<?> listener) {
        }

        private final Map<Key, EventBus.EventListener<?>> forwarding = new ConcurrentHashMap<>();
        private volatile boolean closed;

        EventBusView(ModContext ctx) {
            this.ctx = ctx;
            this.bus = ctx.events();
        }

        @Override
        public String id() {
            return ctx.modId() + "-event-view";
        }

        @Override
        public Scope owner() {
            return ctx.scope();
        }

        @Override
        public boolean isClosed() {
            return closed || bus.isClosed();
        }

        @Override
        public <T> void addListener(Class<T> eventType, EventListener<T> listener) {
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(listener, "listener");
            if (closed) {
                throw new IllegalStateException(
                        "Mod '" + ctx.modId() + "' 已停止，不能再注册事件监听器");
            }
            Key key = new Key(eventType, listener);
            EventBus.EventListener<T> forward = listener::onEvent;
            EventBus.EventListener<?> previous = forwarding.put(key, forward);
            if (previous != null) {
                // 同一 (类型, 监听器) 重复注册 —— 先摘掉旧的，避免重复回调
                @SuppressWarnings("unchecked")
                EventBus.EventListener<T> old = (EventBus.EventListener<T>) previous;
                bus.removeListener(eventType, old);
            }
            bus.addListener(eventType, forward);
        }

        @Override
        public <T> void removeListener(Class<T> eventType, EventListener<T> listener) {
            if (eventType == null || listener == null) {
                return;
            }
            EventBus.EventListener<?> forward = forwarding.remove(new Key(eventType, listener));
            if (forward == null) {
                throw new IllegalArgumentException(
                        "Listener was never added through this EventBus: " + listener);
            }
            @SuppressWarnings("unchecked")
            EventBus.EventListener<T> typed = (EventBus.EventListener<T>) forward;
            bus.removeListener(eventType, typed);
        }

        @Override
        public <T> Subscription subscribe(Class<T> eventType, EventListener<T> listener) {
            addListener(eventType, listener);
            return new Subscription() {
                private volatile boolean active = true;

                @Override
                public void unsubscribe() {
                    if (!active) {
                        return;
                    }
                    active = false;
                    EventBusView.this.removeListener(eventType, listener);
                }

                @Override
                public boolean isActive() {
                    return active && !closed;
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
        public boolean hasListeners(Class<?> eventType) {
            return bus.hasListeners(eventType);
        }

        @Override
        public int listenerCount() {
            return bus.listenerCount();
        }

        /**
         * 注销本 Mod 注册的全部监听器。
         *
         * <p>不关闭总线本身 —— 它属于平台，其他 Mod 还在用。
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (Map.Entry<Key, EventBus.EventListener<?>> entry : forwarding.entrySet()) {
                removeTyped(entry.getKey().type(), entry.getValue());
            }
            forwarding.clear();
        }

        @SuppressWarnings("unchecked")
        private <T> void removeTyped(Class<?> type, EventBus.EventListener<?> forward) {
            bus.removeListener((Class<T>) type, (EventBus.EventListener<T>) forward);
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
