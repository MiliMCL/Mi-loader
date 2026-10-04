package org.loader.runtime.minecraft;

import org.loader.runtime.kernel.*;
import org.loader.runtime.scheduler.Scheduler;
import org.loader.runtime.scheduler.TaskPriority;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick bridge connects Minecraft's tick loop to the Runtime Scheduler.
 * <p>
 * Minecraft runs at 20 TPS (50ms per tick). The tick bridge schedules
 * per-tick work through the Runtime's task system.
 */
public class TickBridge implements Resource {

    private final String id;
    private final Scope owner;
    private final Scheduler scheduler;
    private final List<TickListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, TickTask> scheduledTasks = new LinkedHashMap<>();
    private final AtomicLong tickCounter = new AtomicLong(0);
    private volatile boolean closed = false;
    private volatile boolean paused = false;

    private static final long DEFAULT_TPS = 20;
    private static final long DEFAULT_TICK_DURATION_MS = 1000 / DEFAULT_TPS;

    public TickBridge(Scope owner, Scheduler scheduler) {
        this.id = "minecraft-tick-bridge";
        this.owner = owner;
        this.scheduler = scheduler;
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
     * Returns the current tick count.
     */
    public long currentTick() {
        return tickCounter.get();
    }

    /**
     * Advances the tick counter and runs registered listeners.
     * Called by the Minecraft main loop.
     */
    public void onTick() {
        if (closed || paused) {
            return;
        }
        long tick = tickCounter.incrementAndGet();
        for (TickListener listener : listeners) {
            try {
                listener.onTick(tick);
            } catch (Exception e) {
                // Tick listeners must not break the tick loop
            }
        }
    }

    /**
     * Registers a tick listener.
     */
    public void addListener(TickListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a tick listener.
     */
    public void removeListener(TickListener listener) {
        listeners.remove(listener);
    }

    /**
     * Submits a task to be executed on the next tick.
     */
    public void runOnNextTick(Runnable task) {
        addListener(new TickListener() {
            @Override
            public void onTick(long tick) {
                task.run();
                removeListener(this);
            }
        });
    }

    /**
     * Submits a task to be executed after N ticks.
     */
    public void runAfterTicks(int ticks, Runnable task) {
        if (ticks <= 0) {
            runOnNextTick(task);
            return;
        }
        final int[] remaining = {ticks};
        addListener(new TickListener() {
            @Override
            public void onTick(long tick) {
                if (--remaining[0] <= 0) {
                    task.run();
                    removeListener(this);
                }
            }
        });
    }

    /**
     * Pauses tick processing.
     */
    public void pause() {
        paused = true;
    }

    /**
     * Resumes tick processing.
     */
    public void resume() {
        paused = false;
    }

    public boolean isPaused() {
        return paused;
    }

    @Override
    public void close() {
        closed = true;
        listeners.clear();
        scheduledTasks.clear();
    }

    /**
     * Tick listener interface.
     */
    @FunctionalInterface
    public interface TickListener {
        void onTick(long tick);
    }

    /**
     * Internal tick task representation.
     */
    private record TickTask(String id, Runnable work, int interval, boolean repeating) {
    }
}
