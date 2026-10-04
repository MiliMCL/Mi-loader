package org.loader.runtime.reference;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.*;
import org.loader.runtime.mod.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the Reference Mod through its full lifecycle per REFERENCE_MOD.md.
 */
class ReferenceModTest {

    @Test
    void referenceMod_fullLifecycle() throws Exception {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("ref-mod-test");
        runtime.start();

        // Load the reference mod
        ModManifest manifest = ModManifest.of("reference-mod", "Reference Mod", "1.0");
        Mod mod = new Mod(manifest, runtime.rootScope(), ReferenceMod.class.getClassLoader());
        runtime.rootScope().registerResource(mod);

        ModContext context = new ModContext(mod);
        ReferenceMod referenceMod = new ReferenceMod(context);

        // Initialize
        referenceMod.initialize();
        assertTrue(context.getConfig("general").isPresent());
        assertEquals("true", context.getConfig("general").get().getString("enabled"));

        // Start
        referenceMod.start();
        // Wait for scheduler task to execute
        Thread.sleep(100);
        assertTrue(context.registry().get("hello-service").isPresent());
        assertEquals("Hello from reference-mod", context.registry().get("hello-service").get());

        // Activate
        referenceMod.activate();
        assertTrue(referenceMod.isActiveMod());

        // Event posting
        referenceMod.context().events().post("test-event");

        // Deactivate
        referenceMod.deactivate();
        assertFalse(referenceMod.isActiveMod());

        // Stop
        referenceMod.stop();
        assertTrue(context.registry().get("hello-service").isEmpty());

        // Runtime cleanup
        runtime.close();
        assertTrue(runtime.rootScope().isStopped());
    }

    @Test
    void referenceMod_modContext_sdkAvailable() {
        org.loader.runtime.kernel.Runtime runtime = org.loader.runtime.kernel.Runtime.create("sdk-test");
        runtime.start();

        ModManifest manifest = ModManifest.of("sdk-mod", "SDK Mod", "1.0");
        Mod mod = new Mod(manifest, runtime.rootScope(), ReferenceMod.class.getClassLoader());
        runtime.rootScope().registerResource(mod);
        ModContext context = new ModContext(mod);

        // All SDK accessors must work
        assertNotNull(context.scheduler());
        assertNotNull(context.events());
        assertNotNull(context.registry());
        assertNotNull(context.resources());
        assertNotNull(context.logger());
        assertNotNull(context.environment());
        assertEquals("SDK Mod", context.manifest().name());

        runtime.close();
    }
}
