package org.loader.runtime;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.*;
import org.loader.runtime.minecraft.*;
import org.loader.runtime.observability.RuntimeDiagnostics;
import org.loader.runtime.scheduler.Scheduler;
import org.loader.runtime.service.EventBus;
import org.loader.runtime.tick.TickContract;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成测试：Runtime bootstrap → Minecraft 集成 → 真实 tick 契约 → 关闭清理。
 *
 * <p>验证的是平台内部各层协同一致，不启动真实 Minecraft。
 * 真实 Minecraft 26.2 启动由 CI smoke test 覆盖。
 */
class EndToEndIntegrationTest {

    /** 走完状态机到 RUNNING。 */
    private static MinecraftBootstrap startedBootstrap(org.loader.runtime.kernel.Runtime rt) {
        MinecraftBootstrap b = new MinecraftBootstrap(rt);
        b.beginDiscovery();
        b.beginPreparing();
        b.beginLoading();
        b.beginMinecraftBootstrap();
        b.start();
        return b;
    }

    @Test
    void full_lifecycle_bootstrap_running_shutdown() throws Exception {
        var runtime = org.loader.runtime.kernel.Runtime.create("e2e-test");
        runtime.start();
        assertTrue(runtime.isRunning());

        RuntimeDiagnostics diagnostics = new RuntimeDiagnostics("e2e-diags", runtime.rootScope());
        runtime.rootScope().registerResource(diagnostics);

        MinecraftBootstrap bootstrap = startedBootstrap(runtime);

        // 事件监听在 start 之后注册（started 事件已发出）
        MinecraftEventBridge eventBridge = bootstrap.eventBridge();
        List<String> received = new ArrayList<>();
        eventBridge.on(MinecraftEventBridge.TickEvent.class, e -> received.add("tick:" + e.tick()));

        assertTrue(bootstrap.isRunning());
        assertEquals(LifecycleState.RUNNING, bootstrap.scope().state());

        // 真实 tick 契约：begin/end 成对
        TickBridge bridge = bootstrap.tickBridge();
        AtomicInteger tickCount = new AtomicInteger(0);
        for (int i = 1; i <= 3; i++) {
            var contract = bridge.beginTick();
            bridge.engine().advanceToSchedule(contract);
            bridge.engine().advanceToCoreTick(contract);
            bridge.onTick(tickCount::incrementAndGet);
            bridge.engine().runPendingTasks(contract);
            var metrics = bridge.endTick();
            assertNotNull(metrics);
            eventBridge.post(new MinecraftEventBridge.TickEvent(i));
        }

        assertEquals(3, bridge.currentTick());
        assertEquals(3, tickCount.get(), "Mod 任务应每 tick 执行一次");
        assertTrue(received.contains("tick:1"));
        assertTrue(received.contains("tick:3"));

        // 注册表桥接
        var registry = bootstrap.registryBridge();
        registry.register("blocks", "stone", "minecraft:stone");
        assertEquals("minecraft:stone", registry.get("blocks", "stone").orElseThrow());

        // 关闭
        bootstrap.stop();
        assertFalse(bootstrap.isRunning());
        runtime.close();
        assertFalse(runtime.isRunning());
    }

