package org.loader.runtime.instance;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.*;
import org.loader.runtime.mod.ModManifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ModManager and ModSources.
 */
class ModManagerTest {

    @Test
    void modSources_listOf_returnsStaticList() {
        ModManifest modA = ModManifest.of("modA", "Mod A", "1.0");
        ModManifest modB = ModManifest.of("modB", "Mod B", "2.0");

        var source = org.loader.runtime.instance.ModSources.listOf(List.of(modA, modB));
        List<ModManifest> result = source.scan();

        assertEquals(2, result.size());
        assertTrue(result.contains(modA));
        assertTrue(result.contains(modB));
    }

    @Test
    void modSources_listOf_isImmutable() {
        List<ModManifest> original = new ArrayList<>();
        original.add(ModManifest.of("m1", "M1", "1.0"));

        var source = ModSources.listOf(original);
        original.add(ModManifest.of("m2", "M2", "1.0"));

        // Source should reflect the snapshot taken at creation
        assertEquals(1, source.scan().size());
    }

    @Test
    void modSources_composite_deduplicates() {
        ModManifest a = ModManifest.of("shared", "Shared", "1.0");
        ModManifest b = ModManifest.of("shared", "Shared", "1.0");
        ModManifest c = ModManifest.of("unique", "Unique", "1.0");

        var source = ModSources.composite(List.of(
                ModSources.listOf(List.of(a)),
                ModSources.listOf(List.of(b, c))
        ));

        List<ModManifest> result = source.scan();
        assertEquals(2, result.size());
    }

    @Test
    void modSources_directory_nonExistentDir_returnsEmpty() {
        var source = ModSources.directory(java.nio.file.Path.of("/nonexistent/path/xyz"));
        assertTrue(source.scan().isEmpty());
    }

    @Test
    void modManager_discoverMods() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("discover-test");
        runtime.start();

        ModManifest modX = ModManifest.of("modX", "Mod X", "1.0");
        ModManifest modY = ModManifest.of("modY", "Mod Y", "2.0");

        ModManager manager = new ModManager("test-mgr", runtime.rootScope());
        runtime.rootScope().registerResource(manager);

        manager.addSource(ModSources.listOf(List.of(modX, modY)));

        List<ModManifest> available = manager.availableMods();
        assertEquals(2, available.size());

