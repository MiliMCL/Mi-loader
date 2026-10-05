package org.loader.api.transform.annotation;

import org.loader.api.transform.InjectionPoint;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 在目标方法的指定位置注入一次回调。
 *
 * <pre>
 * public final class MyHooks {
 *     &#64;MiliInject(at = InjectionPoint.HEAD)
 *     public static void onTick(TickInjectionContext ctx) {
 *         // MinecraftServer.tickServer 入口
 *     }
 * }
 * </pre>
 *
 * <h2>方法签名约束</h2>
 * 回调方法必须满足：
 * <ul>
 *   <li><b>静态</b> —— 生成的是 {@code INVOKESTATIC}；</li>
 *   <li>{@code public} 或包可见；</li>
 *   <li>参数为 0 个，或恰好 1 个
 *       {@link org.loader.api.transform.callback.InjectionContext}。</li>
 * </ul>
 *
 * <p>签名不满足时在加载期报错 —— 字节码生成阶段的错误信息无法指向
 * 真正的原因，而这类错误在编译期或加载期就能发现。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MiliInject {

    /**
     * 注入位置。
     *
     * <p>与 {@link org.loader.api.transform.target.TargetInvocation}
     * 配合使用：{@link InjectionPoint#BEFORE_INVOKE} 与
     * {@link InjectionPoint#AFTER_INVOKE} 必须指定 {@link #target()}。
     */
    InjectionPoint at();

    /**
     * 被调用方法内部的目标调用坐标。
     *
     * <p>格式：{@code "owner#name(descriptor)"}，例如
     * {@code "net/minecraft/world/level/Level#getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"}。
     *
     * <p>仅在 {@link InjectionPoint#BEFORE_INVOKE} /
     * {@link InjectionPoint#AFTER_INVOKE} 下必填。
     */
    String target() default "";

    /**
     * 同一方法内多次相同调用时的序号，从 0 开始。
     *
     * <p>仅在 {@code BEFORE_INVOKE} / {@code AFTER_INVOKE} 下有意义。
     */
    int ordinal() default 0;

    /**
     * 字段目标坐标（{@code owner#name:descriptor}）。
     *
     * <p>仅在字段相关的 {@link InjectionPoint} 下必填。
     */
    String field() default "";

    /**
     * {@link InjectionPoint#MODIFY_ARG} 的参数序号，从 0 开始。
     */
    int argIndex() default -1;

    /**
     * 阶段内优先级，数值越小越先执行。
     *
     * <p>多个回调注入同一位置时按此排序，再相同则按方法名排序 ——
     * 保证确定性，绝不依赖加载顺序。
     */
    int priority() default 0;

    /**
     * 回调抛出异常时是否中断游戏。
     *
     * <p>默认 {@code false}：异常被捕获、审计、隔离，游戏继续。
     * 置为 {@code true} 会让异常逃逸进 Minecraft 调用栈 ——
     * 仅建议在调试时使用，因为它会以极难定位的方式打断主循环。
     */
    boolean propagateException() default false;
}