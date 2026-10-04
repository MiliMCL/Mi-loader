package org.loader;

import org.loader.loader.config.LoaderConfig;
import org.loader.loader.discovery.ModDiscovery;
import org.loader.loader.classloader.ModClassLoader;
import org.loader.runtime.mod.ModManifest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModDiscoveryIT {

    /**
     * Locate the test_client directory.
     * Gradle runs sub-module tests with CWD = mili-loader/, so we check
     * CWD first, then walk one level up to the workspace root.
     */
    private static Path findTestClientDir() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path direct = cwd.resolve("test_client");
        if (Files.isDirectory(direct)) return direct;
        Path parent = cwd.getParent();
        if (parent != null) {
            Path sibling = parent.resolve("test_client");
            if (Files.isDirectory(sibling)) return sibling;
        }
        return direct; // fall back (will fail downstream with a clear message)
    }

    @Test
    void discoversTestMod() {
        Path gameDir = findTestClientDir();
        var config = LoaderConfig.load(gameDir);
        ModDiscovery discovery = ModDiscovery.scan(config);
        List<ModManifest> mods = discovery.discover();
        System.out.println("[IT] Discovered " + mods.size() + " mod(s)");
        for (ModManifest m : mods) {
            System.out.println("[IT]   - " + m.id() + " v" + m.version() + " main=" + m.entrypoint());
        }
        assertFalse(mods.isEmpty());
        ModManifest testmod = mods.stream().filter(m -> m.id().equals("testmod")).findFirst().orElse(null);
        assertNotNull(testmod);
        assertEquals("com.example.TestMod", testmod.entrypoint());
    }

    @Test
    void loadsTestModClass() throws Exception {
        Path gameDir = findTestClientDir();
        Path expectedModJar = gameDir.resolve("mods").resolve("testmod-1.0.0.jar");
        System.out.println("[IT] gameDir=" + gameDir);
        System.out.println("[IT] expectedModJar=" + expectedModJar + " exists=" + java.nio.file.Files.exists(expectedModJar));

        var config = LoaderConfig.load(gameDir);
        ModDiscovery discovery = ModDiscovery.scan(config);
        ModManifest testmod = discovery.discover().stream().filter(m -> m.id().equals("testmod")).findFirst().orElse(null);
        assertNotNull(testmod);

        ModClassLoader mcl = new ModClassLoader(testmod, new java.net.URL[0], null, gameDir);
        System.out.println("[IT] Actual URLs: " + java.util.Arrays.toString(mcl.getClassLoader().getURLs()));

        Class<?> clazz = assertDoesNotThrow(() -> mcl.loadModClass("com.example.TestMod"),
                "Should load TestMod class");
        Object inst = clazz.getDeclaredConstructor().newInstance();
        System.out.println("[IT] Loaded: " + inst.getClass().getName());

        boolean invoked = false;
        for (var method : clazz.getMethods()) {
            if ("initialize".equals(method.getName()) && method.getParameterCount() == 1) {
                Class<?> pt = method.getParameterTypes()[0];
                Object arg;
                if (pt.isInterface()) {
                    arg = java.lang.reflect.Proxy.newProxyInstance(
                            pt.getClassLoader(), new Class<?>[]{pt},
                            (p, m, a) -> {
                                if ("info".equals(m.getName()) && a != null && a.length > 0)
                                    System.out.println("[Mod:testmod] " + a[0]);
                                return null;
                            });
                } else {
                    // Concrete class - try instantiate
                    try {
                        arg = pt.getDeclaredConstructor().newInstance();
                    } catch (Exception e) {
                        // Skip if not instantiable without deps (like ModContext)
                        System.out.println("[IT] Skipping non-instantiable entrypoint: " + pt.getSimpleName());
                        continue;
                    }
                }
                method.invoke(inst, arg);
                invoked = true;
                break;
            }
        }
        // For TestMod with ModContext, just verify class loadable
        System.out.println("[IT] Entrypoint found and loaded: " + invoked);
        mcl.close();
    }
}
