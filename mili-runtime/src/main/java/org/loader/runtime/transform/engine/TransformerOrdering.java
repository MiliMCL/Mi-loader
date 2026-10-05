package org.loader.runtime.transform.engine;

import org.loader.api.transform.MiliTransformer;
import org.loader.api.transform.TransformationPhase;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 转换器排序 —— 决定执行次序。
 *
 * <h2>确定性是这个类的全部价值</h2>
 * 排序必须<b>完全确定</b>：同一组转换器，在任何机器、任何 JVM、
 * 任何类加载顺序下，都必须产出<b>逐字节相同</b>的字节码。
 *
 * <p>破坏确定性的典型做法都是"看起来无害"的：
 * <ul>
 *   <li>按 {@code HashMap} 迭代顺序 —— 取决于 {@code String.hashCode}
 *       与插入顺序，不同 JVM 可能不同；</li>
 *   <li>按对象 identity（{@code System.identityHashCode}）—— 每次运行都变；</li>
 *   <li>依赖注册时机（即 Mod 的发现顺序 = 目录扫描顺序）。</li>
 * </ul>
 *
 * <p>任何一种都会导致「装了 B 之后 A 的行为变了」这类 bug，
 * 且无法复现、无法二分定位。
 *
 * <h2>排序键（三级）</h2>
 * <ol>
 *   <li><b>阶段</b>（{@link TransformationPhase#weight()}）升序 ——
 *       强制性的安全边界，见该枚举说明</li>
 *   <li><b>优先级</b>（{@link MiliTransformer#priority()}）升序</li>
 *   <li><b>id 字典序</b>升序 —— 这是确定性的最终来源</li>
 * </ol>
 *
 * <p>三级之后<b>不可能再并列</b>：{@link MiliTransformer#id()} 在注册时
 * 已强制全局唯一（见 {@link TransformerRegistry#register}）。
 * 因此排序结果与注册顺序完全无关。
 */
public final class TransformerOrdering {

    private TransformerOrdering() {
    }

    /**
     * 确定性的全序比较器。
     *
     * <p>直接暴露为常量，便于在需要局部排序处复用同一套规则 ——
     * 排序规则只应有一份实现。
     */
    public static final Comparator<MiliTransformer> ORDER = TransformerOrdering::compare;

    /**
     * 比较两个转换器的执行次序。
     *
     * @return 负数表示 a 先执行
     */
    public static int compare(MiliTransformer a, MiliTransformer b) {
        int byPhase = Integer.compare(phaseWeight(a), phaseWeight(b));
        if (byPhase != 0) {
            return byPhase;
        }
        int byPriority = Integer.compare(priorityOf(a), priorityOf(b));
        if (byPriority != 0) {
            return byPriority;
        }
        // 确定性的最终来源。不加这一级，顺序就会退化为注册顺序。
        return idOf(a).compareTo(idOf(b));
    }

    /**
     * 就地排序并返回同一列表。
     *
     * @return 传入的同一实例（已排序）
     */
    public static List<MiliTransformer> sorted(List<MiliTransformer> transformers) {
        List<MiliTransformer> copy = new ArrayList<>(transformers);
        copy.sort(ORDER);
        return copy;
    }

    /**
     * 诊断：解释某个转换器排在某位置的原因。
     *
     * <p>调试顺序问题时，比打印排序结果更有用 —— 它直接说明
     * 「为什么 A 在 B 前面」。
     */
    public static String explainOrder(List<MiliTransformer> transformers) {
        StringBuilder sb = new StringBuilder();
        sb.append("Transformer order (").append(transformers.size()).append("):\n");
        List<MiliTransformer> sorted = sorted(transformers);
        for (int i = 0; i < sorted.size(); i++) {
            MiliTransformer t = sorted.get(i);
            sb.append(String.format("  %2d. %-32s phase=%-9s priority=%4d id=%s%n",
                    i + 1,
                    safeId(t),
                    safePhase(t),
                    priorityOf(t),
                    safeId(t)));
        }
        return sb.toString();
    }

    private static int phaseWeight(MiliTransformer t) {
        TransformationPhase p = safePhase(t);
        return p.weight();
    }

    private static TransformationPhase safePhase(MiliTransformer t) {
        TransformationPhase p = t.phase();
        return p != null ? p : TransformationPhase.MOD;
    }

    private static int priorityOf(MiliTransformer t) {
        return t.priority();
    }

    private static String idOf(MiliTransformer t) {
        return safeId(t);
    }

    private static String safeId(MiliTransformer t) {
        String id = t.id();
        return id != null ? id : "<no-id>";
    }

    /**
     * 校验一组转换器是否满足「id 唯一」这一确定性前提。
     *
     * <p>唯一性在 {@link TransformerRegistry#register} 已强制，
     * 但诊断与测试需要能独立复核。
     *
     * @return 重复的 id 列表；无重复时返回空列表
     */
    public static List<String> findDuplicateIds(List<MiliTransformer> transformers) {
        List<String> seen = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        for (MiliTransformer t : transformers) {
            String id = safeId(t);
            if (seen.contains(id)) {
                if (!duplicates.contains(id)) {
                    duplicates.add(id);
                }
            } else {
                seen.add(id);
            }
        }
        return duplicates;
    }
}