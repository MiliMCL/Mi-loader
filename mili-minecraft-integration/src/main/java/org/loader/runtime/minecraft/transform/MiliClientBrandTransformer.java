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
 * 客户端品牌转换器 —— 让 F3 与服务器握手认出 Mili。
 *
 * <h2>它修的是什么问题</h2>
 * 原版 {@code ClientBrandRetriever#getClientModName()} 恒返回
 * {@code "vanilla"}，玩家按 F3 看到的是「原版客户端」，
 * 完全无法分辨当前运行的是 Mili 还是纯净原版。
 *
 * <h2>注入方式</h2>
 * 在 {@code getClientModName} 上使用 {@code MODIFY_RETURN}：
 * <pre>
 *   原方法: getClientModName() { return "vanilla"; }
 *   转换后: getClientModName() { return "vanilla"; ClientBrandDispatch.getClientModName(); }
 * </pre>
 * 栈上原值位于回调返回值之下，随 ARETURN 一并丢弃 ——
 * 最终返回 {@code "Mili-loader"}。
 *
 * <p>这一替换同时修正了 {@code ModCheck.identify(...)} 的 modded 判定
 * 与客户端→服务器握手时上报的品牌。
 *
 * <h2>为什么回调是平台分发器</h2>
 * 与 {@link MiliTickTransformer} 同一约束：生成的 Minecraft 字节码
 * 只引用平台类（{@code ClientBrandDispatch}，由 AppClassLoader 定义），
 * 对 Mod 类零引用，避免 ClassLoader 泄漏。
 */
public final class MiliClientBrandTransformer implements MiliTransformer {

    /** 转换器 id —— 同时是排序与审计的主键，不可变更。 */
    public static final String ID = "mili-core-client-brand";

    /** 平台分发器 —— 生成字节码中唯一的外部引用目标。 */
    public static final String DISPATCH_OWNER =
            "org/loader/runtime/minecraft/client/ClientBrandDispatch";

    /** 回调方法名。 */
    public static final String CALLBACK = "getClientModName";

    /** 目标方法返回 String，回调也必须返回 String。 */
    public static final String CALLBACK_DESCRIPTOR = "()Ljava/lang/String;";

    private final TargetMethod brandMethod;

    public MiliClientBrandTransformer() {
        this(MiliSymbol.CLIENT_BRAND);
    }

    /**
     * @param brandMethod 品牌查询方法坐标；由 CI 的符号校验任务保证与
     *                    实际 Minecraft 26.2 一致
     */
    public MiliClientBrandTransformer(TargetMethod brandMethod) {
        this.brandMethod = brandMethod != null ? brandMethod : MiliSymbol.CLIENT_BRAND;
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
        return brandMethod.owner().equals(className);
    }

    @Override
    public TransformationResult transform(TransformationContext context) {
        byte[] original = context.originalBytes();

        // 目标方法必须真实存在。找不到就抛，绝不返回 Skipped ——
        // 否则品牌替换会静默失效，用户看到的仍是「原版客户端」。
        if (!PipelineTransformers.containsMethod(original, brandMethod.name(),
                brandMethod.descriptor())) {
            throw new org.loader.api.transform.TransformationTargetNotFoundException(
                    context.className(), brandMethod.name(), brandMethod.descriptor(),
                    context.environment().minecraftVersion(), id());
        }

        List<MiliClassTransformer.MethodInjection> injections = List.of(
                new MiliClassTransformer.MethodInjection(
                        brandMethod,           // target
                        null,                  // field（非字段注入）
                        InjectionPoint.MODIFY_RETURN,
                        CALLBACK_DESCRIPTOR,   // 回调返回 String
                        null,                  // invocation
                        -1,                    // argIndex
                        DISPATCH_OWNER,
                        CALLBACK,
                        null,                  // replacementDescriptor（仅 REDIRECT 使用）
                        true,                  // replacementStatic（静态回调）
                        id(),
                        0));

        byte[] transformed =
                MiliClassTransformer.apply(original, context.className(), injections);

        return new TransformationResult.Transformed(transformed);
    }

    @Override
    public String toString() {
        return "MiliClientBrandTransformer[" + ID + " → " + brandMethod + "]";
    }
}
