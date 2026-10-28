package org.loader.runtime.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StartupTimeline} 的语义：计数、嵌套、聚合、未归因。
 *
 * <p>这些断言检查的不是"数字对不对"——启动耗时本来就不该在单元测试里断言，
 * 它随机器抖动。检查的是<b>结构</b>：一段只记一次、子确实挂在父下面、
 * 扇出过多时聚合成一行、没打点的时间被算成"未归因"而不是偷偷消失。
 * 结构一旦坏了，时间线上的数字会看上去完全正常，却在误导人。
 */
class StartupTimelineTest {

    private static long ms(long nanos) {
        return nanos / 1_000_000L;
    }

    @Test
    @DisplayName("平坦打点按顺序记录")
    void flatMarksKeepOrder() {
        StartupTimeline t = new StartupTimeline("test");
        t.mark("a");
        t.mark("b");
        t.mark("c");

        List<StartupTimeline.Phase> phases = t.phases();
        assertEquals(3, phases.size());
        assertEquals("a", phases.get(0).name());
        assertEquals("b", phases.get(1).name());
        assertEquals("c", phases.get(2).name());
        phases.forEach(p -> assertEquals(0, p.depth(), "顶层打点深度必须是 0"));
    }

    @Test
    @DisplayName("open 内的打点成为子节点，而不是兄弟")
    void nestedMarkBecomesChild() {
        StartupTimeline t = new StartupTimeline("test");
        t.open("group");
        t.mark("inner");
        t.close();

        List<StartupTimeline.Phase> phases = t.phases();
        assertEquals(2, phases.size());
        StartupTimeline.Phase inner = phases.get(0);
        StartupTimeline.Phase group = phases.get(1);
        assertEquals("inner", inner.name());
        assertEquals(1, inner.depth(), "内层打点深度必须是 1");
        assertEquals("group", group.name());
        assertEquals(0, group.depth());

        String rendered = t.render();
        int groupLine = rendered.indexOf("group");
        int innerLine = rendered.indexOf("inner");
        assertTrue(innerLine > groupLine,
                "父行应在子行之前（渲染是前序），实际:\n" + rendered);
    }

    @Test
    @DisplayName("未闭合区间由 closeAll 由内向外关闭")
    void closeAllClosesRemainingFrames() {
        StartupTimeline t = new StartupTimeline("test");
        t.open("outer");
        t.open("inner");
        assertEquals(0, t.phases().size(), "未关闭前不应产生记录");

        t.closeAll();

        List<StartupTimeline.Phase> phases = t.phases();
        assertEquals(2, phases.size());
        assertEquals("inner", phases.get(0).name(), "先关内层");
        assertEquals("outer", phases.get(1).name(), "后关外层");
    }

    @Test
    @DisplayName("空栈 close 返回 -1 而不抛异常")
    void closeWithoutOpenIsNoOp() {
        StartupTimeline t = new StartupTimeline("test");
        assertEquals(-1L, t.close());
        assertEquals(-1L, t.close());
    }

    @Test
    @DisplayName("连续打点后未归因只剩残余，说明时间被真正覆盖")
    void unaccountedShrinksAfterCoverage() {
        StartupTimeline t = new StartupTimeline("test");
        for (int i = 0; i < 5; i++) {
            t.mark("step" + i);
        }
        long unaccounted = t.unaccountedNanos();
        // 反向断言（unaccounted >= 0）由 Math.max 保证恒真，测不出任何东西。
        // 有意义的断言是：5 次连续打点之后，剩下的只能是计时粒度级别的残余。
        assertTrue(unaccounted < 10_000_000L,
                "连续打点后未归因应 < 10ms，实际 " + ms(unaccounted) + "ms");
    }

    @Test
    @DisplayName("顶层保持时间顺序，即使后一段更短")
    void topLevelKeepsChronologicalOrder() {
        StartupTimeline t = new StartupTimeline("test");
        t.mark("first");
        t.mark("second");
        t.mark("third");

        String rendered = t.render();
        assertTrue(rendered.indexOf("first") < rendered.indexOf("second"));
        assertTrue(rendered.indexOf("second") < rendered.indexOf("third"));
    }

    @Test
    @DisplayName("扇出过多时只展示 top-N，其余聚合成一行")
    void deepFanOutIsAggregated() {
        StartupTimeline t = new StartupTimeline("test");
        t.open("initializeMods");
        for (int i = 0; i < 20; i++) {
            t.mark("phase-" + i);
        }
        t.close();

        String rendered = t.render(8);
        long shown = rendered.lines().filter(l -> l.contains("phase-")).count();
        assertEquals(8L, shown, "超过上限的兄弟应被截断:\n" + rendered);
        assertTrue(rendered.contains("个更短的阶段合计"),
                "截断的部分必须显式聚合，不能静默消失:\n" + rendered);
    }

    @Test
    @DisplayName("空时间线也能渲染，不抛异常")
    void emptyTimelineRenders() {
        StartupTimeline t = new StartupTimeline("empty");
        String rendered = t.render();
        assertTrue(rendered.contains("empty"));
        assertTrue(rendered.contains("总"));
    }

    @Test
    @DisplayName("耗时统计的量级自洽：各段之和不超过总时长")
    void durationsDoNotExceedTotal() {
        StartupTimeline t = new StartupTimeline("test");
        t.mark("a");
        t.mark("b");
        t.mark("c");

        long sum = t.phases().stream().mapToLong(StartupTimeline.Phase::durationNanos).sum();
        assertTrue(sum <= t.totalNanos() + 1_000_000L,
                "各段之和不应超过总时长（允许 1ms 的计时粒度误差）: sum="
                        + ms(sum) + "ms total=" + ms(t.totalNanos()) + "ms");
    }
}
