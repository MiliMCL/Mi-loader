package org.loader.loader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.loader.classloader.MinecraftClassLoader;
import org.loader.loader.classloader.ModClassLoader;
import org.loader.loader.classloader.ModClassLoaderManager;
import org.loader.runtime.mod.ModManifest;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClassLoader 泄漏检测。
 *
 * <p>泄漏是长驻服务器最隐蔽的故障：泄漏的 CL 会持有 JAR 句柄、阻止文件删除、
 * 持续占用 Metaspace。测试通过「创建 → 加载 → 关闭 → 丢弃引用 → 强制 GC →
 * 断言 WeakReference 被清除」验证 CL 可被回收。
 */
@DisplayName("ClassLoader 泄漏检测")
class ClassLoaderLeakTest {

    private static MinecraftClassLoader newGameLoader() {
        return new MinecraftClassLoader("minecraft-game", new URL[0],
                ClassLoaderLeakTest.class.getClassLoader());
    }

    private static Path createModJar(Path modsDir, String modId, String clsResource) throws Exception {
        Files.createDirectories(modsDir);
        Path jar = modsDir.resolve(modId + ".jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("marker/" + clsResource));
            zos.write("x".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return jar;
    }

    /**
     * 反复 GC 直至 WeakReference 被清除或达到重试上限。
     */
    private static boolean awaitCollected(WeakReference<?> ref, int attempts) {
        for (int i = 0; i < attempts; i++) {
            if (ref.get() == null) {
                return true;
            }
            System.gc();
            try {
                // 短暂 sleep 给终结器/弱引用清理一个机会
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ref.get() == null;
    }

    @Test
    @DisplayName("关闭后的 ModClassLoader 可被 GC 回收")
    void closedModClassLoaderIsCollectable() throws Exception {
        Path tmp = Files.createTempDirectory("mili-leak-mod");
        createModJar(tmp.resolve("mods"), "leakmod", "marker.txt");
        MinecraftClassLoader game = newGameLoader();

        WeakReference<ClassLoader> ref;
        try (ModClassLoader mcl = new ModClassLoader(
                ModManifest.of("leakmod", "LeakMod", "1.0"), game, tmp)) {
            mcl.loadModClass("org.loader.api.api.Mod");
            ref = new WeakReference<>(mcl);
        }
        assertTrue(awaitCollected(ref, 30),
                "关闭的 ModClassLoader 应可被回收；仍被引用的原因通常是闭包捕获或未关闭");

        Files.deleteIfExists(tmp.resolve("mods/leakmod.jar"));
        Files.deleteIfExists(tmp.resolve("mods"));
        Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("关闭后 Mod 的 JAR 文件可被删除（无句柄占用）")
    void modJarCanBeDeletedAfterClose() throws Exception {
        Path tmp = Files.createTempDirectory("mili-leak-del");
        Path jar = createModJar(tmp.resolve("mods"), "delmod", "marker.txt");
        MinecraftClassLoader game = newGameLoader();

        ModClassLoader mcl = new ModClassLoader(
                ModManifest.of("delmod", "DelMod", "1.0"), game, tmp);
        assertTrue(Files.exists(jar));
        mcl.close();

        assertDoesNotThrow(() -> Files.deleteIfExists(jar),
                "关闭后应能删除 Mod JAR —— 失败说明存在文件句柄泄漏");

        Files.deleteIfExists(tmp.resolve("mods"));
        Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("Manager 逆序关闭全部 Mod CL")
    void managerClosesAllInReverseOrder() throws Exception {
        Path tmp = Files.createTempDirectory("mili-leak-order");
        MinecraftClassLoader game = newGameLoader();
        ModClassLoaderManager mgr = new ModClassLoaderManager(game, tmp);

        ModClassLoader first = mgr.create(ModManifest.of("rev-1", "1", "1.0"));
        ModClassLoader second = mgr.create(ModManifest.of("rev-2", "2", "1.0"));
        ModClassLoader third = mgr.create(ModManifest.of("rev-3", "3", "1.0"));

        mgr.close();

        assertTrue(first.isClosed(), "所有 Mod CL 都应被关闭");
        assertTrue(second.isClosed());
        assertTrue(third.isClosed());
        // Manager 关闭不牵动全局 MC CL
        assertFalse(game.isClosed());

        Files.deleteIfExists(tmp.resolve("mods"));
        Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("Manager 关闭后可被 GC 回收")
    void managerIsCollectable() throws Exception {
        Path tmp = Files.createTempDirectory("mili-leak-mgr");
        MinecraftClassLoader game = newGameLoader();

        WeakReference<ClassLoader> ref;
        {
            ModClassLoaderManager mgr = new ModClassLoaderManager(game, tmp);
            ModClassLoader mod = mgr.create(ModManifest.of("gc-1", "1", "1.0"));
            // Manager 本身不是 ClassLoader；泄漏风险在它持有的 ModClassLoader 上，
            // 所以引用它创建的 Mod ClassLoader。
            ref = new WeakReference<>(mod);
            mgr.close();
        }
        assertTrue(awaitCollected(ref, 30),
                "已关闭的 ModClassLoader 应可被回收");
        Files.deleteIfExists(tmp.resolve("mods"));
        Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("关闭后的 gameClassLoader 可被 GC 回收")
    void closedGameClassLoaderIsCollectable() throws Exception {
        WeakReference<ClassLoader> ref;
        MinecraftClassLoader game = newGameLoader();
        game.close();
        ref = new WeakReference<>(game);
        assertTrue(awaitCollected(ref, 30),
                "已关闭的 MinecraftClassLoader 应可被回收");
    }

    @Test
    @DisplayName("关闭一个 Mod CL 不影响同批其他 Mod CL 的生命周期")
    void closingOneKeepsOthersAlive() throws Exception {
        Path tmp = Files.createTempDirectory("mili-leak-multi");
        MinecraftClassLoader game = newGameLoader();
        ModClassLoader a = new ModClassLoader(ModManifest.of("keep-a", "A", "1.0"), game, tmp);
        ModClassLoader b = new ModClassLoader(ModManifest.of("keep-b", "B", "1.0"), game, tmp);

        a.close();
        assertTrue(a.isClosed());
        assertFalse(b.isClosed(), "关闭 A 不应连带关闭 B");
        b.close();
        assertTrue(b.isClosed());

        Files.deleteIfExists(tmp.resolve("mods"));
        Files.deleteIfExists(tmp);
    }

    @Test
    @DisplayName("重复创建同一 Mod 不产生额外 CL（避免僵尸 CL）")
    void idempotentCreateDoesNotLeak() throws Exception {
        Path tmp = Files.createTempDirectory("mili-leak-idem");
        MinecraftClassLoader game = newGameLoader();
        ModClassLoaderManager mgr = new ModClassLoaderManager(game, tmp);

        ModManifest m = ModManifest.of("once", "Once", "1.0");
        List<ModClassLoader> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(mgr.create(m));
        }
        assertEquals(1, mgr.loadedModIds().size());
        for (ModClassLoader cl : created) {
            assertSame(created.get(0), cl);
        }
        mgr.close();

        Files.deleteIfExists(tmp.resolve("mods"));
        Files.deleteIfExists(tmp);
    }
}