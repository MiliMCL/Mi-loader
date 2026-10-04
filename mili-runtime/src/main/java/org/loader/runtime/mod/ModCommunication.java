package org.loader.runtime.mod;

import org.loader.runtime.kernel.Scope;
import org.loader.runtime.service.EventBus;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Inter-Mod communication channel.
 * <p>
 * Mods can subscribe to events published by other mods through typed channels.
 * This is an additional communication mechanism beyond ServiceRegistry.
 */
public class ModCommunication implements org.loader.runtime.kernel.Resource {

    private final String id;
    private final Scope owner;
    private final EventBus eventBus;
    private final Map<String, List<ChannelBinding<?>>> channels = new LinkedHashMap<>();
    private volatile boolean closed = false;

    public ModCommunication(String id, Scope owner) {
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
        this.eventBus = new EventBus(id + ":events", owner);
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
     * Publishes an event to a named channel.
     */
    public <T> void publish(String channel, T event) {
        if (closed) {
            throw new IllegalStateException("ModCommunication is closed");
        }
        eventBus.post(new ChannelEvent<>(channel, event));
    }

    /**
     * Subscribes to events on a named channel.
     */
    @SuppressWarnings("unchecked")
    public <T> void subscribe(String channel, Class<T> eventType, ChannelListener<T> listener) {
        if (closed) {
            throw new IllegalStateException("ModCommunication is closed");
        }
        eventBus.addListener(ChannelEvent.class, event -> {
            if (event.channel().equals(channel)) {
                try {
                    T payload = (T) event.payload();
                    listener.onEvent(payload);
                } catch (ClassCastException ignored) {
                    // Type mismatch, skip
                }
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        channels.clear();
        eventBus.close();
    }

    /**
     * A channel-based event wrapper.
     */
    record ChannelEvent<T>(String channel, T payload) {
    }

    /**
     * Listener for channel events.
     */
    @FunctionalInterface
    public interface ChannelListener<T> {
        void onEvent(T event);
    }

    /**
     * Internal binding record.
     */
    private record ChannelBinding<T>(String channel, Class<T> type) {
    }
}
