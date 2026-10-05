package org.loader.runtime.transform.engine;

import org.loader.api.transform.MiliTransformer;
import org.loader.api.transform.TransformationException;
import org.loader.api.transform.TransformationPhase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 转换器注册表 —— 登记、索引、排序。
 *
 * <h2>为什么需要「精确索引」这一层</h2>
 * Minecraft 26.2 约有 1 万个类。若每个类加载时都遍历全部转换器并
 * 询问 {@link MiliTransformer#matches(String)}，那是 1 万 × N 次字符串比较 ——
 * 而其中 9999 个类根本不与任何转换器有关。
 *
 * <p>{@link #preciseClassNames()} 提供一个<b>精确类名集合</b>：
 * 声明了具体类名的转换器会被登记进来，类加载时先做一次
 * {@code Set.contains(O(1))} 判断，不命中就直接走原始字节码，
 * <b>完全不进入 ASM</b>。
 *
 * <p>这是"绝大多数类零开销"这条性能目标的实现基础。
 *
 * <h2>注册期的强校验</h2>
 * 注册时（而非首次使用时）就必须暴露的问题：
 * <ul>
 *   <li><b>id 重复</b> —— 会破坏确定性排序与冲突检测，必须拒绝；</li>
 *   <li><b>Minecraft 版本不匹配</b> —— 平台是严格版本对齐的，
 *       模糊值（{@code 26.x} / {@code latest}）一律拒绝；</li>
 *   <li><b>Mod 声明 CORE 阶段</b> —— 该阶段由平台保留。</li>
 * </ul>
 *
 * <p>配置期失败远优于运行期静默。
 */
public final class TransformerRegistry {

    private final List<MiliTransformer> ordered =
            Collections.synchronizedList(new ArrayList<>());

    private final Map<String, MiliTransformer> byId = new ConcurrentHashMap<>();

    /**
     * 精确类名索引 —— 只包含声明了具体类名的转换器目标。
     *
     * <p>这是热路径上的唯一查询入口。
     */
    private final Set<String> preciseClassNames = ConcurrentHashMap.newKeySet();

    private final String expectedMinecraftVersion;
    private final AtomicBoolean sealed = new AtomicBoolean(false);

    public TransformerRegistry(String expectedMinecraftVersion) {
        this.expectedMinecraftVersion = expectedMinecraftVersion;
    }

    /**
     * 注册一个转换器。
     *
     * @throws TransformationException id 重复、版本不匹配、阶段非法或注册表已封闭
     */
    public void register(MiliTransformer transformer, String modId) {
        if (sealed.get()) {
            throw new TransformationException(
                    "注册表已封闭，不能再注册转换器: " + transformer.id(),
                    transformer.id(), null);
        }
        if (transformer == null) {
            throw new TransformationException("不能注册 null 转换器");
        }

        String id = transformer.id();
        validateId(id, transformer);
        validateVersion(transformer);
        validatePhase(transformer, modId);

        MiliTransformer existing = byId.putIfAbsent(id, transformer);
        if (existing != null) {
            throw new TransformationException(
                    "转换器 id 重复: '" + id + "'\n"
                            + "  已注册: " + existing.getClass().getName() + "\n"
                            + "  重复注册: " + transformer.getClass().getName() + "\n"
                            + "id 是冲突检测与审计的主键，不允许重复。\n"
                            + (modId != null ? "  Mod: " + modId : "  来源: 平台"),
                    id, null);
        }

        ordered.add(transformer);
    }

    private void validateId(String id, MiliTransformer t) {
        if (id == null || id.isBlank()) {
            throw new TransformationException(
                    "转换器必须提供非空 id（用于冲突检测与审计）: "
                            + t.getClass().getName(), null, null);
        }
    }

    /**
     * 严格版本校验。
     *
     * <p><b>本方法是「Mili 严格版本对齐」在转换层的落点。</b>
     * 模糊值会让「为什么 Mod 加载失败」变成无法回答的问题 ——
     * 因为无法判断转换器是否本该生效。
     */
    private void validateVersion(MiliTransformer t) {
        String declared = t.minecraftVersion();
        if (declared == null || declared.isBlank()) {
            throw new TransformationException(
                    "转换器 " + t.id() + " 必须声明 minecraftVersion"
                            + "（精确值，如 \"26.2\"）", t.id(), null);
        }
        if (!expectedMinecraftVersion.equals(declared)) {
            throw new TransformationException(
                    "转换器 " + t.id() + " 声明的 Minecraft 版本为 " + declared
                            + "，但平台目标是 " + expectedMinecraftVersion + "\n"
                            + "Mili 是严格版本对齐的平台："
                            + "转换目标在跨版本时极易失效。",
                    t.id(), null);
        }
        if (isRangeValue(declared)) {
            throw new TransformationException(
                    "转换器 " + t.id() + " 使用了模糊版本值 \"" + declared + "\"。"
                            + "必须精确到具体版本（如 \"26.2\"）。", t.id(), null);
        }
    }

    /** 识别 {@code 26.x} / {@code latest} / {@code *} 这类范围值。 */
    private boolean isRangeValue(String version) {
        String v = version.trim().toLowerCase();
        return v.contains("x") || v.contains("*") || v.contains("latest")
                || v.contains("+") || v.contains("-");
    }

    /**
     * 阶段合法性 —— {@code CORE} 由平台保留。
     *
     * <p>Mod 若能声明 CORE，就能占据受保护位置并让其他 Mod 的注入
     * 静默失效。这必须在注册期就拦住。
     */
    private void validatePhase(MiliTransformer t, String modId) {
        TransformationPhase phase = t.phase();
        if (phase == null) {
            return; // 接口给了默认值，实际不会为 null
        }
        if (modId != null && phase.isProtected()) {
            throw new TransformationException(
                    "Mod " + modId + " 的转换器 " + t.id()
                            + " 不能声明 " + phase + " 阶段 —— 该阶段由平台保留。\n"
                            + "原因：受保护阶段承载 tick 正确性，"
                            + "若可被 Mod 占据，其他 Mod 的注入会静默失效。",
                    t.id(), null);
        }
    }

    /**
     * 封闭注册表 —— 转换开始前调用。
     *
     * <p>封闭后不能再注册，保证一次类加载过程中看到的转换器集合是稳定的。
     * 若运行中允许注册，同一个类在两次加载时可能得到不同结果 ——
     * 那会让 {@code TransformationCache} 变成正确性隐患。
     */
    public void seal() {
        if (sealed.compareAndSet(false, true)) {
            ordered.sort(TransformerOrdering.ORDER);
        }
    }

    /** 是否已封闭。 */
    public boolean isSealed() {
        return sealed.get();
    }

    /**
     * 查询某个类名<b>可能</b>需要的转换器。
     *
     * <p><b>返回空列表即表示该类完全不需要进入 ASM</b> —— 这是热路径。
     *
     * @param className 类内部名
     * @return 匹配的转换器；按执行顺序排列
     */
    public List<MiliTransformer> matching(String className) {
        if (ordered.isEmpty() || className == null) {
            return List.of();
        }

        // 第一步：精确索引。绝大多数类在这里就被排除。
        if (!preciseClassNames.isEmpty() && !preciseClassNames.contains(className)) {
            // 该类不在精确集合内。只有声明了通配规则的转换器才可能关心它。
            List<MiliTransformer> wildcard = matchingByWildcard(className);
            return wildcard.isEmpty() ? List.of() : wildcard;
        }

        // 第二步：交给各转换器自行判断（前置过滤）。
        List<MiliTransformer> result = new ArrayList<>(Math.min(4, ordered.size()));
        synchronized (ordered) {
            for (MiliTransformer t : ordered) {
                if (t.matches(className)) {
                    result.add(t);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 通配匹配 —— 仅处理含 {@code *} 的声明。
     *
     * <p>绝大多数转换器不需要通配，因此该列表通常为空。
     */
    private List<MiliTransformer> matchingByWildcard(String className) {
        List<MiliTransformer> result = null;
        synchronized (ordered) {
            for (MiliTransformer t : ordered) {
                String id = t.id();
                if (id == null) {
                    continue;
                }
                // 转换器可用 "pkg.*" 形式声明关注一个包
                int star = id.indexOf('*');
                if (star > 0) {
                    String prefix = id.substring(0, star);
                    if (className.startsWith(prefix)) {
                        if (result == null) {
                            result = new ArrayList<>(2);
                        }
                        if (t.matches(className)) {
                            result.add(t);
                        }
                    }
                }
            }
        }
        return result == null ? List.of() : Collections.unmodifiableList(result);
    }

    /**
     * 登记一个精确类名 —— 由具体转换器实现调用以建立索引。
     *
     * <p>这是 {@link MiliTransformer} 的可选伴生接口。转换器实现它后，
     * 平台能建立精确索引，从而让无关类的加载开销接近零。
     */
    public void indexClass(String internalClassName) {
        if (internalClassName != null && !internalClassName.isBlank()) {
            preciseClassNames.add(internalClassName);
        }
    }

    /** 是否存在精确索引（用于诊断热路径是否已优化）。 */
    public boolean hasPreciseIndex() {
        return !preciseClassNames.isEmpty();
    }

    /** 已索引的类名数量。 */
    public int indexedClassCount() {
        return preciseClassNames.size();
    }

    /** 全部转换器（已排序；若未封闭则按注册顺序）。 */
    public List<MiliTransformer> all() {
        synchronized (ordered) {
            return List.copyOf(ordered);
        }
    }

    public int size() {
        return ordered.size();
    }

    public boolean isEmpty() {
        return ordered.isEmpty();
    }

    /** 诊断：注册表内容与执行顺序。 */
    public String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("TransformerRegistry[count=").append(size())
                .append(", sealed=").append(sealed.get())
                .append(", preciseIndex=").append(indexedClassCount())
                .append(", expectedMC=").append(expectedMinecraftVersion)
                .append("]\n");
        sb.append(TransformerOrdering.explainOrder(all()));
        return sb.toString();
    }

    /** 清空（测试用）。 */
    public void clear() {
        ordered.clear();
        byId.clear();
        preciseClassNames.clear();
        sealed.set(false);
    }
}