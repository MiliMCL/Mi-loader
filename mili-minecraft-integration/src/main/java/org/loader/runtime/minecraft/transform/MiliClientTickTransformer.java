package org.loader.runtime.minecraft.transform;

import org.loader.api.transform.InjectionPoint;
import org.loader.api.transform.MiliTransformer;
import org.loader.api.transform.TransformationContext;
import org.loader.api.transform.TransformationPhase;
import org.loader.api.transform.TransformationResult;
import org.loader.api.transform.symbol.MiliSymbol;
import org.loader.runtime.transform.asm.MiliClassTransformer;

import java.util.List;

/**
 * 客户端主循环注入转换器 —— 按键轮询的挂载点。
 *
 * <h2>为什么是 {@code Minecraft#tick} 而不是 {@code ClientLevel#tick}</h2>
 * {@code ClientLevel#tick} 只在世界加载后执行 —— 若挂在它上面，
 * 主菜单里的 mod 按键（如「打开配置界面」）永远不会触发。
 * {@code Minecraft#tick} 与世界无关，每个客户端 tick 一次。
 *
 * <h2>注入方式</h2>
 * HEAD 注入 {@code INVOKESTATIC KeyBindingDispatch.tick()V}：
 * <pre>
 *   tick() { KeyBindingDispatch.tick(); ... 原始方法体 ... }
 * </pre>
 * 分发器内部吞掉一切异常 —— 轮询失败绝不中断游戏主循环。
 */
public final class MiliClientTickTransformer implements MiliTransformer {

    /** 转换器 id —— 同时是排序与审计的主键，不可变更。 */
    public static final String ID = "mili-core-client-tick";

    /** 平台分发器 —— 生成字节码中唯一的外部引用目标。 */
    public static final String DISPATCH_OWNER =
            "org/loader/runtime/minecraft/client/input/KeyBindingDispatch";
    public static final String CALLBACK = "tick";

    private static final String VOID_DESC = "()V";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String minecraftVersion() {
        return MiliSymbol.MINECRAFT_VERSION;
    }

    /** CORE 阶段 —— 与服务端 tick 接线同级，先于一切 Mod 转换。 */
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
        return MiliSymbol.CLIENT_TICK.owner().equals(className);
    }

    @Override
    public TransformationResult transform(TransformationContext context) {
        byte[] original = context.originalBytes();
        var target = MiliSymbol.CLIENT_TICK;

        if (!org.loader.runtime.transform.engine.PipelineTransformers
                .containsMethod(original, target.name(), target.descriptor())) {
            throw new org.loader.api.transform.TransformationTargetNotFoundException(
                    context.className(), target.name(), target.descriptor(),
                    context.environment().minecraftVersion(), id());
        }

        byte[] transformed = MiliClassTransformer.apply(
                original, context.className(),
                List.of(new MiliClassTransformer.MethodInjection(
                        target,
                        null,
                        InjectionPoint.HEAD,
                        VOID_DESC,
                        null,
                        -1,
                        DISPATCH_OWNER,
                        CALLBACK,
                        VOID_DESC,
                        false,
                        id(),
                        0)));

        return new TransformationResult.Transformed(transformed);
    }

    @Override
    public String toString() {
        return "MiliClientTickTransformer[" + ID + " → " + MiliSymbol.CLIENT_TICK + "]";
    }
}
