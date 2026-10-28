package org.loader.runtime.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.runtime.kernel.Runtime;
import org.loader.runtime.tick.TickContract;
import org.loader.runtime.tick.TickEngine;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归因链路的端到端验证：任务类 → 类加载器 → modId → 累加器。
 *
 * <p>这条链断开的方式很隐蔽：编译期完全合法，运行期只是<b>归因数字为 0</b>，
 * 而"0 毫秒"看起来像一个很快的 Mod。所以必须有一条测试真的跑一次 tick、
 * 提交一次任务、再断言归因结果里出现了那个 Mod。
 *
 * <p>放在 {@code diagnostics} 包而不是 {@code tick} 包：需要访问
 * {@link ModOwners#clearForTests()}，而那个钩子刻意保持包级可见 ——
 * 清表只该被归因相关的测试调用，不该成为公开 API。
 */
class TickAttributionTest {

    @AfterEach
    void cleanup() {
        ModOwners.clearForTests();
    }

    /** 显式类而不是 lambda：lambda 的隐藏类在归因上行为依赖实现细节。 */
    private static final class NoopWork implements Runnable {
        @Override
        public void run() {
            // 什么都不做 —— 这里测的是归因，不是耗时
        }
    }

    /**
     * 第二个任务类，专供"未登记"用例。
     *
     * <p>不复用 {@link NoopWork}：{@code ModOwners} 的解析结果按
     * {@code Class} 缓存（{@code ClassValue}），而那张缓存<b>无法按类失效</b>。
     * 同一测试 JVM 里先用 NoopWork 归因到 alpha，再清表，NoopWork 仍会
     * 命中旧缓存 —— 用两个类才能让两个用例互不污染。
     */
    private static final class UnregisteredWork implements Runnable {
        @Override
        public void run() {
        }
    }

    @Test
    @DisplayName("已登记的 Mod 提交的任务被归因到它名下")
    void attributesTaskToRegisteredOwner() {
        Runtime runtime = Runtime.create("attr");
        TickEngine engine = new TickEngine("attr-engine",
                runtime.rootScope().createChild("attr"));
        try {
            ModOwners.register(TickAttributionTest.class.getClassLoader(), "alpha");

            TickContract contract = engine.beginTick();
            engine.advanceToSchedule(contract);
            contract.submitTask("work", new NoopWork(), TickContract.TaskPriority.NORMAL);
            engine.advanceToCoreTick(contract);
            engine.runPendingTasks(contract);
            engine.endTick(contract);

            List<ModAttribution.Row> rows = engine.attribution().top(10);
            ModAttribution.Row alpha = rows.stream()
                    .filter(r -> r.owner().equals("alpha"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("任务应归因到 alpha，实际: " + rows));
            assertTrue(alpha.calls() >= 1, "至少记一次调用");
        } finally {
            engine.close();
            runtime.close();
        }
    }

    @Test
    @DisplayName("未登记的任务归入未归因，而不是被丢弃")
    void unregisteredWorkGoesToUnattributed() {
        ModOwners.clearForTests();

        Runtime runtime = Runtime.create("attr2");
        TickEngine engine = new TickEngine("attr-engine-2",
                runtime.rootScope().createChild("attr2"));
        try {
            TickContract contract = engine.beginTick();
            contract.submitTask("work", new UnregisteredWork(),
                    TickContract.TaskPriority.NORMAL);
            engine.advanceToCoreTick(contract);
            engine.runPendingTasks(contract);
            engine.endTick(contract);

            List<ModAttribution.Row> rows = engine.attribution().top(10);
            assertTrue(rows.stream().anyMatch(r -> r.owner().equals(ModOwners.UNATTRIBUTED)),
                    "未归因的耗时必须落在一个明确的行里，不能消失: " + rows);
        } finally {
            engine.close();
            runtime.close();
        }
    }
}
