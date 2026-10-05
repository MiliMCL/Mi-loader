package org.loader.loader;

import org.loader.runtime.minecraft.ApiMinecraftRegistry;
import org.loader.runtime.mod.Mod;
import org.loader.runtime.mod.ModContext;

/**
 * 把 Minecraft 侧的注册表实现装进 runtime 的 {@link ModContext}。
 *
 * <h2>为什么需要一个单独的桥</h2>
 *
 * <p>依赖方向是单向的：{@code loader → integration → runtime}。
 * {@code ModContext} 住在 runtime 里，它<b>不能</b>直接
 * {@code new ApiMinecraftRegistry(...)} —— 那是 integration 的类，
 * 反向引用会形成循环依赖。
 *
 * <p>于是 runtime 的 {@link ModContext} 留了 {@code bindApiRegistry} 挂载点
 * （见该方法注释），由这个类在 Mod 加载时负责装填。装填发生在
 * {@code initialize()} <b>之前</b> —— 顺序反了的话，Mod 第一行调
 * {@code context.registry()} 就会拿到 null，而且是在 Mod 自己的代码里，
 * 报错现场完全指不到平台。
 */
final class RegistryBinder {

    private RegistryBinder() {
    }

    /**
     * 为单个 Mod 装入注册表与 ModContext。
     *
     * @param runtimeCtx 该 Mod 的 runtime 上下文
     * @param mod        该 Mod 的清单（提供命名空间）
     */
    static void bind(ModContext runtimeCtx, Mod mod) {
        String modId = mod.id();
        runtimeCtx.bindApiRegistry(new ApiMinecraftRegistry(modId));
    }
}