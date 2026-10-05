package org.loader.runtime.minecraft;

import org.loader.api.registry.BlockSpec;
import org.loader.runtime.minecraft.block.BlockRegistrar;
import org.loader.runtime.minecraft.reflect.Reflect;

/**
 * 26.2 注册时序的端到端验证（诊断输出 + CI 护栏）。
 *
 * <p>它验证的是整个平台最脆弱的一环：<b>Mod 方块能否真的进游戏</b>。
 *
 * <p>26.2 的约束（全部由反编译 {@code 26.2.jar} 确认）：
 * <ul>
 *   <li>{@code BuiltInRegistries.<clinit>} 要求 {@code isBootstrapped == true}；</li>
 *   <li>{@code Block.<init>} 要求注册表尚未 {@code freeze()}；</li>
 *   <li>{@code Bootstrap.bootStrap()} 开头 {@code if (isBootstrapped) return;}。</li>
 * </ul>
 * 三者叠加，使得"先 bootstrap 再注册"必然失败。已验证可行的时序见
 * {@link RegistrationPhase} 类注释。
 *
 * <p>本类走的是<b>生产 API</b>（{@link RegistrationPhase} /
 * {@link BootstrapGate}），不是另写一套流程 —— 否则测的就不是真实路径。
 */
public final class BootstrapTimingProbe {

    private BootstrapTimingProbe() {
    }

    /**
     * 执行一次完整注册并返回逐行诊断。
     *
     * <p>副作用：会真正 bootstrap 一次 Minecraft，因此同一 JVM 内只能调一次。
     */
    public static String run() {
        StringBuilder sb = new StringBuilder();

        // ── 1. 版本探测 ──────────────────────────────────────────────
        try {
            SharedVersionGate.ensureVersionDetected();
            sb.append("1. 版本已探测: ")
                    .append(BootstrapGate.currentGameVersion()).append('\n');
        } catch (RuntimeException e) {
            sb.append("1. 版本探测失败: ").append(rootCause(e)).append('\n');
            return sb.toString();
        }

        // ── 2. 打开注册窗口 ──────────────────────────────────────────
        try {
            RegistrationPhase.openRegistryWindow();
            sb.append("2. 注册窗口已打开\n");
        } catch (RuntimeException e) {
            sb.append("2. 打开窗口失败: ").append(rootCause(e)).append('\n');
            return sb.toString();
        }

        Object blockRegistry = Reflect.staticField(
                "net.minecraft.core.registries.BuiltInRegistries", "BLOCK");
        int sizeBefore = readSize(blockRegistry);
        sb.append("3. 原版内容尚未 createContents，条目数 = ").append(sizeBefore)
                .append("（Mod 的空隙）\n");

        // ── 4. Mod 注册 ──────────────────────────────────────────────
        BlockRegistrar registrar = new BlockRegistrar("mili_timing");
        Object modBlock;
        try {
            modBlock = registrar.register(
                    BlockSpec.builder("probe/timing_block")
                            .material(BlockSpec.Material.SOLID)
                            .hardness(3.0f)
                            .build());
            sb.append("4. Mod 方块注册成功: ")
                    .append(modBlock.getClass().getName()).append('\n');
        } catch (RuntimeException e) {
            sb.append("5. Mod 方块注册失败: ").append(rootCause(e)).append('\n');
            return sb.append(describeChain(e)).toString();
        }

        int sizeAfterMod = readSize(blockRegistry);
        sb.append("5. 注册后条目数 = ").append(sizeAfterMod)
                .append("（应比第 3 步多 1）\n");

        // ── 6. 关闭窗口：补原版 + 冻结 ───────────────────────────────
        try {
            BootstrapGate.ensureBootstrapped();
            sb.append("6. 游戏启动完成（补原版内容 + freeze）\n");
        } catch (RuntimeException e) {
            sb.append("6. 游戏启动失败: ").append(rootCause(e)).append('\n');
            return sb.append(describeChain(e)).toString();
        }

        int sizeAfterBootstrap = readSize(blockRegistry);
        sb.append("7. 启动后条目数 = ").append(sizeAfterBootstrap)
                .append("（原版 + Mod，应远大于 1）\n");

        // ── 7. 关键校验：Mod 方块活下来了吗 ──────────────────────────
        Object looked = registrar.lookup("probe/timing_block");
        sb.append("8. 按 id 查回 Mod 方块: ")
                .append(looked == null ? "查不到 ⇒ 方案不可行" : "成功")
                .append('\n');
        sb.append("9. 实例同一性: ")
                .append(looked == modBlock ? "通过" : "不通过")
                .append('\n');

        // ── 8. 对照：冻结后确实造不出新方块（证明上面的窗口是真的）───
        try {
            Object fresh = GeneratedBlockProbe.newBlock("mili_timing", "probe/after_freeze");
            sb.append("10. 冻结后新建方块: ").append(fresh == null
                    ? "被拒（预期）" : "竟然成功 ⇒ 窗口从未真正关闭").append('\n');
        } catch (RuntimeException e) {
            sb.append("10. 冻结后新建方块被拒（预期）: ")
                    .append(rootCause(e)).append('\n');
        }

        return sb.toString();
    }

    /** 注册表条目数。{@code Registry.size()} 返回 int，不是 long。 */
    private static int readSize(Object registry) {
        try {
            return (Integer) registry.getClass().getMethod("size").invoke(registry);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return -1;
        }
    }

    private static String describeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (Throwable c = t; c != null && depth < 8; c = c.getCause()) {
            sb.append("   caused by: ").append(c.getClass().getName());
            if (c.getMessage() != null) {
                sb.append(": ").append(c.getMessage());
            }
            sb.append('\n');
            if (c.getCause() == c) {
                break;
            }
            depth++;
        }
        return sb.toString();
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + ": " + c.getMessage();
    }
}
