package org.loader.runtime.minecraft;

import org.loader.runtime.minecraft.reflect.BridgeMismatchException;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Minecraft 26.2 分阶段启动闸门 —— Mod 注册内容的唯一合法入口。
 *
 * <h2>为什么必须分阶段（全部由反编译 26.2.jar 确认，非推测）</h2>
 *
 * <p>26.2 里方块注册表<b>冻结后不可写</b>，而冻结发生在游戏 bootstrap 内部。
 * 三条硬事实叠加：
 * <ol>
 *   <li>{@code BuiltInRegistries.<clinit>} 调 {@code Bootstrap.checkBootstrapCalled()}，
 *       要求 {@code Bootstrap.isBootstrapped == true}，否则抛
 *       {@code IllegalArgumentException: Not bootstrapped (registry minecraft:game_event)}；</li>
 *   <li>{@code Block.<init>} 调
 *       {@code BuiltInRegistries.BLOCK.createIntrusiveHolder(this)}，而
 *       {@code BuiltInRegistries.freeze()} 会把
 *       {@code unregisteredIntrusiveHolders} 置 null，之后一律抛
 *       {@code IllegalStateException: This registry can't create intrusive holders}；</li>
 *   <li>{@code Bootstrap.bootStrap()} 开头是 {@code if (isBootstrapped) return;}。</li>
 * </ol>
 *
 * <p>第 1、2 条要求「标志已置位但还没冻结」，第 3 条又要求「标志未置位」。
 * 两者直接冲突 —— 所以平台<b>不能</b>简单地调 {@code Bootstrap.bootStrap()}
 * 然后再注册，那等于在冻结后注册，一个方块都加不进去。
 *
 * <h2>已验证可行的时序</h2>
 * <pre>
 *   ① SharedConstants.tryDetectVersion()        版本就位
 *   ② 反射置 Bootstrap.isBootstrapped = true     打开 BuiltInRegistries 的类初始化门
 *   ③ 触碰 BuiltInRegistries.BLOCK              类初始化完成；注册表可写且<b>为空</b>
 *   ④ 执行所有 Mod 注册                          条目数 0 → N
 *   ⑤ 各静态 bootStrap() + BuiltInRegistries.bootStrap()   补原版内容 + freeze()
 * </pre>
 *
 * <p>实测（{@code BootstrapTimingTest}，真实 26.2）：
 * ④ 后条目数 = 1，⑤ 后条目数 = <b>1197</b>（原版 1196 + Mod 的 1），
 * 按 id 与按 ResourceKey 都能查回同一个实例。即 Mod 方块与原版内容共存。
 *
 * <h2>第四条硬约束：造出来的方块必须注册</h2>
 *
 * <p>{@code MappedRegistry.freeze()} 的字节码（偏移 114–117）是一句：
 * <pre>
 *   if (!unregisteredIntrusiveHolders.isEmpty())
 *       throw new IllegalStateException("Some intrusive holders were not registered: ...");
 * </pre>
 *
 * <p>而 {@code Block.<init>} 会无条件调
 * {@code createIntrusiveHolder(this)}，把自己登记进那个 Map；只有
 * {@code Registry.register(registry, key, block)} 会把它移走。
 *
 * <p>推论：<b>任何「造了但没注册」的方块都会让游戏启动失败</b>，
 * 而且错误消息里是一串 {@code Block{[unregistered]}}，完全不指向是哪个 Mod
 * 造成的。因此 {@link #closeRegistryWindow()} 在冻结前必须先把
 * {@code unregisteredIntrusiveHolders} 清空 —— 见该方法里的处理。
 *
 * <h2>唯一的例外：游戏本体已完成 bootstrap</h2>
 * <p>若平台运行在官方启动器路径上，游戏可能自己已经 bootstrap 完。此时
 * {@link #isWindowOpen()} 为假，Mod 无法再注册方块 —— 这是 26.2 的固有限制，
 * 只能靠 Mod 只在平台自己的启动路径上生效来规避。
 *
 * <h2>状态归属游戏实例，不归属平台</h2>
 * <p>{@code isBootstrapped} 与注册表冻结状态都由游戏 ClassLoader 定义，
 * 因此本类的窗口标志按 ClassLoader 隔离（见 {@link GameSessionState}）。
 * 同一 JVM 内跑多个游戏实例时，它们各自拥有独立的注册窗口。
 */
public final class RegistrationPhase {

    private static final Logger LOG = Logger.getLogger("Mili/Registration");

    private static final String BOOTSTRAP = "net.minecraft.server.Bootstrap";
    private static final String BUILTIN = "net.minecraft.core.registries.BuiltInRegistries";

    /**
     * {@code Bootstrap.bootStrap()} 内部在 {@code BuiltInRegistries.bootStrap()}
     * 之前调用的各静态 bootStrap。平台必须逐个补上，否则游戏功能残缺。
     */
private static final String[] SUB_BOOTSTRAPS = {
            "net.minecraft.world.level.block.FireBlock",
            "net.minecraft.world.level.block.ComposterBlock",
            "net.minecraft.commands.arguments.selector.options.EntitySelectorOptions",
            "net.minecraft.core.dispenser.DispenseItemBehavior",
            "net.minecraft.core.cauldron.CauldronInteractions",
    };

    private RegistrationPhase() {
    }

    /**
     * 登记一个待执行的 Mod 注册动作。
     *
     * <p>Mod 加载器在游戏 bootstrap 之前可以任意次调用它。动作归属于
     * <b>调用时所在游戏实例</b>（见 {@link GameSessionState}），
     * 在 {@link #runRegistrations} 中按登记顺序执行。
     *
     * <p><b>游戏已 bootstrap 完成时必须抛异常</b>，不能默默排队。
     *
     * <p>曾经的行为是「任何时候登记都不失败」，理由是「让 Mod 的加载顺序
     * 不必与游戏启动时序耦合」。但这个设计有个致命漏洞：
     * {@link #runRegistrations} 只在 {@link BootstrapGate#ensureBootstrapped()}
     * 内部被调用一次 —— 游戏一旦 bootstrap 完，登记的动作就<b>永远不会被执行</b>，
     * 也不会有任何提示。
     *
     * <p>后果是典型的静默失效：Mod 在 {@code initialize()} 之后调用
     * {@code registry().block(...)}，拿到一个句柄、没有任何异常，
     * 而游戏里根本没有那个方块。Mod 作者会一直怀疑自己的代码，
     * 却永远不会想到是「登记得太晚」。
     *
     * <p>ABI 契约对此早有明文：
     * {@code MinecraftRegistry.block} 声明 {@code @throws IllegalStateException
     * 注册时机已过}，且 {@code ModContext} 的文档写明
     * 「Registering later fails loudly rather than corrupting the registry」。
     * 平台必须兑现这个承诺 —— 契约写了而实现不响，是最糟的一种脱节。
     *
     * @param registration 注册动作；在 {@link #runRegistrations} 中按登记顺序执行
     * @throws IllegalStateException 注册窗口已关闭，该动作永远不会被执行
     */
    public static void defer(Runnable registration) {
        if (registration == null) {
            throw new IllegalArgumentException("registration must not be null");
        }
        GameSessionState state = GameSessionState.current();
        if (state.windowClosed.get()) {
            throw new IllegalStateException(
                    "The registration window is already closed — this registration would"
                            + " never run."
                            + "\n  Minecraft 26.2 freezes the block registry inside"
                            + " BuiltInRegistries.bootStrap(); anything queued afterwards is"
                            + " silently dropped."
                            + "\n  Register blocks in your mod's initialize(ModContext)"
                            + " method, or via RegistrationPhase.defer(...) before the"
                            + " platform starts the game.");
        }
        state.pending.add(registration);
    }

    /** 尚未执行的 Mod 注册动作数。 */
    public static int pendingCount() {
        return GameSessionState.current().pending.size();
    }

    /**
     * 执行已登记的 Mod 注册动作。必须在 {@link #openRegistryWindow()} 之后、
     * {@code BuiltInRegistries.bootStrap()} 之前调用。
     *
     * <p>单个 Mod 的注册失败不会中断其余 Mod —— 一个坏 Mod 不该让整个游戏起不来。
     * 失败会被记录并汇总。
     */
    static void runRegistrations() {
        List<Runnable> actions = GameSessionState.current().pending;
        List<Throwable> failures = new ArrayList<>();
        for (Runnable r : actions) {
            try {
                r.run();
            } catch (Throwable t) {
                LOG.log(java.util.logging.Level.SEVERE,
                        "Mod block registration failed; continuing with the rest", t);
                failures.add(t);
            }
        }
        actions.clear();
        if (!failures.isEmpty()) {
            LOG.log(java.util.logging.Level.SEVERE,
                    failures.size() + " mod registration(s) failed; the game will start"
                            + " without them");
        }
    }

    /**
     * 打开注册窗口：置位 {@code Bootstrap.isBootstrapped} 并触发
     * {@code BuiltInRegistries} 的类初始化。
     *
     * <p>必须在任何 {@code new Block(...)} 之前调用。
     *
     * @throws BridgeMismatchException 若无法置位（版本变更导致字段改名）
     */
    public static void openRegistryWindow() {
        GameSessionState state = GameSessionState.current();
        if (state.windowClosed.get()) {
            throw new BridgeMismatchException(
                    "The registration window is already closed — Minecraft 26.2 freezes the"
                            + " block registry in BuiltInRegistries.bootStrap()."
                            + "\n  Mod blocks must be registered via"
                            + " RegistrationPhase.defer(...) before the platform starts"
                            + " the game.");
        }
        SharedVersionGate.ensureVersionDetected();

        if (!state.flagSet.compareAndSet(false, true)) {
            return; // 已置位，幂等
        }
        // 唯一一处写 Minecraft 私有字段的地方。必要性见类注释：BuiltInRegistries
        // 的类初始化硬性要求这个标志，而 bootstrap() 一旦发现它为 true 就整段 return。
        try {
            Class<?> bootstrap = Reflect.gameClass(BOOTSTRAP);
            Field flag = bootstrap.getDeclaredField("isBootstrapped");
            flag.setAccessible(true);
            flag.setBoolean(null, true);
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "Cannot set Bootstrap.isBootstrapped."
                            + "\n  Without it BuiltInRegistries' static initialiser throws"
                            + " IllegalArgumentException(\"Not bootstrapped\") and no block"
                            + " can ever be created."
                            + "\n  This field was renamed in a later Minecraft version;"
                            + " the binding layer needs updating.", e);
        }

        // 触碰 BuiltInRegistries 以完成类初始化。此时它只建出空注册表 ——
        // 原版内容要等 bootStrap() 里的 createContents() 才填。这正是 Mod 的空隙。
        //
        // 【顺序不可换】必须先置 isBootstrapped 再触碰本类。反过来会失败，
        // 而失败会把 BuiltInRegistries 永久标记为 Erroneous（见 ensureRegistriesReadable）。
        try {
            Reflect.staticField(BUILTIN, "BLOCK");
        } catch (RuntimeException | Error e) {
            throw new BridgeMismatchException(
                    "BuiltInRegistries failed to initialise even after the bootstrap flag"
                            + " was set. Real cause: " + e.getCause(), e);
        }
    }

    /**
     * 确保 {@code BuiltInRegistries} 的类初始化<b>已经成功完成</b>，
     * 使后续只读查询（{@code getValue} / {@code getId}）可以安全执行。
     *
     * <h2>为什么这个方法是必需的</h2>
     *
     * <p>26.2 里 {@code BuiltInRegistries.<clinit>} 会调
     * {@code Bootstrap.checkBootstrapCalled()}，要求
     * {@code isBootstrapped == true}。若在置位之前有人先碰到这个类，
     * 类初始化会失败，而 <b>JVM 对失败过的类初始化不会重试</b> ——
     * 该类被永久标记为 {@code Erroneous}，此后任何访问都直接抛
     * {@code NoClassDefFoundError: Could not initialize class ...}。
     *
     * <p>这个坑真实发生过：Mod 在 {@code initialize()} 里调
     * {@code registry().findBlock(...)} 打印注册清单（纯只读意图），
     * 恰好成了第一个触碰者 → 毒化；Mod 因为被 catch 住而"成功"跑完，
     * 但平台随后 {@code openRegistryWindow()} 再碰同一个类时直接炸掉，
     * 报「refusing to start Minecraft without it」，错误完全指不到真正原因。
     *
     * <p>因此规矩是：<b>任何要触碰注册表类的地方，都必须先经由本方法</b>，
     * 由它保证「先置标志、再触碰」这个唯一安全的顺序。只读查询也不例外 ——
     * "我只是看一眼"照样会触发类初始化。
     *
     * <p>幂等：重复调用只在首次执行实际动作。
     *
     * @throws BridgeMismatchException 无法置位标志（游戏版本变更导致字段改名）
     */
    public static void ensureRegistriesReadable() {
        // openRegistryWindow 内部已完成「置标志 + 触碰注册表类」，
        // 且这两步顺序正确、对重复调用幂等 —— 直接复用，不必重复实现。
        openRegistryWindow();
    }

    /**
     * 关闭注册窗口：跑完游戏自身的 bootstrap（补原版内容 + 冻结注册表）。
     * 之后任何 {@code new Block(...)} 都会失败。
     */
    public static void closeRegistryWindow() {
        GameSessionState state = GameSessionState.current();
        if (!state.windowClosed.compareAndSet(false, true)) {
            return; // 幂等
        }
        for (String cls : SUB_BOOTSTRAPS) {
            try {
                Reflect.gameClass(cls).getMethod("bootStrap").invoke(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // 某个静态 bootStrap 失败不应阻断其余 —— 记日志继续。
                LOG.log(java.util.logging.Level.WARNING,
                        "Sub-bootstrap " + cls + " failed; the game may be incomplete", e);
            }
        }
        // 冻结前必须处理「造了但没注册」的方块，否则 freeze() 直接抛
        // "Some intrusive holders were not registered" —— 整局游戏起不来。
        List<Object> orphans = drainUnregisteredBlocks();
        if (!orphans.isEmpty()) {
            throw new BridgeMismatchException(
                    orphans.size() + " block(s) were constructed but never registered."
                            + "\n  Minecraft 26.2 refuses to freeze the block registry while"
                            + " any intrusive holder is still unregistered — one forgotten"
                            + " BlockRegistrar.register(...) call makes the whole game fail"
                            + " to start."
                            + "\n  Constructing a block is NOT registering it: use"
                            + " BlockRegistrar.register(spec), or"
                            + " GeneratedBlockFactory.createBlock(...) only for a block you"
                            + " register yourself."
                            + "\n  Offending blocks: " + describeOrphans(orphans));
        }
        try {
            Reflect.gameClass(BUILTIN).getMethod("bootStrap").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "BuiltInRegistries.bootStrap() failed; Minecraft cannot finish starting."
                            + "\n  Real cause: " + e.getCause(), e);
        }
        try {
            Reflect.gameClass("net.minecraft.world.item.CreativeModeTabs")
                    .getMethod("validate").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING,
                    "CreativeModeTabs.validate() failed", e);
        }
    }

    /**
     * 取出所有「造了但没注册」的方块，并把该 Map 清空。
     *
     * <p>{@code MappedRegistry.unregisteredIntrusiveHolders} 是一个私有
     * {@code Map<T, Holder.Reference<T>>}，键是方块实例。{@code freeze()} 会
     * 检查它是否为空，非空即抛异常（偏移 114–117）。
     *
     * <p>清空是<b>必须的</b>：不清空则游戏永远起不来。但平台也不能默默丢掉
     * 这些方块 —— 那样 Mod 作者会看到「我的作物方块凭空消失了」这种极难排查
     * 的现象。因此这里把清单带回给调用方，由它决定是让启动失败（默认，
     * 见 {@link #closeRegistryWindow()}）还是降级放行。
     *
     * <p>写私有字段是本平台第二次这样做（第一次是 {@code isBootstrapped}）。
     * 这两处都是 26.2 没有对外 API、但平台又必须介入的位置 —— 已在类注释中
     * 逐条记录理由与实测依据。
     */
    private static List<Object> drainUnregisteredBlocks() {
        List<Object> orphans = new ArrayList<>();
        try {
            // 【不要在这里调 ensureRegistriesReadable()】
            // 本方法由 closeRegistryWindow() 在【窗口已关闭之后】调用，
            // 而 ensureRegistriesReadable() 内部走 openRegistryWindow()，
            // 遇到 windowClosed=true 会直接抛
            // "The registration window is already closed"。
            //
            // 上一版把它当成"幂等二次保险"加在这里，结果每次关窗都必然抛错 ——
            // 而且它屏蔽掉了本方法真正要报告的内容（未注册的方块）。
            //
            // 此刻注册表类必然已初始化完毕（窗口能开就说明标志已置、
            // BuiltInRegistries 已被触碰过），直接读即可。
            Object registry = Reflect.staticField(BUILTIN, "BLOCK");
            // 【必须沿类继承链查找】字段声明在父类 MappedRegistry 里
            // （26.2 实测第 66 行），而 BuiltInRegistries.BLOCK 的运行时类
            // 是它的子类 DefaultedMappedRegistry。Class.getDeclaredField
            // 只看类自身、不看父类 —— 上一版直接对 registry.getClass() 调用，
            // 于是抛 NoSuchFieldException，并把"字段在父类"误报成"字段改名了"。
            Field field = findFieldInHierarchy(registry.getClass(),
                    "unregisteredIntrusiveHolders");
            field.setAccessible(true);
            Object map = field.get(registry);
            if (map instanceof java.util.Map<?, ?> holders && !holders.isEmpty()) {
                for (Object holder : holders.keySet()) {
                    // 键就是方块实例本身（Map<T, Holder.Reference<T>>），
                    // 无需再解包 Holder —— 全部走反射，保持本模块编译期
                    // 不依赖 Minecraft（ADR 0005）。
                    orphans.add(holder);
                }
                holders.clear();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 字段改名或 Holder 结构变了：不阻断启动，交给 freeze() 自己去报，
            // 那样至少还能拿到 Minecraft 自己的错误信息。
            LOG.log(java.util.logging.Level.WARNING,
                    "Cannot drain unregistered intrusive holders (field renamed?);"
                            + " Minecraft's own freeze() check will report the problem", e);
        }
        return orphans;
    }

    /**
     * 沿继承链查找字段。
     *
     * <p>{@link Class#getDeclaredField} 只在类自身声明的字段里找，不含父类。
     * 游戏的注册表实现普遍是多层继承
     * （{@code DefaultedMappedRegistry} → {@code MappedRegistry}），
     * 数据字段通常声明在基类 —— 只查自身必然 {@code NoSuchFieldException}。
     */
    private static Field findFieldInHierarchy(Class<?> type, String name)
            throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                // 继续往父类找
            }
        }
        throw new NoSuchFieldException(
                name + " not found in " + type.getName() + " or any superclass");
    }

    /** 把未注册的方块渲染成可读清单。 */
    private static String describeOrphans(List<Object> orphans) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(orphans.size(), 10);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(orphans.get(i).getClass().getName());
        }
        if (orphans.size() > limit) {
            sb.append(" ... and ").append(orphans.size() - limit).append(" more");
        }
        return sb.toString();
    }

    /** 注册窗口是否仍开着（= Mod 还能新建方块）。 */
    public static boolean isWindowOpen() {
        GameSessionState state = GameSessionState.current();
        return state.flagSet.get() && !state.windowClosed.get();
    }

    /** 仅供测试重置。 */
    static void resetForTesting() {
        GameSessionState state = GameSessionState.current();
        state.flagSet.set(false);
        state.windowClosed.set(false);
        state.pending.clear();
    }
}
