package org.loader;

import org.loader.loader.config.LoaderConfig;
import org.loader.loader.discovery.ModDiscovery;
import org.loader.loader.classloader.ModClassLoader;
import org.loader.runtime.mod.ModManifest;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the full Mod discovery + load pipeline.
 *
 * Test JARs are resolved with a three-tier fallback so the test runs both
 * in a developer workspace (where the real test_client/ checkout exists)
 * and in CI (where only src/test/resources fixtures are available):
 *
 * <ol>
 *   <li>System property {@code mili.it.clientDir} — explicit override.</li>
 *   <li>A {@code test_client/} directory relative to CWD or one level up
 *       (developer workspace with built testmod/badmod).</li>
 *   <li>Embedded classpath fixtures under {@code client-fixtures/mods/}
 *       extracted to a temporary directory (CI).</li>
 * </ol>
 *
 * The last tier means CI never needs a pre-built test_client; it ships
 * with the build.
 */
class ModDiscoveryIT {

    private static final String SYSTEM_PROPERTY = "mili.it.clientDir";
    // NOTE: ClassLoader.getResourceAsStream uses paths without a leading slash.
    private static final String FIXTURE_RESOURCE_DIR = "client-fixtures/mods";

    private static Path resolveClientDir() {
        String override = System.getProperty(SYSTEM_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }

        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

        Path direct = cwd.resolve("test_client");
        if (Files.isDirectory(direct) && hasMods(direct)) return direct;

        Path parent = cwd.getParent();
        if (parent != null) {
            Path sibling = parent.resolve("test_client");
            if (Files.isDirectory(sibling) && hasMods(sibling)) return sibling;
        }

        // CI fallback: extract embedded fixtures into a temp dir.
        return extractFixturesIntoTemp();
    }

    private static boolean hasMods(Path gameDir) {
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) return false;
        try (var stream = Files.list(mods)) {
            return stream.anyMatch(p -> p.toString().endsWith(".jar"));
        } catch (Exception e) {
            return false;
        }
    }

    private static Path extractFixturesIntoTemp() {
        try {
            Path tmp = Files.createTempDirectory("mili-it-client-");
            tmp.toFile().deleteOnExit();
            Path modsDir = tmp.resolve("mods");
            Files.createDirectories(modsDir);

            String[] fixtures = {"testmod.jar", "badmod.jar"};
            ClassLoader cl = ModDiscoveryIT.class.getClassLoader();
            for (String name : fixtures) {
                String resource = FIXTURE_RESOURCE_DIR + "/" + name;
                InputStream is = cl.getResourceAsStream(resource);
                assertNotNull(is, "Embedded fixture not found on classpath: " + resource);
                Path dest = modsDir.resolve(name);
                try (is) {
                    Files.copy(is, dest);
                }
                dest.toFile().deleteOnExit();
            }
            return tmp;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to extract embedded client fixtures", e);
        }
    }

    @Test
    void discoversTestMod() {
        Path gameDir = resolveClientDir();
        System.out.println("[IT] gameDir=" + gameDir);
        var config = LoaderConfig.load(gameDir);
        ModDiscovery discovery = ModDiscovery.scan(config);
        List<ModManifest> mods = discovery.discover();
        System.out.println("[IT] Discovered " + mods.size() + " mod(s)");
        for (ModManifest m : mods) {
            System.out.println("[IT]   - " + m.id() + " v" + m.version() + " entrypoint=" + m.entrypoint());
        }
        assertFalse(mods.isEmpty(), "Should discover at least one mod in " + gameDir);

        ModManifest testmod = mods.stream().filter(m -> m.id().equals("testmod")).findFirst().orElse(null);
        assertNotNull(testmod, "Expected to discover 'testmod'");
        assertEquals("com.example.TestMod", testmod.entrypoint());
    }

    @Test
    void loadsTestModClass() throws Exception {
        Path gameDir = resolveClientDir();
        System.out.println("[IT] gameDir=" + gameDir);

        var config = LoaderConfig.load(gameDir);
        ModDiscovery discovery = ModDiscovery.scan(config);
        List<ModManifest> mods = discovery.discover();
        ModManifest testmod = mods.stream().filter(m -> m.id().equals("testmod")).findFirst().orElse(null);
        assertNotNull(testmod, "Expected to discover 'testmod'");

        // Locate the actual JAR that ModDiscovery bound to this manifest.
        // ModClassLoader 的 parent 必须是 MinecraftClassLoader（唯一的 MC 类来源）。
        var gameCL = new org.loader.loader.classloader.MinecraftClassLoader(
                "minecraft-game", new java.net.URL[0], getClass().getClassLoader());
        ModClassLoader mcl = new ModClassLoader(testmod, gameCL, gameDir);
        System.out.println("[IT] Actual URLs: " + java.util.Arrays.toString(mcl.getURLs()));

        Class<?> clazz = assertDoesNotThrow(() -> mcl.loadModClass("com.example.TestMod"),
                "Should load TestMod class");
        Object inst = clazz.getDeclaredConstructor().newInstance();
        System.out.println("[IT] Loaded: " + inst.getClass().getName());

        // Look for the single entrypoint method.
        var methods = clazz.getMethods();
        long initializeMethods = java.util.Arrays.stream(methods)
                .filter(m -> "initialize".equals(m.getName()) && m.getParameterCount() == 1)
                .count();
        assertTrue(initializeMethods >= 1, "TestMod should expose initialize(ModContext)");

        System.out.println("[IT] Entrypoint OK");
        mcl.close();
    }
}
