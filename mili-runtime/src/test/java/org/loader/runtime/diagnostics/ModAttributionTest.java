package org.loader.runtime.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归因的正确性：按归属聚合、未归因单列、槽位耗尽不越界。
 *
 * <p>这里不断言真实耗时（那是基准测试的事），只断言<b>记账规则</b>：
 * 同一归属的多次执行被累加、峰值取最大、未归因不会被算进任何一个 Mod。
 * 记账规则一旦错了，归因报告会给出一个"看起来很精确"的错误答案 ——
 * 那比没有归因更糟，因为人会信它。
 */
class ModAttributionTest {

    @AfterEach
    void cleanup() {
        ModOwners.clearForTests();
    }

    @Test
    @DisplayName("同一归属累加，峰值取最大")
    void aggregatesPerOwner() {
        ModAttribution a = new ModAttribution();
        a.record("alpha", 100);
        a.record("alpha", 200);
        a.record("beta", 50);

        List<ModAttribution.Row> top = a.top(10);
        ModAttribution.Row alpha = top.stream()
                .filter(r -> r.owner().equals("alpha")).findFirst().orElseThrow();
        ModAttribution.Row beta = top.stream()
                .filter(r -> r.owner().equals("beta")).findFirst().orElseThrow();

        assertEquals(300, alpha.totalNanos());
        assertEquals(2, alpha.calls());
        assertEquals(200, alpha.peakNanos(), "峰值应是单次最大值，不是总和");
        assertEquals(50, beta.totalNanos());
        assertEquals(350, a.totalNanos());
        assertEquals(3, a.totalCalls());
    }

    @Test
    @DisplayName("降序排列：最贵的在最前面")
    void sortedByCostDescending() {
        ModAttribution a = new ModAttribution();
        a.record("cheap", 10);
        a.record("expensive", 1000);
        a.record("middle", 100);

        List<ModAttribution.Row> top = a.top(3);
        assertEquals("expensive", top.get(0).owner());
        assertEquals("middle", top.get(1).owner());
        assertEquals("cheap", top.get(2).owner());
    }

    @Test
    @DisplayName("未归因单独成槽，不混进任何 Mod")
    void unattributedStaysSeparate() {
        ModAttribution a = new ModAttribution();
        a.record((String) null, 70);
        a.record("alpha", 30);

        List<ModAttribution.Row> top = a.top(10);
        ModAttribution.Row unknown = top.stream()
                .filter(r -> r.owner().equals(ModOwners.UNATTRIBUTED))
                .findFirst().orElseThrow();
        assertEquals(70, unknown.totalNanos(), "平台自身开销不能被记到某个 Mod 头上");

        ModAttribution.Row alpha = top.stream()
                .filter(r -> r.owner().equals("alpha")).findFirst().orElseThrow();
        assertEquals(30, alpha.totalNanos());
    }

    @Test
    @DisplayName("渲染总是显示未归因行，即使其余为 0")
    void renderAlwaysShowsUnattributed() {
        ModAttribution a = new ModAttribution();
        a.record("alpha", 10);
        String text = a.render(5);
        assertTrue(text.contains("alpha"));
        assertTrue(text.contains(ModOwners.UNATTRIBUTED),
                "缺少未归因行，读者会以为所有时间都属于 Mod:\n" + text);
    }

    @Test
    @DisplayName("槽位耗尽时并入未归因，不越界、不抛异常")
    void overflowIsAbsorbed() {
        ModAttribution a = new ModAttribution();
        for (int i = 0; i < ModAttribution.MAX_SLOTS + 40; i++) {
            a.record("owner-" + i, 1);
        }
        assertTrue(a.ownerCount() <= ModAttribution.MAX_SLOTS);
        assertEquals(ModAttribution.MAX_SLOTS + 40, a.totalCalls(),
                "溢出的记录不能丢，必须并入未归因槽");
    }

    @Test
    @DisplayName("reset 清计数但保留槽位，下次不会重复分配")
    void resetKeepsSlots() {
        ModAttribution a = new ModAttribution();
        a.record("alpha", 100);
        int owners = a.ownerCount();
        a.reset();
        assertEquals(0, a.totalCalls());
        assertEquals(owners, a.ownerCount(), "槽位应保留，否则热路径每次都要重新分配");
    }

    @Test
    @DisplayName("类 → modId 反查：登记后立刻生效")
    void resolvesOwnerFromClassLoader() {
        ModOwners.register(ModAttributionTest.class.getClassLoader(), "testmod");
        assertEquals("testmod", ModOwners.idOf(ModAttributionTest.class));
        assertTrue(ModOwners.registeredCount() >= 1);
        assertNotNull(ModOwners.snapshot());
    }

    @Test
    @DisplayName("未登记的类加载器返回未归因，而不是抛异常")
    void unknownLoaderIsUnattributed() {
        assertEquals(ModOwners.UNATTRIBUTED, ModOwners.idOf(ClassLoader.getPlatformClassLoader()));
        assertEquals(ModOwners.UNATTRIBUTED, ModOwners.idOf((Class<?>) null));
        assertFalse(ModOwners.UNATTRIBUTED.isEmpty());
    }
}
