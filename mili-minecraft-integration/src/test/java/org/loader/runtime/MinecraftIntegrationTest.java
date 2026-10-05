package org.loader.runtime;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.LifecycleState;
import org.loader.runtime.minecraft.*;
import org.loader.runtime.observability.RuntimeDiagnostics;
import org.loader.runtime.tick.TickContract;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minecraft 集成层测试 —— 验证状态机、真实 tick 桥接与事件。
 *
 * <p>不启动真实 Minecraft：通过 {@link TickBridge} 的窄接口验证契约。
 * 真实 Minecraft 26.2 启动验证在 CI smoke test 中进行。
 */
class MinecraftIntegrationTest {

    private static org.loader.runtime.kernel.Runtime startedRuntime(String name) {
        var rt = org.loader.runtime.kernel.Runtime.create(name);
        rt.start();
        return rt;
    }

    // ── 状态机 ─────────────────────────────────────────────────────────────

    @Test
    void bootstrap_startsInCreatedState() {
        var runtime = startedRuntime("mc-state-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            assertEquals(BootstrapState.CREATED, bootstrap.state());
            assertFalse(bootstrap.isRunning());
        } finally {
            runtime.close();
        }
    }

    @Test
    void bootstrap_startAndStop() {
        var runtime = startedRuntime("mc-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            // start() 只能从 BOOTSTRAPPING 进入 —— 它不再替调用方补齐中间阶段。
            // 完整序列由 bootstrap_followsFullStateSequence 逐态断言，这里只关心
            // start/stop 往返后状态与 Scope 释放是否正确。
            bootstrap.beginDiscovery();
            bootstrap.beginPreparing();
            bootstrap.beginLoading();
            bootstrap.beginMinecraftBootstrap();
            bootstrap.start();

            assertTrue(bootstrap.isRunning());
            assertEquals(BootstrapState.RUNNING, bootstrap.state());
            assertEquals(LifecycleState.RUNNING, bootstrap.scope().state());

            bootstrap.stop();
            assertFalse(bootstrap.isRunning());
            assertEquals(BootstrapState.STOPPED, bootstrap.state());
        } finally {
            runtime.close();
        }
    }

    @Test
    void bootstrap_followsFullStateSequence() {
        var runtime = startedRuntime("mc-seq-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            assertEquals(BootstrapState.CREATED, bootstrap.state());

            bootstrap.beginDiscovery();
            assertEquals(BootstrapState.DISCOVERING, bootstrap.state());
            bootstrap.beginPreparing();
            assertEquals(BootstrapState.PREPARING, bootstrap.state());
            bootstrap.beginLoading();
            assertEquals(BootstrapState.LOADING, bootstrap.state());
            bootstrap.beginMinecraftBootstrap();
            assertEquals(BootstrapState.BOOTSTRAPPING, bootstrap.state());
            bootstrap.start();
            assertEquals(BootstrapState.RUNNING, bootstrap.state());
        } finally {
            runtime.close();
        }
    }

    @Test
    void bootstrap_rejectsIllegalTransition() {
        var runtime = startedRuntime("mc-illegal-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            // CREATED 不能直接跳到 RUNNING（需经beginMinecraftBootstrap）
            assertThrows(BootstrapState.IllegalStateTransitionException.class, bootstrap::start);
        } finally {
            runtime.close();
        }
    }

    @Test
    void bootstrap_failReleasesResources() {
        var runtime = startedRuntime("mc-fail-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            bootstrap.beginDiscovery();
            bootstrap.fail(new RuntimeException("simulated failure"));

            assertEquals(BootstrapState.FAILED, bootstrap.state());
            assertNotNull(bootstrap.failure());
            assertEquals("simulated failure", bootstrap.failure().getMessage());
            assertTrue(bootstrap.scope().isStopped(), "失败后 Scope 必须已释放");
        } finally {
            runtime.close();
        }
    }

    @Test
    void bootstrap_failClosesExternalResources() {
        var runtime = startedRuntime("mc-failres-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            var closed = new java.util.concurrent.atomic.AtomicBoolean(false);
            bootstrap.registerExternalResource(() -> closed.set(true));

            bootstrap.beginDiscovery();
            bootstrap.fail(new RuntimeException("boom"));

            assertTrue(closed.get(), "失败时必须释放已注册的外部资源");
        } finally {
            runtime.close();
        }
    }

    // ── Tick 桥接（真实 tick 契约） ────────────────────────────────────────

    @Test
    void tickBridge_countsRealTicks() {
        var runtime = startedRuntime("tick-count-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            bootstrap.beginDiscovery();
            bootstrap.beginPreparing();
            bootstrap.beginLoading();
            bootstrap.beginMinecraftBootstrap();
            bootstrap.start();

            TickBridge bridge = bootstrap.tickBridge();
            assertEquals(0L, bridge.currentTick());

            for (int i = 0; i < 3; i++) {
                var contract = bridge.beginTick();
                bridge.endTick();
                assertNotNull(contract);
            }

            assertEquals(3L, bridge.currentTick(), "每次 beginTick/endTick 对计一个 tick");
            bootstrap.stop();
        } finally {
            runtime.close();
        }
    }

