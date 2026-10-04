package org.loader.runtime.minecraft;

import org.loader.runtime.kernel.Scope;
import org.loader.runtime.service.EventBus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecraft event bridge.
 * <p>
 * Adapts Minecraft game events to the Runtime's typed event system.
 */
public class MinecraftEventBridge implements org.loader.runtime.kernel.Resource {

    private final String id;
    private final Scope owner;
    private final EventBus eventBus;
    private final Map<String, List<MinecraftEventInterceptor<?>>> interceptors = new LinkedHashMap<>();
    private volatile boolean closed = false;

    public MinecraftEventBridge(Scope owner) {
        this.id = "minecraft-event-bridge";
        this.owner = owner;
        this.eventBus = new EventBus(id + ":eventbus", owner);
        owner.registerResource(eventBus);
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
     * Posts a Minecraft event to the event bus.
     */
    public <T extends MinecraftEvent> void post(T event) {
        if (closed) return;

        // Run pre-interceptors by type
        List<MinecraftEventInterceptor<?>> typeInterceptors = interceptors.get(event.getClass().getSimpleName());
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

        // Post the event
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
    @SuppressWarnings("unchecked")
    public <T extends MinecraftEvent> void intercept(Class<T> eventType, MinecraftEventInterceptor<T> interceptor) {
        interceptors.computeIfAbsent(eventType.getSimpleName(), k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(interceptor);
    }

    @Override
    public void close() {
        closed = true;
        eventBus.close();
        interceptors.clear();
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
