package org.loader.api.transform.annotation;

import org.loader.api.transform.TransformationPhase;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个类包含 Mili 转换定义。
 *
 * <p>Mod <b>不实现</b> {@code MiliTransformer} 接口，而是用它标记的类 +
 * 类内的 {@link MiliInject} / {@link MiliRedirect} 等注解。平台在 Mod 加载期
 * 扫描这些注解，合成为内部的 {@code MiliTransformer} 实现。
 *
 * <pre>
 * &#64;MiliTransformer(target = "minecraft.server.tick")
 * public final class MyHooks {
 *     &#64;MiliInject(at = InjectionPoint.HEAD)
 *     public static void onTick(TickInjectionContext ctx) { ... }
 * }
 * </pre>
 *
 * <h2>为什么用注解而不是让 Mod 实现接口</h2>
 * 若 Mod 直接实现 {@code MiliTransformer}，它就必须操作字节码数组 ——
 * 而 ABI 无法为此保持零依赖（需要 byte[] 之外的 ASM 类型）。注解让
 * Mod 的编译依赖仍然只有 {@code mili-abi}，且 ASM 永不进入 Mod 的
 * classpath（ADR-0011 的硬约束）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MiliTransformer {

    /**
     * 逻辑目标名，对应 {@code MiliSymbol} 中的常量名。
     *
     * <p>例如 {@code "minecraft.server.tick"}。
     *
     * <p><b>这是字符串而非直接引用</b>：ABI 自身不能出现任何
     * Minecraft 坐标，否则 ABI 就与游戏版本绑定了。符号解析在
     * runtime 侧完成。
     *
     * <p>未识别的符号名在加载期报错，不静默忽略。
     */
    String target() default "";

    /**
     * 显式指定执行阶段。
     *
     * <p>默认 {@link TransformationPhase#MOD}，即 Mod 默认阶段。
     * Mod <b>不应</b>声明 {@link TransformationPhase#CORE} —— 该阶段
     * 由平台保留，且其目标受保护。
     */
    TransformationPhase phase() default TransformationPhase.MOD;

    /**
     * 阶段内优先级，数值越小越先执行。
     */
    int priority() default 0;

    /**
     * 显式声明 Minecraft 版本。
     *
     * <p>默认使用 {@link org.loader.api.VersionInfo#TARGET_MINECRAFT}。
     *
     * <p><b>只接受精确版本</b>：{@code "26.2"}。范围值
     * （{@code "26.x"}、{@code "latest"}）在加载期被拒绝。
     */
    String minecraftVersion() default "";
}