    @Test
    void tickBridge_endTickOutsideTickReturnsNull() {
        var runtime = startedRuntime("tick-outside-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            bootstrap.beginDiscovery();
            bootstrap.beginPreparing();
            bootstrap.beginLoading();
            bootstrap.beginMinecraftBootstrap();
            bootstrap.start();

            TickBridge bridge = bootstrap.tickBridge();
            assertNull(bridge.endTick(), "不在 tick 内时 endTick 应返回 null");
            bootstrap.stop();
        } finally {
            runtime.close();
        }
    }

    @Test
    void tickBridge_rejectsCrossThreadTick() throws Exception {
        var runtime = startedRuntime("tick-thread-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            bootstrap.beginDiscovery();
            bootstrap.beginPreparing();
            bootstrap.beginLoading();
            bootstrap.beginMinecraftBootstrap();
            bootstrap.start();

            TickBridge bridge = bootstrap.tickBridge();
            bridge.beginTick(); // 绑定主测试线程

            var rejected = new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread other = new Thread(() -> {
                try {
                    bridge.beginTick();
                } catch (IllegalStateException e) {
                    rejected.set(true);
                }
            });
            other.start();
            other.join();

            assertTrue(rejected.get(), "tick 必须在 Minecraft 主线程推进");
            bridge.endTick();
            bootstrap.stop();
        } finally {
            runtime.close();
        }
    }

    @Test
    void tickBridge_onTickSubmitsWorkToActiveContract() {
        var runtime = startedRuntime("tick-ontick-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            bootstrap.beginDiscovery();
            bootstrap.beginPreparing();
            bootstrap.beginLoading();
            bootstrap.beginMinecraftBootstrap();
            bootstrap.start();

            TickBridge bridge = bootstrap.tickBridge();
            var ran = new java.util.concurrent.atomic.AtomicBoolean(false);
            bridge.beginTick();
            bridge.onTick(() -> ran.set(true));

            assertEquals(1, bridge.activeContract().taskCount());
            bridge.engine().runPendingTasks(bridge.activeContract());
            assertTrue(ran.get(), "Mod 提交的任务应被执行");

            bridge.endTick();
            bootstrap.stop();
        } finally {
            runtime.close();
        }
    }

    @Test
    void tickBridge_recordsMetrics() {
        var runtime = startedRuntime("tick-metrics-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            bootstrap.beginDiscovery();
            bootstrap.beginPreparing();
            bootstrap.beginLoading();
            bootstrap.beginMinecraftBootstrap();
            bootstrap.start();

            TickBridge bridge = bootstrap.tickBridge();
            for (int i = 0; i < 4; i++) {
                bridge.beginTick();
                bridge.endTick();
            }

            assertNotNull(bridge.lastMetrics());
            assertNotNull(bridge.diagnostics());
            bootstrap.stop();
        } finally {
            runtime.close();
        }
    }

    // ── 事件与注册表桥接 ───────────────────────────────────────────────────

    @Test
    void eventBridge_postsAndReceivesEvents() {
        var runtime = startedRuntime("event-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            bootstrap.beginDiscovery();
            bootstrap.beginPreparing();
            bootstrap.beginLoading();
            bootstrap.beginMinecraftBootstrap();
            bootstrap.start();

            MinecraftEventBridge eventBridge = bootstrap.eventBridge();
            List<String> receivedEvents = new ArrayList<>();

            eventBridge.on(MinecraftEventBridge.TickEvent.class, e -> receivedEvents.add("tick:" + e.tick()));
            eventBridge.on(MinecraftEventBridge.ServerStartedEvent.class, e -> receivedEvents.add("started"));

            eventBridge.post(new MinecraftEventBridge.ServerStartedEvent());
            eventBridge.post(new MinecraftEventBridge.TickEvent(1));
            eventBridge.post(new MinecraftEventBridge.TickEvent(2));

            assertTrue(receivedEvents.contains("started"));
            assertTrue(receivedEvents.contains("tick:1"));
            assertTrue(receivedEvents.contains("tick:2"));

            bootstrap.stop();
        } finally {
            runtime.close();
        }
    }

    @Test
    void registryBridge_registerAndGet() {
        var runtime = startedRuntime("registry-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            bootstrap.beginDiscovery();
            bootstrap.beginPreparing();
            bootstrap.beginLoading();
            bootstrap.beginMinecraftBootstrap();
            bootstrap.start();

            MinecraftRegistryBridge registry = bootstrap.registryBridge();
            registry.register("blocks", "stone", "minecraft:stone");
            registry.register("blocks", "dirt", "minecraft:dirt");

            assertTrue(registry.get("blocks", "stone").isPresent());
            assertEquals("minecraft:stone", registry.get("blocks", "stone").get());
            assertTrue(registry.get("blocks", "missing").isEmpty());
            assertTrue(registry.keys("blocks").contains("stone"));

            bootstrap.stop();
        } finally {
            runtime.close();
        }
    }

