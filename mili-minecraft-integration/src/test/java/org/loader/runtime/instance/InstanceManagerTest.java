package org.loader.runtime.instance;

import org.junit.jupiter.api.*;
import org.loader.runtime.RuntimeEnvironment;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Instance and InstanceManager.
 */
class InstanceManagerTest {

    @Test
    void instance_builderCreatesValidInstance() {
        Instance instance = Instance.builder("test-world")
                .minecraftVersion("1.21.4")
                .environment(RuntimeEnvironment.DEDICATED_SERVER)
                .addMod("optifine")
                .addMod("jei")
                .configuration("memory", "4G")
                .displayName("Test Server")
                .build();

        assertEquals("test-world", instance.instanceId());
        assertEquals("1.21.4", instance.minecraftVersion());
        assertEquals(RuntimeEnvironment.DEDICATED_SERVER, instance.environment());
        assertEquals(2, instance.modIds().size());
        assertTrue(instance.modIds().contains("optifine"));
        assertTrue(instance.modIds().contains("jei"));
        assertEquals(Optional.of("4G"), instance.config("memory"));
        assertEquals("Test Server", instance.displayName());
    }

    @Test
    void instance_defaultValues() {
        Instance instance = Instance.builder("default-test").build();

        assertEquals("default-test", instance.instanceId());
        assertEquals("26.2", instance.minecraftVersion());
        assertEquals(RuntimeEnvironment.DEDICATED_SERVER, instance.environment());
        assertTrue(instance.modIds().isEmpty());
        assertEquals("default-test", instance.displayName());
    }

    @Test
    void instance_nullFields_throw() {
        // Builder is permissive; validation happens at build() time
        assertThrows(NullPointerException.class, () -> Instance.builder(null).build());
        assertThrows(NullPointerException.class, () -> Instance.builder("id").minecraftVersion(null).build());
        assertThrows(NullPointerException.class, () -> Instance.builder("id").environment(null).build());
    }

    @Test
    void instance_equalityBasedOnId() {
        Instance a = Instance.builder("same-id").displayName("A").build();
        Instance b = Instance.builder("same-id").displayName("B").build();
        Instance c = Instance.builder("different-id").build();

        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void instanceManager_createAndFind() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("mgr-test");
        runtime.start();

        InstanceManager manager = new InstanceManager("test-manager", runtime.rootScope());
        runtime.rootScope().registerResource(manager);

        Instance instance = Instance.builder("world-1")
                .environment(RuntimeEnvironment.DEDICATED_SERVER)
                .build();

        org.loader.runtime.kernel.Scope scope = manager.createInstance(instance);
        assertNotNull(scope);

        assertEquals(1, manager.instanceCount());
        assertTrue(manager.findInstance("world-1").isPresent());
        assertEquals("world-1", manager.findInstance("world-1").get().instanceId());
        assertTrue(manager.findInstance("nonexistent").isEmpty());

        runtime.close();
    }

    @Test
    void instanceManager_duplicateInstance_throws() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("dup-test");
        runtime.start();

        InstanceManager manager = new InstanceManager("dup-manager", runtime.rootScope());
        runtime.rootScope().registerResource(manager);

        Instance instance = Instance.builder("dup").build();
        manager.createInstance(instance);

        assertThrows(IllegalArgumentException.class, () -> manager.createInstance(instance));

        runtime.close();
    }

    @Test
    void instanceManager_startAndStop() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("startstop-test");
        runtime.start();

        InstanceManager manager = new InstanceManager("ss-manager", runtime.rootScope());
        runtime.rootScope().registerResource(manager);

        Instance instance = Instance.builder("ss-world")
                .environment(RuntimeEnvironment.SERVER)
                .build();

        manager.createInstance(instance);
        manager.startInstance("ss-world");

        assertTrue(manager.isRunning("ss-world"));
        assertEquals(Optional.of("ss-world"), manager.activeInstanceId());

        manager.stopInstance("ss-world");
        assertFalse(manager.isRunning("ss-world"));

        runtime.close();
    }

    @Test
    void instanceManager_removeInstance() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("remove-test");
        runtime.start();

        InstanceManager manager = new InstanceManager("remove-manager", runtime.rootScope());
        runtime.rootScope().registerResource(manager);

        Instance instance = Instance.builder("to-remove").build();
        manager.createInstance(instance);
        manager.startInstance("to-remove");

        assertTrue(manager.removeInstance("to-remove"));
        assertEquals(0, manager.instanceCount());
        assertTrue(manager.findInstance("to-remove").isEmpty());
        assertFalse(manager.removeInstance("to-remove"));

        runtime.close();
    }

    @Test
    void instanceManager_findNonExistent_throwsOnStart() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("notfound-test");
        runtime.start();

        InstanceManager manager = new InstanceManager("nf-manager", runtime.rootScope());
        runtime.rootScope().registerResource(manager);

        assertThrows(java.util.NoSuchElementException.class,
                () -> manager.startInstance("nonexistent"));

        runtime.close();
    }

    @Test
    void instanceManager_instanceScope() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("scope-test");
        runtime.start();

        InstanceManager manager = new InstanceManager("scope-manager", runtime.rootScope());
        runtime.rootScope().registerResource(manager);

        Instance instance = Instance.builder("scoped").build();
        org.loader.runtime.kernel.Scope createdScope = manager.createInstance(instance);

        Optional<org.loader.runtime.kernel.Scope> retrieved = manager.instanceScope("scoped");
        assertTrue(retrieved.isPresent());

        runtime.close();
    }
}

