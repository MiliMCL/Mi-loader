package org.loader.runtime.minecraft.world;

import org.loader.api.world.WorldView;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「当前世界」的登记处 —— 平台侧唯一知道游戏 {@code Level} 的地方。
 *
 * <h2>为什么需要它</h2>
 * <p>契约里 {@code ModContext.world()} 是一个<b>无参</b>方法：Mod 调用时
 * 不提供任何上下文，平台必须自己知道「现在是哪个世界」。
 * 而平台没有别的地方持有它 ——
 * {@code MinecraftLifecycle} 管生命周期、{@code TickBridge} 管 tick 计数，
 * 两者都不持有 {@code Level} 引用（Minecraft 的世界里对象来自游戏自身，
 * 平台只能"被告知"）。
 *
 * <p>所以必须有一条登记通道。有两个自然的登记时机，都已实现：
 * <ul>
 *   <li><b>方块回调</b>：任何一次 {@code tick} / {@code randomTick} /
 *       {@code neighborChanged} 都自带 {@code Level} 参数，
 *       {@link org.loader.runtime.minecraft.block.BehaviourDispatch}
 *       在派发前顺手登记 —— 这是最可靠的一条，游戏自己会送来；</li>
 *   <li><b>显式登记</b>：注入层在进入世界时调用 {@link #publish}。</li>
 * </ul>
 *
 * <h2>按 ClassLoader 隔离</h2>
 * <p>与 {@code GameSessionState} 同一个理由：被描述的事实
 * （"哪个 Level 是当前的"）是游戏 ClassLoader 的状态。同一个 JVM 里若有两个
 * 游戏实例，全局单值会让 A 实例的 Mod 读到 B 实例的世界 ——
 * <b>症状是 Mod 改到了另一个世界里，且完全不报错</b>。
 * 因此按 {@link Reflect#gameClassLoader()} 分桶。
 *
 * <h2>过期处理</h2>
 * <p>世界卸载（玩家退出单人）后 Level 不再被回调到，但登记值还在。
 * 持有过期 Level 会让 Mod 读到「一个还在但没人 tick 的世界」。
 * 因此每次读都校验 {@code isLoaded} / {@code isClientSide} 可达，
 * 并提供 {@link #forget} 供退出流程显式清理。
 */
public final class CurrentWorld {

    private static final Map<ClassLoader, Object> WORLDS = new ConcurrentHashMap<>();

    private CurrentWorld() {
    }

    /**
     * 登记某个游戏世界为「当前世界」。
     *
     * @param level 游戏 {@code Level}；传 null 表示世界已卸载
     */
    public static void publish(Object level) {
        ClassLoader key = Reflect.gameClassLoader();
        if (level == null) {
            WORLDS.remove(key);
        } else {
            WORLDS.put(key, level);
        }
    }

    /**
     * 取当前世界视图。
     *
     * @return 世界视图；世界未加载时返回 {@code null}
     *         —— 契约明确允许此时为 null，Mod 必须判空
     */
    public static WorldView current() {
        Object level = WORLDS.get(Reflect.gameClassLoader());
        if (level == null) {
            return null;
        }
        // 兜底：Level 实例可能已被游戏丢弃但登记值还在（例如换维度时的过渡态）。
        // 这种情况返回一个坏掉的 WorldView 会让 Mod 在写入时才炸，
        // 判空更符合契约语义。
        try {
            Object minY = level.getClass()
                    .getMethod("getMinY").invoke(level);
            return minY instanceof Integer
                    ? ReflectiveWorldView.of(level)
                    : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** 世界卸载时清掉登记。 */
    public static void forget() {
        WORLDS.remove(Reflect.gameClassLoader());
    }

    /** 仅供测试：清空全部登记。 */
    public static void resetAll() {
        WORLDS.clear();
    }
}