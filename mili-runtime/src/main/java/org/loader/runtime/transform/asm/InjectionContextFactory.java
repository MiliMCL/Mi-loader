package org.loader.runtime.transform.asm;

import org.loader.api.transform.callback.InjectionContext;
import org.loader.api.transform.callback.ExecutionContext;

/**
 * 注入上下文工厂 —— <b>生成到Minecraft 字节码里的唯一上下文来源</b>。
 *
 * <h2>为什么需要它</h2>
 * 声明式回调允许接收一个 {@link InjectionContext}：
 * <pre>
 *   &#64;MiliInject(at = InjectionPoint.HEAD)
 *   public static void onTick(InjectionContext ctx) { ... }
 * </pre>
 * 注入生成的是 {@code INVOKESTATIC callback(ctx)}。而 {@code ctx}
 * 在编译期并不存在 —— 它必须在<b>运行期</b>、在目标方法被调用的那一刻
 * 才被构造出来。字节码里无法凭空造出一个对象，只能调用某个工厂方法。
 *
 * <p>因此每次注入 {@code InjectionContext} 参数时，生成的指令序列是：
 * <pre>
 *   LDC  owner        // 目标类内部名
 *   LDC  methodName   // 目标方法名
 *   ALOAD 0           // 宿主实例（静态方法 / 构造器为 ACONST_NULL）
 *   INVOKESTATIC InjectionContextFactory.forMethodWithTarget
 *       (Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)
 *       LInjectionContext;
 *   INVOKESTATIC ModCallbacks.onTick(LInjectionContext;)V
 * </pre>
 *
 * <h2>为什么不能由字节码直接 newInjectionContext</h2>
 * {@link InjectionContext} 有 8 个构造参数，其中 {@code tickContract}、
 * {@code runtime} 的真实类型在 ABI 里是 {@code Object}
 * （ABI 不能反向依赖 runtime）。若让生成的字节码自己构造，
 * 就得把平台运行期才能知道的信息硬编码进常量池 ——
 * 而这些信息恰恰是每 tick 都变的。
 *
 * <p>把它收进一个static 方法后：<b>字节码只携带两个字符串常量</b>，
 * 其余全部在平台侧实时填充。这样 Mod 拿到的上下文永远是当下的真实状态，
 * 而不是转换那一刻的快照。
 *
 * <h2>为什么在 runtime 而不在 abi</h2>
 * {@link InjectionContext} 的字段类型刻意是 {@code Object}，
 * 就是为了让 ABI 零依赖 runtime。填充这些字段需要 runtime 的知识
 * （当前 tick 序号、执行单元、Runtime 句柄），
 * 因此实现必须在 runtime 侧 —— ABI 只声明形状。
 *
 * @see InjectionContext
 */
public final class InjectionContextFactory {

    /**
     * 上下文提供器 —— 由平台在启动时装配。
     *
     * <p>设计成接口而非直接读TickEngine：生成字节码发生在
     * <b>类加载期</b>，而tick 信息的来源在运行期才确定；
     * 用接口把两者解耦，也便于测试注入固定值。
     *
     * <p>默认实现返回「无tick 上下文」——
     * 平台尚未装配时注入仍必须能工作，否则游戏启动早期就崩。
     */
    public interface Provider {
        /**
         * 产出一个携带实例与取消能力的上下文 —— <b>完整形态</b>。
         *
         * <h2>为什么抽象方法是四参而非二参</h2>
         * 取消能力由生成字节码按注入点合法性选择工厂
         * （{@link #forCancellableMethod}）：若允许实现者只提供
         * 二参形态（丢失 target 与取消能力），漏装的完整能力会让
         * cancellable 注入在运行期抛 {@code IllegalStateException} ——
         * 且只在玩家真的触发取消那一刻才暴露。四参抽象把
         * 「装配者必须提供完整形态」变成<b>编译期强制</b>。
         *
         * @param target      宿主实例（静态方法 / 构造器为 null）
         * @param cancellable 是否允许 {@code cancel()}
         */
        InjectionContext create(String className, String methodName,
                                Object target, boolean cancellable);

