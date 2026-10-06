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
 *   <li>参数由两种成分按固定顺序构成：
 *     <ol>
 *       <li>至多一个 {@link org.loader.api.transform.callback.InjectionContext}
 *           （执行上下文，可选）；</li>
 *       <li>零个或多个<b>实参捕获</b>参数（仅 HEAD / RETURN / MODIFY_RETURN）：
 *           与目标方法的实参从第 0 位起<b>逐位严格相等</b>（描述符比较，
 *           不允许装箱、不允许子类），且必须构成前缀。</li>
 *     </ol></li>
 * </ul>
 *
 * <p>例如目标 {@code tickServer(BooleanSupplier)} 的合法回调形态：
 * {@code ()V}、{@code (ctx)}、{@code (BooleanSupplier)}、{@code (ctx, BooleanSupplier)}。
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
     * 声明本回调<b>可取消</b>目标方法的执行。
     *
     * <p>启用后回调可接收 {@link org.loader.api.transform.callback.InjectionContext}
     * 并调用 {@code ctx.cancel()}：
     * <ul>
     *   <li>{@link InjectionPoint#HEAD} —— 目标方法立即返回（非 void 方法
     *       返回类型默认值：{@code null} / {@code 0} / {@code false}）；</li>
     *   <li>{@link InjectionPoint#BEFORE_INVOKE} —— 被锚定的那次调用被跳过
     *       （返回值取默认值，后续 {@code AFTER_INVOKE} 仍会触发）。</li>
     * </ul>
     *
     * <p><b>仅这两个注入点支持</b>。字段类、MODIFY_*、RETURN、REDIRECT、
     * OVERWRITE 声明 {@code cancellable = true} 会在加载期报错 ——
     * 「取消一个已经发生的写操作」没有可定义的语义。
     *
     * <p>回调不调用 {@code cancel()} 时行为与普通注入完全一致
     * （开销为一次布尔读取与一次分支）。
     */
    boolean cancellable() default false;

    /**
     * {@link InjectionPoint#MODIFY_CONSTANT} 要匹配的常量值（字符串形式）。
     *
     * <p>按回调返回类型解析：
     * {@code int} → {@code Integer.parseInt}，{@code long} → {@code Long.parseLong}，
     * {@code float}/{@code double} → 对应 {@code parse*}，
     * {@code boolean} → {@code Boolean.parseBoolean}，
     * {@code String} → 原文。
     *
     * <p>解析失败或常量为空时加载期报错 —— 静默匹配零个常量
     * 就是又一次「加载成功但永不生效」。
     */
    String constant() default "";

    /**
     * {@link InjectionPoint#MODIFY_CONSTANT} 的参数序号，从 0 开始。
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