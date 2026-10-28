package org.loader.runtime.service;

import org.loader.runtime.kernel.Scope;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Typed event bus for inter-component communication.
 *
 * <h2>为什么不直接用 {@code CopyOnWriteArrayList}</h2>
 * 第一版用的是 {@code Map<Class, CopyOnWriteArrayList>}。功能上没问题，
 * 但 {@code post()} 走的是 {@code list.iterator()} —— 每次发布都分配一个
 * {@code COWIterator}。事件总线是全平台最热的路径之一（每 tick 有
 * tick 事件、渲染事件、自定义事件），按 20 个监听器 × 每秒 60 次估算，
 * 一天游戏下来是几百万个短命对象，全部由 Young GC 买单。
 *
 * <p>现在监听器集合存成<b>不可变数组</b>：{@code post()} 只做一次
 * map 查找 + 一次数组下标遍历，零额外分配。增删监听器时复制数组
 * （监听器数量是个位数到几十，复制远比热路径分配划算）。
 *
 * <h2>类型匹配：精确类 + 超类型</h2>
 * 旧实现只把事件派发给 {@code event.getClass()} 的监听器。后果是
 * {@code subscribe(Object.class, ...)} 或 {@code subscribe(MinecraftEvent.class, ...)}
 * <b>永远不会被调用</b> —— 订阅成功了、日志里没有异常、就是收不到事件。
 * 这是本平台最讨厌的失效形态（静默）。
 *
 * <p>现在派发顺序是：<b>先精确类，再按继承链自下而上</b>（超类 → 接口）。
 * 顺序是确定的：同一个事件在一个监听器数组里不会重复（每个类型各自一个数组），
 * 而「精确类先于基类」让「针对具体类型的处理器」先于「兜底处理器」运行。
 *
 * <p>超类型链按 {@code Class → 链} 缓存在 {@link ClassValue} 里：
 * 每条链只算一次，之后每次发布是一次数组读取。
 *
 * <h2>监听器异常</h2>
 * 监听器抛异常不中断分发（一个 Mod 的 bug 不该让其他 Mod 收不到事件），
 * 但<b>必须留痕</b>：写入 {@link #setErrorSink(ErrorSink)} 注册的接收器，
 * 没有接收器时退化为 JUL 日志。静默吞掉会让「我的监听器没被调用」
 * 变成无法排查的悬案。
 */
public class EventBus implements org.loader.runtime.kernel.Resource {

    private static final Logger LOG = Logger.getLogger("Mili/EventBus");

    /**
     * 类型 → 派发顺序上的所有事件类型（自身 + 全部超类 + 全部接口）。
     *
     * <p>用 {@link ClassValue} 而不是自己拿 {@code ConcurrentHashMap<Class, ...>}
     * 缓存：{@code ClassValue} 的条目与 Class 同生命周期，Mod 卸载后
     * 链会随 Class 一起被回收，不会把 ModClassLoader 钉在堆上 ——
     * 这正是 {@code ClassLoaderLeakTest} 守的那条线。
     */
    private static final ClassValue<Class<?>[]> DISPATCH_CHAIN = new ClassValue<>() {
        @Override
        protected Class<?>[] computeValue(Class<?> type) {
            LinkedHashSet<Class<?>> chain = new LinkedHashSet<>();
            chain.add(type);
            // 先父类（自下而上），再接口 —— 保证「最具体的类型」排在最前。
            //
            // 连 Object.class 一起纳入：`subscribe(Object.class, ...)` 是
            // 「观察一切事件」的合法用法（诊断面板、事件录制器）。
            // 把 Object 排除在外会让这种订阅静默失效 —— 订阅成功、
            // 无异常、永远收不到，正是本类注释里反复强调要消灭的形态。
            for (Class<?> c = type.getSuperclass(); c != null; c = c.getSuperclass()) {
                chain.add(c);
            }
            // 接口用 BFS，保证多接口时的顺序稳定（同一份字节码永远同一顺序）
            Deque<Class<?>> queue = new ArrayDeque<>();
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                queue.addAll(Arrays.asList(c.getInterfaces()));
            }
            while (!queue.isEmpty()) {
                Class<?> iface = queue.poll();
                if (chain.add(iface)) {
                    queue.addAll(Arrays.asList(iface.getInterfaces()));
                }
            }
            return chain.toArray(new Class<?>[0]);
        }
    };

    private final String id;
    private final Scope owner;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<Class<?>, EventListener<?>[]> listeners = new ConcurrentHashMap<>();

    /**
     * 监听器异常的接收器。
     *
     * <p>用 {@link AtomicReference} 而不是普通字段：装配发生在启动期，
     * 分发发生在任意线程。
     */
    private final AtomicReference<ErrorSink> errorSink = new AtomicReference<>();

    /**
     * 异步分发线程池 —— <b>惰性创建</b>。
     *
     * <p>每个 Mod 都有自己的 EventBus。旧实现在构造时就开 2 个线程，
     * 于是「10 个 Mod 一个都没用 postAsync」也会常驻 20 个线程。
     * 线程本身不贵，但每个线程有栈、TLS、调度开销，而且会把
     * {@code liveThreads} 变成一个无法解释的大数字 —— 诊断面板上
     * 看起来像泄漏，实际是过度预分配。
     *
     * <p>现在只有真正调用 {@link #postAsync} 的 Mod 才付这个成本。
     */
    private volatile ExecutorService dispatchExecutor;
    private final Object executorLock = new Object();

    public EventBus(String id, Scope owner) {
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
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
        return closed.get();
    }

    /**
     * 注册监听器异常的接收器；传 {@code null} 恢复为 JUL 日志。
     *
     * <p>平台在装配期把接收器接到统一日志，使「某个 Mod 的监听器一直抛异常」
     * 能被写进 {@code logs/} 而不是只留在内存里。
     */
    public void setErrorSink(ErrorSink sink) {
        errorSink.set(sink);
    }

    /**
     * Registers a listener for a specific event type.
     */
    public <T> void addListener(Class<T> eventType, EventListener<T> listener) {
        if (closed.get()) {
            throw new IllegalStateException("EventBus is closed");
        }
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(listener, "listener");
        listeners.compute(eventType, (k, existing) -> {
            int n = existing == null ? 0 : existing.length;
            EventListener<?>[] next = new EventListener<?>[n + 1];
            if (n > 0) {
                System.arraycopy(existing, 0, next, 0, n);
            }
            next[n] = listener;
            return next;
        });
    }

    /**
     * Removes a listener. No-op if it was never registered.
     *
     * <p>按身份比较（{@code ==}）优先，其次 {@code equals} —— 包装器通常
     * 是 lambda，{@code equals} 就是身份比较；而显式实现 {@code equals}
     * 的监听器（例如把「同一个对象的同一个方法」注册两次）也能被正确移除。
     */
    public <T> void removeListener(Class<T> eventType, EventListener<T> listener) {
        if (eventType == null || listener == null) {
            return;
        }
        listeners.computeIfPresent(eventType, (k, existing) -> {
            int idx = -1;
            for (int i = 0; i < existing.length; i++) {
                if (existing[i] == listener) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                for (int i = 0; i < existing.length; i++) {
                    if (existing[i].equals(listener)) {
                        idx = i;
                        break;
                    }
                }
            }
            if (idx < 0) {
                return existing;
            }
            if (existing.length == 1) {
                return null;
            }
            EventListener<?>[] next = new EventListener<?>[existing.length - 1];
            System.arraycopy(existing, 0, next, 0, idx);
            System.arraycopy(existing, idx + 1, next, idx, existing.length - idx - 1);
            return next;
        });
    }

    /**
     * Posts an event synchronously. Zero allocation on the dispatch path
     * (the type chain is cached per class).
     */
    public <T> void post(T event) {
        if (closed.get() || event == null) {
            return;
        }
        Class<?>[] chain = DISPATCH_CHAIN.get(event.getClass());
        for (int c = 0; c < chain.length; c++) {
            EventListener<?>[] targets = listeners.get(chain[c]);
            if (targets == null || targets.length == 0) {
                continue;
            }
            dispatch(targets, event);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void dispatch(EventListener<?>[] targets, T event) {
        for (int i = 0; i < targets.length; i++) {
            try {
                ((EventListener<T>) targets[i]).onEvent(event);
            } catch (Throwable e) {
                // 监听器异常不得中断分发 —— 与契约层 EventBus 的语义一致
                reportListenerFailure(targets[i], event, e);
            }
        }
    }

    private void reportListenerFailure(EventListener<?> listener, Object event, Throwable error) {
        ErrorSink sink = errorSink.get();
        if (sink != null) {
            try {
                sink.onListenerError(id, listener, event, error);
                return;
            } catch (Throwable ignored) {
                // 接收器自己坏了 —— 退回日志，绝不让它再次抛出
            }
        }
        LOG.log(Level.WARNING, "事件监听器抛异常（bus=" + id
                + ", event=" + event.getClass().getName()
                + ", listener=" + listener.getClass().getName() + "）", error);
    }

    /**
     * Posts an event asynchronously.
     */
    public <T> CompletableFuture<Void> postAsync(T event) {
        if (closed.get()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> post(event), executor());
    }

    /**
     * 是否至少有一个监听器会收到该类型的事件（含超类型监听器）。
     *
     * <p>存在的理由：转换器与桥接层需要在「没人订阅」时跳过整段工作。
     * 旧实现只能自己遍历内部表，或者干脆每次都构造事件对象 ——
     * 后者在每 tick 路径上是一次无谓分配。
     */
    public boolean hasListeners(Class<?> eventType) {
        if (eventType == null) {
            return false;
        }
        for (Class<?> c : DISPATCH_CHAIN.get(eventType)) {
            EventListener<?>[] arr = listeners.get(c);
            if (arr != null && arr.length > 0) {
                return true;
            }
        }
        return false;
    }

    private ExecutorService executor() {
        ExecutorService current = dispatchExecutor;
        if (current != null) {
            return current;
        }
        synchronized (executorLock) {
            if (dispatchExecutor == null) {
                dispatchExecutor = Executors.newFixedThreadPool(2, r -> {
                    Thread t = new Thread(r, "runtime-eventbus-" + id);
                    t.setDaemon(true);
                    return t;
                });
            }
            return dispatchExecutor;
        }
    }

    /** 已注册的事件类型数量（诊断用）。 */
    public int eventTypeCount() {
        return listeners.size();
    }

    /** 全部类型上的监听器总数（诊断用）。 */
    public int listenerCount() {
        int n = 0;
        for (EventListener<?>[] arr : listeners.values()) {
            n += arr.length;
        }
        return n;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            ExecutorService executor = dispatchExecutor;
            if (executor != null) {
                executor.shutdownNow();
            }
            listeners.clear();
        }
    }

    /**
     * 监听器异常接收器。
     *
     * <p>平台实现把它接到统一日志（{@code PlatformLog}），于是
     * 「某个 Mod 的监听器每 tick 抛一次」能在日志里被看见 ——
     * 而不是安静地损失掉。
     */
    @FunctionalInterface
    public interface ErrorSink {
        void onListenerError(String busId, EventListener<?> listener, Object event, Throwable error);
    }

    /**
     * Event listener functional interface.
     */
    @FunctionalInterface
    public interface EventListener<T> {
        void onEvent(T event);
    }
}
