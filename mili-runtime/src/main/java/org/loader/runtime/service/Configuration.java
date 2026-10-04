package org.loader.runtime.service;

import org.loader.runtime.kernel.Scope;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Configuration store with typed access and change notifications.
 */
public class Configuration implements org.loader.runtime.kernel.Resource {

    private final String id;
    private final Scope owner;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final List<ConfigurationListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public Configuration(String id, Scope owner) {
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
     * Sets a configuration value.
     */
    @SuppressWarnings("unchecked")
    public <T> void set(String key, T value) {
        if (closed.get()) {
            throw new IllegalStateException("Configuration is closed");
        }
        Object old = values.put(key, value);
        if (!Objects.equals(old, value)) {
            notifyListeners(key, old, value);
        }
    }

    /**
     * Gets a configuration value.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key) {
        return Optional.ofNullable((T) values.get(key));
    }

    /**
     * Gets a configuration value with a default.
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        return (T) values.getOrDefault(key, defaultValue);
    }

    /**
     * Gets a required configuration value.
     */
    @SuppressWarnings("unchecked")
    public <T> T getRequired(String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new NoSuchElementException("Missing required config: " + key);
        }
        return (T) value;
    }

    /**
     * Gets a string value.
     */
    public String getString(String key) {
        return get(key).map(Object::toString).orElse(null);
    }

    /**
     * Gets an integer value.
     */
    public int getInt(String key, int defaultValue) {
        return get(key).filter(v -> v instanceof Number)
                .map(v -> ((Number) v).intValue())
                .orElse(defaultValue);
    }

    /**
     * Gets a boolean value.
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        return get(key).filter(v -> v instanceof Boolean)
                .map(v -> (Boolean) v)
                .orElse(defaultValue);
    }

    /**
     * Adds a configuration change listener.
     */
    public void addListener(ConfigurationListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a configuration change listener.
     */
    public void removeListener(ConfigurationListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(String key, Object oldValue, Object newValue) {
        for (ConfigurationListener listener : listeners) {
            try {
                listener.onConfigChange(key, oldValue, newValue);
            } catch (Exception e) {
                // Listeners must not break configuration
            }
        }
    }

    @Override
    public void close() {
        closed.set(true);
        values.clear();
        listeners.clear();
    }

    /**
     * Configuration change listener.
     */
    @FunctionalInterface
    public interface ConfigurationListener {
        void onConfigChange(String key, Object oldValue, Object newValue);
    }
}
