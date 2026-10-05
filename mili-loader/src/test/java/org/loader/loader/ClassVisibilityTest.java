package org.loader.loader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.loader.classloader.ClassVisibility;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 类可见性契约测试。
 *
 * <p>这是 Phase 2 的核心断言：Mod 能看到什么、看不到什么，由
 * {@link ClassVisibility} 单点定义。此测试不依赖真实 Minecraft。
 */
@DisplayName("ClassVisibility 契约")
class ClassVisibilityTest {

    // ── Mod 必须可见 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Mod 可以访问 Mili ABI")
    void modCanAccessAbi() {
        assertTrue(ClassVisibility.isVisible("org.loader.api.Mod"));
        assertTrue(ClassVisibility.isVisible("org.loader.api.ModMetadata"));
        assertTrue(ClassVisibility.isVisible("org.loader.api.world.WorldView"));
        assertFalse(ClassVisibility.isHidden("org.loader.api.Mod"));
    }

    @Test
    @DisplayName("Mod 可以访问 Minecraft 本体")
    void modCanAccessMinecraft() {
        assertTrue(ClassVisibility.isVisible("net.minecraft.client.Minecraft"));
        assertTrue(ClassVisibility.isVisible("net.minecraft.server.level.ServerLevel"));
        assertTrue(ClassVisibility.isVisible("net.minecraft.world.level.block.Block"));
        assertFalse(ClassVisibility.isHidden("net.minecraft.world.entity.Entity"));
    }

    @Test
    @DisplayName("Mod 可以访问公开的 Runtime API 包")
    void modCanAccessPublicRuntimePackages() {
        assertTrue(ClassVisibility.isVisible("org.loader.runtime.tick.TickContract"));
        assertTrue(ClassVisibility.isVisible("org.loader.runtime.mod.Mod"));
        assertTrue(ClassVisibility.isVisible("org.loader.runtime.service.EventBus"));
        // 包根下没有可匹配的前缀，靠显式 SPI 名单放行。
        // 它是 ModContext.environment() 的返回类型，Mod 必须能解析。
        assertTrue(ClassVisibility.isVisible("org.loader.runtime.RuntimeEnvironment"));
        assertEquals(ClassVisibility.Resolution.PARENT_FIRST,
                ClassVisibility.resolve("org.loader.runtime.RuntimeEnvironment"),
                "RuntimeEnvironment 必须 parent-first —— 落进 SELF_FIRST 会先在 Mod "
                        + "自己的 classpath 上失败一轮再回退，且 jar 里若恰好有同名类就会分裂成两份");
    }

    @Test
    @DisplayName("Mod 不能访问平台与 Minecraft 的绑定实现")
    void modCannotAccessMinecraftBinding() {
        // org.loader.runtime.minecraft 是反射 / 字节码生成 / 类查找的所在地，
        // 不是给 Mod 编程的接口。Mod 该用的是 ABI 里的 WorldView / BlockHandle。
        // 曾经这个包在白名单里 —— 那是真实漏洞：Mod 可以直接调 BehaviourDispatch
        // 派发行为、注册生成方块，绕开 ModContext 的全部生命周期与合规检查。
        assertTrue(ClassVisibility.isHidden("org.loader.runtime.minecraft.block.BehaviourDispatch"));
        assertTrue(ClassVisibility.isHidden("org.loader.runtime.minecraft.world.ReflectiveWorldView"));
        assertTrue(ClassVisibility.isHidden("org.loader.runtime.minecraft.MinecraftBootstrap"));
        assertFalse(ClassVisibility.isVisible("org.loader.runtime.minecraft.block.BehaviourDispatch"));
        assertEquals(ClassVisibility.Resolution.FORBIDDEN,
                ClassVisibility.resolve("org.loader.runtime.minecraft.block.BehaviourDispatch"));
    }

    // ── Mod 必须不可见 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Mod 不能访问 Loader 实现内部")
    void modCannotAccessLoaderInternals() {
        assertTrue(ClassVisibility.isHidden("org.loader.loader.LoaderMain"));
        assertTrue(ClassVisibility.isHidden("org.loader.loader.classloader.ModClassLoader"));
        assertFalse(ClassVisibility.isVisible("org.loader.loader.loader.LoaderMain"));
    }

    @Test
    @DisplayName("Mod 不能访问 Runtime 内核实现")
    void modCannotAccessRuntimeKernel() {
        assertTrue(ClassVisibility.isHidden("org.loader.runtime.kernel.Runtime"));
        assertTrue(ClassVisibility.isHidden("org.loader.runtime.kernel.ScopeRegistry"));
        assertFalse(ClassVisibility.isVisible("org.loader.runtime.kernel.Runtime"));
    }