    @Test
    void diagnostics_capturesSnapshot() {
        var runtime = startedRuntime("diag-test");
        try {
            RuntimeDiagnostics diagnostics = new RuntimeDiagnostics("test-diags", runtime.rootScope());
            runtime.rootScope().registerResource(diagnostics);

            diagnostics.setMetadata("test_run", "minecraft_integration");
            diagnostics.recordEvent("test", "snapshot_capture");

            var snapshot = diagnostics.captureSnapshot();
            assertNotNull(snapshot);
            assertTrue(snapshot.uptimeMillis() >= 0);
            assertEquals("minecraft_integration", snapshot.metadata().get("test_run"));
            assertNotNull(snapshot.threadInfo());
            assertTrue(snapshot.rootScope().totalScopes() >= 1);
        } finally {
            runtime.close();
        }
    }

    @Test
    void lifecycle_receivesPhaseChanges() {
        var runtime = startedRuntime("lifecycle-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            MinecraftLifecycle lifecycle = bootstrap.lifecycle();

            List<MinecraftLifecycle.Phase> phases = new ArrayList<>();
            bootstrap.beginDiscovery();
            bootstrap.beginPreparing();
            bootstrap.beginLoading();
            bootstrap.beginMinecraftBootstrap();
            bootstrap.start();

            phases.add(lifecycle.phase());
            assertEquals(MinecraftLifecycle.Phase.RUNNING, lifecycle.phase());
            assertTrue(lifecycle.isRunning());

            bootstrap.stop();
            assertEquals(MinecraftLifecycle.Phase.STOPPED, lifecycle.phase());
            assertFalse(lifecycle.isRunning());
        } finally {
            runtime.close();
        }
    }

    @Test
    void bootstrap_exposesTickEngineDiagnostics() {
        var runtime = startedRuntime("mc-diag-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            bootstrap.beginDiscovery();
            bootstrap.beginPreparing();
            bootstrap.beginLoading();
            bootstrap.beginMinecraftBootstrap();
            bootstrap.start();

            String diag = bootstrap.diagnostics();
            assertTrue(diag.contains("MinecraftBootstrap"));
            assertTrue(diag.contains("state=RUNNING"));
            assertTrue(diag.contains("TickEngine"));

            bootstrap.stop();
        } finally {
            runtime.close();
        }
    }

    @Test
    void bootstrap_stopIsIdempotent() {
        var runtime = startedRuntime("mc-idem-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            bootstrap.beginDiscovery();
            bootstrap.beginPreparing();
            bootstrap.beginLoading();
            bootstrap.beginMinecraftBootstrap();
            bootstrap.start();

            bootstrap.stop();
            assertDoesNotThrow(bootstrap::stop, "重复 stop 应幂等");
            assertEquals(BootstrapState.STOPPED, bootstrap.state());
        } finally {
            runtime.close();
        }
    }

    @Test
    void bootstrapStartTwiceIsRejected() {
        var runtime = startedRuntime("mc-twice-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            bootstrap.beginDiscovery();
            bootstrap.beginPreparing();
            bootstrap.beginLoading();
            bootstrap.beginMinecraftBootstrap();
            bootstrap.start();
            assertThrows(IllegalStateException.class, bootstrap::start);
        } finally {
            runtime.close();
        }
    }

    @Test
    void reflectiveTickSourceDelegatesToBridge() {
        var runtime = startedRuntime("tick-source-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            bootstrap.beginDiscovery();
            bootstrap.beginPreparing();
            bootstrap.beginLoading();
            bootstrap.beginMinecraftBootstrap();
            bootstrap.start();

            var source = new ReflectiveMinecraftTickSource(bootstrap.tickBridge());
            assertFalse(source.isReady());
            source.markReady();
            assertTrue(source.isReady());

            var contract = source.beginTick();
            assertNotNull(contract);
            source.endTick();

            assertEquals(1L, source.describe().contains("ticks=1")
                    ? 1L : bootstrap.tickBridge().currentTick());
            bootstrap.stop();
        } finally {
            runtime.close();
        }
    }

    @Test
    void tickContractIsCreatedWithWorldContext() {
        var runtime = startedRuntime("tick-contract-test");
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            bootstrap.beginDiscovery();
            bootstrap.beginPreparing();
            bootstrap.beginLoading();
            bootstrap.beginMinecraftBootstrap();
            bootstrap.start();

            var contract = bootstrap.tickBridge().beginTick();
            assertEquals("minecraft", contract.worldName());
            assertEquals(TickContract.TickPhase.PRE_TICK, contract.phase(),
                    "beginTick 应推进到 PRE_TICK");
            bootstrap.tickBridge().endTick();
            bootstrap.stop();
        } finally {
            runtime.close();
        }
    }
}