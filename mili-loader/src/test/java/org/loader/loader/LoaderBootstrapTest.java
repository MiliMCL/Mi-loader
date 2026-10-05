package org.loader.loader;

import org.junit.jupiter.api.*;
import org.loader.loader.classloader.MinecraftClassLoader;
import org.loader.loader.classloader.ModClassLoader;
import org.loader.loader.classloader.ModClassLoaderManager;
import org.loader.loader.config.LoaderConfig;
import org.loader.loader.hook.EntryPointHook;
import org.loader.runtime.mod.ModManifest;

import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Loader 启动序列测试。
 *
 * <p>覆盖新ClassLoader 拓扑的构建，不启动真实 Minecraft。
 */
class LoaderBootstrapTest {

    private static MinecraftClassLoader newGameLoader() {
        return new MinecraftClassLoader("minecraft-game", new URL[0],
                LoaderBootstrapTest.class.getClassLoader());
    }

    @Test
    void loaderConfig_defaultsCorrectly() {
        LoaderConfig config = LoaderConfig.load(Path.of("."));
        assertNotNull(config);
        assertEquals(Path.of("./server.jar"), config.getMinecraftPath());
        assertEquals(Path.of("./mods"), config.getModsPath());
        assertEquals(Path.of("./libraries"), config.getLibrariesPath());
    }

    @Test
    void modClassLoader_createdWithGameClassLoaderParent() throws Exception {
        MinecraftClassLoader game = newGameLoader();
        ModManifest manifest = ModManifest.of("test", "Test", "1.0");
        try (ModClassLoader mcl = new ModClassLoader(manifest, game, Path.of("."))) {
            assertNotNull(mcl);
            assertEquals("test", mcl.manifest().id());
            assertSame(game, mcl.gameClassLoader());
        }
    }

    @Test
    void modClassLoaderManager_createsAndTracks() throws Exception {
        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoaderManager mgr = new ModClassLoaderManager(game, Path.of("."))) {
            ModClassLoader mcl = mgr.create(ModManifest.of("a", "A", "1.0"));
            assertNotNull(mcl);
            assertTrue(mgr.has("a"));
            assertEquals(1, mgr.all().size());
            assertSame(game, mgr.gameClassLoader());
        }
    }

    @Test
    void modClassLoaderManager_closeReleasesAll() throws Exception {
        MinecraftClassLoader game = newGameLoader();
        ModClassLoaderManager mgr = new ModClassLoaderManager(game, Path.of("."));
        mgr.create(ModManifest.of("x", "X", "1.0"));
        mgr.create(ModManifest.of("y", "Y", "1.0"));
        assertEquals(2, mgr.all().size());

        assertDoesNotThrow(mgr::close);
        assertTrue(mgr.all().isEmpty());
        assertTrue(mgr.loadedModIds().isEmpty());
        // 关闭 Mod manager 不应关闭全局 MC CL
        assertFalse(game.isClosed());
    }

    @Test
    void modClassLoaderManager_createAllFollowsDependencyOrder() throws Exception {
        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoaderManager mgr = new ModClassLoaderManager(game, Path.of("."))) {
            mgr.createAll(java.util.List.of(
                    ModManifest.of("base", "Base", "1.0"),
                    ModManifest.of("addon", "Addon", "1.0")));

            assertEquals(java.util.List.of("base", "addon"), mgr.loadedModIds(),
                    "创建顺序应遵循依赖拓扑顺序");
        }
    }

    @Test
    void entryPointHook_initialState() {
        EntryPointHook hook = new EntryPointHook();
        assertFalse(hook.isInstalled());
        assertNull(hook.getBootstrap());
        assertNull(hook.getMinecraftScope());
    }

    @Test
    void entryPointHook_installCreatesBootstrapUnderRuntime() {
        org.loader.runtime.kernel.Runtime rt =
                org.loader.runtime.kernel.Runtime.create("hook-test");
        try {
            rt.start();
            MinecraftClassLoader game = newGameLoader();
            ModClassLoaderManager mgr = new ModClassLoaderManager(game, Path.of("."));

            EntryPointHook hook = new EntryPointHook();
            hook.install(game, rt, mgr,
                    org.loader.runtime.RuntimeEnvironment.DEDICATED_SERVER);

            assertTrue(hook.isInstalled());
            assertNotNull(hook.getBootstrap());
            assertNotNull(hook.getMinecraftScope());
            // install 不应直接启动 —— 状态机由 invokeMinecraftMain 推进
            assertEquals(org.loader.runtime.minecraft.BootstrapState.CREATED,
                    hook.getBootstrap().state());
        } finally {
            try {
                rt.close();
            } catch (Exception ignored) {
                // 测试清理
            }
        }
    }

    @Test
    void entryPointHook_installIsIdempotent() {
        org.loader.runtime.kernel.Runtime rt =
                org.loader.runtime.kernel.Runtime.create("hook-idem");
        try {
            rt.start();
            MinecraftClassLoader game = newGameLoader();
            ModClassLoaderManager mgr = new ModClassLoaderManager(game, Path.of("."));

            EntryPointHook hook = new EntryPointHook();
            hook.install(game, rt, mgr, org.loader.runtime.RuntimeEnvironment.DEDICATED_SERVER);
            var first = hook.getBootstrap();
            hook.install(game, rt, mgr, org.loader.runtime.RuntimeEnvironment.DEDICATED_SERVER);

            assertSame(first, hook.getBootstrap(), "重复 install 不应重建 bootstrap");
        } finally {
            try {
                rt.close();
            } catch (Exception ignored) {
                // 测试清理
            }
        }
    }
}