    @Test
    @DisplayName("Mod 可以实现 kernel 的 SPI（Resource / Scope / ScopeListener / LifecycleState）")
    void kernelSpiIsVisible() {
        // Mod 要注册自己的资源就必须 implements Resource；把它藏起来等于
        // 让 Mod 无法使用平台。实测踩过：隐藏后加载 Mod 立刻抛
        // NoClassDefFoundError: org/loader/runtime/kernel/Resource。
        assertFalse(ClassVisibility.isHidden("org.loader.runtime.kernel.Resource"));
        assertFalse(ClassVisibility.isHidden("org.loader.runtime.kernel.Scope"));
        assertFalse(ClassVisibility.isHidden("org.loader.runtime.kernel.ScopeListener"));
        assertFalse(ClassVisibility.isHidden("org.loader.runtime.kernel.LifecycleState"));
        assertEquals(ClassVisibility.Resolution.PARENT_FIRST,
                ClassVisibility.resolve("org.loader.runtime.kernel.Resource"));
        assertEquals(ClassVisibility.Resolution.PARENT_FIRST,
                ClassVisibility.resolve("org.loader.runtime.kernel.Scope"));
        assertEquals(ClassVisibility.Resolution.PARENT_FIRST,
                ClassVisibility.resolve("org.loader.runtime.kernel.ScopeListener"));
        // 嵌套类型（枚举 switch map、接口内类型等）也要放行
        assertFalse(ClassVisibility.isHidden("org.loader.runtime.kernel.LifecycleState$1"));
    }

    @Test
    @DisplayName("Scope 必须可见：它出现在 ModContext 的公开签名里")
    void scopeIsVisibleBecauseItLeaksIntoModFacingSignatures() {
        // 这是本白名单最容易被「优化」掉的一个条目。Scope 是具体类，
        // 看着像实现细节，但 ModContext.scope() 返回它、
        // Resource.owner() 返回它、ScopeListener 回调第一个参数也是它。
        // 隐藏它 ⇒ Mod 调 ctx.scope().id() 就 NoClassDefFoundError。
        assertEquals(ClassVisibility.Resolution.PARENT_FIRST,
                ClassVisibility.resolve("org.loader.runtime.kernel.Scope"));
    }

    @Test
    @DisplayName("kernel 的纯实现类仍然隐藏（Runtime / Registry 等）")
    void kernelImplementationsStayHidden() {
        // 开放 SPI ≠ 开放整包。这些实现类不出现在任何 Mod 需要解析的签名里。
        assertTrue(ClassVisibility.isHidden("org.loader.runtime.kernel.Runtime"));
        assertTrue(ClassVisibility.isHidden("org.loader.runtime.kernel.ScopeRegistry"));
        assertTrue(ClassVisibility.isHidden("org.loader.runtime.kernel.ResourceRegistry"));
        assertTrue(ClassVisibility.isHidden("org.loader.runtime.kernel.LifecycleManager"));
        assertTrue(ClassVisibility.isHidden("org.loader.runtime.kernel.CapabilityManager"));
        assertTrue(ClassVisibility.isHidden("org.loader.runtime.kernel.ScopeShutdownException"));
        assertEquals(ClassVisibility.Resolution.FORBIDDEN,
                ClassVisibility.resolve("org.loader.runtime.kernel.Runtime"));
    }

    @Test
    @DisplayName("Mod 不能访问 Installer 内部")
    void modCannotAccessInstaller() {
        assertTrue(ClassVisibility.isHidden("org.loader.installer.InstallerMain"));
        assertTrue(ClassVisibility.isHidden("org.loader.installer.install.AssetInstaller"));
    }

    @Test
    @DisplayName("Mod 不能访问 JDK 内部与非导出 API")
    void modCannotAccessJdkInternals() {
        assertTrue(ClassVisibility.isHidden("sun.misc.Unsafe"));
        assertTrue(ClassVisibility.isHidden("jdk.internal.misc.Unsafe"));
        assertTrue(ClassVisibility.isHidden("com.sun.crypto.provider.SunJCE"));
        // 注意 java.lang.invoke.MethodHandle 不在上列：java.* 一律委派而非拒绝。
        // 把 java.* 判为 FORBIDDEN 会让每个 Mod 类在 defineClass 时抛
        // NoClassDefFoundError: java/lang/Object —— 平台无法加载任何 Mod。
        assertFalse(ClassVisibility.isHidden("java.lang.invoke.MethodHandle"));
        assertFalse(ClassVisibility.isHidden("java.lang.String"));
    }

