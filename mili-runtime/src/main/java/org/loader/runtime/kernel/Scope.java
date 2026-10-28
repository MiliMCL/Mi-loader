package org.loader.runtime.kernel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A Scope is a lifecycle boundary.
 * <p>
 * A Scope may contain child scopes, tasks, resources, event subscriptions,
 * network handles, capabilities, and services.
 * Closing a Scope initiates coordinated cleanup.
 *
 * <h2>关闭协议（本类最容易被写错的地方）</h2>
 * <ol>
 *   <li><b>幂等</b>：{@link #shutdown()} 用 CAS 抢「关闭权」，抢不到的调用方
 *       立即返回。旧实现只比较状态，两个线程同时看到 RUNNING 会各自跑一遍
 *       {@code stopChildren()} 与 {@code releaseResources()} —— 资源被关两次，
 *       第二次通常抛「已关闭」异常，把第一次的成功掩盖成一次失败。</li>
 *   <li><b>子 Scope 先关</b>：父 Scope 的资源可能被子 Scope 使用，顺序反了会
 *       让子 Scope 的关闭撞上已释放的资源。</li>
 *   <li><b>失败不吞</b>：单个资源关闭失败会汇总成 {@link ScopeShutdownException}
 *       抛出，同时其余资源仍然被关闭。静默忽略会让「某个 Mod 的线程一直活着」
 *       这种问题永远查不到。</li>
 *   <li><b>监听器异常隔离但不静默</b>：监听器抛异常不得中断状态迁移，但必须
 *       记录 —— 否则「Mod 的生命周期回调没被调用」会变成无法解释的现象。</li>
 * </ol>
 */
public class Scope implements Resource {

    private static final Logger LOG = Logger.getLogger("Mili/Scope");

    private final String id;
    private final Scope parent;
    private final AtomicReference<LifecycleState> state = new AtomicReference<>(LifecycleState.DISCOVERED);

    /**
     * 子 Scope 表。
     *
     * <p>用 {@link ConcurrentHashMap} 而非 {@code LinkedHashMap + synchronized}：
     * 旧实现只在 {@code createChild}/{@code removeChild} 上加锁，而
     * {@link #children()} 与 {@code stopChildren()} 是不加锁遍历的 ——
     * 一个 Mod 在初始化里创建子 Scope 的同时主线程正在关闭，就会抛
     * {@code ConcurrentModificationException}，且抛出点在关闭路径上，
     * 表现为「退出游戏时偶发崩溃」。
     *
     * <p>键是 childId：同一父 Scope 下 id 唯一（重复创建会覆盖，
     * 这与「一个 Mod 一个 Scope」的语义一致）。
     */
    private final Map<String, Scope> childScopes = new ConcurrentHashMap<>();

    private final List<Resource> ownedResources = new CopyOnWriteArrayList<>();
    private final Map<Class<?>, CapabilityToken<?>> capabilities = new ConcurrentHashMap<>();
    private final List<ScopeListener> listeners = new CopyOnWriteArrayList<>();

    /** 关闭权。CAS 成功者执行清理，其余调用方直接返回。 */
    private final AtomicBoolean shutdownStarted = new AtomicBoolean(false);
    private volatile boolean closed = false;

    public Scope(String id, Scope parent) {
        this.id = Objects.requireNonNull(id, "scope id must not be null");
        this.parent = parent;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Scope owner() {
        return parent;
    }

    /**
     * Returns the parent scope, or null if this is the root.
     */
    public Scope parent() {
        return parent;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    public boolean isStopped() {
        LifecycleState s = state.get();
        return s == LifecycleState.STOPPED || s == LifecycleState.FAILED || closed;
    }

    /**
     * Returns the current lifecycle state.
     */
    public LifecycleState state() {
        return state.get();
    }

    /**
     * Transitions to the next lifecycle state.
     *
     * @throws IllegalStateException if transition is invalid
     */
    public void transitionTo(LifecycleState target) {
        LifecycleState current;
        do {
            current = state.get();
            if (!current.canTransitionTo(target)) {
                throw new IllegalStateException(
                        "Invalid transition from " + current + " to " + target + " in scope " + id);
            }
        } while (!state.compareAndSet(current, target));
        notifyListeners(current, target);
    }

    /**
     * 把 Scope 从 {@link LifecycleState#DISCOVERED} 一路推进到 {@link LifecycleState#RUNNING}。
     *
     * <p>存在的理由：中间四个状态（RESOLVED / LOADED / INITIALIZED / REGISTERED）
     * 对 Mod 作者没有可观察意义，却必须逐个迁移才能满足状态机。让每个调用方
     * 各写四行 {@code transitionTo} 的结果是有人漏写一行，然后 Mod 永远停在
     * {@code LOADED}，{@code lifecycle().state().allowsNewWork()} 恒为 true
     * 而 {@code RUNNING} 事件永不触发。
     *
     * <p>已经是 RUNNING 时是空操作（幂等）。
     *
     * @return 本次实际推进到的状态
     */
    public LifecycleState driveToRunning() {
        for (LifecycleState next : new LifecycleState[]{
                LifecycleState.RESOLVED,
                LifecycleState.LOADED,
                LifecycleState.INITIALIZED,
                LifecycleState.REGISTERED,
                LifecycleState.RUNNING}) {
            LifecycleState current = state.get();
            if (current == next) {
                continue;
            }
            if (current.canTransitionTo(next)) {
                transitionTo(next);
            } else if (current.ordinal() > next.ordinal()) {
                // 已经越过该状态（例如直接 set 过），跳过
                continue;
            } else {
                throw new IllegalStateException(
                        "无法推进到 " + next + "：当前 " + current + "（scope " + id + "）");
            }
        }
        return state.get();
    }

    /**
     * Adds a child scope.
     */
    public Scope createChild(String childId) {
        checkNotStopped();
        Scope child = new Scope(childId, this);
        childScopes.put(childId, child);
        return child;
    }

    /**
     * Removes a child scope.
     */
    public void removeChild(String childId) {
        childScopes.remove(childId);
    }

    /**
     * Registers a resource owned by this scope.
     */
    public <T extends Resource> T registerResource(T resource) {
        checkNotStopped();
        ownedResources.add(resource);
        return resource;
    }

    /**
     * Returns an immutable view of resources owned by this scope.
     */
    public List<Resource> getResources() {
        return List.copyOf(ownedResources);
    }

    /**
     * Returns the first resource owned by this scope matching the given id.
     */
    public Optional<Resource> getResourceById(String resourceId) {
        return ownedResources.stream()
                .filter(r -> r.id().equals(resourceId))
                .findFirst();
    }

    /**
     * Returns the first resource owned by this scope that is an instance of the given type.
     *
     * <p>供桥接层取「平台装进来的某个实现」（例如 TickEngine）而不必
     * 让调用方知道它注册时的 id。
     */
    public <T> Optional<T> getResource(Class<T> type) {
        for (Resource r : ownedResources) {
            if (type.isInstance(r)) {
                return Optional.of(type.cast(r));
            }
        }
        return Optional.empty();
    }

    /**
     * Registers a capability for this scope.
     */
    public <T> CapabilityToken<T> grantCapability(Class<T> capabilityType, T implementation) {
        checkNotStopped();
        CapabilityTokenImpl<T> token = new CapabilityTokenImpl<>(
                capabilityType, this, implementation, new AtomicBoolean(true));
        capabilities.put(capabilityType, token);
        return token;
    }

    /**
     * Returns a capability granted <b>directly to this scope</b>.
     *
     * <p>不向上查找父 Scope：能力的作用域语义是「谁被授予谁才有」。
     * 需要跨 Mod 发现能力时走
     * {@link org.loader.runtime.kernel.CapabilityBroker}，那是另一套语义
     * （提供方注册、消费方发现），把它塞进 Scope 会让「Mod 自己的权限」
     * 与「别的 Mod 提供的服务」混为一谈。
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<CapabilityToken<T>> getCapability(Class<T> capabilityType) {
        return Optional.ofNullable((CapabilityToken<T>) capabilities.get(capabilityType))
                .filter(CapabilityToken::isActive);
    }

    /**
     * Returns every capability currently granted to this scope.
     *
     * <p>只返回仍然 active 的 token；已被 revoke 的不会出现。
     */
    public Collection<CapabilityToken<?>> capabilities() {
        List<CapabilityToken<?>> active = new ArrayList<>(capabilities.size());
        for (CapabilityToken<?> token : capabilities.values()) {
            if (token.isActive()) {
                active.add(token);
            }
        }
        return List.copyOf(active);
    }

    /**
     * Adds a scope lifecycle listener.
     */
    public void addListener(ScopeListener listener) {
        if (listener == null) {
            return;
        }
        listeners.add(listener);
    }

    /**
     * Removes a scope lifecycle listener.
     *
     * <p>按<b>身份</b>移除。调用方必须传当初 {@code addListener} 的同一个实例 ——
     * 这也是 {@code Lifecycle} 契约视图必须自己维护
     * 「Mod 监听器 → 包装器」映射的原因（见 {@code ApiModContext}）。
     */
    public void removeListener(ScopeListener listener) {
        if (listener == null) {
            return;
        }
        listeners.remove(listener);
    }

    /**
     * Returns all child scopes (snapshot).
     */
    public Collection<Scope> children() {
        return List.copyOf(childScopes.values());
    }

    @Override
    public void close() {
        shutdown();
    }

    /**
     * Initiates graceful shutdown of this scope.
     * Can be called from any non-terminal state. Idempotent and thread-safe.
     */
    public void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            // 已有关闭在进行或已完成 —— 直接返回，不重复释放
            return;
        }
        LifecycleState prev = state.getAndUpdate(current ->
                current.isTerminal() ? current : LifecycleState.STOPPING);
        if (prev != LifecycleState.STOPPING) {
            notifyListeners(prev, LifecycleState.STOPPING);
        }

        ScopeShutdownException errors = null;
        try {
            errors = stopChildren();
        } catch (RuntimeException e) {
            errors = new ScopeShutdownException("Failed to stop child scopes: " + id, e);
        }

        revokeCapabilities();

        ScopeShutdownException resourceErrors = releaseResources();
        if (resourceErrors != null) {
            if (errors == null) {
                errors = resourceErrors;
            } else {
                errors.addSuppressed(resourceErrors);
            }
        }

        LifecycleState terminal = errors == null
                ? LifecycleState.STOPPED
                : LifecycleState.FAILED;
        state.set(terminal);
        closed = true;
        notifyListeners(LifecycleState.STOPPING, terminal);

        if (errors != null) {
            throw errors;
        }
    }

    private ScopeShutdownException stopChildren() {
        ScopeShutdownException errors = null;
        for (Scope child : children()) {
            try {
                child.shutdown();
            } catch (Exception e) {
                child.state.set(LifecycleState.FAILED);
                if (errors == null) {
                    errors = new ScopeShutdownException(
                            "Child scope shutdown failed: " + child.id());
                }
                errors.addSuppressed(e);
            }
        }
        childScopes.clear();
        return errors;
    }

    private void revokeCapabilities() {
        for (CapabilityToken<?> token : capabilities.values()) {
            try {
                token.revoke();
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "revoke 失败: " + id, e);
            }
        }
        capabilities.clear();
    }

    private ScopeShutdownException releaseResources() {
        List<Exception> errors = new ArrayList<>();
        // Close in reverse registration order
        List<Resource> reversed = new ArrayList<>(ownedResources);
        Collections.reverse(reversed);
        for (Resource res : reversed) {
            try {
                res.close();
            } catch (Exception e) {
                errors.add(e);
            }
        }
        ownedResources.clear();
        if (!errors.isEmpty()) {
            ScopeShutdownException ex = new ScopeShutdownException(
                    "Resource cleanup errors in scope: " + id);
            errors.forEach(ex::addSuppressed);
            return ex;
        }
        return null;
    }

    private void notifyListeners(LifecycleState from, LifecycleState to) {
        for (ScopeListener listener : listeners) {
            try {
                listener.onStateChange(this, from, to);
            } catch (Throwable e) {
                // 监听器异常不得中断状态迁移，但必须留痕：
                // 静默吞掉会让「Mod 的生命周期回调没生效」无从解释。
                LOG.log(Level.WARNING,
                        "生命周期监听器抛异常（scope=" + id + ", " + from + " -> " + to + "）", e);
            }
        }
    }

    private void checkNotStopped() {
        if (isStopped() || shutdownStarted.get()) {
            throw new IllegalStateException("Scope is stopped: " + id);
        }
    }

    @Override
    public String toString() {
        return "Scope[" + id + ", state=" + state.get() + "]";
    }
}
