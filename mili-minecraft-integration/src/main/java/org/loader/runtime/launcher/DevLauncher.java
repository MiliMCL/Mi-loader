package org.loader.runtime.launcher;

import org.loader.runtime.minecraft.MinecraftBootstrap;
import org.loader.runtime.RuntimeEnvironment;

/**
 * Development launcher for local testing.
 */
public class DevLauncher {

    private final org.loader.runtime.kernel.Runtime runtime;
    private final MinecraftBootstrap bootstrap;
    private final RuntimeEnvironment environment;

    private DevLauncher(RuntimeEnvironment env) {
        this.runtime = org.loader.runtime.kernel.Runtime.create("dev-runtime");
        this.environment = env;
        this.bootstrap = new MinecraftBootstrap(runtime);
    }

    public static DevLauncher create(RuntimeEnvironment env) {
        return new DevLauncher(env);
    }

    public static DevLauncher server() {
        return new DevLauncher(RuntimeEnvironment.DEDICATED_SERVER);
    }

    public static DevLauncher client() {
        return new DevLauncher(RuntimeEnvironment.CLIENT);
    }

    public org.loader.runtime.kernel.Runtime runtime() {
        return runtime;
    }

    public MinecraftBootstrap bootstrap() {
        return bootstrap;
    }

    public RuntimeEnvironment environment() {
        return environment;
    }

    public void start() {
        runtime.start();
        // 显式走完状态机，确保 Mod 观察到完整的启动序列
        bootstrap.beginDiscovery();
        bootstrap.beginPreparing();
        bootstrap.beginLoading();
        bootstrap.beginMinecraftBootstrap();
        bootstrap.start();
    }

    public void stop() {
        bootstrap.stop();
        runtime.close();
    }

    public void runTickLoop(int ticks) {
        if (!bootstrap.isRunning()) {
            throw new IllegalStateException("Not running");
        }
        for (int i = 0; i < ticks; i++) {
            // TickBridge 要求 begin/end 成对推进，单次 tick 是一个执行信封。
            var contract = bootstrap.tickBridge().beginTick();
            try {
                // 这里本应执行本次 tick 的实际工作（阶段推进、任务派发）。
            } finally {
                bootstrap.tickBridge().endTick();
            }
        }
    }

    @Override
    public String toString() {
        return "DevLauncher[env=" + environment + ", running=" + bootstrap.isRunning() + "]";
    }
}