    @Test
    @DisplayName("Mod 不能访问 Mojang / 其他加载器内部")
    void modCannotAccessForeignInternals() {
        assertTrue(ClassVisibility.isHidden("com.mojang.blaze3d.platform.GlStateManager"));
        assertTrue(ClassVisibility.isHidden("net.minecraftforge.common.ForgeMod"));
        assertTrue(ClassVisibility.isHidden("org.spongepowered.asm.launch.Launch"));
        assertTrue(ClassVisibility.isHidden("org.bouncycastle.jce.provider.BouncyCastleProvider"));
    }

    // ── 解析策略 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resolve: Loader/Runtime 内部 → FORBIDDEN")
    void resolveForbidden() {
        assertEquals(ClassVisibility.Resolution.FORBIDDEN,
                ClassVisibility.resolve("org.loader.loader.LoaderMain"));
        assertEquals(ClassVisibility.Resolution.FORBIDDEN,
                ClassVisibility.resolve("org.loader.runtime.kernel.ScopeRegistry"));
        assertEquals(ClassVisibility.Resolution.FORBIDDEN,
                ClassVisibility.resolve("org.loader.runtime.error.ClassLoaderError"));
        assertEquals(ClassVisibility.Resolution.FORBIDDEN,
                ClassVisibility.resolve("sun.misc.Unsafe"));
    }

    @Test
    @DisplayName("resolve: java.* → PARENT_FIRST（拒绝会破坏类加载本身）")
    void resolveJdkDelegates() {
        assertEquals(ClassVisibility.Resolution.PARENT_FIRST,
                ClassVisibility.resolve("java.lang.String"));
        assertEquals(ClassVisibility.Resolution.PARENT_FIRST,
                ClassVisibility.resolve("java.util.ArrayList"));
    }

    @Test
    @DisplayName("resolve: ABI / Minecraft → PARENT_FIRST")
    void resolveParentFirst() {
        assertEquals(ClassVisibility.Resolution.PARENT_FIRST,
                ClassVisibility.resolve("org.loader.api.Mod"));
        assertEquals(ClassVisibility.Resolution.PARENT_FIRST,
                ClassVisibility.resolve("net.minecraft.client.Minecraft"));
    }

    @Test
    @DisplayName("resolve: Mod 私有包 → SELF_FIRST")
    void resolveSelfFirst() {
        assertEquals(ClassVisibility.Resolution.SELF_FIRST,
                ClassVisibility.resolve("com.example.mymod.MyClass"));
        assertEquals(ClassVisibility.Resolution.SELF_FIRST,
                ClassVisibility.resolve("dev.mymod.impl.Internal"));
    }

    @Test
    @DisplayName("null / 空类名 → FORBIDDEN（保守拒绝）")
    void resolveRejectsNull() {
        assertEquals(ClassVisibility.Resolution.FORBIDDEN, ClassVisibility.resolve(null));
        assertEquals(ClassVisibility.Resolution.FORBIDDEN, ClassVisibility.resolve(""));
        assertEquals(ClassVisibility.Resolution.FORBIDDEN, ClassVisibility.resolve("   "));
        assertTrue(ClassVisibility.isHidden(null));
    }

    @Test
    @DisplayName("可见性与隐藏性互斥")
    void visibleAndHiddenAreMutuallyExclusive() {
        String[] classes = {
                "org.loader.api.Mod",
                "net.minecraft.client.Minecraft",
                "org.loader.loader.LoaderMain",
                "java.lang.String",
                "com.example.Thing",
        };
        for (String c : classes) {
            assertFalse(ClassVisibility.isVisible(c) && ClassVisibility.isHidden(c),
                    "类不能同时可见与隐藏: " + c);
        }
    }

    // ── 跨 Mod 导出登记 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("跨 Mod 访问需要显式导出")
    void crossModExportIsExplicit() {
        ClassVisibility.resetRegistryForTesting();
        try {
            // 未导出时不可见
            assertFalse(ClassVisibility.isExportedTo("mod-a", "com.example.api.", "mod-b"));

            ClassVisibility.export("mod-a", List.of("com.example.api."));
            // 导出后，仅对包前缀匹配者可见
            assertTrue(ClassVisibility.isExportedTo("mod-a", "com.example.api.", "mod-b"));
            assertFalse(ClassVisibility.isExportedTo("mod-a", "com.example.internal.", "mod-b"));
            // 自己永远可见自己的包
            assertTrue(ClassVisibility.isExportedTo("mod-a", "anything.", "mod-a"));
        } finally {
            ClassVisibility.resetRegistryForTesting();
        }
    }
}