package org.loader.loader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.loader.classloader.MinecraftClassLoader;
import org.loader.loader.classloader.ModClassLoader;
import org.loader.loader.classloader.ModClassLoaderManager;
import org.loader.runtime.mod.ModManifest;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨 Mod 重复类检测。
 *
 * <p>child-first 策略下，两个 Mod 定义同名类是<b>合法但可疑</b>的：
 * 它们互相隔离不会崩溃，但通常意味着重复打包了第三方库。
 * Manager 负责检出并报告。
 */
@DisplayName("跨 Mod 重复类检测")
class DuplicateClassTest {

    private static MinecraftClassLoader newGameLoader() {
        return new MinecraftClassLoader("minecraft-game", new URL[0],
                DuplicateClassTest.class.getClassLoader());
    }

    @Test
    @DisplayName("registerClassOwner: 首次注册返回 null")
    void firstOwnershipReturnsNull() {
        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoaderManager mgr = new ModClassLoaderManager(game, Path.of("."))) {
            assertNull(mgr.registerClassOwner("com.example.Shared", "mod-a"));
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("registerClassOwner: 第二个 Mod 声明同名类时检出冲突")
    void secondOwnerIsDetected() {
        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoaderManager mgr = new ModClassLoaderManager(game, Path.of("."))) {
            assertNull(mgr.registerClassOwner("com.example.Shared", "mod-a"));
            assertEquals("mod-a", mgr.registerClassOwner("com.example.Shared", "mod-b"),
                    "应返回首次定义者");
            assertEquals(1, mgr.recordedDuplicates().size());
            assertEquals("com.example.Shared", mgr.recordedDuplicates().get(0).className());
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("registerClassOwner: 同一 Mod 重复注册不算冲突")
    void sameOwnerIsNotConflict() {
        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoaderManager mgr = new ModClassLoaderManager(game, Path.of("."))) {
            assertNull(mgr.registerClassOwner("com.example.Own", "mod-a"));
            assertNull(mgr.registerClassOwner("com.example.Own", "mod-a"),
                    "同一 Mod 重复注册不应算重复类");
            assertTrue(mgr.recordedDuplicates().isEmpty());
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("detectDuplicateClasses: 无重复时返回空")
    void noDuplicatesReturnsEmpty() {
        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoaderManager mgr = new ModClassLoaderManager(game, Path.of("."))) {
            // 无 Mod 加载任何私有类 → 无重复
            assertTrue(mgr.detectDuplicateClasses().isEmpty());
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("detectDuplicateClasses: 共享类不计入重复")
    void sharedClassesAreNotDuplicates() throws Exception {
        // PARENT_FIRST 类天然在所有 Mod 间重复，属正常，不应告警。
        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoaderManager mgr = new ModClassLoaderManager(game, Path.of("."))) {
            ModClassLoader a = mgr.create(ModManifest.of("dup-a", "A", "1.0"));
            ModClassLoader b = mgr.create(ModManifest.of("dup-b", "B", "1.0"));
            a.loadModClass("org.loader.api.Mod");
            b.loadModClass("org.loader.api.Mod");

            assertTrue(mgr.detectDuplicateClasses().isEmpty(),
                    "parent-first 共享类不应被判为重复");
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("create: 重复创建同一 Mod 返回已有实例（幂等）")
    void createIsIdempotent() throws Exception {
        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoaderManager mgr = new ModClassLoaderManager(game, Path.of("."))) {
            ModManifest m = ModManifest.of("idem", "Idem", "1.0");
            ModClassLoader first = mgr.create(m);
            ModClassLoader second = mgr.create(m);
            assertSame(first, second);
            assertEquals(1, mgr.loadedModIds().size());
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("createAll: 按给定顺序创建全部")
    void createAllRespectsOrder() throws Exception {
        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoaderManager mgr = new ModClassLoaderManager(game, Path.of("."))) {
            List<ModManifest> manifests = List.of(
                    ModManifest.of("core-lib", "CoreLib", "1.0"),
                    ModManifest.of("feature", "Feature", "1.0"));
            mgr.createAll(manifests);

            assertEquals(List.of("core-lib", "feature"), mgr.loadedModIds(),
                    "创建顺序应遵循依赖拓扑顺序");
            assertTrue(mgr.has("core-lib"));
            assertTrue(mgr.has("feature"));
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("loadedClassNames 反映已注册类")
    void loadedClassNamesReflectRegistry() {
        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoaderManager mgr = new ModClassLoaderManager(game, Path.of("."))) {
            mgr.registerClassOwner("com.example.A", "mod-a");
            mgr.registerClassOwner("com.example.B", "mod-b");
            assertEquals(2, mgr.loadedClassNames().size());
            assertTrue(mgr.loadedClassNames().contains("com.example.A"));
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("close 后登记表清空")
    void closeClearsRegistry() throws Exception {
        MinecraftClassLoader game = newGameLoader();
        ModClassLoaderManager mgr = new ModClassLoaderManager(game, Path.of("."));
        mgr.registerClassOwner("com.example.A", "mod-a");
        mgr.create(ModManifest.of("close-dup", "C", "1.0"));
        mgr.close();

        assertTrue(mgr.loadedModIds().isEmpty());
        assertTrue(mgr.loadedClassNames().isEmpty());
    }

    @Test
    @DisplayName("不存在 Mod 的 ClassLoader 无 source，不产生 violation")
    void missingSourcesProduceNoViolations() throws Exception {
        Path tmp = Files.createTempDirectory("mili-dup-clean");
        MinecraftClassLoader game = newGameLoader();
        try (ModClassLoaderManager mgr = new ModClassLoaderManager(game, tmp)) {
            ModClassLoader mcl = mgr.create(ModManifest.of("absent", "A", "1.0"));
            assertTrue(mcl.modSources().isEmpty());
            assertFalse(mcl.hasViolations());
        }
        Files.deleteIfExists(tmp);
    }
}