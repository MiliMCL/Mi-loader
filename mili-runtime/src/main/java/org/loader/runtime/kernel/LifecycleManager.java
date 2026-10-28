package org.loader.runtime.kernel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the lifecycle of Runtime components.
 * <p>
 * Ensures deterministic startup and shutdown ordering.
 *
 * <h2>顺序语义</h2>
 * 注册顺序即启动顺序，关闭顺序严格逆序 —— 与资源释放的方向一致
 * （后创建的组件通常依赖先创建的组件）。这个约束是确定性的：
 * 同一个 Scope 集合永远得到同一个顺序，不存在「这次先关 A 下次先关 B」。
 *
 * <h2>并发</h2>
 * 旧实现用 {@code LinkedHashMap} + 只在写方法上加 {@code synchronized}，
 * 而 {@link #scopes()} / {@link #startAll()} / {@link #stopAll()} 是不加锁遍历的。
 * 一个 Mod 在初始化线程里注册 Scope、主线程同时在关停，就会抛
 * {@code ConcurrentModificationException} —— 且抛出点在关闭路径上。
 * 现在用 {@link ConcurrentHashMap} + 插入序队列。
 */
public class LifecycleManager implements Resource {

    private final String id;
    private final Scope owner;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 受管 Scope。
     *
     * <p>{@link ConcurrentHashMap} 本身不保序，因此另存一份插入序，
     * 遍历时按插入序过滤 —— 这样既没有并发修改问题，也保住了确定性顺序。
     */
    private final Map<String, Scope> managedScopes = new ConcurrentHashMap<>();
    private final Queue<String> registrationOrder = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public LifecycleManager(String id, Scope owner) {
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
     * Registers a scope with this lifecycle manager.
     */
    public void registerScope(Scope scope) {
        if (closed.get()) {
            throw new IllegalStateException("LifecycleManager is closed");
        }
        Objects.requireNonNull(scope, "scope");
        if (managedScopes.putIfAbsent(scope.id(), scope) == null) {
            registrationOrder.add(scope.id());
        }
    }

    /**
     * Unregisters a scope (used when a mod is unloaded independently).
     */
    public void unregisterScope(String scopeId) {
        if (scopeId != null && managedScopes.remove(scopeId) != null) {
            registrationOrder.remove(scopeId);
        }
    }

    /**
     * Returns all registered scopes in registration order.
     */
    public Collection<Scope> scopes() {
        List<Scope> ordered = new ArrayList<>(managedScopes.size());
        for (String scopeId : registrationOrder) {
            Scope s = managedScopes.get(scopeId);
            if (s != null) {
                ordered.add(s);
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * Initiates sequential startup of all registered scopes.
     *
     * <p>只推进仍处于 {@link LifecycleState#DISCOVERED} 的 Scope ——
     * 已由创建方推进过的（例如 {@code MinecraftBootstrap} 自己
     * {@code driveToRunning}）不会被重复迁移。
     */
    public void startAll() {
        for (Scope scope : scopes()) {
            if (scope.state() == LifecycleState.DISCOVERED) {
                scope.driveToRunning();
            }
        }
    }

    /**
     * Initiates graceful shutdown in reverse order.
     *
     * <p>单个 Scope 关闭失败不阻断其余 Scope：一个 Mod 的资源没释放干净
     * 不该让整个进程退出流程卡住。失败信息汇总后抛出，调用方可见。
     */
    public void stopAll() {
        List<Scope> reversed = new ArrayList<>(scopes());
        Collections.reverse(reversed);
        ScopeShutdownException errors = null;
        for (Scope scope : reversed) {
            if (scope.isStopped()) {
                continue;
            }
            try {
                scope.shutdown();
            } catch (ScopeShutdownException e) {
                if (errors == null) {
                    errors = new ScopeShutdownException(
                            "多个 Scope 关闭失败（首个: " + scope.id() + "）");
                }
                errors.addSuppressed(e);
            }
        }
        managedScopes.clear();
        registrationOrder.clear();
        if (errors != null) {
            throw errors;
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            stopAll();
        }
    }
}
