package org.loader.loader;

import org.junit.jupiter.api.*;
import org.loader.loader.classloader.ModClassLoader;
import org.loader.loader.classloader.ModClassLoaderManager;
import org.loader.loader.config.LoaderConfig;
import org.loader.loader.discovery.MinecraftDiscovery;
import org.loader.loader.discovery.ModDiscovery;
import org.loader.loader.hook.EntryPointHook;
import org.loader.runtime.mod.ModManifest;
import org.loader.runtime.kernel.Runtime;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the loader bootstrap sequence.
 */
class LoaderBootstrapTest {

    @Test
    void loaderConfig_defaultsCorrectly() {
        LoaderConfig config = LoaderConfig.load(Path.of("."));
        assertNotNull(config);
        assertEquals(Path.of("./server.jar"), config.getMinecraftPath());
        assertEquals(Path.of("./mods"), config.getModsPath());
        assertEquals(Path.of("./libraries"), config.getLibrariesPath());
    }

    @Test
    void modClassLoader_createWithEmptyCp() {
        URL[] urls = new URL[0];
        ModManifest manifest = ModManifest.of("test", "Test", "1.0");
        ModClassLoader mcl = new ModClassLoader(manifest, urls, null);
        assertNotNull(mcl.getClassLoader());
        assertEquals("test", mcl.getManifest().id());
    }

    @Test
    void modClassLoaderManager_createsAndTracks() {
        URL[] urls = new URL[0];
        ModClassLoaderManager mgr = new ModClassLoaderManager();
        ModClassLoader mcl = mgr.createModClassLoader(ModManifest.of("a", "A", "1.0"), List.of());

        assertTrue(mgr.hasMod("a"));
        assertEquals(1, mgr.getAll().size());
    }

    @Test
    void modClassLoaderManager_closeReleasesAll() {
        ModClassLoaderManager mgr = new ModClassLoaderManager();
        mgr.createModClassLoader(ModManifest.of("x", "X", "1.0"), List.of());
        mgr.createModClassLoader(ModManifest.of("y", "Y", "1.0"), List.of());

        assertDoesNotThrow(() -> mgr.close());
        assertTrue(mgr.getAll().isEmpty());
    }

    @Test
    void entryPointHook_initialState() {
        EntryPointHook hook = new EntryPointHook();
        assertFalse(hook.isInstalled());
        assertNull(hook.getBootstrap());
    }
}