        /**
         * 产出一个无实例、不可取消的上下文。
         *
         * <p>便捷形式：委托给完整形态（{@code target = null}、
         * {@code cancellable = false}）—— 与完整形态同源，
         * 不存在第二套语义。
         */
        default InjectionContext create(String className, String methodName) {
            return create(className, methodName, null, false);
        }
    }

    /**
     * 脱离 tick 的默认提供器 —— 完整形态。
     *
     * <p>{@code tickId = -1} 而非 0：{@link InjectionContext#inTick()}
     * 以 {@code tickId >= 0} 判定，-1 表示「不在 tick 内」。
     * 用 0 会让 Mod 在游戏启动前就误以为自己处于第 0 个 tick。
     *
     * <p>实例上下文与取消能力在平台未装配时同样可用：
     * {@code target()} 如实返回宿主实例，{@code cancel()} 生效。
     * 这保证「游戏启动早期、平台 provider 尚未就绪」的阶段里
     * 已加载的注入行为与之后一致，而不是同一注入在两个阶段
     * 表现不同 —— 那种不一致比崩溃更难查。
     */
    private static final Provider DETACHED_FULL = (className, methodName, target, cancellable) ->
            new InjectionContext(
                    className,
                    methodName,
                    null,
                    -1L,
                    null,
                    ExecutionContext.single(),
                    null,
                    Thread.currentThread(),
                    target,
                    cancellable);

    private static volatile Provider provider = DETACHED_FULL;

    private InjectionContextFactory() {
    }

    /**
     * 装配上下文提供器 —— 由平台在 Runtime 就绪后调用。
     *
     * <p>传 null 恢复默认（脱离 tick）。游戏退出时应当调用它卸载，
     * 与 {@code TickCallbackDispatch.install(null)} 同一时机：
     * 否则静态字段会让整条对象图在退出后仍可达。
     */
    public static void install(Provider newProvider) {
        provider = newProvider != null ? newProvider : DETACHED_FULL;
    }

    /** 当前提供器 —— 供诊断输出。 */
    public static Provider provider() {
        return provider;
    }

    /**
     * 由生成字节码调用 —— 为指定方法构造上下文。
     *
     * <p><b>描述符必须与本方法完全一致</b>：{@code (Ljava/lang/String;Ljava/lang/String;)L}
     * {@code org/loader/api/transform/callback/InjectionContext;}。
     * 改签名会让所有已转换的类在 {@code NoSuchMethodError} 中失效，
     * 而症状出现在游戏运行期、堆栈指向 Minecraft。
     */
    public static InjectionContext forMethod(String className, String methodName) {
        return provider.create(className, methodName);
    }

    /**
     * 由生成字节码调用 —— 携带宿主实例构造上下文。
     *
     * <p><b>描述符（含 {@code Ljava/lang/Object;}）必须与本方法完全一致</b>，
     * 引擎侧以常量引用它（见 {@code InjectionMethodVisitor}）。
     * 静态方法 / 构造器上 {@code target} 为 {@code null}。
     */
    public static InjectionContext forMethodWithTarget(
            String className, String methodName, Object target) {
        return provider.create(className, methodName, target, false);
    }

    /**
     * 由生成字节码调用 —— 构造<b>可取消</b>的上下文。
     *
     * <p>与 {@link #forMethodWithTarget} 描述符相同、名字不同：
     * 取消能力由引擎按注入点合法性选择工厂，而不是让回调
     * 在运行期探测 —— 探测式的取消会让「平台漏装 provider」
     * 静默降级为「取消无效」。
     */
    public static InjectionContext forCancellableMethod(
            String className, String methodName, Object target) {
        return provider.create(className, methodName, target, true);
    }
}