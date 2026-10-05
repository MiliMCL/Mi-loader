package org.loader.runtime.transform.conflict;

import org.loader.api.transform.InjectionPoint;
import org.loader.api.transform.TransformationConflictException;
import org.loader.api.transform.target.TargetInvocation;
import org.loader.api.transform.target.TargetMethod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冲突账本 —— 记录谁在什么位置做了什么，并检测互斥。
 *
 * <h2>为什么必须检测，而不是让后来者覆盖</h2>
 * 假设 Mod A 与 Mod B 都想重定向同一个调用。若平台默默让后者胜出，
 * 结果取决于 <b>Mod 加载顺序</b> —— 而加载顺序来自目录扫描。
 * 表现是「装了 B 之后 A 的功能坏了」，且：
 * <ul>
 *   <li>不可复现（顺序可能随文件系统变化）；</li>
 *   <li>无法二分定位（问题不在任何一方代码里）；</li>
 *   <li>平台日志里什么也没有。</li>
 * </ul>
 *
 * <p>这比直接抛异常糟糕得多：抛异常至少让用户在启动时看到明确的
 * 「ModA 与 ModB 冲突」。
 *
 * <h2>可组合 vs 互斥</h2>
 * <b>可组合</b>（不算冲突）：
 * <ul>
 *   <li>多个 {@link InjectionPoint#HEAD} —— 都执行，按顺序</li>
 *   <li>多个 {@link InjectionPoint#RETURN} —— 同上</li>
 *   <li>同一调用点上的 {@link InjectionPoint#BEFORE_INVOKE} 与
 *       {@link InjectionPoint#AFTER_INVOKE} —— 环绕语义正交</li>
 *   <li>{@link InjectionPoint#MODIFY_RETURN} 与调用点注入 —— 作用在不同指令上</li>
 * </ul>
 *
 * <b>互斥</b>（算冲突）：
 * <ul>
 *   <li>{@link InjectionPoint#OVERWRITE} 与<b>任何</b>其他声明</li>
 *   <li>同一调用点上两个 {@link InjectionPoint#REDIRECT}</li>
 *   <li>同一字段访问指令上两个 {@link InjectionPoint#REPLACE_FIELD_ACCESS}</li>
 *   <li>Mod 阶段覆盖 {@code CORE} 阶段已占用的位置</li>
 * </ul>
 *
 * <h2>为什么按「方法」索引，而不是按「位置」分桶</h2>
 * 早期实现把声明键拼成 {@code point|owner#name#desc#ordinal}，
 * 看似精确，实则让 {@link #mutuallyExclusive} 形同虚设：
 * <b>键里带了 point，不同类型的声明就落进不同的桶</b>，
 * {@code MODIFY_ARG} 与 {@code OVERWRITE} 永远不相遇，
 * 于是「OVERWRITE 会丢弃原始方法体」这条规则从未被执行过一次 ——
 * 而它恰恰是后果最严重的一条（其他 Mod 的注入静默消失，无任何日志）。
 *
 * <p>更深的问题是粒度不匹配：{@code OVERWRITE} 是<b>方法级</b>声明
 * （它丢弃整个方法体，与具体哪条指令无关），而 ordinal 是<b>指令级</b>的。
 * 任何以 ordinal 为中心的分桶方案都会让方法级声明漏检。
 *
 * <p>因此本实现改为：<b>按目标方法索引全部声明</b>，
 * 再由 {@link #mutuallyExclusive} 按语义判定两条声明是否真的互斥。
 * 判定逻辑集中在一处，不再依赖「键设计得对不对」这种脆弱前提。
 */
public final class ConflictLedger {

    /** 一条已登记的注入声明。 */
    public record Claim(
            String transformerId,
            String modId,
            TargetMethod target,
            InjectionPoint point,
            TargetInvocation invocation,
            org.loader.api.transform.TransformationPhase phase
    ) {
    }

    /**
     * 目标方法 → 该方法上已登记的全部互斥声明。
     *
     * <p>用 {@code LinkedHashMap} 而非 {@code HashMap}：冲突消息里
     * 「先登记者」的顺序需要与加载顺序一致，否则同一次冲突在不同
     * JVM 上会给出不同的措辞。
     */
    private final Map<String, List<Claim>> byMethod = new LinkedHashMap<>();

    /** 方法标识 —— owner + name + descriptor（内部名，斜杠分隔）。 */
    private static String methodKey(TargetMethod target) {
        return target.owner() + "#" + target.name() + target.descriptor();
    }

    /**
     * 声明所锚定的指令序号；返回 {@code null} 表示不锚定具体调用点。
     *
     * <p>注意 {@code TargetInvocation.at(...)} 允许 ordinal 为 0，
     * 因此这里必须用 {@code null} 而不是 0 作为「无调用点」的哨兵。
     */
    private static Integer ordinalOf(Claim claim) {
        return claim.invocation() == null ? null : claim.invocation().ordinal();
    }

    /**
     * 该注入点是否会与同方法上的其他声明争夺同一处字节码。
     *
     * <p><b>{@code false} 表示完全不入账本</b>（{@code HEAD} / {@code RETURN}）：
     * 它们没有互斥风险，登记下来只会让 {@link #allClaims()} 失真。
     * 早期 Javadoc 曾写「可组合的点仍记录以便审计」，与实现不符 ——
     * 那会让审计输出里混进大量无害条目，反而淹没真正的冲突。
     */
    private static boolean entersLedger(InjectionPoint point) {
        return point != InjectionPoint.HEAD && point != InjectionPoint.RETURN;
    }

    /**
     * 两条声明是否互斥。
     *
     * <p>判定顺序即严重程度顺序：先看方法级（OVERWRITE），
     * 再看指令级（同类改写撞在同一 ordinal 上）。
     */
    private static boolean mutuallyExclusive(Claim incoming, Claim existing) {
        // OVERWRITE 丢弃整个方法体 —— 与目标方法上的任何其他声明互斥，
        // 无论对方锚在哪条指令上。
        if (incoming.point() == InjectionPoint.OVERWRITE
                || existing.point() == InjectionPoint.OVERWRITE) {
            return true;
        }

        Integer a = ordinalOf(incoming);
        Integer b = ordinalOf(existing);
        // 只锚定到具体调用点的声明之间才谈得上「撞在同一条指令上」。
        if (a == null || b == null || !a.equals(b)) {
            return false;
        }

        // 环绕型（BEFORE/AFTER_INVOKE、字段前后访问）语义正交，可共存：
        // 一个在调用前跑、一个在调用后跑，谁也不破坏谁。
        if (isWrapper(incoming.point()) || isWrapper(existing.point())) {
            return false;
        }

        // 剩下的都是「改写这条指令本身」的类型：REDIRECT 换目标、
        // MODIFY_ARG 换实参、REPLACE_FIELD_ACCESS 换字段读写。
        // 同一指令上出现两个来源不同的改写，谁先谁后会决定最终语义 ——
        // 交给 Mod 作者显式协商，而不是靠加载顺序。
        return true;
    }

    /** 环绕型注入点 —— 只在目标指令前后附加代码，不改写它。 */
    private static boolean isWrapper(InjectionPoint point) {
        return switch (point) {
            case BEFORE_INVOKE, AFTER_INVOKE,
                 BEFORE_FIELD_ACCESS, AFTER_FIELD_ACCESS,
                 BEFORE_FIELD_SET -> true;
            case HEAD, RETURN,
                 REDIRECT, REPLACE_FIELD_ACCESS,
                 MODIFY_ARG, MODIFY_RETURN, OVERWRITE -> false;
        };
    }

    /**
     * 登记一条声明，并检测冲突。
     *
     * @throws TransformationConflictException 与已有声明互斥
     */
    public void claim(Claim claim) {
        if (!entersLedger(claim.point())) {
            return;
        }
        List<Claim> existing = byMethod.computeIfAbsent(
                methodKey(claim.target()), k -> new ArrayList<>());

        for (Claim other : existing) {
            TransformationConflictException conflict = detect(claim, other);
            if (conflict != null) {
                throw conflict;
            }
        }
        existing.add(claim);
    }

    /**
     * 判断两条声明是否冲突。
     *
     * @return 冲突时返回异常；否则返回 null
     */
    private TransformationConflictException detect(Claim incoming, Claim existing) {
        // 同一个转换器重复声明同一位置 —— 属于自身错误，同样报出。
        // 放在最前：即便它与自己的另一条声明恰好可组合，重复声明仍是 bug。
        if (incoming.transformerId().equals(existing.transformerId())) {
            return new TransformationConflictException(
                    incoming.target().ownerDotted(),
                    incoming.target().name(),
                    List.of(incoming.transformerId()),
                    "同一转换器重复声明该注入点 " + incoming.point());
        }

        // 目标阶段被更高优先阶段占用，且新声明来自低阶段
        if (existing.phase() != null && incoming.phase() != null
                && existing.phase().isProtected()
                && incoming.phase().weight() > existing.phase().weight()) {
            return new TransformationConflictException(
                    incoming.target().ownerDotted(),
                    incoming.target().name(),
                    List.of(existing.transformerId(), incoming.transformerId()),
                    "受保护的核心注入点（phase=" + existing.phase()
                            + "）不能被 phase=" + incoming.phase() + " 覆盖");
        }

        if (!mutuallyExclusive(incoming, existing)) {
            return null;
        }

        // OVERWRITE 单独措辞：它不是「撞车」，而是「单方面注销对方的声明」，
        // 用户需要知道的是「你装的那个 Mod 的注入没了」，而不只是「位置被占」。
        if (incoming.point() == InjectionPoint.OVERWRITE
                || existing.point() == InjectionPoint.OVERWRITE) {
            return new TransformationConflictException(
                    incoming.target().ownerDotted(),
                    incoming.target().name(),
                    List.of(existing.transformerId(), incoming.transformerId()),
                    "OVERWRITE 会丢弃原始方法体，" + describe(incoming, existing)
                            + " 的注入将不会生效");
        }

        return new TransformationConflictException(
                incoming.target().ownerDotted(),
                incoming.target().name(),
                List.of(existing.transformerId(), incoming.transformerId()),
                "同一调用点存在多个互斥的 " + incoming.point() + " 声明（ordinal="
                        + ordinalOf(incoming) + "）");
    }

    /** 生成「A 与 B」形式的措辞，指明究竟是哪一方会被作废。 */
    private static String describe(Claim incoming, Claim existing) {
        Claim overwrite = incoming.point() == InjectionPoint.OVERWRITE ? incoming : existing;
        Claim other = overwrite == incoming ? existing : incoming;
        return overwrite.transformerId() + "（OVERWRITE） 与 "
                + other.transformerId() + "（" + other.point() + "）";
    }

    /** 当前账本登记的全部声明（不含 HEAD / RETURN）。 */
    public List<Claim> allClaims() {
        List<Claim> all = new ArrayList<>();
        for (List<Claim> list : byMethod.values()) {
            all.addAll(list);
        }
        return all;
    }

    /**
     * 在自身状态的<b>副本</b>上预演一组声明，收集全部冲突但不抛出。
     *
     * <p>用途：Mod 安装阶段即可告知「你的转换与已装 Mod 冲突」，
     * 而不是等到游戏启动、类加载时才炸。
     *
     * <h3>为什么必须基于副本而非空白账本</h3>
     * 「独立」指的是<b>不影响自身状态</b>，而不是「从零开始」。
     * 若在空白账本上预演，就只能检出<b>同一批声明之间</b>的冲突，
     * 对「与已装 Mod 冲突」这个主要用途完全无效 ——
     * 而那恰恰是唯一值得在安装阶段报出的冲突。
     *
     * <p>本方法不修改自身状态，因此可重复调用且结果稳定。
     *
     * @param toCheck 待预演的声明
     * @return 冲突描述列表；无冲突时返回空列表
     */
    public List<String> dryRun(List<Claim> toCheck) {
        ConflictLedger probe = copy();
        List<String> problems = new ArrayList<>();
        for (Claim claim : toCheck) {
            try {
                probe.claim(claim);
            } catch (TransformationConflictException e) {
                problems.add(e.getMessage());
            }
        }
        return problems;
    }

    /**
     * 复制当前账本。
     *
     * <p>{@code Claim} 是不可变 record，可安全共享；
     * 只有外层容器需要新建 —— 否则预演会把结果写回真实账本。
     */
    private ConflictLedger copy() {
        ConflictLedger copy = new ConflictLedger();
        for (Map.Entry<String, List<Claim>> entry : byMethod.entrySet()) {
            copy.byMethod.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    /** 清空账本。 */
    public void clear() {
        byMethod.clear();
    }
}