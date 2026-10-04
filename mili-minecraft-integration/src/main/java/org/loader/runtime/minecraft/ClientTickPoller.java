package org.loader.runtime.minecraft;

import org.loader.runtime.kernel.Scope;
import org.loader.runtime.scheduler.Scheduler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polls the in-process Minecraft client instance via reflection and bridges
 * its lifecycle events to Mili's TickBridge and EventBus.
 *
 * <p>This is Mili's native, Mixin-free approach. Since the game runs in the same
 * JVM as the loader, reflection on {@code Minecraft.getInstance()} provides the
 * observable state needed to drive lifecycle events.
 *
 * <p>Poll rate: 10Hz (every 100ms). Each poll checks:
 * <ul>
 *   <li>Minecraft availability</li>
 *   <li>World presence / absence</li>
 *   <li>Game time change (client tick surrogate)</li>
 *   <li>Player instance presence</li>
 *   <li>Screen / overlay changes</li>
 * </ul>
 */
public class ClientTickPoller implements AutoCloseable {

    private final ClassLoader mcClassLoader;
    private final MinecraftEventBridge eventBridge;
    private final TickBridge tickBridge;
    private final Scheduler scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastGameTime = new AtomicLong(-1);
    private final AtomicReference<Object> lastLevel = new AtomicReference<>(null);
    private final AtomicReference<Object> lastPlayer = new AtomicReference<>(null);
    private volatile Thread pollThread;

    public ClientTickPoller(ClassLoader mcClassLoader, Scope scope, Scheduler scheduler,
                            MinecraftEventBridge eventBridge, TickBridge tickBridge) {
        this.mcClassLoader = mcClassLoader;
        this.scheduler = scheduler;
        this.eventBridge = eventBridge;
        this.tickBridge = tickBridge;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;

        pollThread = new Thread(this::pollLoop, "mili-client-poller");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void pollLoop() {
        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft", true, mcClassLoader);
            Method getInstance = minecraftClass.getMethod("getInstance");

            // Reflect common fields once
            Field levelField = findField(minecraftClass, "level");
            Field playerField = findField(minecraftClass, "player");

            while (running.get()) {
                try {
                    Object mc = getInstance.invoke(null);
                    if (mc == null) {
                        Thread.sleep(100);
                        continue;
                    }

                    Object level = levelField != null ? levelField.get(mc) : null;
                    Object player = playerField != null ? playerField.get(mc) : null;

                    // Detect world start/stop
                    if (level != null && lastLevel.get() == null) {
                        eventBridge.post(new MinecraftEventBridge.ClientWorldJoinEvent());
                    } else if (level == null && lastLevel.get() != null) {
                        eventBridge.post(new MinecraftEventBridge.ClientWorldLeaveEvent());
                    }
                    lastLevel.set(level);

                    // Detect player join/leave
                    if (player != null && lastPlayer.get() == null) {
                        eventBridge.post(new MinecraftEventBridge.ClientPlayerJoinEvent());
                    } else if (player == null && lastPlayer.get() != null) {
                        eventBridge.post(new MinecraftEventBridge.ClientPlayerLeaveEvent());
                    }
                    lastPlayer.set(player);

                    // Detect game tick via level.getGameTime()
                    if (level != null) {
                        Method getGameTime = level.getClass().getMethod("getGameTime");
                        long gameTime = (long) getGameTime.invoke(level);
                        long last = lastGameTime.getAndSet(gameTime);
                        if (gameTime != last && last >= 0) {
                            // Forward-tick bridge
                            for (long t = last + 1; t <= gameTime; t++) {
                                tickBridge.onTick();
                                eventBridge.post(new MinecraftEventBridge.ClientTickEvent(t));
                            }
                        }
                    }

                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // Poll failure is non-fatal
                    try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                }
            }
        } catch (Exception e) {
            System.err.println("[Mili] ClientTickPoller init failed: " + e.getMessage());
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    @Override
    public void close() {
        running.set(false);
        if (pollThread != null) pollThread.interrupt();
    }
}
