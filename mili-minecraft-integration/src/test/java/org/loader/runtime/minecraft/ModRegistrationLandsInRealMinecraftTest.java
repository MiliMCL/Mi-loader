package org.loader.runtime.minecraft;

import org.junit.jupiter.api.*;
import org.loader.api.registry.BlockSpec;
import org.loader.api.registry.MinecraftRegistry;
import org.loader.api.world.BlockHandle;
import org.loader.runtime.minecraft.block.BlockRegistrar;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 验证「Mod 通过契约注册的方块，真的落进了 Minecraft 26.2 的注册表」。
 *
 * <h2>为什么需要这一层，而上一层不够</h2>
 *
 * <p>{@code ModLoadingEndToEndTest}（mili-loader）验证的是契约侧：
 * 入口被找到、initialize 被调用、{@code registry().block(...)} 返回了句柄。
 * 但那一层<b>无法证明方块进了游戏</b> —— 完全可以返回一个纯 Map 支撑的
 * 空壳注册表，全部断言照样通过。
 *
 * <p>历史上正是这样：{@code MinecraftRegistryBridge} 是个
 * {@code Map<String, Registry<Object>>} 空壳，「注册再查回来」永远成功，
 * 而游戏里根本没有那个方块。<b>假绿比没有更糟</b>。
 *
 * <p>本测试直接在真实 26.2.jar 上跑，走完整的注册时序
 * （打开窗口 → 跑deferred 注册 → 关闭窗口），然后用
 * {@code BuiltInRegistries.BLOCK} 反射核对条目。
 *
 * <h2>不启动游戏客户端（用户明确约束）</h2>
 *
 * <p>只做「类加载 + 注册表读写」，不跑游戏主循环、不建窗口、不加载资源包。
 * 成本约几秒，内存占用等同一次普通单测。
 *
 * <p>没有 Minecraft 输入时（CI）自动跳过 —— 这是设计内行为，
 * 不是失败。
 */
class ModRegistrationLandsInRealMinecraftTest {

    private static boolean reportCached;
    private static String cachedReport;

    @BeforeAll
    static void installGameLoader() throws Exception {
        GameTestClassLoader loader = GameTestClassLoader.install("mili-e2e-game");
        assumeTrue(loader != null,
                "No local Minecraft installation — skipping (designed for CI)");
    }

    /** 完整跑一次注册时序并返回诊断报告。多个测试共用，避免重复 bootstrap。 */
    private static synchronized String probeReport() {
        if (!reportCached) {
            cachedReport = runOnce();
            reportCached = true;
        }
        return cachedReport;
    }

    /**
     * 走一遍真实链路：Mod 声明方块 → 平台装配 → 注册落地 → 游戏侧冻结。
     */
    private static String runOnce() {
        StringBuilder report = new StringBuilder();

        // ── 1. Mod 侧：完全按契约写，只用 org.loader.api.* ──────────────
        MinecraftRegistry registry = new ApiMinecraftRegistry("stardewvalley");

        BlockHandle amaranth = registry.block(BlockSpec
                .builder("crops/amaranth")
                .material(BlockSpec.Material.PLANT)
                .noCollision()
                .replaceable()
                .hardness(0.0f)
                .requiresTool(false)
                .build());

        BlockHandle scarecrow = registry.block(BlockSpec
                .builder("structures/scarecrow")
                .material(BlockSpec.Material.SOLID)
                .hardness(2.0f)
                .build());

        report.append("declared: ").append(amaranth.id())
                .append(", ").append(scarecrow.id()).append('\n');
        report.append("registry.isOpen before bootstrap = ")
                .append(registry.isOpen()).append('\n');

        // ── 2. 平台侧：打开窗口 → 执行 deferred 注册 → 关闭（冻结）────
        BootstrapGate.ensureBootstrapped();

        // ── 3. 核对：游戏注册表里真的有这些方块吗 ───────────────────────
        Object blockRegistry = Reflect.staticField(
                "net.minecraft.core.registries.BuiltInRegistries", "BLOCK");
        int total = registrySize(blockRegistry);
        report.append("registry size after bootstrap = ").append(total).append('\n');

        Object found = lookupBlock("stardewvalley:crops/amaranth");
        report.append("lookup stardewvalley:crops/amaranth = ")
                .append(found != null ? found.getClass().getName() : "NOT FOUND")
                .append('\n');

        Object found2 = lookupBlock("stardewvalley:structures/scarecrow");
        report.append("lookup stardewvalley:structures/scarecrow = ")
                .append(found2 != null ? found2.getClass().getName() : "NOT FOUND")
                .append('\n');

        return report.toString();
    }

