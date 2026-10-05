package org.loader.runtime.minecraft;

import org.loader.api.registry.BlockSpec;
import org.loader.runtime.minecraft.block.BlockRegistrar;

/**
 * 最小化的方块构造入口，供时序探针在任意时刻尝试造方块。
 *
 * <p>存在的意义：验证「注册窗口是否开着」不能靠读私有字段
 * （{@code MappedRegistry.frozen} 是实现细节，版本一变就失效），
 * 只能靠<b>行为</b>判断 —— 真的去造一个方块，造得出来就说明窗口开着。
 *
 * <p><b>必须走注册路径</b>：26.2 的 {@code freeze()} 会拒绝任何
 * 「造了没注册」的方块（{@code unregisteredIntrusiveHolders} 非空即抛）。
 * 若这里只造不注册，一次探针就会让之后的游戏启动永久失败 ——
 * 探针本该是<b>观测</b>工具，不能改变被观测系统的可启动性。
 *
 * <p>反过来说，这恰好让「窗口是否开着」的判定更严格：窗口关闭后，
 * {@code register} 会先在 {@link RegistrationPhase#openRegistryWindow()}
 * 处被明确拒绝，报出可理解的错误，而不是深入反射层撞上
 * {@code This registry can't create intrusive holders}。
 */
public final class GeneratedBlockProbe {

    private GeneratedBlockProbe() {
    }

    /**
     * 造一个方块并尝试注册。
     *
     * @param namespace 命名空间
     * @param path     注册路径
     * @return Block 实例
     * @throws RuntimeException 窗口已关闭时（这是要观察的信号，不该吞掉）
     */
    public static Object newBlock(String namespace, String path) {
        SharedVersionGate.ensureVersionDetected();
        return new BlockRegistrar(namespace).register(
                BlockSpec.builder(path).material(BlockSpec.Material.SOLID).build());
    }
}