    @Test
    void minecraft_lifecycle_reachesRunningThenStopped() {
        var runtime = org.loader.runtime.kernel.Runtime.create("lifecycle-test");
        runtime.start();
        try {
            MinecraftBootstrap bootstrap = new MinecraftBootstrap(runtime);
            MinecraftLifecycle lifecycle = bootstrap.lifecycle();
            assertEquals(MinecraftLifecycle.Phase.CREATED, lifecycle.phase());

            bootstrap.beginDiscovery();
            bootstrap.beginPreparing();
            bootstrap.beginLoading();
            bootstrap.beginMinecraftBootstrap();
            bootstrap.start();

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
    void tickContract_strictStageOrderingEndToEnd() {
        var runtime = org.loader.runtime.kernel.Runtime.create("tick-stages-e2e");
        runtime.start();
        try {
            MinecraftBootstrap bootstrap = startedBootstrap(runtime);
            TickBridge bridge = bootstrap.tickBridge();
            List<String> observed = new ArrayList<>();

            bridge.engine().onPreTick((c, p) -> observed.add(p.name()));
            bridge.engine().onSchedule((c, p) -> observed.add(p.name()));
            bridge.engine().onCoreTick((c, p) -> observed.add(p.name()));
            bridge.engine().onAsyncCompletion((c, p) -> observed.add(p.name()));
            bridge.engine().onPostTick((c, p) -> observed.add(p.name()));
            bridge.engine().onTickEnd((c, p) -> observed.add(p.name()));

            var contract = bridge.beginTick();
            assertEquals(TickContract.TickPhase.PRE_TICK, contract.phase());

            bridge.engine().advanceToSchedule(contract);
            bridge.engine().advanceToCoreTick(contract);
            bridge.endTick();

            assertEquals(List.of("PRE_TICK", "SCHEDULE", "CORE_TICK",
                    "ASYNC_COMPLETION", "POST_TICK", "TICK_END"), observed,
                    "阶段必须严格按序，不跳步不重复");

            bootstrap.stop();
        } finally {
            runtime.close();
        }
    }

    @Test
    void modTaskException_doesNotBreakTickLoop() {
        var runtime = org.loader.runtime.kernel.Runtime.create("tick-exception-e2e");
        runtime.start();
        try {
            MinecraftBootstrap bootstrap = startedBootstrap(runtime);
            TickBridge bridge = bootstrap.tickBridge();

            AtomicInteger survived = new AtomicInteger();
            for (int i = 0; i < 3; i++) {
                var contract = bridge.beginTick();
                bridge.engine().advanceToSchedule(contract);
                bridge.onTick(() -> {
                    throw new RuntimeException("mod blew up");
                });
                bridge.onTick(survived::incrementAndGet);
                bridge.engine().runPendingTasks(contract);

                var metrics = bridge.endTick();
                assertNotNull(metrics.error(), "异常应被记录到 tick 契约");
                assertEquals(TickContract.TaskState.COMPLETED,
                        contract.tasks().get(1).state(),
                        "异常任务之后的任务仍应执行");
            }

            assertEquals(3, survived.get(), "每个 tick 的正常任务都应执行");
            assertEquals(3, bridge.engine().failedTickCount(),
                    "三个 tick 都记录了失败");

            bootstrap.stop();
        } finally {
            runtime.close();
        }
    }

    @Test
    void capability_grant_revoke_and_availability() {
        var runtime = org.loader.runtime.kernel.Runtime.create("cap-e2e");
        runtime.start();
        try {
            Scope testScope = runtime.rootScope().createChild("cap-test");

            var token = testScope.grantCapability(String.class, "test-value");
            assertTrue(token.isActive());
            assertEquals("test-value", token.get());
            assertTrue(testScope.getCapability(String.class).isPresent());

            token.revoke();
            assertFalse(token.isActive());
            assertTrue(testScope.getCapability(String.class).isEmpty());
        } finally {
            runtime.close();
        }
    }

    @Test
    void scheduler_metrics_execute_and_report() throws Exception {
        var runtime = org.loader.runtime.kernel.Runtime.create("metrics-e2e");
        runtime.start();
        try {
            Scheduler scheduler = runtime.scheduler();
            AtomicInteger counter = new AtomicInteger(0);

            for (int i = 0; i < 10; i++) {
                scheduler.submit(runtime.rootScope(), counter::incrementAndGet).await();
            }

            var metrics = scheduler.metrics();
            assertTrue(metrics.completed() >= 10);
            assertEquals(10, counter.get());
            assertTrue(metrics.totalExecutionTimeNanos() > 0);
            assertTrue(metrics.averageExecutionTimeMs() >= 0);
        } finally {
            runtime.close();
        }
    }

    @Test
    void eventBridge_typed_dispatch() {
        var runtime = org.loader.runtime.kernel.Runtime.create("event-e2e");
        runtime.start();
        try {
            MinecraftBootstrap bootstrap = startedBootstrap(runtime);

            EventBus eventBus = new EventBus("test-bus", runtime.rootScope());
            runtime.rootScope().registerResource(eventBus);

            List<String> received = new ArrayList<>();
            eventBus.addListener(String.class, received::add);
            eventBus.post("hello");
            eventBus.post("world");
            eventBus.post(42); //类型不匹配，不应被 String 监听器收到

            assertEquals(2, received.size());
            assertTrue(received.contains("hello"));
            assertTrue(received.contains("world"));

            bootstrap.stop();
        } finally {
            runtime.close();
        }
    }

    @Test
    void dispose_and_cleanup_all_scopes() {
        var runtime = org.loader.runtime.kernel.Runtime.create("dispose-e2e");
        runtime.start();

        Scope modScope = runtime.rootScope().createChild("mod-a");
        modScope.createChild("module-a1");

        modScope.registerResource(new EventBus("mod-bus", modScope));
        modScope.registerResource(new Scheduler("mod-scheduler", modScope));

        runtime.close();
        assertTrue(runtime.rootScope().isStopped(), "关闭 Runtime 应递归清理所有 Scope");
    }

    @Test
    void scopeHierarchy_isCreatedUnderRoot() {
        var runtime = org.loader.runtime.kernel.Runtime.create("scope-tree-e2e");
        runtime.start();
        try {
            MinecraftBootstrap bootstrap = startedBootstrap(runtime);
            Scope mcScope = bootstrap.scope();

            assertEquals(runtime.rootScope(), mcScope.parent(),
                    "Minecraft Scope 应挂在 RootScope 之下");
            assertFalse(mcScope.children().stream()
                            .anyMatch(c -> c.id().equals("render-scope")),
                    "SERVER 环境不应有 render scope；CLIENT 才有");

            bootstrap.stop();
        } finally {
            runtime.close();
        }
    }

    @Test
    void tickDiagnostics_exposeEngineMetrics() {
        var runtime = org.loader.runtime.kernel.Runtime.create("tick-diag-e2e");
        runtime.start();
        try {
            MinecraftBootstrap bootstrap = startedBootstrap(runtime);
            TickBridge bridge = bootstrap.tickBridge();
            for (int i = 0; i < 5; i++) {
                bridge.beginTick();
                bridge.endTick();
            }

            String diag = bridge.diagnostics();
            assertTrue(diag.contains("tickCount=5"));
            assertTrue(diag.contains("avg="));
            bootstrap.stop();
        } finally {
            runtime.close();
        }
    }

    @Test
    void bootstrapStop_releasesSchedulerAndTickResources() {
        var runtime = org.loader.runtime.kernel.Runtime.create("release-e2e");
        runtime.start();
        try {
            MinecraftBootstrap bootstrap = startedBootstrap(runtime);
            assertFalse(bootstrap.scheduler().isClosed());
            assertFalse(bootstrap.tickEngine().isClosed());
            assertFalse(bootstrap.tickBridge().isClosed());

            bootstrap.stop();

            assertTrue(bootstrap.scheduler().isClosed(), "停止后 Scheduler 必须关闭");
            assertTrue(bootstrap.tickEngine().isClosed(), "停止后 TickEngine 必须关闭");
            assertTrue(bootstrap.tickBridge().isClosed(), "停止后 TickBridge 必须关闭");
        } finally {
            runtime.close();
        }
    }

    @Test
    void externalResource_releasedOnStop() {
        var runtime = org.loader.runtime.kernel.Runtime.create("extrel-e2e");
        runtime.start();
        try {
            MinecraftBootstrap bootstrap = startedBootstrap(runtime);
            AtomicBoolean released = new AtomicBoolean(false);
            bootstrap.registerExternalResource(() -> released.set(true));

            bootstrap.stop();
            assertTrue(released.get(), "stop 必须释放已注册的外部资源");
        } finally {
            runtime.close();
        }
    }
}