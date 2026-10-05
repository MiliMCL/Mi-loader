package org.loader.loader;

import org.junit.jupiter.api.*;
import org.loader.loader.classloader.MinecraftClassLoader;
import org.loader.loader.classloader.ModClassLoader;
import org.loader.runtime.mod.ModManifest;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModClassLoader 基础行为测试。
 *
 * <p>拓扑前提：ModClassLoader 的 parent 必须是 MinecraftClassLoader，
 * 而非依赖 Mod —— 这样 Minecraft 类全局唯一。
 */
class ModClassLoaderTest {

    private static MinecraftClassLoader newGameLoader() {
        return new MinecraftClassLoader("minecraft-game", new URL[0],
                ModClassLoaderTest.class.getClassLoader());
    }

    @Test
    void modClassLoader_canBeCreated() {
        ModManifest manifest = ModManifest.of("test-mod", "Test", "1.0");
        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoader mcl = new ModClassLoader(manifest, game, Path.of("."))) {
            assertNotNull(mcl);
            assertEquals("test-mod", mcl.manifest().id());
            assertSame(game, mcl.gameClassLoader());
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void modClassLoader_parentIsGameClassLoader_notDependency() throws Exception {
        // 核心契约：parent 永远是 MC CL，而不是依赖 Mod。
        // 否则 Minecraft 类会被重复定义。
        MinecraftClassLoader game = newGameLoader();
        ModManifest a = ModManifest.of("mod-a", "A", "1.0");
        ModManifest b = ModManifest.of("mod-b", "B", "1.0");

        try (ModClassLoader mclA = new ModClassLoader(a, game, Path.of("."));
             ModClassLoader mclB = new ModClassLoader(b, game, Path.of("."))) {
            assertSame(game, mclA.getParent());
            assertSame(game, mclB.getParent());
            // 两个 Mod 之间没有父子关系
            assertNotSame(mclA, mclB.getParent());
            assertNotSame(mclB, mclA.getParent());
        }
    }

    @Test
    void modClassLoader_independentInstances() throws Exception {
        MinecraftClassLoader game = newGameLoader();
        ModManifest m1 = ModManifest.of("mod-a", "A", "1.0");
        ModManifest m2 = ModManifest.of("mod-b", "B", "1.0");

        try (ModClassLoader cl1 = new ModClassLoader(m1, game, Path.of("."));
             ModClassLoader cl2 = new ModClassLoader(m2, game, Path.of("."))) {
            assertNotSame(cl1, cl2);
        }
    }

    @Test
    void modClassLoader_closeIsIdempotent() throws Exception {
        MinecraftClassLoader game = newGameLoader();
        ModManifest manifest = ModManifest.of("close-mod", "CloseMod", "1.0");
        ModClassLoader mcl = new ModClassLoader(manifest, game, Path.of("."));

        assertDoesNotThrow(() -> mcl.close());
        assertTrue(mcl.isClosed());
        assertDoesNotThrow(() -> mcl.close(), "重复 close 不应抛异常");
    }

    @Test
    void modClassLoader_afterCloseRefusesLoading() throws Exception {
        MinecraftClassLoader game = newGameLoader();
        ModManifest manifest = ModManifest.of("closed-mod", "ClosedMod", "1.0");
        ModClassLoader mcl = new ModClassLoader(manifest, game, Path.of("."));
        mcl.close();

        assertThrows(ClassNotFoundException.class,
                () -> mcl.loadModClass("com.example.Whatever"),
                "已关闭的 ClassLoader 必须拒绝加载新类");
    }

    @Test
    void modClassLoader_loadsAbiClassesFromParent() throws Exception {
        // ABI 必须对 Mod 可见（PARENT_FIRST）
        MinecraftClassLoader game = newGameLoader();
        ModManifest manifest = ModManifest.of("abi-mod", "AbiMod", "1.0");
        try (ModClassLoader mcl = new ModClassLoader(manifest, game, Path.of("."))) {
            Class<?> c = mcl.loadModClass("org.loader.api.Mod");
            assertNotNull(c, "Mod 必须能访问 ABI");
            // 应由平台 CL 定义，与 Mod CL 无关
            assertSame(ModClassLoaderTest.class.getClassLoader(), c.getClassLoader());
        }
    }

    @Test
    void modClassLoader_locatesJarSource() throws Exception {
        // mods/<id>.jar 应进入 classpath
        Path tmp = Files.createTempDirectory("mili-mcl-test");
        Files.createDirectories(tmp.resolve("mods"));
        Path jar = tmp.resolve("mods").resolve("sourcemod.jar");
        Files.writeString(jar, "not-a-real-jar");

        MinecraftClassLoader game = newGameLoader();
        ModManifest manifest = ModManifest.of("sourcemod", "SourceMod", "1.0");
        try (ModClassLoader mcl = new ModClassLoader(manifest, game, tmp)) {
            assertEquals(1, mcl.modSources().size());
            assertEquals(jar.toAbsolutePath(), mcl.modSources().get(0).toAbsolutePath());
        }
        Files.deleteIfExists(jar);
        Files.deleteIfExists(tmp.resolve("mods"));
        Files.deleteIfExists(tmp);
    }

    @Test
    void modClassLoader_noSourcesWhenModAbsent() throws Exception {
        Path tmp = Files.createTempDirectory("mili-mcl-empty");
        MinecraftClassLoader game = newGameLoader();
        ModManifest manifest = ModManifest.of("ghostmod", "Ghost", "1.0");
        try (ModClassLoader mcl = new ModClassLoader(manifest, game, tmp)) {
            assertTrue(mcl.modSources().isEmpty(),
                    "不存在的 Mod 不应有 classpath 来源");
        }
        Files.deleteIfExists(tmp);
    }
}