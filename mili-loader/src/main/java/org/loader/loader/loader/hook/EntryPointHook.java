package org.loader.loader.hook;

import org.loader.loader.classloader.ModClassLoaderManager;
import org.loader.runtime.kernel.Runtime;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.minecraft.MinecraftBootstrap;
import org.loader.runtime.minecraft.MinecraftEventBridge;
import org.loader.runtime.RuntimeEnvironment;

import org.loader.runtime.minecraft.ClientTickPoller;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hooks into the Minecraft entry point to bridge Minecraft lifecycle with Mili Runtime.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Runtime is started by {@link org.loader.loader.LoaderMain} before this hook</li>
 *   <li>Hook creates {@link MinecraftBootstrap} under Runtime control</li>
 *   <li>Hook invokes MC main via reflection, captures lifecycle events,
 *       propagates to Runtime Scope and TickBridge</li>
 *   <li>On MC exit: bootstrap.stop() + runtime close handled by caller</li>
 * </ol>
 *
 * <p>This is a Mili-native implementation. It does NOT use Mixin, LaunchWrapper,
 * or any Fabric-specific class transformation. Bytecode transforms are applied
 * via {@link org.loader.loader.classloader.TransformingClassLoader} only when
 * explicitly registered by the Mili Runtime.
 */
public class EntryPointHook {

    private final AtomicReference<MinecraftBootstrap> bootstrap = new AtomicReference<>();
    private final AtomicReference<Runtime> runtime = new AtomicReference<>();
    private final AtomicReference<ModClassLoaderManager> classLoaderManager = new AtomicReference<>();
    private final AtomicReference<Thread> mcMainThread = new AtomicReference<>();
    private final AtomicReference<Scope> minecraftScope = new AtomicReference<>();
    private final AtomicReference<ClassLoader> mcClassLoader = new AtomicReference<>();
    private final AtomicReference<ClientTickPoller> clientPoller = new AtomicReference<>();
    private volatile boolean installed = false;
    private volatile RuntimeEnvironment environment;
    private volatile boolean ownsRuntime = false;

    /**
     * Installs hooks using the given ClassLoader and pre-started Runtime.
     *
     * @param mcClassLoader ClassLoader that can load Minecraft classes
     * @param runtime       already-started Runtime instance
     * @param manager       Mod ClassLoader manager
     * @param env           Runtime environment (CLIENT or DEDICATED_SERVER)
     */
    public void install(ClassLoader mcClassLoader, Runtime runtime, ModClassLoaderManager manager, RuntimeEnvironment env) {
        if (installed) return;
        this.mcClassLoader.set(mcClassLoader);
        this.runtime.set(runtime);
        this.classLoaderManager.set(manager);
        this.environment = env;
        this.ownsRuntime = false;

        MinecraftBootstrap mcBootstrap = new MinecraftBootstrap(runtime, env);
        bootstrap.set(mcBootstrap);
        this.minecraftScope.set(mcBootstrap.scope());

        installed = true;
    }

    /**
     * Invokes Minecraft's main method under Runtime lifecycle control.
     * Blocks until MC exits.
     */
    public void invokeMinecraftMain(ClassLoader mcClassLoader, String[] args) throws Exception {
        MinecraftBootstrap mcBootstrap = bootstrap.get();
        Runtime rt = runtime.get();

        // If Runtime was not pre-set (standalone usage), create and own it
        if (rt == null) {
            rt = Runtime.create("minecraft-runtime");
            runtime.set(rt);
            rt.start();
            ownsRuntime = true;
            if (mcBootstrap == null) {
                mcBootstrap = new MinecraftBootstrap(rt, environment != null ? environment : RuntimeEnvironment.DEDICATED_SERVER);
                bootstrap.set(mcBootstrap);
            }
        }
        if (mcBootstrap == null) {
            throw new IllegalStateException("Hook not installed. Call install() first.");
        }

        // Determine entry point based on environment
        String entrypoint = (environment == RuntimeEnvironment.CLIENT)
                ? "net.minecraft.client.main.Main"
                : "net.minecraft.server.Main";

        // Pre-launch notifications (Mili-native lifecycle, replaces Fabric PreLaunchEntrypoint)
        if (environment == RuntimeEnvironment.CLIENT) {
            mcBootstrap.eventBridge().post(new MinecraftEventBridge.ClientStartingEvent());
        } else {
            mcBootstrap.eventBridge().post(new MinecraftEventBridge.ServerStartingEvent());
        }

        // Transition minecraft scope to RUNNING
        mcBootstrap.start();

        // Start client tick poller for CLIENT mode (bridges MC lifecycle via reflection)
        if (environment == RuntimeEnvironment.CLIENT) {
            ClassLoader gameCL = this.mcClassLoader.get();
            ClientTickPoller poller = new ClientTickPoller(
                    gameCL,
                    mcBootstrap.scope(),
                    mcBootstrap.scheduler(),
                    mcBootstrap.eventBridge(),
                    mcBootstrap.tickBridge());
            clientPoller.set(poller);
            poller.start();
        }

        try {
            Class<?> mainClass = Class.forName(entrypoint, true, mcClassLoader);
            Method mainMethod = mainClass.getMethod("main", String[].class);

            CountDownLatch threadLatch = new CountDownLatch(1);
            CountDownLatch exitLatch = new CountDownLatch(1);

            Thread mcThread = new Thread(() -> {
                mcMainThread.set(Thread.currentThread());
                threadLatch.countDown();
                try {
                    mainMethod.invoke(null, (Object) args);
                } catch (Exception e) {
                    System.err.println("[Minecraft] main() threw: " + e.getCause());
                    if (e.getCause() != null) e.getCause().printStackTrace();
                } finally {
                    exitLatch.countDown();
                }
            }, "minecraft-main");

            mcThread.setContextClassLoader(mcClassLoader);
            mcThread.setDaemon(false);
            mcThread.start();

            // Wait for MC main thread to start
            threadLatch.await(5, TimeUnit.SECONDS);

            // Block until MC exits
            exitLatch.await();

        } finally {
            // Post shutdown events to Runtime
            if (environment == RuntimeEnvironment.CLIENT) {
                mcBootstrap.eventBridge().post(new MinecraftEventBridge.ClientStoppingEvent());
            } else {
                mcBootstrap.eventBridge().post(new MinecraftEventBridge.ServerStoppingEvent());
            }
            // Stop the MC bootstrap (transitions scope, fires lifecycle)
            mcBootstrap.stop();

            // Stop client poller
            ClientTickPoller poller = clientPoller.getAndSet(null);
            if (poller != null) {
                try { poller.close(); } catch (Exception ignored) {}
            }

            // If we created our own Runtime (standalone mode), close it
            // Otherwise, the caller (LoaderMain) owns the Runtime lifecycle
            if (ownsRuntime && rt != null) {
                try { rt.close(); } catch (Exception ignored) {}
            }
        }
    }

    public boolean isInstalled() { return installed; }
    public Thread getMainThread() { return mcMainThread.get(); }
    public MinecraftBootstrap getBootstrap() { return bootstrap.get(); }
    public Scope getMinecraftScope() { return minecraftScope.get(); }
}
