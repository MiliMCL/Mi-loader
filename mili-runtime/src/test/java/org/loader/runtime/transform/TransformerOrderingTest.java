package org.loader.runtime.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.transform.MiliTransformer;
import org.loader.api.transform.TransformationContext;
import org.loader.api.transform.TransformationPhase;
import org.loader.api.transform.TransformationResult;
import org.loader.runtime.transform.engine.TransformerOrdering;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转换器排序确定性测试。
 *
 * <h2>为什么「确定性」本身值得测</h2>
 * 同一组 Mod，在不同机器上必须产出<b>字节级相同</b>的类。
 * 若执行顺序依赖 HashMap 遍历、类加载时机或并发调度，
 * 就会出现「A 机器上游戏正常、B 机器上 tick 数偶尔翻倍」——
 * 这类问题几乎无法复现，也无法排查。
 *
 * <p>因此排序必须是全序：<b>阶段 → 优先级 → id 字典序</b>，
 * 且 id 相同时必须能给出确定答案（本实现下id 全局唯一，
 * 由 {@code TransformerRegistry} 在注册期强制）。
 */
@DisplayName("转换器排序")
class TransformerOrderingTest {

    /** 构造一个只有身份信息的测试转换器。 */
    private static MiliTransformer stub(
            String id, TransformationPhase phase, int priority) {
        return new MiliTransformer() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String minecraftVersion() {
                return "26.2";
            }

            @Override
            public TransformationPhase phase() {
                return phase;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public TransformationResult transform(TransformationContext context) {
                return new TransformationResult.Unchanged();
            }
        };
    }

    private static List<String> idsOf(List<MiliTransformer> transformers) {
        List<String> ids = new ArrayList<>();
        for (MiliTransformer t : transformers) {
            ids.add(t.id());
        }
        return ids;
    }

    @Test
    @DisplayName("阶段顺序固定：EARLY → CORE → MINECRAFT → MOD → LATE")
    void phaseOrderIsFixed() {
        // 顺序有实际语义：
        // CORE 承载 tick 正确性，必须最先完成接线；
        // MOD 在平台接线之后跑，才能拿到已接线的 TickEngine；
        // LATE 用于收尾（如统计、诊断）。
        List<MiliTransformer> input = List.of(
                stub("d-late", TransformationPhase.LATE, 0),
                stub("c-mod", TransformationPhase.MOD, 0),
                stub("a-early", TransformationPhase.EARLY, 0),
                stub("b-core", TransformationPhase.CORE, 0),
                stub("e-minecraft", TransformationPhase.MINECRAFT, 0));

        assertEquals(List.of("a-early", "b-core", "e-minecraft", "c-mod", "d-late"),
                idsOf(TransformerOrdering.sorted(input)));
    }

    @Test
    @DisplayName("同阶段内按 priority 升序")
    void priorityOrdersWithinPhase() {
        List<MiliTransformer> input = List.of(
                stub("p-high", TransformationPhase.MOD, 100),
                stub("p-low", TransformationPhase.MOD, -50),
                stub("p-mid", TransformationPhase.MOD, 0));

        assertEquals(List.of("p-low", "p-mid", "p-high"),
                idsOf(TransformerOrdering.sorted(input)));
    }

    @Test
    @DisplayName("阶段优先于优先级 —— 低优先级的 CORE 仍先于高优先级的 MOD")
    void phaseDominatesPriority() {
        // 这条最容易被写错：如果实现里把 priority 放在 phase 之前，
        // 一个 priority=-1000 的 MOD 转换器会抢在 CORE 之前跑 ——
        // 拿到尚未接线的 TickEngine，挂上去的任务永远不被调度。
        // 现象是「Mod 的 tick 处理器偶尔不跑」，且无任何报错。
        List<MiliTransformer> input = List.of(
                stub("mod-very-high-priority", TransformationPhase.MOD, -1000),
                stub("core-low-priority", TransformationPhase.CORE, 1000));

        assertEquals(List.of("core-low-priority", "mod-very-high-priority"),
                idsOf(TransformerOrdering.sorted(input)));
    }

    @Test
    @DisplayName("阶段与优先级都相同时按 id 字典序 —— 不依赖加载顺序")
    void idBreaksTiesDeterministically() {
        List<MiliTransformer> input = List.of(
                stub("zebra", TransformationPhase.MOD, 0),
                stub("alpha", TransformationPhase.MOD, 0),
                stub("monkey", TransformationPhase.MOD, 0));

        assertEquals(List.of("alpha", "monkey", "zebra"),
                idsOf(TransformerOrdering.sorted(input)));
    }

    @Test
    @DisplayName("输入顺序不影响输出顺序")
    void sortingIsInputOrderIndependent() {
        List<MiliTransformer> base = List.of(
                stub("m1", TransformationPhase.MOD, 0),
                stub("c1", TransformationPhase.CORE, 0),
                stub("e1", TransformationPhase.EARLY, 5),
                stub("m2", TransformationPhase.MOD, -1),
                stub("l1", TransformationPhase.LATE, 0));

        List<String> expected = idsOf(TransformerOrdering.sorted(base));

        // 各种乱序输入都必须得到同一个结果
        List<MiliTransformer> shuffled = new ArrayList<>(base);
        java.util.Collections.reverse(shuffled);
        assertEquals(expected, idsOf(TransformerOrdering.sorted(shuffled)),
                "排序结果必须与输入顺序无关");

        java.util.Collections.shuffle(shuffled,
                new java.util.Random(42));
        assertEquals(expected, idsOf(TransformerOrdering.sorted(shuffled)),
                "随机打乱后结果仍须一致");
    }

    @Test
    @DisplayName("sorted 不修改传入的列表")
    void sortedDoesNotMutateInput() {
        List<MiliTransformer> input = new ArrayList<>(List.of(
                stub("z", TransformationPhase.MOD, 0),
                stub("a", TransformationPhase.CORE, 0)));

        TransformerOrdering.sorted(input);

        assertEquals("z", input.get(0).id(),
                "排序应返回新列表 —— 调用方的列表不应被就地修改"
                        + "（它可能正在被注册表持有）");
    }

    @Test
    @DisplayName("重复 id 被检出 —— 重复会让冲突检测失效")
    void duplicateIdsAreDetected() {
        // id 是冲突检测与审计的主键。重复会让
        // 「谁修改了 MinecraftServer.tickServer」这个问题无法回答。
        List<MiliTransformer> input = List.of(
                stub("dup", TransformationPhase.MOD, 0),
                stub("dup", TransformationPhase.MOD, 0),
                stub("unique", TransformationPhase.MOD, 0));

        List<String> duplicates = TransformerOrdering.findDuplicateIds(input);

        assertEquals(1, duplicates.size(), "应恰好检出一个重复 id: " + duplicates);
        assertEquals("dup", duplicates.get(0));
    }

    @Test
    @DisplayName("explainOrder 输出可读的排序理由")
    void explainOrderIsReadable() {
        String explanation = TransformerOrdering.explainOrder(List.of(
                stub("b-core", TransformationPhase.CORE, 0),
                stub("a-mod", TransformationPhase.MOD, 10)));

        assertTrue(explanation.contains("b-core") && explanation.contains("a-mod"),
                "诊断输出应包含所有转换器 id");
        assertTrue(explanation.indexOf("b-core") < explanation.indexOf("a-mod"),
                "诊断输出应按实际执行顺序排列，便于排查「为什么我的 Mod 没先跑」");
    }
}