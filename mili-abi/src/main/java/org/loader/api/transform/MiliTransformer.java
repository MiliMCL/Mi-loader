package org.loader.api.transform;

/**
 * Mili 原生转换器 —— Mod 与平台扩展字节码转换能力的唯一入口。
 *
 * <p><b>Mod 永远不会看到 ASM。</b>{@code ClassVisitor}、
 * {@code MethodVisitor}、{@code InsnList}、{@code AbstractInsnNode}
 * 都不在本接口的签名里，引擎内部如何实现是平台细节（见 ADR-0011）。
 *
 * <h2>实现形态</h2>
 * Mod 通常<b>不</b>实现本接口，而是写若干被注解标记的回调方法，
 * 由平台在类加载时扫描并合成为 {@code MiliTransformer}：
 *
 * <pre>
 * &#64;MiliTransformer(target = "minecraft.server.tick")
 * public final class MyHooks {
 *     &#64;MiliInject(at = InjectionPoint.HEAD)
 *     public static void onTick(TickInjectionContext ctx) {
 *         // ...
 *     }
 * }
 * </pre>
 *
 * 直接实现本接口则用于平台内部的复杂转换（如需要完整控制指令流时）。
 *
 * @see org.loader.api.transform.annotation.MiliTransformer 声明式入口
 */
public interface MiliTransformer {

    /**
     * 唯一标识 —— 用于冲突检测、审计与日志。
     *
     * <p><b>必须全局唯一。</b>两个转换器返回相同 id 会在注册时被拒绝：
     * 冲突检测与审计都依赖它作为主键，重复会让「谁修改了 MinecraftServer.tickServer」
     * 这类问题无法回答。
     */
    String id();

    /**
     * 本转换器针对的 Minecraft 版本，<b>必须精确匹配</b>。
     *
     * <p>禁止返回 {@code "1.21.x"}、{@code "latest"}、{@code "26.x"}
     * 之类的模糊值。Mili 是严格版本对齐的平台：同一个转换器在不同
     * Minecraft 版本上的目标方法很可能已经改名或改描述符，用范围匹配
     * 会让「为什么 Mod 加载失败」变成一个无法回答的问题。
     *
     * <p>平台在<b>注册时</b>校验此值与实际加载的版本是否一致，
     * 不一致直接拒绝注册 —— 配置期失败优于运行期静默。
     *
     * <p>唯一常量应取自 {@link org.loader.api.VersionInfo#TARGET_MINECRAFT}。
     */
    String minecraftVersion();

    /**
     * 执行阶段。决定本转换器与其他转换器的先后次序。
     *
     * @see TransformationPhase
     */
    default TransformationPhase phase() {
        return TransformationPhase.MOD;
    }

    /**
     * 阶段内优先级，数值越小越先执行。
     *
     * <p>默认 0。相同阶段与相同优先级的转换器按 {@link #id()} 字典序执行
     * —— <b>必须是确定性的</b>，绝不能让顺序依赖哈希或类加载时机，
     * 否则同一组 Mod 在不同机器上会产出不同的字节码。
     */
    default int priority() {
        return 0;
    }

    /**
     * 声明本转换器关注哪些类。
     *
     * <p><b>在读取任何字节码之前调用。</b>返回 false 时该类完全跳过本转换器，
     * 不产生任何字节码读取或 ASM 开销。这条短路是平台启动性能的关键：
     * Minecraft 有约 1 万个类，绝大多数与任何转换器都无关。
     *
     * <p>实现必须快速且无副作用（不做类加载、不反射）。
     *
     * <p>默认返回 false —— 宁可漏匹配后走 {@link TransformationResult#Skipped}，
     * 也不要让所有类都进入完整 ASM 分析。
     */
    default boolean matches(String className) {
        return false;
    }

    /**
     * 执行转换。
     *
     * <h2>返回值的语义选择（本 API 最重要的判断）</h2>
     * <ul>
     *   <li>目标类存在且已修改 → {@link TransformationResult.Transformed}</li>
     *   <li>目标类存在但本转换器无需改动 → {@link TransformationResult.Unchanged}</li>
     *   <li>本转换器本就不适用于这个类 → {@link TransformationResult.Skipped}</li>
     *   <li>声明了目标但找不到 → <b>抛</b>
     *       {@link TransformationTargetNotFoundException}，
     *       <b>不要</b>返回 {@code Skipped}</li>
     * </ul>
     *
     * @param context 转换上下文。{@code context.modScope()} 可用于权限自检，
     *                但引擎在调用前已完成能力校验，实现不必重复检查。
     * @return 转换结果
     * @throws TransformationTargetNotFoundException 声明的目标不存在
     * @throws TransformationVerificationException      产出非法字节码
     * @throws TransformationConflictException         与其他转换器冲突
     */
    TransformationResult transform(TransformationContext context);
}