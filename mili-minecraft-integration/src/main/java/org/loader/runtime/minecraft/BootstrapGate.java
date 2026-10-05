package org.loader.runtime.minecraft;

import java.util.logging.Logger;

/**
 * Minecraft 启动编排器 —— 把已验证的 26.2 分阶段启动序列串起来。
 *
 * <p><b>为什么不再直接调 {@code Bootstrap.bootStrap()}</b>：26.2 里方块注册表
 * 在 bootstrap 内部被冻结，而 {@code Block} 构造器硬性依赖未冻结的注册表。
 * 调完 {@code bootStrap()} 再注册，一个方块都加不进去。完整推导与实测数据见
 * {@link RegistrationPhase} 的类注释。
 *
 * <p>正确顺序：
 * <pre>
 *   1. 打开注册窗口（置位 isBootstrapped + 触发 BuiltInRegistries 类初始化）
 *   2. 跑所有已登记的 Mod 注册       ← 唯一能新建方块的时机
 *   3. 关闭注册窗口（补原版内容 + freeze）
 * </pre>
 */
public final class BootstrapGate {

    private static final Logger LOG = Logger.getLogger("Mili/Bootstrap");

    private BootstrapGate() {
    }

    /**
     * 完成 Minecraft 的全部启动流程，包括执行已登记的 Mod 注册。
     * 可重复调用（第二次是空操作）。
     *
     * <p>Mod 不应直接调用本方法 —— 它们用
     * {@link RegistrationPhase#defer(Runnable)} 登记注册动作，由平台在正确时机执行。
     *
     * @throws IllegalStateException 若启动失败，附带原始原因
     */
    public static void ensureBootstrapped() {
        // 状态与锁都按游戏实例隔离 —— 同一 JVM 内跑多个游戏实例时互不干扰，
        // 且不会因为一个实例 bootstrap 慢而卡住另一个。
        GameSessionState state = GameSessionState.current();
        if (state.bootstrapDone.get()) {
            if (state.bootstrapFailure.get() == null) {
                return;
            }
            throw new IllegalStateException(
                    "Minecraft bootstrap previously failed", state.bootstrapFailure.get());
        }
        synchronized (state.bootstrapLock()) {
            if (state.bootstrapDone.get()) {
                return;
            }
            long start = System.currentTimeMillis();
            try {
                RegistrationPhase.openRegistryWindow();
                // Mod 注册夹在"窗口开"与"窗口关"之间 —— 这是 26.2 唯一的机会。
                RegistrationPhase.runRegistrations();
                RegistrationPhase.closeRegistryWindow();
                state.bootstrapDone.set(true);
                LOG.info("Minecraft bootstrap OK in " + (System.currentTimeMillis() - start) + "ms");
            } catch (Throwable t) {
                state.bootstrapFailure.set(t);
                throw new IllegalStateException(
                        "Minecraft bootstrap failed. The binding layer cannot create"
                                + " blocks or items until the game is initialised.", t);
            }
        }
    }

    /**
     * 只完成 Mod 注册，不关闭窗口。
     *
     * <p>给需要分多步注册（先注册方块、再注册物品）的调用方用。调用方必须
     * 最终自行调用 {@link #ensureBootstrapped()} 或 {@link RegistrationPhase#closeRegistryWindow()}。
     */
    public static void openForRegistration() {
        RegistrationPhase.openRegistryWindow();
        RegistrationPhase.runRegistrations();
    }

    /** 是否已完成初始化。 */
    public static boolean isBootstrapped() {
        GameSessionState state = GameSessionState.current();
        return state.bootstrapDone.get() && state.bootstrapFailure.get() == null;
    }

    /**
     * 读取 Minecraft 当前版本字符串，如 {@code "26.2"}。
     *
     * <p><b>访问器在版本间换过名字</b>（实测 26.2）：{@code WorldVersion} 是
     * record 风格，方法叫 {@code name()} / {@code id()}，不再是
     * {@code getName()}。只调 {@code getName()} 会得到
     * {@code NoSuchMethodException} —— 而这里以前把异常静默成
     * {@code "unknown"}，于是所有绑定层错误消息里的版本号全是
     * {@code unknown}，等于没有版本信息。两种命名都试。
     *
     * <p>只需 {@link SharedVersionGate#ensureVersionDetected()} 之后即可调用。
     */
    public static String currentGameVersion() {
        try {
            Class<?> shared = Class.forName("net.minecraft.SharedConstants",
                    true, org.loader.runtime.minecraft.reflect.Reflect.gameClassLoader());
            Object version = shared.getMethod("getCurrentVersion").invoke(null);
            if (version == null) {
                return "unknown (SharedConstants.getCurrentVersion() returned null —"
                        + " version not detected yet)";
            }
            String name = readVersionName(version);
            return name != null ? name
                    : "unknown (WorldVersion exposes neither name() nor getName())";
        } catch (ReflectiveOperationException e) {
            // 不再静默：调用方（异常消息、测试）需要知道探测为何失败。
            LOG.log(java.util.logging.Level.WARNING,
                    "Cannot read Minecraft version from SharedConstants", e);
            return "unknown (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
        }
    }

    /** 26.2 是 name()，旧版本是 getName()。两个都试。 */
    private static String readVersionName(Object version) {
        for (String method : new String[]{"name", "getName"}) {
            try {
                Object name = version.getClass().getMethod(method).invoke(version);
                if (name != null) {
                    return name.toString();
                }
            } catch (ReflectiveOperationException ignored) {
                // 试下一个命名
            }
        }
        return null;
    }

    /** 仅供测试重置状态。 */
    static void resetForTesting() {
        GameSessionState.resetAll();
    }
}
