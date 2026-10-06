package org.loader.runtime.minecraft.transform;

import org.loader.api.transform.InjectionPoint;
import org.loader.api.transform.MiliTransformer;
import org.loader.api.transform.TransformationContext;
import org.loader.api.transform.TransformationPhase;
import org.loader.api.transform.TransformationResult;
import org.loader.api.transform.target.TargetMethod;
import org.loader.api.transform.symbol.MiliSymbol;
import org.loader.runtime.transform.asm.MiliClassTransformer;
import org.loader.runtime.transform.engine.PipelineTransformers;

import java.util.List;

/**
 * 主界面注入转换器 —— 给主菜单加上「Mods」按钮。
 *
 * <h2>它修的是什么问题</h2>
 * 平台没有任何途径让玩家看到「加载了哪些 mod」。本转换器在
 * {@code TitleScreen#init} 的方法头注入一次静态回调，
 * 由 {@code TitleScreenDispatch} 反射在主界面上添加 Mods 按钮
 * （点击后用原版 {@code AlertScreen} 展示已加载的 mod 列表）。
 *
 * <h2>注入方式</h2>
 * <pre>
 *   HEAD: INVOKESTATIC TitleScreenDispatch.onTitleScreenInit()V
 *         ... 原始方法体 ...
 * </pre>
 * {@code ()V} 回调不消费任何参数，插入点前后栈高度不变。
 *
 * <p><b>为什么选 HEAD 而不是 RETURN</b>：按钮位置由屏幕尺寸计算，
 * 不依赖 init 内部已创建的控件，HEAD 时机足够；且 HEAD 在任何
 * 原始逻辑之前执行，回调自身的异常（已全部吞掉）不会影响后续布局。
 *
 * <h2>回调为什么拿不到 this</h2>
 * 注入引擎的 HEAD 回调只支持无参 {@code ()V}。分发器改从
 * {@code Minecraft.getInstance()} 侧读取当前屏幕 —— init 由
 * {@code Gui.setScreen} 触发，此刻 {@code gui.screen()} 正是
 * 正在初始化的 TitleScreen。
 *
 * <h2>幂等性</h2>
 * 窗口 resize 会重跑 {@code init}。分发器用弱引用记录已注入的
 * 屏幕实例，同一实例不会重复添加按钮。
 */
public final class MiliTitleScreenTransformer implements MiliTransformer {

    /** 转换器 id —— 同时是排序与审计的主键，不可变更。 */
    public static final String ID = "mili-core-title-screen";

    /** 平台分发器 —— 生成字节码中唯一的外部引用目标。 */
    public static final String DISPATCH_OWNER =
            "org/loader/runtime/minecraft/client/TitleScreenDispatch";

    /** 回调方法名。 */
    public static final String CALLBACK = "onTitleScreenInit";

    /** 无参无返回。 */
    private static final String VOID_DESC = "()V";

    private final TargetMethod initMethod;

    public MiliTitleScreenTransformer() {
        this(MiliSymbol.TITLE_SCREEN_INIT);
    }

    /**
     * @param initMethod 主界面初始化方法坐标；由 CI 的符号校验任务保证与
     *                   实际 Minecraft 26.2 一致
     */
    public MiliTitleScreenTransformer(TargetMethod initMethod) {
        this.initMethod = initMethod != null ? initMethod : MiliSymbol.TITLE_SCREEN_INIT;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String minecraftVersion() {
        return MiliSymbol.MINECRAFT_VERSION;
    }

    /**
     * {@link TransformationPhase#CORE} —— 受保护阶段，仅平台可用。
     */
    @Override
    public TransformationPhase phase() {
        return TransformationPhase.CORE;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public boolean matches(String className) {
        return initMethod.owner().equals(className);
    }

    @Override
    public TransformationResult transform(TransformationContext context) {
        byte[] original = context.originalBytes();

        // 目标方法必须真实存在。找不到就抛，绝不返回 Skipped ——
        // 否则主界面按钮会静默消失，而用户看到的是「mod 列表没了」。
        if (!PipelineTransformers.containsMethod(original, initMethod.name(),
                initMethod.descriptor())) {
            throw new org.loader.api.transform.TransformationTargetNotFoundException(
                    context.className(), initMethod.name(), initMethod.descriptor(),
                    context.environment().minecraftVersion(), id());
        }

        List<MiliClassTransformer.MethodInjection> injections = List.of(
                new MiliClassTransformer.MethodInjection(
                        initMethod,      // target
                        null,            // field（非字段注入）
                        InjectionPoint.HEAD,
                        VOID_DESC,
                        null,            // invocation
                        -1,              // argIndex
                        DISPATCH_OWNER,
                        CALLBACK,
                        VOID_DESC,
                        true,            // replacementStatic（静态回调）
                        id(),
                        0));

        byte[] transformed =
                MiliClassTransformer.apply(original, context.className(), injections);

        return new TransformationResult.Transformed(transformed);
    }

    @Override
    public String toString() {
        return "MiliTitleScreenTransformer[" + ID + " → " + initMethod + "]";
    }
}
