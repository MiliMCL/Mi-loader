package org.loader.runtime.instance;

import org.loader.runtime.kernel.Resource;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.util.ModLogger;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages multiple game instances, providing creation, discovery, starting, stopping,
 * and switching between instances.
 * <p>
 * InstanceManager is itself a Resource owned by a Scope. All instance lifecycles
 * are tracked through the Runtime's Scope tree.
 */
public class InstanceManager implements Resource {

    private final String id;
    private final Scope owner;
    private final Map<String, ManagedInstance> instances = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile String activeInstanceId = null;
    private volatile boolean closed = false;
    private final ModLogger logger;

    public InstanceManager(String id, Scope owner) {
        this.id = Objects.requireNonNull(id, "InstanceManager id must not be null");
        this.owner = Objects.requireNonNull(owner, "InstanceManager owner must not be null");
        this.logger = new ModLogger(id, owner);
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

    /**
     * Creates and registers a new managed instance.
     *
     * @param instance definition of the instance to create
     * @return the scope for the new instance
     */
    public Scope createInstance(Instance instance) {
        Objects.requireNonNull(instance, "instance must not be null");
        checkNotClosed();

        lock.writeLock().lock();
        try {
            if (instances.containsKey(instance.instanceId())) {
                throw new IllegalArgumentException(
                        "Instance already exists: " + instance.instanceId());
            }

            logger.info("Creating instance: " + instance.instanceId());

            // Create a child scope for this instance under the manager's owner scope
            Scope instanceScope = owner.createChild("instance:" + instance.instanceId());

            // Create Runtime scoped to this instance. Runtime implements AutoCloseable
            // but not Resource, so we track it via ManagedInstance rather than as a scope resource.
            org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create(instance.instanceId() + "-runtime");
            runtime.start();

            // Create MinecraftBootstrap for this instance
            org.loader.runtime.minecraft.MinecraftBootstrap bootstrap = new org.loader.runtime.minecraft.MinecraftBootstrap(runtime);

            ManagedInstance managed = new ManagedInstance(instance, runtime, bootstrap, instanceScope);
            instances.put(instance.instanceId(), managed);

            return instanceScope;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Starts a specific instance (starts its Runtime and bootstrap).
     */
    public void startInstance(String instanceId) {
        checkNotClosed();
        Objects.requireNonNull(instanceId);

        lock.readLock().lock();
        try {
            ManagedInstance managed = getManagedInstance(instanceId);
            if (!managed.runtime().isRunning()) {
                managed.runtime().start();
            }
            if (!managed.bootstrap().isRunning()) {
                managed.bootstrap().start();
            }
            activeInstanceId = instanceId;
            logger.info("Started instance: " + instanceId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Stops a specific instance cleanly.
     */
    public void stopInstance(String instanceId) {
        checkNotClosed();
        Objects.requireNonNull(instanceId);

        lock.readLock().lock();
        try {
            ManagedInstance managed = getManagedInstance(instanceId);
            managed.bootstrap().stop();
            managed.runtime().close();
            if (instanceId.equals(activeInstanceId)) {
                activeInstanceId = null;
            }
            logger.info("Stopped instance: " + instanceId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Removes an instance, stopping it if running and cleaning up its scope.
     */
    public boolean removeInstance(String instanceId) {
        Objects.requireNonNull(instanceId);

        lock.writeLock().lock();
        try {
            ManagedInstance managed = instances.get(instanceId);
            if (managed == null) {
                return false;
            }

            if (managed.bootstrap().isRunning()) {
                managed.bootstrap().stop();
            }
            managed.runtime().close();
            managed.scope().shutdown();

            instances.remove(instanceId);
            if (instanceId.equals(activeInstanceId)) {
                activeInstanceId = null;
            }

            logger.info("Removed instance: " + instanceId);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the currently active instance's ID.
     */
    public Optional<String> activeInstanceId() {
        return Optional.ofNullable(activeInstanceId);
    }

    /**
     * Returns the currently active instance metadata.
     */
    public Optional<Instance> activeInstance() {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(activeInstanceId)
                    .map(instances::get)
                    .map(ManagedInstance::instance);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns all registered instance IDs.
     */
    public List<String> instanceIds() {
        lock.readLock().lock();
        try {
            return List.copyOf(instances.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the number of registered instances.
     */
    public int instanceCount() {
        lock.readLock().lock();
        try {
            return instances.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Finds an instance by ID.
     */
    public Optional<Instance> findInstance(String instanceId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(instances.get(instanceId))
                    .map(ManagedInstance::instance);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the scope for an instance.
     */
    public Optional<Scope> instanceScope(String instanceId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(instances.get(instanceId))
                    .map(ManagedInstance::scope);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Checks if an instance is currently running.
     */
    public boolean isRunning(String instanceId) {
        lock.readLock().lock();
        try {
            ManagedInstance managed = instances.get(instanceId);
            return managed != null && managed.bootstrap().isRunning();
        } finally {
            lock.readLock().unlock();
        }
    }

    private ManagedInstance getManagedInstance(String instanceId) {
        ManagedInstance managed = instances.get(instanceId);
        if (managed == null) {
            throw new NoSuchElementException("Instance not found: " + instanceId);
        }
        return managed;
    }

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("InstanceManager is closed");
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) return;
            closed = true;

            // Stop and clean up all managed instances
            for (String instanceId : new ArrayList<>(instances.keySet())) {
                ManagedInstance managed = instances.get(instanceId);
                try {
                    if (managed.bootstrap().isRunning()) {
                        managed.bootstrap().stop();
                    }
                    managed.runtime().close();
                } catch (Exception e) {
                    // Log but continue cleanup
                }
            }
            instances.clear();
            activeInstanceId = null;
            logger.info("InstanceManager closed");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Internal record holding the association between Instance metadata, Runtime, and Scope.
     */
    private record ManagedInstance(
            Instance instance,
            org.loader.runtime.kernel.Runtime runtime,
            org.loader.runtime.minecraft.MinecraftBootstrap bootstrap,
            Scope scope
    ) {
    }
}
