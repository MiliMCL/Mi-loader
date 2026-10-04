package org.loader.runtime.service;

import org.loader.runtime.kernel.Scope;
import org.loader.runtime.kernel.ScopeShutdownException;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Typed event bus for inter-component communication.
 * <p>
 * Events are dispatched asynchronously through the Runtime Scheduler.
 */
public class EventBus implements org.loader.runtime.kernel.Resource {

    private final String id;
    private final Scope owner;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<Class<?>, List<EventListener<?>>> listeners = new ConcurrentHashMap<>();
    private final ExecutorService dispatchExecutor;

    public EventBus(String id, Scope owner) {
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
        this.dispatchExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "runtime-eventbus-" + id);
            t.setDaemon(true);
            return t;
        });
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
    @SuppressWarnings("unchecked")
    public <T> void addListener(Class<T> eventType, EventListener<T> listener) {
        if (closed.get()) {
            throw new IllegalStateException("EventBus is closed");
        }
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * Removes a listener.
     */
    public <T> void removeListener(Class<T> eventType, EventListener<T> listener) {
        List<EventListener<?>> typeListeners = listeners.get(eventType);
        if (typeListeners != null) {
            typeListeners.remove(listener);
        }
    }

    /**
     * Posts an event synchronously.
     */
    @SuppressWarnings("unchecked")
    public <T> void post(T event) {
        if (closed.get()) {
            return;
        }
        List<EventListener<?>> typeListeners = listeners.get(event.getClass());
        if (typeListeners != null) {
            for (EventListener<?> listener : typeListeners) {
                try {
                    ((EventListener<T>) listener).onEvent(event);
                } catch (Exception e) {
                    // Listener exceptions must not break event dispatch
                }
            }
        }
    }

    /**
     * Posts an event asynchronously.
     */
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<Void> postAsync(T event) {
        if (closed.get()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> post(event), dispatchExecutor);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            dispatchExecutor.shutdown();
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
