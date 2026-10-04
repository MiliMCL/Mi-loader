package org.loader.runtime.minecraft;

import org.loader.runtime.kernel.Scope;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Minecraft lifecycle bridge.
 * <p>
 * Adapts Minecraft lifecycle phases to Runtime Scope lifecycle.
 */
public class MinecraftLifecycle {

    private final Scope minecraftScope;
    private final List<Consumer<MinecraftBootstrap.MinecraftLifecycleEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile Phase phase = Phase.CREATED;

    public MinecraftLifecycle(Scope minecraftScope) {
        this.minecraftScope = minecraftScope;
    }

    public Phase phase() {
        return phase;
    }

    public boolean isRunning() {
        return phase == Phase.RUNNING;
    }

    /**
     * Adds a lifecycle listener.
     */
    public void addListener(Consumer<MinecraftBootstrap.MinecraftLifecycleEvent> listener) {
        listeners.add(listener);
    }

    /**
     * Removes a lifecycle listener.
     */
    public void removeListener(Consumer<MinecraftBootstrap.MinecraftLifecycleEvent> listener) {
        listeners.remove(listener);
    }

    void onStart() {
        phase = Phase.STARTING;
        notifyListeners(MinecraftBootstrap.MinecraftLifecycleEvent.STARTING);
        phase = Phase.RUNNING;
        notifyListeners(MinecraftBootstrap.MinecraftLifecycleEvent.STARTED);
    }

    void onStop() {
        phase = Phase.STOPPING;
        notifyListeners(MinecraftBootstrap.MinecraftLifecycleEvent.STOPPING);
        phase = Phase.STOPPED;
        notifyListeners(MinecraftBootstrap.MinecraftLifecycleEvent.STOPPED);
    }

    private void notifyListeners(MinecraftBootstrap.MinecraftLifecycleEvent event) {
        for (var listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                // Listeners must not break lifecycle
            }
        }
    }

    /**
     * Minecraft lifecycle phases (separate from Runtime LifecycleState).
     */
    public enum Phase {
        CREATED,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED
    }
}
