package org.loader.loader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.loader.classloader.ClassVisibility;
import org.loader.loader.classloader.MinecraftClassLoader;
import org.loader.loader.classloader.ModClassLoader;
import org.loader.runtime.mod.ModManifest;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 类隔离测试。
 *
 * <p>验证核心目标：修复历史缺陷后，Mod <b>不再</b>各自持有一份 Minecraft 类。
 * 隔离的代价是 Mod 私有类互不可见 —— 这是有意的设计。
 */
@DisplayName("ClassLoader 类隔离")
class ClassIsolationTest {

    private static MinecraftClassLoader newGameLoader() {
        return new MinecraftClassLoader("minecraft-game", new URL[0],
                ClassIsolationTest.class.getClassLoader());
    }

    @Test
    @DisplayName("Minecraft 类在所有 Mod 间共享同一个 ClassLoader")
    void minecraftClassIsSharedAcrossMods() throws Exception {
        // 这是本次架构修复的核心断言：
        // Mod 与 Minecraft 必须共享同一份 MC 类，否则 Mod 操作 MC 对象必然
        // ClassCastException。
        MinecraftClassLoader game = newGameLoader();
        ModManifest a = ModManifest.of("iso-a", "A", "1.0");
        ModManifest b = ModManifest.of("iso-b", "B", "1.0");

        try (ModClassLoader mclA = new ModClassLoader(a, game, Path.of("."));
             ModClassLoader mclB = new ModClassLoader(b, game, Path.of("."))) {

            // 用一个必然存在于平台 classpath 的 ABI 类作为「共享类」代理：
            // 它经由 parent (game CL) 加载，两个 Mod 拿到的必须是同一个 Class。
            Class<?> viaA = mclA.loadModClass("org.loader.api.Mod");
            Class<?> viaB = mclB.loadModClass("org.loader.api.Mod");

            assertSame(viaA, viaB,
                    "两个 Mod 通过 parent 加载的类必须是同一个 Class 实例");
            // MinecraftClassLoader 对 org.loader.* 只做委派、不自己定义，
            // 所以最终定义者是 AppClassLoader。这是刻意设计：MC 的 classpath
            // 绝不能阴影平台的 abi/runtime，否则会出现第二份互不相等的 Mod 类。
            assertNotSame(mclA, viaA.getClassLoader(),
                    "共享类不能由某个 Mod 自己定义");
            assertNotSame(mclB, viaA.getClassLoader());
            assertSame(viaA.getClassLoader(), viaB.getClassLoader());
        }
    }

    @Test
    @DisplayName("Mod 之间没有父子继承关系（防止隐式跨 Mod 可见）")
    void modsAreSiblingsNotParentChild() throws Exception {
        MinecraftClassLoader game = newGameLoader();
        ModManifest a = ModManifest.of("sib-a", "A", "1.0");
        ModManifest b = ModManifest.of("sib-b", "B", "1.0");

        try (ModClassLoader mclA = new ModClassLoader(a, game, Path.of("."));
             ModClassLoader mclB = new ModClassLoader(b, game, Path.of("."))) {
            assertNotSame(mclA.getParent(), mclB,
                    "Mod A 的 parent 不应是 Mod B —— parent 只能是 game CL");
            assertSame(mclA.getParent(), mclB.getParent());
        }
    }

    @Test
    @DisplayName("Mod 私有类采用 self-first，类加载按 Mod 独立")
    void privateClassesUseSelfFirst() throws Exception {
        // SELF_FIRST 意味着每个 Mod 独立定义自己的私有类，
        // 即使类名相同也不共享。
        assertEquals(ClassVisibility.Resolution.SELF_FIRST,
                ClassVisibility.resolve("com.example.mymod.Secret"));
        assertEquals(ClassVisibility.Resolution.SELF_FIRST,
                ClassVisibility.resolve("com.example.othermod.Secret"));
    }

    @Test
    @DisplayName("关闭一个 Mod 不影响其他 Mod 的已加载类")
    void closingOneModDoesNotBreakOthers() throws Exception {
        MinecraftClassLoader game = newGameLoader();
        ModManifest a = ModManifest.of("close-iso-a", "A", "1.0");
        ModManifest b = ModManifest.of("close-iso-b", "B", "1.0");

        ModClassLoader mclA = new ModClassLoader(a, game, Path.of("."));
        ModClassLoader mclB = new ModClassLoader(b, game, Path.of("."));

        Class<?> sharedViaB = mclB.loadModClass("org.loader.api.Mod");
        mclA.close();

        // A 已关闭，B 仍能正常加载共享类
        assertTrue(mclA.isClosed());
        assertFalse(mclB.isClosed());
        assertSame(sharedViaB, mclB.loadModClass("org.loader.api.Mod"),
                "关闭 Mod A 不应使 Mod B 已链接的类失效");

        mclB.close();
    }

