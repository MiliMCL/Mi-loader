package org.loader.runtime.service;

import org.loader.api.service.ServiceBroker;
import org.loader.api.service.ServiceUnavailableException;
import org.loader.runtime.kernel.Resource;
import org.loader.runtime.kernel.Scope;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ServiceBroker} 的运行时实现。
 *
 * <h2>为什么冲突必须显式失败</h2>
 * 两个 Mod 都 {@code provide(EconomyApi.class, ...)} 时，如果后者静默覆盖前者，
 * 症状是「我的 Mod 明明提供了服务，调用方却调到了别人的实现」——
 * 双方都看不到任何错误，直到行为差异在某个边缘场景里爆出来。
 * 这里在<b>发布时</b>就抛 {@link IllegalStateException}，并给出双方 modId。
 *
 * <h2>归属与生命周期</h2>
 * 每次发布都记下「哪个 Scope 发布的」。{@link #discover} 在提供方 Scope
 * 停止后返回空并顺手清理 —— 不需要消费方做任何事，也不需要在关闭路径上
 * 遍历所有消费方。
 *
 * <h2>为什么本类同时是 Resource</h2>
 * 它持有全部服务实例的强引用。作为 {@code rootScope} 的资源，
 * Scope 关闭时会被清空 —— 否则这些实例会把 ModClassLoader 钉在堆上，
 * 正是 {@code ClassLoaderLeakTest} 守的那类泄漏。
 */
public final class ServiceBrokerImpl implements ServiceBroker, Resource {

    private static final Logger LOG = Logger.getLogger("Mili/ServiceBroker");

    private final Scope owner;

    /**
     * 服务类型 → 条目。
     *
     * <p>用 {@link ConcurrentHashMap} 而非 {@code LinkedHashMap + synchronized}：
     * {@code discover} 在事件回调里每 tick 都可能被调用，加锁会让
     * 一个慢调用拖住所有 Mod。
     */
    private final Map<Class<?>, Entry<?>> entries = new ConcurrentHashMap<>();

    private volatile boolean closed = false;

    public ServiceBrokerImpl(Scope owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    @Override
    public String id() {
        return "service-broker";
    }

    @Override
    public Scope owner() {
        return owner;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public <T> void provide(Class<T> serviceType, T implementation) {
        Objects.requireNonNull(serviceType, "serviceType");
        Objects.requireNonNull(implementation, "implementation");
        if (closed) {
            throw new IllegalStateException("ServiceBroker 已关闭");
        }
        if (!serviceType.isInstance(implementation)) {
            throw new IllegalArgumentException(
                    "实现 " + implementation.getClass().getName()
                            + " 不是 " + serviceType.getName() + " 的实例");
        }
        put(serviceType, implementation, owner);
    }

    @Override
    public boolean withdraw(Class<?> serviceType) {
        if (serviceType == null) {
            return false;
        }
        Entry<?> entry = entries.get(serviceType);
        if (entry == null || entry.scope() != owner) {
            // 只允许撤回自己发布的 —— 否则任何 Mod 都能拆掉别人的服务
            return false;
        }
        return entries.remove(serviceType, entry);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> discover(Class<T> serviceType) {
        if (serviceType == null || closed) {
            return Optional.empty();
        }
        Entry<?> entry = entries.get(serviceType);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.scope().isStopped()) {
            entries.remove(serviceType, entry);
            return Optional.empty();
        }
        return Optional.of((T) entry.implementation());
    }

    @Override
    public Set<Class<?>> publishedTypes() {
        pruneStopped();
        return Set.copyOf(entries.keySet());
    }

    @Override
    public Set<Class<?>> providedByMe() {
        Set<Class<?>> mine = new LinkedHashSet<>();
        for (Map.Entry<Class<?>, Entry<?>> e : entries.entrySet()) {
            if (e.getValue().scope() == owner) {
                mine.add(e.getKey());
            }
        }
        return Collections.unmodifiableSet(mine);
    }

    /**
     * 为某个 Mod 的 Scope 构造一个「视角」—— 发布时归属该 Scope，
     * 而不是归属 Runtime 的 rootScope。
     *
     * <p>没有这一步的话，所有 Mod 都从 rootScope 发布，
     * {@link #providedByMe()} 与 {@link #withdraw} 就失去意义。
     */
    public ServiceBroker forScope(Scope modScope) {
        return new ScopedView(modScope);
    }

    /**
     * 统一的发布路径：同 Scope 覆盖，跨 Scope 冲突。
     *
     * <p>「同 Scope 覆盖」不是宽容，是必要 —— Mod 在热重载或重新初始化时
     * 会再次发布同一接口，那必须替换掉自己上一次的实现，而不是报冲突。
     */
    private <T> void put(Class<T> serviceType, T implementation, Scope publisher) {
        Entry<T> candidate = new Entry<>(serviceType, implementation, publisher);
        Entry<?> existing = entries.putIfAbsent(serviceType, candidate);
        if (existing == null) {
            return;
        }
        if (existing.scope() == publisher) {
            // 替换自己的实现
            entries.put(serviceType, candidate);
            return;
        }
        throw new IllegalStateException(
                "服务 " + serviceType.getName() + " 已被其他 Mod 提供。\n"
                        + "  已有提供方 scope: " + existing.scope().id() + "\n"
                        + "  本次提供方 scope: " + publisher.id() + "\n"
                        + "同一接口只允许一个提供方 —— 静默覆盖会让调用方拿到"
                        + "意料之外的实现，且没有任何日志。");
    }

    private void pruneStopped() {
        for (Map.Entry<Class<?>, Entry<?>> e : entries.entrySet()) {
            if (e.getValue().scope().isStopped()) {
                entries.remove(e.getKey(), e.getValue());
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        entries.clear();
    }

    @Override
    public String toString() {
        return "ServiceBroker[services=" + entries.size() + "]";
    }

    private record Entry<T>(Class<T> type, T implementation, Scope scope) {
    }

    /**
     * 绑定到某个 Mod Scope 的视图。
     *
     * <p>读操作（{@code discover} / {@code publishedTypes}）转发到全局表 ——
     * 服务发现的语义本来就是「看全平台」。写操作（{@code provide} /
     * {@code withdraw} / {@code providedByMe}）以该 Scope 为归属。
     */
    private final class ScopedView implements ServiceBroker {
        private final Scope scope;

        ScopedView(Scope scope) {
            this.scope = scope;
        }

        @Override
        public <T> void provide(Class<T> serviceType, T implementation) {
            Objects.requireNonNull(serviceType, "serviceType");
            Objects.requireNonNull(implementation, "implementation");
            if (closed) {
                throw new IllegalStateException("ServiceBroker 已关闭");
            }
            if (!serviceType.isInstance(implementation)) {
                throw new IllegalArgumentException(
                        "实现 " + implementation.getClass().getName()
                                + " 不是 " + serviceType.getName() + " 的实例");
            }
            put(serviceType, implementation, scope);
        }

        @Override
        public boolean withdraw(Class<?> serviceType) {
            if (serviceType == null) {
                return false;
            }
            Entry<?> entry = entries.get(serviceType);
            if (entry == null || entry.scope() != scope) {
                return false;
            }
            return entries.remove(serviceType, entry);
        }

        @Override
        public <T> Optional<T> discover(Class<T> serviceType) {
            return ServiceBrokerImpl.this.discover(serviceType);
        }

        @Override
        public Set<Class<?>> publishedTypes() {
            return ServiceBrokerImpl.this.publishedTypes();
        }

        @Override
        public Set<Class<?>> providedByMe() {
            Set<Class<?>> mine = new LinkedHashSet<>();
            for (Map.Entry<Class<?>, Entry<?>> e : entries.entrySet()) {
                if (e.getValue().scope() == scope) {
                    mine.add(e.getKey());
                }
            }
            return Collections.unmodifiableSet(mine);
        }
    }

    /**
     * 供桥接层在日志里报告「某个服务被请求但不存在」。
     *
     * <p>不直接抛：{@code require()} 已经负责抛，这里只是留痕。
     */
    public static void logMissing(Class<?> type) {
        LOG.log(Level.FINE, "服务未找到: {0}", type == null ? "null" : type.getName());
    }

    /**
     * 未注册时抛出的标准异常 —— 供视图层复用同一份错误文案。
     */
    public static ServiceUnavailableException missing(Class<?> type, Set<Class<?>> available) {
        return new ServiceUnavailableException(
                type == null ? "null" : type.getName(),
                "没有 Mod 提供该服务。已注册的服务类型: "
                        + String.join(", ", available.stream().map(Class::getName).toList()));
    }
}
