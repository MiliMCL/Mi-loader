package org.loader.runtime.minecraft;

import java.util.logging.Logger;

/**
 * Minecraft 版本探测闸门 —— 26.2 注册流程的<b>第一步</b>。
 *
 * <p>与 {@link BootstrapGate} 分开，是因为两者对应的注册阶段不同：
 * <ul>
 *   <li>版本探测：注册<b>之前</b>必须完成。缺它则 {@code DataFixers} 抛
 *       {@code IllegalStateException: Game version not set}；</li>
 *   <li>游戏 bootstrap：注册<b>之后</b>才允许执行。它会冻结注册表。</li>
 * </ul>
 *
 * <p>把两者混成一个 {@code ensureBootstrapped()} 是过去绑定层的结构性错误：
 * 一旦调用它，注册窗口就永久关闭，所有 Mod 方块都再也造不出来。
 * 正确顺序是
 * {@code SharedVersionGate.ensureVersionDetected()} → 注册 →
 * {@code BootstrapGate.ensureBootstrapped()}。
 */
public final class SharedVersionGate {

    private static final Logger LOG = Logger.getLogger("Mili/Bootstrap");

    private SharedVersionGate() {
    }

    /**
     * 幂等地执行 {@code SharedConstants.tryDetectVersion()}。
     *
     * <p>必须在创建任何 {@code BlockBehaviour.Properties} 之前调用。
     *
     * @throws IllegalStateException 探测失败，附原始原因
     */
    public static void ensureVersionDetected() {
        // 状态按游戏 ClassLoader 隔离 —— 详见 GameSessionState 的类注释。
        GameSessionState state = GameSessionState.current();
        if (state.versionDetected.get()) {
            return;
        }
        synchronized (state) {
            if (state.versionDetected.get()) {
                return;
            }
            try {
                Class<?> shared = Class.forName("net.minecraft.SharedConstants",
                        true,
                        org.loader.runtime.minecraft.reflect.Reflect.gameClassLoader());
                shared.getMethod("tryDetectVersion").invoke(null);
                state.versionDetected.set(true);
            } catch (Exception e) {
                Throwable real = e instanceof java.lang.reflect.InvocationTargetException it
                        && it.getCause() != null ? it.getCause() : e;
                throw new IllegalStateException(
                        "SharedConstants.tryDetectVersion() failed. Every"
                                + " BlockBehaviour.Properties needs the game version to be"
                                + " set beforehand.", real);
            }
        }
    }

    /** 版本是否已探测。诊断用。 */
    public static boolean isVersionDetected() {
        return GameSessionState.current().versionDetected.get();
    }

    /** 仅供测试重置。 */
    static void resetForTesting() {
        GameSessionState.current().versionDetected.set(false);
    }
}