        runtime.close();
    }

    @Test
    void modManager_findAvailableMod() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("find-test");
        runtime.start();

        ModManifest target = ModManifest.of("target", "Target", "1.0");
        ModManager manager = new ModManager("find-mgr", runtime.rootScope());
        runtime.rootScope().registerResource(manager);
        manager.addSource(ModSources.listOf(List.of(target)));

        Optional<ModManifest> found = manager.findAvailable("target");
        assertTrue(found.isPresent());
        assertEquals("target", found.get().id());

        assertTrue(manager.findAvailable("nonexistent").isEmpty());

        runtime.close();
    }

    @Test
    void modManager_resolve_validModIds() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("resolve-test");
        runtime.start();

        ModManifest a = ModManifest.of("a", "A", "1.0");
        ModManifest b = ModManifest.of("b", "B", "2.0");

        ModManager manager = new ModManager("resolve-mgr", runtime.rootScope());
        runtime.rootScope().registerResource(manager);
        manager.addSource(ModSources.listOf(List.of(a, b)));

        Instance instance = Instance.builder("inst-1").addMod("a").addMod("b").build();
        ModResolutionResult result = manager.resolve(instance);

        assertTrue(result.success());
        assertEquals(2, result.resolvedMods().size());
        assertTrue(result.missingDependencies().isEmpty());

        runtime.close();
    }

    @Test
    void modManager_resolve_missingDependency() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("missing-dep-test");
        runtime.start();

        ModManager manager = new ModManager("missing-mgr", runtime.rootScope());
        runtime.rootScope().registerResource(manager);

        // Only mod "a" is available, but instance requests "a" and "missing"
        ModManifest a = ModManifest.of("a", "A", "1.0");
        manager.addSource(ModSources.listOf(List.of(a)));

        Instance instance = Instance.builder("inst").addMod("a").addMod("missing").build();
        ModResolutionResult result = manager.resolve(instance);

        assertFalse(result.success());
        assertFalse(result.missingDependencies().isEmpty());

        runtime.close();
    }

    @Test
    void modManager_resolve_withDependencyOrder() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("dep-order-test");
        runtime.start();

        // modC depends on modB, modB depends on modA
        ModManifest modA = ModManifest.of("modA", "A", "1.0");
        ModManifest modB = new ModManifest(
                "modB", "B", "1.0", "", "",
                List.of(new ModManifest.DependencyEntry("modA", "*", true)),
                List.of(), "", List.of());
        ModManifest modC = new ModManifest(
                "modC", "C", "1.0", "", "",
                List.of(new ModManifest.DependencyEntry("modB", "*", true)),
                List.of(), "", List.of());

        ModManager manager = new ModManager("deporder-mgr", runtime.rootScope());
        runtime.rootScope().registerResource(manager);
        manager.addSource(ModSources.listOf(List.of(modC, modB, modA)));

        // Request only modC — should pull in modB and modA
        Instance instance = Instance.builder("inst").addMod("modC").build();
        ModResolutionResult result = manager.resolve(instance);

        assertTrue(result.success());
        List<String> ids = new ArrayList<>();
        for (ModManifest m : result.resolvedMods()) {
            ids.add(m.id());
        }
        // Dependencies must come before dependents
        assertTrue(ids.indexOf("modA") < ids.indexOf("modB"));
        assertTrue(ids.indexOf("modB") < ids.indexOf("modC"));

        runtime.close();
    }

    @Test
    void modManager_createModSet_success() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("modset-test");
        runtime.start();

        ModManifest a = ModManifest.of("a", "A", "1.0");
        ModManager manager = new ModManager("modset-mgr", runtime.rootScope());
        runtime.rootScope().registerResource(manager);
        manager.addSource(ModSources.listOf(List.of(a)));

        Instance instance = Instance.builder("inst").addMod("a").build();
        ModSet modSet = manager.createModSet(instance);

        assertNotNull(modSet);
        assertEquals("inst", modSet.instanceId());
        assertEquals(1, modSet.size());
        assertTrue(modSet.contains("a"));

        runtime.close();
    }

    @Test
    void modManager_createModSet_failureThrows() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("modset-fail-test");
        runtime.start();

        ModManager manager = new ModManager("modsetfail-mgr", runtime.rootScope());
        runtime.rootScope().registerResource(manager);
        // No sources — no mods available

        Instance instance = Instance.builder("inst").addMod("nonexistent").build();

        assertThrows(ModManagerException.class, () -> manager.createModSet(instance));

        runtime.close();
    }

    @Test
    void modManager_cachedResolution() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("cache-test");
        runtime.start();

        ModManifest a = ModManifest.of("a", "A", "1.0");
        ModManager manager = new ModManager("cache-mgr", runtime.rootScope());
        runtime.rootScope().registerResource(manager);
        manager.addSource(ModSources.listOf(List.of(a)));

        Instance instance = Instance.builder("cached").addMod("a").build();
        manager.resolve(instance);

        Optional<ModResolutionResult> cached = manager.cachedResolution("cached");
        assertTrue(cached.isPresent());
        assertTrue(cached.get().success());

        runtime.close();
    }

    @Test
    void modManager_loadIntoRuntime() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("load-test");
        runtime.start();

        ModManifest a = ModManifest.of("loadable", "Loadable Mod", "1.0");
        ModManager manager = new ModManager("load-mgr", runtime.rootScope());
        runtime.rootScope().registerResource(manager);
        manager.addSource(ModSources.listOf(List.of(a)));

        ModSet modSet = manager.createModSet(Instance.builder("load-inst").addMod("loadable").build());
        List<org.loader.runtime.mod.Mod> loaded = manager.load(runtime, modSet);

        assertEquals(1, loaded.size());
        assertEquals("loadable", loaded.get(0).id());

        runtime.close();
    }

    @Test
    void modManager_close_preventsOperations() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("close-test");
        runtime.start();

        ModManager manager = new ModManager("close-mgr", runtime.rootScope());
        runtime.rootScope().registerResource(manager);
        manager.addSource(ModSources.listOf(List.of(ModManifest.of("x", "X", "1.0"))));
        manager.close();

        assertThrows(IllegalStateException.class, manager::availableMods);

        runtime.close();
    }

    @Test
    void resolutionResult_summary_success() {
        var result = ModResolutionResult.success("test", List.of(
                ModManifest.of("a", "A", "1.0")
        ));

        assertTrue(result.success());
        assertTrue(result.summary().contains("Resolved 1 mods"));
    }

    @Test
    void resolutionResult_summary_failed() {
        var result = ModResolutionResult.failed("test",
                List.of("missing-mod"), List.of());

        assertFalse(result.success());
        assertTrue(result.summary().contains("failed"));
        assertTrue(result.summary().contains("missing-mod"));
    }
}
