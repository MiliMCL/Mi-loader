package com.example.testmod;

import org.loader.api.Mod;
import org.loader.api.ModContext;
import org.loader.api.registry.BlockSpec;
import org.loader.api.registry.MinecraftRegistry;
import org.loader.api.world.BlockHandle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试用的「真实 Mod」。
 *
 * <p><b>刻意放在中立包 {@code com.example.testmod} 里</b>，而不是
 * {@code org.loader.*} 下—— {@link ClassVisibility} 把
 * {@code org.loader.loader.**} 与 {@code org.loader.runtime.**} 列为
 * Mod <b>不可见</b>的包。测试 Mod 若待在那些包里，会被平台的隔离规则
 * 直接拒绝加载（这是正确行为）。
 *
 * <p>换句话说，把测试 Mod 放在中立包，同时验证了两件事：
 * 入口能被找到并按契约调用；平台的包隔离规则确实生效。
 */
public class ContractMod implements Mod {

    /**
     * 本Mod 记录下的调用轨迹。
     *
     * <p><b>重要</b>：本类由 {@code ModClassLoader} 加载，因此这份 static
     * 与测试所在 ClassLoader 里的同名字段是<b>两个不同的字段</b>，
     * 互相看不见。测试<b>不能</b>靠它判断 initialize 有没有被调用 ——
     * 那样只会读到空列表，并误判成「没调用」。
     *
     * <p>要验证 initialize 跑过，测试必须读双方共享的对象：
     * 平台侧注册表里有没有 Mod 写进去的方块。
     * 这个字段仅供调试与人工排查。
     */
    public static final List<String> CALLS =
            Collections.synchronizedList(new ArrayList<>());

    /** Mod 自己声明的方块句柄（同样是 Mod CL 内的副本，测试读不到）。 */
    public static volatile BlockHandle registered;

    public static void reset() {
        CALLS.clear();
        registered = null;
    }

    @Override
    public void initialize(ModContext context) {
        CALLS.add("initialize:" + context.modId());

        // 走一遍契约里每一条 Mod 真会碰的能力，
        // 确认适配层没有一条是断的。
        CALLS.add("metadata:" + context.metadata().id());
        CALLS.add("lifecycle:" + context.lifecycle().state());
        CALLS.add("environment:" + context.environment());
        CALLS.add("active:" + context.isActive());
        context.logger().info("[ContractMod] initialized");

        MinecraftRegistry registry = context.registry();
        if (registry == null) {
            CALLS.add("block:registry-null");
            return;
        }
        registered = registry.block(BlockSpec
                .builder("crops/amaranth")
                .material(BlockSpec.Material.PLANT)
                .noCollision()
                .replaceable()
                .hardness(0.0f)
                .requiresTool(false)
                .build());
        CALLS.add("block:" + registered.id());
    }

    /** 入口类里不实现 Mod 的反例。 */
    public static class NotAMod {
        public void initialize(ModContext context) {
            CALLS.add("SHOULD-NOT-BE-CALLED");
        }
    }
}