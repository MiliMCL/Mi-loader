package org.loader.loader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.loader.loader.classloader.MinecraftClassLoader;
import org.loader.loader.classloader.ModClassLoader;
import org.loader.loader.classloader.ModClassLoaderManager;
import org.loader.runtime.mod.ModManifest;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分发包布局回归：Mod 目录<b>不是</b> {@code gameDir/mods}。
 *
 * <h2>这个测试在防什么</h2>
 *
 * <p>分发包的实际布局是：
 * <pre>
 *   mili-0.1.0-mc26.2/
 *     bin/  core/  mods/← stardewvalley.jar在这里
 *     game/                    ← 26.2.jar 在这里
 * </pre>
 * 启动脚本把 {@code game/} 作为 gameDir 传入（正确 —— Minecraft 确实在那儿），
 * 但平台若按 {@code gameDir.resolve("mods")} 找Mod，就会去看
 * {@code game/mods/} —— 一个从来不存在的目录。
 *
 * <p>后果是<b>静默失效</b>：{@code ModDiscovery} 不抛异常、不打警告，
 * 只是返回空列表；游戏照常启动，玩家看到的是一个「没装任何 Mod」的干净世界。
 * 启动日志里唯一可查的线索是「我明明把 JAR 放进去了」。
 *
 * <p>所以这里不只断言「能找到」，还断言「ModClassLoader 真的把这个 JAR
 * 放进了自己的 classpath」—— 后者才是游戏能不能加载到 Mod 代码的关键。
 */
class DistributionModsDirTest {

    private static MinecraftClassLoader emptyGameLoader() {
        return new MinecraftClassLoader("minecraft-game", new URL[0],
                DistributionModsDirTest.class.getClassLoader());
    }

    /**造一个内容合法的最小 Mod JAR。 */
    private static void writeModJar(Path jar, String modId) throws Exception {
        Files.createDirectories(jar.getParent());
        String json = """
                {
                  "id": "%s",
                  "name": "%s",
                  "version": "1.0.0",
                  "entrypoint": "%s.Main",
                  "mili": { "platform": "0.1.0", "abi": 1, "minecraft": "26.2" }
                }
                """.formatted(modId, modId, modId);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("META-INF/mod.json"));
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("ignored.txt"));
            out.closeEntry();
        }
    }

    @Test
    @DisplayName("Mod 目录与 gameDir 平级时（分发包布局）仍能发现并挂载 Mod")
    void modsDirSiblingOfGameDirIsUsed(@TempDir Path tmp) throws Exception {
        // <tmp>/game 与 <tmp>/mods 平级 —— 这就是分发包布局
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Path modsDir = Files.createDirectories(tmp.resolve("mods"));
        writeModJar(modsDir.resolve("demo.jar"), "demo");

        var config = org.loader.loader.config.LoaderConfig.at(gameDir, modsDir);
        var discovery = org.loader.loader.discovery.ModDiscovery.scan(config);
        List<ModManifest> found = discovery.discover();

        assertEquals(1, found.size(),
                "Mod 应从 " + modsDir + " 被发现，而不是从 " + config.getModsPath());
        assertEquals("demo", found.get(0).id());

        // 关键：ModClassLoader 必须把这个 JAR 放进 classpath
        MinecraftClassLoader game = emptyGameLoader();
        try (ModClassLoaderManager mgr =
                     new ModClassLoaderManager(game, gameDir, config.getModsPath())) {
            ModClassLoader mcl = mgr.create(found.get(0));
            assertNotNull(mcl);
            assertEquals(1, mcl.modSources().size(),
                    "ModClassLoader 未挂载 " + modsDir.resolve("demo.jar")
                            + " —— 它找的是 " + gameDir.resolve("mods"));
            assertTrue(mcl.modSources().get(0).endsWith("demo.jar"));
        }
    }

    @Test
    @DisplayName("默认布局（mods 在 gameDir 下）不受影响")
    void defaultLayoutStillWorks(@TempDir Path tmp) throws Exception {
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Path modsDir = Files.createDirectories(gameDir.resolve("mods"));
        writeModJar(modsDir.resolve("demo.jar"), "demo");

        var config = org.loader.loader.config.LoaderConfig.at(gameDir);
        assertEquals(modsDir, config.getModsPath());

        var discovery = org.loader.loader.discovery.ModDiscovery.scan(config);
        assertEquals(1, discovery.discover().size());
    }
}