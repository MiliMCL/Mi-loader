package org.loader.runtime.minecraft;

import org.loader.runtime.RuntimeEnvironment;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.service.EventBus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minecraft event bridge.
 * <p>
 * Adapts Minecraft game events to the Runtime's typed event system.
 *
 * <h2>总线归属</h2>
 * 本桥接<b>默认使用平台总线</b>（{@code Runtime.events()}），而不是自建一条。
 * 旧实现每条桥接各开一条总线，结果是：
 * <pre>
 *   发布方: MinecraftEventBridge.eventBus      ← 服务端启动事件
 *   订阅方: ModContext.eventBus                ← Mod 的 ctx.events()
 * </pre>
 * 两边永远不在同一条总线上，Mod 订阅 {@code ServerStartedEvent} 收不到任何东西，
 * 而且没有任何报错。
 *
 * <p>平台总线由 {@code Runtime} 拥有与释放，因此本类<b>不</b>关闭它
 * （见 {@link #close()}）。
 *
 * <h2>Mod 可见性说明</h2>
 * 本类定义的事件类型位于 {@code mili-minecraft-integration} 模块，
 * 而 Mod 只编译 {@code mili-abi} —— <b>Mod 无法引用这些类型</b>。
 * 它们服务于平台内部组件（资源注入、创造栏、性能面板）。
 * Mod 侧的公开事件词汇是：
 * <ul>
 *   <li>{@link org.loader.api.event.TickEvent} —— 每 tick 两次；</li>
 *   <li>{@link org.loader.api.event.ModLifecycleEvent} —— Mod 状态迁移；</li>
 *   <li>{@link org.loader.api.event.PlatformEvent} —— 平台阶段。</li>
 * </ul>
 */
public class MinecraftEventBridge implements org.loader.runtime.kernel.Resource {

    private final String id;
    private final Scope owner;
    private final EventBus eventBus;
    /** 总线是否由本类创建（是则关闭时一并关闭）。 */
    private final boolean ownsBus;

    /**
     * 类型短名 → 拦截器。
     *
     * <p>{@link ConcurrentHashMap} + {@link CopyOnWriteArrayList}：
     * 注册发生在 Mod 初始化（任意线程），遍历发生在事件发布（主线程）。
     * 旧实现是 {@code LinkedHashMap} + 普通 {@code ArrayList}，
     * 注册与发布并发时直接 {@code ConcurrentModificationException}。
     */
    private final Map<String, List<MinecraftEventInterceptor<?>>> interceptors =
            new ConcurrentHashMap<>();

    private volatile boolean closed = false;

    /** 自建总线（独立模式 / 测试）。 */
    public MinecraftEventBridge(Scope owner) {
        this(owner, null);
    }

    /**
     * @param sharedBus 平台总线；为 {@code null} 时自建一条并随本类关闭
     */
    public MinecraftEventBridge(Scope owner, EventBus sharedBus) {
        this.id = "minecraft-event-bridge";
        this.owner = owner;
        if (sharedBus != null) {
            this.eventBus = sharedBus;
            this.ownsBus = false;
        } else {
            this.eventBus = new EventBus(id + ":eventbus", owner);
            owner.registerResource(eventBus);
            this.ownsBus = true;
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Scope owner() {
        return owner;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /** 底层总线 —— 供平台组件直接发布。 */
    public EventBus bus() {
        return eventBus;
    }

    /**
     * Posts a Minecraft event to the event bus.
     */
    public <T extends MinecraftEvent> void post(T event) {
        if (closed) {
            return;
        }

        // Run pre-interceptors by type
        List<MinecraftEventInterceptor<?>> typeInterceptors =
                interceptors.get(event.getClass().getSimpleName());
        if (typeInterceptors != null) {
            for (MinecraftEventInterceptor<?> interceptor : typeInterceptors) {
                try {
                    if (interceptor.phase() == EventPhase.PRE) {
                        @SuppressWarnings("unchecked")
                        MinecraftEventInterceptor<T> typed = (MinecraftEventInterceptor<T>) interceptor;
                        if (!typed.onEvent(event)) {
                            return; // Event cancelled
                        }
                    }
                } catch (Exception e) {
                    // Interceptor exceptions must not break event flow
                }
            }
        }

        eventBus.post(event);

        // Run post-interceptors
        if (typeInterceptors != null) {
            for (MinecraftEventInterceptor<?> interceptor : typeInterceptors) {
                try {
                    if (interceptor.phase() == EventPhase.POST) {
                        @SuppressWarnings("unchecked")
                        MinecraftEventInterceptor<T> typed = (MinecraftEventInterceptor<T>) interceptor;
                        typed.onEvent(event);
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }

    // ── 环境感知的生命周期事件 ──────────────────────────────────────────────

    /**
     * 发布「即将启动」事件。
     *
     * <p>按环境选择 Client 还是 Server 变体。旧实现无论客户端还是服务端
     * 都发 {@code ServerStartingEvent} —— 客户端 Mod 收到「服务器要启动了」
     * 这种语义错误的事件，只能靠猜来判断自己在哪。
     */
    public void postStarting(RuntimeEnvironment env) {
        if (isClient(env)) {
            post(new ClientStartingEvent());
        } else {
            post(new ServerStartingEvent());
        }
    }

    /** 发布「已启动」事件（按环境分派）。 */
    public void postStarted(RuntimeEnvironment env) {
        if (isClient(env)) {
            post(new ClientStartedEvent());
        } else {
            post(new ServerStartedEvent());
        }
    }

    /** 发布「即将停止」事件（按环境分派）。 */
    public void postStopping(RuntimeEnvironment env) {
        if (isClient(env)) {
            post(new ClientStoppingEvent());
        } else {
            post(new ServerStoppingEvent());
        }
    }

    /** 发布「已停止」事件（按环境分派）。 */
    public void postStopped(RuntimeEnvironment env) {
        if (isClient(env)) {
            post(new ClientStoppedEvent());
        } else {
            post(new ServerStoppedEvent());
        }
    }

    private static boolean isClient(RuntimeEnvironment env) {
        return env != null && env.isClient();
    }

    /**
     * Registers an event listener for a specific event type.
     */
    public <T extends MinecraftEvent> void on(Class<T> eventType, MinecraftEventListener<T> listener) {
        eventBus.addListener(eventType, event -> {
            try {
                listener.onMinecraftEvent(eventType.cast(event));
            } catch (Exception e) {
                // Listener exceptions must not break event flow
            }
        });
    }

    /**
     * Registers an interceptor for a specific event type.
     */
    public <T extends MinecraftEvent> void intercept(Class<T> eventType,
                                                    MinecraftEventInterceptor<T> interceptor) {
        interceptors.computeIfAbsent(eventType.getSimpleName(), k -> new CopyOnWriteArrayList<>())
                .add(interceptor);
    }

    /**
     * 清空拦截器表。
     *
     * <p>不关闭共享总线 —— 它属于平台，其他 Mod 还在用。
     * 自建总线才关闭。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        interceptors.clear();
        if (ownsBus) {
            eventBus.close();
        }
    }

    /**
     * Base interface for all Minecraft events.
     */
    public interface MinecraftEvent {
        String type();
    }

    /**
     * Event listener.
     */
    @FunctionalInterface
    public interface MinecraftEventListener<T extends MinecraftEvent> {
        void onMinecraftEvent(T event);
    }

    /**
     * Event interceptor (can cancel or observe events).
     * This is NOT a functional interface because it has two methods (one default).
     */
    public interface MinecraftEventInterceptor<T extends MinecraftEvent> {
        /**
         * Handle the event. Return false to cancel.
         */
        boolean onEvent(T event);

        EventPhase phase();
    }

    /**
     * Event interception phase.
     */
    public enum EventPhase {
        PRE,
        POST
    }

    // Standard event types

    public record ServerStartingEvent() implements MinecraftEvent {
        @Override
        public String type() { return "server.starting"; }
    }

    public record ServerStartedEvent() implements MinecraftEvent {
        @Override
        public String type() { return "server.started"; }
    }

    public record ServerStoppingEvent() implements MinecraftEvent {
        @Override
        public String type() { return "server.stopping"; }
    }

    public record ServerStoppedEvent() implements MinecraftEvent {
        @Override
        public String type() { return "server.stopped"; }
    }

    // Client lifecycle events

    public record ClientStartingEvent() implements MinecraftEvent {
        @Override
        public String type() { return "client.starting"; }
    }

    public record ClientStartedEvent() implements MinecraftEvent {
        @Override
        public String type() { return "client.started"; }
    }

    public record ClientStoppingEvent() implements MinecraftEvent {
        @Override
        public String type() { return "client.stopping"; }
    }

    public record ClientStoppedEvent() implements MinecraftEvent {
        @Override
        public String type() { return "client.stopped"; }
    }

    public record ClientTickEvent(long tick) implements MinecraftEvent {
        @Override
        public String type() { return "client.tick"; }
    }

    public record ClientWorldJoinEvent() implements MinecraftEvent {
        @Override
        public String type() { return "client.world.join"; }
    }

    public record ClientWorldLeaveEvent() implements MinecraftEvent {
        @Override
        public String type() { return "client.world.leave"; }
    }

    public record ClientPlayerJoinEvent() implements MinecraftEvent {
        @Override
        public String type() { return "client.player.join"; }
    }

    public record ClientPlayerLeaveEvent() implements MinecraftEvent {
        @Override
        public String type() { return "client.player.leave"; }
    }

    public record ClientScreenOpenEvent(String screenClass) implements MinecraftEvent {
        @Override
        public String type() { return "client.screen.open"; }
    }

    public record TickEvent(long tick) implements MinecraftEvent {
        @Override
        public String type() { return "tick"; }
    }
}
