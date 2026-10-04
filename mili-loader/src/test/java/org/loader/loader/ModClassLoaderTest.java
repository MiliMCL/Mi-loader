package org.loader.loader;

import org.junit.jupiter.api.*;
import org.loader.loader.classloader.ModClassLoader;
import org.loader.runtime.mod.ModManifest;

import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ModClassLoader.
 */
class ModClassLoaderTest {

    @Test
    void modClassLoader_canBeCreated() {
        URL[] emptyCp = new URL[0];
        ModManifest manifest = ModManifest.of("test-mod", "Test", "1.0");
        ModClassLoader mcl = new ModClassLoader(manifest, emptyCp, null);

        assertNotNull(mcl);
        assertEquals("test-mod", mcl.getManifest().id());
        assertNotNull(mcl.getClassLoader());
    }

    @Test
    void modClassLoader_isolation_independentInstances() {
        URL[] emptyCp = new URL[0];
        ModManifest m1 = ModManifest.of("mod-a", "A", "1.0");
        ModManifest m2 = ModManifest.of("mod-b", "B", "1.0");

        ModClassLoader cl1 = new ModClassLoader(m1, emptyCp, null);
        ModClassLoader cl2 = new ModClassLoader(m2, emptyCp, null);

        assertNotSame(cl1.getClassLoader(), cl2.getClassLoader());
    }

    @Test
    void modClassLoader_closeReleases() {
        URL[] emptyCp = new URL[0];
        ModManifest manifest = ModManifest.of("close-mod", "CloseMod", "1.0");
        ModClassLoader mcl = new ModClassLoader(manifest, emptyCp, null);

        assertDoesNotThrow(() -> mcl.close());
    }
}