    @Test
    @DisplayName("MinecraftClassLoader 对 org.loader.* 委派给平台，避免版本分裂")
    void minecraftLoaderDelegatesPlatformPackages() throws Exception {
MinecraftClassLoader game = newGameLoader();
        // 平台自有包必须由平台 CL 定义 —— MC classpath 不得阴影平台实现。
        //
        // 注意断言的对象：不是"能否加载"（委派给 parent 后当然能加载，
        // 那正是委派生效的证明），而是"由谁定义"。若 MC CL 自己定义了
        // LoaderMain，Mod 拿到的平台类就会与 AppClassLoader 里的那份不相等，
        // 于是所有跨边界传参都会 ClassCastException。
        Class<?> loaderMain = Class.forName("org.loader.loader.LoaderMain", false, game);
        assertSame(ClassIsolationTest.class.getClassLoader(),
                loaderMain.getClassLoader(),
                "平台类应由 AppClassLoader 定义，MC CL 只做委派");
        assertNotSame(game, loaderMain.getClassLoader());
        assertTrue(game.classpathNames() != null);
    }

    @Test
    @DisplayName("gameClassLoader 关闭后不可再加载")
    void gameLoaderCloseBlocksLoading() throws Exception {
        MinecraftClassLoader game = new MinecraftClassLoader("minecraft-game",
                new URL[0], ClassIsolationTest.class.getClassLoader());
        assertFalse(game.isClosed());
        game.close();
        assertTrue(game.isClosed());
        assertFalse(game.canLoad("net.minecraft.client.Minecraft"),
                "已关闭的 MC CL 不应报告可加载");
        assertDoesNotThrow(game::close, "重复 close 应幂等");
    }

    @Test
    @DisplayName("ModClassLoader 关闭后 gameClassLoader 仍可用")
    void modCloseDoesNotCloseGameLoader() throws Exception {
        MinecraftClassLoader game = newGameLoader();
        ModClassLoader mcl = new ModClassLoader(
                ModManifest.of("lifetime-mod", "L", "1.0"), game, Path.of("."));
        mcl.close();
        assertFalse(game.isClosed(),
                "Mod CL 关闭不得连带关闭全局 MC CL");
    }

    @Test
    @DisplayName("多 Mod 场景下共享类始终同一实例")
    void sharedClassIdentityAcrossManyMods() throws Exception {
        MinecraftClassLoader game = newGameLoader();
        List<ModManifest> manifests = List.of(
                ModManifest.of("many-1", "1", "1.0"),
                ModManifest.of("many-2", "2", "1.0"),
                ModManifest.of("many-3", "3", "1.0"),
                ModManifest.of("many-4", "4", "1.0"));

        var loaders = manifests.stream()
                .map(m -> {
                    try {
                        return new ModClassLoader(m, game, Path.of("."));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();

        try {
            Class<?> first = loaders.get(0).loadModClass("org.loader.api.ModContext");
            for (int i = 1; i < loaders.size(); i++) {
                assertSame(first, loaders.get(i).loadModClass("org.loader.api.ModContext"),
                        "所有 Mod 必须看到同一个 ModContext 类");
            }
        } finally {
            loaders.forEach(mcl -> {
                try {
                    mcl.close();
                } catch (Exception ignored) {
                    // 测试清理
                }
            });
        }
    }

    @Test
    @DisplayName("不存在的 Mod 路径不影响 classpath 构建")
    void missingModPathIsSafe() throws Exception {
        Path tmp = Files.createTempDirectory("mili-iso-missing");
        MinecraftClassLoader game = newGameLoader();
        ModManifest m = ModManifest.of("nonexistent", "N", "1.0");
        try (ModClassLoader mcl = new ModClassLoader(m, game, tmp)) {
            assertTrue(mcl.modSources().isEmpty());
            assertFalse(mcl.hasViolations(),
                    "缺失 Mod 不应产生违规记录: " + mcl.violations());
        }
        Files.deleteIfExists(tmp);
    }
}