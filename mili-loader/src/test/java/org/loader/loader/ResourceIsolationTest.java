package org.loader.loader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.loader.classloader.MinecraftClassLoader;
import org.loader.loader.classloader.ModClassLoader;
import org.loader.runtime.mod.ModManifest;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 资源隔离测试。
 *
 * <p>用动态生成的 JAR 作为 Mod 夹具，验证：
 * <ul>
 *   <li>Mod 自身资源可读</li>
 *   <li>平台内部资源被拒绝并记录 violation</li>
 *   <li>资源不跨 Mod 泄漏</li>
 * </ul>
 */
@DisplayName("ClassLoader 资源隔离")
class ResourceIsolationTest {

    private static MinecraftClassLoader newGameLoader() {
        return new MinecraftClassLoader("minecraft-game", new URL[0],
                ResourceIsolationTest.class.getClassLoader());
    }

    /** 构造一个包含若干资源条目的 Mod JAR。 */
    private static Path createModJar(Path modsDir, String modId,
                                     String resourceName, String content) throws Exception {
        Files.createDirectories(modsDir);
        Path jar = modsDir.resolve(modId + ".jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry(resourceName));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return jar;
    }

    /**
     * 读资源内容并确保流被关闭。
     *
     * <p><b>为什么不能用 {@code assertNotNull(cl.getResourceAsStream(x))}</b>：
     * 那样返回的流从未关闭，而 jar 资源的流背后是 {@link java.util.zip.ZipFile} 的
     * 共享句柄 —— Windows 上会直接锁住 {@code mods/*.jar}，导致随后的删除抛
     * {@code FileSystemException: 另一个程序正在使用此文件}。Linux 上只是
     * 悄悄泄漏句柄，所以这个 bug 只在 Windows 暴露。
     */
    private static String readResource(ModClassLoader cl, String name) throws Exception {
        try (InputStream in = cl.getResourceAsStream(name)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Mod 可以读取自身资源")
    void modCanReadOwnResource() throws Exception {
        Path tmp = Files.createTempDirectory("mili-res-own");
        createModJar(tmp.resolve("mods"), "resmod", "data/config.txt", "hello");

        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoader mcl = new ModClassLoader(
                ModManifest.of("resmod", "ResMod", "1.0"), game, tmp)) {
            assertEquals("hello", readResource(mcl, "data/config.txt"),
                    "Mod 应能读到自身资源");
        }
        Files.deleteIfExists(tmp.resolve("mods/resmod.jar"));
        Files.deleteIfExists(tmp.resolve("mods"));
        Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("Mod 读取平台内部资源被拒绝并记录 violation")
    void internalResourceAccessIsDenied() throws Exception {
        Path tmp = Files.createTempDirectory("mili-res-int");
        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoader mcl = new ModClassLoader(
                ModManifest.of("intmod", "IntMod", "1.0"), game, tmp)) {

            InputStream leaked = mcl.getResourceAsStream("META-INF/mili/platform.json");
            assertNull(leaked, "平台内部资源必须不可读");
            assertTrue(mcl.hasViolations(), "拒绝访问应被记录");
            assertTrue(mcl.violations().stream()
                            .anyMatch(v -> v.target().equals("META-INF/mili/platform.json")),
                    "violation 应指向被拒绝的资源");
        }
        Files.deleteIfExists(tmp.resolve("mods"));
        Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("Mod 无法读取 Loader 内部资源")
    void loaderInternalResourceDenied() throws Exception {
        Path tmp = Files.createTempDirectory("mili-res-loader");
        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoader mcl = new ModClassLoader(
                ModManifest.of("ldrmod", "LdrMod", "1.0"), game, tmp)) {
            assertNull(mcl.getResourceAsStream("org/loader/loader/LoaderMain.class"),
                    "Loader 类文件资源不可经资源接口读取");
            assertNull(mcl.getResourceAsStream("org/loader/installer/InstallerMain.class"));
        }
        Files.deleteIfExists(tmp.resolve("mods"));
        Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("null 资源名被拒绝")
    void nullResourceNameDenied() throws Exception {
        Path tmp = Files.createTempDirectory("mili-res-null");
        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoader mcl = new ModClassLoader(
                ModManifest.of("nullmod", "N", "1.0"), game, tmp)) {
            assertNull(mcl.getResourceAsStream(null));
            assertTrue(mcl.hasViolations());
        }
        Files.deleteIfExists(tmp.resolve("mods"));
        Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("资源不跨 Mod 泄漏：A 的资源 B 读不到")
    void resourcesDoNotLeakAcrossMods() throws Exception {
        Path tmp = Files.createTempDirectory("mili-res-leak");
        // 只有 mod-a 有该资源
        createModJar(tmp.resolve("mods"), "leak-a", "secret/data.txt", "top-secret");

        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoader a = new ModClassLoader(
                ModManifest.of("leak-a", "A", "1.0"), game, tmp);
             ModClassLoader b = new ModClassLoader(
                     ModManifest.of("leak-b", "B", "1.0"), game, tmp)) {

            assertEquals("top-secret", readResource(a, "secret/data.txt"),
                    "A 应能读自己的资源");
            assertNull(b.getResourceAsStream("secret/data.txt"),
                    "B 不应读到 A 的私有资源");
        }
        Files.deleteIfExists(tmp.resolve("mods/leak-a.jar"));
        Files.deleteIfExists(tmp.resolve("mods"));
        Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("缺失资源返回 null 而非抛异常")
    void missingResourceReturnsNull() throws Exception {
        Path tmp = Files.createTempDirectory("mili-res-missing");
        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoader mcl = new ModClassLoader(
                ModManifest.of("missmod", "M", "1.0"), game, tmp)) {
            assertNull(mcl.getResourceAsStream("does/not/exist.txt"));
            assertFalse(mcl.hasViolations(), "读取不存在的资源不算违规");
        }
        Files.deleteIfExists(tmp.resolve("mods"));
        Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("关闭后不再返回资源")
    void closedLoaderReturnsNoResources() throws Exception {
        Path tmp = Files.createTempDirectory("mili-res-closed");
        createModJar(tmp.resolve("mods"), "closedres", "a.txt", "data");
        MinecraftClassLoader game = newGameLoader();
        ModClassLoader mcl = new ModClassLoader(
                ModManifest.of("closedres", "C", "1.0"), game, tmp);
        assertEquals("data", readResource(mcl, "a.txt"));
        mcl.close();
        assertNull(mcl.getResourceAsStream("a.txt"),
                "已关闭 CL 不应再返回资源");
        Files.deleteIfExists(tmp.resolve("mods/closedres.jar"));
        Files.deleteIfExists(tmp.resolve("mods"));
        Files.deleteIfExists(tmp);
    }
}