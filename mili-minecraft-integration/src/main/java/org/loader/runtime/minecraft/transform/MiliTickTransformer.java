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
 * Minecraft tick 注入转换器 —— 让 tick 链第一次真正闭合。
 *
 * <h2>它修的是什么问题</h2>
 * 审计发现：{@link org.loader.runtime.minecraft.TickBridge#beginTick()} 与
 * {@code endTick()} 在生产路径上<b>零调用者</b>。整条链是断的：
 * <pre>
 *   MinecraftServer.tickServer()   ← 真实 tick
 *        ↓ （缺失）
 *   TickBridge.beginTick()/endTick()
 *        ↓
 *   TickEngine → TickContract → Mod 的 TickHandler
 * </pre>
 * 表现是「Mod 的 tick 回调从不执行」，而平台日志里什么也没有 ——
 * 这正是本仓库反复记录的那类静默失效。
 *
 * <h2>注入方式</h2>
 * 在 {@code MinecraftServer#tickServer} 的<b>方法头</b>与<b>返回前</b>
 * 各插入一次对平台分发器的静态调用：
 * <pre>
 *   HEAD:   INVOKESTATIC TickCallbackDispatch.onTickBegin()V
 *            ... 原始方法体 ...
 *   RETURN: INVOKESTATIC TickCallbackDispatch.onTickEnd()V
 * </pre>
 *
 * <p><b>为什么用 {@code onMethodExit} 语义而非「最后一个 RETURN」</b>：
 * {@code tickServer} 内有多个返回路径（无玩家、超时、正常结束）。
 * 若只注入最后一个 RETURN，正常路径之外的返回会被漏掉 ——
 * 表现为「大部分时候 tick 正常，偶尔不执行」，
 * 而那取决于玩家何时离开服务器。
 *
 * <h2>为什么回调是平台分发器而不是 Mod</h2>
 * 生成的 Minecraft 字节码若直接引用 Mod 类，{@code MinecraftClassLoader}
 * 定义的类就会持有 Mod 类引用，导致 Mod 无法卸载（ClassLoader 泄漏）。
 * 本仓库已有 {@code ClassLoaderLeakTest} 守这条线。
 * 因此注入的字节码只引用 {@link TickCallbackDispatch}。
 */
public final class MiliTickTransformer implements MiliTransformer {

    /** 转换器 id —— 同时是排序与审计的主键，不可变更。 */
    public static final String ID = "mili-core-tick";

    /** 平台分发器 —— 生成字节码中唯一的外部引用目标。 */
    public static final String DISPATCH_OWNER =
            "org/loader/runtime/minecraft/transform/TickCallbackDispatch";

    /** 回调方法名。 */
    public static final String CALLBACK_HEAD = "onTickBegin";
    public static final String CALLBACK_TAIL = "onTickEnd";

    /** 无参无返回。 */
    private static final String VOID_DESC = "()V";

    private final TargetMethod tickMethod;

    public MiliTickTransformer() {
        this(MiliSymbol.SERVER_TICK);
    }

    /**
     * @param tickMethod 真实 tick 方法坐标；由 CI 的符号校验任务保证与
     *                   实际 Minecraft 26.2 一致
     */
    public MiliTickTransformer(TargetMethod tickMethod) {
        this.tickMethod = tickMethod != null ? tickMethod : MiliSymbol.SERVER_TICK;
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
     * {@link TransformationPhase#CORE} —— 受保护阶段。
     *
     * <p>tick 接线必须先于任何 Mod 的修改完成。若某个 Mod 的转换先跑，
     * 它可能拿到一个尚未接线的 TickEngine，并在其上挂载任务 ——
     * 那些任务永远不会被调度，且现象是「Mod 的 tick 处理器偶尔不跑」。
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
        return tickMethod.owner().equals(className);
    }

    @Override
    public TransformationResult transform(TransformationContext context) {
        byte[] original = context.originalBytes();

        // 目标方法必须真实存在。找不到就抛，绝不返回 Skipped ——
        // 否则 tick 链会静默断开，而用户看到的是「Mod 不工作」。
        if (!PipelineTransformers.containsMethod(original, tickMethod.name(),
                tickMethod.descriptor())) {
            throw new org.loader.api.transform.TransformationTargetNotFoundException(
                    context.className(), tickMethod.name(), tickMethod.descriptor(),
                    context.environment().minecraftVersion(), id());
        }

        List<MiliClassTransformer.MethodInjection> injections = List.of(
                new MiliClassTransformer.MethodInjection(
                        tickMethod,      // target
                        null,            // field（非字段注入）
                        InjectionPoint.HEAD,
                        VOID_DESC,
                        null,            // invocation
                        -1,              // argIndex
                        DISPATCH_OWNER,
                        CALLBACK_HEAD,
                        VOID_DESC,
                        false,           // replacementStatic
                        id(),
                        0),
                new MiliClassTransformer.MethodInjection(
                        tickMethod,
                        null,
                        InjectionPoint.RETURN,
                        VOID_DESC,
                        null,
                        -1,
                        DISPATCH_OWNER,
                        CALLBACK_TAIL,
                        VOID_DESC,
                        false,
                        id(),
                        0));

        byte[] transformed =
                MiliClassTransformer.apply(original, context.className(), injections);

        return new TransformationResult.Transformed(transformed);
    }

    @Override
    public String toString() {
        return "MiliTickTransformer[" + ID + " → " + tickMethod + "]";
    }
}