package org.loader.runtime.service;

import org.loader.runtime.kernel.Scope;
import org.loader.runtime.kernel.ScopeShutdownException;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * <p>并发语义不变：写入走 {@code compute}/{@code computeIfPresent}
 * （按 key 原子），读取拿到的数组一旦发布就不会被修改 —— 这正是
 * {@code CopyOnWriteArrayList} 保证的那一条，只是把「列表」换成了
 * 「数组」，省掉了迭代器。
 *
 * <p>也刻意<b>不</b>用 {@code ClassValue}：{@code ClassValue} 适合
 * 「一个类一个永久缓存」的场景，而这里每个类都要能随时增删监听器，
 * 用 ClassValue 反而要处理失效，得不偿失。
 */
public class EventBus implements org.loader.runtime.kernel.Resource {

    private static final EventListener<?>[] EMPTY = new EventListener<?>[0];

    private final String id;
    private final Scope owner;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<Class<?>, EventListener<?>[]> listeners = new ConcurrentHashMap<>();

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
     */
    public <T> void removeListener(Class<T> eventType, EventListener<T> listener) {
        if (eventType == null || listener == null) {
            return;
        }
        listeners.computeIfPresent(eventType, (k, existing) -> {
            int idx = -1;
            for (int i = 0; i < existing.length; i++) {
                if (existing[i] == listener || existing[i].equals(listener)) {
                    idx = i;
                    break;
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
     * Posts an event synchronously. Zero allocation on the dispatch path.
     */
    @SuppressWarnings("unchecked")
    public <T> void post(T event) {
        if (closed.get() || event == null) {
            return;
        }
        EventListener<?>[] targets = listeners.get(event.getClass());
        if (targets == null || targets.length == 0) {
            return;
        }
        for (int i = 0; i < targets.length; i++) {
            try {
                ((EventListener<T>) targets[i]).onEvent(event);
            } catch (Exception e) {
                // 监听器异常不得中断分发 —— 与契约层 EventBus 的语义一致
            }
        }
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
                executor.shutdown();
            }
            listeners.clear();
        }
    }

    /**
     * Event listener functional interface.
     */
    @FunctionalInterface
    public interface EventListener<T> {
        void onEvent(T event);
    }
}
