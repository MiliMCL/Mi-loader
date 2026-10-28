package org.loader.runtime.minecraft.transform;

import org.loader.api.transform.InjectionPoint;
import org.loader.api.transform.MiliTransformer;
import org.loader.api.transform.TransformationContext;
import org.loader.api.transform.TransformationPhase;
import org.loader.api.transform.TransformationResult;
import org.loader.api.transform.target.TargetMethod;
import org.loader.api.transform.symbol.MiliSymbol;
import org.loader.runtime.minecraft.client.screen.MenuTransitionDispatch;
import org.loader.runtime.transform.asm.MiliClassTransformer;
import org.loader.runtime.transform.engine.PipelineTransformers;

import java.util.List;

/**
 * 主菜单液态玻璃转场转换器 —— 双 RETURN 注入锚定一条转场链路。
 *
 * <h2>它修的是什么问题</h2>
 * 原版主菜单点击「单人游戏 / 多人游戏」是瞬时换屏。本转换器打两个锚点，
 * 由 {@code MenuTransitionDispatch} 完成全部行为（按钮接管、动画状态、
 * 玻璃绘制），规格见 {@code docs/design/LIQUID_GLASS_MENU_TRANSITION.md}：
 * <ol>
 *   <li>{@code Screen#init(II)} RETURN —— {@code public final}、由
 *       setScreen/resize 路径调用，方法体先跑子类 {@code init()}（按钮
 *       创建）再返回。回调触发时原版按钮已存在，是「接管按钮」的时机；
 *       回调内部按 TitleScreen 实例过滤，其余屏幕空转。</li>
 *   <li>{@code Screen#extractRenderStateWithTooltipAndSubtitles} RETURN ——
 *       每帧整屏（含 tooltip）画完之后恰好一次，转场激活时把玻璃画在最上层。
 *       回调是生成的桥接类 {@code MiliGlassBridge}（其描述符必须携带
 *       {@code GuiGraphicsExtractor} 类型，平台源码写不出 —— 见
 *       {@code GlassBridgeGenerator} 与
 *       {@code MinecraftClassLoader#definePlatformBridge}）。</li>
 * </ol>
 * 两个锚点同属 Screen：一次 {@code transform()} 同时注入，原子生效。
 *
 * <h2>为什么 init 锚点在 Screen 基类而不是 TitleScreen（2026-10-24 热修）</h2>
 * 原方案把 init 钩子打在 {@code TitleScreen#init()V} —— 与 Mods 按钮转换器
 * （{@code mili-core-title-screen}）命中同一方法。而转换管线对同一类的
 * 多个转换器是<b>各自基于原始字节独立转换、最后者覆盖</b>
 * （{@code TransformerPipeline} 逐个用 {@code originalBytes} 重建上下文，
 * {@code current} 只被最后一个 Transformed 结果赋值）：注册序更靠后的
 * title-screen 转换器的输出<b>静默丢弃</b>了本转换器的 init 注入 ——
 * 桥接类失去「init 时定义」的时机，而 26.2 的 {@code LoadingOverlay}
 * 在淡入/淡出阶段会直接调用当前屏幕的提取方法，渲染钩子在遮罩阶段
 * 首次解析 {@code MiliGlassBridge} 失败 → 真机 NoClassDefFoundError 崩溃。
 * 挪到 Screen 基类后，本转换器是 Screen 的<b>唯一</b>转换器，冲突在
 * 类别上消失；副作用是回调会在每个屏幕打开/resize 时触发，
 * 由 {@code TitleScreen} 实例过滤兜底（一次 isInstance，可忽略）。
 *
 * <h2>桥接类为什么在转换期定义（而不是 init 回调里）</h2>
 * 注入的 INVOKESTATIC 在<b>首次执行</b>时解析目标类。加载遮罩阶段
 * {@code LoadingOverlay} 就会执行渲染钩子，早于（或独立于）任何
 * 主菜单初始化 —— 桥接类必须赶在那之前定义进游戏 ClassLoader。
 * 转换期是唯一保证「定义先于执行」的时机：转换发生在
 * {@code MinecraftClassLoader.loadClass} 内部，此刻定义的类必然早于
 * 该类任何字节码的执行。定义失败时<b>两个锚点都不注入</b>
 * （返回 Skipped）—— 绝不埋一颗解析必炸的 INVOKESTATIC；
 * 游戏保持原版行为，转场停用。
 *
 * <h2>注入方式</h2>
 * <pre>
 *   Screen#init(II):
 *     ... 原始方法体（含子类 init() 的按钮创建）...
 *     RETURN(每条路径): INVOKESTATIC MenuTransitionDispatch.onScreenInitReturn()V
 *
 *   Screen#extractRenderStateWithTooltipAndSubtitles:
 *     ... 原始方法体 ...
 *     RETURN: INVOKESTATIC MiliGlassBridge.onScreenRender(GuiGraphicsExtractor,IIF)V
 * </pre>
 * RETURN 由 {@code AdviceAdapter#onMethodExit} 逐退出路径触发 ——
 * init 若有 early return 也不会漏；渲染方法无 early return，
 * 每帧恰好一次。两个锚点分属两个方法，同一转换互不影响。
 *
 * <h2>开关</h2>
 * {@code -Dmili.menu.transition=false} 时返回 Skipped —— 两个锚点都不注入，
 * 游戏回到原版行为。开关读取发生在转换期（类加载时），运行期改属性
 * 只影响「尚未加载的类」，对已加载的 Screen 无回溯效果；
 * 按钮回调层另有运行期开关（每次点击现读），两层语义见
 * MenuTransitionDispatch。
 */
