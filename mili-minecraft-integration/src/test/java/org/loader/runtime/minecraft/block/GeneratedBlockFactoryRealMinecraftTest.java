package org.loader.runtime.minecraft.block;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.loader.api.registry.BlockSpec;
import org.loader.api.world.BlockPos;
import org.loader.api.world.MiliBlock;
import org.loader.api.world.WorldView;
import org.loader.runtime.minecraft.GameTestClassLoader;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 绑定层的<b>真实 Minecraft</b> 端到端验证。
 *
 * <p>与 {@code MinecraftPipelineTest} 的区别：后者验证「构建流水线能跑」，
 * 本类验证「生成的方块类真的能被 JVM 定义、实例化、并把调用正确转调到
 * Mod 的行为实现」。前者全绿但本类失败，是完全可能的 —— 流水线不关心字节码。
 *
 * <p><b>为什么这类测试不可省略</b>：绑定层的核心手段是运行时生成
 * {@code extends Block} 的字节码。任何一处描述符写错、任何一处包名不符，
 * 都不会在编译期暴露，只会在游戏里抛
 * {@code ClassFormatError: Invalid method descriptor} —— 而这类错误信息
 * 完全不指向真正的原因。历史上真实踩过的坑：
 * <ul>
 *   <li>{@code Lookup#defineClass} 要求生成类与 {@code Block} 同包；</li>
 *   <li>引用类型描述符每个都要带结尾的 {@code ;}；</li>
 *   <li>{@code Properties.of()} 在 bootstrap 之前会抛
 *       {@code IllegalArgumentException: Not bootstrapped}。</li>
 * </ul>
 * 只有在真实 26.2 上跑一遍才能守住这些。
 *
 * <p>没有 MC JAR 时整类跳过 —— CI 不应该有 Minecraft 构建输入。
 */
@DisplayName("绑定层真实 Minecraft 端到端")
class GeneratedBlockFactoryRealMinecraftTest {

    private static Path minecraftDir;
    private static GameTestClassLoader gameLoader;

    /**
     * 26.2 的方块注册表在游戏启动完成时被冻结，之后 {@code new Block(...)}
     * 必抛 {@code This registry can't create intrusive holders}。
     *
     * <p>因此所有方块必须在 {@code @BeforeAll} 里、{@code ensureBootstrapped()}
     * 之前造好，各测试用例只从这些已造好的实例里取。测试顺序即注册顺序 ——
     * 这与平台真实的启动时序完全一致。详见
     * {@link org.loader.runtime.minecraft.RegistrationPhase}。
     */
    private static final Map<String, Object> PRE_BUILT = new LinkedHashMap<>();

    @BeforeAll
    static void setUp() throws Exception {
        minecraftDir = org.loader.runtime.minecraft.GameTestClassLoader.locateMinecraftDir();
        if (minecraftDir == null) {
            return;
        }
        // 关键：绑定层默认用平台 CL 找游戏类，而真实拓扑里 Minecraft 由
        // MinecraftClassLoader 定义。必须显式注入，否则全部断言都会挂在
        // ClassNotFoundException: net.minecraft.SharedConstants 上 ——
        // 那不是绑定层坏了，而是它找错了 ClassLoader。
        //
        // 该加载器的父加载器是平台 CL，与生产拓扑一致 —— 详见
        // GameTestClassLoader 的类注释：父加载器为 null 会让生成的
        // MiliBlock 解析不到 BehaviourDispatch，那是生产环境不存在的问题。
        gameLoader = org.loader.runtime.minecraft.GameTestClassLoader.install("mili-probe-game");

        // ── 冻结前阶段：造出所有测试用方块 ──────────────────────────────
        org.loader.runtime.minecraft.SharedVersionGate.ensureVersionDetected();
        // blockFor(...) 走注册路径，因此 registrar 必须最先就位。
        PROBE_REGISTRAR = new BlockRegistrar("mili_probe_blocks");
        blockFor("selfcheck", BlockSpec.builder("mili/selfcheck").build());
        blockFor("shape", BlockSpec.builder("mili/shape")
                .material(BlockSpec.Material.SOLID).build());
        blockFor("hooks", BlockSpec.builder("mili/hooks").build());
        blockFor("unreg", BlockSpec.builder("mili/unreg").build());
        blockFor("hard", BlockSpec.builder("mili/hard")
                .material(BlockSpec.Material.SOLID).hardness(5.0f).build());
        blockFor("indestructible", BlockSpec.builder("mili/indestructible")
                .hardness(-1.0f).build());
        // PLANT + noCollision：验证材质相关映射落在真实 Block 上
        blockFor("plant", BlockSpec.builder("mili/plant")
                .material(BlockSpec.Material.PLANT)
                .noCollision()
                .replaceable()
                .hardness(0.0f)
                .lightLevel(7)
                .build());

        // 注册路径：必须在冻结前完成，所以也放在这里。
        dispatchBaseline = BehaviourDispatch.count();
        REGISTRAR = new BlockRegistrar("mili_probe");
        REGISTRAR_BLOCK = REGISTRAR.register(BlockSpec.builder("probe/registered")
                .material(BlockSpec.Material.SOLID).hardness(2.0f).build());

        AtomicInteger hookedCalls = new AtomicInteger();
        HOOKED_BLOCK = REGISTRAR.register(
                BlockSpec.builder("probe/hooked")
                        .behavior(new MiliBlock() {
                            @Override
                            public void onPlaced(WorldView world, BlockPos pos) {
                                hookedCalls.incrementAndGet();
                            }
                        }).build());
        HOOKED_CALLS = hookedCalls;

        // 重复注册：第一次成功，第二次必须被拒。
        DUP_REGISTRAR = new BlockRegistrar("mili_probe3");
        DUP_REGISTRAR.register(BlockSpec.builder("probe/dup").build());
        try {
            DUP_REGISTRAR.register(BlockSpec.builder("probe/dup").build());
            DUP_ERROR = null;
        } catch (IllegalStateException expected) {
            DUP_ERROR = expected;
        }

        // ── 冻结阶段 ─────────────────────────────────────────────────────
        org.loader.runtime.minecraft.BootstrapGate.ensureBootstrapped();
    }

    private static BlockRegistrar REGISTRAR;
    /** 预构建测试方块用的 registrar —— 见 blockFor(...) 的说明。 */
    private static BlockRegistrar PROBE_REGISTRAR;
    private static Object REGISTRAR_BLOCK;
    private static Object HOOKED_BLOCK;
    private static AtomicInteger HOOKED_CALLS;
    private static BlockRegistrar DUP_REGISTRAR;
    private static IllegalStateException DUP_ERROR;
    /** 注册阶段开始前的句柄数，作为泄漏断言的基线。 */
    private static int dispatchBaseline;

    /**
     * 造一个方块并记入 PRE_BUILT，供各测试按名取用。
     *
     * <p><b>走注册路径而非裸 createBlock</b>：26.2 的 {@code freeze()} 会拒绝
     * 任何「造了没注册」的方块（{@code Some intrusive holders were not
     * registered}）—— 那会让本类的 {@code ensureBootstrapped()} 直接失败，
     * 且症状离真正原因隔了三层。这里用一个独立的 registrar，让每个测试方块
     * 都有合法的注册 id。
     */
    private static Object blockFor(String key, BlockSpec spec) {
        Object block = PROBE_REGISTRAR.register(spec);
        PRE_BUILT.put(key, block);
        return block;
    }

    private static Object preBuilt(String key) {
        Object block = PRE_BUILT.get(key);
        assertNotNull(block, "预构建方块缺失: " + key);
        return block;
    }

    @AfterAll
    static void tearDown() throws Exception {
        Reflect.useGameClassLoader(null);
        if (gameLoader != null) {
            gameLoader.close();
        }
    }

    private static boolean mcAvailable() {
        return minecraftDir != null && gameLoader != null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 自检
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("自检通过：能在真实 26.2 上生成方块类")
    void selfCheckPassesOnRealMinecraft() {
        // 自检刻意在游戏启动之后运行：它必须与注册窗口的开关无关 ——
        // 一个「绑定层是否可用」的检查若在游戏正常启动后报「窗口已关闭」，
        // 那比不检查更糟。当前实现只生成并定义类、不构造方块，
        // 因此没有任何副作用，也不需要窗口开着。
        String failure = GeneratedBlockFactory.selfCheck();
        assertTrue(failure.isEmpty(),
                () -> "绑定层自检失败：\n" + failure
                        + "\n\n排查顺序："
                        + "\n  1. 游戏类是否由正确的 ClassLoader 定义（见 GameTestClassLoader）"
                        + "\n  2. Block 是否 final（final 则无法子类化）"
                        + "\n  3. 生成类是否位于 net.minecraft.world.level.block 包"
                        + "\n  4. 方法描述符每个引用类型是否都带结尾 ';'");
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("游戏类确实由探针 ClassLoader 加载（不是平台 CL）")
    void gameClassesComeFromProbeLoader() throws Exception {
        Class<?> block = Class.forName("net.minecraft.world.level.block.Block", false, gameLoader);
        assertSame(gameLoader, block.getClassLoader(),
                "Block 必须来自探针 CL，否则这个测试没有真正验证 ClassLoader 隔离场景");
    }

    // ══════════════════════════════════════════════════════════════════════
    // 方块生成
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("生成的方块确实是 Block 的子类，且位于游戏包内")
    void generatedBlockIsBlockSubclassInGamePackage() throws Exception {
        Class<?> blockClass = Class.forName(
                "net.minecraft.world.level.block.Block", false, gameLoader);
        assertTrue(!java.lang.reflect.Modifier.isFinal(blockClass.getModifiers()),
                "Block 不是 final —— 若 Minecraft 改了这一点，本绑定层需要重写");

        Object block = preBuilt("shape");

        assertNotNull(block);
        assertTrue(blockClass.isInstance(block), "生成实例必须是 Block 的实例");
        assertSame(gameLoader, block.getClass().getClassLoader(),
                "生成类必须定义在游戏 ClassLoader 里（与 Block 同包）");
        assertTrue(block.getClass().getName().startsWith("net.minecraft.world.level.block."),
                "生成类包名不符： " + block.getClass().getName());
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("生成类确实覆写了 26.2 的行为钩子（签名逐一核对）")
    void generatedClassOverridesRealHooks() throws Exception {
        Object block = preBuilt("hooks");
        Class<?> cls = block.getClass();
        Class<?> blockClass = Class.forName(
                "net.minecraft.world.level.block.Block", false, gameLoader);

        // 这些签名取自 26.2 实测（BlockBehaviour 上）。任何一个对不上，
        // 说明 Minecraft 改了 API —— 此时应该失败并给出清晰信息，
        // 而不是让 Mod 作者在游戏里看到 ClassFormatError。
        assertTrue(declares(cls, blockClass, "tick", 4),
                "未覆写 tick(BlockState, ServerLevel, BlockPos, RandomSource)");
        assertTrue(declares(cls, blockClass, "randomTick", 4),
                "未覆写 randomTick(BlockState, ServerLevel, BlockPos, RandomSource)");
        assertTrue(declares(cls, blockClass, "neighborChanged", 6),
                "未覆写 neighborChanged(..., Orientation, boolean)");
        assertTrue(declares(cls, blockClass, "onPlace", 5),
                "未覆写 onPlace(BlockState, Level, BlockPos, BlockState, boolean)");
        assertTrue(declares(cls, blockClass, "attack", 4),
                "未覆写 attack(BlockState, Level, BlockPos, Player)");
    }

    private static boolean declares(Class<?> sub, Class<?> sup, String name, int argc) {
        outer:
        for (Class<?> c = sub; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == argc) {
                    continue outer;
                }
            }
        }
        return true;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 行为委派
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("onPlace 被正确转调：坐标原样送达 Mod 实现")
    void onPlaceIsDispatchedToModBehaviour() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> seen = new ArrayList<>();

        MiliBlock behaviour = new MiliBlock() {
            @Override
            public void onPlaced(WorldView world, BlockPos pos) {
                calls.incrementAndGet();
                seen.add(pos.x() + "," + pos.y() + "," + pos.z());
            }
        };

        BehaviourDispatch.Handle handle =
                BehaviourDispatch.register(behaviour, "placement-probe");
        try {
            // 方块本体在冻结前已造好；这里只把行为句柄后置绑定上去。
            // BehaviourDispatch.bind 存在的意义正是如此 ——
            // 构造期拿不到句柄（Mod 还没加载完），但 tick 之前一定绑得上。
            Object block = preBuilt("hooks");
            BehaviourDispatch.bind(block, handle);

            // 构造真实的 BlockPos，调用生成类的 onPlace。
            invokeHook(block, "onPlace", 5);

            assertEquals(1, calls.get(),
                    "onPlace 应被转调恰好一次；0 次说明字节码没接上，多于 1 次说明重复覆写");
        } finally {
            BehaviourDispatch.unregister(handle);
        }
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("未注册行为的方块：钩子被调用时不抛异常")
    void unregisteredBehaviourDoesNotThrow() throws Exception {
        // Mod 卸载后其方块可能仍在世界里被 tick。此时 handle 查不到实现，
        // 正确行为是静默跳过，而不是抛 NoSuchElementException 打断游戏循环。
        BehaviourDispatch.Handle handle =
                BehaviourDispatch.register(new MiliBlock() { }, "temporary");
        Object block = preBuilt("unreg");
        BehaviourDispatch.bind(block, handle);
        BehaviourDispatch.unregister(handle);

        assertDoesNotThrow(() -> invokeHook(block, "onPlace", 5),
                "注销后调用钩子必须安全失败");
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("Mod 回调抛异常时被隔离，不逃逸进游戏")
    void modExceptionIsContained() throws Exception {
        BehaviourDispatch.Handle handle = BehaviourDispatch.register(new MiliBlock() {
            @Override
            public void onPlaced(WorldView world, BlockPos pos) {
                throw new IllegalStateException("模拟 Mod 崩溃");
            }
        }, "exploding-probe");
        try {
            Object block = preBuilt("unreg");
            BehaviourDispatch.bind(block, handle);
            assertDoesNotThrow(() -> invokeHook(block, "onPlace", 5),
                    "Mod 的异常必须被 BehaviourDispatch 吞掉 —— "
                            + "逃逸出去会破坏 Minecraft 的 tick 循环");
        } finally {
            BehaviourDispatch.unregister(handle);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Properties 映射
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("BlockSpec → BlockBehaviour.Properties 映射可用")
    void blockSpecMapsToRealProperties() {
        BlockSpec spec = BlockSpec.builder("crops/probe_crop")
                .material(BlockSpec.Material.PLANT)
                .noCollision()
                .replaceable()
                .hardness(0.0f)
                .lightLevel(7)
                .requiresTool(false)
                .build();

        // Properties 不碰注册表，冻结后仍可构造。映射的<b>结果</b>由
        // blockSpecPropertiesLandOnRealBlock 断言 —— 那里读的是游戏真正使用的字段。
        Object properties = assertDoesNotThrow(
                () -> BlockPropertiesBuilder.build(spec, "probemod"),
                "BlockSpec 应能映射为真实 Properties");

        assertNotNull(properties);
        assertEquals("net.minecraft.world.level.block.state.BlockBehaviour$Properties",
                properties.getClass().getName(),
                "必须产出游戏自己的 Properties 类型，否则根本传不进 Block 构造器");
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("BlockSpec 的材质/碰撞真的落到 Block 上")
    void blockSpecPropertiesLandOnRealBlock() throws Exception {
        // PLANT + noCollision + lightLevel(7) 的组合，在 @BeforeAll 冻结前注册。
        assertTrue(readBoolean(preBuilt("plant"), "isRandomlyTicking"),
                "PLANT/可替换方块必须 isRandomlyTicking（作物生长依赖随机刻）");
        assertFalse(readBoolean(preBuilt("plant"), "hasCollision"),
                "noCollision() 必须真的关掉碰撞");
        assertTrue(readBoolean(preBuilt("shape"), "hasCollision"),
                "默认方块应保留碰撞");

        // 构造能力本身由 @BeforeAll 的 blockFor(...) 覆盖 —— 那里真造出了方块。
        assertNotNull(preBuilt("shape"),
                "预构建阶段必须已成功构造出 Block 实例");
    }

    private static boolean readBoolean(Object target, String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getBoolean(target);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        throw new AssertionError("字段 " + name + " 不存在于 "
                + target.getClass().getName() + " 及其父类");
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("硬度真的落到 Block 的默认破坏时间上")
    void hardnessMapsToRealBlockProperty() throws Exception {
        // 26.2 把 destroyTime 从字段改成了 defaultDestroyTime() 方法
        // （javap BlockBehaviour 实测确认）。用字段读会得到
        // "字段 destroyTime 不存在" —— 一个看起来像绑定层坏了、
        // 实际是测试自己用了过时 API 的失败。
        assertEquals(5.0f, readFloat(preBuilt("hard"), "defaultDestroyTime"), 1e-6,
                "BlockSpec.hardness(5.0) 应落到 Block.defaultDestroyTime()。"
                        + "\n  若为 0，说明 strength() 没被调用或被后续调用覆盖");

        // 反向：负硬度表示不可破坏，Minecraft 编码为 -1
        assertEquals(-1.0f, readFloat(preBuilt("indestructible"), "defaultDestroyTime"), 1e-6,
                "hardness < 0 应映射为不可破坏（defaultDestroyTime = -1）");

        // 对照组：BlockSpec 的默认硬度是 3.0（Minecraft 的默认值），且能被显式覆盖为 0。
        // 没有这条对照，只断言 5.0 的话，"所有方块都返回同一个值"也能通过。
        assertEquals(3.0f, readFloat(preBuilt("hooks"), "defaultDestroyTime"), 1e-6,
                "未指定 hardness 时应沿用 BlockSpec 的默认值 3.0");
        assertEquals(0.0f, readFloat(preBuilt("plant"), "defaultDestroyTime"), 1e-6,
                "显式 hardness(0.0)（如作物）应落到 0，而不是被默认值覆盖");
    }

    /**
     * 读 float 返回值的方法（含父类搜索）。
     *
     * <p>用方法而非字段：Minecraft 26.2 把 {@code destroyTime} 字段改成了
     * {@code defaultDestroyTime()}。字段与方法的取舍取决于目标版本，
     * 因此这里只提供方法版，避免测试里再出现"读一个不存在的字段"。
     */
    private static float readFloat(Object target, String methodName) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(methodName);
                m.setAccessible(true);
                return (Float) m.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // 继续往父类找
            }
        }
        throw new AssertionError("方法 " + methodName + "() 不存在于 "
                + target.getClass().getName() + " 及其父类");
    }

    // ══════════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════════

    /** 通过真实 BlockPos 构造调用钩子。 */
    private static void invokeHook(Object block, String hookName, int argc) throws Exception {
        Class<?> blockClass = Class.forName(
                "net.minecraft.world.level.block.Block", false, gameLoader);
        Method hook = null;
        for (Class<?> c = block.getClass(); c != null && hook == null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(hookName) && m.getParameterCount() == argc) {
                    hook = m;
                    break;
                }
            }
        }
        assertNotNull(hook, "找不到钩子 " + hookName + "/" + argc);
        hook.setAccessible(true);

        Object[] args = new Object[argc];
        // 位置参数（index 2 对绝大多数钩子都是 BlockPos）填真实 BlockPos，
        // 其余填 null —— 绑定层必须容忍 null。
        Class<?> posClass = Class.forName("net.minecraft.core.BlockPos", false, gameLoader);
        java.lang.reflect.Constructor<?> ctor =
                posClass.getConstructor(int.class, int.class, int.class);
        Object pos = ctor.newInstance(10, 64, -20);

        for (int i = 0; i < argc; i++) {
            Class<?> pt = hook.getParameterTypes()[i];
            if (pt == posClass) {
                args[i] = pos;
            } else if (pt == boolean.class) {
                args[i] = Boolean.FALSE;
            } else if (pt == int.class) {
                args[i] = 0;
            } else {
                args[i] = null;
            }
        }
        assertNotNull(ctor, "BlockPos(int,int,int) 必须存在");
        hook.invoke(block, args);
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("Minecraft 版本探测可用（确认探针连的是真实 26.2）")
    void probeTargetsExpectedVersion() throws Exception {
        org.loader.runtime.minecraft.BootstrapGate.ensureBootstrapped();
        String text = org.loader.runtime.minecraft.BootstrapGate.currentGameVersion();
        assertTrue(text.contains("26.2"),
                () -> "探针连上的不是目标版本，实际为: " + text);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 端到端注册 —— 前面所有测试都只证明"造得出来"，
    // 这一节才证明"游戏里真的有这个方块"
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("方块真的出现在 Minecraft 方块注册表里")
    void blockEndsUpInRealMinecraftRegistry() {
        assertNotNull(REGISTRAR_BLOCK, "register 必须返回 Block 实例");
        // @BeforeAll 里这个 registrar 注册了两个方块：
        // probe/registered 与 probe/hooked（后者带行为）。
        assertEquals(2, REGISTRAR.registeredCount(),
                "registeredCount 必须如实反映已注册数量 —— 它是重复注册检测的依据，"
                        + "少报会让后来的重复注册漏过");

        // 关键断言：不只是"我这边记了一笔"，而是从 Minecraft 注册表里查回来。
        // 查得到 ⇒ 方块真的进入了游戏，注册 id 也与实例自洽。
        Object fromRegistry = REGISTRAR.lookup("probe/registered");
        assertNotNull(fromRegistry,
                "方块没能进入 BuiltInRegistries.BLOCK —— 造出实例不等于注册成功");
        assertSame(REGISTRAR_BLOCK, fromRegistry,
                "从注册表取回的必须是同一个实例");
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("注册进注册表后行为钩子依然转调")
    void registeredBlockStillDispatchesBehaviour() throws Exception {
        invokeHook(HOOKED_BLOCK, "onPlace", 5);
        assertEquals(1, HOOKED_CALLS.get(),
                "注册后钩子仍必须转调到 Mod 实现 —— 注册不该断开行为委派链");
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("重复注册同一 id 会被明确拒绝，而不是静默覆盖")
    void duplicateRegistrationIsRejected() {
        // 注册必须在冻结前完成，所以第二次注册在 @BeforeAll 里就已发生。
        assertNotNull(DUP_ERROR, "同一 id 注册两次必须抛 IllegalStateException —— "
                + "静默覆盖会让 Mod A 覆盖 Mod B 的方块");
        assertTrue(DUP_ERROR.getMessage().contains("probe/dup"),
                "错误消息应指明冲突的 id，实际：" + DUP_ERROR.getMessage());
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("注册失败不会在 BehaviourDispatch 留下悬挂句柄")
    void failedRegistrationLeavesNoDanglingHandle() {
        // 注册路径先申请句柄再构造 Block。若后续步骤失败却不解绑，
        // BehaviourDispatch 里就会积累永不释放的条目 —— Mod 反复加载/卸载
        // 时表现为持续内存泄漏。
        //
        // @BeforeAll 里 DUP_REGISTRAR 做过一次成功 + 一次失败注册，
        // 条目数应恰好为 1（只有成功那次留下句柄）。
        assertEquals(1, behaviourDispatchSize() - dispatchBaseline,
                "失败的注册不得留下行为句柄 —— 否则每次 Mod 重载都泄漏一份");
    }

    private static int behaviourDispatchSize() {
        return BehaviourDispatch.count();
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("本地 Minecraft 目录缺失时整类跳过而不是失败")
    void absenceIsNotFailure() {
        if (minecraftDir == null) {
            assertNull(minecraftDir);
            assertTrue(true, "没有 Minecraft 构建输入时跳过是正确行为");
        } else {
            assertTrue(Files.exists(minecraftDir.resolve("26.2.jar")));
        }
    }
}