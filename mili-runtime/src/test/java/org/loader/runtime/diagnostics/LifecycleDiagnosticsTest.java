package org.loader.runtime.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断设施的行为：审计环的覆盖语义、关闭期报告的判读口径、
 * 看门狗的停滞识别。
 *
 * <p>看门狗部分<b>不依赖真实时钟等待</b>：直接调用 {@code inspect()}
 * 驱动一次检查，再用一个极短阈值触发。用 {@code Thread.sleep} 等停滞的
 * 测试在 CI 上必然波动，而它要验证的只是"到了阈值就报告"这个判断，
 * 不是线程调度。
 */
class LifecycleDiagnosticsTest {

    @AfterEach
    void resetSink() {
        TickWatchdog.installSink(null);
    }

    // ── LifecycleAudit ────────────────────────────────────────────────────

    @Test
    @DisplayName("审计环满后覆盖最旧的，保留最近的历史")
    void ringKeepsMostRecent() {
        LifecycleAudit audit = new LifecycleAudit();
        int extra = 40;
        for (int i = 0; i < LifecycleAudit.CAPACITY + extra; i++) {
            audit.record("test.event", null, "e" + i);
        }
        assertEquals(LifecycleAudit.CAPACITY, audit.size(), "有效条数不应超过容量");
        assertEquals(LifecycleAudit.CAPACITY + extra, audit.totalRecorded(),
                "累计条数要如实反映被覆盖的部分");

        List<LifecycleAudit.Event> recent = audit.recent(3);
        assertEquals(3, recent.size());
        assertEquals("e" + (LifecycleAudit.CAPACITY + extra - 1),
                recent.get(2).detail(), "最后一条必须是最新的");
    }

    @Test
    @DisplayName("空审计可渲染，不抛异常")
    void emptyAuditRenders() {
        LifecycleAudit audit = new LifecycleAudit();
        assertTrue(audit.render(10).contains("无事件"));
        assertTrue(audit.recent(10).isEmpty());
    }

    @Test
    @DisplayName("落盘带头部，且是追加而不是覆盖")
    void dumpAppends(@TempDir Path dir) throws Exception {
        LifecycleAudit audit = new LifecycleAudit();
        audit.record("mod.failed", "alpha", "boom");

        Path file = dir.resolve("nested/mili-audit.log");
        assertTrue(audit.dumpTo(file, 10, "=== 第一次 ==="));
        assertTrue(audit.dumpTo(file, 10, "=== 第二次 ==="));

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("=== 第一次 ==="));
        assertTrue(text.contains("=== 第二次 ==="), "第二次写必须追加，不能覆盖上一份墓碑");
        assertTrue(text.contains("alpha"));
    }

    @Test
    @DisplayName("渲染包含类别与归属，便于直接 grep")
    void renderCarriesKindAndOwner() {
        LifecycleAudit audit = new LifecycleAudit();
        audit.record("tick.stall", "alpha", "2000ms");
        String text = audit.render(5);
        assertTrue(text.contains("tick.stall"));
        assertTrue(text.contains("[alpha]"));
    }

    // ── ShutdownAudit ─────────────────────────────────────────────────────

    @Test
    @DisplayName("基线缺失时明确说明，而不是假装干净")
    void reportWithoutBaselineSaysSo() {
        String text = ShutdownAudit.report(null, ShutdownAudit.capture(), Map.of("alpha", 10));
        assertTrue(text.contains("基线缺失"), text);
    }

    @Test
    @DisplayName("新增线程被列出；平台自有线程被排除")
    void reportListsOnlyForeignThreads() {
        ShutdownAudit.Snapshot base =
                new ShutdownAudit.Snapshot(3, Set.of("a", "b", "c"), 0L);
        ShutdownAudit.Snapshot stop =
                new ShutdownAudit.Snapshot(5, Set.of("a", "b", "c", "leaked-pool-1",
                        "mili-watchdog-tick"), 0L);

        String text = ShutdownAudit.report(base, stop, Map.of("alpha", 120));
        assertTrue(text.contains("leaked-pool-1"), text);
        assertFalse(text.contains("mili-watchdog-tick"),
                "平台自有线程不该被当成残留:\n" + text);
        assertTrue(text.contains("值得查"), "线程数增长时应给出提示");
        assertTrue(text.contains("alpha"), "已加载类快照应出现在报告里");
    }

    @Test
    @DisplayName("没有新增线程时给出明确的'无'")
    void reportWithoutLeaks() {
        ShutdownAudit.Snapshot base = new ShutdownAudit.Snapshot(2, Set.of("a", "b"), 0L);
        ShutdownAudit.Snapshot stop = new ShutdownAudit.Snapshot(2, Set.of("a", "b"), 0L);
        assertTrue(ShutdownAudit.report(base, stop, Map.of()).contains("新增线程: 无"));
    }

    // ── TickWatchdog ──────────────────────────────────────────────────────

    @Test
    @DisplayName("enter/exit 维护当前归属")
    void tracksCurrentOwner() {
        TickWatchdog w = new TickWatchdog("test");
        w.enter("alpha", "task-1");
        assertEquals("alpha", w.currentMod());
        assertEquals("task-1", w.currentTask());
        assertTrue(w.heldNanos() > 0L);

        w.exit();
        assertEquals(0L, w.heldNanos(), "退出后不应继续累计持有时间");
        assertTrue(w.describe().contains("alpha"));
    }

    @Test
    @DisplayName("超过阈值触发一次报告，同一次停滞不刷屏")
    void reportsStallOncePerEpisode() throws Exception {
        List<String> messages = new ArrayList<>();
        TickWatchdog.installSink(messages::add);

        TickWatchdog w = new TickWatchdog("test", 50_000_000L, 10L);
        w.enter("alpha", "slow-task");
        // 阈值取 50ms 而不是 1ms：两次 inspect() 之间若被调度器插进 >1ms，
        // 去重窗口就失效了，断言会偶发失败。50ms 给调度留出余量，
        // 又不至于让测试等太久。
        Thread.sleep(60L);

        w.inspect();
        w.inspect();
        assertEquals(1, messages.size(),
                "同一次停滞在阈值窗口内只应报告一次:\n" + messages);
        assertTrue(messages.get(0).contains("tick 停滞"));
        assertTrue(messages.get(0).contains("alpha"));

        w.exit();
        w.inspect();
        assertEquals(1, messages.size(), "退出后不应再报告");
    }

    @Test
    @DisplayName("未超过阈值不报告")
    void quietWhenUnderThreshold() {
        List<String> messages = new ArrayList<>();
        TickWatchdog.installSink(messages::add);

        TickWatchdog w = new TickWatchdog("test", 500_000_000L, 10L);
        w.enter("alpha", "fast");
        w.inspect();
        assertTrue(messages.isEmpty());
        w.exit();
    }

    @Test
    @DisplayName("start/close 成对，关闭后不再运行")
    void startsAndCloses() {
        TickWatchdog w = new TickWatchdog("lifecycle");
        w.start();
        assertTrue(w.isRunning());
        w.close();
        assertFalse(w.isRunning());
    }
}