public final class MiliMenuTransitionTransformer implements MiliTransformer {

    /** 转换器 id —— 同时是排序与审计的主键，不可变更。 */
    public static final String ID = "mili-core-menu-transition";

    /** init 钩子分发器 —— 生成字节码中唯一的外部引用目标（app CL）。 */
    public static final String DISPATCH_OWNER =
            "org/loader/runtime/minecraft/client/screen/MenuTransitionDispatch";

    /** init 钩子回调方法名（Screen#init(II) RETURN，对全部屏幕触发）。 */
    public static final String INIT_CALLBACK = "onScreenInitReturn";

    /** 渲染钩子桥接类（运行期生成、定义进 MinecraftClassLoader）。 */
    public static final String BRIDGE_OWNER =
            "org/loader/runtime/minecraft/client/screen/MiliGlassBridge";

    /** 渲染钩子回调方法名。 */
    public static final String RENDER_CALLBACK = "onScreenRender";

    private static final String VOID_DESC = "()V";
    /** 渲染回调描述符 —— 必须与宿主实参逐位严格相等（捕获语义）。 */
    private static final String RENDER_DESC =
            "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

    private final TargetMethod initMethod;
    private final TargetMethod screenRenderMethod;

    public MiliMenuTransitionTransformer() {
        this(MiliSymbol.SCREEN_INIT, MiliSymbol.SCREEN_EXTRACT_RENDER_STATE);
    }

    /**
     * @param initMethod         屏幕初始化出口坐标（Screen 基类入口）
     * @param screenRenderMethod 屏幕渲染出口坐标
     */
    public MiliMenuTransitionTransformer(TargetMethod initMethod,
                                         TargetMethod screenRenderMethod) {
        this.initMethod = initMethod;
        this.screenRenderMethod = screenRenderMethod;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String minecraftVersion() {
        return MiliSymbol.MINECRAFT_VERSION;
    }

    /** {@link TransformationPhase#CORE} —— 受保护阶段，仅平台可用。 */
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
        return initMethod.owner().equals(className)
                || screenRenderMethod.owner().equals(className);
    }

    @Override
    public TransformationResult transform(TransformationContext context) {
        if (!Boolean.parseBoolean(System.getProperty("mili.menu.transition", "true"))) {
            return TransformationResult.Skipped.because(
                    "mili.menu.transition=false —— 转场整体关闭");
        }
        // 两个锚点同属 Screen（见类注释「为什么 init 锚点在 Screen 基类」）。
        if (!context.className().equals(screenRenderMethod.owner())) {
            return TransformationResult.Skipped.because(
                    "非 Screen —— 本转换器只处理 Screen");
        }

        // 目标方法必须真实存在。找不到就抛，绝不返回 Skipped ——
        // 否则转场会静默消失（Mods 按钮转换器的同一铁律）。
        if (!PipelineTransformers.containsMethod(context.originalBytes(),
                initMethod.name(), initMethod.descriptor())) {
            throw new org.loader.api.transform.TransformationTargetNotFoundException(
                    context.className(), initMethod.name(), initMethod.descriptor(),
                    context.environment().minecraftVersion(), id());
        }
        if (!PipelineTransformers.containsMethod(context.originalBytes(),
                screenRenderMethod.name(), screenRenderMethod.descriptor())) {
            throw new org.loader.api.transform.TransformationTargetNotFoundException(
                    context.className(), screenRenderMethod.name(),
                    screenRenderMethod.descriptor(),
                    context.environment().minecraftVersion(), id());
        }

        // ── 关键次序：先定义桥接类，再注入 ─────────────────────────────
        // 定义失败 = 渲染钩子解析必炸。两个锚点都不注入（Skipped），
        // 游戏保持原版行为 —— 绝不允许埋一颗 NoClassDefFoundError 地雷。
        if (!MenuTransitionDispatch.ensureBridgeDefinedEager()) {
            return TransformationResult.Skipped.because(
                    "玻璃桥接类定义失败 —— 两个锚点都不注入，转场停用（游戏不受影响）");
        }

        List<MiliClassTransformer.MethodInjection> injections = List.of(
                new MiliClassTransformer.MethodInjection(
                        initMethod,
                        null,
                        InjectionPoint.RETURN,
                        VOID_DESC,
                        null,
                        -1,
                        DISPATCH_OWNER,
                        INIT_CALLBACK,
                        VOID_DESC,
                        true,
                        id(),
                        0),
                new MiliClassTransformer.MethodInjection(
                        screenRenderMethod,
                        null,
                        InjectionPoint.RETURN,
                        RENDER_DESC,
                        null,
                        -1,
                        BRIDGE_OWNER,
                        RENDER_CALLBACK,
                        RENDER_DESC,
                        true,
                        id(),
                        0));

        byte[] transformed = MiliClassTransformer.apply(
                context.originalBytes(), context.className(), injections);
        return new TransformationResult.Transformed(transformed);
    }

    @Override
    public String toString() {
        return "MiliMenuTransitionTransformer[" + ID + " → " + initMethod + ", "
                + screenRenderMethod + "]";
    }
}
