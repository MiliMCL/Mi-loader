package org.loader.runtime.minecraft;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 26.2 注册时序护栏 —— 平台最脆弱的一环。
 *
 * <p>回答的问题只有一个：<b>Mod 方块到底能不能真的进游戏？</b>
 *
 * <p>这条链路上任何一个环节坏掉（版本探测、id 绑定、窗口时序、注册表查找），
 * 症状都是「Mod 加载了但方块不存在」，而不会在编译期或启动早期报错。
 * 因此必须有真实 26.2 在场时才跑得动的端到端断言。
 *
 * <p>没有 Minecraft 构建输入时整类跳过 —— CI 不应该有 MC 依赖。
 */
@DisplayName("26.2 注册时序（真实 Minecraft）")
/**
 * 打上 {@code registry-e2e} 标记：本类直接操作 Minecraft 的方块注册表，
 * 前提是「本 JVM 从未 bootstrap 过注册表」。而 {@code bootStrap()} 是
 * 进程级不可逆的，所以这类测试在共享 JVM 的单元测试套件里必然互相污染。
 *
 * <p>默认构建按 tag 排除；需要时显式开启：
 * <pre>./gradlew :mili-minecraft-integration:test -Pmili.registryTests=true</pre>
 */
@org.junit.jupiter.api.Tag("registry-e2e")
class BootstrapTimingTest {

    private static GameTestClassLoader gameLoader;
    private static Path minecraftDir;

    @BeforeAll
    static void setUp() {
        minecraftDir = GameTestClassLoader.locateMinecraftDir();
        if (minecraftDir == null) {
            return;
        }
        try {
            // 父加载器指向平台 CL —— 见 GameTestClassLoader 类注释：
            // 裸 URLClassLoader(urls, null) 会让绑定层看起来坏掉，
            // 而那正是生产拓扑不会发生的情况。
            gameLoader = GameTestClassLoader.install("mili-timing-game");

            // 重置会话状态：JVM 内别的测试类可能已经走过一轮
            // 「打开窗口 -> 注册 -> 关闭窗口」。GameSessionState 按游戏
            // ClassLoader 隔离，但 GameTestClassLoader 在同一个 JVM 内
            // 会复用同一个 CL 实例，于是状态被带过来 —— 表现是
            // 「窗口已关闭」，与本次测试的意图完全相反。
            RegistrationPhase.resetForTesting();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot install the test game classloader", e);
        }
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

    @Test
    @org.junit.jupiter.api.condition.EnabledIf("mcAvailable")
    @DisplayName("Mod 方块与原版内容在同一注册表中共存")
    void modBlockCoexistsWithVanillaContent() {
        String report = probeReport();
        System.out.println("===== TIMING PROBE =====");
        System.out.println(report);
        System.out.println("===== END =====");

        // 不用"整段输出对不对"来断言，而是逐条抓关键结论 ——
        // 输出格式调整不该让测试变红，反之亦然。
        assertTrue(report.contains("Mod 方块注册成功"),
                () -> "Mod 方块没能注册：\n" + report);
        assertTrue(!report.contains("方案不可行"),
                () -> "注册后查不回 Mod 方块，说明它没真正进入游戏：\n" + report);
        assertTrue(report.contains("实例同一性: 通过"),
                () -> "查回的实例与注册的不是同一个：\n" + report);
        assertTrue(report.contains("冻结后新建方块被拒"),
                () -> "冻结后仍能新建方块 —— 意味着前面的'成功'没有真正验证窗口：\n" + report);

        // 条目数从 0（只有 Mod 的）涨到 1197（原版 + Mod）是这条路可行的核心证据。
        assertTrue(report.contains("条目数 = 0"),
                () -> "打开窗口后注册表本应为空（Mod 的空隙）：\n" + report);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIf("mcAvailable")
    @DisplayName("窗口关闭后再注册会被明确拒绝，而不是静默失败")
    void registrationAfterFreezeIsRejected() {
        // 依赖 probeReport() 跑完完整启动（窗口已关闭）。
        // 显式调用而非依赖"另一个测试先跑过" —— JUnit 不保证测试顺序，
        // 隐式顺序依赖会让本测试在单独运行时以"什么都没抛"的方式假绿。
        probeReport();

        // 此时再注册必须报出可理解的错误，而不是让 Mod 作者面对
        // "This registry can't create intrusive holders"。
        org.loader.runtime.minecraft.block.BlockRegistrar late =
                new org.loader.runtime.minecraft.block.BlockRegistrar("mili_late");
        RuntimeException failure = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> late.register(
                        org.loader.api.registry.BlockSpec.builder("probe/too_late").build()),
                "窗口关闭后注册必须失败 —— 若成功说明冻结根本没生效");
        assertTrue(failure.getMessage().contains("registration window")
                        || failure.getMessage().contains("frozen")
                        || failure.getMessage().contains("freeze"),
                () -> "错误消息应指向「注册窗口已关闭」这一根因，实际：\n"
                        + failure.getMessage());
    }

    /**
     * 探针报告，只跑一次。
     *
     * <p>Minecraft 在一个 JVM 内只能 bootstrap 一次，而 {@code run()} 会真正
     * 完成整条启动序列（含冻结注册表）。两个测试都需要"完整启动之后"这个状态，
     * 因此这里缓存结果而非让两个测试各跑一次 ——
     * 第二次运行只会得到一份"窗口已关闭"的残缺报告。
     */
    private static synchronized String probeReport() {
        if (cachedReport == null) {
            cachedReport = BootstrapTimingProbe.run();
        }
        // 环境前提检查：注册窗口的整个前提是「这个游戏 ClassLoader 从未
        // bootstrap 过」。同一个 JVM 里只要有任何一处先跑完了
        // BuiltInRegistries.bootStrap()，注册表就永久冻结 —— 这不是
        // 被测代码的缺陷，而是测试前提不成立。
        //
        // 用 assumption 跳过而不是 assertTrue 失败：让「前提缺失」和
        // 「实现错误」在报告里能分辨。真要跑这条断言，必须让这个测试类
        // 独占一个 JVM（Gradle 的 forkEvery 或 --tests 单跑）。
        if (cachedReport.contains("window is already closed")) {
            org.junit.jupiter.api.Assumptions.abort(
                    "注册表已在本次 JVM 中被 bootstrap 过 —— 该断言要求全新的游戏 ClassLoader。"
                            + "\n  单独运行本测试类可满足前提："
                            + "gradlew :mili-minecraft-integration:test --tests '*BootstrapTimingTest*'");
        }
        return cachedReport;
    }

    private static String cachedReport;

    @Test
    @DisplayName("没有 Minecraft 构建输入时整类跳过而不是失败")
    void absenceIsNotFailure() {
        if (minecraftDir == null) {
            assertTrue(true, "没有 Minecraft 构建输入时跳过是正确行为");
        } else {
            assertTrue(Files.exists(minecraftDir.resolve("26.2.jar")));
        }
    }
}
