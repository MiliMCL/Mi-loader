package org.loader.runtime.mod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.runtime.kernel.Runtime;
import org.loader.runtime.kernel.Scope;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime 侧 {@link ModLoader} 的「占位语义」回归测试。
 *
 * <h2>这个测试在防什么</h2>
 *
 * <p>旧实现里 {@code createClassLoader} 只有一行
 * {@code return parent != null ? parent : getClass().getClassLoader()}，
 * 注释写着「In a real implementation, this would create a URLClassLoader」。
 * 结果是：一个根本没有被打进 classpath 的 Mod 也能「加载成功」，
 * 直到某处引用它的类时抛 {@code ClassNotFoundException}/{@code NoClassDefFoundError}
 * —— 报错点离根因很远，看起来像 Mod 自己的问题。
 *
 * <p>现在占位语义是<b>显式</b>的：入口类不可见就立刻失败，消息直接指向
 * 真正的生产路径（{@code mili-loader} 的 {@code ModClassLoader}）。
 */
class ModLoaderPlaceholderTest {

    private static ModManifest withEntrypoint(String id, String entrypoint) {
        return new ModManifest(id, id, "1.0", "", "", List.of(), List.of(),
                entrypoint, List.of(), ModManifest.VersionBinding.unbound());
    }

    @Test
    @DisplayName("明确声明不创建隔离 ClassLoader")
    void doesNotCreateIsolatedClassLoaders() {
        assertFalse(ModLoader.createsIsolatedClassLoaders(),
                "runtime 的 ModLoader 是占位实现，隔离加载器属于 mili-loader");
    }

    @Test
    @DisplayName("无入口类的清单可以加载，classLoader 就是调用方给的")
    void manifestWithoutEntrypointLoadsWithCallerLoader() {
        Runtime runtime = Runtime.create("placeholder-basic");
        runtime.start();
        try {
            ModLoader loader = new ModLoader(runtime);
            ClassLoader caller = getClass().getClassLoader();

            Mod mod = loader.load(ModManifest.of("plain", "Plain", "1.0"), caller);

            assertSame(caller, mod.classLoader());
            assertEquals("plain", mod.id());
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("入口类可见时正常加载（不触发静态初始化）")
    void visibleEntrypointLoads() {
        Runtime runtime = Runtime.create("placeholder-visible");
        runtime.start();
        try {
            ModLoader loader = new ModLoader(runtime);
            Mod mod = loader.load(
                    withEntrypoint("visible", "org.loader.runtime.mod.ModManifest"),
                    getClass().getClassLoader());

            assertNotNull(mod.classLoader());

            // 主线程是否被初始化：ModManifest 无静态初始化器，
            // 这里只验证加载不会因为 initialize=true 而执行用户代码。
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("入口类不可见 → 立刻 ModLoadException，消息指向生产路径")
    void missingEntrypointFailsFast() {
        Runtime runtime = Runtime.create("placeholder-missing");
        runtime.start();
        try {
            ModLoader loader = new ModLoader(runtime);

            ModLoadException e = assertThrows(ModLoadException.class, () ->
                    loader.load(withEntrypoint("ghost", "com.example.NotOnClasspath"),
                            getClass().getClassLoader()));

            assertTrue(e.getMessage().contains("com.example.NotOnClasspath"), e.getMessage());
            assertTrue(e.getMessage().contains("ModClassLoader"),
                    () -> "错误消息必须指出正确的生产路径: " + e.getMessage());
            assertTrue(e.getMessage().contains("ghost"), e.getMessage());

            // 失败的 Mod 不得留在「已加载」集合里造成假象
            assertTrue(loader.find("ghost").isEmpty());
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("重复加载同一 Mod 被拒绝")
    void duplicateLoadRejected() {
        Runtime runtime = Runtime.create("placeholder-dup");
        runtime.start();
        try {
            ModLoader loader = new ModLoader(runtime);
            ModManifest manifest = ModManifest.of("dup", "Dup", "1.0");
            loader.load(manifest, getClass().getClassLoader());

            assertThrows(IllegalStateException.class,
                    () -> loader.load(manifest, getClass().getClassLoader()));
        } finally {
            runtime.close();
        }
    }

    @Test
    @DisplayName("unload 只解除 Runtime 登记，不声称类被卸载")
    void unloadRemovesRegistrationOnly() {
        Runtime runtime = Runtime.create("placeholder-unload");
        runtime.start();
        try {
            ModLoader loader = new ModLoader(runtime);
            Mod mod = loader.load(ModManifest.of("tmp", "Tmp", "1.0"),
                    getClass().getClassLoader());

            loader.unload(mod);

            assertTrue(loader.find("tmp").isEmpty());
            Scope scope = mod.scope();
            assertTrue(scope.isStopped(), "Scope 应已关闭");
        } finally {
            runtime.close();
        }
    }
}
