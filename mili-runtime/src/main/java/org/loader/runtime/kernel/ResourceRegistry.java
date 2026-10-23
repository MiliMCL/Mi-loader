package org.loader.runtime.kernel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全 Runtime 的资源登记处 —— 每个资源都必须有已知的属主与生命周期。
 *
 * <h2>关闭语义（这一条以前是错的）</h2>
 * 旧实现的 {@code close()} 只做 {@code resources.clear()}：登记表被清空，
 * 但表里的资源<b>一个都没关</b>。它的症状极其隐蔽 —— 进程退出时没有异常、
 * 没有日志，只有「ZIP 句柄没释放」「线程还活着」这类现象，
 * 而且因为对象随后被 GC，堆转储里也看不到它们。
 *
 * <p>现在 {@code close()} 按<b>注册的逆序</b>关闭每个资源（后注册的可能
 * 依赖先注册的），并把单个资源的异常收集成 suppressed 抛出。
 * 即使某个资源关闭失败，其余资源仍会继续关闭。
 *
 * <h2>为什么用插入有序的 Map</h2>
 * 逆序释放需要顺序。{@code ConcurrentHashMap} 不保证顺序，所以这里用
 * {@code LinkedHashMap} + 方法级 {@code synchronized}：登记/注销是低频操作
 * （启动期与关停期），读多写少的场景下锁竞争可以忽略；而
 * {@code ConcurrentHashMap} 换来的是「关闭顺序随机」这种不可接受的语义。
 */
public class ResourceRegistry implements Resource {

    private final String id;
    private final Scope owner;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, ResourceEntry> resources = new LinkedHashMap<>();

    public ResourceRegistry(String id, Scope owner) {
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

    /** 是否仍接受登记。 */
    public boolean isOpen() {
        return !closed.get();
    }

    /**
     * 登记一个资源。
     *
     * <p>同一 id 重复登记会覆盖旧条目 —— 旧条目不会被自动关闭，
     * 因为「覆盖」可能是有意的（热重载时的替换）。需要确定性释放时
     * 应先 {@link #unregister(String)} 再登记。
     */
    public synchronized <T extends Resource> T register(T resource) {
        if (closed.get()) {
            throw new IllegalStateException("ResourceRegistry is closed: " + id);
        }
        Objects.requireNonNull(resource, "resource");
        resources.put(resource.id(), new ResourceEntry(resource, resource.owner()));
        return resource;
    }

    /** 按 id 查找。 */
    public synchronized Optional<Resource> get(String resourceId) {
        return Optional.ofNullable(resources.get(resourceId)).map(ResourceEntry::resource);
    }

    /** 从登记表移除（<b>不关闭</b>，由调用方决定资源的去向）。 */
    public synchronized Optional<Resource> unregister(String resourceId) {
        ResourceEntry entry = resources.remove(resourceId);
        return Optional.ofNullable(entry).map(ResourceEntry::resource);
    }

    /** 某个 Scope 拥有的全部资源。 */
    public synchronized List<Resource> getResourcesByOwner(Scope owner) {
        List<Resource> out = new ArrayList<>();
        for (ResourceEntry entry : resources.values()) {
            if (entry.owner().equals(owner)) {
                out.add(entry.resource());
            }
        }
        return List.copyOf(out);
    }

    /**
     * 找出「属主自身不在登记表中」的资源 —— 典型的悬空引用。
     */
    public synchronized List<Resource> detectOrphans() {
        List<Resource> orphans = new ArrayList<>();
        for (ResourceEntry entry : resources.values()) {
            Resource res = entry.resource();
            if (res instanceof Scope scope && scope.owner() != null
                    && !resources.containsKey(scope.owner().id())) {
                orphans.add(res);
            }
        }
        return Collections.unmodifiableList(orphans);
    }

    /** 当前登记数量（诊断用）。 */
    public synchronized int size() {
        return resources.size();
    }

    /**
     * 逆序关闭全部资源并清空登记表。幂等。
     *
     * @throws ScopeShutdownException 有资源关闭失败时；其余资源仍会被关闭，
     *                                失败原因作为 suppressed 附上
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<Resource> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(resources.size());
            for (ResourceEntry entry : resources.values()) {
                snapshot.add(entry.resource());
            }
            resources.clear();
        }

        List<Exception> errors = new ArrayList<>();
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Resource resource = snapshot.get(i);
            try {
                resource.close();
            } catch (Exception e) {
                errors.add(e);
            }
        }
        if (!errors.isEmpty()) {
            ScopeShutdownException failure =
                    new ScopeShutdownException("Resource cleanup errors in registry: " + id);
            errors.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private record ResourceEntry(Resource resource, Scope owner) {
    }
}