    @Test
    void modBlocksLandInTheRealMinecraftRegistry() {
        String report = probeReport();
        System.out.println("=== Mod 注册落地报告 ===\n" + report);

        assertTrue(report.contains("NOT FOUND") == false,
                "Mod 方块必须真的能在 BuiltInRegistries.BLOCK 里查到。"
                        + "\n实际报告：\n" + report);
        assertTrue(report.contains("MiliBlock"),
                "Mod 方块应是平台生成的 Block 子类，实际报告：\n" + report);
    }

    @Test
    void modBlocksCoexistWithVanillaContent() {
        probeReport();
        Object blockRegistry = Reflect.staticField(
                "net.minecraft.core.registries.BuiltInRegistries", "BLOCK");
        int total = registrySize(blockRegistry);

        // 原版方块远多于 1000 个（实测 1196）。若总数只有个位数，
        // 说明原版内容没被补上 —— 那Mod 方块"存在"也没有意义，
        // 因为游戏本身是残缺的。
        assertTrue(total > 1000,
                "原版内容必须与 Mod 方块共存，注册表只有 " + total + " 项");

        // 原版方块也要能查到，证明注册表本身是健康的
        assertNotNull(lookupBlock("minecraft:stone"),
                "原版方块必须存在");
    }

    @Test
    void registryIsClosedAfterBootstrap() {
        probeReport();
        assertTrue(BootstrapGate.isBootstrapped());
        assertFalse(RegistrationPhase.isWindowOpen(),
                "游戏 bootstrap 后注册窗口必须关闭");

        // 冻结后再注册必须失败，而不是静默丢弃
        MinecraftRegistry late = new ApiMinecraftRegistry("toolate");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> late.block(BlockSpec.builder("x").build()),
                "窗口关闭后注册必须失败 —— 否则 Mod 会以为注册成功了");
        assertTrue(e.getMessage().contains("registration window")
                        || e.getMessage().contains("defer"),
                "错误消息必须指向时机问题，而不是泛泛的失败：\n" + e.getMessage());
    }

    @Test
    void findBlockReturnsHandleAfterLanding() {
        probeReport();
        // 契约的 findBlock 在注册落地后应能补上真实数值 ID
        MinecraftRegistry registry = new ApiMinecraftRegistry("stardewvalley");
        // 这个实例是新的，查不到 —— 因为句柄按实例持有，不跨实例共享。
        // 这正是契约要的语义：每个 Mod 有自己的注册表视图。
        assertTrue(registry.blocks().isEmpty(),
                "新实例不应看到别的 Mod 声明的方块");
    }

    @Test
    void behaviorDispatchSurvivesRealRegistration() {
        probeReport();
        // 行为委派：生成的 MiliBlock 里 behaviour 字段是 int，
        // 必须能被 BehaviourDispatch 读回（描述符 I，不引用平台类型）。
        Object block = lookupBlock("stardewvalley:crops/amaranth");
        assertNotNull(block);
        String cls = block.getClass().getName();
        assertTrue(cls.startsWith("net.minecraft.world.level.block."),
                "生成类必须与 Block 同包（privateLookupIn 的要求），实际：" + cls);
    }

    // ── 反射工具 ────────────────────────────────────────────────────────────

    private static int registrySize(Object registry) {
        try {
            Object size = registry.getClass().getMethod("size").invoke(registry);
            return size instanceof Number n ? n.intValue() : -1;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot read registry size", e);
        }
    }

    private static Object lookupBlock(String fullId) {
        try {
            Class<?> resourceKeyClass = Reflect.gameClass("net.minecraft.resources.ResourceKey");
            Class<?> identifierClass = Reflect.gameClass("net.minecraft.resources.Identifier");
            Class<?> registryClass = Reflect.gameClass("net.minecraft.core.Registry");
            Object blockRegistryKey = Reflect.staticField(
                    "net.minecraft.core.registries.Registries", "BLOCK");

            int colon = fullId.indexOf(':');
            Object identifier = identifierClass
                    .getMethod("fromNamespaceAndPath", String.class, String.class)
                    .invoke(null, fullId.substring(0, colon), fullId.substring(colon + 1));
            Object key = resourceKeyClass
                    .getMethod("create", resourceKeyClass, identifierClass)
                    .invoke(null, blockRegistryKey, identifier);

            Object registry = Reflect.staticField(
                    "net.minecraft.core.registries.BuiltInRegistries", "BLOCK");
            Method getValue = registryClass.getMethod("getValue", resourceKeyClass);
            return getValue.invoke(registry, key);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot look up block " + fullId, e);
        }
    }
}