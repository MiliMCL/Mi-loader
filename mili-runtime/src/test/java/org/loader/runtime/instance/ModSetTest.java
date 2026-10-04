package org.loader.runtime.instance;

import org.junit.jupiter.api.*;
import org.loader.runtime.mod.ModManifest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ModSet.
 */
class ModSetTest {

    @Test
    void modSet_holdsResolvedMods() {
        ModManifest modA = ModManifest.of("modA", "Mod A", "1.0");
        ModManifest modB = ModManifest.of("modB", "Mod B", "2.0");

        ModSet set = new ModSet("test-instance", List.of(modA, modB));

        assertEquals("test-instance", set.instanceId());
        assertEquals(2, set.size());
        assertFalse(set.isEmpty());
        assertTrue(set.contains("modA"));
        assertTrue(set.contains("modB"));
        assertEquals(List.of("modA", "modB"), set.modIds());
    }

    @Test
    void modSet_findById() {
        ModManifest mod = ModManifest.of("findable", "Findable Mod", "1.0");
        ModSet set = new ModSet("inst", List.of(mod));

        assertTrue(set.findById("findable").isPresent());
        assertTrue(set.findById("missing").isEmpty());
    }

    @Test
    void modSet_emptySet() {
        ModSet set = new ModSet("empty-inst", List.of());

        assertTrue(set.isEmpty());
        assertEquals(0, set.size());
    }

    @Test
    void modSet_immutableAfterConstruction() {
        ModManifest mod1 = ModManifest.of("m1", "M1", "1.0");
        List<ModManifest> mutableList = new java.util.ArrayList<>();
        mutableList.add(mod1);

        ModSet set = new ModSet("immutable-test", mutableList);
        ModManifest mod2 = ModManifest.of("m2", "M2", "1.0");
        mutableList.add(mod2);

        // ModSet should be a copy - adding to original list shouldn't affect it
        assertEquals(1, set.size());
        assertFalse(set.contains("m2"));
    }
}
