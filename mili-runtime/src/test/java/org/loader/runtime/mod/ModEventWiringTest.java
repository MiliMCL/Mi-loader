package org.loader.runtime.mod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.event.TickEvent;
import org.loader.api.event.TickPhase;
import org.loader.runtime.kernel.Runtime;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.tick.TickEngine;
import org.loader.runtime.tick.TickEventBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「Mod 侧订阅 → 平台总线 → 引擎发布」这条链的端到端验证。
 *
 * <p>这是全平台历史上断得最彻底的一环：{@code ctx.events()} 曾经返回每个 Mod
 * 自己的私有总线，而 tick 引擎在另一条总线上发布（甚至根本不发布）。
 * 结果是 Mod 订阅 {@code TickEvent} 编译通过、订阅成功、运行无异常，
 * 然后永远收不到事件 —— 没有任何一处会报错。
 *
 * <p>本测试把三件事钉在一起：
 * <ol>
 *   <li>{@code ModContext.events()} 在装配模式下返回平台总线；</li>
 *   <li>{@code TickEventBridge} 把引擎的 tick 边界发布到同一条总线；</li>
 *   <li>Mod 停止时监听器被自动注销（不会在卸载后继续被调用）。</li>
 * </ol>
 */
class ModEventWiringTest {

    private static ModContext contextFor(Runtime runtime, String modId) {
        Scope scope = runtime.rootScope().createChild("mod:" + modId);
        scope.driveToRunning();
        ModManifest manifest = ModManifest.bound(modId, modId, "1.0.0",
                "0.1.0", org.loader.api.VersionInfo.ABI_VERSION, "26.2");
        Mod mod = new Mod(manifest, scope, ModEventWiringTest.class.getClassLoader());
        return new ModContext(mod, runtime, runtime.tickEngine(), null);
    }

    @Test
    @DisplayName("Mod 通过 ctx.events() 能收到 TickEvent")
    void modReceivesTickEvents() {
        Runtime runtime = Runtime.create("wiring");
        runtime.start();
        try {
            TickEngine engine = new TickEngine("engine", runtime.rootScope());
            runtime.tickEngine().set(engine);
            TickEventBridge.install(engine, runtime.rootScope(), runtime.events());

            ModContext ctx = contextFor(runtime, "mod-a");
            var api = new ApiModContext(ctx);

            List<TickEvent> received = new ArrayList<>();
            api.events().subscribe(TickEvent.class, received::add);

            var contract = engine.beginTick();
            engine.endTick(contract);

            assertEquals(2, received.size(), "一个 tick 应产生 START 与 END 两条事件");
            assertEquals(TickPhase.START, received.get(0).phase());
            assertEquals(TickPhase.END, received.get(1).phase());
            assertEquals(1L, received.get(0).tick());

            engine.close();
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("两个 Mod 共享平台总线 —— A 发布的事件 B 能收到")
    void crossModEvents() {
        Runtime runtime = Runtime.create("wiring");
        runtime.start();
        try {
            ModContext ctxA = contextFor(runtime, "mod-a");
            ModContext ctxB = contextFor(runtime, "mod-b");
            var apiA = new ApiModContext(ctxA);
            var apiB = new ApiModContext(ctxB);

            List<String> bReceived = new ArrayList<>();
            apiB.events().subscribe(PingEvent.class, e -> bReceived.add(e.payload()));

            apiA.events().post(new PingEvent("hello"));

            assertEquals(List.of("hello"), bReceived,
                    "共享总线是跨 Mod 通信的前提；私有总线时期这里永远是空");
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("Mod 停止后监听器自动注销，不再收到事件")
    void listenersAreRemovedWhenModStops() {
        Runtime runtime = Runtime.create("wiring");
        runtime.start();
        try {
            ModContext ctx = contextFor(runtime, "mod-a");
            var api = new ApiModContext(ctx);

            AtomicInteger hits = new AtomicInteger();
            api.events().subscribe(PingEvent.class, e -> hits.incrementAndGet());

            api.events().post(new PingEvent("before"));
            assertEquals(1, hits.get());

            ctx.scope().shutdown();

            api.events().post(new PingEvent("after"));
            assertEquals(1, hits.get(),
                    "Mod 停止后其监听器必须从平台总线摘除 —— 否则会调用到已释放的对象");
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("订阅返回的 Subscription 可精确退订，不影响其他监听器")
    void subscriptionUnsubscribe() {
        Runtime runtime = Runtime.create("wiring");
        runtime.start();
        try {
            ModContext ctx = contextFor(runtime, "mod-a");
            var api = new ApiModContext(ctx);

            AtomicInteger a = new AtomicInteger();
            AtomicInteger b = new AtomicInteger();
            var sub = api.events().subscribe(PingEvent.class, e -> a.incrementAndGet());
            api.events().subscribe(PingEvent.class, e -> b.incrementAndGet());

            api.events().post(new PingEvent("1"));
            sub.unsubscribe();
            api.events().post(new PingEvent("2"));

            assertEquals(1, a.get());
            assertEquals(2, b.get());
            assertFalse(sub.isActive());
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("超类型订阅能收到子类型事件（Mod 视角）")
    void supertypeSubscriptionThroughContext() {
        Runtime runtime = Runtime.create("wiring");
        runtime.start();
        try {
            ModContext ctx = contextFor(runtime, "mod-a");
            var api = new ApiModContext(ctx);

            List<Object> seen = new ArrayList<>();
            api.events().subscribe(Object.class, seen::add);

            api.events().post(new PingEvent("x"));

            assertEquals(1, seen.size());
            assertInstanceOf(PingEvent.class, seen.get(0));
        } finally {
            runtime.close();
        }
    }

    record PingEvent(String payload) {
    }
}
