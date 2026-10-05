package org.loader.runtime.minecraft;

import org.loader.runtime.kernel.Scope;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Minecraft 生命周期 —— 把 {@link BootstrapState} 映射为 Runtime Scope 生命周期。
 *
 * <p>与 {@link BootstrapState} 的分工：
 * <ul>
 *   <li>{@link BootstrapState} —— 启动状态机（九态），关心"启动走到哪一步"</li>
 *   <li>本类 —— 生命周期通知（STARTING/RUNNING/STOPPING/STOPPED），关心"运行状态"</li>
 * </ul>
 */
public final class MinecraftLifecycle {

    private final Scope minecraftScope;
    private final List<Consumer<Phase>> listeners = new CopyOnWriteArrayList<>();
    private volatile Phase phase = Phase.CREATED;

    public MinecraftLifecycle(Scope minecraftScope) {
        this.minecraftScope = minecraftScope;
    }

    public Scope scope() {
        return minecraftScope;
    }

    public Phase phase() {
        return phase;
    }

    public boolean isRunning() {
        return phase == Phase.RUNNING;
    }

    /** 添加生命周期监听器。 */
    public void addListener(Consumer<Phase> listener) {
        listeners.add(listener);
    }

    /** 移除生命周期监听器。 */
    public void removeListener(Consumer<Phase> listener) {
        listeners.remove(listener);
    }

    void onStart() {
        phase = Phase.STARTING;
        notifyListeners(Phase.STARTING);
        phase = Phase.RUNNING;
        notifyListeners(Phase.RUNNING);
    }

    void onStop() {
        phase = Phase.STOPPING;
        notifyListeners(Phase.STOPPING);
        phase = Phase.STOPPED;
        notifyListeners(Phase.STOPPED);
    }

    private void notifyListeners(Phase event) {
        for (var listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                // 监听器异常不得破坏生命周期
            }
        }
    }

    /**
     * 运行期生命周期阶段（区别于启动状态机 {@link BootstrapState}）。
     */
    public enum Phase {
        CREATED,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED
    }
}